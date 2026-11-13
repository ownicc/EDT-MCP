/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class VerificationTestReportsTest
{
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void selectedEvidenceDistinguishesPassedFailedSkippedEmptyAndMissing() throws Exception
    {
        assertState("passed", "<testsuite tests='1'><testcase name='ok'/></testsuite>");
        assertState("failed", "<testsuite tests='1'><testcase name='bad'><failure message='broken'/></testcase></testsuite>");
        assertState("failed", "<testsuite><testcase name='bad'><error/></testcase></testsuite>");
        assertState("skipped", "<testsuite><testcase name='skip'><skipped/></testcase></testsuite>");
        assertState("incomplete", "<testsuite tests='0'/>");
        var missing = json(VerificationTestReports.read(List.of(folder.getRoot().toPath().resolve("absent.xml").toString()), Instant.EPOCH));
        assertEquals("incomplete", missing.get("state").getAsString());
        assertEquals("notSelected", json(VerificationTestReports.read(List.of(), Instant.EPOCH)).get("state").getAsString());
    }

    @Test
    public void googleTestDisabledCasesAreSkippedRatherThanPassed() throws Exception
    {
        assertState("skipped", "<testsuites tests='2' failures='0' disabled='1' skipped='0' errors='0'>"
            + "<testsuite tests='2' failures='0' disabled='1' skipped='0' errors='0'>"
            + "<testcase name='ok' status='run' result='completed'/>"
            + "<testcase name='DISABLED_test' status='notrun' result='suppressed'/>"
            + "</testsuite></testsuites>");
        assertState("incomplete", "<testsuite tests='1' disabled='1'><testcase name='unknown'/></testsuite>");
    }

    @Test
    public void multipleAssertionsStillCountAsOneFailedTestcase() throws Exception
    {
        assertState("failed", "<testsuite tests='1' failures='1'><testcase name='bad'>"
            + "<failure message='first'/><failure message='second'/></testcase></testsuite>");
    }

    @Test
    public void staleMalformedIncompleteAndUnsafeReportsNeverPass() throws Exception
    {
        for (String xml : List.of("<unfinished>", "<unrelated/>",
            "<testsuite tests='2'><testcase name='onlyOne'/></testsuite>",
            "<testcase><failure/></testcase>",
            "<testsuite failures='1'><testcase/></testsuite>",
            "<!DOCTYPE x [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><testsuite><testcase name='&x;'/></testsuite>"))
        {
            assertState("incomplete", xml);
        }
        Path path = report("<testsuite><testcase/></testsuite>");
        Files.setLastModifiedTime(path, FileTime.from(Instant.EPOCH));
        assertEquals("incomplete", json(VerificationTestReports.read(List.of(path.toString()), Instant.now())).get("state").getAsString());
    }

    @Test
    public void nestedSuitesCountEachCaseOnceAndExposeFailureNames() throws Exception
    {
        Path path = report("<testsuites tests='2' failures='1'><testsuite tests='2' failures='1'>"
            + "<testsuite tests='2' failures='1'><testcase name='ok'/><testcase classname='M' name='bad'>"
            + "<failure message='broken'/></testcase></testsuite></testsuite></testsuites>");
        var result = json(VerificationTestReports.read(List.of(path.toString()), Instant.EPOCH));
        assertEquals("failed", result.get("state").getAsString());
        assertEquals(2, result.get("total").getAsInt());
        assertEquals(1, result.get("failed").getAsInt());
        var row = result.getAsJsonArray("reports").get(0).getAsJsonObject();
        assertEquals(64, row.get("sha256").getAsString().length());
        assertTrue(row.toString(), row.toString().contains("M.bad"));
    }

    @Test
    public void failureSurvivesAnotherMissingReportAndDetailTruncation() throws Exception
    {
        StringBuilder xml = new StringBuilder("<testsuite>");
        for (int i = 0; i < 110; i++) xml.append("<testcase name='t").append(i).append("'><failure/></testcase>");
        Path path = report(xml.append("</testsuite>").toString());
        var result = json(VerificationTestReports.read(List.of(path.toString(), folder.getRoot().toPath().resolve("missing").toString()), Instant.EPOCH));
        assertEquals("failed", result.get("state").getAsString());
        assertFalse(result.get("complete").getAsBoolean());
        assertEquals(110, result.get("failed").getAsInt());
        var row = result.getAsJsonArray("reports").get(0).getAsJsonObject();
        assertTrue(row.get("truncated").getAsBoolean());
        assertEquals(100, row.getAsJsonArray("details").size());
    }

    private void assertState(String state, String xml) throws Exception
    {
        Path path = report(xml);
        var result = json(VerificationTestReports.read(List.of(path.toString()), Instant.EPOCH));
        assertEquals(result.toString(), state, result.get("state").getAsString());
    }

    private Path report(String xml) throws Exception
    {
        Path path = folder.newFile().toPath();
        return Files.writeString(path, xml);
    }

    private static JsonObject json(Object value)
    {
        return JsonParser.parseString(com.ditrix.edt.mcp.server.protocol.GsonProvider.toJson(value)).getAsJsonObject();
    }
}
