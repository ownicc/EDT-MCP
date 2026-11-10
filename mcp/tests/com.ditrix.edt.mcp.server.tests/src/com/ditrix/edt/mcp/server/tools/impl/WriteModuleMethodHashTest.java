package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import com.ditrix.edt.mcp.server.utils.ContentHash;
import com.google.gson.JsonParser;

public class WriteModuleMethodHashTest
{
    private static final String TARGET = "// documentation\n&AtServer\nProcedure A()\n    Old();\nEndProcedure";
    private static final String BASE = TARGET + "\n\nProcedure B()\n    Other();\nEndProcedure\n";

    private static Map<String, String> request()
    {
        var params = new HashMap<String, String>();
        params.put("projectName", "Project");
        params.put("modulePath", "CommonModules/Tools/Module.bsl");
        params.put("mode", "replaceMethod");
        params.put("methodName", "A");
        params.put("source", TARGET.replace("Old();", "New();"));
        params.put("expectedMethodHash", ContentHash.of(TARGET));
        return params;
    }

    @Test public void publicSchemaExposesTheExplicitMethodGuard()
    {
        var properties = JsonParser.parseString(new WriteModuleSourceTool().getInputSchema()).getAsJsonObject()
            .getAsJsonObject("properties");
        assertTrue(properties.has("expectedMethodHash"));
    }

    @Test public void changedNeighborIsPreservedInTheCurrentPreparedSnapshot()
    {
        String current = BASE.replace("Other();", "HumanEdit();");
        var result = WriteModuleSourceTool.prepareContent(WriteModuleSourceTool.WriteRequest.from(request()),
            current, true, false, "\n");
        assertNull(result.error());
        assertEquals(current.replace("Old();", "New();"), new String(result.outputBytes(), StandardCharsets.UTF_8));
    }

    @Test public void changedTargetBodyDocumentationAndDirectiveRefuseTheOldToken()
    {
        for (String current : new String[] {BASE.replace("Old();", "HumanEdit();"),
            BASE.replace("// documentation", "// human documentation"), BASE.replace("&AtServer", "&AtClient")})
        {
            var result = WriteModuleSourceTool.prepareContent(WriteModuleSourceTool.WriteRequest.from(request()),
                current, true, false, "\n");
            assertNotNull("Changed target must reject the token", result.error());
            assertTrue(result.error(), result.error().contains("expectedMethodHash"));
        }
    }

    @Test public void supplyingBothGuardsDoesNotRelaxTheModuleGuard()
    {
        var params = request();
        params.put("expectedHash", ContentHash.of(BASE));
        var result = WriteModuleSourceTool.prepareContent(WriteModuleSourceTool.WriteRequest.from(params),
            BASE.replace("Other();", "HumanEdit();"), true, false, "\n");
        assertNotNull(result.error());
        assertTrue(result.error(), result.error().contains("expectedHash"));
    }

    @Test public void methodHashCannotAuthorizeAnotherModeOrBlankToken()
    {
        for (String mode : new String[] {"append", "replace", "searchReplace", "insertBefore", "insertAfter"})
        {
            var params = request(); params.put("mode", mode); params.put("oldSource", "Old();");
            params.put("expectedHash", ContentHash.of(BASE));
            String result = new WriteModuleSourceTool().execute(params);
            assertTrue(result, result.contains("expectedMethodHash") && result.contains("replaceMethod"));
        }
        var params = request(); params.put("expectedMethodHash", "   ");
        String result = new WriteModuleSourceTool().execute(params);
        assertTrue(result, result.contains("expectedMethodHash"));
        params = request(); params.put("expectedHash", "");
        result = new WriteModuleSourceTool().execute(params);
        assertTrue(result, result.contains("expectedHash") && result.contains("blank"));
    }
}
