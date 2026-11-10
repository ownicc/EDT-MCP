# Testing EDT MCP Server

## Architecture

The plugin has three verification layers: build/unit tests, live tool E2E, and
[MCP protocol conformance](conformance/README.md). The standalone proxy has its
own build and integration tests, described below.

### 0. Build

**Requirements:** run the plugin build on **JDK 25**, using the Maven version pinned
in [setup-maven](../.github/actions/setup-maven/action.yml) (currently **3.9.16**).
Tycho 5.0.4 reads the EDT 2026.2 platform's Java 25 classes and runs its bundles
during tests. The plugin's emitted bytecode and `Bundle-RequiredExecutionEnvironment`
remain Java 17; that does not make JDK 17 sufficient to run this build. Maven 3.10
is not supported by the current Tycho resolver.

The plugin must be built with the official build script `source/compile.sh`. It runs the Tycho build (`mvn clean verify`, which also executes the unit tests) and packages the p2 update site into a versioned zip:
```bash
bash source/compile.sh \
  --java-home /path/to/jdk-25 \
  --maven-home /path/to/apache-maven-3.9.16
```
Outputs:
- update-site zip: `source/dist/MCP-EDT.v<version>.zip`
- p2 repository: `mcp/repositories/com.ditrix.edt.mcp.server.repository/target/repository`

To run only the unit tests (without packaging the archive) you can invoke Maven directly:
```bash
export JAVA_HOME=/path/to/jdk-25
export PATH="$JAVA_HOME/bin:/path/to/apache-maven-3.9.16/bin:$PATH"
cd mcp
mvn clean verify
```

### 1. Unit Tests (Tycho Surefire)

Located in `mcp/tests/com.ditrix.edt.mcp.server.tests/`

These are JUnit 4 tests that run inside the Eclipse/Tycho build without requiring a running EDT instance. They cover:

- **Protocol layer**: `JsonSchemaBuilder`, `JsonUtils`, `GsonProvider`, `McpConstants`
- **JSON-RPC DTOs**: `JsonRpcRequest`, `JsonRpcResponse`, `JsonRpcError`
- **Tool results**: `ToolResult`, `ToolCallResult`, `ToolsListResult`

Unit tests run automatically during the build (see **0. Build**). Results are in:
```
mcp/tests/com.ditrix.edt.mcp.server.tests/target/surefire-reports/
```

### 2. E2E Tests (Python HTTP client)

Located in `tests/e2e/` — a modular black-box suite: `harness.py` (HTTP/SSE client +
git-fixture isolation + assertions), `run_all.py` (serial runner, discovery,
`--junit-xml`, `--filter`), and one `tools/test_<tool>.py` per tool. A coverage ratchet
(`tools/test_coverage_ratchet.py`) fails the suite if `tools/list` advertises a tool with
no test.

These tests send real HTTP requests to a running MCP server and validate every tool. They require:
- A running EDT instance with the MCP plugin installed
- The `TestConfiguration` project loaded in EDT

**Local EDT setup.** Build the plugin first (see **0. Build**) and use a writable
development copy of EDT 2026.2, rather than modifying the system installation.
Installing through the UI is one local option:

1. In EDT: **Help → Install New Software… → Add… → Archive…**, select
   `source/dist/MCP-EDT.v<version>.zip` (or **Local…** → the `repository` folder),
   install `com.ditrix.edt.mcp.server.feature`, restart EDT.
2. Import the `TestConfiguration` project into the EDT workspace and wait until
   derived data is built.
3. Make sure the MCP server is listening on port 8765 (the plugin starts it
   automatically inside the EDT workbench).

CI automates this setup: it installs Eclipse and EDT through `p2 director`, installs
the built plugin into a writable runner directory, starts the headless host under
Xvfb with `EDT_MCP_AUTO_START=true`, and imports fixtures using
`EDT_MCP_IMPORT_PROJECTS`. See the runnable phases in
[e2e-tests.yml](../.github/workflows/e2e-tests.yml). No system-wide installation,
Docker image, or self-hosted runner is required.

**Running locally:**
```bash
# Make sure EDT is running with MCP server on port 8765
python tests/e2e/run_all.py

# Or with custom settings:
python tests/e2e/run_all.py --host localhost --port 8765 --project TestConfiguration

# Run a subset (substring match on tool/test name):
python tests/e2e/run_all.py --filter modify_metadata

# Generate JUnit XML report:
python tests/e2e/run_all.py --junit-xml results.xml
```

Each mutating test isolates itself with a **git fixture**: it snapshots the
`TestConfiguration/` tree, runs, then restores it (`git checkout` + `git clean`), so a
failed test never leaks artifacts into the next one. The runner reports `fixture clean:
True/False` at the end; a dirty fixture fails the run even if every functional test passed.

**E2E tests cover all tools** — one `tools/test_<tool>.py` per tool (happy path + negative
+ error-quality + anti-cheat), enforced by the coverage ratchet. The unified metadata CRUD
tools are covered by `test_create_metadata.py`, `test_modify_metadata.py`,
`test_delete_metadata.py`, and `test_get_metadata_details.py` (basic / full / `assignable`).

### Launch-preset wire checks

Set `EDT_MCP_TOOL_PRESET=analysis-only` or `EDT_MCP_TOOL_PRESET=code-review` on the **EDT
process before startup**, alongside the other `EDT_MCP_*` launch overrides. Set
`EDT_MCP_PROGRESSIVE_DISCLOSURE=false` there to expose the complete preset. The preset is
session-only: stored Tools preferences are untouched by launch-preset resolution and registry
reads, including migration state. Predefined override IDs are `all-tools`, `analysis-only`,
`code-review`, and `development`. IDs are case-insensitive and trimmed.
Resolution happens once on first use, including bridge calls and manual starts without early
startup. An unset variable, a blank value or `custom` uses the stored selection; explicit
`custom` logs one INFO line explaining that there is no launch override. An unknown ID is
ignored with one WARNING listing only the predefined override IDs and the stored-selection hint.

The Tools tab loads saved settings, runs their normal migrations, and shows the active launch
preset in a read-only note. Unchanged OK/Apply preserves the saved selection and preset ID;
intentional edits are saved for a launch without the override and do not change this session.
Use a fresh workspace for each CI preset launch: these wire checks prove the advertised
catalogue, while the unit tests prove that a session override is not persisted.

Launch EDT separately for each read-only preset, and pass the **same** preset to the test
process. With clean fixtures, run from `tests/e2e`:

```bash
EDT_MCP_TOOL_PRESET=analysis-only python -m tools.test_preset_launch
# Restart EDT with EDT_MCP_TOOL_PRESET=code-review, then:
EDT_MCP_TOOL_PRESET=code-review python -m tools.test_preset_launch
```

Use `MCP_HOST`, `MCP_PORT`, and `MCP_PROJECT` for non-default harness settings. The module
uses the shared MCP handshake and HTTP client to check `tools/list`: every tool lacking
`readOnlyHint=true` is absent except `compare_configurations`, `enable_toolset`, and
`revalidate_objects`; all six #641 writers are absent. Read-tool controls distinguish the
two presets and reject an empty or narrowed catalogue. It never calls a writer and checks
fixture cleanliness before and after. Without the preset variable it reports a clear skip;
other presets skip this read-only policy check.

Run this module directly for preset launches: `run_all.py` always invokes `clean_project`
during model setup/cleanup, and the read-only presets disable that tool. A normal default
suite discovers these tests and skips them when `EDT_MCP_TOOL_PRESET` is unset. Presets cannot
be switched by the test; each wire check requires an EDT restart.

## Test Configuration

The `tests/TestConfiguration/` directory contains a minimal 1C:Enterprise configuration for testing:

- **Catalog.Catalog** — with ItemForm
- **CommonModule.OK** — empty, valid module
- **CommonModule.Error** — module with intentional error
- **CommonForm.Form** — common form
- **CommonAttribute.CommonAttribute** — common attribute
- **Subsystem.Subsystem** — subsystem
- **SessionParameter.SessionParameter** — session parameter

## GitHub Actions

### build.yml (automatic)
Runs unit tests on every push/PR to master. Test results are published to PR checks.

### e2e-2026.2.yml (automatic and manual)
[e2e-2026.2.yml](../.github/workflows/e2e-2026.2.yml) runs on pushes/PRs to master
and `workflow_dispatch`. It calls the reusable
[e2e-tests.yml](../.github/workflows/e2e-tests.yml): build the plugin, install and
boot EDT 2026.2 on stock GitHub-hosted runners, import the fixture projects, then
run the suite in isolated shards. Live-infobase tests are skipped in cloud CI;
they require the licensed 1C platform and remain part of the local test loop.

### conformance-2026.2.yml
The separate [conformance gate](conformance/README.md) checks the MCP wire protocol
against the official suite, using the committed expected-failure baseline.

## Standalone proxy

The proxy is independent of the Eclipse/Tycho plugin build. With JDK 17 or newer
and Maven on PATH, run:

```bash
mvn -f proxy/pom.xml clean verify
```

This runs unit tests and real-HTTP integration tests against in-process fake
backends; no running EDT or 1C platform is needed. CI runs it on JDK 17 through
[proxy.yml](../.github/workflows/proxy.yml). See [proxy/README.md](../proxy/README.md)
for routing and configuration.

## Project Structure

```
EDT-MCP/
├── mcp/
│   ├── bundles/
│   │   └── com.ditrix.edt.mcp.server/        # Main plugin
│   ├── tests/
│   │   ├── pom.xml                             # Tests parent
│   │   └── com.ditrix.edt.mcp.server.tests/   # Unit test fragment
│   │       ├── META-INF/MANIFEST.MF
│   │       ├── pom.xml
│   │       └── src/                            # JUnit tests
│   └── pom.xml                                 # Root (includes tests module)
├── tests/
│   ├── TestConfiguration/                      # Test 1C configuration
│   └── e2e/
│       ├── harness.py                          # HTTP/SSE client + git-fixture + asserts
│       ├── run_all.py                          # Serial E2E runner (--filter, --junit-xml)
│       └── tools/test_<tool>.py                # One black-box test file per tool
├── proxy/                                     # Standalone proxy and HTTP integration tests
└── .github/workflows/
    ├── build.yml                               # CI with unit tests
    ├── e2e-2026.2.yml                          # Automatic/manual E2E entry point
    ├── e2e-tests.yml                           # Reusable headless E2E workflow
    ├── conformance-2026.2.yml                  # Protocol conformance entry point
    └── proxy.yml                              # Standalone proxy build and tests
```
