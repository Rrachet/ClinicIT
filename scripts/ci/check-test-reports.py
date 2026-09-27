#!/usr/bin/env python3
"""Fail if the Surefire reports show failures, errors, skipped tests, or no tests at all.

The PostgreSQL-backed tests are designed to be *skipped* where no database is available
(so a laptop without Docker can still run the unit tests). In CI that must never happen
silently: a misconfigured database service would otherwise turn ~200 integration tests
into a green build that tested nothing.

    python3 scripts/ci/check-test-reports.py target/surefire-reports
"""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

reports = sorted(Path(sys.argv[1] if len(sys.argv) > 1 else "target/surefire-reports").glob("TEST-*.xml"))
totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
skipped = []
for report in reports:
    suite = ET.parse(report).getroot()
    for key in totals:
        totals[key] += int(suite.get(key, 0))
    for case in suite.iter("testcase"):
        if case.find("skipped") is not None:
            skipped.append(f"{case.get('classname')}.{case.get('name')}")

print(f"{len(reports)} test classes: {totals}")
problems = []
if totals["tests"] == 0:
    problems.append("no tests ran")
if totals["failures"] or totals["errors"]:
    problems.append(f"{totals['failures']} failures, {totals['errors']} errors")
if skipped:
    problems.append(f"{len(skipped)} skipped (is PostgreSQL reachable?): " + ", ".join(skipped[:10]))
if problems:
    print("FAIL: " + "; ".join(problems))
    sys.exit(1)
print("OK: every test ran and passed")
