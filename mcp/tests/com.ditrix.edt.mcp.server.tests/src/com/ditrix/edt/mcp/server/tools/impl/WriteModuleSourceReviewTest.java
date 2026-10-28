package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.eclipse.core.resources.ResourcesPlugin;
import org.junit.Test;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.google.gson.JsonParser;

public class WriteModuleSourceReviewTest
{
    @Test public void headlessDefaultAndExtraArgumentsCannotBypassReview() throws Exception
    {
        var field = Activator.class.getDeclaredField("plugin");
        field.setAccessible(true);
        Object saved = field.get(null);
        var project = ResourcesPlugin.getWorkspace().getRoot().getProject("ReviewGuardsTest");
        try
        {
            field.set(null, null);
            project.create(null); project.open(null);
            project.getFolder("src").create(false, true, null);
            var file = project.getFile("src/Module.bsl");
            byte[] base = "old\r\n".getBytes(StandardCharsets.UTF_8);
            file.create(new ByteArrayInputStream(base), false, null);
            var tool = new WriteModuleSourceTool();
            var params = Map.of("projectName", project.getName(), "modulePath", "Module.bsl",
                "mode", "append", "source", "new", "skipReview", "true", "confirm", "true");
            String result = tool.execute(params);
            var json = JsonParser.parseString(result).getAsJsonObject();
            assertFalse(result, json.get("success").getAsBoolean());
            assertTrue(result, json.get("error").getAsString().contains("workbench"));
            try (var in = file.getContents()) { assertArrayEquals(base, in.readAllBytes()); }
            assertEquals(ResponseType.JSON, tool.getResponseType(params, result));
            assertEquals(ResponseType.JSON, tool.getResponseType(params, "{\"reviewId\":\"id\"}"));
            assertEquals(ResponseType.MARKDOWN, tool.getResponseType(params, "---\nstatus: success\n---\nWritten"));
        }
        finally { field.set(null, saved); if (project.exists()) project.delete(true, true, null); }
    }
}
