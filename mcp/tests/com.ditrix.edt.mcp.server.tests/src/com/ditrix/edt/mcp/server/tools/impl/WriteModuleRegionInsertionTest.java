package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.ContentHash;
import com.ditrix.edt.mcp.server.protocol.InputSchemaCompactor;
import com.google.gson.JsonParser;

public class WriteModuleRegionInsertionTest
{
    private static final String BASE = "#Region PublicAPI\n// keep this note\n\n#EndRegion\n\n"
        + "Procedure Existing()\nEndProcedure\n";
    private static final String SOURCE = "// new documentation\n&AtServer\nProcedure Added() Export\n"
        + "    Value = 42;\nEndProcedure";

    private static Map<String, String> request(String base)
    {
        var params = new HashMap<String, String>();
        params.put("projectName", "Project");
        params.put("modulePath", "CommonModules/Tools/Module.bsl");
        params.put("mode", "insertIntoRegion");
        params.put("regionName", "PublicAPI");
        params.put("source", SOURCE);
        params.put("expectedHash", ContentHash.of(base));
        return params;
    }

    @Test public void schemaExposesRegionModeAndItsConditionalTarget()
    {
        var properties = JsonParser.parseString(new WriteModuleSourceTool().getInputSchema())
            .getAsJsonObject().getAsJsonObject("properties");
        assertTrue(properties.getAsJsonObject("mode").getAsJsonArray("enum").toString()
            .contains("insertIntoRegion"));
        assertTrue(properties.has("regionName"));
        var wire = InputSchemaCompactor.compact("write_module_source",
            JsonParser.parseString(new WriteModuleSourceTool().getInputSchema())).getAsJsonObject();
        assertTrue(wire.getAsJsonObject("properties").getAsJsonObject("regionName")
            .get("description").getAsString().contains("Required only for insertIntoRegion"));
    }

    @Test public void insertsBeforeRegionEndPreservingCommentsAndNeighbors()
    {
        var result = prepare(request(BASE), BASE, false, "\n");
        assertNull(result.error());
        assertEquals(BASE.replace("\n#EndRegion", "\n" + SOURCE + "\n\n#EndRegion"),
            new String(result.outputBytes(), StandardCharsets.UTF_8));
    }

    @Test public void preservesBomAndCrLfBytes()
    {
        String base = BASE.replace("\n", "\r\n");
        var result = prepare(request(base), base, true, "\r\n");
        assertNull(result.error());
        String expected = "\uFEFF" + BASE.replace("\n#EndRegion", "\n" + SOURCE + "\n\n#EndRegion")
            .replace("\n", "\r\n");
        assertEquals(expected, new String(result.outputBytes(), StandardCharsets.UTF_8));
    }

    @Test public void requiresRegionNameAndWholeModuleHashBeforeProjectAccess()
    {
        for (String key : new String[] {"regionName", "expectedHash"})
        {
            var params = request(BASE); params.remove(key);
            String error = new WriteModuleSourceTool().execute(params);
            assertTrue(error, error.contains(key) && error.contains("required"));
            params.put(key, "   ");
            error = new WriteModuleSourceTool().execute(params);
            assertTrue(error, error.contains(key));
        }
    }

    @Test public void rejectsAmbiguousArgumentsAndRegionNameInOtherModes()
    {
        for (String key : new String[] {"methodName", "oldSource", "expectedSource", "overwrite", "edits"})
        {
            var params = request(BASE); params.put(key, key.equals("edits") ? "[]" : "false");
            String error = new WriteModuleSourceTool().execute(params);
            assertTrue(error, error.contains(key));
        }
        for (String mode : new String[] {"replace", "append", "replaceMethod", "insertAfter", "replaceMethods"})
        {
            var params = request(BASE); params.put("mode", mode);
            String error = new WriteModuleSourceTool().execute(params);
            assertTrue(error, error.contains("regionName"));
        }
        var params = request(BASE); params.put("expectedMethodHash", "token");
        String error = new WriteModuleSourceTool().execute(params);
        assertTrue(error, error.contains("expectedMethodHash") && error.contains("replaceMethod"));
    }

    @Test public void refusesModuleWideCaseInsensitiveDuplicateAndMultipleIncomingMethods()
    {
        var params = request(BASE); params.put("source", SOURCE.replace("Added", "eXISTING"));
        var result = prepare(params, BASE, false, "\n");
        assertNotNull(result.error());
        assertTrue(result.error(), result.error().contains("already exists"));
        params = request(BASE); params.put("source", SOURCE + "\nProcedure Another()\nEndProcedure");
        result = prepare(params, BASE, false, "\n");
        assertNotNull(result.error());
        assertTrue(result.error(), result.error().contains("exactly one"));
    }

    @Test public void staleWholeHashRefusesBeforeRegionInsertion()
    {
        var result = prepare(request(BASE), BASE.replace("note", "human note"), false, "\n");
        assertNotNull(result.error());
        assertTrue(result.error(), result.error().contains("expectedHash"));
        assertNull(result.outputBytes());
    }

    @Test public void previewStillRefusesNonemptyRegionsAndRepeatedInsertion()
    {
        String base = BASE.replace("// keep this note", SOURCE);
        var params = request(base); params.put("preview", "true");
        var result = prepare(params, base, false, "\n");
        assertNotNull(result.error());
        assertNull(result.outputBytes());
    }

    private static WriteModuleSourceTool.ContentPreparation prepare(Map<String, String> params,
        String base, boolean bom, String delimiter)
    {
        return WriteModuleSourceTool.prepareContent(WriteModuleSourceTool.WriteRequest.from(params),
            base, true, bom, delimiter);
    }
}
