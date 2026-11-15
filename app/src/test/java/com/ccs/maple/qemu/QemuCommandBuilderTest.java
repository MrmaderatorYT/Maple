package com.ccs.maple.qemu;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class QemuCommandBuilderTest {
    @Test
    public void formatForNameMapsKnownExtensions() {
        assertEquals("qcow2", QemuCommandBuilder.formatForName("disk.qcow2"));
        assertEquals("vhdx", QemuCommandBuilder.formatForName("WIN.VHDX"));
        assertEquals("vpc", QemuCommandBuilder.formatForName("old.vhd"));
        assertEquals("vmdk", QemuCommandBuilder.formatForName("box.vmdk"));
        assertEquals("vdi", QemuCommandBuilder.formatForName("box.vdi"));
        assertEquals("parallels", QemuCommandBuilder.formatForName("mac.hdd"));
        assertEquals("dmg", QemuCommandBuilder.formatForName("disk.dmg"));
        assertEquals("raw", QemuCommandBuilder.formatForName("usb.img"));
    }

    @Test
    public void unknownExtensionLetsQemuProbe() {
        assertNull(QemuCommandBuilder.formatForName("notes.txt"));
        assertNull(QemuCommandBuilder.formatForName(null));
    }
}
