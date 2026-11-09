package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;
import java.util.HashMap;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import com.ditrix.edt.mcp.server.utils.BslWriteRequests;
import com.google.gson.JsonParser;

/** Receipt admission/replay precedes mutable project/content checks. */
public class WriteModuleSourceRequestTest
{
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @Test public void explicitDefaultsReplayAndChangedEffectiveArgumentsConflict() throws Exception
    {
        var directory = temporary.newFolder().toPath();
        var store = new BslWriteRequests(directory);
        var tool = new WriteModuleSourceTool(store);
        Map<String,String> params = new HashMap<>(Map.of("projectName", "MissingRequestProject", "modulePath", "a.bsl",
            "mode", "append", "source", "secret-receipt-source", "requestId", "r"));
        String first = tool.execute(params);
        var response = JsonParser.parseString(first).getAsJsonObject();
        assertEquals("r", response.get("requestId").getAsString());
        assertFalse(response.get("mutationCommitted").getAsBoolean());
        params.put("skipSyntaxCheck", "false");
        params.put("normalizeInvalidCharacters", "true");
        params.put("overwrite", "false");
        assertEquals(first, tool.execute(params));
        params.put("source", "different");
        assertEquals("requestIdConflict", JsonParser.parseString(tool.execute(params)).getAsJsonObject().get("code").getAsString());
        assertEquals(first, new GetWriteReviewStatusTool(store).execute(Map.of("requestId", "r")));
        try (var files = java.nio.file.Files.list(directory))
        { for (var path : files.toList()) assertFalse(java.nio.file.Files.readString(path).contains("secret-receipt-source")); }
    }

    @Test public void legacyErrorsRemainUnwrappedAndRequestIdValidationIsActionable() throws Exception
    {
        var tool = new WriteModuleSourceTool(new BslWriteRequests(temporary.newFolder().toPath()));
        Map<String,String> params = new HashMap<>(Map.of("projectName", "MissingRequestProject", "modulePath", "a.bsl", "mode", "append", "source", "x"));
        assertFalse(JsonParser.parseString(tool.execute(params)).getAsJsonObject().has("requestId"));
        for (String id : java.util.List.of(" ", "x".repeat(129)))
        {
            params.put("requestId", id);
            var result = JsonParser.parseString(tool.execute(params)).getAsJsonObject();
            assertEquals("invalidRequestId", result.get("code").getAsString());
        }
        assertTrue(tool.getInputSchema().contains("requestId"));
    }

    @Test public void statusAcceptsExactlyOneIdentityAndRecoversPersistedDirectOutcome() throws Exception
    {
        var directory = temporary.newFolder().toPath();
        var store = new BslWriteRequests(directory);
        String saved = store.execute("r", "a".repeat(64), () -> "File written successfully");
        var tool = new GetWriteReviewStatusTool(new BslWriteRequests(directory));
        assertEquals(saved, tool.execute(Map.of("requestId", "r")));
        for (var params : java.util.List.of(Map.<String,String>of(), Map.of("requestId", "r", "reviewId", "v"), Map.of("requestId", " "), Map.of("requestId", "missing")))
        {
            var result = JsonParser.parseString(tool.execute(params)).getAsJsonObject();
            assertFalse(result.toString(), result.get("success").getAsBoolean());
        }
    }
}
