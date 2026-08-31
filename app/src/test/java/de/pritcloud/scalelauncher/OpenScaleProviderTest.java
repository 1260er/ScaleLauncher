package de.pritcloud.scalelauncher;

import static org.junit.Assert.fail;

import org.junit.Test;

public final class OpenScaleProviderTest {
    @Test
    public void nullUserQueryResultIsRejected() {
        try {
            OpenScaleProvider.requireUsersCursor(
                    null);

            fail(
                    "A null openScale user cursor must be treated as an error");
        } catch (IllegalStateException expected) {
            // expected
        }
    }
}
