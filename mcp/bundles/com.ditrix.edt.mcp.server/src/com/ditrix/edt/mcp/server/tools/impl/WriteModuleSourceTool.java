/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Comparator;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.BooleanSupplier;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ProjectScope;
import org.eclipse.core.runtime.ICoreRunnable;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.IScopeContext;
import org.eclipse.core.runtime.preferences.InstanceScope;

import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.GsonProvider;
import com.ditrix.edt.mcp.server.preferences.ConsentSettingsService;
import com.ditrix.edt.mcp.server.ui.BslWriteReviewUi;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.HostKind;
import com.ditrix.edt.mcp.server.utils.compare.ComparisonTextDiff;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.AdoptedModuleState;
import com.ditrix.edt.mcp.server.utils.BslEmptyRegion;
import com.ditrix.edt.mcp.server.utils.BslModuleUtils;
import com.ditrix.edt.mcp.server.utils.PreparedBslWrite;
import com.ditrix.edt.mcp.server.utils.BslWriteReviews.Outcome;
import com.ditrix.edt.mcp.server.utils.BslWriteReviews.State;
import com.ditrix.edt.mcp.server.utils.BslWriteReviewCommit;
import com.ditrix.edt.mcp.server.utils.BslWriteReviews;
import com.ditrix.edt.mcp.server.utils.BslWriteRequests;
import com.ditrix.edt.mcp.server.utils.BslSyntaxChecker;
import com.ditrix.edt.mcp.server.utils.ContentHash;
import com.ditrix.edt.mcp.server.utils.FrontMatter;
import com.ditrix.edt.mcp.server.utils.InvalidFileCharacters;
import com.ditrix.edt.mcp.server.utils.MetadataScope;
import com.ditrix.edt.mcp.server.utils.ModuleTargetResolver;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
import com.ditrix.edt.mcp.server.utils.Refusals;
import com.ditrix.edt.mcp.server.utils.VendorSupportGuard;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tool to write BSL source code to 1C metadata object modules.
 * Supports content, whole-file, append, and method-targeted write modes.
 * Optionally validates BSL syntax (balanced block keywords) before writing.
 * Can resolve module path from objectName + moduleType.
 */
public class WriteModuleSourceTool implements IMcpTool
{
    private final BslWriteRequests requestStore;
    public WriteModuleSourceTool() { this(null); }
    WriteModuleSourceTool(BslWriteRequests requestStore) { this.requestStore = requestStore; }

    public static final String NAME = "write_module_source"; //$NON-NLS-1$

    private static final String MODE_REPLACE = "replace"; //$NON-NLS-1$
    private static final String MODE_APPEND = "append"; //$NON-NLS-1$
    private static final String MODE_SEARCH_REPLACE = "searchReplace"; //$NON-NLS-1$
    private static final String MODE_REPLACE_METHODS = "replaceMethods";
    private static final String MODE_REPLACE_METHOD = "replaceMethod"; //$NON-NLS-1$
    private static final String MODE_INSERT_BEFORE = "insertBefore"; //$NON-NLS-1$
    private static final String MODE_INSERT_AFTER = "insertAfter"; //$NON-NLS-1$
    private static final String MODE_INSERT_INTO_REGION = "insertIntoRegion";

    private static final String PROJECT_NAME = "projectName"; //$NON-NLS-1$
    private static final String MODULE_PATH = "modulePath"; //$NON-NLS-1$

    private static final String MODULE_TYPE_MODULE = "Module"; //$NON-NLS-1$
    private static final String MODULE_TYPE_OBJECT_MODULE = "ObjectModule"; //$NON-NLS-1$
    private static final String MODULE_TYPE_COMMAND_MODULE = "CommandModule"; //$NON-NLS-1$

    /** Maximum source length to prevent accidental huge writes */
    private static final int MAX_SOURCE_LENGTH = 500_000;
    private static final int MAX_BATCH_EDITS = 50;

    /** UTF-8 BOM bytes */
    private static final byte[] UTF8_BOM = { (byte)0xEF, (byte)0xBB, (byte)0xBF };

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Create or edit BSL source in a metadata module. When reviewId is returned, poll get_write_review_status; "
            + "only human approval in EDT applies the change. Parameters and examples: " //$NON-NLS-1$
            + "get_tool_guide('write_module_source')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        String schema = JsonSchemaBuilder.object()
            .stringProperty(PROJECT_NAME,
                "EDT project name (required)", true) //$NON-NLS-1$
            .stringProperty(MODULE_PATH,
                "src/-relative module path, e.g. 'CommonModules/MyModule/Module.bsl'. " + //$NON-NLS-1$
                "Alternative to objectName (pass exactly one).") //$NON-NLS-1$
            .stringProperty("objectName", //$NON-NLS-1$
                "Object name 'Type.Name', e.g. 'Document.MyDoc'. " + //$NON-NLS-1$
                "Resolved with moduleType. Alternative to modulePath.") //$NON-NLS-1$
            .enumProperty("moduleType", //$NON-NLS-1$
                "Module type for objectName resolution (default ObjectModule).", //$NON-NLS-1$
                MODULE_TYPE_OBJECT_MODULE, "ManagerModule", "FormModule", MODULE_TYPE_COMMAND_MODULE, "RecordSetModule", MODULE_TYPE_MODULE) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .stringProperty("source", //$NON-NLS-1$
                "BSL source to write (required unless edits is supplied): full file for replace, new fragment for " + //$NON-NLS-1$
                "searchReplace, text to add for append, or exactly one complete method for " + //$NON-NLS-1$
                "a method-targeted mode or insertIntoRegion. Mutually exclusive with edits.") //$NON-NLS-1$
            .stringProperty("oldSource", //$NON-NLS-1$
                "Fragment to find and replace; required for searchReplace, must match exactly once.") //$NON-NLS-1$
            .enumProperty("mode", //$NON-NLS-1$
                "Write mode (default searchReplace, or replaceMethods when edits is supplied).", //$NON-NLS-1$
                MODE_SEARCH_REPLACE, MODE_REPLACE, MODE_APPEND,
                MODE_REPLACE_METHOD, MODE_INSERT_BEFORE, MODE_INSERT_AFTER, MODE_REPLACE_METHODS,
                MODE_INSERT_INTO_REGION)
            .objectArrayProperty("edits", "Replace 1 to 50 distinct methods in one module/review. Each entry: methodName and source (one complete method). Requires expectedHash; implies mode=replaceMethods. Do not mix with top-level source, methodName, oldSource, expectedSource or overwrite. Combined source limit: 500000 characters.")
            .stringProperty("methodName", //$NON-NLS-1$
                "Existing anchor method; required for replaceMethod, insertBefore, and insertAfter.") //$NON-NLS-1$
            .stringProperty("regionName", "Required only for insertIntoRegion: an existing uniquely named empty region outside methods. Requires expectedHash; incompatible with methodName and edits.")
            .stringProperty("formName", //$NON-NLS-1$
                "Form name; required when moduleType=FormModule (e.g. 'ItemForm').") //$NON-NLS-1$
            .stringProperty("commandName", //$NON-NLS-1$
                "Command name; required when moduleType=CommandModule (e.g. 'FillByTemplate').") //$NON-NLS-1$
            .booleanProperty("skipSyntaxCheck", //$NON-NLS-1$
                "Skip the BSL syntax check (default false).") //$NON-NLS-1$
            .booleanProperty("preview", "Validate and return a bounded diff without writing or opening review (default false). Do not combine with requestId.")
            .booleanProperty("normalizeInvalidCharacters", //$NON-NLS-1$
                "Replace the characters the 1C standard InvalidCharacterInFile forbids in the " //$NON-NLS-1$
                + "SOURCE you supply - en/em/figure dash, horizontal bar and typographic minus " //$NON-NLS-1$
                + "become '-', a no-break space becomes a space, a soft hyphen is dropped " //$NON-NLS-1$
                + "(default true). They are invisible in a diff and read exactly like their ASCII " //$NON-NLS-1$
                + "twins, so they otherwise land in the module as a marker. Set false to write the " //$NON-NLS-1$
                + "source byte-for-byte.") //$NON-NLS-1$
            .stringProperty("expectedSource", //$NON-NLS-1$
                "Lost-update guard for mode=replace: the module content you last read; mismatch rejects.") //$NON-NLS-1$
            .booleanProperty("overwrite", //$NON-NLS-1$
                "Force mode=replace over an existing module without an expectedSource check (default false).") //$NON-NLS-1$
            .stringProperty("requestId", "Optional workspace-unique operation ID (1 to 128 characters). Replays the retained receipt; never reuse an ID for another write.")
            .stringProperty("expectedHash", //$NON-NLS-1$
                "Lost-update guard: the hash from the read that produced your edit. The write is " //$NON-NLS-1$
                + "rejected if the module changed since; required for insertIntoRegion, method-targeted modes and edits unless replaceMethod uses expectedMethodHash.") //$NON-NLS-1$
            .stringProperty("expectedMethodHash", "For replaceMethod only: methodHash from read_method_source, including documentation/directives. Allows unrelated module changes before preparation. If expectedHash is also supplied, both must match.")
            .build();
        JsonObject document = JsonParser.parseString(schema).getAsJsonObject();
        document.add("oneOf", JsonParser.parseString(GsonProvider.toJson(List.of(
            Map.of("required", List.of("source")), Map.of("required", List.of("edits"))))));
        JsonObject edits = document.getAsJsonObject("properties").getAsJsonObject("edits");
        JsonObject item = JsonParser.parseString(JsonSchemaBuilder.object()
            .stringProperty("methodName", "Existing method name; unique in the original module and in this batch.", true)
            .stringProperty("source", "One complete replacement method with the same name and kind.", true).build()).getAsJsonObject();
        item.addProperty("additionalProperties", false);
        edits.add("items", item);
        edits.addProperty("minItems", 1);
        edits.addProperty("maxItems", MAX_BATCH_EDITS);
        return GsonProvider.toJson(document);
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.MARKDOWN;
    }

    @Override
    public ResponseType getResponseType(Map<String, String> params, String result)
    {
        return result != null && result.stripLeading().startsWith("{") ? ResponseType.JSON : ResponseType.MARKDOWN;
    }

    @Override
    public String getResultFileName(Map<String, String> params)
    {
        String modulePath = JsonUtils.extractStringArgument(params, MODULE_PATH);
        if (modulePath != null && !modulePath.isEmpty())
        {
            String safeName = modulePath.replace("/", "-").replace("\\", "-").toLowerCase(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            return "write-" + safeName + ".md"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return "write-module-source.md"; //$NON-NLS-1$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        // 1. Extract and default the request parameters.
        WriteRequest req = WriteRequest.from(params);

        // 2. Validate required parameters
        String argError = validateWriteArguments(params, req);
        if (argError != null)
        {
            return argError;
        }
        if (req.preview && params.containsKey("requestId"))
            return ToolResult.error("preview cannot be combined with requestId; previews do not reserve or replay write receipts.")
                .put("code", "previewRequestIdUnsupported").put("mutationCommitted", false).toJson();

        // 3. Resolve the module target.
        ModuleTargetResult target = resolveModuleTarget(req.modulePath, req.objectName,
            req.moduleType, req.formName, req.commandName);
        if (target.error != null)
        {
            return target.error;
        }
        req.modulePath = target.modulePath;
        String requestId = JsonUtils.extractStringArgument(params, "requestId");
        if (requestId != null)
        {
            if (requestId.isBlank() || requestId.length() > 128)
                return ToolResult.error("requestId must contain 1 to 128 characters and must never be reused for another write.")
                    .put("code", "invalidRequestId").toJson();
            try
            {
                BslWriteRequests store = requestStore != null ? requestStore : BslWriteRequests.shared();
                req.requestId = requestId;
                req.requestStore = store;
                return store.execute(requestId, requestDigest(req), () -> executeWrite(req));
            }
            catch (java.io.IOException e)
            {
                return ToolResult.error("Cannot open workspace write receipts; no write was started. Check workspace storage: " + e.getMessage())
                    .put("requestId", requestId).put("mutationCommitted", false).toJson();
            }
        }
        return executeWrite(req);
    }

    private String executeWrite(WriteRequest req)
    {
        boolean reviewEnabled = ConsentSettingsService.getInstance().isBslWriteReviewEnabled();
        // 4. Validate project
        ProjectContext ctx = ProjectContext.of(req.projectName);
        if (!ctx.exists())
        {
            return ToolResult.error(ProjectContext.notFoundMessage(req.projectName)).toJson();
        }
        IProject project = ctx.project();

        AtomicBoolean mutationEntered = new AtomicBoolean();
        AtomicBoolean mutationCommitted = new AtomicBoolean();
        try
        {
            // 5. Get file
            IFile file = BslModuleUtils.resolveModuleFile(project, req.modulePath);
            String outside = outsideProjectError(project, file, req.modulePath);
            if (outside != null)
            {
                return outside;
            }
            req.modulePath = BslModuleUtils.sourceRelativePath(file);
            boolean fileExists = file.exists();

            // For non-replace modes, file must exist
            if (!fileExists && !MODE_REPLACE.equals(req.mode))
            {
                return ToolResult.error("File not found: src/" + req.modulePath + //$NON-NLS-1$
                    ". Only 'replace' mode can create new files.").toJson(); //$NON-NLS-1$
            }

            if (reviewEnabled || ((req.preview || req.expectedMethodHash != null
                || MODE_INSERT_INTO_REGION.equals(req.mode)) && HostKind.current() != HostKind.HEADLESS))
            {
                if (!project.isOpen()) return ToolResult.error(req.preview
                    ? "Open the project in EDT before previewing a BSL write."
                    : "Open the project in EDT before submitting a BSL review.").toJson();
                String refusal = BslWriteReviewCommit.preparationRefusal(file);
                if (refusal != null) return ToolResult.error(refusal).toJson();
            }

            // 6. The metadata root, resolved ONCE and here - where the vendor guard always resolved it, so
            // the order of the refusals is unchanged - for the guard and for the adopted-module step after
            // the write (#711). Then vendor support: the module and the object that owns it must be
            // editable (#642).
            ProjectContext.ConfigurationResult root = ctx.resolveMetadataRoot();
            if (!root.ok())
            {
                return root.errorJson();
            }
            String locked = vendorSupportRefusal(ctx, root, file);
            if (locked != null)
            {
                return locked;
            }

            if (req.preview) return previewResult(prepareWrite(req, file, 0L));
            if (!reviewEnabled && (req.expectedMethodHash != null || MODE_INSERT_INTO_REGION.equals(req.mode)))
                return writePreparedGuarded(req, file, mutationEntered, mutationCommitted);
            return reviewEnabled ? reviewWrite(req, file)
                : writeModule(req, file, fileExists, root.scope(), mutationEntered, mutationCommitted);
        }
        catch (Exception e)
        {
            if (!mutationEntered.get() && ToolResult.isErrorPayload(e.getMessage())) return e.getMessage();
            String refusal = Refusals.messageOf(e);
            String message = refusal != null && !mutationEntered.get() ? refusal
                : (mutationEntered.get() ? "Failed to write file: " : "Failed to prepare module write: ") //$NON-NLS-1$ //$NON-NLS-2$
                    + e.getMessage();
            ToolResult error = mutationCommitted.get()
                ? ToolResult.errorAfterMutation(message)
                : mutationEntered.get()
                    ? ToolResult.errorWithUnknownMutationOutcome(message)
                    : ToolResult.error(message);
            return error.toJson();
        }
    }

    /**
     * Refuses a module path that resolves to no file, or to a file of another project: an
     * absolute path resolves across the whole workspace, so without this check the write -
     * and its vendor-support guard - would judge the named project while changing another.
     *
     * @param project the named project
     * @param file the resolved module file, may be {@code null}
     * @param modulePath the requested path, for the message
     * @return the error JSON, or {@code null} when the file belongs to {@code project}
     */
    static String outsideProjectError(IProject project, IFile file, String modulePath)
    {
        if (file == null)
        {
            return ToolResult.error("File not found: " + modulePath //$NON-NLS-1$
                + ". Pass a src/-relative module path of project '" + project.getName() //$NON-NLS-1$
                + "', e.g. 'CommonModules/MyModule/Module.bsl'.").toJson(); //$NON-NLS-1$
        }
        IProject owner = file.getProject();
        if (owner == null || !owner.equals(project))
        {
            return ToolResult.error("modulePath '" + modulePath + "' is a file of project '" //$NON-NLS-1$ //$NON-NLS-2$
                + (owner == null ? "?" : owner.getName()) + "', not of '" + project.getName() //$NON-NLS-1$ //$NON-NLS-2$
                + "'. Nothing was changed. Pass projectName='" //$NON-NLS-1$
                + (owner == null ? "?" : owner.getName()) //$NON-NLS-1$
                + "' and a src/-relative modulePath.").toJson(); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * The vendor-support refusal for writing {@code file}, as ready JSON: the module (when it
     * exists) and the object that owns it must be editable. The caller has already refused a
     * project whose metadata root cannot be read (fail closed).
     *
     * @param ctx the resolved project
     * @param root the project's resolved metadata root
     * @param file the module file
     * @return the error JSON, or {@code null} when the write may proceed
     */
    private static String vendorSupportRefusal(ProjectContext ctx, ProjectContext.ConfigurationResult root,
        IFile file)
    {
        String refusal = VendorSupportGuard.refusalForModuleFile(ctx.project(), root.scope(), file);
        return refusal == null ? null : ToolResult.error(refusal).toJson();
    }

    /**
     * Parsed and defaulted {@code write_module_source} request inputs, bundled into one
     * holder so the write helpers take a single seam instead of a long parameter list
     * (avoids the &gt;7-parameter smell on {@link #computeNewLines}). {@code mode} is
     * defaulted to {@link #MODE_SEARCH_REPLACE} at construction exactly as the inline flow
     * did; {@code modulePath} is mutable because {@link #resolveModuleTarget} may resolve it
     * from {@code objectName}.
     */
    static final class WriteRequest
    {
        final String projectName;
        String modulePath;
        final String objectName;
        final String moduleType;
        String source;
        final String oldSource;
        final String mode;
        final String methodName;
        final String regionName;
        final String formName;
        final String commandName;
        final boolean skipSyntaxCheck;
        final boolean preview;
        final boolean normalizeInvalidCharacters;
        final String expectedSource;
        final boolean overwrite;
        final String expectedHash;
        final String expectedMethodHash;
        List<MethodReplacement> edits;
        final String editsError;
        String requestId;
        BslWriteRequests requestStore;

        private WriteRequest(Map<String, String> params)
        {
            this.projectName = JsonUtils.extractStringArgument(params, PROJECT_NAME);
            this.modulePath = JsonUtils.extractStringArgument(params, MODULE_PATH);
            this.objectName = JsonUtils.extractStringArgument(params, "objectName"); //$NON-NLS-1$
            this.moduleType = JsonUtils.extractStringArgument(params, "moduleType"); //$NON-NLS-1$
            // Kept RAW here (NOT \r\n-normalized) so validateWriteArguments measures the
            // exact same length the inline flow did; the \r\n->\n normalization happens in
            // writeModule, just like the original try block did, before any content compute.
            this.source = JsonUtils.extractStringArgument(params, "source"); //$NON-NLS-1$
            this.oldSource = JsonUtils.extractStringArgument(params, "oldSource"); //$NON-NLS-1$
            String rawMode = JsonUtils.extractStringArgument(params, "mode"); //$NON-NLS-1$
            this.mode = (rawMode == null || rawMode.isEmpty())
                ? (params.containsKey("edits") ? MODE_REPLACE_METHODS : MODE_SEARCH_REPLACE) : rawMode;
            BatchEdits batch = parseBatchEdits(params);
            this.edits = batch.edits();
            this.editsError = batch.error();
            this.methodName = JsonUtils.extractStringArgument(params, "methodName"); //$NON-NLS-1$
            this.regionName = JsonUtils.extractStringArgument(params, "regionName");
            this.formName = JsonUtils.extractStringArgument(params, "formName"); //$NON-NLS-1$
            this.commandName = JsonUtils.extractStringArgument(params, "commandName"); //$NON-NLS-1$
            this.skipSyntaxCheck = JsonUtils.extractBooleanArgument(params, "skipSyntaxCheck", false); //$NON-NLS-1$
            this.preview = JsonUtils.extractBooleanArgument(params, "preview", false);
            this.normalizeInvalidCharacters =
                JsonUtils.extractBooleanArgument(params, "normalizeInvalidCharacters", true); //$NON-NLS-1$
            this.expectedSource = JsonUtils.extractStringArgument(params, "expectedSource"); //$NON-NLS-1$
            this.overwrite = JsonUtils.extractBooleanArgument(params, "overwrite", false); //$NON-NLS-1$
            this.expectedHash = JsonUtils.extractStringArgument(params, "expectedHash"); //$NON-NLS-1$
            this.expectedMethodHash = JsonUtils.extractStringArgument(params, "expectedMethodHash");
        }

        static WriteRequest from(Map<String, String> params)
        {
            return new WriteRequest(params);
        }
    }

    /**
     * The body of {@link #execute} that runs once the project/file is resolved: reads the
     * current content, applies the optimistic-lock + syntax guards, then WRITES the module
     * and builds the success response. Kept under {@link #execute}'s {@code try} so any
     * {@link Exception} reaches the SAME {@code catch} as before; declares
     * {@code throws Exception} because {@link #computeNewLines}/{@link #writeFile} do.
     *
     * @param req the parsed request (its {@code modulePath} is already resolved)
     * @param file the resolved module file
     * @param fileExists whether {@code file} already exists on disk
     * @param scope the project's metadata root, for the adopted-module step after the write
     * @return the success response, or a ready {@link ToolResult#error} JSON payload from a
     *         guard (returned verbatim) — the same value in the same case as the inline flow; once
     *         the text is written, a failed adopted-module step answers instead with a
     *         {@link ToolResult#errorAfterMutation} payload ({@code mutationCommitted:true}), see
     *         {@link #finishWrite}
     */
    private String writeModule(WriteRequest req, IFile file, boolean fileExists, MetadataScope scope,
        AtomicBoolean mutationEntered, AtomicBoolean mutationCommitted) throws Exception
    {
        String normalizedCharacters = normalizeIncomingSource(req);

        // Read current content (if file exists)
        List<String> originalLines;
        boolean hasBom;
        String lineDelimiter;
        if (fileExists)
        {
            originalLines = BslModuleUtils.readFileLines(file);
            hasBom = detectBom(file);
            // Preserve the module's OWN line separator (issue #305): EDT writes BSL modules with CRLF,
            // and the raw \n write flipped every line to LF, marking the whole module changed. Detect
            // from the raw text (readFileText keeps the original separators; readFileLines strips them).
            lineDelimiter = resolveLineDelimiter(file.getProject(), BslModuleUtils.readFileText(file));
        }
        else
        {
            originalLines = new ArrayList<>();
            hasBom = true; // New BSL files should have BOM
            lineDelimiter = resolveLineDelimiter(file.getProject(), null);
        }

        String hashError = checkExpectedHashGuard(req, file, fileExists);
        if (hashError != null)
        {
            return hashError;
        }

        // Compute new content based on mode
        int totalOriginal = originalLines.size();
        NewLinesResult computed = computeNewLines(req, file, fileExists, originalLines);
        if (computed.error != null)
        {
            return computed.error;
        }
        List<String> newLines = computed.newLines;

        // BSL syntax check
        if (!req.skipSyntaxCheck)
        {
            String syntaxError = runSyntaxCheck(newLines);
            if (syntaxError != null)
            {
                return syntaxError;
            }
        }

        // Method-targeted writes always carry a hash and build a whole replacement module in
        // memory, so a concurrent change must not be overwritten. Re-reading the hash and then
        // writing as two operations leaves a window between them; the two run inside ONE
        // workspace operation holding the file's modify rule, which is what actually excludes
        // another workspace writer (an editor save, another tool) for the whole compare-and-write.
        // A process writing the file outside Eclipse is not covered - IFile.setContents(FORCE)
        // has no compare-and-swap - but it IS seen by the guard's own read.
        if ((isMethodTargetedMode(req.mode) || MODE_REPLACE_METHODS.equals(req.mode)) && fileExists)
        {
            String[] lateHashError = new String[1];
            Exception[] failure = new Exception[1];
            ICoreRunnable operation = monitor -> {
                try
                {
                    lateHashError[0] = checkExpectedHashGuard(req, file, fileExists);
                    if (lateHashError[0] != null)
                    {
                        return;
                    }
                    mutationEntered.set(true);
                    writeFile(file, newLines, hasBom, fileExists, lineDelimiter);
                    mutationCommitted.set(true);
                }
                catch (Exception e)
                {
                    failure[0] = e;
                }
            };
            // The workspace comes from the resource itself - tools/impl must not add another
            // ResourcesPlugin.getWorkspace() copy (rule #4, enforced by ProjectContextAdoptionRatchetTest).
            IWorkspace workspace = file.getWorkspace();
            workspace.run(operation, workspace.getRuleFactory().modifyRule(file),
                IWorkspace.AVOID_UPDATE, null);
            if (failure[0] != null)
            {
                throw failure[0];
            }
            if (lateHashError[0] != null)
            {
                return lateHashError[0];
            }
        }
        else
        {
            // Write file (the mutating step — kept inline under the passed guards)
            mutationEntered.set(true);
            writeFile(file, newLines, hasBom, fileExists, lineDelimiter);
            // IFile.setContents/create has returned: any later exception is response work after the
            // workspace mutation, not a refusal that left the module untouched.
            mutationCommitted.set(true);
        }

        // The module of an object ADOPTED into an extension gets the state EDT's UI records in the owner's
        // .mdo when code is added to an adopted module - Extended - recorded at once (#711): EDT's own
        // listener sets it only in the model and never saves the .mdo. Every other module is untouched and
        // answers as before. A dry run (#465) must skip this step - it changes the model and exports the
        // owner's .mdo.
        return finishWrite(
            () -> AdoptedModuleState.ensureExtended(file.getProject(), scope, file.getProjectRelativePath().toString()),
            moduleState -> buildSuccessResponse(req.projectName, req.modulePath, req.mode, req.skipSyntaxCheck,
                newLines, fileExists, totalOriginal, normalizedCharacters, moduleState));
    }

    record ContentPreparation(String error, List<String> lines, byte[] outputBytes,
        String normalizedCharacters) { }

    /** Computes the requested mode against one detached base; never rereads a workspace file. */
    static ContentPreparation prepareContent(WriteRequest req, String base, boolean exists,
        boolean hasBom, String delimiter)
    {
        if (req.editsError != null) return new ContentPreparation(req.editsError, null, null, null);
        String normalizedCharacters = normalizeIncomingSource(req);
        String current = base.replace("\r\n", "\n");
        String error = evaluateExpectedHash(current, req.expectedHash, exists);
        if (error != null) return new ContentPreparation(error, null, null, null);
        if (req.expectedMethodHash != null)
        {
            var method = BslModuleUtils.replacementMethodSnapshot(splitSourceLines(base), req.methodName);
            if (method == null) return new ContentPreparation(ToolResult.error(
                "expectedMethodHash requires one complete, unambiguous addressable method '" + req.methodName
                    + "'. Read it again with read_method_source or get_module_structure; use searchReplace for unsupported declarations.")
                .put("code", "methodHashUnavailable").toJson(), null, null, null);
            if (!ContentHash.matches(method.text(), req.expectedMethodHash))
                return new ContentPreparation(ToolResult.error(
                    "expectedMethodHash mismatch: the method, its documentation or directives changed. Read it again with read_method_source before replacing it.")
                    .put("code", "methodHashMismatch").toJson(), null, null, null);
        }
        NewLinesResult computed = computeNewLines(req, exists, splitSourceLines(base), current);
        if (computed.error != null) return new ContentPreparation(computed.error, null, null, null);
        error = req.skipSyntaxCheck ? null : runSyntaxCheck(computed.newLines);
        return error != null ? new ContentPreparation(error, null, null, null)
            : new ContentPreparation(null, List.copyOf(computed.newLines),
                encodeOutput(computed.newLines, hasBom, delimiter), normalizedCharacters);
    }

    private String reviewWrite(WriteRequest req, IFile file) throws Exception
    {
        String digest = requestDigest(req);
        PreparedBslWrite write = prepareWrite(req, file, BslWriteReviews.shared().projectToken(req.projectName));
        if (write.baseExists() && write.baseHash().equals(PreparedBslWrite.sha256(write.outputBytes())))
            return ToolResult.success().put("status", "noChange").put("applied", false)
                .put("mutationCommitted", false).put(PROJECT_NAME, write.projectName())
                .put(MODULE_PATH, write.modulePath()).toJson();
        BslWriteReviews reviews = BslWriteReviews.shared();
        var admission = reviews.register(write, digest, (id, accepted) -> BslWriteReviewCommit.commit(accepted,
            () -> reviews.tryEnterMutation(id), this::finishReviewedWrite));
        if (admission.errorJson() != null) return admission.errorJson();
        String id = admission.snapshot().reviewId();
        if (req.requestStore != null) req.requestStore.observeReview(req.requestId, reviews, id);
        String openingError = admission.reused() ? BslWriteReviewUi.awaitOpeningAndFocus(id)
            : BslWriteReviewUi.open(write, id);
        if (openingError != null) return openingError;
        var current = reviews.get(id);
        return current == null ? ToolResult.error("Review status is no longer retained. Read the module before retrying.").toJson()
            : current.toJson();
    }

    /** All effective public behavior arguments, defaulted and ordered, without resource handles. */
    private static String requestDigest(WriteRequest req)
    {
        Map<String, Object> arguments = new TreeMap<>();
        arguments.put(PROJECT_NAME, req.projectName);
        arguments.put(MODULE_PATH, req.modulePath);
        arguments.put("mode", req.mode);
        arguments.put("source", req.source);
        arguments.put("oldSource", req.oldSource);
        arguments.put("methodName", req.methodName);
        arguments.put("skipSyntaxCheck", req.skipSyntaxCheck);
        arguments.put("normalizeInvalidCharacters", req.normalizeInvalidCharacters);
        arguments.put("expectedSource", req.expectedSource);
        arguments.put("expectedHash", req.expectedHash);
        arguments.put("overwrite", req.overwrite);
        // Leave legacy digests byte-for-byte compatible with already retained receipts.
        if (req.edits != null) arguments.put("edits", req.edits);
        if (req.expectedMethodHash != null) arguments.put("expectedMethodHash", req.expectedMethodHash);
        if (req.regionName != null) arguments.put("regionName", req.regionName);
        return PreparedBslWrite.sha256(GsonProvider.toJson(arguments).getBytes(StandardCharsets.UTF_8));
    }

    private PreparedBslWrite prepareWrite(WriteRequest req, IFile file, long token) throws Exception
    {
        boolean exists = file.exists();
        byte[] raw = exists ? BslWriteReviewCommit.readBytes(file) : new byte[0];
        boolean bom = raw.length >= 3 && raw[0] == UTF8_BOM[0]
            && raw[1] == UTF8_BOM[1] && raw[2] == UTF8_BOM[2];
        Charset charset = bom || !exists ? StandardCharsets.UTF_8 : Charset.forName(file.getCharset());
        String base = new String(raw, bom ? 3 : 0, raw.length - (bom ? 3 : 0), charset);
        ContentPreparation content = prepareContent(req, base, exists, !exists || bom,
            resolveLineDelimiter(file.getProject(), exists ? base : null));
        if (content.error() != null) throw new IllegalStateException(content.error());
        String proposed = new String(content.outputBytes(), StandardCharsets.UTF_8);
        if (proposed.startsWith("\uFEFF")) proposed = proposed.substring(1);
        return new PreparedBslWrite(req.projectName, file.getProject().getLocationURI().toString(), token,
            req.modulePath, BslWriteReviewCommit.canonicalLocation(file), exists,
            PreparedBslWrite.sha256(raw), base, proposed, content.outputBytes(),
            splitSourceLines(base).size(), content.lines().size(), req.mode, req.skipSyntaxCheck,
            content.normalizedCharacters());
    }

    /** Direct prepared writes use the same complete byte snapshot guard as reviewed writes. */
    private String writePreparedGuarded(WriteRequest req, IFile file, AtomicBoolean mutationEntered,
        AtomicBoolean mutationCommitted) throws Exception
    {
        PreparedBslWrite write = prepareWrite(req, file, 0L);
        BooleanSupplier enter = () -> { mutationEntered.set(true); return true; };
        Outcome outcome;
        if (HostKind.current() == HostKind.HEADLESS)
        {
            Outcome[] result = new Outcome[1];
            file.getWorkspace().run(monitor -> {
                result[0] = BslWriteReviewCommit.writeIfUnchanged(write, file, false, enter);
                if (Boolean.TRUE.equals(result[0].mutationCommitted())) mutationCommitted.set(true);
            }, file.getProject(), IWorkspace.AVOID_UPDATE, null);
            outcome = result[0];
            if (outcome.status() == State.APPLIED) return finishReviewedWrite(write);
        }
        else
        {
            outcome = BslWriteReviewCommit.commit(write, enter, this::finishReviewedWrite);
            if (Boolean.TRUE.equals(outcome.mutationCommitted())) mutationCommitted.set(true);
        }
        if (outcome.errorJson() != null) return outcome.errorJson();
        if (outcome.resultMarkdown() != null) return outcome.resultMarkdown();
        return ToolResult.error("Method replacement was not applied (" + outcome.status().name().toLowerCase(java.util.Locale.ROOT)
            + "). Read the module again before submitting another write.")
            .put("mutationCommitted", Boolean.TRUE.equals(outcome.mutationCommitted())).toJson();
    }

    static String previewResult(PreparedBslWrite write)
    {
        return ToolResult.success().put("preview", true).put("applied", false).put("mutationCommitted", false)
            .put("projectName", write.projectName()).put("modulePath", write.modulePath()).put("mode", write.mode())
            .put("wouldChange", !write.baseExists() || !write.baseHash().equals(PreparedBslWrite.sha256(write.outputBytes())))
            .put("contentHash", ContentHash.of(write.baseText())).put("proposedHash", ContentHash.of(write.proposedText()))
            .put("syntaxChecked", !write.skipSyntaxCheck()).put("linesBefore", write.linesBefore())
            .put("linesAfter", write.linesAfter()).put("normalizedCharacters", write.normalizedCharacters())
            .put("diff", ComparisonTextDiff.renderPreview(write.modulePath(), write.baseText(), write.proposedText(), write.baseExists()))
            .put("message", "Preview only. Submit again without preview to request application; all write guards and review settings are checked again.")
            .toJson();
    }

    private String finishReviewedWrite(PreparedBslWrite write)
    {
        ProjectContext ctx = ProjectContext.of(write.projectName());
        ProjectContext.ConfigurationResult root = ctx.resolveMetadataRoot();
        if (!root.ok()) return ToolResult.markErrorAfterMutation(root.errorJson());
        IFile file = BslModuleUtils.resolveModuleFile(ctx.project(), write.modulePath());
        return finishWrite(
            () -> AdoptedModuleState.ensureExtended(ctx.project(), root.scope(), file.getProjectRelativePath().toString()),
            state -> buildSuccessResponse(write.projectName(), write.modulePath(), write.mode(),
                write.skipSyntaxCheck(), splitSourceLines(write.proposedText()), write.baseExists(),
                write.linesBefore(), write.normalizedCharacters(), state));
    }

    /**
     * The end of a write whose text has landed: runs the adopted-module step, then answers with the
     * success response - or, when the step failed, with an error that says the text IS written
     * ({@code mutationCommitted:true}), so neither the caller nor a harness takes it for a refusal and
     * re-sends the edit. The step is run HERE, not handed in as a result, so a write cannot run it and
     * answer past its failure: leaving this call out leaves the step out with it. Package-visible for tests.
     *
     * @param step the adopted-module step; run exactly once
     * @param successResponse builds the success response from the step's result; not called when it failed
     * @return the response
     */
    static String finishWrite(Supplier<AdoptedModuleState.Result> step,
        Function<AdoptedModuleState.Result, String> successResponse)
    {
        AdoptedModuleState.Result moduleState = step.get();
        if (moduleState.failed())
        {
            return ToolResult.errorAfterMutation(moduleState.failureMessage()).toJson();
        }
        return successResponse.apply(moduleState);
    }

    /**
     * Optimistic-lock guard (any mode): when the caller carried an {@code expectedHash} from
     * its last read, reject if the module changed since — a cheap lost-update check that
     * complements searchReplace's {@code oldSource} match and replace's {@code expectedSource}.
     * Reads the canonical text the same way those guards do (readFileText, {@code \n}-normalized)
     * so the hashes always agree; skipped entirely when no {@code expectedHash} is given.
     *
     * @return a ready {@link ToolResult#error} JSON payload to return verbatim, or {@code null}
     *         when the guard passes (or is disabled)
     */
    private static String checkExpectedHashGuard(WriteRequest req, IFile file, boolean fileExists)
        throws Exception
    {
        String currentTextForHash =
            (req.expectedHash != null && !req.expectedHash.isEmpty() && fileExists)
                ? BslModuleUtils.readFileText(file).replace("\r\n", "\n") //$NON-NLS-1$ //$NON-NLS-2$
                : null;
        return evaluateExpectedHash(currentTextForHash, req.expectedHash, fileExists);
    }

    /**
     * Validates the required and scalar write arguments in the SAME order the inline
     * flow did: projectName presence, source presence + length, mode validity, and the
     * oldSource requirement for searchReplace. {@code mode} is expected to have already
     * been defaulted by the caller.
     *
     * @return a ready {@link ToolResult#error} JSON payload (or the {@code requireArgument}
     *         error) to return verbatim, or {@code null} when all arguments are valid
     */
    private static String validateWriteArguments(Map<String, String> params, WriteRequest req)
    {
        String err = JsonUtils.requireArgument(params, PROJECT_NAME);
        if (err != null)
        {
            return err;
        }
        if (req.expectedMethodHash != null)
        {
            if (!MODE_REPLACE_METHOD.equals(req.mode) || req.edits != null)
                return ToolResult.error("expectedMethodHash is supported only with mode=replaceMethod and one methodName; batches and other modes require expectedHash.").toJson();
            if (req.expectedMethodHash.isBlank())
                return ToolResult.error("expectedMethodHash must not be blank. Read methodHash with read_method_source.").toJson();
            if (params.containsKey("expectedHash") && (req.expectedHash == null || req.expectedHash.isBlank()))
                return ToolResult.error("expectedHash must not be blank when supplied with expectedMethodHash. Read contentHash with read_module_source or omit expectedHash to use only the method guard.").toJson();
        }
        if (params.containsKey("regionName") && !MODE_INSERT_INTO_REGION.equals(req.mode))
            return ToolResult.error("regionName is supported only with mode=insertIntoRegion.").toJson();
        if (MODE_INSERT_INTO_REGION.equals(req.mode))
        {
            for (String key : List.of("methodName", "edits", "oldSource", "expectedSource", "overwrite"))
                if (params.containsKey(key))
                    return ToolResult.error(key + " is not supported with insertIntoRegion; pass regionName and one complete method in source.").toJson();
            if (req.regionName == null || req.regionName.isBlank())
                return ToolResult.error("regionName is required for insertIntoRegion. Read the module with read_module_source and select an existing empty region.").toJson();
            if (req.expectedHash == null || req.expectedHash.isBlank())
                return ToolResult.error("expectedHash is required for insertIntoRegion. Read contentHash with read_module_source.").toJson();
        }
        if (req.editsError != null) return req.editsError;
        if (MODE_REPLACE_METHODS.equals(req.mode))
        {
            if (req.edits == null) return ToolResult.error("edits is required for mode=replaceMethods").toJson();
            for (String key : List.of("source", "methodName", "oldSource", "expectedSource", "overwrite"))
                if (params.containsKey(key))
                    return ToolResult.error(key + " is mutually exclusive with edits; pass only per-entry methodName/source.").toJson();
            if (req.expectedHash == null || req.expectedHash.isBlank())
                return ToolResult.error("expectedHash is required for edits. Read the module and pass its contentHash.").toJson();
            return null;
        }
        if (req.edits != null) return ToolResult.error("edits requires mode=replaceMethods; omit mode or use replaceMethods.").toJson();
        if (req.source == null)
        {
            return ToolResult.error("source is required").toJson(); //$NON-NLS-1$
        }
        if (req.source.length() > MAX_SOURCE_LENGTH)
        {
            return ToolResult.error("source exceeds maximum allowed length (" + MAX_SOURCE_LENGTH + " characters)").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }

        // Validate mode
        if (!MODE_REPLACE.equals(req.mode) && !MODE_APPEND.equals(req.mode)
            && !MODE_SEARCH_REPLACE.equals(req.mode) && !isMethodTargetedMode(req.mode)
            && !MODE_INSERT_INTO_REGION.equals(req.mode))
        {
            return ToolResult.error("invalid mode '" + req.mode + "'. " + //$NON-NLS-1$ //$NON-NLS-2$
                "Allowed: searchReplace, replace, append, replaceMethod, insertBefore, " + //$NON-NLS-1$
                "insertAfter, insertIntoRegion, replaceMethods").toJson(); //$NON-NLS-1$
        }

        // Validate oldSource for searchReplace mode
        if (MODE_SEARCH_REPLACE.equals(req.mode)
            && (req.oldSource == null || req.oldSource.isEmpty()))
        {
            return ToolResult.error("oldSource is required for searchReplace mode").toJson(); //$NON-NLS-1$
        }
        if (isMethodTargetedMode(req.mode))
        {
            if (req.methodName == null || req.methodName.trim().isEmpty())
            {
                return ToolResult.error("methodName is required for mode=" + req.mode).toJson(); //$NON-NLS-1$
            }
            if ((req.expectedHash == null || req.expectedHash.trim().isEmpty())
                && !(MODE_REPLACE_METHOD.equals(req.mode) && req.expectedMethodHash != null))
            {
                return ToolResult.error("expectedHash is required for mode=" + req.mode + //$NON-NLS-1$
                    ". Read the module with read_module_source or read_method_source and pass " + //$NON-NLS-1$
                    "its contentHash.").toJson(); //$NON-NLS-1$
            }
        }
        return null;
    }

    record MethodReplacement(String methodName, String source) { }
    private record BatchEdits(List<MethodReplacement> edits, String error) { }

    /** Strict parsing is intentional: extractObjectArray skips malformed members, unsafe for a write batch. */
    private static BatchEdits parseBatchEdits(Map<String, String> params)
    {
        if (!params.containsKey("edits")) return new BatchEdits(null, null);
        try
        {
            var value = JsonParser.parseString(params.get("edits"));
            if (!value.isJsonArray() || value.getAsJsonArray().isEmpty()
                || value.getAsJsonArray().size() > MAX_BATCH_EDITS)
                return batchError("edits must be an array of 1 to " + MAX_BATCH_EDITS + " method replacements.");
            List<MethodReplacement> edits = new ArrayList<>();
            var names = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
            long length = 0;
            for (var element : value.getAsJsonArray())
            {
                String label = "edits[" + edits.size() + "]";
                if (!element.isJsonObject()) return batchError(label + " must be an object with methodName and source.");
                var entry = element.getAsJsonObject();
                for (String key : entry.keySet())
                    if (!key.equals("methodName") && !key.equals("source"))
                        return batchError(label + " has unsupported field '" + key + "'; only methodName and source are allowed.");
                for (String key : List.of("methodName", "source"))
                    if (!entry.has(key) || !entry.get(key).isJsonPrimitive()
                        || !entry.getAsJsonPrimitive(key).isString() || entry.get(key).getAsString().isBlank())
                        return batchError(label + "." + key + " must be a non-blank string.");
                String name = entry.get("methodName").getAsString();
                if (!names.add(name)) return batchError(label + " has duplicate methodName '" + name + "'.");
                String source = entry.get("source").getAsString();
                length += source.length();
                if (length > MAX_SOURCE_LENGTH)
                    return batchError("edits combined source exceeds maximum allowed length (" + MAX_SOURCE_LENGTH + " characters).");
                edits.add(new MethodReplacement(name, source));
            }
            return new BatchEdits(List.copyOf(edits), null);
        }
        catch (RuntimeException e) { return batchError("edits must be a JSON array of methodName/source objects."); }
    }

    private static BatchEdits batchError(String message)
    {
        return new BatchEdits(null, ToolResult.error(message).toJson());
    }

    private static InvalidFileCharacters.Result normalizeIncoming(String source, boolean normalize)
    {
        String text = source.replace("\r\n", "\n");
        return normalize ? InvalidFileCharacters.normalize(text) : InvalidFileCharacters.unchanged(text);
    }

    /** Both direct writes and human-reviewed proposals normalize only incoming fragments. */
    private static String normalizeIncomingSource(WriteRequest req)
    {
        if (req.edits == null)
        {
            var fix = normalizeIncoming(req.source, req.normalizeInvalidCharacters);
            req.source = fix.text();
            return fix.summary();
        }
        List<MethodReplacement> normalized = new ArrayList<>();
        List<String> summaries = new ArrayList<>();
        for (int i = 0; i < req.edits.size(); i++)
        {
            var edit = req.edits.get(i);
            var fix = normalizeIncoming(edit.source(), req.normalizeInvalidCharacters);
            normalized.add(new MethodReplacement(edit.methodName(), fix.text()));
            if (fix.summary() != null) summaries.add("edits[" + i + "]: " + fix.summary());
        }
        req.edits = List.copyOf(normalized);
        return summaries.isEmpty() ? null : String.join("; ", summaries);
    }

    /** Validate every target against the same base, then splice backwards so positions cannot shift. */
    private static MethodEditResult applyMethodReplacements(List<String> original, List<MethodReplacement> edits)
    {
        record Replacement(int start, int end, List<String> lines) { }
        List<Replacement> replacements = new ArrayList<>();
        var spans = BslModuleUtils.findMethodSpansViaText(original);
        for (int i = 0; i < edits.size(); i++)
        {
            var edit = edits.get(i);
            MethodEditResult checked = applyMethodTargetedEdit(original, MODE_REPLACE_METHOD, edit.methodName(), edit.source());
            if (checked.error != null)
            {
                JsonObject error = JsonParser.parseString(checked.error).getAsJsonObject();
                return new MethodEditResult(ToolResult.error("edits[" + i + "] (" + edit.methodName() + "): "
                    + error.get("error").getAsString()).put("editIndex", i).toJson(), null);
            }
            var target = spans.stream().filter(span -> span.name.equalsIgnoreCase(edit.methodName())).findFirst().orElseThrow();
            replacements.add(new Replacement(target.startLine, target.endLine, splitSourceLines(edit.source())));
        }
        replacements.sort(Comparator.comparingInt(Replacement::start).reversed());
        List<String> result = new ArrayList<>(original);
        for (var replacement : replacements)
        {
            result.subList(replacement.start(), replacement.end() + 1).clear();
            result.addAll(replacement.start(), replacement.lines());
        }
        return new MethodEditResult(null, result);
    }

    private static boolean isMethodTargetedMode(String mode)
    {
        return MODE_REPLACE_METHOD.equals(mode) || MODE_INSERT_BEFORE.equals(mode)
            || MODE_INSERT_AFTER.equals(mode);
    }

    /**
     * Resolved module target produced by {@link #resolveModuleTarget}: either a ready
     * {@link ToolResult#error} JSON payload in {@link #error} (the caller returns it
     * verbatim) or the validated {@code src/}-relative {@link #modulePath}.
     */
    private static final class ModuleTargetResult
    {
        final String error;
        final String modulePath;

        private ModuleTargetResult(String error, String modulePath)
        {
            this.error = error;
            this.modulePath = modulePath;
        }
    }

    /**
     * Resolves the module target (read-only — no file is written). modulePath and
     * objectName form an exclusive OR (a flat Map schema cannot express XOR, so it is
     * enforced here): exactly one must be given. moduleType is a decorator on the
     * objectName path only — it is meaningless with an explicit modulePath, so it is
     * flagged instead of being silently ignored. formName/commandName are validated
     * deeper in {@link ModuleTargetResolver} (they depend on the resolved moduleType).
     * The resolved path is then validated against path traversal and a {@code .bsl}
     * extension, mirroring the inline checks exactly.
     *
     * @return a {@link ModuleTargetResult} whose {@link ModuleTargetResult#error} is
     *         non-{@code null} when the caller must return that JSON payload, otherwise
     *         the validated module path
     */
    private ModuleTargetResult resolveModuleTarget(String modulePath, String objectName,
        String moduleType, String formName, String commandName)
    {
        ModuleTargetResolver.Result result = ModuleTargetResolver.resolveForWrite(
            modulePath, objectName, moduleType, formName, commandName);
        return new ModuleTargetResult(result.error(), result.modulePath());
    }

    /**
     * Computed new module content produced by {@link #computeNewLines}: either a ready
     * {@link ToolResult#error} JSON payload in {@link #error} (the caller returns it
     * verbatim) or the {@link #newLines} to write.
     */
    private static final class NewLinesResult
    {
        final String error;
        final List<String> newLines;

        private NewLinesResult(String error, List<String> newLines)
        {
            this.error = error;
            this.newLines = newLines;
        }
    }

    /**
     * Computes the new module content for the request's mode (read-only — no file is
     * written; only the already-read {@code file}/{@code originalLines} content is
     * consulted). Mirrors the inline {@code switch} exactly, including the replace
     * lost-update precondition and the searchReplace not-found / multiple-match errors.
     * <p>
     * Declares {@code throws Exception} because {@link #checkReplacePrecondition} does,
     * so the same exceptions reach the SAME {@code catch (Exception)} in {@link #execute}
     * as before.
     *
     * @return a {@link NewLinesResult} whose {@link NewLinesResult#error} is non-{@code null}
     *         when the caller must return that JSON payload, otherwise the lines to write
     */
    private NewLinesResult computeNewLines(WriteRequest req, IFile file, boolean fileExists,
        List<String> originalLines) throws Exception
    {
        String currentText = fileExists && (MODE_SEARCH_REPLACE.equals(req.mode)
            || (MODE_REPLACE.equals(req.mode) && req.expectedSource != null
                && (req.expectedHash == null || req.expectedHash.isEmpty())))
            ? BslModuleUtils.readFileText(file).replace("\r\n", "\n") : null;
        return computeNewLines(req, fileExists, originalLines, currentText);
    }

    private static NewLinesResult computeNewLines(WriteRequest req, boolean fileExists,
        List<String> originalLines, String currentText)
    {
        String source = req.source;
        switch (req.mode)
        {
            case MODE_REPLACE:
            {
                // Lost-update guard: overwriting an EXISTING module blindly
                // clobbers any edit made since the agent last read it. Creating a
                // NEW module is unconditional (nothing to lose).
                // A matching expectedHash is itself a valid lost-update precondition
                // (the whole-file token proves the agent saw the current state) and
                // was ALREADY validated at step 6b — so when one was supplied, the
                // expectedSource/overwrite precondition is satisfied and skipped.
                boolean hashGuardSatisfied = req.expectedHash != null && !req.expectedHash.isEmpty();
                if (fileExists && !hashGuardSatisfied)
                {
                    String preconditionError =
                        evaluateReplacePrecondition(currentText, req.expectedSource, req.overwrite);
                    if (preconditionError != null)
                    {
                        return new NewLinesResult(preconditionError, null);
                    }
                }
                return new NewLinesResult(null, splitSourceLines(source));
            }

            case MODE_APPEND:
            {
                return new NewLinesResult(null, appendLines(originalLines, source));
            }

            case MODE_SEARCH_REPLACE:
            {
                // Normalize oldSource
                String oldSource = req.oldSource.replace("\r\n", "\n"); //$NON-NLS-1$ //$NON-NLS-2$

                // Read the raw file content (preserves the trailing newline that
                // writeFile always adds). Reconstructing it from originalLines via
                // String.join dropped that final newline, so an oldSource fragment
                // that ended at EOF (including the final newline) was reported
                // "not found".
                String currentContent = currentText;

                SearchReplaceResult sr = applySearchReplace(currentContent, oldSource, source);
                if (sr.occurrences == 0)
                {
                    return new NewLinesResult(ToolResult.error("oldSource not found in current file content. " + //$NON-NLS-1$
                        "The file may have changed since last read, or the oldSource text " + //$NON-NLS-1$
                        "does not match exactly. Please read the file again with read_module_source.").toJson(), null); //$NON-NLS-1$
                }
                if (sr.occurrences > 1)
                {
                    return new NewLinesResult(ToolResult.error("oldSource found multiple times in the file (" + //$NON-NLS-1$
                        sr.occurrences +
                        " occurrences). Provide a larger, more specific oldSource fragment " + //$NON-NLS-1$
                        "that matches exactly one location.").toJson(), null); //$NON-NLS-1$
                }

                return new NewLinesResult(null, splitSourceLines(sr.newContent));
            }

            case MODE_REPLACE_METHODS:
            {
                MethodEditResult edit = applyMethodReplacements(originalLines, req.edits);
                return new NewLinesResult(edit.error, edit.newLines);
            }
            case MODE_REPLACE_METHOD:
            case MODE_INSERT_BEFORE:
            case MODE_INSERT_AFTER:
            case MODE_INSERT_INTO_REGION:
            {
                // No BSL model here on purpose. Loading it touches EMF/Xtext, which the sibling
                // read tools reach only through the UI thread, and this runs on the MCP dispatch
                // thread; marshalling it would add another unbounded wait on a thread a modal
                // dialog can hold. Nothing is lost: the span the model produced was accepted only
                // when it matched the text span line for line, so it could not change an outcome.
                MethodEditResult edit = applyMethodTargetedEdit(originalLines, req.mode,
                    req.methodName, source, req.regionName);
                return new NewLinesResult(edit.error, edit.newLines);
            }

            default:
                return new NewLinesResult(ToolResult.error("unsupported mode: " + req.mode).toJson(), null); //$NON-NLS-1$
        }
    }

    /** Result of a pure method-targeted validation and splice. */
    static final class MethodEditResult
    {
        final String error;
        final List<String> newLines;

        MethodEditResult(String error, List<String> newLines)
        {
            this.error = error;
            this.newLines = newLines;
        }
    }

    /**
     * Validates one complete incoming method, resolves one unambiguous anchor in
     * the current module, and performs the requested line splice without I/O.
     * The raw current lines are authoritative: the text span is what the splice uses, and no
     * BSL model is consulted (see the call site for why).
     */
    static MethodEditResult applyMethodTargetedEdit(List<String> originalLines, String mode,
        String methodName, String source)
    {
        return applyMethodTargetedEdit(originalLines, mode, methodName, source, null);
    }

    private static MethodEditResult applyMethodTargetedEdit(List<String> originalLines, String mode,
        String methodName, String source, String regionName)
    {
        // A declaration this line scanner cannot ADDRESS - the keyword alone on its line, or the
        // keyword and a name with the parenthesis on the next - makes every answer below unsafe
        // rather than merely incomplete: the method is invisible, so a span can run straight
        // through it and the duplicate-name check cannot see the name it declares. Naming the
        // line and refusing is the only honest outcome for a whole-line scanner.
        BslModuleUtils.Unaddressable hidden = BslModuleUtils.unaddressable(originalLines);
        if (hidden != null)
        {
            return methodEditError("the module has " + hidden.what + " at line " //$NON-NLS-1$ //$NON-NLS-2$
                + (hidden.line + 1) + " ('" + originalLines.get(hidden.line).trim() //$NON-NLS-1$ //$NON-NLS-2$
                + "'), which method-targeted modes cannot address: " + hidden.fix //$NON-NLS-1$
                + ", or edit with mode 'searchReplace'."); //$NON-NLS-1$
        }
        // The mirror of the same blindness: a SECOND declaration written after the opener on
        // one line is invisible to every anchored rule here, so the scan counts one method
        // where two are declared and a span runs straight through the hidden one.
        int crowded = BslModuleUtils.secondDeclarationOnLine(originalLines);
        if (crowded >= 0)
        {
            return methodEditError("the module declares two methods on line " + (crowded + 1) //$NON-NLS-1$
                + " ('" + originalLines.get(crowded).trim() //$NON-NLS-1$
                + "'), which method-targeted modes cannot address: one declaration per line. " //$NON-NLS-1$
                + "Split them, or edit with mode 'searchReplace'."); //$NON-NLS-1$
        }
        List<BslModuleUtils.MethodSpan> moduleSpans =
            BslModuleUtils.findMethodSpansViaText(originalLines);
        List<BslModuleUtils.MethodSpan> targets = new ArrayList<>();
        for (BslModuleUtils.MethodSpan span : moduleSpans)
        {
            if (span.name.equalsIgnoreCase(methodName))
            {
                targets.add(span);
            }
        }
        boolean intoRegion = MODE_INSERT_INTO_REGION.equals(mode);
        if (!intoRegion && targets.isEmpty())
        {
            return methodEditError("methodName '" + methodName + //$NON-NLS-1$
                "' was not found in the current module. Read it again with " + //$NON-NLS-1$
                "read_module_source or read_method_source and retry."); //$NON-NLS-1$
        }
        if (!intoRegion && targets.size() > 1)
        {
            return methodEditError("methodName '" + methodName + "' is ambiguous: " + //$NON-NLS-1$ //$NON-NLS-2$
                targets.size() + " declarations exist in the current module. " + //$NON-NLS-1$
                "Method-targeted writes do not choose between preprocessor branches."); //$NON-NLS-1$
        }
        if (!intoRegion && !targets.get(0).complete)
        {
            return methodEditError("methodName '" + methodName + //$NON-NLS-1$
                "' has no matching method terminator in the current module. " + //$NON-NLS-1$
                "The target span is incomplete, so no write was performed."); //$NON-NLS-1$
        }

        List<String> sourceLines = splitSourceLines(source);
        // The same guard on the PAYLOAD: a declaration the scanner cannot address hides inside an
        // otherwise one-method source, and the outer method then borrows the hidden one's
        // terminator - the exactly-one-method contract would be satisfied by text that is not.
        BslModuleUtils.Unaddressable hiddenInSource = BslModuleUtils.unaddressable(sourceLines);
        if (hiddenInSource != null)
        {
            return methodEditError("source has " + hiddenInSource.what + " at line " //$NON-NLS-1$ //$NON-NLS-2$
                + (hiddenInSource.line + 1) + " ('" //$NON-NLS-1$
                + sourceLines.get(hiddenInSource.line).trim()
                + "'), which this mode cannot address: " + hiddenInSource.fix + "."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        // And on the payload: "Procedure A() Function B()" reads as ONE method to the scan and
        // as balanced pairs to the syntax check, so without this it would be written as a
        // method nested inside a method - invalid BSL reported as a success.
        int crowdedInSource = BslModuleUtils.secondDeclarationOnLine(sourceLines);
        if (crowdedInSource >= 0)
        {
            return methodEditError("source declares two methods on line " //$NON-NLS-1$
                + (crowdedInSource + 1) + " ('" + sourceLines.get(crowdedInSource).trim() //$NON-NLS-1$ //$NON-NLS-2$
                + "'): this mode takes exactly one method, and a declaration after the opener " //$NON-NLS-1$
                + "on the same line is not one. Put one declaration per line."); //$NON-NLS-1$
        }
        List<BslModuleUtils.MethodSpan> sourceSpans =
            BslModuleUtils.findMethodSpansViaText(sourceLines);
        if (sourceSpans.size() != 1)
        {
            return methodEditError("source must contain exactly one complete Procedure/Function " + //$NON-NLS-1$
                "for mode=" + mode + "; found " + sourceSpans.size() + " declarations."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        BslModuleUtils.MethodSpan incoming = sourceSpans.get(0);
        if (!incoming.complete)
        {
            return methodEditError("source must contain exactly one complete Procedure/Function; " + //$NON-NLS-1$
                "declaration '" + incoming.name + "' has no matching method terminator."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (hasNonBlankLinesOutside(sourceLines, incoming.startLine, incoming.endLine))
        {
            return methodEditError("source must consist of exactly one complete Procedure/Function " + //$NON-NLS-1$
                "plus its contiguous documentation and annotations; module-level code or " + //$NON-NLS-1$
                "detached text outside that method is not allowed."); //$NON-NLS-1$
        }

        if (MODE_REPLACE_METHOD.equals(mode)
            && !incoming.name.equalsIgnoreCase(methodName))
        {
            return methodEditError("source declares method '" + incoming.name + //$NON-NLS-1$
                "', but replaceMethod targets '" + methodName + "'. Rename is not supported; " + //$NON-NLS-1$ //$NON-NLS-2$
                "declare the targeted method in source."); //$NON-NLS-1$
        }
        if (!MODE_REPLACE_METHOD.equals(mode))
        {
            for (BslModuleUtils.MethodSpan existing : moduleSpans)
            {
                if (existing.name.equalsIgnoreCase(incoming.name))
                {
                    return methodEditError("cannot insert method '" + incoming.name + //$NON-NLS-1$
                        "': a declaration with that name already exists in the module. " + //$NON-NLS-1$
                        "Read the module again and choose a unique method name."); //$NON-NLS-1$
                }
            }
        }

        BslModuleUtils.MethodSpan target = intoRegion ? null : targets.get(0);
        if (MODE_REPLACE_METHOD.equals(mode) && incoming.isFunction != target.isFunction)
        {
            // Refused for the same reason a rename is: matching the NAME is not the whole
            // contract. Turning a Function into a Procedure takes the return value away from
            // every expression that consumes Target(), and no check below can see it -
            // Function/EndFunction and Procedure/EndProcedure are each balanced on their own.
            return methodEditError("source declares " //$NON-NLS-1$
                + (incoming.isFunction ? "a Function" : "a Procedure") //$NON-NLS-1$ //$NON-NLS-2$
                + ", but '" + methodName + "' is " //$NON-NLS-1$ //$NON-NLS-2$
                + (target.isFunction ? "a Function" : "a Procedure") //$NON-NLS-1$ //$NON-NLS-2$
                + " in the module. replaceMethod does not change the kind of a method, because " //$NON-NLS-1$
                + "callers of a Function consume its return value; declare the same kind in " //$NON-NLS-1$
                + "source, or change the kind deliberately with delete plus insert."); //$NON-NLS-1$
        }
        List<String> result;
        if (MODE_REPLACE_METHOD.equals(mode))
        {
            result = new ArrayList<>(originalLines);
            result.subList(target.startLine, target.endLine + 1).clear();
            result.addAll(target.startLine, sourceLines);
        }
        else
        {
            int insertionLine;
            if (intoRegion)
            {
                BslEmptyRegion.Resolution region = BslEmptyRegion.resolve(originalLines, regionName);
                if (region.error() != null) return methodEditError(region.error());
                insertionLine = region.insertionLine();
            }
            else
                insertionLine = MODE_INSERT_BEFORE.equals(mode) ? target.startLine : target.endLine + 1;
            result = BslModuleUtils.insertMethodLines(originalLines, insertionLine, sourceLines);
        }
        return new MethodEditResult(null, result);
    }

    private static MethodEditResult methodEditError(String message)
    {
        return new MethodEditResult(ToolResult.error(message).toJson(), null);
    }

    private static boolean hasNonBlankLinesOutside(List<String> lines, int startLine, int endLine)
    {
        for (int i = 0; i < lines.size(); i++)
        {
            if ((i < startLine || i > endLine) && !lines.get(i).trim().isEmpty())
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Runs the BSL syntax check (read-only) over the computed {@code newLines} and,
     * on failure, formats the blocking error message exactly as the inline check did.
     *
     * @return a ready {@link ToolResult#error} JSON payload to return verbatim, or
     *         {@code null} when the syntax check passes
     */
    private static String runSyntaxCheck(List<String> newLines)
    {
        BslSyntaxChecker.CheckResult checkResult = BslSyntaxChecker.check(newLines);
        if (checkResult.isValid())
        {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("BSL syntax check failed. Write blocked.\n\n"); //$NON-NLS-1$
        sb.append("Errors:\n"); //$NON-NLS-1$
        for (String error : checkResult.getErrors())
        {
            sb.append("- ").append(error).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        sb.append("\nPass skipSyntaxCheck=true to force write."); //$NON-NLS-1$
        return ToolResult.error(sb.toString()).toJson();
    }

    /**
     * Builds the success frontmatter + content returned after the module is written
     * (pure string building — no side effects), preserving the exact keys and the
     * {@code linesBefore}/{@code newFile} branch the inline flow used. Package-visible for tests.
     *
     * @param moduleState the adopted-module step's result: a key and, when it has one, a paragraph
     *     for the module of an adopted object; nothing for any other module
     * @return the wrapped success response
     */
    static String buildSuccessResponse(String projectName, String modulePath, String mode, // NOSONAR the response's facts; a parameter object would only rename them
        boolean skipSyntaxCheck, List<String> newLines, boolean fileExists, int totalOriginal,
        String normalizedCharacters, AdoptedModuleState.Result moduleState)
    {
        FrontMatter fm = FrontMatter.create()
            .put("tool", NAME) //$NON-NLS-1$
            .put(PROJECT_NAME, projectName)
            .put(MODULE_PATH, modulePath)
            .put("mode", mode) //$NON-NLS-1$
            .put("status", "success") //$NON-NLS-1$ //$NON-NLS-2$
            .put("linesAfter", newLines.size()) //$NON-NLS-1$
            .put("syntaxCheck", skipSyntaxCheck ? "skipped" : "passed"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        if (fileExists)
        {
            fm.put("linesBefore", totalOriginal); //$NON-NLS-1$
        }
        else
        {
            fm.put("newFile", true); //$NON-NLS-1$
        }

        // Only when something WAS replaced: a key that is always there would be noise on every
        // write, while a silent replacement would leave the caller believing it wrote what it
        // sent. Absent means the source went in untouched.
        if (normalizedCharacters != null)
        {
            fm.put("normalizedCharacters", normalizedCharacters); //$NON-NLS-1$
        }

        // The same rule: only for the module of an object adopted into an extension (#711) - every
        // other write answers byte for byte as before. The paragraph comes only with a switch, a state
        // left as it is, a missing extension block or a warning; on every write into an already-Extended
        // module the key alone says it.
        String extensionState = moduleState.frontMatterValue();
        if (extensionState != null)
        {
            fm.put(AdoptedModuleState.FRONT_MATTER_KEY, extensionState);
        }
        String note = moduleState.bodySentence();
        return fm.wrapContent(note == null ? "File written successfully" //$NON-NLS-1$
            : "File written successfully\n\n" + note); //$NON-NLS-1$
    }

    /**
     * Counts the number of occurrences of a substring in a string.
     */
    private static int countOccurrences(String text, String search)
    {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(search, idx)) >= 0)
        {
            count++;
            idx++;
        }
        return count;
    }

    /**
     * Result of a pure content-based search/replace.
     * {@code occurrences}: 0 = oldSource absent, 1 = replaced ({@code newContent}
     * set), &gt;1 = ambiguous ({@code newContent} null).
     */
    static final class SearchReplaceResult
    {
        final int occurrences;
        final String newContent;

        SearchReplaceResult(int occurrences, String newContent)
        {
            this.occurrences = occurrences;
            this.newContent = newContent;
        }
    }

    /**
     * Pure content-based search/replace used by MODE_SEARCH_REPLACE. Operating on
     * the raw file content (which keeps the trailing newline) means an oldSource
     * fragment that ends at EOF, including the final newline, is found. Returns
     * occurrences=0 when absent and &gt;1 when ambiguous (newContent null in both),
     * or 1 with the replaced content.
     */
    static SearchReplaceResult applySearchReplace(String currentContent, String oldSource, String newSource)
    {
        int idx = currentContent.indexOf(oldSource);
        if (idx < 0)
        {
            return new SearchReplaceResult(0, null);
        }
        int secondIdx = currentContent.indexOf(oldSource, idx + 1);
        if (secondIdx >= 0)
        {
            return new SearchReplaceResult(countOccurrences(currentContent, oldSource), null);
        }
        String newContent = currentContent.substring(0, idx) + newSource
            + currentContent.substring(idx + oldSource.length());
        return new SearchReplaceResult(1, newContent);
    }

    /**
     * Lost-update precondition for {@code mode=replace} over an EXISTING module.
     * mode=searchReplace is already protected by its {@code oldSource} content match;
     * a full replace would otherwise blindly clobber whatever the module contains now,
     * losing a concurrent edit (or one made between the agent's read and write).
     * <p>
     * Resolution:
     * <ul>
     * <li>{@code expectedSource} provided — compare it to the current module content;
     *     on mismatch reject with a "read it again then retry" steer (mirrors the
     *     searchReplace not-found steer), on match proceed.</li>
     * <li>else {@code overwrite == true} — proceed (explicit force).</li>
     * <li>else — reject and point at expectedSource / overwrite / searchReplace.</li>
     * </ul>
     * Content is compared on {@code \n}-normalized text (same normalization
     * {@code source} and searchReplace use), so a CRLF/LF difference alone is not a
     * spurious mismatch.
     *
     * @return {@code null} to proceed, or a ready {@link ToolResult#error} JSON
     *     payload to return verbatim from {@link #execute}.
     */
    private static String checkReplacePrecondition(IFile file, String expectedSource,
        boolean overwrite) throws Exception
    {
        // Read the raw module content (same normalization searchReplace uses) ONLY
        // when an expectedSource has to be compared; otherwise the decision is purely
        // overwrite vs reject and no file access is needed.
        String currentContent = expectedSource == null
            ? null
            : BslModuleUtils.readFileText(file).replace("\r\n", "\n"); //$NON-NLS-1$ //$NON-NLS-2$
        return evaluateReplacePrecondition(currentContent, expectedSource, overwrite);
    }

    /**
     * Pure decision for the {@code mode=replace}-over-existing lost-update guard,
     * split out so it is unit-testable without an Eclipse workspace (mirrors
     * {@link #applySearchReplace}). {@code currentContent} is the {@code \n}-normalized
     * current module content and may be {@code null} when {@code expectedSource} is
     * {@code null} (no content comparison is performed in that branch).
     *
     * @return {@code null} to proceed, or a ready {@link ToolResult#error} JSON
     *     payload to return verbatim from {@link #execute}.
     */
    static String evaluateReplacePrecondition(String currentContent, String expectedSource,
        boolean overwrite)
    {
        if (expectedSource != null)
        {
            String expectedNormalized = expectedSource.replace("\r\n", "\n"); //$NON-NLS-1$ //$NON-NLS-2$
            if (currentContent == null || !currentContent.equals(expectedNormalized))
            {
                return ToolResult.error("expectedSource does not match the current module content. " + //$NON-NLS-1$
                    "The module changed since you read it (a concurrent edit), so a full replace " + //$NON-NLS-1$
                    "would lose that change. Please read the file again with read_module_source, " + //$NON-NLS-1$
                    "then retry with the up-to-date expectedSource.").toJson(); //$NON-NLS-1$
            }
            return null;
        }
        if (overwrite)
        {
            return null;
        }
        return ToolResult.error("module already exists; a full replace would overwrite it and " + //$NON-NLS-1$
            "could lose a concurrent edit. Pass expectedSource (the content you last read with " + //$NON-NLS-1$
            "read_module_source) to guard against a lost update, or overwrite=true to force, " + //$NON-NLS-1$
            "or use mode=searchReplace to change only a fragment.").toJson(); //$NON-NLS-1$
    }

    /**
     * Pure decision for the {@code expectedHash} optimistic-lock guard, split out so
     * it is unit-testable without an Eclipse workspace (mirrors
     * {@link #evaluateReplacePrecondition}). Applies to ANY write mode and is the cheap
     * counterpart to {@code expectedSource}: the caller round-trips the opaque
     * {@code contentHash} from a read tool, and a change since then is rejected with the
     * same "read it again then retry" steer.
     *
     * @param currentContent the {@code \n}-normalized current module content, or
     *     {@code null} when {@code expectedHash} is blank or the file does not exist
     *     (no content comparison is performed in those branches)
     * @param expectedHash the opaque token the caller carried over from its read; a
     *     blank value disables the guard (backward compatible)
     * @param fileExists whether the target module already exists on disk
     * @return {@code null} to proceed, or a ready {@link ToolResult#error} JSON payload
     *     to return verbatim from {@link #execute}
     */
    static String evaluateExpectedHash(String currentContent, String expectedHash, boolean fileExists)
    {
        if (expectedHash == null || expectedHash.isEmpty())
        {
            return null;
        }
        if (!fileExists)
        {
            return ToolResult.error("expectedHash was provided but this module does not exist yet, " + //$NON-NLS-1$
                "so there is nothing to match. To CREATE a new module omit expectedHash; otherwise the " + //$NON-NLS-1$
                "path is wrong or the module was deleted — re-check with read_module_source.").toJson(); //$NON-NLS-1$
        }
        if (!ContentHash.matches(currentContent, expectedHash))
        {
            return ToolResult.error("expectedHash does not match the current module content. " + //$NON-NLS-1$
                "The module changed since you read it (a concurrent edit), so writing now could lose " + //$NON-NLS-1$
                "that change. Please read the file again with read_module_source and retry with the " + //$NON-NLS-1$
                "up-to-date contentHash.").toJson(); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Whether the text ends with a line separator in any of the three spellings.
     *
     * @param source the payload
     * @return whether it ends with a separator
     */
    private static boolean endsWithSeparator(String source)
    {
        return source.endsWith("\n") || source.endsWith("\r"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The module's lines after {@code mode=append}: the current lines, then the source's. An EMPTY
     * source appends nothing, so the write rewrites the module's current text - the no-op an adopted
     * module's step is re-run with (#711). Package-visible for tests.
     *
     * @param originalLines the module's current lines
     * @param source the appended text
     * @return the new lines
     */
    static List<String> appendLines(List<String> originalLines, String source)
    {
        List<String> appended = new ArrayList<>(originalLines);
        appended.addAll(splitSourceLines(source));
        return appended;
    }

    /**
     * Splits source code into lines, handling trailing newline artifact. An empty source is no line
     * at all, not one empty line.
     */
    static List<String> splitSourceLines(String source)
    {
        if (source.isEmpty())
        {
            return new ArrayList<>();
        }

        // All three separators, not just the newline: a payload written with bare carriage
        // returns would otherwise arrive as ONE element holding several physical lines, and
        // every rule here reasons about whole lines.
        String[] parts = source.split("\\r\\n|\\r|\\n", -1); //$NON-NLS-1$
        List<String> lines = new ArrayList<>(Arrays.asList(parts));

        // A trailing separator produces a trailing empty element; remove it to avoid adding an
        // extra blank line. ANY separator, not just the newline: the split above cuts on all
        // three, so a payload ending in a bare carriage return produces that element too - and
        // testing only for the newline left it in, splicing a blank line into the module.
        if (endsWithSeparator(source) && lines.size() > 1
            && lines.get(lines.size() - 1).isEmpty())
        {
            lines.remove(lines.size() - 1);
        }

        return lines;
    }

    /**
     * Detects if the file starts with UTF-8 BOM.
     */
    private boolean detectBom(IFile file)
    {
        try (InputStream is = file.getContents();
             BufferedInputStream bis = new BufferedInputStream(is))
        {
            byte[] bom = new byte[3];
            int read = bis.read(bom);
            return read == 3
                && (bom[0] & 0xFF) == 0xEF
                && (bom[1] & 0xFF) == 0xBB
                && (bom[2] & 0xFF) == 0xBF;
        }
        catch (Exception e)
        {
            // Default: assume BOM for BSL files
            return true;
        }
    }

    /**
     * The line delimiter to write the module with. An EXISTING module keeps its OWN dominant
     * delimiter (so an edit does not flip every line to LF and mark the whole module changed —
     * issue #305); a brand-new module (or an existing single-line one with no delimiter) uses the
     * project's configured line separator, falling back to the platform default.
     *
     * @param project the module's project (for the line-separator preference scope)
     * @param rawCurrentText the file's raw content WITH its original separators, or {@code null}
     *     for a new file
     * @return {@code "\r\n"} or {@code "\n"}
     */
    private static String resolveLineDelimiter(IProject project, String rawCurrentText)
    {
        String detected = detectDominantDelimiter(rawCurrentText);
        if (detected != null)
        {
            return detected;
        }
        String pref = Platform.getPreferencesService().getString(Platform.PI_RUNTIME,
            Platform.PREF_LINE_SEPARATOR, null,
            new IScopeContext[] { new ProjectScope(project), InstanceScope.INSTANCE });
        return (pref != null && !pref.isEmpty()) ? pref : System.lineSeparator();
    }

    /**
     * The dominant line delimiter of {@code text} ({@code "\r\n"} vs {@code "\n"}), or {@code null}
     * when the text is null/empty or has no line break at all (a single line, where the caller falls
     * back to the project default). A tie counts as CRLF (the EDT/1C convention for BSL modules).
     */
    static String detectDominantDelimiter(String text)
    {
        if (text == null || text.isEmpty())
        {
            return null;
        }
        int crlf = countOccurrences(text, "\r\n"); //$NON-NLS-1$
        int lf = countOccurrences(text, "\n") - crlf; // bare LF not part of a CRLF //$NON-NLS-1$
        if (crlf == 0 && lf == 0)
        {
            return null;
        }
        return crlf >= lf ? "\r\n" : "\n"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Writes lines to the file, preserving BOM if needed. {@code lineDelimiter} is the module's own
     * separator (see {@link #resolveLineDelimiter}); {@code lines} are LF-normalized (no {@code \r}),
     * so joining with it re-applies the file's convention verbatim.
     */
    private void writeFile(IFile file, List<String> lines, boolean withBom,
        boolean fileExists, String lineDelimiter) throws Exception
    {
        byte[] output = encodeOutput(lines, withBom, lineDelimiter);

        InputStream stream = new ByteArrayInputStream(output);

        if (fileExists)
        {
            file.setContents(stream, IResource.FORCE | IResource.KEEP_HISTORY, null);
        }
        else
        {
            // Create parent directories if needed
            createParentFolders(file);
            file.create(stream, true, null);
        }
    }

    static byte[] encodeOutput(List<String> lines, boolean withBom, String lineDelimiter)
    {
        String content = String.join(lineDelimiter, lines);

        // Ensure file ends with a newline, in the file's own delimiter
        if (!content.endsWith(lineDelimiter))
        {
            content += lineDelimiter;
        }

        byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8); //$NON-NLS-1$

        byte[] output;
        if (withBom)
        {
            output = new byte[UTF8_BOM.length + contentBytes.length];
            System.arraycopy(UTF8_BOM, 0, output, 0, UTF8_BOM.length);
            System.arraycopy(contentBytes, 0, output, UTF8_BOM.length, contentBytes.length);
        }
        else
        {
            output = contentBytes;
        }

        return output;
    }

    /**
     * Recursively creates parent folders for the given file.
     */
    private void createParentFolders(IFile file) throws Exception
    {
        IFolder parent = (IFolder)file.getParent();
        if (parent != null && !parent.exists())
        {
            createFolder(parent);
        }
    }

    /**
     * Recursively creates a folder and its parents.
     */
    private void createFolder(IFolder folder) throws Exception
    {
        if (folder.exists())
        {
            return;
        }
        if (folder.getParent() instanceof IFolder)
        {
            IFolder parentFolder = (IFolder)folder.getParent();
            if (!parentFolder.exists())
            {
                createFolder(parentFolder);
            }
        }
        folder.create(true, true, null);
    }
}
