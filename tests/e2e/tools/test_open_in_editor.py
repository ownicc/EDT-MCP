"""Explicit BSL editor activation, navigation and no source mutation."""
from harness import call, assert_ok, assert_error, assert_no_diff, e2e_test, PROJECT

MODULE = "CommonModules/Calc/Module.bsl"


@e2e_test(tool="open_in_editor", kind="read")
def test_open_bsl_method_and_line_preserves_source():
    r = call("open_in_editor", {
        "projectName": PROJECT, "modulePath": MODULE, "methodName": "aDd",
    })
    assert_ok(r, "open method")
    assert r.structured["opened"] is True
    assert r.structured["modulePath"] == MODULE
    assert r.structured["line"] == 1
    r = call("open_in_editor", {
        "projectName": PROJECT, "modulePath": MODULE, "line": 6, "column": 2,
    })
    assert_ok(r, "open position")
    assert (r.structured["line"], r.structured["column"]) == (6, 2)
    assert_no_diff()


@e2e_test(tool="open_in_editor", kind="read")
def test_open_bsl_refuses_missing_method_and_conflicting_selectors():
    r = call("open_in_editor", {
        "projectName": PROJECT, "modulePath": MODULE, "methodName": "NoSuchMethod_OpenEditor",
    })
    assert_error(r, "missing method")
    r = call("open_in_editor", {
        "projectName": PROJECT, "modulePath": MODULE, "methodName": "Add", "line": 1,
    })
    assert_error(r, "conflicting selectors")
    r = call("open_in_editor", {
        "projectName": PROJECT, "modulePath": MODULE, "line": 1, "column": 999999,
    })
    assert_error(r, "column outside line")
    assert_no_diff()
