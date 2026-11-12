"""Immutable diagnostic snapshots: real capture/edit/revalidate/compare and refusal contracts."""

import re

from harness import (
    PROJECT, call, assert_ok, assert_error, assert_error_quality, assert_no_diff,
    assert_diff_contains, read_disk, wait_for_project_ready, e2e_test,
)

MODULE = "CommonModules/Вычисление/Module.bsl"
_HASH = re.compile(r'contentHash:\s*"?([0-9a-f]{16})"?')


def _capture(**filters):
    result = call("problem_snapshots", {"action": "capture", "projectName": PROJECT, **filters})
    assert_ok(result, "capture complete stored diagnostics")
    data = result.structured
    assert data["snapshotId"] and data["scope"]["projectName"] == PROJECT, data
    assert isinstance(data["problemCount"], int) and data["problemCount"] >= 0, data
    return data


def _compare(before, after, **page):
    result = call("problem_snapshots", {"action": "compare", "baselineId": before["snapshotId"],
                                        "snapshotId": after["snapshotId"], **page})
    assert_ok(result, "compare immutable diagnostics")
    data = result.structured
    assert data["matchingBasis"] == "signatureCounts", data
    assert data["total"] == sum(data["counts"].values()), data
    assert data["returned"] == len(data["problems"]), data
    return data


def _validate():
    result = call("revalidate_objects", {"projectName": PROJECT, "objects": ["CommonModule.Вычисление"]})
    assert_ok(result, "complete module validation")
    assert wait_for_project_ready(), "project did not finish validation"


def _replace_source(source):
    current = call("read_module_source", {"projectName": PROJECT, "modulePath": MODULE})
    assert_ok(current, "read source revision")
    token = _HASH.search(current.text or "")
    assert token, current.text
    result = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE, "mode": "replace",
        "expectedHash": token.group(1), "source": source, "skipSyntaxCheck": True,
    })
    assert_ok(result, "write fixture source for native diagnostics")
    assert read_disk("src/" + MODULE).lstrip("\ufeff") == source, "saved source differs"
    _validate()


@e2e_test(tool="problem_snapshots", kind="read")
def test_same_snapshot_and_repeatable_paging_do_not_mutate():
    snapshot = _capture()
    first = _compare(snapshot, snapshot, limit=1)
    assert first["counts"] == {"new": 0, "fixed": 0, "remaining": snapshot["problemCount"]}, first
    assert first == _compare(snapshot, snapshot, limit=1), "immutable comparison changed"
    tail = _compare(snapshot, snapshot, limit=1, offset=snapshot["problemCount"])
    assert tail["problems"] == [] and not tail["hasMore"], tail
    assert tail["counts"] == first["counts"], tail
    assert_no_diff()


@e2e_test(tool="problem_snapshots", kind="write-metadata")
def test_native_errors_appear_shift_and_disappear_after_saved_edits():
    """A broken/no-op capture or line-based matcher fails this real marker workflow."""
    original = read_disk("src/" + MODULE).lstrip("\ufeff")
    _validate()
    before = _capture(modulePaths=[MODULE])
    bad = "Procedure E2eSnapshotProbe() Export\n    E2eSnapshotMissing = ;\nEndProcedure\n"
    _replace_source(bad)
    assert_diff_contains("E2eSnapshotMissing = ;")
    broken = _capture(modulePaths=[MODULE])
    added = _compare(before, broken, limit=1000)
    assert added["counts"]["new"] > 0, added
    assert broken["problemCount"] > 0, broken

    _replace_source("// shift one\n// shift two\n" + bad)
    shifted = _capture(modulePaths=[MODULE])
    unchanged = _compare(broken, shifted, limit=1000)
    assert unchanged["counts"] == {"new": 0, "fixed": 0, "remaining": broken["problemCount"]}, unchanged
    located = [row for row in unchanged["problems"] if "beforeLine" in row and "afterLine" in row]
    assert located and all(row["afterLine"] == row["beforeLine"] + 2 for row in located), unchanged

    _replace_source(original)
    restored = _capture(modulePaths=[MODULE])
    removed = _compare(shifted, restored, limit=1000)
    assert removed["counts"]["fixed"] > 0, removed
    roundtrip = _compare(before, restored)
    assert roundtrip["counts"] == {"new": 0, "fixed": 0, "remaining": before["problemCount"]}, roundtrip
    assert_no_diff("restoring the original source leaves the fixture clean")


@e2e_test(tool="problem_snapshots", kind="read")
def test_normalized_scopes_match_and_different_filters_refuse():
    before = _capture(modulePaths=["src/" + MODULE, MODULE], checkId="SNAPSHOT_NO_SUCH_CHECK")
    after = _capture(modulePaths=[MODULE.lower()], checkId="snapshot_no_such_check")
    assert _compare(before, after)["counts"] == {"new": 0, "fixed": 0, "remaining": 0}
    other = _capture(checkId="another_nonexistent_check")
    result = call("problem_snapshots", {"action": "compare", "baselineId": before["snapshotId"],
                                        "snapshotId": other["snapshotId"]})
    assert_error_quality(assert_error(result), names=["scope"], suggests=["same", "modulePaths"])
    assert_no_diff()


@e2e_test(tool="problem_snapshots", kind="read")
def test_only_last_ten_successful_captures_are_retained():
    oldest = _capture(checkId="snapshot_no_such_check")
    for _ in range(9):
        _capture(checkId="snapshot_no_such_check")
    assert _compare(oldest, oldest)["total"] == 0
    invalid = call("problem_snapshots", {"action": "capture", "projectName": PROJECT, "modulePaths": ["src/"]})
    assert_error_quality(assert_error(invalid), names=["src/"], suggests=["module"])
    assert _compare(oldest, oldest)["total"] == 0, "failed capture evicted a snapshot"
    newest = _capture(checkId="snapshot_no_such_check")
    result = call("problem_snapshots", {"action": "compare", "baselineId": oldest["snapshotId"],
                                        "snapshotId": newest["snapshotId"]})
    assert_error_quality(assert_error(result), names=[oldest["snapshotId"]], suggests=["capture"])
    assert_no_diff()


@e2e_test(tool="problem_snapshots", kind="read")
def test_invalid_actions_conditional_arguments_filters_and_page_values():
    cases = [
        ({}, ["action"], ["capture", "compare"]),
        ({"action": "bad-action"}, ["bad-action"], ["capture"]),
        ({"action": "capture"}, ["projectName"], ["list_projects"]),
        ({"action": "capture", "projectName": "__no_snapshot_project__"},
         ["__no_snapshot_project__"], ["list_projects"]),
        ({"action": "capture", "projectName": PROJECT, "severity": "bad-severity"},
         ["bad-severity"], ["MAJOR"]),
        ({"action": "capture", "projectName": PROJECT, "modulePaths": ["CommonModules/__absent__/Module.bsl"]},
         ["__absent__"], ["list_modules"]),
        ({"action": "capture", "projectName": PROJECT, "baselineId": "x"}, ["baselineId"], ["compare"]),
        ({"action": "compare"}, ["baselineId"], ["capture"]),
        ({"action": "compare", "baselineId": "x"}, ["snapshotId"], ["capture"]),
        ({"action": "compare", "baselineId": "unknown-before", "snapshotId": "unknown-after"},
         ["unknown-before"], ["capture"]),
        ({"action": "compare", "baselineId": "x", "snapshotId": "y", "limit": 1.5}, ["1.5"], ["integer"]),
        ({"action": "compare", "baselineId": "x", "snapshotId": "y", "offset": -1}, ["-1"], ["0"]),
        ({"action": "compare", "baselineId": "x", "snapshotId": "y", "severity": "MAJOR"},
         ["severity"], ["capture"]),
    ]
    for arguments, names, suggests in cases:
        result = call("problem_snapshots", arguments)
        assert_error_quality(assert_error(result, str(arguments)), names=names, suggests=suggests)
    assert_no_diff()
