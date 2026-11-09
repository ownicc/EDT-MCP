package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.Path;
import org.eclipse.jface.text.Document;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.Test;

public class ModuleDocumentSnapshotTest
{
    @Test public void capturesConnectedBufferTextAndDirtyWithoutDiskToken()
    {
        IFile file = mock(IFile.class);
        when(file.getFullPath()).thenReturn(new Path("/p/src/a.bsl"));
        ITextFileBuffer buffer = mock(ITextFileBuffer.class);
        when(buffer.getLocation()).thenReturn(new Path("/p/src/a.bsl"));
        Document document = new Document("// unsaved\r\nФункция Тест()\r\nКонецФункции\r\n");
        when(buffer.getDocument()).thenReturn(document);
        when(buffer.isDirty()).thenReturn(true);
        var snapshot = ModuleDocumentSnapshot.find(file, new IWorkbenchWindow[0], new ITextFileBuffer[] {buffer});
        assertNotNull(snapshot);
        assertEquals(document.get(), snapshot.text());
        assertTrue(snapshot.dirty());
        assertTrue(snapshot.documentHash().startsWith("document:"));
        assertFalse(ContentHash.matches(snapshot.text(), snapshot.documentHash()));
        document.set("changed later");
        assertTrue(snapshot.text().contains("Функция Тест"));
    }

    @Test public void readsExistingEditorInAnotherWindowWithoutRestoringOtherEditors()
    {
        IFile file = mock(IFile.class);
        IWorkbenchWindow first = mock(IWorkbenchWindow.class), second = mock(IWorkbenchWindow.class);
        when(first.getPages()).thenReturn(new IWorkbenchPage[0]);
        var page = mock(IWorkbenchPage.class);
        when(second.getPages()).thenReturn(new IWorkbenchPage[] {page});
        var reference = mock(IEditorReference.class);
        var dormant = mock(IEditorReference.class);
        when(page.getEditorReferences()).thenReturn(new IEditorReference[] {dormant, reference});
        ITextEditor editor = mock(ITextEditor.class);
        when(reference.getEditor(false)).thenReturn(editor);
        var input = mock(IFileEditorInput.class);
        when(input.getFile()).thenReturn(file);
        when(editor.getEditorInput()).thenReturn(input);
        var provider = mock(IDocumentProvider.class);
        when(editor.getDocumentProvider()).thenReturn(provider);
        when(provider.getDocument(input)).thenReturn(new Document("Procedure Run()\nEndProcedure"));
        var snapshot = ModuleDocumentSnapshot.find(file, new IWorkbenchWindow[] {first, second}, new ITextFileBuffer[0]);
        assertNotNull(snapshot);
        assertEquals("Procedure Run()\nEndProcedure", snapshot.text());
        assertFalse(snapshot.dirty());
        verify(dormant, never()).getEditor(true);
    }

    @Test public void absentBufferIsNotAnEmptyDocument()
    {
        assertNull(ModuleDocumentSnapshot.find(mock(IFile.class), new IWorkbenchWindow[0], new ITextFileBuffer[0]));
    }
}
