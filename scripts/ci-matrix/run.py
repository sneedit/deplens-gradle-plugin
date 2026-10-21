#!/usr/bin/env python3
"""Container worker: isolated source copy, CI test commands, report collection."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile


def install_gradle(version):
    destination = Path('/cache/distributions')
    executable = destination / f'gradle-{version}/bin/gradle'
    if not executable.exists():
        destination.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=destination) as directory:
            archive = Path(directory) / 'gradle.zip'
            url = f'https://services.gradle.org/distributions/gradle-{version}-bin.zip'
            subprocess.run(['curl', '--fail', '--location', '--retry', '3', '-o', str(archive), url], check=True)
            checksum = subprocess.check_output(['curl', '--fail', '--silent', '--show-error',
                                                '--location', '--retry', '3', url + '.sha256'], text=True).strip()
            with archive.open('rb') as source:
                actual = hashlib.file_digest(source, 'sha256').hexdigest()
            if actual != checksum:
                raise ValueError(f'Gradle {version}: distribution checksum mismatch')
            subprocess.run(['unzip', '-q', str(archive), '-d', directory], check=True)
            (Path(directory) / f'gradle-{version}').rename(executable.parent.parent)
    return str(executable)


def matrix_commands(gradle, java):
    version = tuple(int(part) for part in gradle.split('.')[:2])
    isolation = '-Dorg.gradle.' + ('isolated-projects' if version >= (9, 7)
                                 else 'unsafe.isolated-projects') + '=true'
    cache = ['--configuration-cache', '--configuration-cache-problems=fail']
    build = ['gradle', 'build', *cache, f'-PtestJavaVersion={java}', f'-PtestGradleVersion={gradle}',
             '-Dorg.gradle.java.installations.fromEnv=JAVA_HOME_21_X64,JAVA_HOME', '--no-daemon', '--stacktrace']
    console = ['python3', 'scripts/test-console.py', 'gradle']
    validation = ['gradle', ':plugin:validatePlugins', *cache, isolation, '--no-daemon']
    commands = [build, build.copy(), console, [*console, *cache], [*console, *cache, isolation],
            validation, validation.copy(),
            ['python3', 'scripts/test-isolated-example.py', 'gradle', '--gradle-version', gradle],
            ['python3', 'scripts/test-release.py', 'gradle']]
    if java == '21':
        commands.append(['python3', 'scripts/test-central-signing.py', 'gradle'])
    return commands


def collect(workspace):
    for path in workspace.rglob('build'):
        if not path.is_dir():
            continue
        for name in ('reports', 'test-results', 'libs', 'isolation-ci'):
            source = path / name
            if source.is_dir():
                target = Path('/reports') / path.relative_to(workspace) / name
                shutil.copytree(source, target, dirs_exist_ok=True)
    # Do not traverse the fixture's shared dependency-cache links.
    for run in (workspace / 'build/central-verification').glob('run-*'):
        for source in [*run.glob('*.log'), *run.glob('central-bundle.zip')]:
            target = Path('/reports') / source.relative_to(workspace)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)


def main():
    examples = sys.argv[1:] == ['--examples']
    gradle, java = ('', '21') if examples else sys.argv[1:]
    os.environ['JAVA_HOME'] = '/opt/java/jdk25' if java == '25' else '/opt/java/openjdk'
    Path(os.environ['HOME']).mkdir(parents=True, exist_ok=True)
    os.environ['JAVA_TOOL_OPTIONS'] = '-Duser.home=' + os.environ['HOME']
    os.environ['PATH'] = os.environ['JAVA_HOME'] + '/bin:' + os.environ['PATH']
    with tempfile.TemporaryDirectory(prefix='deplens-ci-') as directory:
        workspace = Path(directory) / 'project'
        def ignore(directory, names):
            excluded = {'.git', '.gradle', '.idea', '.DS_Store', '__pycache__'}
            if 'src' not in Path(directory).parts:
                excluded.add('build')
            return excluded.intersection(names)

        shutil.copytree('/source', workspace, ignore=ignore)
        (workspace / 'gradlew').chmod(0o755)
        os.chdir(workspace)
        try:
            if examples:
                commands = [['./gradlew', 'publishToMavenLocal', '-Pversion=0.2.0', '--no-daemon']]
                commands += [['./gradlew', '-p', f'examples/{name}', ':depLensInventory',
                              ':depLensCheckDependencies', '--no-daemon', '--stacktrace']
                             for name in ('simple-java', 'multi-module', 'kotlin-dsl')]
                commands += [['python3', 'scripts/test-complex-example.py', './gradlew']]
            else:
                executable = install_gradle(gradle)
                # Console helpers require an absolute executable path.
                commands = [[executable if arg == 'gradle' else arg for arg in command]
                            for command in matrix_commands(gradle, java)]
            for command in commands:
                print('\n+ ' + ' '.join(command), flush=True)
                subprocess.run(command, check=True)
        finally:
            collect(workspace)


if __name__ == '__main__':
    main()
