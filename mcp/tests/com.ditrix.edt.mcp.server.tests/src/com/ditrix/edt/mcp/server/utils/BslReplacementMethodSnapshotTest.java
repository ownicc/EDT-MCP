// SPDX-License-Identifier: AGPL-3.0-or-later
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/** The token owns exactly the whole-line fragment replaceMethod would replace. */
public class BslReplacementMethodSnapshotTest
{
    @Test
    public void includesDocumentationAndDirectivesWithoutTrailingNewline()
    {
        String fragment = "// Summary\n&AtServer\nProcedure A() Export\n    X = 1;\nEndProcedure";
        var snapshot = BslModuleUtils.replacementMethodSnapshot(
            ("#Region Methods\n" + fragment + "\n\nProcedure B()\nEndProcedure\n#EndRegion\n").lines().toList(), "a");
        assertNotNull(snapshot);
        assertEquals(fragment, snapshot.text());
        assertEquals(ContentHash.of(fragment), snapshot.methodHash());
        assertEquals(1, snapshot.span().startLine);
        assertEquals(3, snapshot.span().declarationLine);
        assertEquals(5, snapshot.span().endLine);
    }

    @Test
    public void acceptsRussianFunctionAndWrappedSignature()
    {
        String fragment = "// Описание\n&НаСервере\nФункция Расчет(\n    Параметр) Экспорт\n    Возврат Параметр;\nКонецФункции";
        var snapshot = BslModuleUtils.replacementMethodSnapshot(fragment.lines().toList(), "РАСЧЕТ");
        assertNotNull(snapshot);
        assertTrue(snapshot.span().isFunction);
        assertEquals(fragment, snapshot.text());
    }

    @Test
    public void unrelatedMethodAndFinalNewlineDoNotChangeToken()
    {
        String target = "Procedure A()\nEndProcedure";
        var before = BslModuleUtils.replacementMethodSnapshot(
            (target + "\n\nProcedure B()\n    X = 1;\nEndProcedure\n").lines().toList(), "A");
        var after = BslModuleUtils.replacementMethodSnapshot(
            (target + "\n\nProcedure B()\n    X = 2;\nEndProcedure").lines().toList(), "A");
        assertNotNull(before);
        assertNotNull(after);
        assertEquals(before.methodHash(), after.methodHash());
    }

    @Test
    public void documentationDirectiveAndBodyEditsChangeToken()
    {
        String target = "// Summary\n&AtServer\nProcedure A()\n    X = 1;\nEndProcedure";
        var before = BslModuleUtils.replacementMethodSnapshot(target.lines().toList(), "A");
        for (String changed : List.of(target.replace("Summary", "Changed"),
            target.replace("AtServer", "AtClient"), target.replace("X = 1", "X = 2")))
        {
            var after = BslModuleUtils.replacementMethodSnapshot(changed.lines().toList(), "A");
            assertNotNull(after);
            assertNotEquals(before.methodHash(), after.methodHash());
        }
    }

    @Test
    public void tokenIsAnImmutableSnapshotOfInputLines()
    {
        var lines = new ArrayList<>(List.of("Procedure A()", "EndProcedure"));
        var snapshot = BslModuleUtils.replacementMethodSnapshot(lines, "A");
        lines.set(0, "Procedure B()");
        assertEquals("Procedure A()\nEndProcedure", snapshot.text());
        assertEquals(ContentHash.of("Procedure A()\nEndProcedure"), snapshot.methodHash());
    }

    @Test
    public void missingAmbiguousIncompleteAndUnaddressableTargetsHaveNoSnapshot()
    {
        assertNull(BslModuleUtils.replacementMethodSnapshot(List.of(), "A"));
        assertNull(BslModuleUtils.replacementMethodSnapshot(List.of("Procedure A()", "EndProcedure"), "Missing"));
        for (String source : List.of(
            "#If Client Then\nProcedure A()\nEndProcedure\n#Else\nProcedure a()\nEndProcedure\n#EndIf",
            "Procedure A()\n    X = 1;",
            "Procedure A() Function Hidden()\nEndFunction\nEndProcedure",
            "Procedure A()\nEndProcedure\nProcedure\nHidden()\nEndProcedure",
            "&AtServer Procedure A()\nEndProcedure",
            "Procedure A()\nEndProcedure; X = 1;"))
        {
            assertNull(source, BslModuleUtils.replacementMethodSnapshot(source.lines().toList(), "A"));
        }
    }
}
