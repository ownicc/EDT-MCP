package com.ditrix.edt.mcp.server.ui;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.eclipse.compare.IEncodedStreamContentAccessor;
import org.eclipse.compare.structuremergeviewer.DiffNode;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.Test;

public class BslMethodCompareInputTest
{
    @Test public void extractsOnlyMethodBytesWithOriginalCoordinates() throws Exception
    {
        String source = "\ufeff// Заголовок\r\nФункция Имя()\r\nВозврат \"😀\";\r\nКонецФункции\r\n// Соседний код";
        var fragment = BslMethodCompareInput.extract(source, List.of(new StructuralBslDiff.Span(1,0,13),
            new StructuralBslDiff.Span(2,0,13), new StructuralBslDiff.Span(3,0,12)));
        assertEquals("Функция Имя()\r\nВозврат \"😀\";\r\nКонецФункции", fragment.text());
        assertEquals(2, fragment.firstLine());
        assertEquals(4, fragment.lastLine());
    }

    @Test public void methodTabContainsOnlyDetachedMatchedBodiesAndCannotSave() throws Exception
    {
        String a = "Функция Имя()\nВозврат 1;\nКонецФункции\n\n";
        String b = "Функция Другая()\nВозврат 2;\nКонецФункции\n\n";
        var result = MethodAwareBslDiff.compute(a+b,b+a.replace("1", "3"),new NullProgressMonitor());
        var move = result.moves().stream().filter(m->m.key().endsWith(":имя")).findFirst().orElseThrow();
        var input = new BslMethodCompareInput(move,a+b,b+a.replace("1", "3"));
        assertFalse(input.getCompareConfiguration().isLeftEditable());
        assertFalse(input.getCompareConfiguration().isRightEditable());
        input.setDirty(true);input.saveChanges(new NullProgressMonitor());input.save(new NullProgressMonitor());
        assertFalse(input.isDirty());assertFalse(input.isSaveNeeded());
        var node=(DiffNode)input.prepareInput(new NullProgressMonitor());
        String left = new String(((IEncodedStreamContentAccessor)node.getLeft()).getContents().readAllBytes(), StandardCharsets.UTF_8);
        String right = new String(((IEncodedStreamContentAccessor)node.getRight()).getContents().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(left.contains("Возврат 1;"));assertTrue(right.contains("Возврат 3;"));
        assertFalse(left.contains("Другая"));assertFalse(right.contains("Другая"));
        assertTrue(input.getTitle().contains("1 → 5"));
    }
}
