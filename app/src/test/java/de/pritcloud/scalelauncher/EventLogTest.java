package de.pritcloud.scalelauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class EventLogTest {
    @Test
    public void pruneKeepsNewestThreeThousandEntries() {
        StringBuilder stored = new StringBuilder();

        for (int index = 0; index < 3200; index++) {
            if (index > 0) {
                stored.append("\n");
            }

            stored.append("entry-").append(index);
        }

        String[] lines =
                EventLog.prune(stored.toString()).split("\n");

        assertEquals(3000, lines.length);
        assertEquals("entry-200", lines[0]);
        assertEquals("entry-3199", lines[2999]);
    }

    @Test
    public void newestFirstLimitsVisibleEntries() {
        String stored =
                "entry-1\n"
                        + "entry-2\n"
                        + "entry-3\n"
                        + "entry-4";

        assertEquals(
                "entry-4\nentry-3",
                EventLog.newestFirst(
                        stored,
                        2));

        assertEquals(
                "entry-4\nentry-3\nentry-2\nentry-1",
                EventLog.newestFirst(
                        stored,
                        10));
    }

    @Test
    public void pruneRespects512KiBCharacterLimit() {
        StringBuilder stored = new StringBuilder();
        String payload = "x".repeat(4096);

        for (int index = 0; index < 200; index++) {
            if (index > 0) {
                stored.append("\n");
            }

            stored.append("entry-")
                    .append(index)
                    .append("-")
                    .append(payload);
        }

        String pruned =
                EventLog.prune(stored.toString());

        assertTrue(pruned.length() <= 512 * 1024);
        assertTrue(pruned.contains("entry-199-"));
        assertFalse(pruned.contains("entry-0-"));
    }
}

