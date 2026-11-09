"""Explicit BSL editor activation, navigation and no source mutation."""
from harness import call, assert_ok, assert_error, assert_error_quality, assert_no_diff, e2e_test, PROJECT

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


@e2e_test(tool="open_in_editor", kind="read")
def test_open_bsl_by_english_or_russian_object_type_and_configuration_path():
    for object_name, method_name, expected_path in (
        ("CommonModule.Calc", "aDd", MODULE),
        ("ОбщийМодуль.Calc", "aDd", MODULE),
        ("CommonModule.Вычисление", "маркер", "CommonModules/Вычисление/Module.bsl"),
        ("ОбщийМодуль.Вычисление", "маркер", "CommonModules/Вычисление/Module.bsl"),
    ):
        r = call("open_in_editor", {
            "projectName": PROJECT, "objectName": object_name, "methodName": method_name,
        })
        assert_ok(r, "open by " + object_name)
        assert r.structured["opened"] is True
        assert r.structured["modulePath"] == expected_path
        assert (r.structured["line"], r.structured["column"]) == (1, 1)
    r = call("open_in_editor", {
        "projectName": PROJECT, "modulePath": "Configuration/ManagedApplicationModule.bsl",
    })
    assert_ok(r, "open configuration module by explicit path")
    assert r.structured["modulePath"] == "Configuration/ManagedApplicationModule.bsl"
    assert_no_diff()


@e2e_test(tool="open_in_editor", kind="read")
def test_open_bsl_metadata_address_validation_preserves_source():
    cases = [
        ({}, ["modulePath", "objectName"], ["required"]),
        ({"modulePath": MODULE, "objectName": "CommonModule.Calc"},
         ["modulePath", "objectName"], ["pass only one"]),
        ({"modulePath": MODULE, "moduleType": "Module"},
         ["moduleType", "objectName"], ["drop one"]),
        ({"objectName": "NoDot"}, ["objectName"], ["Type.Name"]),
        ({"objectName": "UnknownType.Name"}, ["UnknownType"], ["metadata type"]),
        ({"objectName": "Document.Order", "moduleType": "UnknownModule"},
         ["UnknownModule"], ["Allowed"]),
        ({"objectName": "Документ.Заказ", "moduleType": "FormModule"},
         ["formName", "FormModule"], ["required"]),
        ({"objectName": "Document.Order", "moduleType": "CommandModule"},
         ["commandName", "CommandModule"], ["required"]),
        ({"objectName": "Document.Order", "moduleType": "FormModule", "formName": "../../Outside"},
         ["modulePath"], ["project-relative"]),
        ({"objectName": "CommonModule.Calc", "methodName": "Add", "line": 1},
         ["methodName", "line"], ["cannot be combined"]),
    ]
    for target, names, suggests in cases:
        r = call("open_in_editor", {"projectName": PROJECT, **target})
        error = assert_error(r, "invalid module selector: " + repr(target))
        assert_error_quality(error, names=names, suggests=suggests)
    assert_no_diff()
