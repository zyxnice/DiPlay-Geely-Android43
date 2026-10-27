package com.shilapi.xcertplay.compat;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Parcelable;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Narrow API18/H53 test-build adapter; it never grants or fabricates Android USB permission. */
public final class GeelyUsbDeviceAccess {
    private static final String TAG = "xcertplay-usb";
    private static final String ENABLE_METADATA = "com.shilapi.xcertplay.GEELY_USB_BYPASS";
    private static final File SYSFS = new File("/sys/bus/usb/devices");
    private static final Map<UsbDeviceConnection, ConnectionRecord> CONNECTIONS =
            Collections.synchronizedMap(new WeakHashMap<UsbDeviceConnection, ConnectionRecord>());
    private final Context context;
    private final UsbManager manager;
    private final GeelyUsbFdBroker broker;
    private final Map<UsbDevice, RecoveredDevice> recovered = new IdentityHashMap<UsbDevice, RecoveredDevice>();
    private final Map<String, Long> diagnosticTimes = new HashMap<String, Long>();
    private String discoverySummary = "not-run";

    public GeelyUsbDeviceAccess(Context context) {
        this.context = context.getApplicationContext();
        this.manager = (UsbManager) this.context.getSystemService(Context.USB_SERVICE);
        this.broker = new GeelyUsbFdBroker(this.context);
    }

    public static boolean isEnabled(Context context) {
        if (Build.VERSION.SDK_INT != 18 || !"C3ALFUS".equals(Build.BOARD)
                || Build.DISPLAY == null || !Build.DISPLAY.startsWith("06.03.08600")) return false;
        try {
            ApplicationInfo application = context.getPackageManager().getApplicationInfo(
                    context.getPackageName(), PackageManager.GET_META_DATA);
            return application.metaData != null && application.metaData.getBoolean(ENABLE_METADATA, false);
        } catch (PackageManager.NameNotFoundException unavailable) {
            Log.e(TAG, "8600 USB test-build metadata unavailable", unavailable);
            return false;
        }
    }

    /** Local sysfs I/O only. Caller runs discovery on its existing USB worker, never starts the root bridge here. */
    public synchronized List<UsbDevice> discover(List<UsbDevice> frameworkDevices) {
        LinkedHashMap<String, UsbDevice> merged = new LinkedHashMap<String, UsbDevice>();
        for (UsbDevice device : frameworkDevices) merged.put(device.getDeviceName(), device);
        if (!isEnabled(context)) {
            recovered.clear();
            discoverySummary = "kernelDiscovery=disabled framework=" + frameworkDevices.size();
            return new ArrayList<UsbDevice>(merged.values());
        }
        Map<UsbDevice, RecoveredDevice> next = new IdentityHashMap<UsbDevice, RecoveredDevice>();
        File[] paths = SYSFS.listFiles();
        int appleDevices = 0;
        int failures = paths == null ? 1 : 0;
        String failureCategory = paths == null ? "sysfs-list-unavailable" : "none";
        if (paths == null) diagnostic("sysfs", "8600 USB sysfs listing unavailable; bypass discovery incomplete", null);
        if (paths != null) for (File path : paths) {
            if (!GeelyUsbIdentity.isValidSysfsName(path.getName())) continue;
            try {
                if (!"05ac".equalsIgnoreCase(readText(new File(path, "idVendor")))) continue;
                appleDevices++;
                RecoveredDevice candidate = inspect(path);
                String devicePath = candidate.identity.devicePath();
                if (merged.containsKey(devicePath)) continue; // System objects and their permission route win.
                RecoveredDevice stable = findExisting(candidate.identity);
                if (stable != null) candidate = stable;
                else Log.i(TAG, "8600 USB recovered Apple transport " + candidate.identity);
                merged.put(devicePath, candidate.device);
                next.put(candidate.device, candidate);
            } catch (Exception failure) {
                failures++;
                failureCategory = failure.getClass().getSimpleName();
                diagnostic(path.getName(), "8600 USB discovery incomplete sysfs=" + path.getName()
                        + "; descriptors/identity inaccessible or invalid, no device fabricated", failure);
            }
        }
        recovered.clear();
        recovered.putAll(next);
        discoverySummary = "kernelDiscovery=enabled framework=" + frameworkDevices.size() + " sysfsApple="
                + appleDevices + " recovered=" + next.size() + " incomplete=" + failures + " reason=" + failureCategory;
        return new ArrayList<UsbDevice>(merged.values());
    }

    /** State summary for the existing on-screen diagnostic report; contains no credentials. */
    public synchronized String discoverySummary() { return discoverySummary; }

    private RecoveredDevice inspect(File path) throws Exception {
        int bus = decimal(path, "busnum");
        int number = decimal(path, "devnum");
        int vendor = hex(path, "idVendor");
        int product = hex(path, "idProduct");
        byte[] descriptors = readDescriptors(new File(path, "descriptors"));
        GeelyUsbDescriptorParser.ParsedDevice parsed = GeelyUsbDescriptorParser.parse(descriptors);
        if (vendor != parsed.vendorId || product != parsed.productId || !parsed.hasAppleTransport())
            throw new IOException("Not a matching Apple USBMUX/iAP2 transport");
        GeelyUsbIdentity identity = new GeelyUsbIdentity(path.getName(), bus, number, vendor, product,
                GeelyUsbIdentity.sha256(descriptors));
        if (bus != decimal(path, "busnum") || number != decimal(path, "devnum")
                || vendor != hex(path, "idVendor") || product != hex(path, "idProduct"))
            throw new IOException("USB enumeration changed during discovery");
        return new RecoveredDevice(identity, construct(identity.devicePath(), parsed));
    }

    private RecoveredDevice findExisting(GeelyUsbIdentity identity) {
        for (RecoveredDevice candidate : recovered.values()) if (candidate.identity.equals(identity)) return candidate;
        return null;
    }

    public synchronized boolean isRecovered(UsbDevice device) { return recovered.containsKey(device); }

    public boolean hasAccess(UsbDevice device) {
        if (isRecovered(device)) return isEnabled(context) && broker.isBridgeAvailable();
        return manager != null && manager.hasPermission(device);
    }

    public UsbDeviceConnection open(UsbDevice device) {
        RecoveredDevice candidate;
        synchronized (this) { candidate = recovered.get(device); }
        if (candidate == null) return manager == null ? null : manager.openDevice(device);
        if (!isEnabled(context)) throw new IllegalStateException("8600 USB bypass guard no longer matches");
        UsbDeviceConnection connection = null;
        try (GeelyUsbFdBroker.OpenedDevice opened = broker.open(candidate.identity)) {
            if (!candidate.identity.equals(opened.identity)
                    || !candidate.identity.descriptorSha256.equals(GeelyUsbIdentity.sha256(opened.rawDescriptors)))
                throw new IOException("USB identity changed before connection open");
            GeelyUsbDescriptorParser.ParsedDevice parsed = GeelyUsbDescriptorParser.parse(opened.rawDescriptors);
            if (!parsed.hasAppleTransport()) throw new IOException("Returned USB descriptor is not Apple transport");
            Constructor<UsbDeviceConnection> constructor = UsbDeviceConnection.class.getDeclaredConstructor(UsbDevice.class);
            constructor.setAccessible(true);
            connection = constructor.newInstance(device);
            Method open = UsbDeviceConnection.class.getDeclaredMethod("open", String.class, android.os.ParcelFileDescriptor.class);
            open.setAccessible(true);
            if (!Boolean.TRUE.equals(open.invoke(connection, candidate.identity.devicePath(), opened.descriptor)))
                throw new IOException("UsbDeviceConnection rejected transferred USB descriptor");
            CONNECTIONS.put(connection, new ConnectionRecord(device, candidate.identity, parsed.rawDescriptors(), broker));
            Log.i(TAG, "8600 USB connection opened through OEM FD bridge " + candidate.identity.devicePath());
            return connection; // Firmware native_open duplicates the FD; OpenedDevice closes the received FD.
        } catch (Exception failure) {
            if (connection != null) {
                CONNECTIONS.remove(connection);
                connection.close();
            }
            Log.e(TAG, "8600 USB OEM FD connection failed path=" + candidate.identity.devicePath(), failure);
            throw new IllegalStateException("8600 USB OEM FD connection failed: "
                    + (failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()), failure);
        }
    }

    /** Complete descriptor copy, independent of API18's native descriptor read size. */
    public static byte[] descriptorsFor(UsbDeviceConnection connection) {
        ConnectionRecord record = CONNECTIONS.get(connection);
        return record == null ? null : record.descriptors.clone();
    }

    /** False means normal framework route. A recovered-route failure must never fall through to su. */
    public static boolean setConfigurationIfRecovered(UsbDevice device, UsbDeviceConnection connection, int value) {
        ConnectionRecord record = CONNECTIONS.get(connection);
        if (record == null) return false;
        if (record.device != device) throw new IllegalArgumentException("USB device/connection identity mismatch");
        if (connection.getFileDescriptor() < 0) throw new IllegalStateException("USB connection already closed");
        GeelyUsbDescriptorParser.Configuration configuration = GeelyUsbDescriptorParser.parse(record.descriptors).configuration(value);
        if (configuration == null || !configuration.hasAppleTransport())
            throw new IllegalArgumentException("Configuration does not contain Apple transport");
        try {
            record.broker.setConfiguration(record.identity, value);
            byte[] actual = new byte[1];
            if (connection.controlTransfer(0x80, 8, 0, 0, actual, 1, 2000) != 1 || (actual[0] & 255) != value)
                throw new IOException("USB active configuration did not match requested value");
            Log.i(TAG, "8600 USB OEM configuration verified path=" + record.identity.devicePath() + " value=" + value);
            return true;
        } catch (Exception failure) {
            Log.e(TAG, "8600 USB OEM configuration failed path=" + record.identity.devicePath() + " value=" + value, failure);
            throw new IllegalStateException("8600 USB OEM configuration failed: "
                    + (failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()), failure);
        }
    }

    private static UsbDevice construct(String name, GeelyUsbDescriptorParser.ParsedDevice parsed) throws Exception {
        Constructor<UsbEndpoint> endpointConstructor = UsbEndpoint.class.getDeclaredConstructor(int.class, int.class, int.class, int.class);
        Constructor<UsbInterface> interfaceConstructor = UsbInterface.class.getDeclaredConstructor(int.class, int.class, int.class, int.class, Parcelable[].class);
        Constructor<UsbDevice> deviceConstructor = UsbDevice.class.getDeclaredConstructor(String.class, int.class, int.class, int.class, int.class, int.class, Parcelable[].class);
        endpointConstructor.setAccessible(true); interfaceConstructor.setAccessible(true); deviceConstructor.setAccessible(true);
        List<Parcelable> interfaces = new ArrayList<Parcelable>();
        for (GeelyUsbDescriptorParser.Configuration configuration : parsed.configurations) {
            for (GeelyUsbDescriptorParser.UsbInterfaceDescriptor descriptor : configuration.interfaces) {
                Parcelable[] endpoints = new Parcelable[descriptor.endpoints.size()];
                for (int i = 0; i < endpoints.length; i++) {
                    GeelyUsbDescriptorParser.Endpoint endpoint = descriptor.endpoints.get(i);
                    endpoints[i] = endpointConstructor.newInstance(endpoint.address, endpoint.attributes, endpoint.maxPacketSize, endpoint.interval);
                }
                interfaces.add(interfaceConstructor.newInstance(descriptor.number, descriptor.interfaceClass,
                        descriptor.interfaceSubclass, descriptor.interfaceProtocol, endpoints));
            }
        }
        return deviceConstructor.newInstance(name, parsed.vendorId, parsed.productId, parsed.deviceClass,
                parsed.deviceSubclass, parsed.deviceProtocol, interfaces.toArray(new Parcelable[interfaces.size()]));
    }

    private static byte[] readDescriptors(File path) throws IOException {
        try (FileInputStream input = new FileInputStream(path)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (count == 0) continue;
                if (count > GeelyUsbDescriptorParser.MAX_DESCRIPTOR_BYTES - output.size())
                    throw new IOException("USB descriptors exceed 1 MiB limit");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static String readText(File path) throws IOException {
        try (FileInputStream input = new FileInputStream(path)) {
            byte[] bytes = new byte[128];
            int count = input.read(bytes);
            if (count < 1 || count == bytes.length) throw new IOException("Invalid sysfs text: " + path.getName());
            return new String(bytes, 0, count, "US-ASCII").trim();
        }
    }
    private static int decimal(File path, String name) throws IOException { return Integer.parseInt(readText(new File(path, name)), 10); }
    private static int hex(File path, String name) throws IOException { return Integer.parseInt(readText(new File(path, name)), 16); }

    private void diagnostic(String key, String message, Exception failure) {
        long now = android.os.SystemClock.elapsedRealtime();
        Long previous = diagnosticTimes.get(key);
        if (previous != null && now - previous < 30000) return;
        if (diagnosticTimes.size() > 64) diagnosticTimes.clear();
        diagnosticTimes.put(key, now);
        if (failure == null) Log.w(TAG, message); else Log.w(TAG, message, failure);
    }

    private static final class RecoveredDevice {
        final GeelyUsbIdentity identity;
        final UsbDevice device;
        RecoveredDevice(GeelyUsbIdentity identity, UsbDevice device) {
            this.identity = identity; this.device = device;
        }
    }
    private static final class ConnectionRecord {
        final UsbDevice device;
        final GeelyUsbIdentity identity;
        final byte[] descriptors;
        final GeelyUsbFdBroker broker;
        ConnectionRecord(UsbDevice device, GeelyUsbIdentity identity, byte[] descriptors, GeelyUsbFdBroker broker) {
            this.device = device; this.identity = identity; this.descriptors = descriptors; this.broker = broker;
        }
    }
}
