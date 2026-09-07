package de.pritcloud.scalelauncher;

import java.io.File;

/**
 * Debug-only fault injection for deterministic openScale queue/retry tests.
 *
 * The production/release build always passes debugEnabled=false and therefore
 * never consumes or reacts to these marker files.
 */
final class OpenScaleDebugFaults {
    static final String FAIL_ONCE =
            "debug_fail_openscale_once";

    static final String FAIL_TWICE =
            "debug_fail_openscale_twice";

    private OpenScaleDebugFaults() {
    }

    static boolean consume(
            File cacheDir,
            boolean debugEnabled) {
        if (!debugEnabled
                || cacheDir == null) {
            return false;
        }

        File once =
                new File(
                        cacheDir,
                        FAIL_ONCE);

        File twice =
                new File(
                        cacheDir,
                        FAIL_TWICE);

        /*
         * FAIL_TWICE becomes FAIL_ONCE after the first consumption.
         * The second attempt then consumes FAIL_ONCE and removes it.
         */
        if (twice.isFile()) {
            if (once.exists()
                    && !once.delete()) {
                return false;
            }

            if (!twice.renameTo(
                    once)) {
                return false;
            }

            return true;
        }

        return once.isFile()
                && once.delete();
    }
}
