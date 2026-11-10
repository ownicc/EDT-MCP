package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class BslMethodHashCommitTest
{
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void evenAnUnchangedMethodHashCannotOverwriteALaterNeighborEdit() throws Exception
    {
        String a = "Procedure A()\n    Old();\nEndProcedure";
        String before = a + "\n\nProcedure B()\n    Original();\nEndProcedure\n";
        var target = temporary.newFile("Module.bsl").toPath();
        Files.writeString(target, before);
        var proposal = new PreparedBslWrite("Project", "file:/project", 0, "Module.bsl", target.toUri().toString(),
            true, PreparedBslWrite.sha256(before.getBytes(StandardCharsets.UTF_8)), before,
            before.replace("Old();", "New();"), before.replace("Old();", "New();").getBytes(StandardCharsets.UTF_8),
            7, 7, "replaceMethod", false, null);
        String concurrent = before.replace("Original();", "HumanEdit();");
        Files.writeString(target, concurrent);
        assertEquals(BslModuleUtils.replacementMethodSnapshot(before.lines().toList(), "A").methodHash(),
            BslModuleUtils.replacementMethodSnapshot(concurrent.lines().toList(), "A").methodHash());
        IFile file = mock(IFile.class);
        when(file.exists()).thenReturn(true);
        when(file.getLocation()).thenReturn(new Path(target.toString()));
        when(file.getContents(true)).thenAnswer(call -> Files.newInputStream(target));
        var result = BslWriteReviewCommit.writeIfUnchanged(proposal, file, false, () -> {
            fail("Full module changed after preparation; mutation must not start"); return false;
        });
        assertEquals(BslWriteReviews.State.STALE, result.status());
        assertEquals(Boolean.FALSE, result.mutationCommitted());
        assertEquals(concurrent, Files.readString(target));
    }
}
