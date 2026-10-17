#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Sneed IT
# SPDX-License-Identifier: Apache-2.0
"""Package signed local publications, or explicitly upload them to the Central Portal."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import HTTPRedirectHandler, Request, build_opener
import uuid
import zipfile

BASE = 'https://central.sonatype.com/api/v1/publisher/'
PLUGIN_IDS = ('com.sneedit.deplens', 'com.sneedit.deplens.settings')
VERSION = re.compile(r'(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)')


class CentralError(RuntimeError):
    def __init__(self, message, retryable=False):
        super().__init__(message)
        self.retryable = retryable


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # Never forward the publishing token to a redirected destination.
        return None


def artifact_paths(version):
    if not VERSION.fullmatch(version):
        raise ValueError('Expected a stable release version X.Y.Z')
    prefix = f'com/sneedit/deplens/plugin/{version}/plugin-{version}'
    paths = [prefix + suffix for suffix in ('.pom', '.jar', '-sources.jar', '-javadoc.jar', '.module')]
    paths.extend(f'{plugin.replace(".", "/")}/{plugin}.gradle.plugin/{version}/'
                 f'{plugin}.gradle.plugin-{version}.pom' for plugin in PLUGIN_IDS)
    return paths


def bundle_entries(version):
    return [name + suffix for name in artifact_paths(version) for suffix in ('', '.asc', '.md5', '.sha1')]


def verify_contents(read, version):
    for name in artifact_paths(version):
        data = read(name)
        if not data:
            raise ValueError('Empty release artifact: ' + name)
        signature = read(name + '.asc')
        if not signature.startswith(b'-----BEGIN PGP SIGNATURE-----'):
            raise ValueError('Missing armored PGP signature: ' + name)
        for algorithm in ('md5', 'sha1'):
            expected = hashlib.new(algorithm, data).hexdigest()
            actual = read(name + '.' + algorithm).decode('ascii').strip()
            if actual != expected:
                raise ValueError('Checksum mismatch: ' + name + '.' + algorithm)


def create_bundle(repository, version, output):
    repository = Path(repository).resolve()

    def read(name):
        file = repository / name
        if file.is_symlink() or not file.resolve().is_relative_to(repository):
            raise ValueError('Release artifacts must be regular files inside the staging repository')
        return file.read_bytes()

    verify_contents(read, version)
    output = Path(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_DEFLATED) as bundle:
        for name in bundle_entries(version):
            entry = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.external_attr = 0o100644 << 16
            bundle.writestr(entry, read(name))
    verify_bundle(output, version)
    return output


def verify_bundle(file, version):
    with zipfile.ZipFile(file) as bundle:
        expected = bundle_entries(version)
        if sorted(bundle.namelist()) != sorted(expected):
            raise ValueError('Bundle must contain exactly this version of all three signed publications')
        verify_contents(bundle.read, version)


def credentials():
    names = ('MAVEN_CENTRAL_USERNAME', 'MAVEN_CENTRAL_PASSWORD')
    missing = [name for name in names if not os.environ.get(name)]
    if missing:
        raise ValueError('Missing required environment variables: ' + ', '.join(missing))
    value = ':'.join(os.environ[name] for name in names)
    return base64.b64encode(value.encode()).decode('ascii')


def request(endpoint, token, data=b'', content_type='application/json'):
    req = Request(BASE + endpoint, data=data, method='POST', headers={
        'Authorization': 'Bearer ' + token,
        'Content-Type': content_type,
        'User-Agent': 'DepLens-release-publisher/1',
    })
    try:
        with build_opener(NoRedirect()).open(req, timeout=60) as response:
            return response.read()
    except HTTPError as error:
        # Responses can contain credentials or arbitrary server text: never echo them.
        raise CentralError(f'Central Portal request failed (HTTP {error.code})',
                           retryable=error.code == 429 or error.code >= 500) from None
    except (URLError, TimeoutError, OSError):
        raise CentralError('Central Portal request failed (transport error)', retryable=True) from None


def save_result(file, deployment_id, state):
    file = Path(file)
    file.parent.mkdir(parents=True, exist_ok=True)
    file.write_text(json.dumps({'deploymentId': deployment_id, 'deploymentState': state}, indent=2) + '\n')


def valid_deployment_id(value):
    try:
        return str(uuid.UUID(value))
    except (ValueError, AttributeError):
        raise ValueError('Central Portal did not return a valid deployment ID') from None


def await_published(deployment_id, token, result, timeout=2400, interval=10):
    deployment_id = valid_deployment_id(deployment_id)
    deadline = time.monotonic() + timeout
    previous = None
    while time.monotonic() < deadline:
        try:
            raw = request('status?' + urlencode({'id': deployment_id}), token)
        except CentralError as error:
            if not error.retryable:
                raise
            print('Central Portal status temporarily unavailable; retrying.', flush=True)
        else:
            try:
                status = json.loads(raw)
                state = status['deploymentState']
            except (ValueError, KeyError, TypeError):
                raise CentralError('Central Portal returned an invalid status response') from None
            if status.get('deploymentId') != deployment_id:
                raise CentralError('Central Portal returned a status for an unexpected deployment')
            if state not in ('PENDING', 'VALIDATING', 'VALIDATED', 'PUBLISHING', 'PUBLISHED', 'FAILED'):
                raise CentralError('Central Portal returned an unknown deployment state')
            save_result(result, deployment_id, state)
            if state != previous:
                print(f'Maven Central deployment {deployment_id}: {state}', flush=True)
                previous = state
            if state == 'PUBLISHED':
                return
            if state == 'FAILED':
                raise CentralError(f'Maven Central validation failed; inspect deployment {deployment_id} in the Central Portal')
        time.sleep(min(interval, max(0, deadline - time.monotonic())))
    raise CentralError(f'Timed out waiting for Maven Central publication; resume status checks for deployment {deployment_id}')


def publish(bundle, version, result, timeout=2400):
    verify_bundle(bundle, version)
    token = credentials()
    boundary = 'deplens-' + uuid.uuid4().hex
    body = (f'--{boundary}\r\nContent-Disposition: form-data; name="bundle"; filename="deplens-{version}.zip"\r\n'
            'Content-Type: application/octet-stream\r\n\r\n').encode()
    body += Path(bundle).read_bytes() + f'\r\n--{boundary}--\r\n'.encode()
    endpoint = 'upload?' + urlencode({'name': 'DepLens ' + version, 'publishingType': 'AUTOMATIC'})
    # Do not retry uploads: a transport failure may occur after Central accepted the bundle.
    response = request(endpoint, token, body, 'multipart/form-data; boundary=' + boundary)
    try:
        deployment_id = valid_deployment_id(response.decode('ascii').strip())
    except (ValueError, UnicodeError):
        raise CentralError('Central Portal returned no valid deployment ID; inspect the Portal before retrying the upload') from None
    save_result(result, deployment_id, 'UPLOADED')
    print('Uploaded Maven Central deployment: ' + deployment_id, flush=True)
    await_published(deployment_id, token, result, timeout=timeout)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    bundle = commands.add_parser('bundle', help='Create and validate a ZIP using local files only')
    bundle.add_argument('repository', type=Path)
    bundle.add_argument('--version', required=True)
    bundle.add_argument('--output', type=Path, required=True)
    upload = commands.add_parser('publish', help='Upload a bundle and wait for PUBLISHED (external publication)')
    upload.add_argument('bundle', type=Path)
    upload.add_argument('--version', required=True)
    upload.add_argument('--result', type=Path, default=Path('build/central-publication.json'))
    upload.add_argument('--timeout', type=int, default=2400)
    status = commands.add_parser('status', help='Resume status checks without uploading another bundle')
    status.add_argument('deployment_id')
    status.add_argument('--result', type=Path, default=Path('build/central-publication.json'))
    status.add_argument('--timeout', type=int, default=2400)
    args = parser.parse_args()
    if args.command != 'bundle' and args.timeout <= 0:
        parser.error('--timeout must be a positive number of seconds')
    try:
        if args.command == 'bundle':
            output = create_bundle(args.repository, args.version, args.output)
            print('Signed Central bundle verified (local files only): ' + str(output))
        elif args.command == 'publish':
            publish(args.bundle, args.version, args.result, timeout=args.timeout)
        else:
            await_published(args.deployment_id, credentials(), args.result, timeout=args.timeout)
    except (ValueError, OSError, zipfile.BadZipFile, CentralError) as error:
        print(str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
