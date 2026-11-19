package com.ccs.maple.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class VmConfigTest {

    @Test
    public void jsonRoundTripKeepsEveryField() throws Exception {
        VmConfig a = new VmConfig();
        a.name = "Ubuntu test";
        a.arch = VmConfig.ARCH_AARCH64;
        a.os = OsType.WIN_11;
        a.firmware = VmConfig.FW_UEFI;
        a.ramMb = 4096;
        a.cpus = 3;
        a.diskFile = "disk.qcow2";
        a.cdPath = "/data/x/ubuntu.iso";
        a.cdIsDiskImage = true;
        a.bootCdFirst = true;
        a.network = false;
        a.portForwards.add("tcp:2222:22");
        a.portForwards.add("udp:5000:5000");
        a.sound = false;
        a.sharedUri = "content://com.android.externalstorage.documents/tree/primary%3AShare";
        a.sharedWritable = true;
        a.profile = VmConfig.PROFILE_SUSTAINED;
        a.displayWidth = 1280;
        a.displayHeight = 720;
        a.scaling = VmConfig.SCALE_INTEGER;
        a.orientation = VmConfig.ORIENT_LANDSCAPE;
        a.pointerSpeed = 250;
        a.inputMode = VmConfig.INPUT_MOUSE;
        a.highRefresh = false;
        a.showHud = true;
        a.showKeys = false;
        a.win11Helper = true;
        a.suspendOnStop = true;
        a.hasSuspendState = true;

        VmConfig b = VmConfig.fromJson(a.toJson());

        assertEquals(a.id, b.id);
        assertEquals(a.name, b.name);
        assertEquals(a.arch, b.arch);
        assertEquals(a.os, b.os);
        assertEquals(a.firmware, b.firmware);
        assertEquals(a.ramMb, b.ramMb);
        assertEquals(a.cpus, b.cpus);
        assertEquals(a.diskFile, b.diskFile);
        assertEquals(a.cdPath, b.cdPath);
        assertEquals(a.cdIsDiskImage, b.cdIsDiskImage);
        assertEquals(a.bootCdFirst, b.bootCdFirst);
        assertEquals(a.network, b.network);
        assertEquals(a.portForwards, b.portForwards);
        assertEquals(a.sound, b.sound);
        assertEquals(a.sharedUri, b.sharedUri);
        assertEquals(a.sharedWritable, b.sharedWritable);
        assertEquals(a.profile, b.profile);
        assertEquals(a.displayWidth, b.displayWidth);
        assertEquals(a.displayHeight, b.displayHeight);
        assertEquals(a.scaling, b.scaling);
        assertEquals(a.orientation, b.orientation);
        assertEquals(a.pointerSpeed, b.pointerSpeed);
        assertEquals(a.inputMode, b.inputMode);
        assertEquals(a.highRefresh, b.highRefresh);
        assertEquals(a.showHud, b.showHud);
        assertEquals(a.showKeys, b.showKeys);
        assertEquals(a.win11Helper, b.win11Helper);
        assertEquals(a.suspendOnStop, b.suspendOnStop);
        assertEquals(a.hasSuspendState, b.hasSuspendState);
    }

    @Test
    public void missingFieldsFallBackToDefaults() {
        VmConfig c = VmConfig.fromJson(new org.json.JSONObject());
        assertEquals(VmConfig.ARCH_X86_64, c.arch);
        assertEquals(OsType.OTHER, c.os);
        assertEquals(1024, c.ramMb);
        assertEquals(VmConfig.PROFILE_BALANCED, c.profile);
        assertTrue(c.network);
        assertTrue(c.portForwards.isEmpty());
    }

    @Test
    public void unknownOsNameDoesNotThrow() {
        assertEquals(OsType.OTHER, OsType.fromName("PLAN9"));
        assertEquals(OsType.OTHER, OsType.fromName(null));
        assertEquals(OsType.LINUX, OsType.fromCatalog("linux"));
        assertEquals(OsType.FREEBSD, OsType.fromCatalog("freebsd"));
        assertEquals(OsType.OTHER, OsType.fromCatalog("macos"));
    }

    @Test
    public void uefiIsForcedOnArm() {
        VmConfig c = new VmConfig();
        c.arch = VmConfig.ARCH_AARCH64;
        c.firmware = VmConfig.FW_BIOS;
        assertTrue(c.isUefi());
    }

    @Test
    public void portForwardsAcceptValidEntries() {
        List<String> f = VmConfig.parsePortForwards("tcp:2222:22, udp:5000:5000");
        assertEquals(java.util.Arrays.asList("tcp:2222:22", "udp:5000:5000"), f);
    }

    @Test
    public void portForwardsDefaultToTcp() {
        assertEquals(java.util.Collections.singletonList("tcp:2222:22"),
                VmConfig.parsePortForwards("2222:22"));
    }

    @Test
    public void portForwardsEmptyInputIsEmptyList() {
        assertTrue(VmConfig.parsePortForwards("").isEmpty());
        assertTrue(VmConfig.parsePortForwards(null).isEmpty());
    }

    @Test
    public void portForwardsRejectPrivilegedHostPorts() {
        assertNull(VmConfig.parsePortForwards("tcp:80:80"));
    }

    @Test
    public void portForwardsRejectBadProtocolAndNumbers() {
        assertNull(VmConfig.parsePortForwards("icmp:2222:22"));
        assertNull(VmConfig.parsePortForwards("tcp:abc:22"));
        assertNull(VmConfig.parsePortForwards("tcp:2222:70000"));
        assertNull(VmConfig.parsePortForwards("tcp:2222"));
    }
}
