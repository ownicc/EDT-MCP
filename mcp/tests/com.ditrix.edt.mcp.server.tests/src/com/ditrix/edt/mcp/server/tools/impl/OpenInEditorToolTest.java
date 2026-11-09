package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;

import java.util.Map;

import org.eclipse.jface.text.Document;
import org.junit.Test;

import com.ditrix.edt.mcp.server.preferences.ToolPreset;
import com.ditrix.edt.mcp.server.tools.BuiltInToolRegistrar;
import com.ditrix.edt.mcp.server.tools.McpToolRegistry;

/** Navigation uses the editor document, never a saved-file line number. */
public class OpenInEditorToolTest
{
    @Test
    public void registeredToolRejectsConflictingNavigationBeforeOpeningAnything()
    {
        McpToolRegistry registry = McpToolRegistry.getInstance();
        try
        {
            BuiltInToolRegistrar.registerAll(registry);
            assertNotNull(registry.getTool("open_in_editor"));
            String result = registry.getTool("open_in_editor").execute(Map.of(
                "projectName", "P", "modulePath", "CommonModules/M/Module.bsl",
                "methodName", "Run", "line", "2"));
            assertTrue(result, result.contains("\"success\":false"));
            assertTrue(result, result.contains("methodName"));
            assertTrue(ToolPreset.CODE_REVIEW.getDisabledTools().contains("open_in_editor"));
            assertTrue(ToolPreset.ANALYSIS_ONLY.getDisabledTools().contains("open_in_editor"));
        }
        finally
        {
            registry.clear();
        }
    }

    @Test
    public void noNavigationRetainsTheExistingEditorSelection() throws Exception
    {
        assertNull(OpenInEditorTool.locate(new Document("text"), null, null, null));
    }

    @Test
    public void coordinatesRespectCrLfEmptyLinesAndTheEndOfTheSelectedLine() throws Exception
    {
        Document doc = new Document("abc\r\n\r\nxyz");
        assertEquals(5, OpenInEditorTool.locate(doc, null, 2, 1).offset());
        assertEquals(10, OpenInEditorTool.locate(doc, null, 3, 4).offset());
        assertThrows(IllegalArgumentException.class, () -> OpenInEditorTool.locate(doc, null, 1, 5));
        assertThrows(IllegalArgumentException.class, () -> OpenInEditorTool.locate(doc, null, 4, 1));
    }

    @Test
    public void methodNavigationUsesCurrentUnsavedDocumentAndIgnoresComments() throws Exception
    {
        Document doc = new Document("Procedure Run()\nEndProcedure");
        doc.set("// unsaved line\n// Procedure Run()\n&НаСервере\nПроцедура Run()\nКонецПроцедуры");
        String before = doc.get();
        OpenInEditorTool.Position pos = OpenInEditorTool.locate(doc, "rUn", null, null);
        assertEquals(4, pos.line());
        assertEquals(doc.getLineOffset(3), pos.offset());
        assertEquals(before, doc.get());
    }

    @Test
    public void absentOrAmbiguousMethodIsRefused() throws Exception
    {
        Document doc = new Document("Procedure Run()\nEndProcedure\nProcedure RUN()\nEndProcedure");
        assertThrows(IllegalArgumentException.class, () -> OpenInEditorTool.locate(doc, "Run", null, null));
        assertThrows(IllegalArgumentException.class, () -> OpenInEditorTool.locate(doc, "Missing", null, null));
    }

    @Test
    public void invalidOrConflictingCoordinatesAreRefusedBeforeUiWork()
    {
        OpenInEditorTool tool = new OpenInEditorTool();
        for (Map<String, String> bad : java.util.List.of(
            Map.of("line", "0"), Map.of("line", "-1"), Map.of("line", "1.5"),
            Map.of("line", "1", "column", "0"), Map.of("column", "2"),
            Map.of("methodName", "Run", "column", "1")))
        {
            java.util.HashMap<String, String> args = new java.util.HashMap<>(bad);
            args.put("projectName", "P");
            args.put("modulePath", "CommonModules/M/Module.bsl");
            String result = tool.execute(args);
            assertTrue(result, result.contains("\"success\":false"));
        }
        assertTrue(tool.execute(Map.of()).contains("projectName"));
        assertTrue(tool.execute(Map.of("projectName", "P")).contains("modulePath"));
        assertTrue(tool.execute(Map.of("projectName", "P", "modulePath", "X.mdo")).contains(".bsl"));
    }

    @Test
    public void splitDeclarationCannotHideADuplicateFromMethodLookup() throws Exception
    {
        Document doc = new Document("#If Server Then\nProcedure Run()\nEndProcedure\n#Else\nProcedure Run\n()\nEndProcedure\n#EndIf");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> OpenInEditorTool.locate(doc, "Run", null, null));
        assertTrue(error.getMessage(), error.getMessage().contains("line"));
    }
}
