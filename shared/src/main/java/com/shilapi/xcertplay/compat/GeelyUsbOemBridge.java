package com.shilapi.xcertplay.compat;

import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;

/** Fixed helper launcher for the H53 OEM text bridge; never exposes arbitrary shell execution. */
final class GeelyUsbOemBridge {
    private static final String TAG = "GeelyUsbOemBridge";
    private static final String TOKEN = "com.neusoft.c3alfus.projectservice.IUsbProtectService";
    private static final int SEND_SHELL_COMMAND = 0x16;

    boolean isAvailable() {
        try { return service() != null; }
        catch (Exception failure) {
            Log.w(TAG, "OEM usbprotect lookup unavailable: " + failure.getClass().getSimpleName());
            return false;
        }
    }

    /** Caller runs on a daemon worker: OEM Binder may block until the helper's hard deadline. */
    void start(File helperDex, File runtimeDirectory, GeelyUsbFdHelper.Request request) throws IOException {
        String command = buildCommand(helperDex, runtimeDirectory, request);
        Parcel input = Parcel.obtain();
        Parcel output = Parcel.obtain();
        try {
            IBinder binder = service();
            if (binder == null) throw new IOException("oem_service_missing");
            input.writeInterfaceToken(TOKEN);
            input.writeByteArray((command + "\u0000").getBytes(GeelyUsbFdHelper.UTF8));
            if (!binder.transact(SEND_SHELL_COMMAND, input, output, 0)) throw new IOException("oem_transaction_rejected");
            output.readException();
            byte[] result = output.createByteArray();
            if (result != null) {
                java.util.regex.Matcher failed = java.util.regex.Pattern.compile(
                        "GEELY_USB_HELPER_FAILED:([a-z_]{1,64})").matcher(new String(result, GeelyUsbFdHelper.UTF8));
                if (failed.find()) throw new IOException("oem_helper_" + failed.group(1));
            }
            // A successful text transaction is not proof of successful root FD access.
            Log.d(TAG, "OEM helper invocation returned textBytes=" + (result == null ? -1 : result.length));
        } catch (SecurityException denied) {
            throw new IOException("oem_bridge_denied", denied);
        } catch (Exception failure) {
            if (failure instanceof IOException) throw (IOException) failure;
            throw new IOException("oem_bridge_daemon_failure", failure);
        } finally {
            output.recycle();
            input.recycle();
        }
    }

    private static IBinder service() throws Exception {
        Class<?> manager = Class.forName("android.os.ServiceManager");
        Method method = manager.getDeclaredMethod("getService", String.class);
        return (IBinder) method.invoke(null, "usbprotect");
    }

    static String buildCommand(File helperDex, File runtimeDirectory, GeelyUsbFdHelper.Request request) throws IOException {
        if (helperDex == null || runtimeDirectory == null || request == null) throw new IOException("helper_arguments");
        File dex = helperDex.getCanonicalFile();
        File runtime = runtimeDirectory.getCanonicalFile();
        if (!dex.getName().equals("helper.dex") || !runtime.getName().equals("runtime")
                || !dex.getParentFile().equals(runtime.getParentFile())
                || !dex.getParentFile().getName().equals("geely-usb")) throw new IOException("helper_private_path");
        StringBuilder command = new StringBuilder("ANDROID_DATA=");
        command.append(shellQuote(runtime.getPath()));
        command.append(" CLASSPATH=").append(shellQuote(dex.getPath()));
        command.append(" /system/bin/app_process /system/bin ").append(GeelyUsbFdHelper.ENTRY_POINT);
        for (String argument : request.arguments()) command.append(' ').append(shellQuote(argument));
        command.append(" 2>/dev/null");
        if (command.toString().getBytes(GeelyUsbFdHelper.UTF8).length + 1 > 900) throw new IOException("oem_command_limit");
        return command.toString();
    }

    static String shellQuote(String value) throws IOException {
        if (value == null || value.indexOf('\u0000') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IOException("shell_argument");
        }
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
