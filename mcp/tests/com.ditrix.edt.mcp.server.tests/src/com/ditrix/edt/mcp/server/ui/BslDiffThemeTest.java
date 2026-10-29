package com.ditrix.edt.mcp.server.ui;

import static org.junit.Assert.assertTrue;
import java.util.List;
import org.eclipse.swt.graphics.RGB;
import org.junit.Test;

public class BslDiffThemeTest
{
    @Test public void darkChangedBackgroundsStandOutFromSource()
    {
        for (RGB background : List.of(BslStructuralCompare.DARK_ADDED, BslStructuralCompare.DARK_REMOVED))
            assertTrue("Changed background blends into source: " + background,
                (luminance(background) + .05) / (luminance(BslStructuralCompare.DARK_BACKGROUND) + .05) >= 2.0);
    }

    @Test public void darkTextRemainsReadableOnSourceAndChangedBackgrounds()
    {
        for (String category : List.of("Others", "BSL_Keywords", "Strings", "Numbers",
            "Comment", "BSL_Pragmas", "Preprocessor", "Builtin function", "Operators"))
        {
            for (RGB background : List.of(BslStructuralCompare.DARK_BACKGROUND,
                BslStructuralCompare.DARK_ADDED, BslStructuralCompare.DARK_REMOVED))
            {
                RGB foreground = background.equals(BslStructuralCompare.DARK_BACKGROUND)
                    ? BslStructuralCompare.darkForeground(category) : BslStructuralCompare.DARK_CHANGED_FOREGROUND;
                double light = luminance(foreground), dark = luminance(background);
                double contrast = (Math.max(light, dark) + .05) / (Math.min(light, dark) + .05);
                assertTrue(category + " has insufficient contrast on " + background + ": " + contrast,
                    contrast >= 4.5);
            }
        }
    }

    private static double luminance(RGB color)
    {
        return .2126 * linear(color.red) + .7152 * linear(color.green) + .0722 * linear(color.blue);
    }

    private static double linear(int channel)
    {
        double value = channel / 255.0;
        return value <= .04045 ? value / 12.92 : Math.pow((value + .055) / 1.055, 2.4);
    }
}
