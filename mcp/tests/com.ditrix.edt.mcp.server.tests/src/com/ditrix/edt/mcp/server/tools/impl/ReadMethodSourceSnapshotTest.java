// SPDX-License-Identifier: AGPL-3.0-or-later
package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.resources.IFile;
import org.eclipse.xtext.nodemodel.impl.NodeModelBuilder;
import org.junit.Test;

import com._1c.g5.v8.dt.bsl.model.BslFactory;
import com.ditrix.edt.mcp.server.utils.BslModuleUtils;
import com.ditrix.edt.mcp.server.utils.ContentHash;

/** Read tokens and displayed boundaries must describe one saved-file snapshot. */
public class ReadMethodSourceSnapshotTest
{
    @Test
    public void bomCrlfReadProducesBothTokensAndTheExactDirectiveInclusiveFragment() throws Exception
    {
        String fragment = "// Описание\n&НаСервере\nФункция Расчет() Экспорт\n    Возврат 1;\nКонецФункции";
        String module = "#Область Методы\n" + fragment + "\n#КонецОбласти\n";
        IFile file = file("\uFEFF" + module.replace("\n", "\r\n"));
        String result = new ReadMethodSourceTool().readMethodFromFile(file, "Project", "Module.bsl", "расчет", null, null);
        assertTrue(result, result.contains("methodHash: " + ContentHash.of(fragment) + "\n"));
        assertTrue(result, result.contains("contentHash: " + ContentHash.of(module) + "\n"));
        assertTrue(result, result.contains("startLine: 2\nendLine: 6\ntotalLines: 7\n"));
        assertTrue(result, result.contains("```bsl\n" + fragment + "\n```\n"));
        assertFalse(result, result.contains("\uFEFF"));
    }

    @Test
    public void savedSourceAndHashesCannotComeFromDifferentReads() throws Exception
    {
        String first = "// Saved\n&AtServer\nProcedure A()\nEndProcedure\n";
        String second = first.replace("Saved", "Later");
        IFile file = mock(IFile.class);
        when(file.getCharset()).thenReturn("UTF-8");
        AtomicInteger reads = new AtomicInteger();
        when(file.getContents()).thenAnswer(invocation -> new ByteArrayInputStream(
            (reads.getAndIncrement() == 0 ? first : second).getBytes(StandardCharsets.UTF_8)));
        String result = new ReadMethodSourceTool().readMethodFromFile(file, "Project", "Module.bsl", "A", null, null);
        assertEquals("one stream is the source of both hashes and displayed text", 1, reads.get());
        assertTrue(result, result.contains("contentHash: " + ContentHash.of(first) + "\n"));
        assertTrue(result, result.contains("methodHash: " + ContentHash.of(first.substring(0, first.length() - 1)) + "\n"));
        assertTrue(result, result.contains("```bsl\n" + first + "```\n"));
        assertFalse(result, result.contains("Later"));
    }

    @Test
    public void savedSpanWinsOverModelWithoutSourceRanges() throws Exception
    {
        String fragment = "// Saved\n&AtServer\nProcedure A() Export\nEndProcedure";
        var nativeMethod = BslFactory.eINSTANCE.createProcedure();
        nativeMethod.setName("A");
        nativeMethod.setExport(true);
        String result = new ReadMethodSourceTool().readMethodFromFile(file(fragment + "\n"),
            "Project", "Module.bsl", "A", nativeMethod, null);
        assertTrue(result, result.contains("startLine: 1\nendLine: 4\n"));
        assertTrue(result, result.contains("methodHash: " + ContentHash.of(fragment) + "\n"));
        assertTrue(result, result.contains("```bsl\n" + fragment + "\n```\n"));
    }

    @Test
    public void nativeSourceRemainsReadableWhenSavedDeclarationIsOutsideScanner() throws Exception
    {
        for (String source : new String[] {
            "Procedure\nA()\nEndProcedure",
            "&AtServer Procedure A()\nEndProcedure" })
        {
            var nativeMethod = BslFactory.eINSTANCE.createProcedure();
            nativeMethod.setName("A");
            NodeModelBuilder builder = new NodeModelBuilder();
            var root = builder.newRootNode(source);
            builder.associateWithSemanticElement(root, nativeMethod);
            builder.newLeafNode(0, source.length(), null, false, null, root);
            assertEquals(source, BslModuleUtils.getSourceText(nativeMethod));

            String result = new ReadMethodSourceTool().readMethodFromFile(file(source),
                "Project", "Module.bsl", "A", nativeMethod, null);
            assertTrue(result, result.contains("```bsl\n" + source + "\n```\n"));
            assertFalse(result, result.contains("contentHash:"));
            assertFalse(result, result.contains("methodHash:"));
        }
    }

    @Test
    public void ambiguousIncompleteAndUnaddressableReadsDoNotMintMethodTokens() throws Exception
    {
        for (String source : new String[] {
            "",
            "Procedure A()\nEndProcedure\nProcedure a()\nEndProcedure",
            "Procedure A()\n    X = 1;",
            "Procedure A() Function Hidden()\nEndFunction\nEndProcedure",
            "Procedure A()\nEndProcedure\nProcedure\nHidden()\nEndProcedure" })
        {
            String result = new ReadMethodSourceTool().readMethodFromFile(file(source),
                "Project", "Module.bsl", "A", null, null);
            assertFalse(result, result.contains("methodHash:"));
        }
    }

    private static IFile file(String source) throws Exception
    {
        IFile file = mock(IFile.class);
        when(file.getCharset()).thenReturn("UTF-8");
        when(file.getContents()).thenAnswer(invocation -> new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)));
        return file;
    }
}
