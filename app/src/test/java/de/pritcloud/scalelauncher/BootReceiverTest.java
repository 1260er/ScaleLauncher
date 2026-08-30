package de.pritcloud.scalelauncher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Intent;

import org.junit.Test;

public final class BootReceiverTest {
    @Test
    public void bootRequiresAutoStart() {
        assertFalse(
                BootReceiver.shouldStart(
                        Intent.ACTION_BOOT_COMPLETED,
                        false,
                        ServiceState.Mode.RUNNING));

        assertTrue(
                BootReceiver.shouldStart(
                        Intent.ACTION_BOOT_COMPLETED,
                        true,
                        ServiceState.Mode.STOPPED));
    }

    @Test
    public void appUpdateRestoresRunningMonitoringWithoutAutoStart() {
        assertTrue(
                BootReceiver.shouldStart(
                        Intent.ACTION_MY_PACKAGE_REPLACED,
                        false,
                        ServiceState.Mode.RUNNING));

        assertTrue(
                BootReceiver.shouldStart(
                        Intent.ACTION_MY_PACKAGE_REPLACED,
                        false,
                        ServiceState.Mode.STARTING));
    }

    @Test
    public void appUpdateKeepsStoppedMonitoringStopped() {
        assertFalse(
                BootReceiver.shouldStart(
                        Intent.ACTION_MY_PACKAGE_REPLACED,
                        true,
                        ServiceState.Mode.STOPPED));

        assertFalse(
                BootReceiver.shouldStart(
                        Intent.ACTION_MY_PACKAGE_REPLACED,
                        true,
                        ServiceState.Mode.ERROR));
    }

    @Test
    public void ignoresUnrelatedBroadcasts() {
        assertFalse(
                BootReceiver.shouldStart(
                        "example.UNRELATED",
                        true,
                        ServiceState.Mode.RUNNING));
    }
}
