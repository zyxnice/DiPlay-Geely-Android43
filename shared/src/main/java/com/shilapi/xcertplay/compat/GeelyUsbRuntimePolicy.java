package com.shilapi.xcertplay.compat;

import java.io.IOException;

/** Fixed, reversible H53 runtime preparation. No persistent properties or arbitrary paths. */
public final class GeelyUsbRuntimePolicy {
    static final String HOST_CONFIG = "accessory,ncm";
    static final String ADB_CONFIG = "accessory,ncm,adb";

    interface Port {
        State read() throws Exception;
        void configuration(String value) throws Exception;
        void power(String value) throws Exception;
        void charging(String value) throws Exception;
        void awaitHost() throws Exception;
        void beginRollback();
        void awaitRestored(State original) throws Exception;
    }

    static final class State {
        final String config, role, charging, power, fault;
        final boolean activeApple;
        State(String config, String role, String charging, String power, String fault, boolean activeApple) {
            this.config = config; this.role = role; this.charging = charging;
            this.power = power; this.fault = fault; this.activeApple = activeApple;
        }
        boolean powered() { return "on".equals(power) || "normal/on".equals(power); }
        boolean ready() { return "0".equals(role) && powered() && "sdp".equals(charging) && "working fine".equals(fault); }
    }

    static boolean matches(int sdk, String board, String display, boolean optedIn) {
        return optedIn && sdk == 18 && "C3ALFUS".equals(board) && "06.03.08600.H53.00060".equals(display);
    }

    static String prepare(Port port) throws Exception {
        State before = port.read();
        // An already selected CarPlay device may belong to a live session: never reset it.
        if (before.activeApple && "0".equals(before.role)) return "active-preserved";
        validate(before);
        if (before.ready()) return "ready";
        boolean changedConfig = false, changedPower = false, changedCharging = false;
        try {
            if (!"0".equals(before.role) || !HOST_CONFIG.equals(before.config)) {
                changedConfig = true;
                port.configuration(HOST_CONFIG);
                port.awaitHost();
            }
            State host = port.read();
            if (!"0".equals(host.role) || !"working fine".equals(host.fault)) throw new IOException("host_not_ready");
            if (!host.powered()) { changedPower = true; port.power("on"); }
            if (!"sdp".equals(host.charging)) { changedCharging = true; port.charging("sdp"); }
            if (!port.read().ready()) throw new IOException("runtime_readback");
            return "prepared";
        } catch (Exception failure) {
            // Restore only writes owned by this attempt. A rollback failure remains visible.
            port.beginRollback();
            Exception rollbackFailure = null;
            try { if (changedCharging) port.charging(before.charging); }
            catch (Exception rollback) { rollbackFailure = rollback; }
            try { if (changedPower) port.power(before.powered() ? "on" : "off"); }
            catch (Exception rollback) { rollbackFailure = rollback; }
            try { if (changedConfig) port.configuration(before.config); }
            catch (Exception rollback) { rollbackFailure = rollback; }
            try {
                if (changedConfig) port.awaitRestored(before);
                State restored = port.read();
                if ((changedConfig && (!before.config.equals(restored.config) || !before.role.equals(restored.role)))
                        || (changedPower && before.powered() != restored.powered())
                        || (changedCharging && !before.charging.equals(restored.charging)))
                    throw new IOException("runtime_rollback_readback");
            } catch (Exception rollback) { rollbackFailure = rollback; }
            if (rollbackFailure != null) throw new IOException("runtime_rollback_failed", rollbackFailure);
            throw failure;
        }
    }

    private static void validate(State state) throws IOException {
        if (!HOST_CONFIG.equals(state.config) && !ADB_CONFIG.equals(state.config)) throw new IOException("unexpected_usb_config");
        if (!"0".equals(state.role) && !"1".equals(state.role)) throw new IOException("unexpected_usb_role");
        if (!"sdp".equals(state.charging) && !"cdp".equals(state.charging) && !"dcp".equals(state.charging))
            throw new IOException("unexpected_charging_mode");
        if (!state.powered() && !"off".equals(state.power) && !"normal/off".equals(state.power))
            throw new IOException("unexpected_power_state");
        if (!"working fine".equals(state.fault)) throw new IOException("otg_power_fault");
    }
}
