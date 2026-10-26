package com.shilapi.xcertplay.compat;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** Identity of one enumeration, including the complete descriptor bytes, never just a device path. */
public final class GeelyUsbIdentity {
    public final String sysfsName;
    public final int busNumber;
    public final int deviceNumber;
    public final int vendorId;
    public final int productId;
    public final String descriptorSha256;

    public GeelyUsbIdentity(String sysfsName, int busNumber, int deviceNumber,
                            int vendorId, int productId, String descriptorSha256) {
        if (!isValidSysfsName(sysfsName) || busNumber < 1 || busNumber > 999
                || deviceNumber < 1 || deviceNumber > 127
                || !sysfsName.substring(0, sysfsName.indexOf('-')).equals(Integer.toString(busNumber))
                || vendorId < 0 || vendorId > 65535 || productId < 0 || productId > 65535
                || descriptorSha256 == null || !descriptorSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid USB enumeration identity");
        }
        this.sysfsName = sysfsName;
        this.busNumber = busNumber;
        this.deviceNumber = deviceNumber;
        this.vendorId = vendorId;
        this.productId = productId;
        this.descriptorSha256 = descriptorSha256;
    }

    public static boolean isValidSysfsName(String name) {
        return name != null && name.length() <= 128
                && name.matches("[1-9][0-9]{0,2}-[1-9][0-9]*(\\.[1-9][0-9]*)*");
    }

    public String devicePath() {
        return String.format(Locale.US, "/dev/bus/usb/%03d/%03d", busNumber, deviceNumber);
    }

    public static String sha256(byte[] bytes) {
        if (bytes == null) throw new IllegalArgumentException("Missing USB descriptors");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest) hex.append(String.format(Locale.US, "%02x", value & 255));
            return hex.toString();
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof GeelyUsbIdentity)) return false;
        GeelyUsbIdentity identity = (GeelyUsbIdentity) other;
        return busNumber == identity.busNumber && deviceNumber == identity.deviceNumber
                && vendorId == identity.vendorId && productId == identity.productId
                && sysfsName.equals(identity.sysfsName) && descriptorSha256.equals(identity.descriptorSha256);
    }

    @Override public int hashCode() {
        int hash = sysfsName.hashCode();
        hash = 31 * hash + busNumber;
        hash = 31 * hash + deviceNumber;
        hash = 31 * hash + vendorId;
        hash = 31 * hash + productId;
        return 31 * hash + descriptorSha256.hashCode();
    }

    @Override public String toString() {
        return sysfsName + " " + devicePath() + " vid=" + Integer.toHexString(vendorId)
                + " pid=" + Integer.toHexString(productId) + " descriptors=" + descriptorSha256;
    }
}
