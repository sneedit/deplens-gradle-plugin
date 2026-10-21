#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Sneed IT
# SPDX-License-Identifier: Apache-2.0
"""Build the complex example twice and require Configuration Cache reuse."""
import argparse
from pathlib import Path
import subprocess
import sys
import tempfile


def verify_cache(first_log, second_log):
    if 'Configuration cache entry stored.' not in first_log:
        raise ValueError('First run did not store a fresh configuration cache entry')
    if ('Reusing configuration cache.' not in second_log
            or 'Configuration cache entry reused.' not in second_log
            or 'Calculating task graph' in second_log):
        raise ValueError('Second run did not reuse the configuration cache')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('gradle', help='Path to the Gradle executable')
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    example = root / 'examples/complex'
    logs = example / 'build/reports/complex-example'
    logs.mkdir(parents=True, exist_ok=True)
    for label in ('first', 'second'):
        (logs / f'{label}.log').unlink(missing_ok=True)
    subprocess.run([sys.executable, str(example / 'prepare_repository.py')], check=True)
    with tempfile.TemporaryDirectory(prefix='project-cache-', dir=logs) as cache:
        command = [str(Path(args.gradle).resolve()), '-p', str(example),
                   ':depLensInventory', ':depLensCheckDependencies', 'build',
                   '--project-cache-dir', cache, '--configuration-cache',
                   '--configuration-cache-problems=fail',
                   '-Dorg.gradle.unsafe.isolated-projects=true',
                   '--console=plain', '--no-daemon', '--stacktrace']
        for label in ('first', 'second'):
            print(f'Running complex example: {label} run; log: {logs / (label + ".log")}',
                  flush=True)
            with (logs / f'{label}.log').open('w') as log:
                result = subprocess.run(command, cwd=root, stdout=log,
                                        stderr=subprocess.STDOUT, timeout=600)
            if result.returncode:
                print((logs / f'{label}.log').read_text(), flush=True)
                result.check_returncode()
        verify_cache((logs / 'first.log').read_text(), (logs / 'second.log').read_text())
    print('PASS: first run stored the Configuration Cache; second run reused it.')


if __name__ == '__main__':
    main()
