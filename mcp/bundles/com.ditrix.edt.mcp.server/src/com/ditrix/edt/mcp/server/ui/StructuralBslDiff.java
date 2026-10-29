/** MCP Server for EDT. Licensed under AGPL-3.0-or-later. */
package com.ditrix.edt.mcp.server.ui;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Pinned difftastic JSON adapter. All columns exposed to SWT are UTF-16. */
final class StructuralBslDiff
{
    static final String EXECUTABLE_PROPERTY = "edt.mcp.difftastic"; //$NON-NLS-1$
    private static final long MAX_OUTPUT_BYTES = 16L * 1024 * 1024;
    record Span(int line, int start, int length) { }
    record Row(Integer left, Integer right) { }
    record Result(String language, String status, List<Row> rows, List<Span> left, List<Span> right) { }

    private StructuralBslDiff() { }

    static Result compare(Path executable, String before, String after, IProgressMonitor monitor)
        throws IOException, InterruptedException
    {
        if (!executable.isAbsolute() || !Files.isRegularFile(executable) || !Files.isExecutable(executable))
            throw new IOException("The BSL-enabled difftastic executable is unavailable"); //$NON-NLS-1$
        Path directory = Files.createTempDirectory("edt-bsl-diff-"); //$NON-NLS-1$
        Path left = directory.resolve("current.bsl"); //$NON-NLS-1$
        Path right = directory.resolve("proposed.bsl"); //$NON-NLS-1$
        Path output = directory.resolve("diff.json"); //$NON-NLS-1$
        Process process = null;
        try
        {
            Files.writeString(left, before, StandardCharsets.UTF_8);
            Files.writeString(right, after, StandardCharsets.UTF_8);
            ProcessBuilder builder = new ProcessBuilder(executable.toString(), "--display=json", //$NON-NLS-1$
                "--color=never", left.toString(), right.toString()); //$NON-NLS-1$
            builder.environment().keySet().removeIf(key -> key.startsWith("DFT_")); //$NON-NLS-1$
            builder.environment().put("DFT_UNSTABLE", "yes"); //$NON-NLS-1$ //$NON-NLS-2$
            process = builder.redirectErrorStream(true).redirectOutput(output.toFile()).start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!process.waitFor(100, TimeUnit.MILLISECONDS))
            {
                if (monitor.isCanceled()) throw new OperationCanceledException();
                if (System.nanoTime() >= deadline) throw new IOException("Structural comparison timed out"); //$NON-NLS-1$
                if (Files.size(output) > MAX_OUTPUT_BYTES) throw new IOException("Diff output is too large"); //$NON-NLS-1$
            }
            if (monitor.isCanceled()) throw new OperationCanceledException();
            if (process.exitValue() != 0 || Files.size(output) > MAX_OUTPUT_BYTES)
                throw new IOException("Difftastic did not return a usable comparison"); //$NON-NLS-1$
            try { return parse(Files.readString(output, StandardCharsets.UTF_8), before, after); }
            catch (RuntimeException e) { throw new IOException("Invalid difftastic output", e); } //$NON-NLS-1$
        }
        finally
        {
            if (process != null && process.isAlive())
            {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
            for (Path file : List.of(left, right, output, directory)) Files.deleteIfExists(file);
        }
    }

    static List<String> lines(String text)
    {
        if (text.isEmpty()) return List.of();
        return List.copyOf(Arrays.asList(text.split("\\r\\n|\\n|\\r", -1))); //$NON-NLS-1$
    }

    static Result parse(String json, String before, String after)
    {
        try
        {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            String language = root.get("language").getAsString(); //$NON-NLS-1$
            String status = root.get("status").getAsString(); //$NON-NLS-1$
            if (!List.of("changed", "unchanged", "created", "deleted").contains(status)) //$NON-NLS-1$
                throw new IllegalArgumentException("Unknown diff status"); //$NON-NLS-1$
            List<String> lhs = lines(before), rhs = lines(after);
            JsonArray chunks = root.getAsJsonArray("chunks"); //$NON-NLS-1$
            if (chunks == null && status.equals("changed")) //$NON-NLS-1$
                throw new IllegalArgumentException("Changed comparison has no chunks"); //$NON-NLS-1$
            if (chunks == null) chunks = new JsonArray();
            List<Span> left = spans(chunks, "lhs", lhs); //$NON-NLS-1$
            List<Span> right = spans(chunks, "rhs", rhs); //$NON-NLS-1$
            if (status.equals("created")) right = entire(rhs); //$NON-NLS-1$
            if (status.equals("deleted")) left = entire(lhs); //$NON-NLS-1$
            List<Row> rows = new ArrayList<>();
            int l = 0, r = 0;
            if (root.has("aligned_lines")) //$NON-NLS-1$
            {
                JsonArray alignment = root.getAsJsonArray("aligned_lines"); //$NON-NLS-1$
                for (int index = 0; index < alignment.size(); index++)
                {
                    JsonArray pair = alignment.get(index).getAsJsonArray();
                    if (pair.size() != 2) throw new IllegalArgumentException("Invalid aligned pair"); //$NON-NLS-1$
                    Integer a = pair.get(0).isJsonNull() ? null : pair.get(0).getAsInt();
                    Integer b = pair.get(1).isJsonNull() ? null : pair.get(1).getAsInt();
                    boolean last = index == alignment.size() - 1;
                    if ((a == null && b == null) || (a != null && (a < l || a > lhs.size()
                        || (a == lhs.size() && !last))) || (b != null && (b < r || b > rhs.size()
                        || (b == rhs.size() && !last))))
                        throw new IllegalArgumentException("Invalid diff line alignment"); //$NON-NLS-1$
                    int untilL = a == null ? l : a, untilR = b == null ? r : b;
                    while (l < untilL || r < untilR)
                        rows.add(new Row(l < untilL ? l++ : null, r < untilR ? r++ : null));
                    // Difftastic may align a virtual EOF marker after the final physical line.
                    Integer visibleA = a != null && a == lhs.size() ? null : a;
                    Integer visibleB = b != null && b == rhs.size() ? null : b;
                    if (visibleA != null || visibleB != null) rows.add(new Row(visibleA, visibleB));
                    if (a != null) l = a + 1;
                    if (b != null) r = b + 1;
                }
            }
            while (l < lhs.size() || r < rhs.size())
                rows.add(new Row(l < lhs.size() ? l++ : null, r < rhs.size() ? r++ : null));
            return new Result(language, status, List.copyOf(rows), left, right);
        }
        catch (RuntimeException e) { throw new IllegalArgumentException("Invalid difftastic comparison", e); } //$NON-NLS-1$
    }

    private static List<Span> entire(List<String> lines)
    {
        List<Span> result = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++)
            if (!lines.get(i).isEmpty()) result.add(new Span(i, 0, lines.get(i).length()));
        return List.copyOf(result);
    }

    private static List<Span> spans(JsonArray chunks, String side, List<String> lines)
    {
        List<Span> spans = new ArrayList<>();
        for (JsonElement chunk : chunks)
            for (JsonElement entry : chunk.getAsJsonArray())
            {
                JsonObject pair = entry.getAsJsonObject();
                if (!pair.has(side) || pair.get(side).isJsonNull()) continue;
                JsonObject line = pair.getAsJsonObject(side);
                int number = line.get("line_number").getAsInt(); //$NON-NLS-1$
                if (number < 0 || number >= lines.size()) throw new IllegalArgumentException("Invalid span line"); //$NON-NLS-1$
                String source = lines.get(number);
                for (JsonElement change : line.getAsJsonArray("changes")) //$NON-NLS-1$
                {
                    JsonObject range = change.getAsJsonObject();
                    int start = utf16(source, range.get("start").getAsInt()); //$NON-NLS-1$
                    int end = utf16(source, range.get("end").getAsInt()); //$NON-NLS-1$
                    if (end < start || !source.substring(start, end).equals(range.get("content").getAsString())) //$NON-NLS-1$
                        throw new IllegalArgumentException("Diff span does not match source"); //$NON-NLS-1$
                    spans.add(new Span(number, start, end - start));
                }
            }
        spans.sort(Comparator.comparingInt(Span::line).thenComparingInt(Span::start));
        return List.copyOf(spans);
    }

    private static int utf16(String text, int column)
    {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (column < 0 || column > bytes.length) throw new IllegalArgumentException("Invalid UTF-8 column"); //$NON-NLS-1$
        try
        {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, 0, column)).length();
        }
        catch (CharacterCodingException e) { throw new IllegalArgumentException("Column splits a UTF-8 character", e); } //$NON-NLS-1$
    }
}
