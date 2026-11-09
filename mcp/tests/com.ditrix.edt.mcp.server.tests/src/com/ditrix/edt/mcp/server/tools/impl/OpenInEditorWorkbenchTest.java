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
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.Assume;
import org.junit.Test;

/** Opt-in real workbench check with a plain text provider: run -Pworkbench-tests under Xvfb. */
public class OpenInEditorWorkbenchTest
{
    @Test
    public void opensReusesAndNavigatesTheDirtyBslEditor() throws Exception
    {
        Assume.assumeTrue("Requires a disposable graphical test workbench", Boolean.getBoolean("edt.mcp.uiTests"));
        assertTrue("Run with -Pworkbench-tests under Xvfb", PlatformUI.isWorkbenchRunning());
        Display display = PlatformUI.getWorkbench().getDisplay();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var exercised = new java.util.concurrent.atomic.AtomicBoolean();
        display.syncExec(() -> {
            exercised.set(true);
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject("OpenEditorFixture");
            try (var editors = new com.ditrix.edt.mcp.server.utils.PlainTextEditorFixture())
            {
                project.create(null);
                project.open(null);
                IFolder src = project.getFolder("src");
                src.create(true, true, null);
                IFile file = createModule(project, "CommonModules/Вычисления/Module.bsl");
                String source = "Procedure Run()\nEndProcedure\n";
                file.create(new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)), true, null);
                // Plain text provider exercises ITextEditor/file buffers without EDT semantic services.
                org.eclipse.ui.ide.IDE.setDefaultEditor(file, "com.ditrix.edt.mcp.server.tests.plainTextEditor");
                assertNotNull("Plain text fixture editor must be registered", PlatformUI.getWorkbench().getEditorRegistry().findEditor("com.ditrix.edt.mcp.server.tests.plainTextEditor"));
                assertEquals("com.ditrix.edt.mcp.server.tests.plainTextEditor", org.eclipse.ui.ide.IDE.getDefaultEditor(file).getId());
                OpenInEditorTool tool = new OpenInEditorTool();
                Map<String, String> args = Map.of("projectName", project.getName(), "modulePath", "CommonModules/Вычисления/Module.bsl");
                String opened = tool.execute(args);
                assertTrue(opened, opened.contains("\"success\":true"));
                var page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
                var editor = page.getActiveEditor();
                ITextEditor text = editor instanceof ITextEditor t ? t : editor.getAdapter(ITextEditor.class);
                assertNotNull(text);
                IDocument document = text.getDocumentProvider().getDocument(text.getEditorInput());
                document.replace(0, 0, "// unsaved\n");
                assertTrue(editor.isDirty());
                String result = tool.execute(Map.of("projectName", project.getName(), "objectName", "ОбщийМодуль.Вычисления", "methodName", "run"));
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
                    "projectName", project.getName(), "objectName", "CommonModule.Вычисления", "line", "1", "column", "3")));
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
                for (Map<String, String> target : java.util.List.of(
                    Map.of("modulePath", "ExternalConnectionModule.bsl"),
                    Map.of("objectName", "CommonForm.Options"),
                    Map.of("objectName", "ОбщаяКоманда.Печать"),
                    Map.of("objectName", "Document.Order", "moduleType", "FormModule", "formName", "ItemForm"),
                    Map.of("objectName", "Документ.Заказ", "moduleType", "CommandModule", "commandName", "Печать")))
                {
                    String expected = target.containsKey("modulePath") ? target.get("modulePath")
                        : switch (target.get("objectName"))
                        {
                            case "CommonForm.Options" -> "CommonForms/Options/Module.bsl";
                            case "ОбщаяКоманда.Печать" -> "CommonCommands/Печать/CommandModule.bsl";
                            case "Document.Order" -> "Documents/Order/Forms/ItemForm/Module.bsl";
                            default -> "Documents/Заказ/Commands/Печать/CommandModule.bsl";
                        };
                    IFile other = createModule(project, expected);
                    other.create(new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)), true, null);
                    // Plain text provider exercises ITextEditor/file buffers without EDT semantic services.
                    org.eclipse.ui.ide.IDE.setDefaultEditor(other, "com.ditrix.edt.mcp.server.tests.plainTextEditor");
                    var addressed = new java.util.HashMap<>(target);
                    addressed.put("projectName", project.getName());
                    String responseByObject = tool.execute(addressed);
                    var json = com.google.gson.JsonParser.parseString(responseByObject).getAsJsonObject();
                    assertTrue(responseByObject, json.get("success").getAsBoolean());
                    assertEquals(expected, json.get("modulePath").getAsString());
                    assertEquals(source, java.nio.file.Files.readString(other.getLocation().toFile().toPath()));
                }
                assertTrue(editor.isDirty());
                assertEquals("// unsaved\n" + source, document.get());
                assertEquals(source, java.nio.file.Files.readString(file.getLocation().toFile().toPath()));
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
            }
        });
        assertTrue("Workbench fixture must execute", exercised.get());
        if (failure.get() != null)
        {
            throw new AssertionError("Workbench editor integration failed", failure.get());
        }
    }

    private static IFile createModule(IProject project, String modulePath) throws Exception
    {
        IFile file = project.getFile("src/" + modulePath);
        var parent = file.getParent();
        var missing = new java.util.ArrayList<IFolder>();
        while (parent instanceof IFolder folder && !folder.exists())
        {
            missing.add(folder);
            parent = folder.getParent();
        }
        java.util.Collections.reverse(missing);
        for (IFolder folder : missing)
        {
            folder.create(true, true, null);
        }
        return file;
    }
}
