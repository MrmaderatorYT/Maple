package com.ccs.maple.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class IoTest {
    @Test
    public void humanSizeUsesBinaryUnits() {
        assertEquals("500 B", Io.humanSize(500));
        assertEquals("1.5 KB", Io.humanSize(1536));
        assertEquals("3.0 GB", Io.humanSize(3L << 30));
    }

    @Test
    public void hexIsLowercaseAndPadded() {
        assertEquals("00ff10", Io.hex(new byte[]{0, (byte) 0xff, 0x10}));
    }
}
