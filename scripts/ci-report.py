#!/usr/bin/env python3
"""Actions summaries based on actual JUnit XML or DepLens JSON; no GitHub writes."""
import argparse
import json
from pathlib import Path
import xml.etree.ElementTree as ET


def tests(directory, outcome):
    files = list(Path(directory).glob("TEST-*.xml"))
    print(f"Build/test step: **{outcome or 'not run'}**")
    if not files:
        print("No JUnit test reports were produced; test success is unconfirmed.")
        return 1
    counts = dict.fromkeys(("tests", "failures", "errors", "skipped"), 0)
    for file in files:
        suite = ET.parse(file).getroot()
        for key in counts:
            counts[key] += int(suite.get(key, 0))
    print(" | ".join(f"{key}: {value}" for key, value in counts.items()))
    return int(outcome != "success" or counts["failures"] > 0 or counts["errors"] > 0 or counts["tests"] == 0)


def updates(file):
    if not Path(file).is_file():
        print("No DepLens report was produced; the update check did not complete.")
        return 1
    report = json.loads(Path(file).read_text())
    entries = report["entries"]
    counts = {status: sum(status in entry.get("statuses", []) for entry in entries)
              for status in ("UPDATE_AVAILABLE", "NO_MATCHING_CANDIDATES", "FAILED")}
    counts["RECOMMENDED_UPDATES"] = sum(
        "UPDATE_AVAILABLE" in entry.get("statuses", []) and entry.get("recommendedVersion") is not None
        and not {"FAILED", "NOT_CHECKED"}.intersection(entry.get("statuses", [])) for entry in entries)
    counts["AVAILABLE_ONLY"] = counts["UPDATE_AVAILABLE"] - counts["RECOMMENDED_UPDATES"]
    # The JSON preserves local plugin definitions, which have no external update target.
    counts["NOT_CHECKED"] = sum(
        "NOT_CHECKED" in entry.get("statuses", [])
        and "FAILED" not in entry.get("statuses", [])
        and not {"LOCAL_CONVENTION", "PLUGIN_DEFINITION"}.intersection(entry.get("pluginStates", []))
        for entry in entries)
    print(" | ".join(f"{key}: {value}" for key, value in counts.items()))
    print("Counts refer to inventory entries, including consuming projects; see the JSON artifact for details.")
    complete = report.get("complete") is True and report.get("checksComplete") is True
    print(f"Inventory/checks complete: **{str(complete).lower()}**")
    return int(not complete or counts["FAILED"] > 0 or counts["NOT_CHECKED"] > 0)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("kind", choices=("tests", "updates"))
    parser.add_argument("path")
    parser.add_argument("--outcome", default="success")
    args = parser.parse_args()
    raise SystemExit(tests(args.path, args.outcome) if args.kind == "tests" else updates(args.path))
