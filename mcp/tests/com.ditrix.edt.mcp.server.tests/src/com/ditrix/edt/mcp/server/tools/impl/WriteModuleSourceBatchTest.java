package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import com.ditrix.edt.mcp.server.protocol.GsonProvider;
import com.ditrix.edt.mcp.server.utils.BslWriteRequests;
import com.ditrix.edt.mcp.server.utils.ContentHash;
import com.google.gson.JsonParser;

/** Batch regressions: omitted/partial edits, stale base and receipts that ignore a changed entry. */
public class WriteModuleSourceBatchTest
{
    private static final String BASE = "// untouched\n\nProcedure First() Export\n    Old();\nEndProcedure\n\n"
        + "// docs\n&AtServer\nFunction Second() Export\n    Return 2;\nEndFunction\n\n"
        + "Процедура Третья()\n    Старое();\nКонецПроцедуры\n// tail\n";
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private static Map<String,String> params()
    {
        Map<String,String> p = new HashMap<>(Map.of("projectName", "MissingBatchProject", "modulePath", "a.bsl",
            "expectedHash", ContentHash.of(BASE)));
        p.put("edits", GsonProvider.toJson(List.of(
            Map.of("methodName", "Третья", "source", "Процедура Третья()\n    Новое();\nКонецПроцедуры"),
            Map.of("methodName", "First", "source", "Procedure First() Export\n    One();\n    Two();\nEndProcedure"),
            Map.of("methodName", "Second", "source", "// docs\n&AtServer\nFunction Second() Export\n    Return 42;\nEndFunction"))));
        return p;
    }
    private static WriteModuleSourceTool.ContentPreparation prepare(Map<String,String> p, String base,
        boolean bom, String delimiter)
    {
        try
        {
            return WriteModuleSourceTool.prepareContent(WriteModuleSourceTool.WriteRequest.from(p),
                base, true, bom, delimiter);
        }
        catch (NullPointerException e) { throw new AssertionError("Batch must prepare edits without top-level source", e); }
    }
    private static String error(Map<String,String> p)
    {
        String result = new WriteModuleSourceTool().execute(p);
        var json = JsonParser.parseString(result).getAsJsonObject();
        assertFalse(result, json.get("success").getAsBoolean());
        return result;
    }

    @Test public void threeReplacementsUseOriginalSpansAndPreserveBomCrLfAndUntouchedText()
    {
        var result = prepare(params(), BASE.replace("\n", "\r\n"), true, "\r\n");
        assertNull(result.error(), result.error());
        String expected = "// untouched\n\nProcedure First() Export\n    One();\n    Two();\nEndProcedure\n\n"
            + "// docs\n&AtServer\nFunction Second() Export\n    Return 42;\nEndFunction\n\n"
            + "Процедура Третья()\n    Новое();\nКонецПроцедуры\n// tail\n";
        assertEquals("\uFEFF" + expected.replace("\n", "\r\n"), new String(result.outputBytes(), StandardCharsets.UTF_8));
    }

    @Test public void oneBadMethodRefusesTheWholePreparedProposal()
    {
        for (String source : List.of("Function Second()\nEndFunction\nProcedure Extra()\nEndProcedure",
            "Procedure Second()\nEndProcedure", "Function Wrong()\nEndFunction"))
        {
            var p = params(); p.put("edits", GsonProvider.toJson(List.of(
                Map.of("methodName", "First", "source", "Procedure First()\n    Changed();\nEndProcedure"),
                Map.of("methodName", "Second", "source", source))));
            var result = prepare(p, BASE, false, "\n");
            assertNotNull("bad later edit must refuse", result.error());
            assertTrue(result.error(), result.error().contains("edits[1]"));
            assertNull(result.lines()); assertNull(result.outputBytes());
        }
    }

    @Test public void combinedSyntaxFailureProducesNoProposal()
    {
        var p = params(); p.put("edits", GsonProvider.toJson(List.of(Map.of("methodName", "First", "source",
            "Procedure First()\n    If True Then\n    Value = 1;\nEndProcedure"))));
        var result = prepare(p, BASE, false, "\n");
        assertNotNull(result.error()); assertNull(result.outputBytes());
    }

    @Test public void staleHashRefusesAllEdits()
    {
        var p = params(); p.put("expectedHash", "stale");
        var result = prepare(p, BASE, false, "\n");
        assertNotNull(result.error()); assertNull(result.outputBytes());
    }

    @Test public void batchReachesProjectResolutionWithoutTopLevelSource()
    {
        assertTrue(error(params()), error(params()).contains("MissingBatchProject"));
        var p = params(); p.put("mode", "replaceMethods");
        assertTrue(error(p), error(p).contains("MissingBatchProject"));
    }

    @Test public void strictArrayValidationNeverDropsAnEntry()
    {
        for (String edits : List.of("null", "{}", "[]", "[null]", "[3]", "[true]", "not-json",
            "[{\"methodName\":\"First\",\"source\":\"x\"},null]",
            "[{\"methodName\":true,\"source\":\"x\"}]",
            "[{\"methodName\":\"First\",\"source\":3}]",
            "[{\"methodName\":\" \u0020\",\"source\":\"x\"}]",
            "[{\"methodName\":\"First\"}]",
            "[{\"methodName\":\"First\",\"source\":\"x\",\"mode\":\"append\"}]"))
        {
            var p = params(); p.put("edits", edits);
            assertTrue(edits + ": " + error(p), error(p).contains("edits"));
        }
    }

    @Test public void duplicateTargetsAndConflictingSelectorsRefuseBeforeProjectResolution()
    {
        var p = params(); p.put("edits", GsonProvider.toJson(List.of(
            Map.of("methodName", "First", "source", "x"), Map.of("methodName", "FIRST", "source", "y"))));
        assertTrue(error(p), error(p).contains("duplicate"));
        for (String key : List.of("source", "methodName", "oldSource", "expectedSource", "overwrite"))
        {
            p = params(); p.put(key, key.equals("overwrite") ? "false" : "x");
            assertTrue(error(p), error(p).contains(key));
        }
        p = params(); p.put("mode", "append"); assertTrue(error(p), error(p).contains("mode"));
        p = params(); p.remove("expectedHash"); assertTrue(error(p), error(p).contains("expectedHash"));
        p = params(); p.remove("edits"); p.put("mode", "replaceMethods");
        assertTrue(error(p), error(p).contains("edits"));
    }

    @Test public void boundedBatchUsesCombinedSourceBudget()
    {
        var p = params(); var edits = new ArrayList<Map<String,String>>();
        for (int i = 0; i < 50; i++) edits.add(Map.of("methodName", "M" + i, "source", "x"));
        p.put("edits", GsonProvider.toJson(edits)); assertTrue(error(p), error(p).contains("MissingBatchProject"));
        edits.add(Map.of("methodName", "M50", "source", "x")); p.put("edits", GsonProvider.toJson(edits));
        assertTrue(error(p), error(p).contains("50"));
        p = params(); p.put("edits", GsonProvider.toJson(List.of(Map.of("methodName", "A", "source", "x".repeat(250001)),
            Map.of("methodName", "B", "source", "y".repeat(250000)))));
        assertTrue(error(p), error(p).contains("500000"));
    }

    @Test public void normalizationOnlyTouchesIncomingFragments()
    {
        var p = params(); p.put("edits", GsonProvider.toJson(List.of(Map.of("methodName", "First", "source",
            "Procedure First()\r\n    // a\u2014b\r\nEndProcedure"))));
        String base = BASE.replace("// tail", "// tail\u00a0"); p.put("expectedHash", ContentHash.of(base));
        var result = prepare(p, base, false, "\n");
        assertNull(result.error()); String text = new String(result.outputBytes(), StandardCharsets.UTF_8);
        assertTrue(text, text.contains("// a-b")); assertTrue(text, text.contains("// tail\u00a0"));
        assertNotNull(result.normalizedCharacters());
        p.put("normalizeInvalidCharacters", "false"); p.put("expectedHash", ContentHash.of(BASE)); result = prepare(p, BASE, false, "\n");
        assertNull(result.error()); assertTrue(new String(result.outputBytes(), StandardCharsets.UTF_8).contains("// a\u2014b"));
    }

    @Test public void batchReceiptIncludesEveryEntry() throws Exception
    {
        var store = new BslWriteRequests(temporary.newFolder().toPath()); var tool = new WriteModuleSourceTool(store);
        var p = params(); p.put("requestId", "batch-r");
        String first = tool.execute(p); assertTrue(first, first.contains("requestId"));
        p.put("mode", "replaceMethods"); assertEquals(first, tool.execute(p));
        for (int i = 0; i < 3; i++)
        {
            var changed = JsonParser.parseString(params().get("edits")).getAsJsonArray();
            var entry = changed.get(i).getAsJsonObject();
            entry.addProperty("source", entry.get("source").getAsString().replaceFirst("\\n", "\n// digest change\n"));
            p.put("edits", changed.toString());
            assertEquals("requestIdConflict", JsonParser.parseString(tool.execute(p)).getAsJsonObject().get("code").getAsString());
        }
        var original = JsonParser.parseString(params().get("edits")).getAsJsonArray();
        var reordered = new com.google.gson.JsonArray();
        reordered.add(original.get(2)); reordered.add(original.get(0)); reordered.add(original.get(1));
        p.put("edits", reordered.toString());
        assertEquals("requestIdConflict", JsonParser.parseString(tool.execute(p)).getAsJsonObject().get("code").getAsString());
        assertEquals(first, new GetWriteReviewStatusTool(store).execute(Map.of("requestId", "batch-r")));
    }
}
