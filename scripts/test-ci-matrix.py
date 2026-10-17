#!/usr/bin/env python3
"""Run the GitHub Actions test matrix in Docker using the current working files."""
import argparse
from datetime import datetime
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
IMAGE = 'deplens-ci-matrix:local'


def read_matrix(workflow):
    # Deliberately accept only the explicit Gradle/Java include list used by this workflow.
    # Fail on a changed layout rather than silently running an incomplete matrix.
    section = workflow.split('      matrix:\n', 1)[1].split('\n    steps:', 1)[0]
    pattern = r"\s*- gradle: '([0-9.]+)'\s*\n\s*java: '([0-9]+)'"
    entries = re.findall(pattern, section)
    remainder = re.sub(pattern, '', section).replace('include:', '').strip()
    if not entries or remainder:
        raise ValueError('Unsupported CI matrix layout; update scripts/test-ci-matrix.py')
    if any(java not in ('21', '25') for _, java in entries):
        raise ValueError('CI uses a JDK not present in scripts/ci-matrix/Dockerfile')
    return entries


def stream(command, log):
    with log.open('w', buffering=1) as output:
        with subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                              text=True, bufsize=1) as process:
            for line in process.stdout:
                print(line, end='', flush=True)
                output.write(line)
            return process.wait()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--list', action='store_true', help='Show the matrix without starting Docker')
    parser.add_argument('--gradle', help='Run one CI combination (requires --java)')
    parser.add_argument('--java', help='Java version for the selected combination (requires --gradle)')
    args = parser.parse_args()
    if bool(args.gradle) != bool(args.java):
        parser.error('--gradle and --java must be supplied together')
    try:
        matrix = read_matrix((ROOT / '.github/workflows/build.yml').read_text())
        if args.gradle:
            selected = (args.gradle, args.java)
            if selected not in matrix:
                parser.error('Combination is not in the CI matrix; use --list to see available combinations')
            matrix = [selected]
        if args.list:
            for gradle, java in matrix:
                print(f'Gradle {gradle} / Java {java}')
            if not args.gradle:
                print('Example projects (wrapper / Java 21), after the matrix passes')
            return 0
        subprocess.run(['docker', 'info'], check=True, stdout=subprocess.DEVNULL)
        subprocess.run(['docker', 'build', '-t', IMAGE, str(ROOT / 'scripts/ci-matrix')], check=True)
        base = ROOT / 'build/ci-matrix'
        base.mkdir(parents=True, exist_ok=True)
        cache = base / 'cache'
        cache.mkdir(exist_ok=True)
        # A distinct directory prevents stale reports from looking like a new success.
        results = base / (datetime.now().strftime('%Y%m%d-%H%M%S') + f'-{os.getpid()}')
        results.mkdir()
        outcomes = []

        def run(label, arguments):
            target = results / label
            target.mkdir()
            print(f'\n=== {label} ===', flush=True)
            command = ['docker', 'run', '--rm', '--init', '--user', f'{os.getuid()}:{os.getgid()}',
                       '--mount', f'type=bind,src={ROOT},dst=/source,readonly',
                       '--mount', f'type=bind,src={cache},dst=/cache',
                       '--mount', f'type=bind,src={target},dst=/reports', IMAGE, *arguments]
            code = stream(command, target / 'run.log')
            outcomes.append((label, code))

        for gradle, java in matrix:
            run(f'gradle-{gradle}-java-{java}', [gradle, java])
        if args.gradle:
            print('\nExample projects skipped for a single-combination run.')
        elif all(code == 0 for _, code in outcomes):
            run('examples', ['--examples'])
        else:
            print('\nExample projects skipped because the matrix failed (same dependency as CI).')
        summary = '\n'.join(f'{"PASS" if code == 0 else "FAIL"}  {label}' for label, code in outcomes) + '\n'
        (results / 'summary.txt').write_text(summary)
        print(f'\n{summary}\nReports: {results}')
        return int(any(code != 0 for _, code in outcomes))
    except (OSError, ValueError, IndexError, subprocess.CalledProcessError) as error:
        print(f'Cannot run CI matrix: {error}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(130)
