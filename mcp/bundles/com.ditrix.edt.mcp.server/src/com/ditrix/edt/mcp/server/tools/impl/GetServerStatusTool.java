/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import com.ditrix.edt.mcp.server.HostKind;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.eclipse.jface.preference.IPreferenceStore;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.McpServer;
import com.ditrix.edt.mcp.server.preferences.PreferenceConstants;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpConstants;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.tools.McpToolRegistry;
import com.ditrix.edt.mcp.server.transport.HttpTransport;
import com.ditrix.edt.mcp.server.transport.StdioPluginSupport;
import com.ditrix.edt.mcp.server.transport.TransportStatus;
import com.ditrix.edt.mcp.server.utils.CheckDescriptionLoader;
import com.ditrix.edt.mcp.server.utils.NativeRenderModeProbe;
import com.ditrix.edt.mcp.server.utils.NativeRenderModeProbe.NativeRenderMode;
import com.ditrix.edt.mcp.server.utils.ProjectReadiness;

/**
 * Self-diagnosis tool: returns the running MCP server's introspection snapshot
 * so a client can answer "why is the screenshot blank / why is JSON plain /
 * which tools are exposed" without guessing.
 * <p>
 * Reports: listening port, MCP protocol version, plugin and EDT version, the
 * enabled/total tool counts, the {@code plainTextMode} and {@code checksFolder}
 * preference flags, the startup modes and requested properties of the two form-render modes
 * ({@code -DnativeFormBufferedLayoutRender} / {@code -DnativeFormLayoutRender}),
 * and whether authentication is enabled.
 * <p>
 * SECURITY: the auth token value is never emitted — only the {@code authEnabled}
 * boolean derived from whether {@link PreferenceConstants#PREF_AUTH_TOKEN} is
 * non-empty. The {@code checksFolder} path is likewise reduced to a boolean
 * ({@code checksFolderConfigured}), never the path itself.
 * <p>
 * Read-only: the {@code get_} name prefix lets the central
 * {@code ToolAnnotationClassifier} mark this tool read-only and idempotent.
 * {@code execute()} is null-safe for a headless context (a missing
 * {@link Activator} or {@link McpServer} degrades to {@code unknown}/{@code false}
 * rather than throwing).
 */
public class GetServerStatusTool implements IMcpTool
{
    private final Supplier<McpServer> httpServer;
    private final BooleanSupplier ipcRunning;

    public GetServerStatusTool()
    {
        this(() -> Activator.getDefault() == null ? null : Activator.getDefault().getMcpServer(), StdioPluginSupport::isIpcRunning);
    }

    GetServerStatusTool(Supplier<McpServer> httpServer, BooleanSupplier ipcRunning)
    { this.httpServer = httpServer; this.ipcRunning = ipcRunning; }
    public static final String NAME = "get_server_status"; //$NON-NLS-1$

    /** Form-render JVM flag: enables the offscreen buffered layout render. */
    private static final String FLAG_BUFFERED_LAYOUT_RENDER = "nativeFormBufferedLayoutRender"; //$NON-NLS-1$

    /** Form-render JVM flag: selects the native (C++) layout render path. */
    private static final String FLAG_NATIVE_LAYOUT_RENDER = "nativeFormLayoutRender"; //$NON-NLS-1$

    /** Output key: whether the MCP server is currently running. */
    private static final String KEY_RUNNING = "running"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Diagnose the EDT MCP server, feature configuration, and optional project prerequisites. Parameters and examples: " //$NON-NLS-1$
            + "get_tool_guide('get_server_status')."; //$NON-NLS-1$
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", "Optional explicit EDT project to probe; omitted = server snapshot only.")
            .enumProperty("operation", "Project prerequisite profile; requires projectName. Default: metadata.",
                "metadata", "diagnostics", "runtime")
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the operation succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("port", "HTTP configured or last bound TCP port; active only when transports.http is true") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty(KEY_RUNNING, "Whether at least one MCP transport is listening") //$NON-NLS-1$
            .objectProperty("transports", "Active MCP listeners; IPC exposes no secret or socket address",
                JsonSchemaBuilder.object()
                    .booleanProperty("http", "Whether the HTTP listener is running", true)
                    .booleanProperty("ipc", "Whether the local IPC listener is running", true), false)
            .stringProperty("hostKind", "EDT host kind: WINDOWED or HEADLESS")
            .stringProperty("protocolVersion", "MCP protocol version implemented by the server") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("pluginVersion", "Version of the EDT-MCP plugin") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("edtVersion", "Detected 1C:EDT version") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("enabledTools", "Number of tools currently enabled") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("totalTools", "Total number of registered tools") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("plainTextMode", "Whether JSON responses are forced to plain text") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("checksFolderConfigured", "Whether a checks folder path is configured") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("authEnabled", "Whether bearer-token authentication is enabled") //$NON-NLS-1$ //$NON-NLS-2$
            .objectProperty("formRenderFlags", //$NON-NLS-1$
                "atStartup is the mode when this plugin activated; requested is the current " //$NON-NLS-1$
                    + "system property. EDT binds these modes at class initialization; changing them " //$NON-NLS-1$
                      + "requires an EDT restart") //$NON-NLS-1$
              .objectProperty("readiness", "Optional bounded project prerequisite snapshot, not a full operation preflight",
                  JsonSchemaBuilder.object()
                      .stringProperty("projectName", "Requested workspace project", true)
                      .enumProperty("operation", "Checked prerequisite profile", true, "metadata", "diagnostics", "runtime")
                      .enumProperty("state", "Observed readiness", true,
                          "ready", "building", "missing", "unsupported", "unavailable")
                      .booleanProperty("ready", "Whether the checked prerequisites are available", true)
                      .stringProperty("reason", "Machine-readable reason for this verdict", true)
                      .stringProperty("message", "Explanation without secrets or local installation paths", true)
                      .stringArrayProperty("actions", "Suggested recovery steps; empty when ready", true)
                      .booleanProperty("runtimeRequired", "Whether this profile requires a registered local platform", true), false)
            .build();
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName");
        String operation = JsonUtils.extractStringArgument(params, "operation");
        boolean probeRequested = params != null
            && (params.containsKey("projectName") || params.containsKey("operation"));
        if (probeRequested)
        {
            if (projectName == null || projectName.isBlank())
            {
                return ToolResult.error("Provide a non-blank projectName to request project readiness; "
                    + "omit projectName and operation for the server snapshot only.").toJson();
            }
            if (operation == null && !params.containsKey("operation")) operation = "metadata";
            if (!java.util.Set.of("metadata", "diagnostics", "runtime").contains(operation == null ? "" : operation))
            {
                return ToolResult.error("operation must be metadata, diagnostics, or runtime.").toJson();
            }
        }
        try
        {
            Activator activator = Activator.getDefault();

            // Tool counts come from the singleton registry; getEnabledTools()
            // applies the per-tool enablement preference, getToolCount() is the total.
            McpToolRegistry registry = McpToolRegistry.getInstance();
            int totalTools = registry.getToolCount();
            int enabledTools = registry.getEnabledTools().size();

            ToolResult result = ToolResult.success();

            // Port: read from the live server when available; null-safe for headless.
            McpServer server = httpServer.get();
            TransportStatus transports = TransportStatus.read(server, ipcRunning);
            boolean http = transports.http();
            boolean ipc = transports.ipc();
            result.put("port", server == null ? PreferenceConstants.DEFAULT_PORT : server.getPort()); //$NON-NLS-1$
            result.put(KEY_RUNNING, transports.running());
            result.put("transports", Map.of("http", http, "ipc", ipc));

            result.put("hostKind", HostKind.current().name());
            result.put("protocolVersion", McpConstants.PROTOCOL_VERSION); //$NON-NLS-1$
            result.put("pluginVersion", McpConstants.PLUGIN_VERSION); //$NON-NLS-1$
            result.put("edtVersion", GetEdtVersionTool.getEdtVersion()); //$NON-NLS-1$

            result.put("enabledTools", enabledTools); //$NON-NLS-1$
            result.put("totalTools", totalTools); //$NON-NLS-1$

            // Preference-backed flags. Degrade to defaults/false when the
            // preference store is unavailable (headless / no Activator).
            boolean plainTextMode = PreferenceConstants.DEFAULT_PLAIN_TEXT_MODE;
            boolean checksFolderConfigured = false;
            boolean authEnabled = false;
            if (activator != null)
            {
                IPreferenceStore store = activator.getPreferenceStore();
                if (store != null)
                {
                    plainTextMode = store.getBoolean(PreferenceConstants.PREF_PLAIN_TEXT_MODE);

                    // Only whether a checks folder is configured, never the path. Since #31 the
                    // descriptions SHIP with the plugin, so this reports an OVERRIDE being in
                    // play - not whether get_check_description works. Read through the loader so
                    // "configured" means here exactly what it means where it is acted on.
                    checksFolderConfigured = CheckDescriptionLoader.hasOverrideFolder();

                    // Only whether auth is on, never the token value - and "on" means what the
                    // authorizer means by it, so a preference of blanks reports auth OFF here
                    // instead of promising a check that HttpTransport does not perform.
                    String authToken = store.getString(PreferenceConstants.PREF_AUTH_TOKEN);
                    authEnabled = !HttpTransport.normalizeToken(authToken).isEmpty();
                }
            }
            result.put("plainTextMode", plainTextMode); //$NON-NLS-1$
            result.put("checksFolderConfigured", checksFolderConfigured); //$NON-NLS-1$
            result.put("authEnabled", authEnabled); //$NON-NLS-1$

            // EDT-startup render modes and raw requested System properties:
            // the diagnostic for a blank get_form_screenshot / get_form_layout_snapshot.
            Map<String, Object> formRenderFlags = new LinkedHashMap<>();
            formRenderFlags.put(FLAG_NATIVE_LAYOUT_RENDER,
                createRenderFlagState(NativeRenderModeProbe.getStartupNativeRenderMode(),
                    System.getProperty(FLAG_NATIVE_LAYOUT_RENDER)));
            formRenderFlags.put(FLAG_BUFFERED_LAYOUT_RENDER,
                createRenderFlagState(NativeRenderModeProbe.getStartupBufferedRenderMode(),
                    System.getProperty(FLAG_BUFFERED_LAYOUT_RENDER)));
            result.put("formRenderFlags", formRenderFlags); //$NON-NLS-1$

            if (probeRequested)
            {
                result.put("readiness", ProjectReadiness.check(projectName, operation));
            }

            return result.toJson();
        }
        catch (Exception e)
        {
            Activator.logError("Error in get_server_status", e); //$NON-NLS-1$
            return ToolResult.error(e.getMessage()).toJson();
        }
    }

    /**
     * Builds one render-flag state from the mode at MCP activation and the raw requested
     * system property. EDT reads the flags into static finals at class initialization and
     * binds its offscreen handler once in {@code HippoLayoutService.INSTANCE}; changing
     * a property later cannot reconfigure that handler. An EDT restart is required.
     */
    private static Map<String, Object> createRenderFlagState(NativeRenderMode startupMode,
        String requested)
    {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("atStartup", startupMode.name().toLowerCase(Locale.ROOT)); //$NON-NLS-1$
        if (requested != null)
        {
            state.put("requested", requested); //$NON-NLS-1$
        }
        return state;
    }
}
