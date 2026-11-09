#!/usr/bin/env python3
"""Verify an imported GTHost JUnit receipt against the exact local P1 import source."""

import hashlib
import json
import pathlib
import sys
import xml.etree.ElementTree as ET


def digest(path: pathlib.Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main(receipt_path: pathlib.Path) -> None:
    receipt = json.loads(receipt_path.read_text())
    root = pathlib.Path(__file__).resolve().parent.parent
    assert receipt["build_host"] == "gthost-build01"
    assert len(receipt["remote_source_commit"]) == 40
    for relative, expected in receipt["source_sha256"].items():
        path = root / relative
        assert path.is_file() and digest(path) == expected, f"source differs: {relative}"
    result = pathlib.Path(receipt["junit_xml"])
    assert digest(result) == receipt["junit_sha256"], "JUnit receipt differs"
    suite = ET.parse(result).getroot()
    assert suite.tag == "testsuite"
    assert suite.attrib["name"] == receipt.get("suite_name", "com.nozzleitall.desktop.ProjectLibraryImportTest")
    assert suite.attrib["hostname"] == "gthost-build01"
    counts = (int(suite.attrib["tests"]), int(suite.attrib["skipped"]),
              int(suite.attrib["failures"]), int(suite.attrib["errors"]))
    assert counts == tuple(receipt["focused_counts"])
    assert len(suite.findall("testcase")) == counts[0]
    print(f"PASS: imported GTHost {suite.attrib['name']} receipt matches current source hashes; {counts[0]} tests, {counts[2]} failures")


if __name__ == "__main__":
    main(pathlib.Path(sys.argv[1]))
