#!/usr/bin/env python3
"""Validate stable release versions before shell output or publication."""
import re
import sys


def valid_version(value):
    return re.fullmatch(r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)", value) is not None


if __name__ == "__main__":
    if len(sys.argv) != 2 or not valid_version(sys.argv[1]):
        sys.exit("Expected a stable release version X.Y.Z, without a v prefix or prerelease suffix.")
    print(f"Release version: {sys.argv[1]}")
