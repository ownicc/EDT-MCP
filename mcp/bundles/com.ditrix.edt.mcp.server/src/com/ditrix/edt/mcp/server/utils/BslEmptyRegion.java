// SPDX-License-Identifier: AGPL-3.0-or-later
package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves a saved module's empty region as a whole-line insertion target. */
public final class BslEmptyRegion
{
    // Native Bsl.xtext LETTER ranges. Non-ASCII IDENT_DIGIT ranges are already included here.
    private static final String LETTERS = "_A-Za-z\\u00c0-\\u00d6\\u00d8-\\u00f6\\u00f8-\\u1fff"
        + "\\u3040-\\u318f\\u3300-\\u337f\\u3400-\\u3d2d\\u4e00-\\u9fff\\uf900-\\ufaff";
    private static final String NAME = "[" + LETTERS + "][" + LETTERS + "0-9]*";
    private static final String SPACE = "[ \\t\\u00a0]";
    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final String REGION = "(?:Region|\\u041e\\u0431\\u043b\\u0430\\u0441\\u0442\\u044c)";
    private static final String END_REGION =
        "(?:EndRegion|\\u041a\\u043e\\u043d\\u0435\\u0446\\u041e\\u0431\\u043b\\u0430\\u0441\\u0442\\u0438)";
    private static final Pattern START = Pattern.compile(
        "^" + SPACE + "*#[ \\t]*" + REGION + SPACE + "+(" + NAME + ")" + SPACE + "*(?://.*)?$", FLAGS);
    private static final Pattern END = Pattern.compile(
        "^" + SPACE + "*#[ \\t]*" + END_REGION + SPACE + "*(?://.*)?$", FLAGS);
    private static final Pattern REGION_DIRECTIVE = Pattern.compile(
        "^" + SPACE + "*#[ \\t]*(?:" + REGION + "|" + END_REGION + ")", FLAGS);

    private BslEmptyRegion()
    {
    }

    /** A zero-based insertion line, or an actionable refusal with insertionLine = -1. */
    public record Resolution(int insertionLine, String error)
    {
    }

    private record OpenRegion(String name, int startLine)
    {
    }

    /** Finds the uniquely named empty region outside methods; never changes the input. */
    public static Resolution resolve(List<String> lines, String regionName)
    {
        if (regionName == null || regionName.isBlank())
        {
            return refused("regionName must name an existing empty region. Read read_module_source and retry.");
        }
        List<String> scan = BslSyntaxChecker.maskLiteralsAndComments(lines);
        List<OpenRegion> stack = new ArrayList<>();
        OpenRegion target = null;
        int endLine = -1;
        int matches = 0;
        for (int i = 0; i < lines.size(); i++)
        {
            String raw = lines.get(i);
            if (raw.indexOf('\r') >= 0 || raw.indexOf('\n') >= 0)
            {
                return refused("Unsupported physical line boundary at line " + (i + 1)
                    + ". Use LF or CRLF line endings before inserting into a region.");
            }
            if (!REGION_DIRECTIVE.matcher(scan.get(i)).find())
            {
                continue;
            }
            // The mask only identifies code. Validate the original directive so an extra string
            // literal cannot disappear into whitespace and masquerade as a valid directive tail.
            Matcher start = START.matcher(raw);
            if (start.matches())
            {
                OpenRegion opened = new OpenRegion(start.group(1), i);
                stack.add(opened);
                if (opened.name.equalsIgnoreCase(regionName))
                {
                    matches++;
                }
            }
            else if (END.matcher(raw).matches())
            {
                if (stack.isEmpty())
                {
                    return refused("Unmatched region end at line " + (i + 1)
                        + ". Fix the region boundaries before inserting a method.");
                }
                OpenRegion closed = stack.remove(stack.size() - 1);
                if (closed.name.equalsIgnoreCase(regionName))
                {
                    target = closed;
                    endLine = i;
                }
            }
            else
            {
                return refused("Unsupported region directive at line " + (i + 1)
                    + ". Put #Region <name> or #EndRegion alone on its line; trailing // comments are allowed.");
            }
        }
        if (!stack.isEmpty())
        {
            return refused("Unclosed region '" + stack.get(stack.size() - 1).name
                + "'. Fix the region boundaries before inserting a method.");
        }
        if (matches > 1)
        {
            return refused("regionName '" + regionName + "' is ambiguous: " + matches
                + " declarations exist. Read read_module_source and select a uniquely named region.");
        }
        if (target == null)
        {
            return refused("regionName '" + regionName
                + "' was not found. Read read_module_source and use an existing empty region.");
        }
        for (BslModuleUtils.MethodSpan method : BslModuleUtils.findMethodSpansViaText(lines))
        {
            if (target.startLine >= method.declarationLine && target.startLine <= method.endLine)
            {
                return refused("Region '" + regionName
                    + "' is inside a method. Select a module-level region for a new method.");
            }
        }
        for (int i = target.startLine + 1; i < endLine; i++)
        {
            String trimmed = lines.get(i).replace('\u00a0', ' ').trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("//"))
            {
                return refused("Region '" + regionName + "' is not empty at line " + (i + 1)
                    + ". Select an empty region containing only whitespace/comments, or use insertBefore/insertAfter.");
            }
        }
        return new Resolution(endLine, null);
    }

    private static Resolution refused(String message)
    {
        return new Resolution(-1, message);
    }
}
