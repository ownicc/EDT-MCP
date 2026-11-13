"""
e2e tests for get_server_status (kind: read).

Self-diagnosis tool. It takes NO input parameters (getInputSchema() is an empty
JsonSchemaBuilder.object().build()) and execute() reads NOTHING from the params
map. ResponseType is JSON, so the real payload is a dict in r.structured (NOT
r.text — for a JSON tool r.text is just the placeholder/content text).

It returns a fixed snapshot of the running server:
  port, running, transports { http, ipc }, protocolVersion, pluginVersion, edtVersion,
  enabledTools, totalTools, plainTextMode, checksFolderConfigured, authEnabled,
  formRenderFlags { nativeFormLayoutRender, nativeFormBufferedLayoutRender }.

Stable, code-anchored expectations (from the real impl, verified against source):
  - protocolVersion == McpConstants.PROTOCOL_VERSION == "2025-11-25"
  - running == true  (we are talking to a LIVE server on :8765, so server!=null
    and server.isRunning() must be true; a broken tool that hardcodes the
    headless `false` branch would fail here)
  - totalTools / enabledTools are positive ints (registry is populated)
  - formRenderFlags carries BOTH named JVM-flag keys, each with the render mode
    sampled at plugin activation (the diagnostic for a blank form screenshot).
    EDT binds the modes and its offscreen handler once at class initialization.
    atStartup records the mode at MCP activation; optional requested is the raw
    current system property. Changing the launch configuration requires an EDT restart.

Optional project probes check metadata, diagnostics, or local-runtime prerequisites.
The negative matrix covers missing project handles and invalid parameter combinations.
Unknown extra arguments retain the existing compatibility behavior.

Every test ends with assert_no_diff(): a read/diagnosis tool must never mutate
the project on disk.
"""

from harness import (
    PROJECT, call, assert_ok, assert_error, assert_contains, assert_no_diff, e2e_test,
)

# Code-anchored constant from McpConstants.PROTOCOL_VERSION; if the server spoke a
# different protocol version than it reports here, that is a real contract break.
EXPECTED_PROTOCOL_VERSION = "2025-11-25"


# ──────────────────────────────────────────────────────────────────────────────
# Happy path
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="get_server_status", kind="read")
def test_returns_live_server_snapshot_and_does_not_mutate():
    r = call("get_server_status", {})
    assert_ok(r, "get_server_status happy path")

    # JSON response type -> payload is in structured, not text.
    s = r.structured
    if not isinstance(s, dict):
        raise AssertionError(
            "get_server_status must return a JSON object in structuredContent; got: %r"
            % (s,))

    if "readiness" in s:
        raise AssertionError("A server-only snapshot must not request project readiness")

    if s.get("hostKind") != "WINDOWED":
        raise AssertionError("Windowed HTTP e2e must report hostKind=WINDOWED")

    transports = s.get("transports")
    if not isinstance(transports, dict) or transports.get("http") is not True:
        raise AssertionError("HTTP e2e requires an active HTTP transport")
    if not isinstance(transports.get("ipc"), bool):
        raise AssertionError("IPC listener status must be a boolean")
    if set(transports) != {"http", "ipc"}:
        raise AssertionError("Transport status must contain only listener booleans")

    # --- the server is LIVE: prove we hit the running-server branch, not the
    # headless fallback. A no-op/headless-stubbed tool would report running=false.
    if s.get("running") is not True:
        raise AssertionError(
            "expected running=true from a live server on :8765; snapshot=%r" % (s,))

    # --- protocol version is the code-anchored constant. This MUST match exactly;
    # a wrong/placeholder value here is a real regression (mutation thinking).
    if s.get("protocolVersion") != EXPECTED_PROTOCOL_VERSION:
        raise AssertionError(
            "expected protocolVersion=%r, got %r"
            % (EXPECTED_PROTOCOL_VERSION, s.get("protocolVersion")))

    # --- port is a real positive port number (live branch put server.getPort()).
    port = s.get("port")
    if not isinstance(port, int) or port <= 0:
        raise AssertionError("expected a positive integer port, got %r" % (port,))

    # --- tool counts: the registry is populated, so both are positive ints and
    # enabled <= total. A broken registry snapshot (0 tools) would fail here.
    total = s.get("totalTools")
    enabled = s.get("enabledTools")
    if not isinstance(total, int) or total <= 0:
        raise AssertionError("expected totalTools to be a positive int, got %r" % (total,))
    if not isinstance(enabled, int) or enabled <= 0:
        raise AssertionError("expected enabledTools to be a positive int, got %r" % (enabled,))
    if enabled > total:
        raise AssertionError(
            "enabledTools (%r) must not exceed totalTools (%r)" % (enabled, total))

    # --- version strings are present and non-empty (plugin + EDT identity).
    for key in ("pluginVersion", "edtVersion"):
        val = s.get(key)
        if not isinstance(val, str) or not val.strip():
            raise AssertionError("expected non-empty string %s, got %r" % (key, val))

    # --- the three security/diagnostic booleans must be real booleans (the tool
    # deliberately emits booleans, never the token value or the checks-folder path).
    for key in ("plainTextMode", "checksFolderConfigured", "authEnabled"):
        if not isinstance(s.get(key), bool):
            raise AssertionError(
                "expected boolean %s, got %r" % (key, s.get(key)))

    # --- formRenderFlags: the blank-screenshot diagnostic. BOTH named keys are the
    # load-bearing contract; each must expose the render mode at plugin activation.
    flags = s.get("formRenderFlags")
    if not isinstance(flags, dict):
        raise AssertionError("expected formRenderFlags object, got %r" % (flags,))
    for fk in ("nativeFormLayoutRender", "nativeFormBufferedLayoutRender"):
        if fk not in flags:
            raise AssertionError(
                "formRenderFlags must contain key %r; got keys %r"
                % (fk, sorted(flags.keys())))
        state = flags[fk]
        if not isinstance(state, dict):
            raise AssertionError(
                "formRenderFlags[%r] must be an object, got %r" % (fk, state))
        if state.get("atStartup") not in {"on", "off", "unknown"}:
            raise AssertionError(
                "formRenderFlags[%r].atStartup must be on, off, or unknown; got %r"
                % (fk, state.get("atStartup")))
        if "effective" in state:
            raise AssertionError(
                "formRenderFlags[%r] must NOT claim an effective mode: the renderer binds it at "
                "HippoLayoutService class-init and this tool cannot observe that moment; got %r"
                % (fk, state))
        if "requested" in state and not isinstance(state["requested"], str):
            raise AssertionError(
                "formRenderFlags[%r].requested must be a string when present; got %r"
                % (fk, state["requested"]))

    # SECURITY contract: the snapshot must NEVER leak the raw auth token or the
    # checks-folder path — only the derived booleans above. Assert no token/path
    # key sneaked into the payload.
    for forbidden in ("authToken", "token", "checksFolder", "secret", "socket"):
        if forbidden in s:
            raise AssertionError(
                "snapshot must NOT expose %r (security: booleans only); snapshot=%r"
                % (forbidden, s))

    assert_no_diff("a diagnosis/read tool must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# Negative / robustness matrix
#
# The tool has NO parameters (no required/enum/path/XOR), so there is no
# client-reachable VALIDATION error to assert error-quality on. The matrix below
# is therefore robustness coverage (graceful handling of an unexpected arg),
# which IS client-reachable and would catch a real regression.
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="get_server_status", kind="read")
def test_ignores_unexpected_argument_gracefully():
    # The tool declares no params and reads none. An unknown extra argument must
    # be IGNORED, not cause a failure. A tool that rejected/crashed on an unknown
    # key would be a robustness regression -> this test would then fail.
    r = call("get_server_status", {"bogusUnknownArg_e2e": "x"})
    assert_ok(r, "unexpected extra arg must be ignored, not error")

    # And the snapshot must still be the same well-formed payload: the extra arg
    # changed nothing, so the stable protocolVersion + running flag still hold.
    s = r.structured
    if not isinstance(s, dict):
        raise AssertionError(
            "expected the same JSON snapshot despite the extra arg; got %r" % (s,))
    if s.get("running") is not True:
        raise AssertionError(
            "extra arg must not disturb the snapshot; expected running=true, got %r"
            % (s.get("running"),))
    if s.get("protocolVersion") != EXPECTED_PROTOCOL_VERSION:
        raise AssertionError(
            "extra arg must not disturb the snapshot; expected protocolVersion=%r, got %r"
            % (EXPECTED_PROTOCOL_VERSION, s.get("protocolVersion")))

    assert_no_diff("an ignored-arg call must not touch the project on disk")


@e2e_test(tool="get_server_status", kind="read")
def test_ready_project_metadata_and_diagnostics_prerequisites():
    for operation in ("metadata", "diagnostics"):
        r = call("get_server_status", {"projectName": PROJECT, "operation": operation})
        assert_ok(r, operation + " prerequisites")
        readiness = r.structured["readiness"]
        assert readiness["projectName"] == PROJECT, readiness
        assert readiness["operation"] == operation, readiness
        assert readiness["state"] == "ready", readiness
        assert readiness["ready"] is True, readiness
        assert readiness["runtimeRequired"] is False, readiness
        assert readiness["actions"] == [], readiness
    assert_no_diff("project readiness must not change project files")


@e2e_test(tool="get_server_status", kind="read")
def test_missing_project_reports_recovery_without_failing_server_snapshot():
    r = call("get_server_status", {"projectName": "E2E_Missing_Readiness_Project"})
    assert_ok(r, "missing project is a successful diagnostic snapshot")
    readiness = r.structured["readiness"]
    assert readiness["state"] == "missing", readiness
    assert readiness["reason"] == "project_missing", readiness
    assert readiness["ready"] is False, readiness
    assert readiness["operation"] == "metadata", readiness
    assert "list_projects" in " ".join(readiness["actions"]), readiness
    assert "transports" in r.structured and "formRenderFlags" in r.structured
    assert_no_diff("probing a missing project must not create one")


@e2e_test(tool="get_server_status", kind="read")
def test_project_probe_rejects_invalid_parameters():
    cases = (
        ({"operation": "runtime"}, "projectName"),
        ({"projectName": " "}, "projectName"),
        ({"projectName": PROJECT, "operation": "unknown"}, "operation"),
    )
    for arguments, expected in cases:
        error = assert_error(call("get_server_status", arguments), "invalid readiness parameters")
        assert_contains(error, expected, "error names the invalid argument")
    assert_no_diff("invalid readiness parameters must not change project files")
