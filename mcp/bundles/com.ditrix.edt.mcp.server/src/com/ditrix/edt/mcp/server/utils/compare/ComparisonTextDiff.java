/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils.compare;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import org.eclipse.jgit.diff.DiffAlgorithm;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;

import com.ditrix.edt.mcp.server.utils.MarkdownUtils;

/** Detached, bounded module text comparison. No live model objects enter the formatter. */
public final class ComparisonTextDiff
{
    private static final int MAX_SOURCE_LENGTH = 500_000;
    private static final int MAX_SOURCE_LINES = 20_000;
    private static final int MAX_OUTPUT_BYTES = 64 * 1024;

    private ComparisonTextDiff()
    {
    }

    /** Absence is an observation about a side, not a failed read or an empty existing file. */
    public record Source(String module, String text, boolean absent, String problem)
    {
        public static Source available(String module, String text)
        {
            return new Source(module, Objects.requireNonNull(text), false, null);
        }

        public static Source absent(String module)
        {
            return new Source(module, "", true, null);
        }

        public static Source unavailable(String module, String problem)
        {
            return new Source(module, null, false, Objects.requireNonNull(problem));
        }
    }

    /** Format exact text changes; structural and merge verdicts remain in the existing report. */
    public static String render(Source from, Source to, String fromLabel, String toLabel,
        int contextLines, int maxLines)
    {
        StringBuilder result = new StringBuilder("## Module text diff (")
            .append(fromLabel).append(" → ").append(toLabel).append(")\n\n")
            .append("Scope: whole owning module, including when a section was addressed. ")
            .append("This pairwise text comparison does not change EDT's structural or conflict verdict.\n\n");
        describe(result, from, fromLabel);
        describe(result, to, toLabel);
        result.append('\n');
        if (from.problem() != null || to.problem() != null)
        {
            return result.append("Text diff unavailable; no equality or deletion is inferred.\n\n").toString();
        }
        if (from.text().length() > MAX_SOURCE_LENGTH || to.text().length() > MAX_SOURCE_LENGTH)
        {
            return inputLimit(result);
        }
        RawText before = new RawText(from.text().getBytes(StandardCharsets.UTF_8));
        RawText after = new RawText(to.text().getBytes(StandardCharsets.UTF_8));
        if (before.size() > MAX_SOURCE_LINES || after.size() > MAX_SOURCE_LINES)
        {
            return inputLimit(result);
        }
        if (from.text().equals(to.text()))
        {
            return result.append("The selected module texts are equal. Side presence is reported above; ")
                .append("this says nothing about unfinished or excluded structural content.\n\n").toString();
        }

        EditList edits = DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.HISTOGRAM)
            .diff(RawTextComparator.DEFAULT, before, after);
        BoundedOutput output = new BoundedOutput(maxLines);
        boolean truncated = false;
        try (DiffFormatter formatter = new DiffFormatter(output))
        {
            formatter.setContext(contextLines);
            formatter.format(edits, before, after);
        }
        catch (OutputLimit e)
        {
            truncated = true;
        }
        catch (IOException e)
        {
            return result.append("Text diff unavailable: could not format module text.\n\n").toString();
        }
        String body = output.complete.toString(StandardCharsets.UTF_8);
        String fence = "`".repeat(Math.max(3, longestBackticks(body) + 1));
        result.append(fence).append("diff\n--- ").append(fromLabel)
            .append("\n+++ ").append(toLabel).append('\n').append(body)
            .append(fence).append("\n\n");
        if (truncated)
        {
            result.append("Text diff truncated at ").append(maxLines)
                .append(" formatted lines or 65536 bytes; an overlong line is omitted in full. ")
                .append("Hunk headers describe the complete hunk; the displayed patch may be incomplete.\n\n");
        }
        return result.toString();
    }

    private static void describe(StringBuilder result, Source source, String label)
    {
        result.append("- ").append(label).append(": ")
            .append(source.problem() != null ? "unavailable" : source.absent() ? "absent" : "present");
        if (source.module() != null && !source.module().isBlank())
        {
            result.append(" — ").append(MarkdownUtils.inlineCode(source.module()));
        }
        if (source.problem() != null)
        {
            result.append(" — ").append(MarkdownUtils.inlineCode(source.problem()));
        }
        result.append('\n');
    }

    private static String inputLimit(StringBuilder result)
    {
        return result.append("Text diff unavailable: input limit is 500000 UTF-16 units and ")
            .append("20000 lines per side. No text comparison was performed.\n\n").toString();
    }

    private static int longestBackticks(String value)
    {
        int longest = 0;
        int current = 0;
        for (int i = 0; i < value.length(); i++)
        {
            current = value.charAt(i) == '`' ? current + 1 : 0;
            longest = Math.max(longest, current);
        }
        return longest;
    }

    /** Keep complete UTF-8 lines only, with bounded storage even for a single huge line. */
    private static final class BoundedOutput extends OutputStream
    {
        private final ByteArrayOutputStream complete = new ByteArrayOutputStream();
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        private final int maxLines;
        private int lines;

        BoundedOutput(int maxLines)
        {
            this.maxLines = maxLines;
        }

        @Override
        public void write(int value) throws IOException
        {
            if (lines >= maxLines || complete.size() + pending.size() >= MAX_OUTPUT_BYTES)
            {
                throw new OutputLimit();
            }
            pending.write(value);
            if (value == '\n')
            {
                pending.writeTo(complete);
                pending.reset();
                lines++;
            }
        }
    }

    private static final class OutputLimit extends IOException
    {
        private static final long serialVersionUID = 1L;
    }
}
