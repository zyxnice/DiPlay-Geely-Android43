package com.shilapi.xcertplay.compat;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Owns only wired discovery preparation; it is never called while streaming or on the UI thread. */
public final class GeelyUsbRuntimeRecovery {
    private static final String TAG = "xcertplay-usb";
    private static final String OPT_IN = "com.shilapi.xcertplay.GEELY_USB_RUNTIME_RECOVERY";
    private static FutureTask<String> inFlight;
    private final Context context;
    private long lastAttempt = -10000;
    private int changes;
    private volatile boolean stopped;
    private volatile File activeLease;
    private String summary = "not-run";

    public GeelyUsbRuntimeRecovery(Context context) { this.context = context.getApplicationContext(); }

    public static boolean isEnabled(Context context) {
        try {
            ApplicationInfo app = context.getPackageManager().getApplicationInfo(context.getPackageName(), PackageManager.GET_META_DATA);
            return GeelyUsbRuntimePolicy.matches(Build.VERSION.SDK_INT, Build.BOARD, Build.DISPLAY,
                    app.metaData != null && app.metaData.getBoolean(OPT_IN, false));
        } catch (PackageManager.NameNotFoundException absent) { return false; }
    }

    public void stop() {
        stopped = true;
        revokeLease();
    }

    private void revokeLease() {
        File lease = activeLease;
        if (lease != null && lease.exists() && !lease.delete()) Log.e(TAG, "Could not revoke runtime preparation lease");
    }
    public synchronized String summary() { return summary; }

    /** Returns false during the bounded settle interval; the controller keeps its normal discovery poll. */
    public synchronized boolean prepare() throws IOException {
        if (stopped || Thread.currentThread().isInterrupted()) throw new IOException("runtime_cancelled");
        if (!isEnabled(context)) { summary = "disabled"; return true; }
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IOException("runtime_on_ui_thread");
        // A previous controller may still be rolling back after its lease was revoked.
        // Do not trust a transient ready snapshot or open a data path until it has exited.
        if (operationPending()) { summary = "waiting-for-previous-operation"; return false; }
        long now = SystemClock.elapsedRealtime();
        // Poll locally first, avoiding privileged helper launches in the steady waiting state.
        if (locallyReady()) { summary = "ready"; return true; }
        if (now - lastAttempt < 5000) return false;
        if (changes >= 3) throw new IOException("USB模式被系统反复切回，请保存连接日志后重试");
        lastAttempt = now;
        final File dex = new GeelyUsbFdBroker(context).prepareHelper();
        final String nonce = java.util.UUID.randomUUID().toString().replace("-", "");
        final File lease = new File(dex.getParentFile(), "runtime-" + nonce + ".lease");
        final long deadline = SystemClock.elapsedRealtime() + 6000;
        FutureTask<String> task;
        synchronized (GeelyUsbRuntimeRecovery.class) {
            if (operationPending()) return false;
            java.io.FileOutputStream output = new java.io.FileOutputStream(lease);
            try { output.write(nonce.getBytes("US-ASCII")); } finally { output.close(); }
            activeLease = lease;
            if (stopped) { revokeLease(); throw new IOException("runtime_cancelled"); }
            task = new FutureTask<String>(new java.util.concurrent.Callable<String>() {
                @Override public String call() throws Exception {
                    if (stopped) throw new IOException("runtime_cancelled");
                    return new GeelyUsbOemBridge().prepareRuntime(dex, new File(dex.getParentFile(), "runtime"), deadline, lease, nonce);
                }
            });
            inFlight = task;
            Thread worker = new Thread(task, "geely-usb-runtime");
            worker.setDaemon(true);
            worker.start();
        }
        try {
            summary = task.get(8, TimeUnit.SECONDS);
            if ("prepared".equals(summary)) changes++;
            Log.i(TAG, "8600 runtime preparation result=" + summary + " changes=" + changes);
            return true;
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            // Do not cancel the Future: Binder is not interruptible; retain the single-flight guard.
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            summary = "failed:" + cause.getMessage();
            Log.e(TAG, "8600 runtime preparation " + summary, failure);
            throw new IOException("USB自动准备失败：" + cause.getMessage(), failure);
        } finally {
            revokeLease();
        }
    }

    static synchronized boolean operationPending() { return inFlight != null && !inFlight.isDone(); }

    private static boolean locallyReady() {
        try {
            return "0".equals(GeelyUsbRuntimeHelper.text("/proc/driver/usbroleswitch/0/mode"))
                    && "sdp".equals(GeelyUsbRuntimeHelper.text("/sys/class/otg_peripheral/charging_mode"))
                    && GeelyUsbRuntimePolicy.isPowered(GeelyUsbRuntimeHelper.text("/sys/class/otg_peripheral/power"))
                    && GeelyUsbRuntimePolicy.isHealthy(GeelyUsbRuntimeHelper.text("/sys/class/otg_peripheral/pin_fault/status"));
        } catch (IOException unavailable) { return false; }
    }
}
