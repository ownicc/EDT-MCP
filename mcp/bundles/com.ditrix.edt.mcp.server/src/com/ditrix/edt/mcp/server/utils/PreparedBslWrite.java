/**
 * MCP Server for EDT
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** A detached proposal: no workspace handle or model transaction survives human review. */
public record PreparedBslWrite(String projectName, String projectLocation, long projectToken,
    String modulePath, String fileLocation, boolean baseExists, String baseHash,
    String baseText, String proposedText, byte[] outputBytes, int linesBefore, int linesAfter,
    String mode, boolean skipSyntaxCheck, String normalizedCharacters)
{
    public PreparedBslWrite
    {
        outputBytes = outputBytes.clone();
    }

    @Override
    public byte[] outputBytes()
    {
        return outputBytes.clone();
    }

    public long payloadBytes()
    {
        return (long)baseText.getBytes(StandardCharsets.UTF_8).length
            + proposedText.getBytes(StandardCharsets.UTF_8).length + outputBytes.length;
    }

    /** Full raw-byte fingerprint, separate from the public normalized ContentHash contract. */
    public static String sha256(byte[] bytes)
    {
        try
        {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); //$NON-NLS-1$
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException("SHA-256 is unavailable", e); //$NON-NLS-1$
        }
    }
}
