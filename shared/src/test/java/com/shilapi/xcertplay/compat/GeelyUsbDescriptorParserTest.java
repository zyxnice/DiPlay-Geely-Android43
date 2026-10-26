package com.shilapi.xcertplay.compat;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import static org.junit.Assert.*;

/** Synthetic USB wire descriptors: no car, USB device, credentials or privileged operation required. */
public final class GeelyUsbDescriptorParserTest {
    @Test public void preservesAllConfigurationsAlternatesAndBootHid() {
        byte[] hid = usbInterface(0, 0, 3, 1, 1, endpoint(0x81, 3));
        byte[] mux = usbInterface(1, 0, 255, 254, 2, endpoint(0x82, 2), endpoint(2, 2));
        byte[] alternate = usbInterface(1, 1, 255, 254, 2, endpoint(0x83, 2), endpoint(3, 2));
        byte[] iap = usbInterface(0, 0, 255, 253, 1, endpoint(0x84, 2), endpoint(4, 2));
        GeelyUsbDescriptorParser.ParsedDevice parsed = GeelyUsbDescriptorParser.parse(
                device(0x05ac, configuration(1, 2, hid, mux, alternate), configuration(5, 1, iap)));
        assertTrue(parsed.hasAppleTransport());
        assertEquals(2, parsed.configurations.size());
        assertEquals(3, parsed.configuration(1).interfaces.size());
        assertEquals(3, parsed.configuration(1).interfaces.get(0).interfaceClass);
        assertEquals(1, parsed.configuration(1).interfaces.get(2).alternateSetting);
        assertEquals(0x83, parsed.configuration(1).interfaces.get(2).endpoints.get(0).address);
        assertTrue(parsed.configuration(5).hasAppleTransport());
        assertNull(parsed.configuration(9));
    }

    @Test public void completeDescriptorsAreNotTruncatedAt4096() {
        byte[] padding = new byte[250]; padding[0] = (byte)250; padding[1] = 0x24;
        byte[][] pieces = new byte[25][];
        pieces[0] = usbInterface(0, 0, 255, 254, 2, endpoint(0x81, 2), endpoint(1, 2));
        for (int i = 1; i < pieces.length; i++) pieces[i] = padding;
        byte[] raw = device(0x05ac, configuration(1, 1, pieces));
        assertTrue(raw.length > 4096);
        GeelyUsbDescriptorParser.ParsedDevice parsed = GeelyUsbDescriptorParser.parse(raw);
        assertArrayEquals(raw, parsed.rawDescriptors());
        byte[] copy = parsed.rawDescriptors(); copy[0] = 0;
        assertEquals(18, parsed.rawDescriptors()[0]);
    }

    @Test public void doesNotTreatAppleHidOrOtherVendorsAsPhoneTransport() {
        assertFalse(GeelyUsbDescriptorParser.parse(device(0x05ac,
                configuration(1, 1, usbInterface(0, 0, 3, 1, 1, endpoint(0x81, 3))))).hasAppleTransport());
        assertFalse(GeelyUsbDescriptorParser.parse(device(0x1234,
                configuration(1, 1, mux()))).hasAppleTransport());
        assertFalse(GeelyUsbDescriptorParser.parse(device(0x05ac,
                configuration(1, 1, usbInterface(0, 0, 255, 254, 1)))).hasAppleTransport());
    }

    @Test public void rejectsTruncationAndInconsistentCounts() {
        byte[] valid = device(0x05ac, configuration(1, 1, mux()));
        for (int length = 0; length < valid.length; length++) invalid(Arrays.copyOf(valid, length));
        byte[] raw = valid.clone(); raw[17] = 2; invalid(raw);
        raw = valid.clone(); raw[22] = 2; invalid(raw); // bNumInterfaces
        raw = valid.clone(); raw[31] = 1; invalid(raw); // bNumEndpoints (actually two)
        raw = valid.clone(); raw[20] = 8; raw[21] = 0; invalid(raw); // short wTotalLength
        raw = valid.clone(); raw[27] = 0; invalid(raw); // zero bLength
        raw = valid.clone(); raw[36] = 20; invalid(raw); // endpoint crosses configuration boundary
        raw = Arrays.copyOf(valid, valid.length + 1); invalid(raw);
        invalid(new byte[GeelyUsbDescriptorParser.MAX_DESCRIPTOR_BYTES + 1]);
    }

    @Test public void rejectsDuplicateAndMisplacedStructure() {
        invalid(device(0x05ac, configuration(1, 1, mux()), configuration(1, 1, mux())));
        invalid(device(0x05ac, configuration(0, 1, mux())));
        invalid(device(0x05ac, configuration(1, 1, mux(), mux())));
        invalid(device(0x05ac, configuration(1, 1, usbInterface(0, 1, 255, 254, 2))));
        invalid(device(0x05ac, configuration(1, 0, endpoint(0x81, 2))));
        invalid(device(0x05ac, configuration(1, 1,
                usbInterface(0, 0, 255, 254, 2, endpoint(0x81, 2), endpoint(0x81, 2)))));
        invalid(device(0x05ac, configuration(1, 1,
                usbInterface(0, 0, 255, 254, 2, endpoint(0, 2)))));
    }

    @Test public void parsedListsCannotBeChangedByCallers() {
        GeelyUsbDescriptorParser.ParsedDevice parsed = GeelyUsbDescriptorParser.parse(device(0x05ac, configuration(1, 1, mux())));
        try { parsed.configurations.clear(); fail(); } catch (UnsupportedOperationException expected) { }
        try { parsed.configuration(1).interfaces.clear(); fail(); } catch (UnsupportedOperationException expected) { }
        try { parsed.configuration(1).interfaces.get(0).endpoints.clear(); fail(); } catch (UnsupportedOperationException expected) { }
    }

    private static void invalid(byte[] raw) {
        try { GeelyUsbDescriptorParser.parse(raw); fail("Accepted malformed descriptors length=" + raw.length); }
        catch (IllegalArgumentException expected) { }
    }
    private static byte[] mux() { return usbInterface(0, 0, 255, 254, 2, endpoint(0x81, 2), endpoint(1, 2)); }
    private static byte[] device(int vendor, byte[]... configurations) {
        byte[] header = {18, 1, 0, 2, 0, 0, 0, 64, (byte)vendor, (byte)(vendor >> 8), 0x34, 0x12, 0, 1, 1, 2, 3, (byte)configurations.length};
        return concat(header, concat(configurations));
    }
    private static byte[] configuration(int value, int count, byte[]... body) {
        byte[] payload = concat(body); int length = 9 + payload.length;
        return concat(new byte[]{9, 2, (byte)length, (byte)(length >> 8), (byte)count, (byte)value, 0, (byte)0x80, 50}, payload);
    }
    private static byte[] usbInterface(int number, int alt, int cls, int sub, int protocol, byte[]... endpoints) {
        return concat(new byte[]{9, 4, (byte)number, (byte)alt, (byte)endpoints.length, (byte)cls, (byte)sub, (byte)protocol, 0}, concat(endpoints));
    }
    private static byte[] endpoint(int address, int attributes) { return new byte[]{7, 5, (byte)address, (byte)attributes, 64, 0, 0}; }
    private static byte[] concat(byte[]... arrays) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] array : arrays) output.write(array, 0, array.length);
        return output.toByteArray();
    }
}
