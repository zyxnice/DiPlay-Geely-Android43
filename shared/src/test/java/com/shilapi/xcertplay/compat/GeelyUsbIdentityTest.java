package com.shilapi.xcertplay.compat;

import org.junit.Test;
import static org.junit.Assert.*;

public final class GeelyUsbIdentityTest {
    private static final String SHA = GeelyUsbIdentity.sha256(new byte[]{1, 2, 3});
    @Test public void canonicalPathAndCompleteEnumerationEquality() {
        GeelyUsbIdentity identity = identity("1-2.3", 1, 7, SHA);
        assertEquals("/dev/bus/usb/001/007", identity.devicePath());
        assertEquals(identity, identity("1-2.3", 1, 7, SHA));
        assertEquals(identity.hashCode(), identity("1-2.3", 1, 7, SHA).hashCode());
        assertNotEquals(identity, identity("1-2.3", 1, 8, SHA));
        assertNotEquals(identity, identity("1-2.4", 1, 7, SHA));
        assertNotEquals(identity, identity("1-2.3", 1, 7, GeelyUsbIdentity.sha256(new byte[]{3, 2, 1})));
        assertEquals("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81", SHA);
    }
    @Test public void rejectsPathInjectionInvalidUsbNumbersAndHashes() {
        for (String name : new String[]{"../1-2", "1-2/../../../dev", "1-2:1.0", "usb1", "0-1", "1-0", "01-2", "1-2;id", "1-2\n", "1-2..3"})
            invalid(name, 1, 7, SHA);
        invalid("2-1", 1, 7, SHA);
        invalid("1-2", 0, 7, SHA);
        invalid("1-2", 1, 0, SHA);
        invalid("1-2", 1, 128, SHA);
        invalid("1-2", 1, 7, "");
        invalid("1-2", 1, 7, SHA.toUpperCase(java.util.Locale.US));
        invalid("1-2", 1, 7, null);
    }
    private static GeelyUsbIdentity identity(String name, int bus, int dev, String hash) {
        return new GeelyUsbIdentity(name, bus, dev, 0x05ac, 0x1234, hash);
    }
    private static void invalid(String name, int bus, int dev, String hash) {
        try { identity(name, bus, dev, hash); fail("Accepted invalid USB identity"); }
        catch (IllegalArgumentException expected) { }
    }
}
