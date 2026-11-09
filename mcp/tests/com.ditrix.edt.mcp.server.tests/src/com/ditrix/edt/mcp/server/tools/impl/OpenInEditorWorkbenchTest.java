package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.application.WorkbenchAdvisor;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.Assume;
import org.junit.Test;

/** Opt-in real workbench check: run under Xvfb with -Dedt.mcp.uiTests=true. */
public class OpenInEditorWorkbenchTest
{
    @Test
    public void opensReusesAndNavigatesTheDirtyBslEditor() throws Exception
    {
        Assume.assumeTrue("Requires a disposable graphical test workbench", Boolean.getBoolean("edt.mcp.uiTests"));
        Display display = new Display();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try
        {
            PlatformUI.createAndRunWorkbench(display, new WorkbenchAdvisor()
            {
                @Override
                public String getInitialWindowPerspectiveId()
                {
                    return "org.eclipse.ui.resourcePerspective";
                }

                @Override
                public void postStartup()
                {
                    display.asyncExec(() -> {
                        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject("OpenEditorFixture");
                        try
                        {
                            project.create(null);
                            project.open(null);
                            IFolder src = project.getFolder("src");
                            src.create(true, true, null);
                            IFile file = src.getFile("Module.bsl");
                            String source = "Procedure Run()\nEndProcedure\n";
                            file.create(new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)), true, null);
                            OpenInEditorTool tool = new OpenInEditorTool();
                            Map<String, String> args = Map.of("projectName", project.getName(), "modulePath", "Module.bsl");
                            String opened = tool.execute(args);
                            assertTrue(opened, opened.contains("\"success\":true"));
                            var page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
                            var editor = page.getActiveEditor();
                            ITextEditor text = editor instanceof ITextEditor t ? t : editor.getAdapter(ITextEditor.class);
                            assertNotNull(text);
                            IDocument document = text.getDocumentProvider().getDocument(text.getEditorInput());
                            document.replace(0, 0, "// unsaved\n");
                            assertTrue(editor.isDirty());
                            String result = tool.execute(Map.of("projectName", project.getName(), "modulePath", "Module.bsl", "methodName", "run"));
                            assertTrue(result, result.contains("\"success\":true"));
                            assertSame(editor, page.getActiveEditor());
                            assertEquals(document.getLineOffset(1), ((ITextSelection) text.getSelectionProvider().getSelection()).getOffset());
                            assertTrue(editor.isDirty());
                            assertEquals("// unsaved\n" + source, document.get());
                            assertEquals(source, java.nio.file.Files.readString(file.getLocation().toFile().toPath()));
                            tool.execute(args);
                            assertEquals(document.getLineOffset(1), ((ITextSelection) text.getSelectionProvider().getSelection()).getOffset());
                            assertEquals(1, page.getEditorReferences().length);
                            var request = new java.util.concurrent.FutureTask<>(() -> tool.execute(Map.of(
                                "projectName", project.getName(), "modulePath", "Module.bsl", "line", "1", "column", "3")));
                            Thread caller = new Thread(request, "open-editor-mcp-caller");
                            caller.setDaemon(true);
                            caller.start();
                            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
                            while (!request.isDone() && System.nanoTime() < deadline)
                            {
                                if (!display.readAndDispatch())
                                {
                                    Thread.sleep(5);
                                }
                            }
                            String response = request.get(1, java.util.concurrent.TimeUnit.SECONDS);
                            assertTrue(response, response.contains("\"success\":true"));
                            assertEquals(2, ((ITextSelection) text.getSelectionProvider().getSelection()).getOffset());
                            assertTrue(editor.isDirty());
                        }
                        catch (Throwable e)
                        {
                            failure.set(e);
                        }
                        finally
                        {
                            var page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
                            page.closeAllEditors(false);
                            try
                            {
                                if (project.exists())
                                {
                                    project.delete(true, true, null);
                                }
                            }
                            catch (Exception e)
                            {
                                failure.compareAndSet(null, e);
                            }
                            PlatformUI.getWorkbench().close();
                        }
                    });
                }
            });
        }
        finally
        {
            display.dispose();
        }
        if (failure.get() != null)
        {
            throw new AssertionError("Workbench editor integration failed", failure.get());
        }
    }
}
