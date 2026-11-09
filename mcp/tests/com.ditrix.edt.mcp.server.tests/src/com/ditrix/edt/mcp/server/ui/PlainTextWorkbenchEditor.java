package com.ditrix.edt.mcp.server.ui;

import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.editors.text.FileDocumentProvider;
import org.eclipse.ui.editors.text.TextEditor;

/** Real text editor with no EDT semantic document-provider lifecycle. */
public final class PlainTextWorkbenchEditor extends TextEditor
{
    @Override protected void setDocumentProvider(IEditorInput input)
    {
        setDocumentProvider(new FileDocumentProvider());
    }
}
