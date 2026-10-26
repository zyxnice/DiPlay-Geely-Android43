package com.shilapi.xcertplay.compat;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import static org.junit.Assert.*;

/** Host checks cover the actual restricted decoder and command builder, without starting a helper. */
public final class GeelyUsbFdProtocolTest {
    private static final String HEX = "0123456789abcdef0123456789abcdef";

    @Test public void cliAndBinaryProtocolRoundTripTheCompleteEnumeration() throws Exception {
        GeelyUsbFdHelper.Request request = request(0);
        assertTrue(request.matches(GeelyUsbFdHelper.Request.parse(request.arguments())));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        GeelyUsbFdHelper.writeRequest(new DataOutputStream(bytes), request);
        GeelyUsbFdHelper.Request decoded = GeelyUsbFdHelper.readRequest(
                new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())), request.socketName, request.appUid);
        assertTrue(request.matches(decoded));
        assertFalse(request.matches(request(5)));
    }

    @Test public void configurationRequiresNonzeroValueAndMatchingOperation() throws Exception {
        assertEquals(5, GeelyUsbFdHelper.Request.parse(request(5).arguments()).configuration);
        String[] mismatched = request(0).arguments();
        mismatched[0] = "config";
        rejectsArguments(mismatched);
        mismatched = request(5).arguments();
        mismatched[0] = "open";
        rejectsArguments(mismatched);
        mismatched = request(5).arguments();
        mismatched[10] = "256";
        rejectsArguments(mismatched);
    }

    @Test public void cliRejectsUnknownOperationPathsAndNonAppleVendors() {
        String[][] changes = {{"0", "exec"}, {"1", "../socket"}, {"2", "short"}, {"3", "0"},
                {"4", "1-2/../../other"}, {"5", "1;id"}, {"6", "-1"}, {"7", "65535"},
                {"8", "$(id)"}, {"9", HEX}, {"10", "+1"}};
        for (String[] change : changes) {
            String[] arguments = request(0).arguments();
            arguments[Integer.parseInt(change[0])] = change[1];
            rejectsArguments(arguments);
        }
        rejectsArguments(new String[] {"open"});
    }

    @Test public void binaryDecoderRejectsUnknownVersionAndTruncatedIdentity() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        GeelyUsbFdHelper.writeRequest(new DataOutputStream(bytes), request(0));
        byte[] wire = bytes.toByteArray();
        wire[7] = 2;
        rejectsWire(wire);
        rejectsWire(Arrays.copyOf(wire, 9));
    }

    @Test public void identityComparisonChecksNonceAndEveryEnumerationField() {
        GeelyUsbFdHelper.Request expected = request(0);
        GeelyUsbIdentity changed = new GeelyUsbIdentity("1-2", 1, 3, 0x05ac, 0x12a8, expected.identity.descriptorSha256);
        assertFalse(expected.matches(new GeelyUsbFdHelper.Request(expected.socketName, HEX, 10123, changed, 0)));
        assertFalse(expected.matches(new GeelyUsbFdHelper.Request(expected.socketName,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 10123, expected.identity, 0)));
        assertFalse(expected.matches(new GeelyUsbFdHelper.Request(expected.socketName, HEX, 10124, expected.identity, 0)));
    }

    @Test public void descriptorsMustMatchTheFdIdentityAndContainAppleTransport() throws Exception {
        byte[] raw = descriptors();
        GeelyUsbIdentity identity = request(0).identity;
        assertTrue(GeelyUsbFdHelper.verifyDescriptors(identity, raw).hasAppleTransport());
        byte[] changedProduct = raw.clone(); changedProduct[10] = 1;
        rejectsDescriptors(identity, changedProduct);
        byte[] changedTransport = raw.clone(); changedTransport[32] = 3;
        GeelyUsbIdentity hid = new GeelyUsbIdentity("1-2", 1, 2, 0x05ac, 0x12a8, GeelyUsbIdentity.sha256(changedTransport));
        rejectsDescriptors(hid, changedTransport);
        byte[] changedHash = raw.clone(); changedHash[16] = 1;
        rejectsDescriptors(identity, changedHash);
    }

    @Test public void boundedReaderRejectsOverflowAndPreservesExactBytes() throws Exception {
        byte[] bytes = new byte[8193];
        assertArrayEquals(bytes, GeelyUsbFdHelper.readLimited(new ByteArrayInputStream(bytes), bytes.length));
        try {
            GeelyUsbFdHelper.readLimited(new ByteArrayInputStream(bytes), bytes.length - 1);
            fail("Oversized response accepted");
        } catch (IOException expected) { assertEquals("descriptor_limit", expected.getMessage()); }
    }

    @Test public void launcherOnlyBuildsFixedHelperAndQuotesEveryArgument() throws Exception {
        String command = GeelyUsbOemBridge.buildCommand(new File("/data/data/test/files/geely-usb/helper.dex"),
                new File("/data/data/test/files/geely-usb/runtime"), request(0));
        assertTrue(command.contains("/system/bin/app_process /system/bin " + GeelyUsbFdHelper.ENTRY_POINT));
        assertTrue(command.contains("'open' 'geely_usb_" + HEX + "' '" + HEX + "'"));
        assertTrue(command.getBytes(GeelyUsbFdHelper.UTF8).length < 900);
        assertEquals("'a'\\''b;$(id)'", GeelyUsbOemBridge.shellQuote("a'b;$(id)"));
        try { GeelyUsbOemBridge.shellQuote("bad\nvalue"); fail("newline accepted"); }
        catch (IOException expected) { assertEquals("shell_argument", expected.getMessage()); }
    }

    @Test public void launcherRejectsArbitraryPathsAndNativeBufferOverflow() throws Exception {
        try {
            GeelyUsbOemBridge.buildCommand(new File("/system/other.dex"), new File("/system/runtime"), request(0));
            fail("arbitrary entry path accepted");
        } catch (IOException expected) { assertEquals("helper_private_path", expected.getMessage()); }
        StringBuilder path = new StringBuilder("/data/");
        for (int i = 0; i < 700; i++) path.append('x');
        path.append("/geely-usb/");
        try {
            GeelyUsbOemBridge.buildCommand(new File(path + "helper.dex"), new File(path + "runtime"), request(0));
            fail("oversized native command accepted");
        } catch (IOException expected) { assertEquals("oem_command_limit", expected.getMessage()); }
    }

    private static GeelyUsbFdHelper.Request request(int configuration) {
        byte[] raw = descriptors();
        return new GeelyUsbFdHelper.Request("geely_usb_" + HEX, HEX, 10123,
                new GeelyUsbIdentity("1-2", 1, 2, 0x05ac, 0x12a8, GeelyUsbIdentity.sha256(raw)), configuration);
    }

    private static byte[] descriptors() {
        return new byte[] {18, 1, 0, 2, 0, 0, 0, 64, (byte)0xac, 5, (byte)0xa8, 0x12, 0, 1, 0, 0, 0, 1,
                9, 2, 32, 0, 1, 5, 0, (byte)0x80, 50,
                9, 4, 0, 0, 2, (byte)255, (byte)254, 2, 0,
                7, 5, (byte)0x81, 2, 0, 2, 0, 7, 5, 1, 2, 0, 2, 0};
    }

    private static void rejectsArguments(String[] arguments) {
        try { GeelyUsbFdHelper.Request.parse(arguments); fail("Unsafe CLI accepted"); }
        catch (IllegalArgumentException expected) { }
    }

    private static void rejectsWire(byte[] wire) throws Exception {
        try {
            GeelyUsbFdHelper.readRequest(new DataInputStream(new ByteArrayInputStream(wire)), "geely_usb_" + HEX, 10123);
            fail("Malformed protocol accepted");
        } catch (IOException expected) { }
    }

    private static void rejectsDescriptors(GeelyUsbIdentity identity, byte[] descriptors) throws Exception {
        try { GeelyUsbFdHelper.verifyDescriptors(identity, descriptors); fail("Mismatched FD descriptors accepted"); }
        catch (IOException expected) { }
    }
}
