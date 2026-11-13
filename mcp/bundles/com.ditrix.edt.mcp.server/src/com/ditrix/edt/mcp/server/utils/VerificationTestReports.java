/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded, explicitly selected JUnit evidence; never launches tests or writes report files. */
public final class VerificationTestReports
{
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int MAX_DETAILS = 100;

    private VerificationTestReports() { }

    public static void validatePaths(List<String> paths)
    {
        if (paths.size() > 10) throw new IllegalArgumentException("testReports accepts at most 10 paths; select fewer reports."); //$NON-NLS-1$
        var seen = new HashSet<Path>();
        for (String value : paths)
        {
            if (value == null || value.isBlank() || !Path.of(value).isAbsolute())
                throw new IllegalArgumentException("Invalid testReports path '" + value + "'; pass an absolute path on the EDT server."); //$NON-NLS-1$ //$NON-NLS-2$
            if (!seen.add(Path.of(value).normalize()))
                throw new IllegalArgumentException("Duplicate testReports path '" + value + "'; select each report once."); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    public static Map<String, Object> read(List<String> paths, Instant baselineAt)
    {
        validatePaths(paths);
        List<Map<String, Object>> reports = new ArrayList<>();
        int total = 0, passed = 0, failed = 0, errors = 0, skipped = 0;
        boolean complete = true;
        var realPaths = new HashSet<Path>();
        for (String value : paths)
        {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("path", value); //$NON-NLS-1$
            try
            {
                Path path = Path.of(value).toRealPath();
                if (!realPaths.add(path)) throw new IllegalArgumentException("Report alias selected twice; select each report once"); //$NON-NLS-1$
                var before = Files.readAttributes(path, BasicFileAttributes.class);
                if (!before.isRegularFile() || before.size() > MAX_BYTES)
                    throw new IllegalArgumentException("Select a regular JUnit XML file no larger than " + MAX_BYTES + " bytes"); //$NON-NLS-1$ //$NON-NLS-2$
                byte[] bytes;
                try (var input = Files.newInputStream(path)) { bytes = input.readNBytes(MAX_BYTES + 1); }
                if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("JUnit XML exceeds size limit"); //$NON-NLS-1$
                var after = Files.readAttributes(path, BasicFileAttributes.class);
                if (before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())
                    || !java.util.Objects.equals(before.fileKey(), after.fileKey()) || bytes.length != after.size())
                    throw new IllegalArgumentException("Report changed during read; wait for the test runner to finish and retry"); //$NON-NLS-1$
                row.put("modifiedAt", after.lastModifiedTime().toInstant().toString()); //$NON-NLS-1$
                row.put("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))); //$NON-NLS-1$ //$NON-NLS-2$
                if (after.lastModifiedTime().toMillis() < baselineAt.toEpochMilli())
                    throw new IllegalArgumentException("Report predates baseline; select results from the post-edit test run"); //$NON-NLS-1$
                JUnitTestResults results = JUnitXmlParser.parseComplete(new ByteArrayInputStream(bytes));
                if (results.getTotal() == 0) throw new IllegalArgumentException("JUnit report contains no executed test selection"); //$NON-NLS-1$
                total += results.getTotal(); passed += results.getPassed(); failed += results.getFailures();
                errors += results.getErrors(); skipped += results.getSkipped();
                row.put("state", !results.isPassed() ? "failed" : results.getSkipped() > 0 ? "skipped" : "passed"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                row.put("total", results.getTotal()); row.put("passed", results.getPassed()); //$NON-NLS-1$ //$NON-NLS-2$
                row.put("failed", results.getFailures()); row.put("errors", results.getErrors()); //$NON-NLS-1$ //$NON-NLS-2$
                row.put("skipped", results.getSkipped()); //$NON-NLS-1$
                List<Map<String, String>> details = new ArrayList<>();
                details(details, "failed", results.getFailureDetails()); //$NON-NLS-1$
                details(details, "error", results.getErrorDetails()); //$NON-NLS-1$
                details(details, "skipped", results.getSkippedDetails()); //$NON-NLS-1$
                row.put("details", details); //$NON-NLS-1$
                row.put("truncated", results.getFailures() + results.getErrors() + results.getSkipped() > details.size()); //$NON-NLS-1$
            }
            catch (Exception e)
            {
                complete = false;
                row.put("state", "incomplete"); //$NON-NLS-1$ //$NON-NLS-2$
                row.put("reason", bounded(PlatformFailures.describe(e))); //$NON-NLS-1$
            }
            reports.add(row);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("state", paths.isEmpty() ? "notSelected" : failed + errors > 0 ? "failed" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            : !complete ? "incomplete" : skipped > 0 ? "skipped" : "passed"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        summary.put("complete", complete); //$NON-NLS-1$
        summary.put("total", total); summary.put("passed", passed); summary.put("failed", failed); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        summary.put("errors", errors); summary.put("skipped", skipped); summary.put("reports", reports); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return summary;
    }

    private static void details(List<Map<String, String>> output, String state, List<JUnitTestResults.TestCase> cases)
    {
        for (var item : cases)
        {
            if (output.size() == MAX_DETAILS) return;
            output.add(Map.of("state", state, "name", bounded(item.name), "message", bounded(item.message))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
    }

    private static String bounded(String text)
    {
        return text == null ? "" : text.length() <= 1024 ? text : text.substring(0, 1024) + "… [truncated]"; //$NON-NLS-1$ //$NON-NLS-2$
    }
}
