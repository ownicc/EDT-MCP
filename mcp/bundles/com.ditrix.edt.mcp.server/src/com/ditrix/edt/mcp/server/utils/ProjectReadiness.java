/** MCP Server for EDT. Licensed under AGPL-3.0-or-later. */
package com.ditrix.edt.mcp.server.utils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.IRuntimeInstallationManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.RuntimeInstallations;
import com._1c.g5.wiring.ServiceAccess;
import com._1c.g5.wiring.ServiceProperties;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker.ProjectState;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker.ProjectStateResult;

/** Read-only project prerequisite snapshots. Never resolves a runtime or connects to an infobase. */
public final class ProjectReadiness
{
    private static final ProjectReadiness SHARED = new ProjectReadiness(new EdtEnvironment(), 2000);

    private final Environment environment;
    private final long timeoutMs;
    private final AtomicBoolean inFlight = new AtomicBoolean();

    ProjectReadiness(Environment environment, long timeoutMs)
    {
        this.environment = environment;
        this.timeoutMs = timeoutMs;
    }

    /** The same bounded probe lane is shared by HTTP, IPC, and tool instances. */
    public static Map<String, Object> check(String projectName, String operation)
    {
        return SHARED.diagnose(projectName, operation);
    }

    Map<String, Object> diagnose(String projectName, String operation)
    {
        if (!inFlight.compareAndSet(false, true))
        {
            return result(projectName, operation, "unavailable", "probe_busy",
                "Another project-readiness probe is still running.",
                "Wait and retry; if the earlier probe remains blocked, inspect the EDT log.");
        }
        CompletableFuture<Map<String, Object>> completion = new CompletableFuture<>();
        // No queue and at most one admitted platform call. A timeout interrupts the worker but
        // keeps the claim until it actually exits: EDT APIs may ignore interruption.
        Thread worker = new Thread(() -> {
            Map<String, Object> snapshot;
            try
            {
                snapshot = probe(projectName, operation);
            }
            catch (RuntimeException | LinkageError e)
            {
                // Platform exceptions can contain credentials or local paths. Return fixed text.
                snapshot = result(projectName, operation, "unavailable", "probe_failed",
                    "EDT could not provide a project-readiness snapshot.",
                    "Inspect the EDT log and retry after EDT finishes starting.");
            }
            finally
            {
                inFlight.set(false);
            }
            completion.complete(snapshot);
        }, "edt-mcp-project-readiness");
        worker.setDaemon(true);
        worker.start();
        try
        {
            return completion.get(timeoutMs, TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException e)
        {
            worker.interrupt();
            Thread.currentThread().interrupt();
            return result(projectName, operation, "unavailable", "probe_interrupted",
                "The project-readiness check was interrupted.", "Retry when EDT is responsive.");
        }
        catch (TimeoutException e)
        {
            worker.interrupt();
            return result(projectName, operation, "unavailable", "probe_timeout",
                "EDT did not provide project readiness within the time limit.",
                "Wait and retry; inspect the EDT log if the probe stays blocked.");
        }
        catch (ExecutionException e)
        {
            return result(projectName, operation, "unavailable", "probe_failed",
                "EDT could not provide a project-readiness snapshot.", "Inspect the EDT log and retry.");
        }
    }

    private Map<String, Object> probe(String projectName, String operation)
    {
        IProject project = environment.project(projectName);
        ProjectStateResult state = environment.state(project);
        if (!state.isReady())
        {
            String reason = state.getReason().name().toLowerCase(Locale.ROOT);
            return switch (state.getReason()) {
                case MISSING -> result(projectName, operation, "missing", "project_missing",
                    "The workspace project does not exist.",
                    "Use list_projects to check the name, or import the project into this workspace.");
                case CLOSED -> result(projectName, operation, "unavailable", "project_closed",
                    "The workspace project is closed.", "Open the project in EDT and retry.");
                case NOT_EDT_PROJECT -> result(projectName, operation, "unsupported", "not_edt_project",
                    "The project is not an EDT project.", "Select an EDT project using list_projects.");
                default -> result(projectName, operation,
                    state.getState() == ProjectState.BUILDING ? "building" : "unavailable", reason,
                    lifecycleMessage(reason),
                    "Wait for EDT startup and build completion, then retry. If the context stays "
                        + "unavailable, inspect the EDT log and close and reopen the project.");
            };
        }
        IBmModelManager models = environment.models();
        if (models == null)
        {
            return result(projectName, operation, "unavailable", "bm_model_manager_unavailable",
                "IBmModelManager is not available.", "Wait for EDT services to start and retry.");
        }
        if (!BmModelResolver.resolve(project, models).isAvailable())
        {
            return result(projectName, operation, "unavailable", "bm_model_unavailable",
                "EDT has not registered the project's BM model.",
                "Wait a few seconds and retry; list_projects can report ready before model registration.");
        }
        if ("diagnostics".equals(operation))
        {
            String missing = environment.missingDiagnosticService();
            if (missing != null)
            {
                return result(projectName, operation, "unavailable", "diagnostic_service_unavailable",
                    missing + " is not available.", "Wait for EDT check services to start and retry.");
            }
        }
        if ("runtime".equals(operation))
        {
            IRuntimeInstallationManager runtimes = environment.runtimes();
            if (runtimes == null)
            {
                return result(projectName, operation, "unavailable", "runtime_service_unavailable",
                    "IRuntimeInstallationManager is not available.",
                    "Wait for EDT platform services to start and retry.");
            }
            if (!runtimes.isSupportedByOs())
            {
                return result(projectName, operation, "unsupported", "runtime_os_unsupported",
                    "EDT does not support local platform installations on this operating system.",
                    "Use an environment with supported local 1C:Enterprise installations.");
            }
            if (runtimes.getAllRegistered().isEmpty())
            {
                return result(projectName, operation, "missing", "runtime_missing",
                    "No local 1C:Enterprise platform installation is registered in EDT.",
                    "Register a platform in EDT Preferences > 1C:Enterprise > Installed Installations.");
            }
            if (runtimes.getAllSupported().isEmpty())
            {
                return result(projectName, operation, "unsupported", "runtime_unsupported",
                    "None of the registered local platform installations is supported by EDT.",
                    "Check registered installations in EDT Preferences and register a supported platform.");
            }
        }
        return result(projectName, operation, "ready", "prerequisites_available",
            "The checked project prerequisites are available; this does not guarantee a later operation succeeds.");
    }

    private static String lifecycleMessage(String reason)
    {
        return switch (reason) {
            case "manager_unavailable" -> "IDtProjectManager is not available.";
            case "context_not_started" -> "The project has an EDT nature but its context has not started.";
            case "context_restarting" -> "The EDT project context is restarting.";
            case "context_starting" -> "EDT is starting the project context.";
            case "building" -> "EDT is still computing project derived data.";
            case "nature_unreadable" -> "EDT could not read the project nature.";
            default -> "EDT could not determine the project build state.";
        };
    }

    private static Map<String, Object> result(String projectName, String operation, String state,
        String reason, String message, String... actions)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projectName", projectName);
        result.put("operation", operation);
        result.put("state", state);
        result.put("ready", "ready".equals(state));
        result.put("reason", reason);
        result.put("message", message);
        result.put("actions", List.of(actions));
        result.put("runtimeRequired", "runtime".equals(operation));
        return result;
    }

    /** Platform boundary: implementations only observe current state. */
    interface Environment
    {
        IProject project(String name);
        ProjectStateResult state(IProject project);
        IBmModelManager models();
        String missingDiagnosticService();
        IRuntimeInstallationManager runtimes();
    }

    private static final class EdtEnvironment implements Environment
    {
        @Override public IProject project(String name) { return ProjectContext.of(name).project(); }
        @Override public ProjectStateResult state(IProject project)
        { return ProjectStateChecker.checkProjectState(project); }
        @Override public IBmModelManager models()
        {
            Activator activator = Activator.getDefault();
            return activator == null ? null : activator.getBmModelManager();
        }
        @Override public String missingDiagnosticService()
        {
            Activator activator = Activator.getDefault();
            if (activator == null || activator.getMarkerManager() == null) return "IMarkerManager";
            return activator.getCheckRepository() == null ? "ICheckRepository" : null;
        }
        @Override public IRuntimeInstallationManager runtimes()
        {
            // Read registered local installations. Do not resolve an execution environment: its
            // resolver can search, download or install a platform as a side effect.
            return ServiceAccess.get(IRuntimeInstallationManager.class,
                ServiceProperties.named(RuntimeInstallations.ENTERPRISE_PLATFORM));
        }
    }
}
