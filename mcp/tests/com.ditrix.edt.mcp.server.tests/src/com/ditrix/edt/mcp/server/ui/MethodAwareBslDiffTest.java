package com.ditrix.edt.mcp.server.ui;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import java.time.Duration;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.junit.Test;

public class MethodAwareBslDiffTest
{
    @Test public void exportsAllChangedMethodsAndOmitsUnchangedNeighbours() throws Exception
    {
        String moved = method("Перенос", "Возврат 1;");
        String editedMove = method("ПереносСПравкой", "Возврат 2;");
        String stable = method("Стабильный", "Возврат 3;");
        String changed = method("Изменённый", "Возврат 4;");
        String removed = method("Удалённый", "Возврат 5;");
        String added = method("Добавленный", "Возврат 6;");
        String a = "#Область Один\n" + moved + editedMove + stable + changed + removed
            + "#КонецОбласти\n#Область Два\n#КонецОбласти\n";
        String b = "#Область Один\n" + stable + changed.replace("4", "40")
            + "#КонецОбласти\n#Область Два\n" + moved + editedMove.replace("2", "20") + added + "#КонецОбласти\n";
        var result = compare(a, b);
        assertNull(result.fallback());
        var kinds = new java.util.HashMap<String, MethodAwareBslDiff.MethodKind>();
        for (var method : result.methods())
        {
            kinds.put(method.key().substring(method.key().indexOf(':') + 1), method.kind());
            assertEquals(method.kind() == MethodAwareBslDiff.MethodKind.ADDED, method.left().isEmpty());
            assertEquals(method.kind() == MethodAwareBslDiff.MethodKind.REMOVED, method.right().isEmpty());
            if (!method.left().isEmpty()) assertTrue(BslMethodCompareInput.extract(a, method.left()).text().endsWith("КонецФункции"));
            if (!method.right().isEmpty()) assertTrue(BslMethodCompareInput.extract(b, method.right()).text().endsWith("КонецФункции"));
        }
        assertEquals(java.util.Map.of("перенос", MethodAwareBslDiff.MethodKind.MOVED,
            "переноссправкой", MethodAwareBslDiff.MethodKind.MOVED_CHANGED,
            "изменённый", MethodAwareBslDiff.MethodKind.CHANGED,
            "удалённый", MethodAwareBslDiff.MethodKind.REMOVED,
            "добавленный", MethodAwareBslDiff.MethodKind.ADDED), kinds);
    }

    @Test public void formattingOnlyMethodChangeIsSelectable() throws Exception
    {
        String a = method("Форматирование", "Возврат 1;");
        var result = compare(a, a.replace("Возврат", "    Возврат"));
        assertNull(result.fallback());
        assertEquals(1, result.methods().size());
        assertEquals(MethodAwareBslDiff.MethodKind.CHANGED, result.methods().get(0).kind());
        assertTrue(result.diff().left().isEmpty());
        assertTrue(result.diff().right().isEmpty());
    }

    @Test public void ambiguousAndMalformedMethodsDoNotExposeGuessedPairs() throws Exception
    {
        String a = method("Дубль", "Возврат 1;");
        assertTrue(compare(a + a, a + a.replace("1", "2")).methods().isEmpty());
        assertTrue(compare(a, "Функция Дубль(\nВозврат 2;").methods().isEmpty());
    }

    @Test public void tokenDiffIsolatesOneRepeatedCallArgument()
    {
        var engine = new MethodAwareBslDiff(null, new NullProgressMonitor());
        var a = new ArrayList<MethodAwareBslDiff.Token>();
        var b = new ArrayList<MethodAwareBslDiff.Token>();
        for (int i = 0; i < 300; i++)
        {
            a.add(new MethodAwareBslDiff.Token("number", "1", i, 1));
            b.add(new MethodAwareBslDiff.Token("number", i == 150 ? "2" : "1", i, 1));
        }
        var left = new ArrayList<MethodAwareBslDiff.Token>();
        var right = new ArrayList<MethodAwareBslDiff.Token>();
        engine.diff(a, b, left, right);
        assertEquals(List.of(new MethodAwareBslDiff.Token("number", "1", 150, 1)), left);
        assertEquals(List.of(new MethodAwareBslDiff.Token("number", "2", 150, 1)), right);
    }

    @Test public void spansUseUtf16AndSplitEveryPhysicalLineDelimiter()
    {
        String source = "\ufeffA😀\r\n\tB\rC\n";
        var spans = MethodAwareBslDiff.spans(source,
            List.of(new MethodAwareBslDiff.Token("text", "😀\r\n\tB\rC", 2, 8)));
        assertEquals(List.of(new StructuralBslDiff.Span(0, 2, 2),
            new StructuralBslDiff.Span(1, 0, 2), new StructuralBslDiff.Span(2, 0, 1)), spans);
    }

    @Test public void newlineOnlyChangeStillMarksItsLine()
    {
        assertEquals(List.of(new StructuralBslDiff.Span(0, 1, 0)), MethodAwareBslDiff.spans("a\n",
            List.of(new MethodAwareBslDiff.Token("line", "\n", 1, 1))));
    }

    @Test public void alignmentIncludesFinalEmptyLineAndNeverDropsSource()
    {
        var engine = new MethodAwareBslDiff(null, new NullProgressMonitor());
        assertEquals(List.of(new StructuralBslDiff.Row(0, 0), new StructuralBslDiff.Row(1, 1),
            new StructuralBslDiff.Row(null, 2)), engine.align("a\r\nb", "a\r\nc\r\n"));
        assertEquals(List.of(new StructuralBslDiff.Row(null, 0), new StructuralBslDiff.Row(null, 1)), engine.align("", "\n"));
        assertEquals(List.of(), engine.align("", ""));
    }

    @Test public void refusesOversizedInputBeforeEnteringParser()
    {
        var engine = new MethodAwareBslDiff(null, new NullProgressMonitor());
        assertThrows(IllegalArgumentException.class, () -> engine.compare("x".repeat(524289), ""));
        assertThrows(IllegalArgumentException.class, () -> engine.compare("\n".repeat(20000), ""));
    }

    @Test public void conditionalDirectiveAfterCrRegionRetainsMethodProjection() throws Exception
    {
        String a = "#Region A\r#If Client Then\r" + method("Первый", "Возврат 1;").replace("\n", "\r")
            + "#EndIf\r#EndRegion";
        assertNull(compare(a, a).fallback());
    }

    @Test public void observesCancellationBeforeEnteringParser()
    {
        var monitor = new NullProgressMonitor();
        monitor.setCanceled(true);
        assertThrows(OperationCanceledException.class, () -> new MethodAwareBslDiff(null, monitor).compare("a", "b"));
    }

    @Test public void configuredBudgetExpiresBeforeEnteringParser()
    {
        var engine = new MethodAwareBslDiff(null, new NullProgressMonitor(), Duration.ZERO);
        var error = assertThrows(IllegalStateException.class, () -> engine.compare("a", "b"));
        assertEquals(org.eclipse.osgi.util.NLS.bind(Messages.BslDiff_Timeout, 0L), error.getMessage());
    }

    @Test public void cancellationTakesPrecedenceOverExpiredBudget()
    {
        var monitor = new NullProgressMonitor();
        monitor.setCanceled(true);
        var engine = new MethodAwareBslDiff(null, monitor, Duration.ZERO);
        assertThrows(OperationCanceledException.class, () -> engine.compare("a", "b"));
    }
    private static String method(String name, String body)
    {
        return "Функция " + name + "() Экспорт\n" + body + "\nКонецФункции\n\n";
    }

    private static MethodAwareBslDiff.Comparison compare(String a, String b) throws Exception
    {
        return MethodAwareBslDiff.compute(a, b, new NullProgressMonitor());
    }

    @Test public void conditionalInsideMethodDoesNotDisableMoves() throws Exception
    {
        String a = method("Переносимый", "#Если Клиент Тогда\nВозврат 1;\n#Иначе\nВозврат 2;\n#КонецЕсли");
        String b = method("Стабильный", "Возврат 3;");
        var result = compare(a + b, b + a);
        assertNull(result.fallback());
        assertEquals(1, result.moves().size());
        assertTrue(result.diff().left().isEmpty());
        assertTrue(result.diff().right().isEmpty());
    }

    @Test public void transferBetweenRegionsIsPairedWithoutTextChanges() throws Exception
    {
        String a = method("Переносимый", "Возврат 1;");
        var result = compare("#Область Один\n" + a + "#КонецОбласти\n#Область Два\n#КонецОбласти\n",
            "#Область Один\n#КонецОбласти\n#Область Два\n" + a + "#КонецОбласти\n");
        assertNull(result.fallback());
        assertEquals(1, result.moves().size());
        assertTrue(result.diff().left().isEmpty());
        assertTrue(result.diff().right().isEmpty());
    }

    @Test public void transferBetweenConditionalBranchesIsPaired() throws Exception
    {
        String a = method("Переносимый", "Возврат 1;");
        var result = compare("#Если Клиент Тогда\n" + a + "#Иначе\n#КонецЕсли\n",
            "#Если Клиент Тогда\n#Иначе\n" + a + "#КонецЕсли\n");
        assertNull(result.fallback());
        assertEquals(1, result.moves().size());
    }

    @Test public void newFunctionDoesNotColourStartOfUnchangedNextFunction() throws Exception
    {
        String a = method("Первый", "Возврат 1;");
        String inserted = method("Количество", "Возврат 2;");
        var result = compare(a, inserted + a);
        assertTrue(result.diff().left().isEmpty());
        int nextLine = inserted.split("\n", -1).length - 1;
        assertFalse(result.diff().right().stream().anyMatch(span -> span.line() >= nextLine));
    }

    @Test public void insertedConditionDoesNotColourUnchangedConditionPrefix() throws Exception
    {
        String existing = "Если Флаг Тогда\nВозврат 1;\nКонецЕсли;\nВозврат 0;";
        String inserted = "Если Другое Тогда\nВозврат 2;\nКонецЕсли;\n";
        var result = compare(method("Проверка", existing), method("Проверка", inserted + existing));
        assertTrue(result.diff().left().isEmpty());
        assertFalse(result.diff().right().stream().anyMatch(span -> span.line() >= 4));
    }

    @Test public void multilineStringEditDoesNotColourUnchangedOpeningLine() throws Exception
    {
        var result = compare(method("Текст", "Возврат \"Начало\n|Было\n|Конец\";"),
            method("Текст", "Возврат \"Начало\n|Стало\n|Конец\";"));
        assertFalse(result.diff().left().isEmpty());
        assertTrue(result.diff().left().stream().allMatch(span -> span.line() == 2));
        assertTrue(result.diff().right().stream().allMatch(span -> span.line() == 2));
    }

    @Test public void addedLinesAndRegionsDoNotMoveEveryFollowingMethod() throws Exception
    {
        String a = method("Первый", "Возврат 1;") + method("Последний", "Возврат 2;");
        var result = compare(a, "#Область Новая\n#КонецОбласти\n" + a);
        assertTrue(result.moves().isEmpty());
    }

    @Test public void stringReplacementDoesNotColourItsUnchangedPrefix() throws Exception
    {
        String a = method("Текст", "Возврат \"Общий префикс: старое\";");
        String b = method("Текст", "Возврат \"Общий префикс: новое\";");
        var result = compare(a, b);
        int prefix = "Возврат \"Общий префикс: ".length();
        assertTrue(result.diff().left().stream().allMatch(span -> span.start() >= prefix));
        assertTrue(result.diff().right().stream().allMatch(span -> span.start() >= prefix));
    }

    @Test public void duplicateConditionalMethodsDoNotGetArbitraryMovePairs() throws Exception
    {
        String a = "#Если Клиент Тогда\n" + method("Повтор", "Возврат 1;")
            + "#Иначе\n" + method("Повтор", "Возврат 2;") + "#КонецЕсли\n";
        var result = compare(a, a.replace("Возврат 2", "Возврат 3"));
        assertEquals("ambiguous-identity:flat-tokens", result.fallback());
        assertTrue(result.moves().isEmpty());
        assertFalse(result.diff().right().isEmpty());
    }

    @Test public void movedEditedMethodKeepsLocalChangesInsideItsOwnBody() throws Exception
    {
        String a = method("Переносимый", "#Если Клиент Тогда\nВозврат 11;\n#КонецЕсли");
        String b = method("Стабильный", "Возврат 30;");
        var result = compare(a + b, b + a.replace("11", "12"));
        assertNull(result.fallback());
        assertEquals(1, result.moves().size());
        assertEquals(1, result.diff().left().size());
        assertEquals(1, result.diff().right().size());
        assertEquals(2, result.diff().left().get(0).line());
        assertEquals(6, result.diff().right().get(0).line());
    }

    @Test public void methodDocumentationTravelsWithItsMethod() throws Exception
    {
        String a = "// Описание первого метода\n" + method("Переносимый", "Возврат 1;");
        String b = "// Описание второго метода\n" + method("Стабильный", "Возврат 2;");
        var result = compare(a + b, b + a);
        assertTrue(result.diff().left().isEmpty());
        assertTrue(result.diff().right().isEmpty());
    }

    @Test public void regionTransferDoesNotAlsoMarkItsStableNeighbourMoved() throws Exception
    {
        String a = method("Первый", "Возврат 1;");
        String b = method("Второй", "Возврат 2;");
        var result = compare("#Область Один\n" + a + "#КонецОбласти\n#Область Два\n" + b + "#КонецОбласти\n",
            "#Область Один\n#КонецОбласти\n#Область Два\n" + b + a + "#КонецОбласти\n");
        assertEquals(1, result.moves().size());
        assertTrue(result.moves().get(0).key().endsWith(":первый"));
    }

    @Test public void reverseRegionTransferDoesNotMarkTheOtherNeighbourMoved() throws Exception
    {
        String a = method("Первый", "Возврат 1;");
        String b = method("Второй", "Возврат 2;");
        var result = compare("#Область Один\n" + a + "#КонецОбласти\n#Область Два\n" + b + "#КонецОбласти\n",
            "#Область Один\n" + b + a + "#КонецОбласти\n#Область Два\n#КонецОбласти\n");
        assertEquals(1, result.moves().size());
        assertTrue(result.moves().get(0).key().endsWith(":второй"));
    }

    @Test public void movedMethodEndpointsOccupyDistinctDisplayPositions() throws Exception
    {
        String a = method("Переносимый", "#Если Клиент Тогда\nВозврат 11;\n#Иначе\nВозврат 20;\n#КонецЕсли");
        String b = method("Стабильный", "Возврат 30;");
        var result = compare(a+b,b+a);
        var move=result.moves().get(0);
        int x=-1,y=-1;
        for(int i=0;i<result.diff().rows().size();i++)
        {
            var row=result.diff().rows().get(i);
            if(java.util.Objects.equals(row.left(),move.left().get(0).line()))x=i;
            if(java.util.Objects.equals(row.right(),move.right().get(0).line()))y=i;
        }
        assertTrue(x>=0&&y>=0);assertNotEquals(x,y);
        int stableLine=a.split("\n",-1).length-1;
        assertTrue(result.diff().rows().contains(new StructuralBslDiff.Row(stableLine,0)));
    }

    @Test public void repeatedCommentDeletionDoesNotIndexTheOtherSideDuringSelfComparison() throws Exception
    {
        String a="// A\n// X\n// X\n// X\n// X\n// Z";
        String b="// B\n// Y\n// Q";
        var result=compare(a,b);
        assertEquals("changed",result.diff().status());
        assertEquals(6,result.diff().rows().stream().filter(row->row.left()!=null).count());
        assertEquals(3,result.diff().rows().stream().filter(row->row.right()!=null).count());
    }

    @Test public void bomDoesNotHideFirstRegionContext() throws Exception
    {
        String source="\ufeff#Область A\n"+method("Переносимый", "Возврат 1;")+"#КонецОбласти\n";
        // First run the supported parser lifecycle, then inspect its semantic projection.
        compare(source,source);
        var provider=org.eclipse.xtext.resource.IResourceServiceProvider.Registry.INSTANCE
            .getResourceServiceProvider(org.eclipse.emf.common.util.URI.createURI("memory:/review.bsl"));
        var engine=new MethodAwareBslDiff(provider.get(org.eclipse.xtext.parser.IParser.class),new NullProgressMonitor());
        assertEquals(List.of("region:a"),engine.project(engine.parse(source),source).methods().get(0).context());
    }

    @Test public void bomDoesNotHideFirstConditionalBranchTransfer() throws Exception
    {
        String a=method("Переносимый", "Возврат 1;");
        var result=compare("\ufeff#If Client Then\n"+a+"#Else\n#EndIf\n", "\ufeff#If Client Then\n#Else\n"+a+"#EndIf\n");
        assertEquals(1,result.moves().size());
    }

}
