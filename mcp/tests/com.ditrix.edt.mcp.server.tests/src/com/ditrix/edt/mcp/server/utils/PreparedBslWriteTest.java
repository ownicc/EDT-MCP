package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;
import org.junit.Test;

public class PreparedBslWriteTest
{
    @Test
    public void acceptedBytesAreDetachedFromBothCallerAndConsumer()
    {
        byte[] bytes = { 1, 2, 3 };
        PreparedBslWrite write = new PreparedBslWrite("p", "file:/p", 0, "a.bsl", "file:/p/src/a.bsl",
            false, "", "", "new", bytes, 0, 1, "replace", false, "");
        bytes[0] = 9;
        byte[] consumed = write.outputBytes();
        assertArrayEquals(new byte[] { 1, 2, 3 }, consumed);
        consumed[1] = 9;
        assertArrayEquals(new byte[] { 1, 2, 3 }, write.outputBytes());
    }

    @Test
    public void rawFingerprintDistinguishesBomAndLineEndings()
    {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            PreparedBslWrite.sha256(new byte[] { 97, 98, 99 }));
        assertNotEquals(PreparedBslWrite.sha256(new byte[] { 97, 10 }),
            PreparedBslWrite.sha256(new byte[] { 97, 13, 10 }));
        assertNotEquals(PreparedBslWrite.sha256(new byte[] { 97 }),
            PreparedBslWrite.sha256(new byte[] { (byte) 239, (byte) 187, (byte) 191, 97 }));
    }
}
