/** MCP Server for EDT. Licensed under AGPL-3.0-or-later. */
package com.ditrix.edt.mcp.server.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;

/** Shared geometry avoids font fallback and undersized Unicode arrows. */
final class BslMoveIcon
{
    private BslMoveIcon() { }

    static int size(int lineHeight) { return Math.max(16, lineHeight - 6); }

    static void draw(GC gc, int x, int y, int size)
    {
        int oldWidth = gc.getLineWidth(), oldCap = gc.getLineCap(), oldAntialias = gc.getAntialias();
        try
        {
            gc.setAntialias(SWT.ON);
            gc.setLineWidth(Math.max(2, size / 10));
            gc.setLineCap(SWT.CAP_ROUND);
            int left = x + 2, right = x + size - 3, middle = y + size / 2;
            int head = Math.max(4, size / 4);
            gc.drawLine(left, middle, right, middle);
            gc.drawLine(left, middle, left + head, middle - head);
            gc.drawLine(left, middle, left + head, middle + head);
            gc.drawLine(right, middle, right - head, middle - head);
            gc.drawLine(right, middle, right - head, middle + head);
        }
        finally
        {
            gc.setLineWidth(oldWidth); gc.setLineCap(oldCap); gc.setAntialias(oldAntialias);
        }
    }
}
