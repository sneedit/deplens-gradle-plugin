#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Sneed IT
# SPDX-License-Identifier: Apache-2.0
"""Exercise signed Central staging with a disposable local PGP key; no network publication."""
import argparse
import importlib.util
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def load(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts' / (name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('gradle', help='Gradle executable (wrapper or installed distribution)')
    args = parser.parse_args()
    if not shutil.which('gpg'):
        parser.error('GnuPG is required for the disposable signing fixture')
    gradle = str(Path(args.gradle).resolve())
    base = ROOT / 'build/central-verification'
    base.mkdir(parents=True, exist_ok=True)
    output = Path(tempfile.mkdtemp(prefix='run-', dir=base))
    repository = output / 'repository'
    central = load('publish-central')
    release = load('test-release')
    env = dict(os.environ)
    for name in list(env):
        if name.startswith('ORG_GRADLE_PROJECT_signing') or name in (
                'GRADLE_PUBLISH_KEY', 'GRADLE_PUBLISH_SECRET', 'MAVEN_CENTRAL_USERNAME',
                'MAVEN_CENTRAL_PASSWORD', 'SIGNING_KEY', 'SIGNING_PASSWORD',
                'DEVELOCITY_TERMS_OF_USE_AGREE'):
            env.pop(name, None)
    # Reuse dependency downloads, but exclude the user's credentials and init scripts.
    original_home = Path(env.get('GRADLE_USER_HOME', Path.home() / '.gradle'))
    fixture_home = output / 'gradle-home'
    fixture_home.mkdir()
    for name in ('caches', 'wrapper', 'jdks'):
        if (original_home / name).exists():
            (fixture_home / name).symlink_to(original_home / name, target_is_directory=True)
    env['GRADLE_USER_HOME'] = str(fixture_home)

    def run(label, arguments, expect_failure=False):
        with (output / (label + '.log')).open('w') as log:
            result = subprocess.run([gradle, *arguments, '--offline', '--no-scan',
                                     '--no-configuration-cache', '--no-daemon'],
                                    cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
        if (result.returncode == 0) == expect_failure:
            raise ValueError(label + ' failed; see ' + str(output / (label + '.log')))
        print(label + ': passed', flush=True)
        return (output / (label + '.log')).read_text()

    arguments = [':plugin:publishAllPublicationsToCentralStagingRepository', '-Pversion=0.1.0',
                 '-PcentralStagingDirectory=' + str(repository)]
    text = run('reject-unsigned-staging', arguments, expect_failure=True)
    if 'Maven Central staging requires' not in text:
        raise ValueError('Unsigned staging failed for an unrelated reason')
    if list(repository.glob('**/*.pom')):
        raise ValueError('Unsigned publication reached the staging repository')

    with tempfile.TemporaryDirectory(prefix='deplens-pgp-') as key_directory:
        key_home = Path(key_directory)
        key_home.chmod(0o700)
        password = secrets.token_hex(20)
        gpg = ['gpg', '--no-options', '--homedir', str(key_home), '--batch', '--pinentry-mode', 'loopback']

        def key_command(arguments, export=False):
            result = subprocess.run([*gpg, '--passphrase-fd', '0', *arguments],
                                    input=(password + '\n').encode(),
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            if result.returncode:
                raise ValueError('Disposable PGP fixture operation failed')
            return result.stdout if export else None

        try:
            key_command(['--quick-generate-key', 'DepLens local test <deplens-test@example.invalid>',
                         'rsa2048', 'sign', '1d'])
            key = key_command(['--armor', '--export-secret-keys'], export=True)
            env['ORG_GRADLE_PROJECT_signingInMemoryKey'] = key.decode()
            env['ORG_GRADLE_PROJECT_signingInMemoryKeyPassword'] = password
            run('stage-signed-release', [*arguments, '-PsignPublications=true'])
            repository_url = next(line.split('=', 1)[1] for line in (ROOT / 'gradle.properties').read_text().splitlines()
                                  if line.startswith('repositoryUrl='))
            release.verify_publications(repository, '0.1.0', repository_url)
            for name in central.artifact_paths('0.1.0'):
                result = subprocess.run([*gpg, '--no-auto-key-retrieve', '--verify',
                                         str(repository / (name + '.asc')), str(repository / name)],
                                        stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                if result.returncode:
                    raise ValueError('Invalid artifact signature: ' + name)
            bundle = central.create_bundle(repository, '0.1.0', output / 'central-bundle.zip')
            print('All seven artifacts have valid PGP signatures and checksums.', flush=True)
            print('Signed Central bundle verified locally: ' + str(bundle), flush=True)
        finally:
            env.pop('ORG_GRADLE_PROJECT_signingInMemoryKey', None)
            env.pop('ORG_GRADLE_PROJECT_signingInMemoryKeyPassword', None)
            subprocess.run(['gpgconf', '--homedir', str(key_home), '--kill', 'gpg-agent'],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
    print('Central signing verification passed; no upload or remote publication performed.', flush=True)


if __name__ == '__main__':
    main()
