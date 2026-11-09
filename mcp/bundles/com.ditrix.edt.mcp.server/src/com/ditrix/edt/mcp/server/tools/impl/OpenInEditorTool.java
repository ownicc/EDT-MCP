/**
 * MCP Server for EDT
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.impl;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;

import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.BslModuleUtils;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
import com.ditrix.edt.mcp.server.utils.ModuleTargetResolver;

/** Explicit editor activation; source and dirty state are never changed. */
public class OpenInEditorTool implements IMcpTool
{
    public static final String NAME = "open_in_editor"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Open or activate a BSL module in EDT, optionally at a method or line/column. " //$NON-NLS-1$
            + "Preserves unsaved edits. Parameters and examples: get_tool_guide('open_in_editor')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", "EDT project name", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("modulePath", "BSL path from src/; exactly one of modulePath or objectName") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("objectName", "Type.Name; TYPE may be Russian/English, name is a programmatic identifier, not a synonym") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("moduleType", "With objectName: ObjectModule, ManagerModule, FormModule, CommandModule, RecordSetModule or Module; defaults by type") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("formName", "With objectName and FormModule: form identifier; not required for CommonForm") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("commandName", "With objectName and CommandModule: command identifier; not required for CommonCommand") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("methodName", "Case-insensitive method name; cannot be combined with line/column") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("line", "1-based line in the current editor document") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("column", "1-based column; requires line; default 1") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether opening and requested navigation completed", true) //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("opened", "The BSL editor was opened or activated") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("projectName", "Resolved project") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("modulePath", "Canonical source-relative module path") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("line", "Selected 1-based line when navigation was requested") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("column", "Selected 1-based column when navigation was requested") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String required = JsonUtils.requireArguments(params, "projectName"); //$NON-NLS-1$
        if (required != null)
        {
            return required;
        }
        FutureTask<String> task = null;
        try
        {
            String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
            ModuleTargetResolver.Result target = ModuleTargetResolver.resolve(
                JsonUtils.extractStringArgument(params, "modulePath"), //$NON-NLS-1$
                JsonUtils.extractStringArgument(params, "objectName"), //$NON-NLS-1$
                JsonUtils.extractStringArgument(params, "moduleType"), //$NON-NLS-1$
                JsonUtils.extractStringArgument(params, "formName"), //$NON-NLS-1$
                JsonUtils.extractStringArgument(params, "commandName")); //$NON-NLS-1$
            if (target.error() != null)
            {
                return target.error();
            }
            String modulePath = target.modulePath().replace('\\', '/');
            String method = JsonUtils.extractStringArgument(params, "methodName"); //$NON-NLS-1$
            String methodName = method == null || method.isBlank() ? null : method.trim();
            Integer line = coordinate(JsonUtils.extractStringArgument(params, "line"), "line"); //$NON-NLS-1$
            Integer column = coordinate(JsonUtils.extractStringArgument(params, "column"), "column"); //$NON-NLS-1$
            validateNavigation(methodName, line, column);
            if (!modulePath.toLowerCase(Locale.ROOT).endsWith(".bsl")) //$NON-NLS-1$
            {
                throw new IllegalArgumentException("modulePath must identify a .bsl module"); //$NON-NLS-1$
            }
            org.eclipse.core.runtime.IPath path = new org.eclipse.core.runtime.Path(modulePath);
            if (path.isAbsolute() || path.getDevice() != null || Arrays.asList(modulePath.split("/")).contains("..")) //$NON-NLS-1$
            {
                throw new IllegalArgumentException("modulePath must be a project-relative path from src/"); //$NON-NLS-1$
            }
            ProjectContext ctx = ProjectContext.of(projectName);
            if (!ctx.exists())
            {
                return ToolResult.error(ProjectContext.notFoundMessage(projectName)).toJson();
            }
            IProject project = ctx.project();
            if (!project.isOpen())
            {
                return ToolResult.error("Project is closed: " + projectName).toJson(); //$NON-NLS-1$
            }
            IFile file = BslModuleUtils.resolveModuleFile(project, modulePath);
            if (file == null || !file.exists() || !project.equals(file.getProject()))
            {
                return ToolResult.error("BSL module not found in project: " + modulePath).toJson(); //$NON-NLS-1$
            }
            if (!PlatformUI.isWorkbenchRunning())
            {
                return ToolResult.error("No running EDT workbench").toJson(); //$NON-NLS-1$
            }
            Display display = PlatformUI.getWorkbench().getDisplay();
            task = new FutureTask<>(() -> open(file, methodName, line, column));
            if (Display.getCurrent() == display)
            {
                task.run();
            }
            else
            {
                display.asyncExec(task);
            }
            return task.get(10, TimeUnit.SECONDS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            if (task != null)
            {
                task.cancel(false);
            }
            return ToolResult.error("Editor opening interrupted; check the editor before retrying").toJson(); //$NON-NLS-1$
        }
        catch (TimeoutException e)
        {
            task.cancel(false);
            return ToolResult.error("EDT UI did not finish within 10 seconds; editor state may have changed").toJson(); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return ToolResult.error(cause.getMessage()).toJson();
        }
    }

    private static String open(IFile file, String methodName, Integer line, Integer column) throws Exception
    {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        IWorkbenchPage page = window == null ? null : window.getActivePage();
        if (page == null)
        {
            return ToolResult.error("No active EDT workbench page").toJson(); //$NON-NLS-1$
        }
        IEditorPart editor = IDE.openEditor(page, file, true);
        ITextEditor textEditor = editor instanceof ITextEditor text ? text : editor == null ? null : editor.getAdapter(ITextEditor.class);
        if (textEditor == null || textEditor.getDocumentProvider() == null)
        {
            return ToolResult.error("Opened editor does not expose a BSL text document").put("opened", editor != null).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        IDocument document = textEditor.getDocumentProvider().getDocument(textEditor.getEditorInput());
        if (document == null)
        {
            return ToolResult.error("Opened editor has no text document").put("opened", true).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        Position position;
        try
        {
            position = locate(document, methodName, line, column);
        }
        catch (IllegalArgumentException e)
        {
            return ToolResult.error(e.getMessage()).put("opened", true).toJson(); //$NON-NLS-1$
        }
        if (position != null)
        {
            textEditor.selectAndReveal(position.offset(), 0);
        }
        page.activate(editor);
        ToolResult result = ToolResult.success().put("opened", true) //$NON-NLS-1$
            .put("projectName", file.getProject().getName()) //$NON-NLS-1$
            .put("modulePath", BslModuleUtils.sourceRelativePath(file)); //$NON-NLS-1$
        if (position != null)
        {
            result.put("line", position.line()).put("column", position.column()); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return result.toJson();
    }

    private static Integer coordinate(String raw, String name)
    {
        if (raw == null)
        {
            return null;
        }
        try
        {
            return new BigDecimal(raw).intValueExact();
        }
        catch (ArithmeticException | NumberFormatException e)
        {
            throw new IllegalArgumentException(name + " must be a positive integer"); //$NON-NLS-1$
        }
    }

    private static void validateNavigation(String methodName, Integer line, Integer column)
    {
        if (methodName != null && (line != null || column != null))
        {
            throw new IllegalArgumentException("methodName cannot be combined with line or column"); //$NON-NLS-1$
        }
        if (column != null && line == null)
        {
            throw new IllegalArgumentException("column requires line"); //$NON-NLS-1$
        }
        if (line != null && line < 1 || column != null && column < 1)
        {
            throw new IllegalArgumentException("line and column must be positive (1-based)"); //$NON-NLS-1$
        }
    }

    record Position(int offset, int line, int column) { }

    static Position locate(IDocument document, String methodName, Integer line, Integer column) throws BadLocationException
    {
        validateNavigation(methodName, line, column);
        if (methodName != null)
        {
            List<String> lines = Arrays.asList(document.get().split("\\r\\n|\\r|\\n", -1)); //$NON-NLS-1$
            BslModuleUtils.Unaddressable unsupported = BslModuleUtils.unaddressable(lines);
            if (unsupported != null)
            {
                throw new IllegalArgumentException("Cannot uniquely locate method: " + unsupported.what //$NON-NLS-1$
                    + " at line " + (unsupported.line + 1) + ". Use line/column navigation instead."); //$NON-NLS-1$ //$NON-NLS-2$
            }
            List<BslModuleUtils.MethodSpan> matches = BslModuleUtils.findMethodSpansViaText(lines).stream()
                .filter(method -> method.name.equalsIgnoreCase(methodName)).toList();
            if (matches.size() != 1)
            {
                throw new IllegalArgumentException(matches.isEmpty() ? "Method not found: " + methodName : "Ambiguous method: " + methodName); //$NON-NLS-1$ //$NON-NLS-2$
            }
            line = matches.get(0).declarationLine + 1;
        }
        if (line == null)
        {
            return null;
        }
        int col = column == null ? 1 : column;
        if (line > document.getNumberOfLines() || col > document.getLineInformation(line - 1).getLength() + 1)
        {
            throw new IllegalArgumentException("line/column is outside the editor document"); //$NON-NLS-1$
        }
        return new Position(document.getLineOffset(line - 1) + col - 1, line, col);
    }
}
