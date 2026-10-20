#!/usr/bin/env python3
"""Validate stable release versions before shell output or publication."""
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


if __name__ == "__main__":
    if (len(sys.argv) not in (2, 3) or not valid_version(sys.argv[1])
            or (len(sys.argv) == 3 and sys.argv[2] != '--check-remote-tags')):
        sys.exit("Expected a stable release version X.Y.Z, without a v prefix or prerelease suffix.")
    if len(sys.argv) == 3:
        try:
            check_newer_than_remote_tags(sys.argv[1], sys.stdin)
        except ValueError as error:
            sys.exit(f'Error: {error}')
    print(f"Release version: {sys.argv[1]}")
