package com.shilapi.xcertplay.compat;

import org.junit.Test;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public final class GeelyUsbRuntimePolicyTest {
    @Test public void coldBootRestoresHostPowerAndSdpWithoutPersistence() throws Exception {
        Fake port = new Fake();
        assertEquals("prepared", GeelyUsbRuntimePolicy.prepare(port));
        assertTrue(port.read().ready());
        assertEquals("config:accessory,ncm,host,power:on,charge:sdp", String.join(",", port.calls));
    }
    @Test public void stableWaitingDoesNotRewriteNodes() throws Exception {
        Fake port = new Fake(); GeelyUsbRuntimePolicy.prepare(port); port.calls.clear();
        assertEquals("ready", GeelyUsbRuntimePolicy.prepare(port));
        assertTrue(port.calls.isEmpty());
    }
    @Test public void oemChargingReversionOnlyRepairsCharging() throws Exception {
        Fake port = new Fake(); GeelyUsbRuntimePolicy.prepare(port); port.calls.clear(); port.charge = "dcp";
        GeelyUsbRuntimePolicy.prepare(port);
        assertEquals("[charge:sdp]", port.calls.toString());
    }
    @Test public void selectedCarPlayDeviceIsNeverReset() throws Exception {
        Fake port = new Fake(); port.role = "0"; port.active = true;
        assertEquals("active-preserved", GeelyUsbRuntimePolicy.prepare(port));
        assertTrue(port.calls.isEmpty());
    }
    @Test public void overcurrentAndUnknownStateAreRejectedBeforeWrites() throws Exception {
        for (int scenario = 0; scenario < 4; scenario++) {
            Fake port = new Fake();
            if (scenario == 0) port.fault = "overcurrent";
            if (scenario == 1) port.config = "mtp,adb";
            if (scenario == 2) port.role = "unknown";
            if (scenario == 3) port.power = "fault/off";
            try { GeelyUsbRuntimePolicy.prepare(port); fail("unsafe state accepted"); } catch (IOException expected) { }
            assertTrue(port.calls.isEmpty());
        }
    }
    @Test public void failedHostTransitionRestoresOnlyConfiguration() throws Exception {
        Fake port = new Fake(); port.hostFails = true;
        try { GeelyUsbRuntimePolicy.prepare(port); fail(); } catch (IOException expected) { }
        assertEquals("accessory,ncm,adb", port.config);
        assertEquals("[config:accessory,ncm, host, config:accessory,ncm,adb]", port.calls.toString());
    }
    @Test public void failedReadbackRestoresAllOwnedWrites() throws Exception {
        Fake port = new Fake(); port.ignoreSdp = true;
        try { GeelyUsbRuntimePolicy.prepare(port); fail(); } catch (IOException expected) { }
        assertEquals("accessory,ncm,adb", port.config); assertEquals("dcp", port.charge); assertEquals("off", port.power);
        assertEquals("1", port.role);
    }
    @Test public void platformGuardIsExactAndOptIn() {
        assertTrue(GeelyUsbRuntimePolicy.matches(18, "C3ALFUS", "06.03.08600.H53.00060", true));
        assertFalse(GeelyUsbRuntimePolicy.matches(19, "C3ALFUS", "06.03.08600.H53.00060", true));
        assertFalse(GeelyUsbRuntimePolicy.matches(18, "C3ALFUS", "06.03.08600.H53.00061", true));
        assertFalse(GeelyUsbRuntimePolicy.matches(18, "C3ALFUS", "06.03.08600.H53.00060", false));
        assertFalse(GeelyUsbRuntimePolicy.matches(18, "other", "06.03.08600.H53.00060", true));
    }

    @Test public void cancellationDuringHostWaitPreventsForwardPowerAndChargeWrites() throws Exception {
        Fake port = new Fake(); port.cancelDuringWait = true;
        try { GeelyUsbRuntimePolicy.prepare(port); fail(); } catch (IOException expected) { }
        assertEquals("[config:accessory,ncm, host, config:accessory,ncm,adb]", port.calls.toString());
        assertEquals("accessory,ncm,adb", port.config);
    }
    @Test public void rollbackContinuesAfterItsFirstWriteFails() throws Exception {
        Fake port = new Fake(); port.ignoreSdp = true; port.failChargeRollback = true;
        try { GeelyUsbRuntimePolicy.prepare(port); fail(); }
        catch (IOException expected) { assertEquals("runtime_rollback_failed", expected.getMessage()); }
        assertEquals("off", port.power); assertEquals("accessory,ncm,adb", port.config);
    }
    @Test public void expiredOrRevokedLeaseAllowsOnlyBoundedRollback() throws Exception {
        java.io.File lease = java.io.File.createTempFile("geely-runtime-", ".lease");
        String nonce = "0123456789abcdef0123456789abcdef";
        try {
            java.io.FileOutputStream output = new java.io.FileOutputStream(lease);
            output.write(nonce.getBytes("US-ASCII")); output.close();
            GeelyUsbRuntimeHelper.verifyLease(lease, nonce, 100, 200, false);
            try { GeelyUsbRuntimeHelper.verifyLease(lease, nonce, 201, 200, false); fail(); }
            catch (IOException expected) { assertEquals("runtime_expired", expected.getMessage()); }
            assertTrue(lease.delete());
            try { GeelyUsbRuntimeHelper.verifyLease(lease, nonce, 101, 200, false); fail(); }
            catch (IOException expected) { assertEquals("runtime_cancelled", expected.getMessage()); }
            GeelyUsbRuntimeHelper.verifyLease(lease, nonce, 201, 200, true);
            try { GeelyUsbRuntimeHelper.verifyLease(lease, nonce, 1700, 200, true); fail(); }
            catch (IOException expected) { assertEquals("runtime_expired", expected.getMessage()); }
        } finally { lease.delete(); }
    }
    @Test public void restoredPropertyWithWrongRoleIsNotSuccessfulRollback() throws Exception {
        Fake port = new Fake(); port.ignoreSdp = true; port.ignoreRoleRestore = true;
        try { GeelyUsbRuntimePolicy.prepare(port); fail(); }
        catch (IOException expected) { assertEquals("runtime_rollback_failed", expected.getMessage()); }
        assertEquals("accessory,ncm,adb", port.config); assertEquals("0", port.role);
    }
    @Test public void cancelledPreparationRemainsPendingUntilRollbackCompletes() throws Exception {
        java.lang.reflect.Field pending = GeelyUsbRuntimeRecovery.class.getDeclaredField("inFlight");
        pending.setAccessible(true);
        Object previous = pending.get(null);
        java.util.concurrent.FutureTask<String> rollback = new java.util.concurrent.FutureTask<>(() -> "rolled-back");
        try {
            pending.set(null, rollback);
            assertTrue(GeelyUsbRuntimeRecovery.operationPending());
            rollback.run();
            assertFalse(GeelyUsbRuntimeRecovery.operationPending());
        } finally { pending.set(null, previous); }
    }

    private static final class Fake implements GeelyUsbRuntimePolicy.Port {
        String config = "accessory,ncm,adb", role = "1", charge = "dcp", power = "off", fault = "working fine";
        boolean active, hostFails, ignoreSdp, cancelDuringWait, cancelled, rollback, failChargeRollback, ignoreRoleRestore;
        List<String> calls = new ArrayList<>();
        public GeelyUsbRuntimePolicy.State read() throws IOException {
            if (cancelled && !rollback) throw new IOException("runtime_cancelled");
            return new GeelyUsbRuntimePolicy.State(config, role, charge, power, fault, active);
        }
        public void configuration(String value) { calls.add("config:" + value); config = value; }
        public void power(String value) { calls.add("power:" + value); power = value; }
        public void charging(String value) throws IOException {
            calls.add("charge:" + value);
            if (rollback && failChargeRollback) throw new IOException("restore_charge_failed");
            if (!ignoreSdp || !value.equals("sdp")) charge = value;
        }
        public void awaitHost() throws IOException {
            calls.add("host"); if (hostFails) throw new IOException("host_timeout"); role = "0";
            if (cancelDuringWait) cancelled = true;
        }
        public void beginRollback() { rollback = true; }
        public void awaitRestored(GeelyUsbRuntimePolicy.State original) {
            if (!ignoreRoleRestore) role = original.role;
        }
    }
}
