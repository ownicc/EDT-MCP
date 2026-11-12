"""No-platform probes require an explicit stand declaration before any MCP call."""

import importlib.util
import os
from pathlib import Path
import unittest
from unittest import mock


E2E_DIR = Path(__file__).resolve().parents[1]


def _load(name, relative):
    spec = importlib.util.spec_from_file_location(name, E2E_DIR / relative)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _cases(no_platform="", live=""):
    with mock.patch.dict(os.environ, {
            "EDT_MCP_NO_PLATFORM": no_platform, "EDT_MCP_LIVE_INFOBASE": live}):
        harness = _load("no_platform_harness", "harness.py")
        with mock.patch.dict("sys.modules", {"harness": harness}):
            create = _load("no_platform_create", "tools/test_create_infobase.py")
            imports = _load("no_platform_import", "tools/test_import_project_from_file.py")
    return harness, (
        (create, create.test_no_platform_runtime_errors_actionably, "create_infobase"),
        (imports, imports.test_without_a_platform_the_call_is_refused_at_once,
         "import_project_from_file"),
    )


class NoPlatformGateTest(unittest.TestCase):
    def test_undeclared_stands_skip_before_any_call_without_enabling_live_tests(self):
        for value in ("", "0", "false", "true", "typo"):
            harness, cases = _cases(no_platform=value)
            self.assertFalse(harness.LIVE_INFOBASE)
            for module, probe, tool in cases:
                with self.subTest(value=value, tool=tool), \
                        mock.patch.object(module, "call", side_effect=AssertionError(
                            "no-platform probe called MCP before checking the stand")) as called:
                    with self.assertRaisesRegex(harness.E2ESkip, "EDT_MCP_NO_PLATFORM=1"):
                        probe()
                    called.assert_not_called()
            with self.assertRaises(harness.E2ESkip):
                harness.requires_live_infobase()

    def test_live_stand_skips_even_with_a_conflicting_no_platform_declaration(self):
        harness, cases = _cases(no_platform="1", live="1")
        for module, probe, tool in cases:
            with self.subTest(tool=tool), \
                    mock.patch.dict("sys.modules", {"harness": harness}), \
                    mock.patch.object(module, "call") as called:
                with self.assertRaises(harness.E2ESkip):
                    probe()
                called.assert_not_called()

    def test_declared_no_platform_stand_keeps_the_negative_contract(self):
        harness, cases = _cases(no_platform="1")
        for module, probe, tool in cases:
            with self.subTest(tool=tool):
                error = harness.Result({"result": {"isError": True, "content": [{
                    "type": "text", "text": "No platform. Use import_configuration_from_xml. "
                    "No project was created."}]}})
                projects = harness.Result({"result": {"content": [{"type": "text", "text": ""}]}})
                with mock.patch.object(module, "call", side_effect=[error, projects]) as called:
                    probe()
                self.assertEqual(tool, called.call_args_list[0].args[0])
        self.assertFalse(harness.LIVE_INFOBASE)
        with self.assertRaises(harness.E2ESkip):
            harness.requires_live_infobase()

    def test_declared_no_platform_stand_does_not_hide_success_or_unrelated_errors(self):
        harness, cases = _cases(no_platform="1")
        for module, probe, tool in cases:
            for result in (
                    {"isError": False, "content": [{"type": "text", "text": "created"}]},
                    {"isError": True, "content": [{"type": "text", "text": "Conversion failed"}]}):
                with self.subTest(tool=tool, result=result), mock.patch.object(
                        module, "call", return_value=harness.Result({"result": result})):
                    with self.assertRaises(harness.E2EAssertion):
                        probe()


if __name__ == "__main__":
    unittest.main()
