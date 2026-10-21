#!/usr/bin/env python3
"""Validate stable release versions before shell output or publication."""
from pathlib import Path
import re
import sys


def valid_version(value):
    return re.fullmatch(r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)", value) is not None


def check_newer_than_remote_tags(value, lines):
    versions = []
    for line in lines:
        fields = line.split()
        if len(fields) != 2 or not fields[1].startswith('refs/tags/v'):
            continue
        version = fields[1][len('refs/tags/v'):]
        if valid_version(version):
            versions.append(tuple(map(int, version.split('.'))))
    candidate = tuple(map(int, value.split('.')))
    if candidate in versions:
        raise ValueError(f'Remote tag v{value} already exists.')
    if versions and candidate <= max(versions):
        latest = '.'.join(map(str, max(versions)))
        raise ValueError(f'Release version {value} must be higher than the latest stable remote tag v{latest}.')


def update_examples(root, value):
    """Validate every target before writing; preserve all surrounding content."""
    if not valid_version(value):
        raise ValueError('Expected a stable release version X.Y.Z.')
    targets = {
        'examples/simple-java/build.gradle':
            r"(?m)^(    id 'com\.sneedit\.deplens' version ')[^'\n]+(')$",
        'examples/multi-module/build.gradle':
            r"(?m)^(    id 'com\.sneedit\.deplens' version ')[^'\n]+(')$",
        'examples/kotlin-dsl/build.gradle.kts':
            r'(?m)^(    id\("com\.sneedit\.deplens"\) version ")[^"\n]+(")$',
        '.github/workflows/build.yml':
            r'(?m)^(        run: ./gradlew publishToMavenLocal -Pversion=)[^ \n]+( --no-daemon)$',
        'scripts/ci-matrix/run.py':
            r"(?m)^(                commands = \[\['./gradlew', 'publishToMavenLocal', '-Pversion=)[^'\n]+(', '--no-daemon'\]\])$",
    }
    changes = []
    for name, pattern in targets.items():
        path = root / name
        original = path.read_bytes().decode('utf-8')
        updated, count = re.subn(pattern, lambda match: match[1] + value + match[2], original)
        if count != 1:
            raise ValueError(f'{name}: expected exactly one example plugin version; found {count}.')
        if updated != original:
            changes.append((path, updated))
    for path, updated in changes:
        path.write_bytes(updated.encode('utf-8'))
    return [path.relative_to(root).as_posix() for path, _ in changes]


def advance_development_version(root, value):
    """Advance to the next minor snapshot without overwriting newer development."""
    if not valid_version(value):
        raise ValueError('Expected a stable release version X.Y.Z.')
    major, minor, _ = map(int, value.split('.'))
    next_version = f'{major}.{minor + 1}.0-SNAPSHOT'
    properties = (root / 'gradle.properties').read_bytes().decode('utf-8')
    versions = re.findall(r'(?m)^version=(.+)$', properties)
    if len(versions) != 1 or not versions[0].endswith('-SNAPSHOT') or not valid_version(versions[0][:-9]):
        raise ValueError('Expected exactly one development version X.Y.Z-SNAPSHOT in gradle.properties.')
    current = versions[0]
    if tuple(map(int, current[:-9].split('.'))) >= (major, minor + 1, 0):
        return []
    targets = {
        'gradle.properties': f'version={current}',
        'build.gradle': f"getOrElse('{current}')",
        'plugin/build.gradle': f"getOrElse('{current}')",
        'scripts/ci-self-check.init.gradle': f"getOrDefault('version', '{current}')",
    }
    changes = []
    for name, marker in targets.items():
        path = root / name
        original = path.read_bytes().decode('utf-8')
        if original.count(marker) != 1:
            raise ValueError(f'{name}: expected 1 development version reference.')
        changes.append((path, original.replace(marker, marker.replace(current, next_version))))
    for path, updated in changes:
        path.write_bytes(updated.encode('utf-8'))
    return [path.relative_to(root).as_posix() for path, _ in changes]


if __name__ == "__main__":
    if (len(sys.argv) not in (2, 3) or not valid_version(sys.argv[1])
            or (len(sys.argv) == 3 and sys.argv[2] not in (
                '--check-remote-tags', '--update-examples', '--advance-development'))):
        sys.exit("Expected a stable release version X.Y.Z, without a v prefix or prerelease suffix.")
    if len(sys.argv) == 3:
        try:
            if sys.argv[2] == '--check-remote-tags':
                check_newer_than_remote_tags(sys.argv[1], sys.stdin)
            elif sys.argv[2] == '--advance-development':
                changed = advance_development_version(Path(__file__).resolve().parent.parent, sys.argv[1])
                print('Updated development versions: ' + ', '.join(changed) if changed
                      else 'Development version is already up to date.')
            else:
                changed = update_examples(Path(__file__).resolve().parent.parent, sys.argv[1])
                if changed:
                    print('Updated example and CI versions: ' + ', '.join(changed))
                    sys.exit(3)
        except (ValueError, OSError) as error:
            sys.exit(f'Error: {error}')
    print(f"Release version: {sys.argv[1]}")
