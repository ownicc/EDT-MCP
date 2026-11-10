/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.proxy;

/**
 * The proxy's own version string, shared by everything that needs to report it: the MCP
 * {@code initialize} response ({@code McpProxyHandler}) and the {@code --version} CLI flag
 * ({@code CliCommands}).
 *
 * <p>Read from the running jar's manifest {@code Implementation-Version} entry, same as any
 * other {@code Package#getImplementationVersion()} lookup. Maven writes the project version
 * into the packaged jar; release builds supply it through {@code -Drevision=X.Y.Z}.
 * The lookup falls back to {@link #FALLBACK_VERSION} when the entry is absent, including
 * when running from a test/IDE classpath with no jar.
 */
public final class ProxyVersion
{
    private static final String FALLBACK_VERSION = "dev"; //$NON-NLS-1$

    private ProxyVersion()
    {
        // utility class
    }

    /**
     * Returns the proxy's version.
     *
     * @return the {@code Implementation-Version} manifest entry of the jar this class was
     *         loaded from, or {@value #FALLBACK_VERSION} when there is none (not running from a
     *         packaged jar)
     */
    public static String current()
    {
        String version = ProxyVersion.class.getPackage().getImplementationVersion();
        return version != null ? version : FALLBACK_VERSION;
    }
}
