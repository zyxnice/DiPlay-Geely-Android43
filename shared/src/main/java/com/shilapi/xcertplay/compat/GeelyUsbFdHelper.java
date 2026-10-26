package com.shilapi.xcertplay.compat;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.ParcelFileDescriptor;
import android.os.Process;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;

/** Short-lived API-18 helper. Only the fixed Apple open/config protocol is accepted. */
public final class GeelyUsbFdHelper {
    static final int MAGIC = 0x47554644;
    static final int VERSION = 1;
    static final int DEADLINE_MS = 8000;
    static final Charset UTF8 = Charset.forName("UTF-8");
    static final String ENTRY_POINT = "com.shilapi.xcertplay.compat.GeelyUsbFdHelper";

    private GeelyUsbFdHelper() { }

    /** CLI is intentionally paths-free: operation/socket/nonce/uid/identity/configuration. */
    public static void main(String[] args) {
        Thread expiry = new Thread(new Runnable() {
            @Override public void run() {
                try { Thread.sleep(DEADLINE_MS); } catch (InterruptedException ignored) { }
                Runtime.getRuntime().halt(2);
            }
        }, "geely-usb-helper-expiry");
        expiry.setDaemon(true);
        expiry.start();
        try {
            Request request = Request.parse(args);
            if (Process.myUid() != 0) throw new IOException("helper_uid");
            serve(request);
        } catch (Exception failure) {
            // Only short diagnostic categories enter the OEM text response; no nonce or paths.
            String reason = failure.getMessage();
            System.out.println("GEELY_USB_HELPER_FAILED:"
                    + (reason != null && reason.matches("[a-z_]{1,64}") ? reason : "helper_failure"));
        }
        // app_process is a one-shot child of the OEM daemon, never a persistent service.
        System.exit(0);
    }

    private static void serve(Request expected) throws IOException {
        LocalSocket socket = new LocalSocket();
        try {
            socket.connect(new LocalSocketAddress(expected.socketName, LocalSocketAddress.Namespace.ABSTRACT));
            socket.setSoTimeout(DEADLINE_MS - 1000);
            if (socket.getPeerCredentials().getUid() != expected.appUid) throw new IOException("app_uid");
            DataInputStream input = new DataInputStream(inputWithoutDescriptors(socket));
            if (!expected.matches(readRequest(input, expected.socketName, expected.appUid))) {
                throw new IOException("request_identity");
            }
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            ParcelFileDescriptor descriptor = null;
            try {
                File deviceDirectory = verifySysfs(expected.identity);
                File node = new File(expected.identity.devicePath());
                if (!node.getCanonicalPath().equals(expected.identity.devicePath())) throw new IOException("usb_node_path");
                descriptor = ParcelFileDescriptor.open(node, ParcelFileDescriptor.MODE_READ_WRITE);
                byte[] descriptors;
                // dup owns a separate Java close handle; never wrap and close the original FD.
                FileInputStream descriptorInput = new ParcelFileDescriptor.AutoCloseInputStream(
                        ParcelFileDescriptor.dup(descriptor.getFileDescriptor()));
                try {
                    descriptors = readLimited(descriptorInput, GeelyUsbDescriptorParser.MAX_DESCRIPTOR_BYTES);
                    // dup shares the open-file offset. libusbhost must receive descriptor bytes at offset 0.
                    descriptorInput.getChannel().position(0);
                }
                finally { descriptorInput.close(); }
                GeelyUsbDescriptorParser.ParsedDevice parsed = verifyDescriptors(expected.identity, descriptors);
                if (!verifySysfs(expected.identity).equals(deviceDirectory)) throw new IOException("usb_reenumerated");
                if (expected.configuration != 0) {
                    GeelyUsbDescriptorParser.Configuration configuration = parsed.configuration(expected.configuration);
                    if (configuration == null || !configuration.hasAppleTransport()) throw new IOException("configuration_transport");
                    writeConfiguration(deviceDirectory, expected.identity, expected.configuration);
                }
                if (expected.configuration == 0) {
                    socket.setFileDescriptorsForSend(new java.io.FileDescriptor[] {descriptor.getFileDescriptor()});
                }
                // SCM_RIGHTS belongs to this single byte. Do not buffer it with the header.
                try { output.write(1); output.flush(); }
                finally { socket.setFileDescriptorsForSend(null); }
                writeRequest(output, expected);
                output.writeInt(expected.configuration == 0 ? descriptors.length : 0);
                if (expected.configuration == 0) output.write(descriptors);
                output.flush();
            } catch (IOException failure) {
                socket.setFileDescriptorsForSend(null);
                throw failure;
            } finally {
                if (descriptor != null) descriptor.close();
            }
        } finally { socket.close(); }
    }

    private static File verifySysfs(GeelyUsbIdentity identity) throws IOException {
        if (identity.vendorId != 0x05ac) throw new IOException("not_apple");
        File directory = new File("/sys/bus/usb/devices", identity.sysfsName).getCanonicalFile();
        if (!directory.getPath().startsWith("/sys/devices/") || !directory.isDirectory()) throw new IOException("sysfs_path");
        if (readNumber(directory, "busnum", 10) != identity.busNumber
                || readNumber(directory, "devnum", 10) != identity.deviceNumber
                || readNumber(directory, "idVendor", 16) != identity.vendorId
                || readNumber(directory, "idProduct", 16) != identity.productId
                || !readText(directory, "devpath").equals(identity.sysfsName.substring(identity.sysfsName.indexOf('-') + 1))) {
            throw new IOException("sysfs_identity");
        }
        InputStream descriptors = new FileInputStream(child(directory, "descriptors"));
        try { verifyDescriptors(identity, readLimited(descriptors, GeelyUsbDescriptorParser.MAX_DESCRIPTOR_BYTES)); }
        finally { descriptors.close(); }
        return directory;
    }

    private static void writeConfiguration(File directory, GeelyUsbIdentity identity, int value) throws IOException {
        // No caller-provided path or payload reaches this write.
        if (!verifySysfs(identity).equals(directory)) throw new IOException("configuration_identity");
        FileOutputStream output = new FileOutputStream(child(directory, "bConfigurationValue"));
        try { output.write(Integer.toString(value).getBytes(UTF8)); output.flush(); }
        finally { output.close(); }
        if (readNumber(directory, "bConfigurationValue", 10) != value) throw new IOException("configuration_readback");
        if (!verifySysfs(identity).equals(directory)) throw new IOException("configuration_reenumerated");
    }

    private static File child(File directory, String name) throws IOException {
        File file = new File(directory, name).getCanonicalFile();
        if (!directory.equals(file.getParentFile())) throw new IOException("sysfs_child");
        return file;
    }

    private static String readText(File directory, String name) throws IOException {
        InputStream input = new FileInputStream(child(directory, name));
        try { return new String(readLimited(input, 128), UTF8).trim(); }
        finally { input.close(); }
    }

    private static int readNumber(File directory, String name, int radix) throws IOException {
        String text = readText(directory, name);
        if (!(radix == 16 ? text.matches("[0-9a-fA-F]{1,4}") : text.matches("[0-9]{1,3}"))) throw new IOException("sysfs_number");
        try { return Integer.parseInt(text, radix); }
        catch (NumberFormatException invalid) { throw new IOException("sysfs_number", invalid); }
    }

    static byte[] readLimited(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (count == 0) throw new IOException("empty_read");
            if (count > limit - output.size()) throw new IOException("descriptor_limit");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    /** After the success byte, every recv must reject/close ancillary FDs before the next recv. */
    static InputStream inputWithoutDescriptors(final LocalSocket socket) throws IOException {
        return new FilterInputStream(socket.getInputStream()) {
            private void rejectDescriptors() throws IOException {
                FileDescriptor[] unexpected = socket.getAncillaryFileDescriptors();
                if (unexpected == null || unexpected.length == 0) return;
                for (FileDescriptor descriptor : unexpected) {
                    if (descriptor != null) try { new FileInputStream(descriptor).close(); }
                    catch (IOException ignored) { }
                }
                throw new IOException("usb_helper_extra_fd");
            }

            @Override public int read() throws IOException {
                int value = in.read();
                rejectDescriptors();
                return value;
            }

            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                int count = in.read(bytes, offset, length);
                rejectDescriptors();
                return count;
            }
        };
    }

    static GeelyUsbDescriptorParser.ParsedDevice verifyDescriptors(GeelyUsbIdentity identity, byte[] bytes) throws IOException {
        try {
            GeelyUsbDescriptorParser.ParsedDevice parsed = GeelyUsbDescriptorParser.parse(bytes);
            if (parsed.vendorId != identity.vendorId || parsed.productId != identity.productId
                    || !parsed.hasAppleTransport() || !GeelyUsbIdentity.sha256(bytes).equals(identity.descriptorSha256)) {
                throw new IOException("descriptor_identity");
            }
            return parsed;
        } catch (IllegalArgumentException invalid) { throw new IOException("descriptor_invalid", invalid); }
    }

    static void writeRequest(DataOutputStream output, Request request) throws IOException {
        output.writeInt(MAGIC);
        output.writeInt(VERSION);
        output.writeUTF(request.nonce);
        GeelyUsbIdentity identity = request.identity;
        output.writeUTF(identity.sysfsName);
        output.writeInt(identity.busNumber);
        output.writeInt(identity.deviceNumber);
        output.writeInt(identity.vendorId);
        output.writeInt(identity.productId);
        output.writeUTF(identity.descriptorSha256);
        output.writeInt(request.configuration);
    }

    static Request readRequest(DataInputStream input, String socketName, int uid) throws IOException {
        if (input.readInt() != MAGIC || input.readInt() != VERSION) throw new IOException("protocol_version");
        String nonce = input.readUTF();
        String name = input.readUTF();
        int bus = input.readInt();
        int device = input.readInt();
        int vendor = input.readInt();
        int product = input.readInt();
        String hash = input.readUTF();
        int configuration = input.readInt();
        try {
            return new Request(socketName, nonce, uid,
                    new GeelyUsbIdentity(name, bus, device, vendor, product, hash), configuration);
        } catch (IllegalArgumentException invalid) { throw new IOException("protocol_identity", invalid); }
    }

    static final class Request {
        final String socketName;
        final String nonce;
        final int appUid;
        final GeelyUsbIdentity identity;
        final int configuration;

        Request(String socketName, String nonce, int appUid, GeelyUsbIdentity identity, int configuration) {
            if (socketName == null || !socketName.matches("geely_usb_[0-9a-f]{32}")
                    || nonce == null || !nonce.matches("[0-9a-f]{32}") || appUid < 10000
                    || identity == null || identity.vendorId != 0x05ac || configuration < 0 || configuration > 255) {
                throw new IllegalArgumentException("Invalid restricted helper request");
            }
            this.socketName = socketName;
            this.nonce = nonce;
            this.appUid = appUid;
            this.identity = identity;
            this.configuration = configuration;
        }

        boolean matches(Request other) {
            return appUid == other.appUid && socketName.equals(other.socketName) && nonce.equals(other.nonce)
                    && identity.equals(other.identity) && configuration == other.configuration;
        }

        String[] arguments() {
            return new String[] {configuration == 0 ? "open" : "config", socketName, nonce,
                    Integer.toString(appUid), identity.sysfsName, Integer.toString(identity.busNumber),
                    Integer.toString(identity.deviceNumber), Integer.toString(identity.vendorId),
                    Integer.toString(identity.productId), identity.descriptorSha256, Integer.toString(configuration)};
        }

        static Request parse(String[] args) {
            if (args == null || args.length != 11 || !("open".equals(args[0]) || "config".equals(args[0]))) {
                throw new IllegalArgumentException("Invalid helper operation");
            }
            for (int position : new int[] {3, 5, 6, 7, 8, 10}) {
                if (args[position] == null || !args[position].matches("0|[1-9][0-9]{0,9}")) throw new IllegalArgumentException("Invalid numeric argument");
            }
            Request request = new Request(args[1], args[2], Integer.parseInt(args[3]),
                    new GeelyUsbIdentity(args[4], Integer.parseInt(args[5]), Integer.parseInt(args[6]),
                            Integer.parseInt(args[7]), Integer.parseInt(args[8]), args[9]), Integer.parseInt(args[10]));
            if ((request.configuration == 0) != "open".equals(args[0])) throw new IllegalArgumentException("Invalid operation configuration");
            return request;
        }
    }
}
