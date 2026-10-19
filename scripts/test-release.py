#!/usr/bin/env python3
"""Verify release artifacts in a local file repository; never invokes Plugin Portal publication."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
IDS = {'com.sneedit.deplens': 'com.sneedit.deplens.DepLensPlugin',
       'com.sneedit.deplens.settings': 'com.sneedit.deplens.DepLensSettingsPlugin'}
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def verify_publications(repository, version, repository_url):
    implementation = repository / 'com/sneedit/deplens/plugin' / version
    poms = [implementation / f'plugin-{version}.pom']
    poms += [repository / plugin.replace('.', '/') / f'{plugin}.gradle.plugin' / version
             / f'{plugin}.gradle.plugin-{version}.pom' for plugin in IDS]
    for file in poms:
        pom = ET.parse(file).getroot()
        value = lambda field: pom.findtext('m:' + field, namespaces=NS)
        require(value('version') == version, 'Publication version mismatch')
        require(value('name') and value('description'), 'Missing publication name/description')
        require(value('url') == repository_url, 'Missing or incorrect project URL')
        require(pom.findtext('m:scm/m:url', namespaces=NS) == repository_url, 'Missing SCM URL')
        require(pom.findtext('m:scm/m:connection', namespaces=NS) == 'scm:git:' + repository_url + '.git', 'Missing SCM connection')
        require(pom.findtext('m:licenses/m:license/m:url', namespaces=NS)
                == 'https://www.apache.org/licenses/LICENSE-2.0.txt', 'Missing Apache-2.0 license')
        require(pom.findtext('m:developers/m:developer/m:id', namespaces=NS) == 'sneedit', 'Missing developer ID')
        require(pom.findtext('m:developers/m:developer/m:name', namespaces=NS) == 'Sneed IT', 'Missing developer name')
        require(pom.findtext('m:developers/m:developer/m:url', namespaces=NS) == 'https://github.com/sneedit', 'Missing developer URL')
        dependencies = pom.findall('m:dependencies/m:dependency', NS)
        require(all(not d.findtext('m:version', '', NS).endswith('-SNAPSHOT') for d in dependencies), 'Snapshot release dependency')
        if file != poms[0]:
            require([(d.findtext('m:groupId', '', NS), d.findtext('m:artifactId', '', NS), d.findtext('m:version', '', NS))
                     for d in dependencies] == [('com.sneedit.deplens', 'plugin', version)], 'Plugin marker points at the wrong implementation')
        else:
            require({d.findtext('m:artifactId', '', NS) for d in dependencies} == {'jspecify', 'gson', 'tomlj'},
                    'Unexpected or missing implementation dependencies')
    for suffix in ('', '-sources', '-javadoc'):
        with zipfile.ZipFile(implementation / f'plugin-{version}{suffix}.jar') as jar:
            for notice in ('LICENSE', 'NOTICE'):
                require(jar.read('META-INF/' + notice) == (ROOT / notice).read_bytes(), 'Missing or changed legal notice')
            manifest = jar.read('META-INF/MANIFEST.MF').decode()
            require('Implementation-Version: ' + version in manifest, 'Missing release version in manifest')
            if not suffix:
                for plugin, entry in IDS.items():
                    descriptor = jar.read(f'META-INF/gradle-plugins/{plugin}.properties').decode()
                    require('implementation-class=' + entry in descriptor, 'Incorrect plugin descriptor')
                    for feature in ('configuration-cache', 'isolated-projects'):
                        require(f'compatibility.feature.{feature}=DECLARED_SUPPORTED' in descriptor,
                                f'Missing {feature} compatibility declaration for {plugin}')
                    major = struct.unpack('>H', jar.read(entry.replace('.', '/') + '.class')[6:8])[0]
                    require(major == 65, 'Plugin bytecode must target Java 21')
                require(not any(name.startswith('org/gradle/') for name in jar.namelist()), 'Gradle API classes leaked into plugin JAR')
            else:
                ending = '.java' if suffix == '-sources' else '.html'
                require('com/sneedit/deplens/DepLensPlugin' + ending in jar.namelist(), 'Missing sources/Javadoc content')
    metadata = json.loads((implementation / f'plugin-{version}.module').read_text())
    require(metadata['component']['version'] == version, 'Incorrect Gradle module version')
    require({'apiElements', 'runtimeElements'}.issubset({v['name'] for v in metadata['variants']}), 'Missing runtime/API variants')
    for variant in metadata['variants']:
        if variant['name'] in ('apiElements', 'runtimeElements'):
            require(variant['attributes']['org.gradle.jvm.version'] == 21, 'Incorrect minimum Java variant')
            require(variant['attributes']['org.gradle.plugin.api-version'] == '8.8', 'Incorrect minimum Gradle API variant')


def dependency_fixture(repository):
    base = repository / 'release/fixture/library'
    for version in ('1.0', '1.1'):
        directory = base / version
        directory.mkdir(parents=True, exist_ok=True)
        (directory / f'library-{version}.pom').write_text(
            f'<project><modelVersion>4.0.0</modelVersion><groupId>release.fixture</groupId>'
            f'<artifactId>library</artifactId><version>{version}</version></project>')
        with zipfile.ZipFile(directory / f'library-{version}.jar', 'w'):
            pass
    (base / 'maven-metadata.xml').write_text('<metadata><groupId>release.fixture</groupId><artifactId>library</artifactId>'
        '<versioning><versions><version>1.0</version><version>1.1</version></versions></versioning></metadata>')


def consumer_files(plugin, kotlin, repository, version):
    repo = repository.as_uri()
    settings_plugin = plugin.endswith('.settings')
    if kotlin:
        settings = f'''pluginManagement {{ repositories {{ maven {{ url = uri("{repo}") }}; mavenCentral(); gradlePluginPortal() }} }}
rootProject.name = "release-consumer"
'''
        declaration = f'plugins {{ id("{plugin}") version "{version}" }}\n'
        build = '' if settings_plugin else declaration
        if settings_plugin:
            settings = settings.split('rootProject.name')[0] + declaration + 'rootProject.name = "release-consumer"\n'
        if settings_plugin:
            build = 'plugins { java }\n'
        else:
            build = build.replace('plugins { ', 'plugins { java; ')
        build += f'repositories {{ maven {{ url = uri("{repo}") }} }}\n'
        build += 'dependencies { implementation("release.fixture:library:1.0") }\n'
        build += 'depLens { failIfChecksIncomplete.set(true) }\n'
        return {'settings.gradle.kts': settings, 'build.gradle.kts': build}
    settings = f'''pluginManagement {{ repositories {{ maven {{ url = uri('{repo}') }}; mavenCentral(); gradlePluginPortal() }} }}
rootProject.name = 'release-consumer'
'''
    declaration = f"plugins {{ id '{plugin}' version '{version}' }}\n"
    build = '' if settings_plugin else declaration
    if settings_plugin:
        settings = settings.split('rootProject.name')[0] + declaration + "rootProject.name = 'release-consumer'\n"
    if settings_plugin:
        build = "plugins { id 'java' }\n"
    else:
        build = build.replace('plugins { ', "plugins { id 'java'; ")
    build += f"repositories {{ maven {{ url = uri('{repo}') }} }}\n"
    build += "dependencies { implementation 'release.fixture:library:1.0' }\n"
    build += 'depLens { failIfChecksIncomplete = true }\n'
    return {'settings.gradle': settings, 'build.gradle': build}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('gradle', help='Gradle executable to test')
    parser.add_argument('--version', default='0.1.0', help='Local release candidate version; does not publish externally')
    args = parser.parse_args()
    spec = importlib.util.spec_from_file_location('release_version', ROOT / 'scripts/release-version.py')
    validator = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(validator)
    require(validator.valid_version(args.version), 'Expected a stable release version X.Y.Z')
    base = ROOT / 'build/release-verification'
    base.mkdir(parents=True, exist_ok=True)
    output = Path(tempfile.mkdtemp(prefix='run-', dir=base))
    repository = output / 'repository'
    repository_url = next(line.split('=', 1)[1] for line in (ROOT / 'gradle.properties').read_text().splitlines()
                          if line.startswith('repositoryUrl='))
    executable = str(Path(args.gradle).resolve())
    env = dict(os.environ)
    # Local artifact verification does not need publication credentials.
    for name in ('GRADLE_PUBLISH_KEY', 'GRADLE_PUBLISH_SECRET', 'MAVEN_CENTRAL_USERNAME',
                 'MAVEN_CENTRAL_PASSWORD', 'ORG_GRADLE_PROJECT_signingInMemoryKey',
                 'ORG_GRADLE_PROJECT_signingInMemoryKeyPassword'):
        env.pop(name, None)

    def run(label, arguments, directory=ROOT, failure=False):
        command = [executable, *arguments, '--offline', '--no-scan', '--no-daemon', '--stacktrace',
                   '-Dorg.gradle.java.installations.fromEnv=JAVA_HOME_21_X64,JAVA_HOME']
        with (output / (label + '.log')).open('w') as log:
            result = subprocess.run(command, cwd=directory, env=env, stdout=log, stderr=subprocess.STDOUT)
        text = (output / (label + '.log')).read_text()
        require((result.returncode != 0) if failure else (result.returncode == 0), label + ' failed; see ' + str(output / (label + '.log')))
        print(label + ': passed', flush=True)
        return text

    for value in ('0.1.0-SNAPSHOT', '01.2.3', 'v0.1.0', '0.1.0-rc.1'):
        text = run('reject-version-' + value, [':plugin:validateRelease', '-Pversion=' + value], failure=True)
        require('Expected a stable release version' in text, 'Version guard failed for an unrelated reason')
    text = run('reject-placeholder', [':plugin:validateRelease', '-Pversion=' + args.version,
                                     '-PrepositoryUrl=https://github.com/REPLACE_ME/deplens-gradle-plugin'], failure=True)
    require('Set repositoryUrl to the real HTTPS repository URL' in text, 'Repository guard failed for an unrelated reason')
    guard = [':plugin:validateRelease', '-Pversion=' + args.version, '--configuration-cache', '--configuration-cache-problems=fail']
    run('validate-release-cache', guard)
    require('Configuration cache entry reused' in run('validate-release-cache-reuse', guard), 'Release guard did not reuse the cache')
    run('package-local', [':plugin:publishAllPublicationsToReleaseVerificationRepository', '-Pversion=' + args.version,
                          '-PreleaseVerificationDirectory=' + str(repository)])
    verify_publications(repository, args.version, repository_url)
    dependency_fixture(repository)
    for plugin in IDS:
        for kotlin in (False, True):
            label = plugin + ('-kotlin' if kotlin else '-groovy')
            directory = output / label
            directory.mkdir()
            files = consumer_files(plugin, kotlin, repository, args.version)
            require(all('includeBuild' not in text and 'mavenLocal' not in text for text in files.values()), 'Consumer must use only packaged artifacts')
            for name, text in files.items():
                (directory / name).write_text(text)
            arguments = [':depLensInventory', ':depLensCheck', '--configuration-cache', '--configuration-cache-problems=fail']
            run(label, arguments, directory)
            cached = run(label + '-cache', arguments, directory)
            require('Configuration cache entry reused' in cached, 'Consumer did not reuse configuration cache')
            report = json.loads((directory / 'build/reports/deplens/depLensCheck.json').read_text())
            require(report['complete'] and report['checksComplete'], 'Packaged consumer check incomplete')
            require(any(e['coordinate'] == 'release.fixture:library' and e.get('recommendedVersion') == '1.1'
                        for e in report['entries']), 'Packaged plugin failed to resolve a dependency update')
    (output / 'result.json').write_text(json.dumps({'version': args.version, 'repository': str(repository), 'passed': True}, indent=2))
    print('Release artifact verification passed (local files only): ' + str(output), flush=True)


if __name__ == '__main__':
    main()
