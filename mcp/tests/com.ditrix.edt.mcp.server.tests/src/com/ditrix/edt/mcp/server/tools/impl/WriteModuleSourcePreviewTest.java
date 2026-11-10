package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import com.ditrix.edt.mcp.server.utils.BslWriteRequests;
import com.ditrix.edt.mcp.server.utils.ContentHash;
import com.ditrix.edt.mcp.server.utils.PreparedBslWrite;
import com.google.gson.JsonParser;

public class WriteModuleSourcePreviewTest
{
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @Test public void previewIsAnExplicitBooleanOptIn()
    {
        var schema = JsonParser.parseString(new WriteModuleSourceTool().getInputSchema()).getAsJsonObject();
        var properties = schema.getAsJsonObject("properties");
        assertTrue("A read-only preview must be discoverable", properties.has("preview"));
        assertEquals("boolean", properties.getAsJsonObject("preview").get("type").getAsString());
    }

    @Test public void previewNeverAdmitsOrReplaysAWriteReceipt() throws Exception
    {
        var directory = temporary.newFolder();
        var receipts = new BslWriteRequests(directory.toPath());
        String result = new WriteModuleSourceTool(receipts).execute(Map.of(
            "projectName", "Project", "modulePath", "CommonModules/Tools/Module.bsl",
            "mode", "append", "source", "// preview", "preview", "true", "requestId", "operation"));
        var json = JsonParser.parseString(result).getAsJsonObject();
        assertFalse(json.get("success").getAsBoolean());
        assertTrue(result, result.contains("requestId") && result.contains("preview"));
        assertEquals("Preview must not reserve an operation ID", 0, directory.list().length);
    }

    @Test public void previewReportsThePreparedChangeAndReadCompatibleHashes()
    {
        String before = "Procedure Anchor()\n    Old();\nEndProcedure\n";
        String after = before.replace("Old();", "New();");
        var result = JsonParser.parseString(WriteModuleSourceTool.previewResult(proposal(before, after, true, false)))
            .getAsJsonObject();
        assertTrue(result.get("success").getAsBoolean());
        assertTrue(result.get("preview").getAsBoolean());
        assertTrue(result.get("wouldChange").getAsBoolean());
        assertFalse(result.get("applied").getAsBoolean());
        assertFalse(result.get("mutationCommitted").getAsBoolean());
        assertTrue(result.get("syntaxChecked").getAsBoolean());
        assertEquals(ContentHash.of(before), result.get("contentHash").getAsString());
        assertEquals(ContentHash.of(after), result.get("proposedHash").getAsString());
        String diff = result.get("diff").getAsString();
        assertTrue(diff, diff.contains("-    Old();") && diff.contains("+    New();"));
        assertFalse(result.has("reviewId"));
        assertFalse(result.has("requestId"));
    }

    @Test public void noOpAndNewFileHaveDifferentPresenceSemantics()
    {
        String text = "// no changes\n";
        var unchanged = JsonParser.parseString(WriteModuleSourceTool.previewResult(proposal(text, text, true, true)))
            .getAsJsonObject();
        assertFalse(unchanged.get("wouldChange").getAsBoolean());
        assertFalse(unchanged.get("syntaxChecked").getAsBoolean());
        var created = JsonParser.parseString(WriteModuleSourceTool.previewResult(proposal("", "\n", false, true)))
            .getAsJsonObject();
        assertTrue(created.get("wouldChange").getAsBoolean());
    }

    @Test public void previewBoundsTheDiffAndMakesAnInputLimitExplicit()
    {
        String before = "x".repeat(500_001);
        var result = JsonParser.parseString(WriteModuleSourceTool.previewResult(proposal(before, before + "y", true, true)))
            .getAsJsonObject();
        assertTrue(result.get("wouldChange").getAsBoolean());
        assertTrue(result.get("diff").getAsString().contains("input limit"));
        assertTrue("Do not dump an oversized source", result.toString().length() < 2000);
    }

    private static PreparedBslWrite proposal(String before, String after, boolean exists, boolean skipSyntax)
    {
        return new PreparedBslWrite("Project", "file:/project", 0, "CommonModules/Tools/Module.bsl",
            "file:/project/src/CommonModules/Tools/Module.bsl", exists,
            PreparedBslWrite.sha256(before.getBytes(StandardCharsets.UTF_8)), before, after,
            after.getBytes(StandardCharsets.UTF_8), before.lines().toList().size(), after.lines().toList().size(),
            "searchReplace", skipSyntax, null);
    }
}
