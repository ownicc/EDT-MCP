"""Unified verification evidence over real EDT markers and selected local JUnit files."""

import json
from pathlib import Path
import tempfile

from harness import (
    PROJECT, call, assert_ok, assert_error, assert_error_quality, assert_no_diff,
    read_disk, e2e_test,
)
from tools.test_problem_snapshots import MODULE, _capture, _validate, _replace_source


def _report(baseline, **options):
    result = call("verification_report", {"baselineId": baseline["snapshotId"], "waitSeconds": 15, **options})
    assert_ok(result, "assemble verification evidence")
    data = result.structured
    assert data["scope"] == baseline["scope"], data
    assert data["baselineCapturedAt"] == baseline["capturedAt"], data
    assert data["validation"]["state"] == "complete", data
    assert data["diagnostics"]["complete"] is True, data
    assert data["diagnostics"]["returned"] == len(data["diagnostics"]["problems"]), data
    return data


@e2e_test(tool="verification_report", kind="read")
def test_unchanged_scope_and_selected_test_evidence():
    _validate()
    before = _capture(modulePaths=[MODULE])
    data = _report(before, limit=1)
    assert data["verdict"] == "noNewProblems", data
    assert data["tests"]["state"] == "notSelected", data
    assert data["diagnostics"]["counts"] == {"new": 0, "fixed": 0, "remaining": before["problemCount"]}, data
    assert data["diagnostics"]["truncated"] == (before["problemCount"] > 1), data
    with tempfile.TemporaryDirectory(prefix="edt-verification-e2e-") as directory:
        path = Path(directory) / "junit.xml"
        for outcome, expected, verdict in [
            ("", "passed", "noNewProblems"),
            ('<failure message="expected synthetic test failure"/>', "failed", "regression"),
            ('<error message="expected synthetic runner error"/>', "failed", "regression"),
            ("<skipped/>", "skipped", "incomplete"),
        ]:
            path.write_text(f'<testsuite tests="1"><testcase name="selected">{outcome}</testcase></testsuite>', encoding="utf-8")
            data = _report(before, testReports=[str(path.resolve())])
            assert data["tests"]["state"] == expected, data
            assert data["verdict"] == verdict, data
            assert data["tests"]["total"] == 1, data
            assert len(data["tests"]["reports"][0]["sha256"]) == 64, data
        path.unlink()
        data = _report(before, testReports=[str(path.resolve())])
        assert data["verdict"] == "incomplete" and not data["tests"]["complete"], data
    assert_no_diff()


@e2e_test(tool="verification_report", kind="write-metadata")
def test_report_detects_real_new_and_fixed_diagnostics_after_saved_changes():
    original = read_disk("src/" + MODULE).lstrip("\ufeff")
    _validate()
    before = _capture(modulePaths=[MODULE])
    _replace_source("Procedure E2eVerificationProbe() Export\n    E2eVerificationMissing = ;\nEndProcedure\n")
    broken = _report(before, limit=1)
    assert broken["verdict"] == "regression", broken
    assert broken["diagnostics"]["counts"]["new"] > 0, broken
    assert broken["diagnostics"]["clean"] is False, broken
    bad_baseline = {"snapshotId": broken["diagnostics"]["snapshotId"], "scope": broken["scope"], "capturedAt": broken["capturedAt"]}
    _replace_source(original)
    restored = _report(bad_baseline)
    assert restored["diagnostics"]["counts"]["fixed"] > 0, restored
    roundtrip = _report(before)
    assert roundtrip["diagnostics"]["counts"] == {"new": 0, "fixed": 0, "remaining": before["problemCount"]}, roundtrip
    assert_no_diff()


@e2e_test(tool="verification_report", kind="read")
def test_invalid_baselines_and_report_arguments_are_actionable():
    for args, names, suggestions in [
        ({}, ["baselineId"], ["problem_snapshots"]),
        ({"baselineId": "__missing_verification_baseline__"}, ["__missing_verification_baseline__"], ["capture"]),
        ({"baselineId": "x", "waitSeconds": 16}, ["waitSeconds", "16"], ["1", "15"]),
        ({"baselineId": "x", "limit": 0}, ["limit", "0"], ["1", "1000"]),
        ({"baselineId": "x", "testReports": ["relative.xml"]}, ["relative.xml"], ["absolute"]),
    ]:
        result = call("verification_report", args)
        assert_error_quality(assert_error(result, json.dumps(args)), names=names, suggests=suggestions)
    assert_no_diff()
