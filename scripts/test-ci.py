#!/usr/bin/env python3
"""Regression checks for CI failures being reported as success."""
import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import zipfile
from unittest.mock import patch
from urllib.error import HTTPError


def load(name):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).parent / (name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


report = load('ci-report')
release = load('release-version')
isolated = load('test-isolated-example')
matrix = load('test-ci-matrix')
worker = load('ci-matrix/run')
release_artifacts = load('test-release')
central = load('publish-central')


class ReleaseScriptChecks(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.repository = self.root / 'checkout'
        self.remote = self.root / 'remote.git'
        self.env = {key: value for key, value in os.environ.items()
                    if not key.startswith('GIT_')}
        self.env.update(GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL=os.devnull,
                        GIT_TERMINAL_PROMPT='0')
        self.git('init', '--bare', str(self.remote), cwd=self.root)
        self.git('init', '-b', 'main', str(self.repository), cwd=self.root)
        self.git('config', 'user.name', 'Release Test')
        self.git('config', 'user.email', 'release@example.invalid')
        scripts = self.repository / 'scripts'
        scripts.mkdir()
        for name in ('release.sh', 'release-version.py'):
            shutil.copy2(Path(__file__).parent / name, scripts / name)
        self.git('add', '.')
        self.git('commit', '-m', 'Initial commit')
        self.git('remote', 'add', 'origin', str(self.remote))
        self.git('push', '-u', 'origin', 'main')

    def git(self, *args, cwd=None):
        return subprocess.run(['git', *args], cwd=cwd or self.repository,
                              env=self.env, check=True, capture_output=True, text=True).stdout.strip()

    def release(self, *args):
        return subprocess.run([str(self.repository / 'scripts/release.sh'), *args],
                              cwd=self.root, env=self.env, capture_output=True, text=True)

    def assert_rejected(self, message, *args):
        result = self.release(*args)
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertIn(message, result.stdout + result.stderr)
        self.assertEqual('', self.git('ls-remote', '--tags', 'origin'))

    def test_release_pushes_annotated_tag_from_any_directory(self):
        result = self.release('0.1.1')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('tag', self.git('cat-file', '-t', 'v0.1.1'))
        self.assertEqual(self.git('rev-parse', 'HEAD'), self.git('rev-parse', 'v0.1.1^{}'))
        self.assertIn('refs/tags/v0.1.1', self.git('ls-remote', '--tags', 'origin'))

    def test_invalid_arguments_do_not_create_tags(self):
        for args in ((), ('v0.1.1',), ('0.1.1-rc1',), ('01.1.1',), ('0.1.1', 'extra')):
            with self.subTest(args=args):
                self.assert_rejected('Usage:' if len(args) != 1 else 'Expected a stable', *args)

    def test_dirty_checkout_is_rejected(self):
        (self.repository / 'untracked.txt').write_text('Uncommitted work')
        self.assert_rejected('Commit or stash', '0.1.1')

    def test_other_branch_and_detached_head_are_rejected(self):
        self.git('checkout', '-b', 'feature')
        self.assert_rejected('Switch to main', '0.1.1')
        self.git('checkout', '--detach')
        self.assert_rejected('Switch to main', '0.1.1')

    def test_unpushed_commit_is_rejected(self):
        self.git('commit', '--allow-empty', '-m', 'Unpushed change')
        self.assert_rejected('must match origin/main', '0.1.1')

    def test_remote_ahead_is_detected_by_fetch(self):
        other = self.root / 'other'
        self.git('clone', '-b', 'main', str(self.remote), str(other))
        self.git('config', 'user.name', 'Other', cwd=other)
        self.git('config', 'user.email', 'other@example.invalid', cwd=other)
        self.git('commit', '--allow-empty', '-m', 'Remote change', cwd=other)
        self.git('push', 'origin', 'main', cwd=other)
        self.assert_rejected('must match origin/main', '0.1.1')

    def test_existing_local_and_remote_tags_are_rejected(self):
        self.git('tag', 'v0.1.1')
        self.assert_rejected('Local tag v0.1.1 already exists', '0.1.1')
        self.git('push', 'origin', 'v0.1.1')
        self.git('tag', '-d', 'v0.1.1')
        result = self.release('0.1.1')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Remote tag v0.1.1 already exists', result.stderr)
        self.assertEqual('', self.git('tag', '--list'))

    def test_failed_push_retains_tag_and_reports_recovery(self):
        hook = self.remote / 'hooks/pre-receive'
        hook.write_text('#!/bin/sh\nexit 1\n')
        hook.chmod(0o755)
        self.assert_rejected('Local tag v0.1.1 was retained', '0.1.1')
        self.assertEqual('tag', self.git('cat-file', '-t', 'v0.1.1'))

    def push_remote_tags(self, *tags):
        for tag in tags:
            self.git('tag', '-a', tag, '-m', tag)
            self.git('push', 'origin', 'refs/tags/' + tag)
            self.git('tag', '-d', tag)

    def test_lower_versions_are_rejected_without_creating_tags(self):
        self.push_remote_tags('v1.9.9', 'v1.10.0', 'v0.99.99')
        for version in ('1.9.10', '1.0.0', '0.100.0'):
            with self.subTest(version=version):
                result = self.release(version)
                self.assertNotEqual(0, result.returncode)
                self.assertIn('must be higher than the latest stable remote tag v1.10.0', result.stderr)
                self.assertEqual('', self.git('tag', '--list'))
                self.assertEqual('', self.git('ls-remote', '--tags', 'origin', 'refs/tags/v' + version))

    def test_higher_versions_use_numeric_order_and_ignore_prereleases(self):
        self.push_remote_tags('v1.9.99', 'v2.0.0-rc1', 'v99.0.0-SNAPSHOT',
                              'unrelated', '99.0.0', 'v01.0.0')
        for version in ('1.10.0', '1.10.1', '2.0.0'):
            with self.subTest(version=version):
                result = self.release(version)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertIn('refs/tags/v' + version, self.git('ls-remote', '--tags', 'origin'))


class MatrixChecks(unittest.TestCase):
    def test_matrix_matches_workflow_and_rejects_unknown_fields(self):
        workflow = (Path(__file__).resolve().parent.parent / '.github/workflows/build.yml').read_text()
        entries = matrix.read_matrix(workflow)
        self.assertEqual([('8.8', '21'), ('8.14', '21'), ('9.8.1', '21'), ('9.8.1', '25')], entries)
        with self.assertRaises(ValueError):
            matrix.read_matrix(workflow.replace("java: '25'", "java: '25'\n            unexpected: true"))
        with self.assertRaises(ValueError):
            matrix.read_matrix(workflow.replace("java: '25'", "java: '26'"))

    def test_runtime_version_and_isolation_flags_follow_combination(self):
        for gradle, java, flag in (('8.8', '21', 'unsafe.isolated-projects'),
                                    ('9.8.1', '25', 'isolated-projects')):
            commands = worker.matrix_commands(gradle, java)
            self.assertEqual(commands[0], commands[1])
            self.assertIn(f'-PtestJavaVersion={java}', commands[0])
            self.assertIn(f'-PtestGradleVersion={gradle}', commands[0])
            self.assertIn(f'-Dorg.gradle.{flag}=true', commands[4])
            self.assertEqual(commands[5], commands[6])
            self.assertIn(['python3', 'scripts/test-isolated-example.py', 'gradle', '--gradle-version', gradle], commands)
            self.assertIn(['python3', 'scripts/test-release.py', 'gradle'], commands)
            signing = ['python3', 'scripts/test-central-signing.py', 'gradle']
            self.assertEqual(java == '21', signing in commands)

    def test_release_waits_for_full_verification_before_publication(self):
        root = Path(__file__).resolve().parents[1]
        workflow = (root / '.github/workflows/release.yml').read_text()
        self.assertIn('uses: ./.github/workflows/build.yml', workflow)
        self.assertIn('needs: [version, verification]', workflow)
        self.assertIn('release-version: ${{ needs.version.outputs.version }}', workflow)
        publish = (root / '.github/workflows/publish.yml').read_text()
        self.assertLess(publish.index(':plugin:validateRelease'), publish.index(':plugin:publishPlugins'))

    def test_packaged_consumer_fixtures_use_both_ids_without_development_injection(self):
        for plugin in release_artifacts.IDS:
            for kotlin in (False, True):
                files = release_artifacts.consumer_files(plugin, kotlin, Path('/tmp/release-fixture'), '0.1.0')
                contents = '\n'.join(files.values())
                self.assertIn(plugin, contents)
                self.assertIn('release.fixture:library:1.0', contents)
                self.assertNotIn('includeBuild', contents)
                self.assertNotIn('mavenLocal', contents)
                settings = files['settings.gradle.kts' if kotlin else 'settings.gradle']
                if plugin.endswith('.settings'):
                    self.assertLess(settings.index(plugin), settings.index('rootProject.name'))

    def test_release_verification_rejects_missing_publications(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(FileNotFoundError):
                release_artifacts.verify_publications(Path(directory), '0.1.0', 'https://github.com/sneedit/deplens-gradle-plugin')

    def run_matrix(self, codes, arguments=()):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)
        (root / '.github/workflows').mkdir(parents=True)
        workflow = Path(__file__).resolve().parent.parent / '.github/workflows/build.yml'
        (root / '.github/workflows/build.yml').write_text(workflow.read_text())
        with patch.object(matrix, 'ROOT', root), patch.object(matrix.subprocess, 'run'), \
                patch.object(matrix, 'stream', side_effect=codes) as stream, \
                patch('sys.argv', ['test-ci-matrix.py', *arguments]), contextlib.redirect_stdout(io.StringIO()):
            outcome = matrix.main()
        summary = next((root / 'build/ci-matrix').glob('*/summary.txt')).read_text()
        return outcome, stream.call_count, summary

    def test_selection_runs_only_requested_combination_without_examples(self):
        for code in (0, 1):
            outcome, calls, summary = self.run_matrix([code], ['--gradle', '9.8.1', '--java', '25'])
            self.assertEqual(code, outcome)
            self.assertEqual(1, calls)
            self.assertEqual(('PASS' if code == 0 else 'FAIL') + '  gradle-9.8.1-java-25\n', summary)

    def test_invalid_or_incomplete_selection_fails_before_docker(self):
        for arguments in (['--gradle', '8.8'], ['--java', '21'],
                          ['--gradle', '8.8', '--java', '25']):
            with patch('sys.argv', ['test-ci-matrix.py', *arguments]), \
                    patch.object(matrix.subprocess, 'run') as docker, \
                    contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as error:
                matrix.main()
            self.assertEqual(2, error.exception.code)
            docker.assert_not_called()

    def test_failure_runs_remaining_combinations_but_skips_examples(self):
        outcome, calls, summary = self.run_matrix([1, 0, 0, 0])
        self.assertEqual(1, outcome)
        self.assertEqual(4, calls)
        self.assertIn('FAIL  gradle-8.8-java-21', summary)
        self.assertIn('PASS  gradle-9.8.1-java-25', summary)
        self.assertNotIn('examples', summary)

    def test_success_runs_examples_and_example_failure_is_not_hidden(self):
        for code in (0, 1):
            outcome, calls, summary = self.run_matrix([0, 0, 0, 0, code])
            self.assertEqual(code, outcome)
            self.assertEqual(5, calls)
            self.assertIn(('PASS' if code == 0 else 'FAIL') + '  examples', summary)


class CiChecks(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name)
        self.output = io.StringIO()
        self.capture = contextlib.redirect_stdout(self.output)
        self.capture.__enter__()
        self.addCleanup(self.capture.__exit__, None, None, None)

    def suite(self, failures=0, errors=0):
        (self.path / 'TEST-example.xml').write_text(
            f'<testsuite tests="3" failures="{failures}" errors="{errors}" skipped="1"/>')

    def test_real_success(self):
        self.suite()
        self.assertEqual(0, report.tests(self.path, 'success'))
        self.assertIn('tests: 3', self.output.getvalue())

    def test_missing_reports_fail(self):
        self.assertEqual(1, report.tests(self.path, 'success'))

    def test_test_failure_or_error_fails_even_with_successful_step(self):
        for failures, errors in ((1, 0), (0, 1)):
            self.suite(failures, errors)
            self.assertEqual(1, report.tests(self.path, 'success'))

    def test_failed_or_skipped_build_does_not_report_success(self):
        self.suite()
        for outcome in ('failure', 'cancelled', 'skipped', ''):
            self.assertEqual(1, report.tests(self.path, outcome))

    def update_report(self, **changes):
        data = dict(complete=True, checksComplete=True,
                    entries=[dict(statuses=['UPDATE_AVAILABLE'])])
        data.update(changes)
        file = self.path / 'updates.json'
        file.write_text(json.dumps(data))
        return file

    def test_available_updates_are_not_build_failures(self):
        self.assertEqual(0, report.updates(self.update_report()))

    def test_recommendations_available_only_and_missing_candidates_have_separate_counts(self):
        file = self.update_report(entries=[
            dict(statuses=['UPDATE_AVAILABLE'], recommendedVersion='2.0'),
            dict(statuses=['UPDATE_AVAILABLE'], recommendedVersion=None),
            dict(statuses=['NO_MATCHING_CANDIDATES'])])
        self.assertEqual(0, report.updates(file))
        output = self.output.getvalue()
        self.assertIn('RECOMMENDED_UPDATES: 1', output)
        self.assertIn('AVAILABLE_ONLY: 1', output)
        self.assertIn('NO_MATCHING_CANDIDATES: 1', output)

    def test_incomplete_checks_fail(self):
        for fields in (dict(complete=False), dict(checksComplete=False),
                       dict(entries=[dict(statuses=['FAILED'])]),
                       dict(entries=[dict(statuses=['NOT_CHECKED'])])):
            self.assertEqual(1, report.updates(self.update_report(**fields)))

    def test_local_plugin_definitions_are_not_unchecked_external_updates(self):
        file = self.update_report(entries=[dict(statuses=['NOT_CHECKED'],
                                               pluginStates=['LOCAL_CONVENTION', 'PLUGIN_DEFINITION'])])
        self.assertEqual(0, report.updates(file))
        self.assertIn('NOT_CHECKED: 0', self.output.getvalue())

    def test_missing_update_report_fails(self):
        self.assertEqual(1, report.updates(self.path / 'missing.json'))

    def test_stable_release_versions(self):
        for value in ('0.1.0', '1.2.3', '10.20.30'):
            self.assertTrue(release.valid_version(value))
        for value in ('v1.2.3', '01.2.3', '1.2', '1.2.3-SNAPSHOT',
                      '1.2.3-rc.1', '1.2.3\n', '1.2.3; echo bad'):
            self.assertFalse(release.valid_version(value))

    def isolation_report(self):
        return dict(complete=True, checksComplete=True, entries=[
            dict(build=str(self.path / build), project=project,
                 coordinate='com.example:demo-library',
                 statuses=['UPDATE_AVAILABLE'], recommendedVersion='1.1.0')
            for build, project in (('', ':'), ('', ':app'), ('', ':lib'),
                                   ('buildSrc', ':'), ('included-one', ':'),
                                   ('included-one', ':tool'), ('included-two', ':'))])

    def verify_isolation(self, data=None, second=None, log=None):
        first = json.dumps(data if data is not None else self.isolation_report()).encode()
        isolated.verify(self.path, 'Isolated Projects\nConfiguration cache entry stored.',
                        log if log is not None else
                        'Reusing configuration cache.\nConfiguration cache entry reused.',
                        first, second if second is not None else first)

    def test_isolated_example_complete_cached_results_pass(self):
        self.verify_isolation()

    def test_isolated_example_requires_isolation_and_fresh_cache(self):
        data = json.dumps(self.isolation_report()).encode()
        for log in ('Configuration cache entry stored.',
                    'Isolated Projects\nConfiguration cache entry reused.'):
            with self.assertRaises(ValueError):
                isolated.verify(self.path, log,
                                'Reusing configuration cache.\nConfiguration cache entry reused.',
                                data, data)

    def test_isolated_example_requires_cache_reuse(self):
        for log in ('BUILD SUCCESSFUL', 'Configuration cache entry stored.',
                    'Reusing configuration cache.\nCalculating task graph\n'
                    'Configuration cache entry reused.'):
            with self.assertRaises(ValueError):
                self.verify_isolation(log=log)

    def test_isolated_example_requires_identical_json(self):
        with self.assertRaises(ValueError):
            self.verify_isolation(second=b'{}')

    def test_isolated_example_requires_complete_expected_results(self):
        for mutation in ('inventory', 'checks', 'missing-build', 'duplicate', 'failure', 'major'):
            data = self.isolation_report()
            if mutation == 'inventory':
                data['complete'] = False
            elif mutation == 'checks':
                data['checksComplete'] = False
            elif mutation == 'missing-build':
                data['entries'].pop()
            elif mutation == 'duplicate':
                data['entries'][-1] = data['entries'][0]
            elif mutation == 'failure':
                data['entries'][0]['statuses'] = ['FAILED']
            else:
                data['entries'][0]['recommendedVersion'] = '2.0.0'
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                self.verify_isolation(data=data)


class CentralPublishingChecks(unittest.TestCase):
    deployment = '28570f16-da32-4c14-bd2e-c1acc0782365'

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name)
        self.repo = self.path / 'repository'
        self.bundle = self.path / 'bundle.zip'
        self.result = self.path / 'result.json'

    def stage(self):
        for name in central.artifact_paths('0.1.0'):
            file = self.repo / name
            file.parent.mkdir(parents=True, exist_ok=True)
            data = ('fixture:' + name).encode()
            file.write_bytes(data)
            Path(str(file) + '.asc').write_bytes(b'-----BEGIN PGP SIGNATURE-----\nfixture\n')
            for algorithm in ('md5', 'sha1'):
                Path(str(file) + '.' + algorithm).write_text(hashlib.new(algorithm, data).hexdigest())

    def test_bundle_includes_exact_release_and_all_signed_publications(self):
        self.stage()
        (self.repo / 'maven-metadata.xml').write_text('must not be uploaded')
        central.create_bundle(self.repo, '0.1.0', self.bundle)
        with zipfile.ZipFile(self.bundle) as bundle:
            self.assertEqual(28, len(bundle.namelist()))
            self.assertEqual(sorted(central.bundle_entries('0.1.0')), sorted(bundle.namelist()))
        original = self.bundle.read_bytes()
        central.create_bundle(self.repo, '0.1.0', self.bundle)
        self.assertEqual(original, self.bundle.read_bytes())

    def test_invalid_version_missing_signature_and_wrong_checksum_fail_locally(self):
        self.stage()
        with self.assertRaises(ValueError):
            central.create_bundle(self.repo, '0.1.0-SNAPSHOT', self.bundle)
        artifact = self.repo / central.artifact_paths('0.1.0')[0]
        Path(str(artifact) + '.sha1').write_text('wrong')
        with self.assertRaisesRegex(ValueError, 'Checksum mismatch'):
            central.create_bundle(self.repo, '0.1.0', self.bundle)
        Path(str(artifact) + '.asc').unlink()
        with self.assertRaises(FileNotFoundError):
            central.create_bundle(self.repo, '0.1.0', self.bundle)

    def test_unexpected_zip_entries_are_rejected_before_any_request(self):
        self.stage()
        central.create_bundle(self.repo, '0.1.0', self.bundle)
        with zipfile.ZipFile(self.bundle, 'a') as bundle:
            bundle.writestr('../unexpected', 'bad')
        with patch.object(central, 'request') as request, self.assertRaises(ValueError):
            central.publish(self.bundle, '0.1.0', self.result)
        request.assert_not_called()

    def test_publish_uses_automatic_api_once_then_waits_for_published(self):
        self.stage()
        central.create_bundle(self.repo, '0.1.0', self.bundle)
        status = lambda state: json.dumps({'deploymentId': self.deployment, 'deploymentState': state}).encode()
        responses = [self.deployment.encode(), status('VALIDATED'), status('PUBLISHING'), status('PUBLISHED')]
        with patch.object(central, 'credentials', return_value='fixture-token'), \
                patch.object(central, 'request', side_effect=responses) as request, \
                patch.object(central.time, 'sleep'), contextlib.redirect_stdout(io.StringIO()) as output:
            central.publish(self.bundle, '0.1.0', self.result)
        self.assertEqual(4, request.call_count)
        self.assertIn('publishingType=AUTOMATIC', request.call_args_list[0].args[0])
        self.assertIn(b'name="bundle"', request.call_args_list[0].args[2])
        self.assertNotIn('fixture-token', output.getvalue())
        self.assertEqual('PUBLISHED', json.loads(self.result.read_text())['deploymentState'])

    def test_failed_validation_never_reports_success_or_echoes_server_errors(self):
        response = json.dumps({'deploymentId': self.deployment, 'deploymentState': 'FAILED',
                               'errors': ['sensitive-server-text']}).encode()
        with patch.object(central, 'request', return_value=response), \
                contextlib.redirect_stdout(io.StringIO()) as output, \
                self.assertRaises(central.CentralError) as error:
            central.await_published(self.deployment, 'fixture-token', self.result)
        self.assertNotIn('sensitive-server-text', str(error.exception) + output.getvalue())
        self.assertEqual('FAILED', json.loads(self.result.read_text())['deploymentState'])

    def test_status_retries_transient_errors_without_reuploading(self):
        published = json.dumps({'deploymentId': self.deployment, 'deploymentState': 'PUBLISHED'}).encode()
        with patch.object(central, 'request', side_effect=[central.CentralError('temporary', True), published]) as request, \
                patch.object(central.time, 'sleep'), contextlib.redirect_stdout(io.StringIO()):
            central.await_published(self.deployment, 'fixture-token', self.result)
        self.assertTrue(all(call.args[0].startswith('status?') for call in request.call_args_list))

    def test_ambiguous_upload_failure_is_not_retried(self):
        self.stage()
        central.create_bundle(self.repo, '0.1.0', self.bundle)
        with patch.object(central, 'credentials', return_value='fixture-token'), \
                patch.object(central, 'request', side_effect=central.CentralError('transport', True)) as request, \
                self.assertRaises(central.CentralError):
            central.publish(self.bundle, '0.1.0', self.result)
        self.assertEqual(1, request.call_count)

    def test_http_errors_hide_response_bodies_and_only_mark_transient_statuses_retryable(self):
        for code, retryable in [(401, False), (403, False), (429, True), (503, True)]:
            error = HTTPError(central.BASE, code, 'sensitive-server-message', {},
                              io.BytesIO(b'fixture-token-must-not-be-logged'))
            with patch.object(central, 'build_opener') as opener:
                opener.return_value.open.side_effect = error
                with self.assertRaises(central.CentralError) as caught:
                    central.request('status?id=fixture', 'fixture-token')
            self.assertEqual(retryable, caught.exception.retryable)
            self.assertEqual(f'Central Portal request failed (HTTP {code})', str(caught.exception))

    def test_validated_state_is_not_success_when_publication_times_out(self):
        response = json.dumps({'deploymentId': self.deployment, 'deploymentState': 'VALIDATED'}).encode()
        with patch.object(central, 'request', return_value=response), \
                patch.object(central.time, 'monotonic', side_effect=[0, 0, 2, 3]), \
                patch.object(central.time, 'sleep'), contextlib.redirect_stdout(io.StringIO()), \
                self.assertRaisesRegex(central.CentralError, 'Timed out'):
            central.await_published(self.deployment, 'fixture-token', self.result, timeout=1)
        self.assertEqual('VALIDATED', json.loads(self.result.read_text())['deploymentState'])

    def test_missing_credentials_are_reported_by_name_only(self):
        with patch.dict(central.os.environ, {'MAVEN_CENTRAL_USERNAME': 'sensitive-user'}, clear=True), \
                self.assertRaises(ValueError) as error:
            central.credentials()
        self.assertEqual('Missing required environment variables: MAVEN_CENTRAL_PASSWORD', str(error.exception))

    def test_publishing_jobs_are_independent_and_release_waits_for_both(self):
        root = Path(__file__).resolve().parents[1]
        publish = (root / '.github/workflows/publish.yml').read_text()
        self.assertIn('  central:', publish)
        self.assertIn('  plugin-portal:', publish)
        self.assertEqual(2, publish.count('needs: prepare'))
        self.assertIn('publish-central.py publish', publish)
        self.assertIn('--no-scan', publish)
        release = (root / '.github/workflows/release.yml').read_text()
        self.assertIn('needs: [version, publish]', release)
        for secret in ('MAVEN_CENTRAL_USERNAME', 'MAVEN_CENTRAL_PASSWORD', 'SIGNING_KEY', 'SIGNING_PASSWORD'):
            self.assertIn(secret + ': ${{ secrets.' + secret + ' }}', release)
            self.assertIn(secret + ':', publish)


if __name__ == '__main__':
    unittest.main()
