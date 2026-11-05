"""Prints the results of instrumented tests (JUnit XML) so failures show in the CI log."""
import glob
import sys
import xml.etree.ElementTree as ET

files = glob.glob(sys.argv[1] + "/**/*.xml", recursive=True)
total = failed = 0
for path in files:
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError:
        continue
    for case in root.iter("testcase"):
        total += 1
        name = f"{case.get('classname', '').split('.')[-1]}.{case.get('name')}"
        problems = list(case.iter("failure")) + list(case.iter("error"))
        skipped = case.find("skipped") is not None
        if problems:
            failed += 1
            print(f"FAILED  {name}")
            for problem in problems:
                lines = (problem.text or problem.get("message") or "").strip().splitlines()
                for line in lines[:12]:
                    print(f"        {line}")
        else:
            print(f"{'skipped' if skipped else 'passed '} {name}")
print(f"{total} tests, {failed} failed")
