package com.shilapi.xcertplay.compat;

import android.content.Context;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.util.Log;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Receives a real USBFS descriptor through an authenticated, one-shot local FD channel. */
public final class GeelyUsbFdBroker {
    private static final String TAG = "GeelyUsbFdBroker";
    private static final Object ASSET_LOCK = new Object();
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Context context;
    private final GeelyUsbOemBridge bridge = new GeelyUsbOemBridge();

    public GeelyUsbFdBroker(Context context) { this.context = context.getApplicationContext(); }

    public boolean isBridgeAvailable() { return bridge.isAvailable(); }

    public OpenedDevice open(GeelyUsbIdentity identity) throws IOException {
        return exchange(identity, 0);
    }

    /** Only a configuration declared with an Apple transport on this enumeration may be selected. */
    public void setConfiguration(GeelyUsbIdentity identity, int configuration) throws IOException {
        if (configuration <= 0 || configuration > 255) throw new IOException("configuration_value");
        exchange(identity, configuration);
    }

    private OpenedDevice exchange(final GeelyUsbIdentity identity, final int configuration) throws IOException {
        if (Looper.myLooper() != null && Looper.myLooper() == Looper.getMainLooper()) throw new IOException("usb_broker_on_ui_thread");
        final GeelyUsbFdHelper.Request request = new GeelyUsbFdHelper.Request(
                "geely_usb_" + randomHex(), randomHex(), Process.myUid(), identity, configuration);
        final File dex = prepareHelper();
        final File runtime = new File(dex.getParentFile(), "runtime");
        final LocalServerSocket server = new LocalServerSocket(request.socketName);
        final AtomicReference<LocalSocket> activeSocket = new AtomicReference<LocalSocket>();
        final AtomicReference<IOException> bridgeFailure = new AtomicReference<IOException>();
        final AtomicBoolean expired = new AtomicBoolean();
        Thread deadline = new Thread(new Runnable() {
            @Override public void run() {
                try { Thread.sleep(GeelyUsbFdHelper.DEADLINE_MS); }
                catch (InterruptedException finished) { return; }
                expired.set(true);
                closeQuietly(activeSocket.get());
                closeServer(server);
            }
        }, "geely-usb-fd-deadline");
        deadline.setDaemon(true);
        deadline.start();
        Thread launcher = new Thread(new Runnable() {
            @Override public void run() {
                try { bridge.start(dex, runtime, request); }
                catch (IOException failure) {
                    bridgeFailure.set(failure);
                    closeQuietly(activeSocket.get());
                    closeServer(server);
                }
            }
        }, "geely-usb-oem-launch");
        launcher.setDaemon(true);
        launcher.start();
        LocalSocket socket = null;
        ParcelFileDescriptor descriptor = null;
        try {
            socket = server.accept();
            activeSocket.set(socket);
            if (expired.get()) throw new IOException("usb_helper_timeout");
            socket.setSoTimeout(GeelyUsbFdHelper.DEADLINE_MS - 1000);
            if (socket.getPeerCredentials().getUid() != 0) throw new IOException("usb_helper_uid");
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            GeelyUsbFdHelper.writeRequest(output, request);
            output.flush();
            InputStream rawInput = socket.getInputStream();
            // Do not buffer this byte: receive its SCM_RIGHTS before another read can replace it.
            int status = rawInput.read();
            FileDescriptor[] received = socket.getAncillaryFileDescriptors();
            boolean needsDescriptor = configuration == 0;
            int count = received == null ? 0 : received.length;
            if (status != 1 || count != (needsDescriptor ? 1 : 0)) {
                closeDescriptors(received);
                throw new IOException("usb_helper_status_or_fd_count:" + status + ":" + count);
            }
            if (needsDescriptor) {
                try { descriptor = ParcelFileDescriptor.dup(received[0]); }
                finally { closeDescriptors(received); }
            }
            DataInputStream input = new DataInputStream(GeelyUsbFdHelper.inputWithoutDescriptors(socket));
            GeelyUsbFdHelper.Request reply = GeelyUsbFdHelper.readRequest(input, request.socketName, request.appUid);
            if (!request.matches(reply)) throw new IOException("usb_helper_response_identity");
            int length = input.readInt();
            if (length < 0 || length > GeelyUsbDescriptorParser.MAX_DESCRIPTOR_BYTES
                    || (needsDescriptor ? length < 18 : length != 0)) throw new IOException("usb_helper_descriptor_length");
            byte[] raw = new byte[length];
            input.readFully(raw);
            FileDescriptor[] unexpected = socket.getAncillaryFileDescriptors();
            if (unexpected != null && unexpected.length != 0) {
                closeDescriptors(unexpected);
                throw new IOException("usb_helper_extra_fd");
            }
            if (expired.get()) throw new IOException("usb_helper_timeout");
            if (!needsDescriptor) {
                Log.i(TAG, "OEM Apple configuration selected and read back: " + configuration + " device=" + identity.sysfsName);
                return null;
            }
            GeelyUsbFdHelper.verifyDescriptors(identity, raw);
            OpenedDevice opened = new OpenedDevice(descriptor, raw, identity);
            descriptor = null;
            Log.i(TAG, "OEM Apple USBFS FD received; device=" + identity.sysfsName + " bytes=" + raw.length);
            return opened;
        } catch (IOException failure) {
            IOException cause = bridgeFailure.get();
            if (cause != null) failure = new IOException(cause.getMessage(), cause);
            else if (expired.get()) failure = new IOException("usb_helper_timeout", failure);
            Log.w(TAG, "OEM USB " + (configuration == 0 ? "open" : "configuration") + " failed: " + failure.getMessage());
            throw failure;
        } finally {
            deadline.interrupt();
            closeQuietly(descriptor);
            // Collect any queued descriptors even on a malformed or truncated response.
            if (socket != null) {
                try { closeDescriptors(socket.getAncillaryFileDescriptors()); } catch (Exception ignored) { }
            }
            closeQuietly(socket);
            closeServer(server);
        }
    }

    File prepareHelper() throws IOException {
        synchronized (ASSET_LOCK) {
            File directory = new File(context.getFilesDir(), "geely-usb");
            if (!directory.isDirectory() && !directory.mkdir()) throw new IOException("helper_directory");
            File canonical = directory.getCanonicalFile();
            if (!canonical.getParentFile().equals(context.getFilesDir().getCanonicalFile())) throw new IOException("helper_directory_path");
            File runtime = new File(canonical, "runtime");
            if (!runtime.isDirectory() && !runtime.mkdir()) throw new IOException("helper_runtime");
            if (!runtime.getCanonicalFile().equals(runtime)) throw new IOException("helper_runtime_path");
            File cache = new File(runtime, "dalvik-cache");
            if (!cache.isDirectory() && !cache.mkdir()) throw new IOException("helper_dalvik_cache");
            if (!cache.getCanonicalFile().equals(cache)) throw new IOException("helper_cache_path");
            File dex = new File(canonical, "helper.dex");
            File temporary = new File(canonical, "helper.dex.tmp");
            if (!dex.getCanonicalFile().equals(dex)
                    || !temporary.getCanonicalFile().equals(temporary)) throw new IOException("helper_asset_path");
            try {
                InputStream input = context.getAssets().open("geely-usb/helper.dex");
                try {
                    FileOutputStream output = new FileOutputStream(temporary);
                    try {
                        byte[] buffer = new byte[8192];
                        int count;
                        int total = 0;
                        while ((count = input.read(buffer)) != -1) {
                            if (count == 0 || count > 4 * 1024 * 1024 - total) throw new IOException("helper_asset_limit");
                            output.write(buffer, 0, count);
                            total += count;
                        }
                        if (total < 8) throw new IOException("helper_asset_empty");
                        output.getFD().sync();
                    } finally { output.close(); }
                } finally { input.close(); }
                if (!temporary.renameTo(dex)) throw new IOException("helper_asset_install");
                return dex;
            } finally {
                if (temporary.exists() && !temporary.delete()) Log.w(TAG, "Could not remove temporary helper asset");
            }
        }
    }

    private static String randomHex() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        char[] hex = new char[32];
        char[] digits = "0123456789abcdef".toCharArray();
        for (int index = 0; index < bytes.length; index++) {
            hex[index * 2] = digits[(bytes[index] & 255) >>> 4];
            hex[index * 2 + 1] = digits[bytes[index] & 15];
        }
        return new String(hex);
    }

    private static void closeDescriptors(FileDescriptor[] descriptors) {
        if (descriptors == null) return;
        for (FileDescriptor descriptor : descriptors) {
            // FileInputStream owns and closes this received raw FD. dup() above owns a different FD.
            if (descriptor != null) closeQuietly(new FileInputStream(descriptor));
        }
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable != null) try { closeable.close(); } catch (IOException ignored) { }
    }

    private static void closeServer(LocalServerSocket server) {
        try { server.close(); } catch (IOException ignored) { }
    }

    /** Owns the received FD until DeviceAccess transfers or closes it; descriptors describe that FD. */
    public static final class OpenedDevice implements Closeable {
        public final ParcelFileDescriptor descriptor;
        public final byte[] rawDescriptors;
        public final GeelyUsbIdentity identity;

        OpenedDevice(ParcelFileDescriptor descriptor, byte[] rawDescriptors, GeelyUsbIdentity identity) {
            this.descriptor = descriptor;
            this.rawDescriptors = rawDescriptors;
            this.identity = identity;
        }

        @Override public void close() throws IOException { descriptor.close(); }
    }
}
