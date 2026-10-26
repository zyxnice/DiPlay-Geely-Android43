package com.shilapi.xcertplay.compat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Strict parser for Linux sysfs descriptors: device followed by every complete configuration. */
public final class GeelyUsbDescriptorParser {
    public static final int MAX_DESCRIPTOR_BYTES = 1024 * 1024;
    private GeelyUsbDescriptorParser() { }

    public static ParsedDevice parse(byte[] raw) {
        if (raw == null || raw.length < 18 || raw.length > MAX_DESCRIPTOR_BYTES
                || u(raw, 0) != 18 || u(raw, 1) != 1) fail("Invalid device descriptor");
        int declaredConfigurations = u(raw, 17);
        if (declaredConfigurations == 0) fail("Device has no configurations");
        List<Configuration> configurations = new ArrayList<Configuration>();
        Set<Integer> values = new HashSet<Integer>();
        int offset = 18;
        while (offset < raw.length) {
            if (raw.length - offset < 9 || u(raw, offset) < 9 || u(raw, offset + 1) != 2)
                fail("Missing configuration header");
            int totalLength = le16(raw, offset + 2);
            int headerLength = u(raw, offset);
            if (totalLength < headerLength || totalLength > raw.length - offset)
                fail("Truncated configuration");
            int end = offset + totalLength;
            int value = u(raw, offset + 5);
            if (value == 0 || !values.add(value)) fail("Invalid or duplicate configuration value");
            int interfaceCount = u(raw, offset + 4);
            int attributes = u(raw, offset + 7);
            int maxPower = u(raw, offset + 8);
            List<UsbInterfaceDescriptor> interfaces = new ArrayList<UsbInterfaceDescriptor>();
            Set<Integer> numbers = new HashSet<Integer>();
            Set<Integer> alternatives = new HashSet<Integer>();
            UsbInterfaceDescriptor current = null;
            int expectedEndpoints = 0;
            int cursor = offset + headerLength;
            while (cursor < end) {
                if (end - cursor < 2) fail("Incomplete descriptor header");
                int length = u(raw, cursor);
                int type = u(raw, cursor + 1);
                if (length < 2 || length > end - cursor) fail("Invalid descriptor length");
                if (type == 1 || type == 2) fail("Unexpected nested device/configuration");
                if (type == 4) {
                    checkEndpointCount(current, expectedEndpoints);
                    if (length < 9) fail("Short interface descriptor");
                    int number = u(raw, cursor + 2);
                    int alt = u(raw, cursor + 3);
                    if (!alternatives.add((number << 8) | alt)) fail("Duplicate interface alternate setting");
                    numbers.add(number);
                    expectedEndpoints = u(raw, cursor + 4);
                    if (expectedEndpoints > 30) fail("Too many interface endpoints");
                    current = new UsbInterfaceDescriptor(number, alt, u(raw, cursor + 5),
                            u(raw, cursor + 6), u(raw, cursor + 7));
                    interfaces.add(current);
                } else if (type == 5) {
                    if (current == null || length < 7) fail("Endpoint without interface or short endpoint");
                    int address = u(raw, cursor + 2);
                    if ((address & 15) == 0 || (address & 0x70) != 0) fail("Invalid endpoint address");
                    for (Endpoint endpoint : current.endpoints) {
                        if (endpoint.address == address) fail("Duplicate endpoint address");
                    }
                    current.mutableEndpoints.add(new Endpoint(address, u(raw, cursor + 3),
                            le16(raw, cursor + 4), u(raw, cursor + 6)));
                    if (current.endpoints.size() > expectedEndpoints) fail("Too many endpoint descriptors");
                }
                cursor += length;
            }
            checkEndpointCount(current, expectedEndpoints);
            if (numbers.size() != interfaceCount) fail("Configuration interface count mismatch");
            for (Integer number : numbers) {
                if (!alternatives.contains(number << 8)) fail("Interface lacks alternate setting zero");
            }
            configurations.add(new Configuration(value, attributes, maxPower, interfaceCount, interfaces));
            if (configurations.size() > declaredConfigurations) fail("Too many configurations");
            offset = end;
        }
        if (configurations.size() != declaredConfigurations) fail("Device configuration count mismatch");
        return new ParsedDevice(raw, le16(raw, 8), le16(raw, 10), u(raw, 4), u(raw, 5), u(raw, 6), configurations);
    }

    private static void checkEndpointCount(UsbInterfaceDescriptor current, int expected) {
        if (current != null && current.endpoints.size() != expected) fail("Interface endpoint count mismatch");
    }
    private static int u(byte[] bytes, int index) { return bytes[index] & 255; }
    private static int le16(byte[] bytes, int index) { return u(bytes, index) | (u(bytes, index + 1) << 8); }
    private static void fail(String reason) { throw new IllegalArgumentException(reason); }

    public static final class ParsedDevice {
        public final int vendorId, productId, deviceClass, deviceSubclass, deviceProtocol;
        public final List<Configuration> configurations;
        private final byte[] raw;
        private ParsedDevice(byte[] raw, int vendorId, int productId, int deviceClass,
                             int deviceSubclass, int deviceProtocol, List<Configuration> configurations) {
            this.raw = raw.clone(); this.vendorId = vendorId; this.productId = productId;
            this.deviceClass = deviceClass; this.deviceSubclass = deviceSubclass; this.deviceProtocol = deviceProtocol;
            this.configurations = Collections.unmodifiableList(configurations);
        }
        public byte[] rawDescriptors() { return raw.clone(); }
        public Configuration configuration(int value) {
            for (Configuration configuration : configurations) if (configuration.value == value) return configuration;
            return null;
        }
        public boolean hasAppleTransport() {
            if (vendorId != 0x05ac) return false;
            for (Configuration configuration : configurations) if (configuration.hasAppleTransport()) return true;
            return false;
        }
    }

    public static final class Configuration {
        public final int value, attributes, maxPower, declaredInterfaceCount;
        public final List<UsbInterfaceDescriptor> interfaces;
        private Configuration(int value, int attributes, int maxPower, int count, List<UsbInterfaceDescriptor> interfaces) {
            this.value = value; this.attributes = attributes; this.maxPower = maxPower;
            this.declaredInterfaceCount = count; this.interfaces = Collections.unmodifiableList(interfaces);
        }
        public boolean hasAppleTransport() {
            for (UsbInterfaceDescriptor descriptor : interfaces) if (descriptor.hasAppleTransport()) return true;
            return false;
        }
    }

    public static final class UsbInterfaceDescriptor {
        public final int number, alternateSetting, interfaceClass, interfaceSubclass, interfaceProtocol;
        private final List<Endpoint> mutableEndpoints = new ArrayList<Endpoint>();
        public final List<Endpoint> endpoints = Collections.unmodifiableList(mutableEndpoints);
        private UsbInterfaceDescriptor(int number, int alt, int cls, int sub, int protocol) {
            this.number = number; this.alternateSetting = alt; this.interfaceClass = cls;
            this.interfaceSubclass = sub; this.interfaceProtocol = protocol;
        }
        public boolean hasAppleTransport() {
            return interfaceClass == 255 && ((interfaceSubclass == 254 && interfaceProtocol == 2)
                    || (interfaceSubclass == 253 && interfaceProtocol == 1));
        }
    }

    public static final class Endpoint {
        public final int address, attributes, maxPacketSize, interval;
        private Endpoint(int address, int attributes, int maxPacketSize, int interval) {
            this.address = address; this.attributes = attributes; this.maxPacketSize = maxPacketSize; this.interval = interval;
        }
    }
}
