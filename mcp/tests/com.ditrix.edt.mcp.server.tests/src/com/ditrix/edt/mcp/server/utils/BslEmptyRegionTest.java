// SPDX-License-Identifier: AGPL-3.0-or-later
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import java.util.List;

import org.junit.Test;

/** Safe whole-line locations for the explicit empty-region insertion mode. */
public class BslEmptyRegionTest
{
    @Test
    public void resolvesEnglishRegionBeforeItsPairedEnd()
    {
        var source = List.of("#Region Methods", "", "#EndRegion");
        var result = BslEmptyRegion.resolve(source, "methods");
        assertNull(result.error());
        assertEquals(2, result.insertionLine());
        assertEquals(List.of("#Region Methods", "", "#EndRegion"), source);
    }

    @Test
    public void resolvesRussianRegionAndNativeHashSpacing()
    {
        var result = BslEmptyRegion.resolve(List.of("\t# Область СлужебныеПроцедуры // heading",
            "    // Documentation stays here", "\t", "\t# КонецОбласти // ending"), "СЛУЖЕБНЫЕПРОЦЕДУРЫ");
        assertNull(result.error());
        assertEquals(3, result.insertionLine());
    }

    @Test
    public void permitsAnEmptyNestedTargetAndKeepsItsOuterRegion()
    {
        var result = BslEmptyRegion.resolve(List.of("#Region Outer", "#Region Target", "// comment",
            "#EndRegion", "#EndRegion"), "Target");
        assertNull(result.error());
        assertEquals(3, result.insertionLine());
    }

    @Test
    public void doesNotSelectAnotherRegionsTerminator()
    {
        var result = BslEmptyRegion.resolve(List.of("#Region Other", "#EndRegion", "#Region Target",
            "// preserved", "", "#EndRegion", "#Region Last", "#EndRegion"), "Target");
        assertNull(result.error());
        assertEquals(5, result.insertionLine());
    }

    @Test
    public void ignoresDirectiveTextInsideCommentsAndContinuedStrings()
    {
        var result = BslEmptyRegion.resolve(List.of("// #Region Target", "Procedure P()",
            "    Text = \"start", "// an ignored comment while the string is open",
            "    |#Region Target", "    |#EndRegion\";", "EndProcedure", "#Region Target",
            "// #EndRegion is documentation", "#EndRegion"), "Target");
        assertNull(result.error());
        assertEquals(9, result.insertionLine());
    }

    @Test
    public void duplicateNamesAreAmbiguousAcrossNestingAndBranches()
    {
        for (String source : List.of(
            "#Region Target\n#EndRegion\n#Region target\n#EndRegion",
            "#Region Target\n#Region target\n#EndRegion\n#EndRegion",
            "#If Client Then\n#Region Target\n#EndRegion\n#Else\n#Region target\n#EndRegion\n#EndIf"))
        {
            assertRefused(source, "Target", "ambiguous");
        }
    }

    @Test
    public void missingOrBlankTargetIsRefused()
    {
        assertRefused("#Region Other\n#EndRegion", "Target", "not found");
        assertRefused("#Region Other\n#EndRegion", "", "regionName");
        assertRefused("#Region Other\n#EndRegion", " \t", "regionName");
    }

    @Test
    public void unbalancedOrUnsupportedRegionDirectivesRefuse()
    {
        for (String source : List.of("#Region Target", "#EndRegion\n#Region Target\n#EndRegion",
            "#Region Target\n#EndRegion\n#Region Unclosed",
            "#Region\nTarget\n#EndRegion", "#Region Target extra\n#EndRegion",
            "#Region Target\n#EndRegion extra"))
        {
            var result = BslEmptyRegion.resolve(source.lines().toList(), "Target");
            assertNotNull(source, result.error());
            assertEquals(source, -1, result.insertionLine());
        }
    }

    @Test
    public void literalTailsDoNotDisappearIntoTheDirectiveMask()
    {
        assertRefused("#Region Target \"extra\"\n#EndRegion", "Target", "Unsupported region directive");
        assertRefused("#Region Target\n#EndRegion \"extra\"", "Target", "Unsupported region directive");
    }

    @Test
    public void acceptsNativeNonAsciiWhitespaceAndIdentifierRanges()
    {
        var result = BslEmptyRegion.resolve(List.of("\u00a0#\tRegion Әдістер\u00a0// heading",
            "\u00a0// comment", "\u00a0#\tEndRegion"), "ӘДІСТЕР");
        assertNull(result.error());
        assertEquals(2, result.insertionLine());
    }

    @Test
    public void packedPhysicalLinesCannotHideAnotherMatchingRegion()
    {
        var result = BslEmptyRegion.resolve(List.of("// comment\r#Region Target\r#EndRegion",
            "#Region Target", "#EndRegion"), "Target");
        assertNotNull(result.error());
        assertTrue(result.error(), result.error().contains("physical line boundary"));
        assertEquals(-1, result.insertionLine());
    }

    @Test
    public void nonemptyTargetsRefuseIncludingMaskedLiteralExpressions()
    {
        for (String content : List.of("Var Value;", "Procedure P()\nEndProcedure", "\"literal\";",
            "#If Client Then\n#EndIf", "#Region Nested\n#EndRegion", "&AtServer"))
        {
            assertRefused("#Region Target\n" + content + "\n#EndRegion", "Target", "not empty");
        }
    }

    @Test
    public void targetsInsideCompleteOrIncompleteMethodsRefuse()
    {
        for (String source : List.of(
            "Procedure P()\n#Region Target\n// empty\n#EndRegion\nEndProcedure",
            "Procedure P()\n#Region Target\n#EndRegion",
            "Function P()\n#Region Target\n#EndRegion\nEndFunction"))
        {
            assertRefused(source, "Target", "inside a method");
        }
    }

    @Test
    public void targetBeforeOrAfterCompleteMethodsIsModuleLevel()
    {
        var before = BslEmptyRegion.resolve(List.of("#Region Target", "#EndRegion", "Procedure P()",
            "EndProcedure"), "Target");
        assertNull(before.error());
        assertEquals(1, before.insertionLine());
        var after = BslEmptyRegion.resolve(List.of("Procedure P()", "EndProcedure", "#Region Target",
            "#EndRegion"), "Target");
        assertNull(after.error());
        assertEquals(3, after.insertionLine());
    }

    private static void assertRefused(String source, String name, String expectedText)
    {
        var result = BslEmptyRegion.resolve(source.lines().toList(), name);
        assertNotNull(source, result.error());
        assertTrue(result.error(), result.error().contains(expectedText));
        assertEquals(-1, result.insertionLine());
    }
}
