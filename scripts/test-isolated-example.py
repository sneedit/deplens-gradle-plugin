#!/usr/bin/env python3
"""Run the checked-in composite twice and verify isolation/cache/report coverage."""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import tempfile


def verify(example, first_log, second_log, first_json, second_json):
    if 'isolated projects' not in first_log.lower():
        raise ValueError('Project isolation was not enabled')
    if 'Configuration cache entry stored' not in first_log:
        raise ValueError('First run did not store a fresh configuration cache entry')
    if ('Reusing configuration cache' not in second_log
            or 'Configuration cache entry reused' not in second_log
            or 'Calculating task graph' in second_log):
        raise ValueError('Second run did not reuse the configuration cache')
    if first_json != second_json:
        raise ValueError('JSON reports differ between runs')
    report = json.loads(second_json)
    if report.get('complete') is not True or report.get('checksComplete') is not True:
        raise ValueError('Inventory or update checks are incomplete')
    if any('FAILED' in entry['statuses'] for entry in report['entries']):
        raise ValueError('Update checks failed')
    entries = [entry for entry in report['entries']
               if entry['coordinate'] == 'com.example:demo-library']
    expected = {(str(example / build), project) for build, project in (
        ('', ':'), ('', ':app'), ('', ':lib'), ('buildSrc', ':'),
        ('included-one', ':'), ('included-one', ':tool'), ('included-two', ':'))}
    actual = {(entry['build'], entry['project']) for entry in entries}
    if len(entries) != 7 or actual != expected:
        raise ValueError(f'Expected all seven consumer contexts; found {actual}')
    if any(entry.get('recommendedVersion') != '1.1.0'
           or entry['statuses'] != ['UPDATE_AVAILABLE'] for entry in entries):
        raise ValueError('Consumer update results do not respect the minor-update rule')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('gradle', help='Path to the Gradle executable')
    parser.add_argument('--gradle-version', required=True)
    args = parser.parse_args()
    version = tuple(int(part) for part in args.gradle_version.split('.')[:2])
    isolation = ('--isolated-projects' if version >= (9, 7)
                 else '-Dorg.gradle.unsafe.isolated-projects=true')
    root = Path(__file__).resolve().parent.parent
    example = root / 'examples' / 'isolated-composite'
    artifacts = example / 'build' / 'isolation-ci'
    artifacts.mkdir(parents=True, exist_ok=True)
    for name in ('first.log', 'second.log', 'first.json', 'second.json'):
        (artifacts / name).unlink(missing_ok=True)
    subprocess.run([sys.executable, str(example / 'prepare_repository.py')], check=True)
    with tempfile.TemporaryDirectory(prefix='project-cache-', dir=artifacts) as cache:
        command = [str(Path(args.gradle).resolve()), '-p', str(example), 'depLensCheck',
                   '--project-cache-dir', cache, '--configuration-cache',
                   '--configuration-cache-problems=fail', isolation, '--console=plain',
                   '--no-daemon', '--stacktrace',
                   '-Dorg.gradle.java.installations.fromEnv=JAVA_HOME_21_X64,JAVA_HOME']
        for label in ('first', 'second'):
            print(f'Running isolated composite: {label} run', flush=True)
            report_path = example / 'build/reports/deplens/depLensCheck.json'
            report_path.unlink(missing_ok=True)
            with (artifacts / f'{label}.log').open('w') as log:
                subprocess.run(command, cwd=root, stdout=log, stderr=subprocess.STDOUT,
                               check=True, timeout=600)
            (artifacts / f'{label}.json').write_bytes(
                report_path.read_bytes())
        verify(example, (artifacts / 'first.log').read_text(),
               (artifacts / 'second.log').read_text(),
               (artifacts / 'first.json').read_bytes(),
               (artifacts / 'second.json').read_bytes())
    print('PASS: seven consumer contexts across four builds; cache reused; JSON byte-identical.')
    print(f'Logs and JSON reports: {artifacts}')


if __name__ == '__main__':
    main()
