/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.io.File;
import java.io.InputStream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Parses a JUnit XML report file into a {@link JUnitTestResults} model.
 *
 * Supports the standard {@code testsuite}/{@code testcase} layout, including
 * the {@code testsuites} root wrapper. The parser is hardened against XXE,
 * SSRF and entity-expansion attacks.
 */
public final class JUnitXmlParser
{
    private static final String MESSAGE = "message"; //$NON-NLS-1$

    private JUnitXmlParser()
    {
        // utility class
    }

    /**
     * Parses a JUnit XML file. Throws on parse failure.
     */
    public static JUnitTestResults parse(File junitXml) throws Exception
    {
        DocumentBuilder builder = newSecureBuilder();
        Document doc = builder.parse(junitXml);
        return parseDocument(doc);
    }

    /**
     * Parses JUnit XML from an input stream. Useful for unit tests.
     */
    public static JUnitTestResults parse(InputStream in) throws Exception
    {
        DocumentBuilder builder = newSecureBuilder();
        Document doc = builder.parse(in);
        return parseDocument(doc);
    }

    /** Strict complete evidence for verification; legacy YAXUnit parsing remains permissive. */
    public static JUnitTestResults parseComplete(InputStream in) throws Exception
    {
        Element root = newSecureBuilder().parse(in).getDocumentElement();
        JUnitTestResults results = new JUnitTestResults();
        int[] counts = completeSuite(root, results);
        results.addToTotals(counts[0], counts[1], counts[2], counts[3] + counts[4]);
        return results;
    }

    private static int[] completeSuite(Element suite, JUnitTestResults results)
    {
        String tag = suite.getTagName();
        if (!"testsuite".equals(tag) && !"testsuites".equals(tag)) //$NON-NLS-1$ //$NON-NLS-2$
            throw new IllegalArgumentException("Expected JUnit testsuite/testsuites, got " + tag); //$NON-NLS-1$
        int[] counts = new int[5];
        var children = suite.getChildNodes();
        for (int i = 0; i < children.getLength(); i++)
        {
            if (!(children.item(i) instanceof Element child)) continue;
            if ("testsuite".equals(child.getTagName()) || "testsuites".equals(child.getTagName())) //$NON-NLS-1$ //$NON-NLS-2$
            {
                int[] nested = completeSuite(child, results);
                for (int j = 0; j < counts.length; j++) counts[j] += nested[j];
            }
            else if ("testcase".equals(child.getTagName())) //$NON-NLS-1$
            {
                counts[0]++;
                String name = child.getAttribute("classname"); //$NON-NLS-1$
                name = (name.isEmpty() ? "" : name + ".") + child.getAttribute("name"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                int kind = 0;
                StringBuilder messages = new StringBuilder();
                var content = child.getChildNodes();
                for (int j = 0; j < content.getLength(); j++)
                {
                    if (!(content.item(j) instanceof Element outcome)) continue;
                    int next = switch (outcome.getTagName()) {
                        case "failure" -> 1; //$NON-NLS-1$
                        case "error" -> 2; //$NON-NLS-1$
                        case "skipped" -> 3; //$NON-NLS-1$
                        default -> 0;
                    };
                    if (next == 0) continue;
                    if (kind != 0 && kind != next)
                        throw new IllegalArgumentException("JUnit testcase has conflicting outcomes: " + name); //$NON-NLS-1$
                    kind = next;
                    if (!messages.isEmpty()) messages.append('\n');
                    messages.append(outcome.getAttribute(MESSAGE));
                }
                if (kind == 0 && ("notrun".equals(child.getAttribute("status")) //$NON-NLS-1$ //$NON-NLS-2$
                    || "suppressed".equals(child.getAttribute("result")))) kind = 4; //$NON-NLS-1$ //$NON-NLS-2$
                if (kind == 0 && "skipped".equals(child.getAttribute("result"))) kind = 3; //$NON-NLS-1$ //$NON-NLS-2$
                if (kind > 0)
                {
                    counts[kind]++;
                    var detail = new JUnitTestResults.TestCase(name, messages.toString(), null);
                    if (kind == 1) results.addFailure(detail);
                    else if (kind == 2) results.addError(detail);
                    else results.addSkipped(detail);
                }
            }
        }
        String[] attributes = {"tests", "failures", "errors", "skipped", "disabled"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        for (int i = 0; i < counts.length; i++)
        {
            if (suite.hasAttribute(attributes[i]) && Integer.parseInt(suite.getAttribute(attributes[i])) != counts[i])
                throw new IllegalArgumentException("Incomplete or inconsistent JUnit " + attributes[i] + " count"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return counts;
    }

    private static JUnitTestResults parseDocument(Document doc)
    {
        doc.getDocumentElement().normalize();

        JUnitTestResults results = new JUnitTestResults();

        NodeList suiteNodes = doc.getElementsByTagName("testsuite"); //$NON-NLS-1$
        for (int i = 0; i < suiteNodes.getLength(); i++)
        {
            Element suite = (Element) suiteNodes.item(i);

            int attrTests = getIntAttr(suite, "tests", 0); //$NON-NLS-1$
            int attrFailures = getIntAttr(suite, "failures", 0); //$NON-NLS-1$
            int attrErrors = getIntAttr(suite, "errors", 0); //$NON-NLS-1$
            int attrSkipped = getIntAttr(suite, "skipped", 0); //$NON-NLS-1$

            // Scan the suite's testcases: record each failure/error/skipped into 'results'
            // and return the fallback node counts {caseCount, failures, errors, skipped} // NOSONAR explanatory comment, not commented-out code
            // for producers that omit or misreport the testsuite attributes.
            int[] nodeCounts = countSuiteCases(suite, results);

            // Use the higher of attribute count vs node count to handle producers
            // that omit or misreport testsuite attributes.
            int finalTests = Math.max(attrTests, nodeCounts[0]);
            int finalFailures = Math.max(attrFailures, nodeCounts[1]);
            int finalErrors = Math.max(attrErrors, nodeCounts[2]);
            int finalSkipped = Math.max(attrSkipped, nodeCounts[3]);
            results.addToTotals(finalTests, finalFailures, finalErrors, finalSkipped);
        }

        // Standalone testcases without a wrapping testsuite — count them only.
        if (suiteNodes.getLength() == 0)
        {
            NodeList caseNodes = doc.getElementsByTagName("testcase"); //$NON-NLS-1$
            results.setTotal(caseNodes.getLength());
        }

        return results;
    }

    /**
     * Scans every {@code <testcase>} of a {@code <testsuite>}: records each failure / error / skipped
     * child into {@code results} and returns the per-suite fallback counts
     * {@code {caseCount, failures, errors, skipped}} — the inner testcase loop of {@link #parseDocument},
     * extracted verbatim. The counts let the caller reconcile against the suite-level attributes.
     */
    private static int[] countSuiteCases(Element suite, JUnitTestResults results)
    {
        int nodeFailures = 0;
        int nodeErrors = 0;
        int nodeSkipped = 0;

        NodeList caseNodes = suite.getElementsByTagName("testcase"); //$NON-NLS-1$
        for (int j = 0; j < caseNodes.getLength(); j++)
        {
            Element testCase = (Element) caseNodes.item(j);
            String className = testCase.getAttribute("classname"); //$NON-NLS-1$
            String testName = testCase.getAttribute("name"); //$NON-NLS-1$
            String fullName = (className != null && !className.isEmpty())
                    ? className + "." + testName //$NON-NLS-1$
                    : testName;

            NodeList failureNodes = testCase.getElementsByTagName("failure"); //$NON-NLS-1$
            if (failureNodes.getLength() > 0)
            {
                nodeFailures++;
                Element failure = (Element) failureNodes.item(0);
                results.addFailure(new JUnitTestResults.TestCase(fullName,
                        failure.getAttribute(MESSAGE),
                        failure.getTextContent()));
            }

            NodeList errorNodes = testCase.getElementsByTagName("error"); //$NON-NLS-1$
            if (errorNodes.getLength() > 0)
            {
                nodeErrors++;
                Element error = (Element) errorNodes.item(0);
                results.addError(new JUnitTestResults.TestCase(fullName,
                        error.getAttribute(MESSAGE),
                        error.getTextContent()));
            }

            NodeList skippedNodes = testCase.getElementsByTagName("skipped"); //$NON-NLS-1$
            if (skippedNodes.getLength() > 0)
            {
                nodeSkipped++;
                Element skip = (Element) skippedNodes.item(0);
                results.addSkipped(new JUnitTestResults.TestCase(fullName,
                        skip.getAttribute(MESSAGE),
                        null));
            }
        }
        return new int[] {caseNodes.getLength(), nodeFailures, nodeErrors, nodeSkipped};
    }

    private static DocumentBuilder newSecureBuilder() throws Exception // NOSONAR propagates checked exceptions across the reflective boundary by design
    {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // Harden XML parsing against XXE/SSRF and entity-expansion attacks.
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); //$NON-NLS-1$
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false); //$NON-NLS-1$
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false); //$NON-NLS-1$
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false); //$NON-NLS-1$
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); //$NON-NLS-1$
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, ""); //$NON-NLS-1$
        return factory.newDocumentBuilder();
    }

    private static int getIntAttr(Element element, String attr, int defaultValue)
    {
        String value = element.getAttribute(attr);
        if (value == null || value.isEmpty())
        {
            return defaultValue;
        }
        try
        {
            return Integer.parseInt(value);
        }
        catch (NumberFormatException e)
        {
            return defaultValue;
        }
    }
}
