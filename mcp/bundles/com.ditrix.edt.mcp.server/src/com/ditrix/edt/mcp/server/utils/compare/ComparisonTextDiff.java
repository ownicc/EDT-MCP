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
import java.util.List;
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
    public record Source(String module, String text, boolean absent, String problem, List<Fragment> fragments)
    {
        public Source
        {
            fragments = fragments == null ? null : List.copyOf(fragments);
        }

        public static Source available(String module, String text)
        {
            return new Source(module, Objects.requireNonNull(text), false, null, null);
        }

        public static Source absent(String module)
        {
            return new Source(module, "", true, null, null);
        }

        public static Source unavailable(String module, String problem)
        {
            return new Source(module, null, false, Objects.requireNonNull(problem), null);
        }
    }

    /** Exact source excerpt; a negative offset denotes an absent paired fragment. */
    public record Fragment(String text, int offset, int line, int column)
    {
        static Fragment absent()
        {
            return new Fragment("", -1, 0, 0);
        }
    }

    /** Format exact text changes; structural and merge verdicts remain in the existing report. */
    public static String render(Source from, Source to, String fromLabel, String toLabel,
        int contextLines, int maxLines)
    {
        boolean section = from.fragments() != null || to.fragments() != null;
        StringBuilder result = new StringBuilder(section ? "## Section text diff (" : "## Module text diff (")
            .append(fromLabel).append(" → ").append(toLabel).append(")\n\n")
            .append(section ? "Scope: selected section's own fragments; methods include their signature/body. "
                + "Child methods/regions are excluded. Fragment offsets are UTF-16; source lines/columns are 1-based. "
                + "Hunk lines refer to the original module; partial lines stay partial. This is not an applicable patch. "
                : "Scope: whole owning module. ")
            .append("This pairwise text comparison does not change EDT's structural or conflict verdict.\n\n");
        describe(result, from, fromLabel);
        describe(result, to, toLabel);
        result.append('\n');
        return appendDiff(result, from, to, fromLabel, toLabel, contextLines, maxLines);
    }

    /** Reuse the same bounded formatter for a detached, non-applying BSL edit preview. */
    public static String renderPreview(String module, String before, String after, boolean exists)
    {
        if (before.equals(after)) return "## BSL edit preview\n\nCurrent and proposed text are equal. "
            + "File presence and output bytes are reported separately by wouldChange.\n\n";
        return appendDiff(new StringBuilder("## BSL edit preview\n\n"),
            exists ? Source.available(module, before) : Source.absent(module), Source.available(module, after),
            "Current", "Proposed", 3, 200);
    }

    private static String appendDiff(StringBuilder result, Source from, Source to, String fromLabel, String toLabel,
        int contextLines, int maxLines)
    {
        if (from.problem() != null || to.problem() != null)
        {
            return result.append("Text diff unavailable; no equality or deletion is inferred.\n\n").toString();
        }
        if (from.text().length() > MAX_SOURCE_LENGTH || to.text().length() > MAX_SOURCE_LENGTH)
        {
            return inputLimit(result);
        }
        if (from.fragments() != null || to.fragments() != null)
        {
            for (Source source : List.of(from, to))
            {
                if (source.fragments() == null) continue;
                long length = 0, lines = 0;
                for (Fragment fragment : source.fragments())
                {
                    length += fragment.text().length();
                    lines += new RawText(fragment.text().getBytes(StandardCharsets.UTF_8)).size();
                    if (length > MAX_SOURCE_LENGTH || lines > MAX_SOURCE_LINES) return inputLimit(result);
                }
            }
            return appendSectionDiff(result, from, to, fromLabel, toLabel, contextLines, maxLines);
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
        return appendFormatted(result, output, truncated, fromLabel, toLabel, maxLines, false);
    }

    private static String appendSectionDiff(StringBuilder result, Source from, Source to,
        String fromLabel, String toLabel, int contextLines, int maxLines)
    {
        List<Fragment> left = from.fragments() == null ? List.of() : from.fragments();
        List<Fragment> right = to.fragments() == null ? List.of() : to.fragments();
        if (!from.absent() && !to.absent() && left.size() != right.size())
        {
            return result.append("Text diff unavailable: section fragment pairing is incomplete.\n\n").toString();
        }
        BoundedOutput output = new BoundedOutput(maxLines);
        boolean truncated = false;
        boolean equal = true;
        try
        {
            for (int i = 0; i < Math.max(left.size(), right.size()); i++)
            {
                Fragment a = i < left.size() ? left.get(i) : Fragment.absent();
                Fragment b = i < right.size() ? right.get(i) : Fragment.absent();
                output.write(("# Fragment " + (i + 1) + "\n# " + fromLabel + ": " + coordinates(a)
                    + "\n# " + toLabel + ": " + coordinates(b) + "\n").getBytes(StandardCharsets.UTF_8));
                equal &= a.text().equals(b.text());
                if (a.text().equals(b.text())) continue;
                RawText before = new RawText(a.text().getBytes(StandardCharsets.UTF_8));
                RawText after = new RawText(b.text().getBytes(StandardCharsets.UTF_8));
                EditList edits = DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.HISTOGRAM)
                    .diff(RawTextComparator.DEFAULT, before, after);
                try (DiffFormatter formatter = new DiffFormatter(output)
                {
                    @Override
                    protected void writeHunkHeader(int aStart, int aEnd, int bStart, int bEnd) throws IOException
                    {
                        int x = Math.max(0, a.line() - 1), y = Math.max(0, b.line() - 1);
                        super.writeHunkHeader(aStart + x, aEnd + x, bStart + y, bEnd + y);
                    }
                })
                {
                    formatter.setContext(contextLines);
                    formatter.format(edits, before, after);
                }
            }
        }
        catch (OutputLimit e)
        {
            truncated = true;
        }
        catch (IOException e)
        {
            return result.append("Text diff unavailable: could not format section fragments.\n\n").toString();
        }
        appendFormatted(result, output, truncated, fromLabel, toLabel, maxLines, true);
        if (equal && !truncated)
        {
            result.append("The selected section texts are equal. Child differences and EDT's structural status "
                + "are reported separately; side presence is reported above.\n\n");
        }
        return result.toString();
    }

    private static String coordinates(Fragment fragment)
    {
        return fragment.offset() < 0 ? "absent fragment" : "offset " + fragment.offset()
            + ", length " + fragment.text().length() + ", line " + fragment.line() + ", column " + fragment.column();
    }

    private static String appendFormatted(StringBuilder result, BoundedOutput output, boolean truncated,
        String fromLabel, String toLabel, int maxLines, boolean section)
    {
        String body = output.complete.toString(StandardCharsets.UTF_8);
        if (section) body = body.replace("\n\\ No newline at end of file\n", "\n\\ No newline at end of part\n");
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
