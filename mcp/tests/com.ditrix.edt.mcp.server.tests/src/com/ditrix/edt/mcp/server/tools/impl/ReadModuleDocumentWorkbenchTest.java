package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.Assume;
import org.junit.Test;

/** Real buffer/editor selection without opening, saving or modifying the target. */
public class ReadModuleDocumentWorkbenchTest
{
    @Test public void readsDirtyDocumentAndPreservesDiskAndEditorState() throws Exception
    {
        Assume.assumeTrue(Boolean.getBoolean("edt.mcp.uiTests"));
        assertTrue("Run with -Pworkbench-tests under Xvfb", PlatformUI.isWorkbenchRunning());
        Display display = PlatformUI.getWorkbench().getDisplay();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var exercised = new java.util.concurrent.atomic.AtomicBoolean();
        display.syncExec(() -> {
            exercised.set(true);
            var project = ResourcesPlugin.getWorkspace().getRoot().getProject("ReadDocumentFixture");
            var manager = FileBuffers.getTextFileBufferManager();
            try (var editors = new com.ditrix.edt.mcp.server.utils.PlainTextEditorFixture())
            {
                project.create(null); project.open(null);
                var folder = project.getFolder("src"); folder.create(true, true, null);
                var file = folder.getFile("Module.bsl");
                String disk = "Procedure Run()\r\nEndProcedure\r\n";
                file.create(new ByteArrayInputStream(disk.getBytes(StandardCharsets.UTF_8)), true, null);
                // Plain text provider exercises ITextEditor/file buffers without EDT semantic services.
                org.eclipse.ui.ide.IDE.setDefaultEditor(file, "com.ditrix.edt.mcp.server.tests.plainTextEditor");
                assertNotNull("Plain text fixture editor must be registered", PlatformUI.getWorkbench().getEditorRegistry().findEditor("com.ditrix.edt.mcp.server.tests.plainTextEditor"));
                assertEquals("com.ditrix.edt.mcp.server.tests.plainTextEditor", org.eclipse.ui.ide.IDE.getDefaultEditor(file).getId());
                var reader = new ReadModuleSourceTool();
                var args = Map.of("projectName", project.getName(), "modulePath", "Module.bsl", "view", "document");
                String missing = reader.execute(args);
                assertTrue(missing, missing.contains("No connected document"));
                var page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
                assertEquals(0, page.getEditorReferences().length);
                assertTrue(new OpenInEditorTool().execute(Map.of("projectName", project.getName(), "modulePath", "Module.bsl")).contains("\"success\":true"));
                var editor = page.getActiveEditor();
                var text = editor instanceof ITextEditor t ? t : editor.getAdapter(ITextEditor.class);
                var document = text.getDocumentProvider().getDocument(text.getEditorInput());
                document.replace(0, 0, "// unsaved\r\n");
                String live = reader.execute(args);
                assertTrue(live, live.contains("// unsaved"));
                assertTrue(live, live.contains("dirty: true"));
                assertTrue(live, live.contains("view: document"));
                assertTrue(live, live.contains("documentHash:"));
                assertFalse(live, live.contains("contentHash:"));
                // Disk content/hash remain available when optional UI observations time out.
                var savedRead = new FutureTask<>(() -> reader.execute(Map.of("projectName", project.getName(), "modulePath", "Module.bsl")));
                var diskCaller = new Thread(savedRead, "disk-read-mcp");
                diskCaller.setDaemon(true);
                diskCaller.start();
                String saved = savedRead.get(10, TimeUnit.SECONDS);
                assertTrue(saved, saved.contains("contentHash:"));
                assertFalse(saved, saved.contains("// unsaved"));
                String range = reader.execute(Map.of("projectName", project.getName(), "modulePath", "Module.bsl", "view", "document", "startLine", "2", "endLine", "2"));
                assertTrue(range, range.contains("Procedure Run()"));
                assertFalse(range, range.contains("// unsaved"));
                var request = new FutureTask<>(() -> reader.execute(args));
                new Thread(request, "document-read-mcp").start();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                while (!request.isDone() && System.nanoTime() < deadline)
                    if (!display.readAndDispatch()) Thread.sleep(5);
                assertTrue(request.get(1, TimeUnit.SECONDS).contains("// unsaved"));
                assertSame(editor, page.getActiveEditor());
                assertTrue(editor.isDirty());
                assertEquals(disk, java.nio.file.Files.readString(file.getLocation().toFile().toPath()));
                page.closeAllEditors(false);
                manager.connect(file.getFullPath(), LocationKind.IFILE, null);
                try
                {
                    var buffer = manager.getTextFileBuffer(file.getFullPath(), LocationKind.IFILE);
                    buffer.getDocument().set("// buffer only\n");
                    String buffered = reader.execute(args);
                    assertTrue(buffered, buffered.contains("// buffer only"));
                    assertTrue(buffered, buffered.contains("dirty: true"));
                    assertEquals(0, page.getEditorReferences().length);
                    assertEquals(disk, java.nio.file.Files.readString(file.getLocation().toFile().toPath()));
                }
                finally { manager.disconnect(file.getFullPath(), LocationKind.IFILE, null); }
            }
            catch (Throwable e) { failure.set(e); }
            finally
            {
                PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage().closeAllEditors(false);
                try { if (project.exists()) project.delete(true, true, null); }
                catch (Exception e) { failure.compareAndSet(null, e); }
            }
        });
        assertTrue("Workbench fixture must execute", exercised.get());
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
}
