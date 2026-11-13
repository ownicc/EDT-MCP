/** MCP Server for EDT. Licensed under AGPL-3.0-or-later. */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IProject;
import org.junit.Test;

import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.IRuntimeInstallationManager;
import com._1c.g5.v8.dt.platform.services.model.RuntimeInstallation;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker.NotReadyReason;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker.ProjectState;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker.ProjectStateResult;

/** Observable prerequisite verdicts, isolation of optional probes, and bounded failed calls. */
public class ProjectReadinessTest
{
    private final Fixture environment = new Fixture();
    private final ProjectReadiness readiness = new ProjectReadiness(environment, 1000);

    @Test
    public void metadataAndDiagnosticsDoNotNeedOrQueryRuntime()
    {
        environment.failRuntime = true;
        for (String operation : List.of("metadata", "diagnostics"))
        {
            Map<String, Object> result = readiness.diagnose("Demo", operation);
            assertEquals("ready", result.get("state"));
            assertEquals(true, result.get("ready"));
            assertEquals(false, result.get("runtimeRequired"));
            assertEquals("Demo", result.get("projectName"));
        }
    }

    @Test
    public void classifiesLifecycleReasonsWithoutInventingAvailability()
    {
        Object[][] cases = {
            { ProjectState.NOT_AVAILABLE, NotReadyReason.MISSING, "missing", "project_missing" },
            { ProjectState.NOT_AVAILABLE, NotReadyReason.CLOSED, "unavailable", "project_closed" },
            { ProjectState.NOT_AVAILABLE, NotReadyReason.NOT_EDT_PROJECT, "unsupported", "not_edt_project" },
            { ProjectState.NOT_AVAILABLE, NotReadyReason.CONTEXT_NOT_STARTED, "unavailable", "context_not_started" },
            { ProjectState.UNKNOWN, NotReadyReason.MANAGER_UNAVAILABLE, "unavailable", "manager_unavailable" },
            { ProjectState.UNKNOWN, NotReadyReason.BUILD_STATE_UNKNOWN, "unavailable", "build_state_unknown" },
            { ProjectState.BUILDING, NotReadyReason.BUILDING, "building", "building" },
            { ProjectState.BUILDING, NotReadyReason.CONTEXT_STARTING, "building", "context_starting" }
        };
        for (Object[] item : cases)
        {
            environment.state = new ProjectStateResult((ProjectState) item[0], (NotReadyReason) item[1],
                "platform message containing password=do-not-serialize");
            Map<String, Object> result = readiness.diagnose("Demo", "metadata");
            assertEquals(item[2], result.get("state"));
            assertEquals(item[3], result.get("reason"));
            assertEquals(false, result.get("ready"));
            assertFalse(((List<?>) result.get("actions")).isEmpty());
            assertFalse(result.toString().contains("do-not-serialize"));
        }
    }

    @Test
    public void readyDerivedDataWithoutRegisteredModelIsUnavailable()
    {
        when(environment.models.getModel(environment.project)).thenReturn(null);
        Map<String, Object> result = readiness.diagnose("Demo", "metadata");
        assertEquals("unavailable", result.get("state"));
        assertEquals("bm_model_unavailable", result.get("reason"));
        assertTrue(result.get("actions").toString().contains("retry"));
        environment.models = null;
        assertEquals("bm_model_manager_unavailable", readiness.diagnose("Demo", "metadata").get("reason"));
    }

    @Test
    public void diagnosticsServicesDoNotBlockMetadata()
    {
        environment.missingDiagnosticService = "IMarkerManager";
        assertEquals("ready", readiness.diagnose("Demo", "metadata").get("state"));
        Map<String, Object> result = readiness.diagnose("Demo", "diagnostics");
        assertEquals("unavailable", result.get("state"));
        assertTrue(result.get("message").toString().contains("IMarkerManager"));
        environment.missingDiagnosticService = "ICheckRepository";
        assertTrue(readiness.diagnose("Demo", "diagnostics").get("message").toString().contains("ICheckRepository"));
    }

    @Test
    public void runtimeDistinguishesMissingUnsupportedAndUnavailable()
    {
        assertEquals("missing", readiness.diagnose("Demo", "runtime").get("state"));
        when(environment.runtimes.getAllRegistered()).thenReturn(List.of(mock(RuntimeInstallation.class)));
        assertEquals("unsupported", readiness.diagnose("Demo", "runtime").get("state"));
        when(environment.runtimes.getAllSupported()).thenReturn(List.of(mock(RuntimeInstallation.class)));
        Map<String, Object> available = readiness.diagnose("Demo", "runtime");
        assertEquals("ready", available.get("state"));
        assertEquals(true, available.get("runtimeRequired"));
        environment.runtimes = null;
        assertEquals("unavailable", readiness.diagnose("Demo", "runtime").get("state"));
    }

    @Test
    public void platformExceptionsAreRedacted()
    {
        environment.failRuntime = true;
        Map<String, Object> result = readiness.diagnose("Demo", "runtime");
        assertEquals("unavailable", result.get("state"));
        assertEquals("probe_failed", result.get("reason"));
        assertFalse(result.toString().contains("password"));
        assertFalse(result.toString().contains("private-path"));
    }

    @Test
    public void timeoutKeepsUninterruptibleWorkBoundedAndRejectsAnotherProbe() throws Exception
    {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        environment.block = () -> {
            started.countDown();
            boolean done = false;
            while (!done)
            {
                try { release.await(); done = true; }
                catch (InterruptedException e) { /* Simulate an EDT API ignoring cancellation. */ }
            }
        };
        try
        {
            ProjectReadiness bounded = new ProjectReadiness(environment, 100);
            long before = System.nanoTime();
            assertEquals("probe_timeout", bounded.diagnose("Demo", "metadata").get("reason"));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 2000);
            assertTrue(started.await(1, TimeUnit.SECONDS));
            assertEquals("probe_busy", bounded.diagnose("Other", "metadata").get("reason"));
        }
        finally { release.countDown(); }
    }

    @Test
    public void interruptionReturnsUnavailableAndPreservesInterruptFlag()
    {
        environment.block = () -> {
            try { Thread.sleep(1000); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        };
        Thread.currentThread().interrupt();
        try
        {
            assertEquals("probe_interrupted", readiness.diagnose("Demo", "metadata").get("reason"));
            assertTrue(Thread.currentThread().isInterrupted());
        }
        finally { Thread.interrupted(); }
    }

    private static final class Fixture implements ProjectReadiness.Environment
    {
        final IProject project = mock(IProject.class);
        IBmModelManager models = mock(IBmModelManager.class);
        IRuntimeInstallationManager runtimes = mock(IRuntimeInstallationManager.class);
        ProjectStateResult state = new ProjectStateResult(ProjectState.READY, "Project is ready");
        String missingDiagnosticService;
        boolean failRuntime;
        Runnable block = () -> { };

        Fixture()
        {
            when(project.getName()).thenReturn("Demo");
            when(models.getModel(project)).thenReturn(mock(IBmModel.class));
            when(runtimes.isSupportedByOs()).thenReturn(true);
            when(runtimes.getAllRegistered()).thenReturn(List.of());
            when(runtimes.getAllSupported()).thenReturn(List.of());
        }

        @Override public IProject project(String name) { return project; }
        @Override public ProjectStateResult state(IProject target) { block.run(); return state; }
        @Override public IBmModelManager models() { return models; }
        @Override public String missingDiagnosticService() { return missingDiagnosticService; }
        @Override public IRuntimeInstallationManager runtimes()
        {
            if (failRuntime) throw new IllegalStateException("password=secret /private-path");
            return runtimes;
        }
    }
}
