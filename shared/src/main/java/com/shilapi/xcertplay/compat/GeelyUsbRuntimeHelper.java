package com.shilapi.xcertplay.compat;

import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;

/** One-shot OEM child. Only prepare(deadline) is accepted; no caller-supplied shell or paths. */
public final class GeelyUsbRuntimeHelper implements GeelyUsbRuntimePolicy.Port {
    static final String ENTRY_POINT = "com.shilapi.xcertplay.compat.GeelyUsbRuntimeHelper";
    private static final String OTG = "/sys/class/otg_peripheral/";
    private final long deadline;
    private final File lease;
    private final String nonce;
    private boolean rollingBack;

    private GeelyUsbRuntimeHelper(long deadline, File lease, String nonce) {
        this.deadline = deadline; this.lease = lease; this.nonce = nonce;
    }

    public static void main(String[] arguments) {
        Thread watchdog = new Thread(new Runnable() {
            @Override public void run() {
                try { Thread.sleep(8000); } catch (InterruptedException ignored) { }
                Runtime.getRuntime().halt(2);
            }
        }, "geely-runtime-deadline");
        watchdog.setDaemon(true);
        watchdog.start();
        try {
            if (arguments.length != 3 || !arguments[0].matches("[0-9]{1,16}")
                    || !arguments[2].matches("[a-f0-9]{32}")) throw new IOException("runtime_arguments");
            File lease = new File(arguments[1]);
            if (!lease.getCanonicalFile().equals(lease) || !lease.getName().equals("runtime-" + arguments[2] + ".lease")
                    || !lease.getParent().equals("/data/data/com.shihab.diplay.repair1.geely8600/files/geely-usb"))
                throw new IOException("runtime_lease_path");
            long deadline = Long.parseLong(arguments[0]);
            if (deadline <= SystemClock.elapsedRealtime() || deadline - SystemClock.elapsedRealtime() > 6000)
                throw new IOException("runtime_expired");
            if (Process.myUid() != 0 || !GeelyUsbRuntimePolicy.matches(Build.VERSION.SDK_INT, Build.BOARD, Build.DISPLAY, true))
                throw new IOException("runtime_platform_guard");
            String result = GeelyUsbRuntimePolicy.prepare(new GeelyUsbRuntimeHelper(deadline, lease, arguments[2]));
            System.out.println("GEELY_RUNTIME_OK:" + result);
        } catch (Exception failure) {
            String reason = failure.getMessage();
            System.out.println("GEELY_RUNTIME_FAILED:" + (reason != null && reason.matches("[a-z_]{1,64}") ? reason : "runtime_failure"));
        }
        System.exit(0);
    }

    private void checkDeadline() throws IOException {
        verifyLease(lease, nonce, SystemClock.elapsedRealtime(), deadline, rollingBack);
    }

    static void verifyLease(File lease, String nonce, long now, long deadline, boolean rollingBack) throws IOException {
        if (now >= deadline + (rollingBack ? 1500 : 0)) throw new IOException("runtime_expired");
        if (!rollingBack && (!lease.isFile() || !nonce.equals(text(lease.getPath())))) throw new IOException("runtime_cancelled");
    }

    @Override public void beginRollback() { rollingBack = true; }

    @Override public void awaitRestored(GeelyUsbRuntimePolicy.State original) throws Exception {
        do {
            checkDeadline();
            if (original.role.equals(text("/proc/driver/usbroleswitch/0/mode"))
                    && original.config.equals(property("sys.usb.state"))) return;
            Thread.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline + 1500);
        throw new IOException("runtime_restore_timeout");
    }

    @Override public GeelyUsbRuntimePolicy.State read() throws Exception {
        checkDeadline();
        return new GeelyUsbRuntimePolicy.State(property("sys.usb.config"),
                text("/proc/driver/usbroleswitch/0/mode"), text(OTG + "charging_mode"),
                text(OTG + "power"), text(OTG + "pin_fault/status"), activeApple());
    }

    @Override public void configuration(String value) throws Exception {
        checkDeadline();
        if (!GeelyUsbRuntimePolicy.HOST_CONFIG.equals(value) && !GeelyUsbRuntimePolicy.ADB_CONFIG.equals(value))
            throw new IOException("runtime_config_value");
        Class<?> properties = Class.forName("android.os.SystemProperties");
        properties.getDeclaredMethod("set", String.class, String.class).invoke(null, "sys.usb.config", value);
    }

    @Override public void power(String value) throws Exception {
        if (!"on".equals(value) && !"off".equals(value)) throw new IOException("runtime_power_value");
        write(OTG + "power", value);
    }

    @Override public void charging(String value) throws Exception {
        if (!"sdp".equals(value) && !"cdp".equals(value) && !"dcp".equals(value)) throw new IOException("runtime_charge_value");
        // H53 UsbProtectProvider transaction 8 writes exactly this node (2 -> sdp).
        // Avoid a nested synchronous Binder call back into the launching OEM daemon.
        write(OTG + "charging_mode", value);
    }

    @Override public void awaitHost() throws Exception {
        long until = Math.min(deadline - 1500, SystemClock.elapsedRealtime() + 2000);
        do {
            if ("0".equals(text("/proc/driver/usbroleswitch/0/mode"))
                    && GeelyUsbRuntimePolicy.HOST_CONFIG.equals(property("sys.usb.state"))) return;
            Thread.sleep(50);
        } while (SystemClock.elapsedRealtime() < until);
        throw new IOException("host_transition_timeout");
    }

    static String property(String key) throws Exception {
        Method method = Class.forName("android.os.SystemProperties").getDeclaredMethod("get", String.class);
        return (String) method.invoke(null, key);
    }

    static String text(String path) throws IOException {
        FileInputStream input = new FileInputStream(path);
        try {
            byte[] bytes = new byte[128];
            int count = input.read(bytes);
            if (count <= 0 || count == bytes.length) throw new IOException("runtime_state_unreadable");
            return new String(bytes, 0, count, "US-ASCII").trim().toLowerCase(java.util.Locale.US);
        } finally { input.close(); }
    }

    private void write(String path, String value) throws Exception {
        checkDeadline();
        // Never energize a port with a current fault, including between two preparation steps.
        if ("on".equals(value) && !"working fine".equals(text(OTG + "pin_fault/status"))) throw new IOException("otg_power_fault");
        FileOutputStream output = new FileOutputStream(path);
        try { output.write(value.getBytes("US-ASCII")); output.flush(); }
        finally { output.close(); }
    }

    private static boolean activeApple() throws IOException {
        File[] paths = new File("/sys/bus/usb/devices").listFiles();
        if (paths == null) throw new IOException("runtime_usb_listing");
        for (File path : paths) {
            if (!GeelyUsbIdentity.isValidSysfsName(path.getName()) || !path.getName().startsWith("1-")) continue;
            if ("05ac".equals(text(path + "/idVendor")) && "6".equals(text(path + "/bConfigurationValue"))) return true;
        }
        return false;
    }
}
