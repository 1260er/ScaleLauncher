package de.pritcloud.scalelauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Set;

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

    @Test
    public void providerApi3OnlyIsAccepted() {
        assertFalse(
                new OpenScaleProvider.Meta(
                        2,
                        1)
                        .supportsRequiredApi());

        assertTrue(
                new OpenScaleProvider.Meta(
                        3,
                        1)
                        .supportsRequiredApi());

        assertFalse(
                new OpenScaleProvider.Meta(
                        4,
                        1)
                        .supportsRequiredApi());
    }

    @Test
    public void api3JsonUsesIdentityOnly()
            throws Exception {
        JSONArray values =
                new JSONArray();

        OpenScaleProvider.add(
                values,
                "builtin.weight",
                "Weight",
                "kg",
                "FLOAT",
                false,
                70.5f);

        assertEquals(
                1,
                values.length());

        JSONObject item =
                values.getJSONObject(
                        0);

        assertEquals(
                "builtin.weight",
                item.getString(
                        "identity"));

        assertEquals(
                "kg",
                item.getString(
                        "unit"));

        assertEquals(
                "FLOAT",
                item.getString(
                        "inputType"));

        assertEquals(
                70.5d,
                item.getDouble(
                        "value"),
                0.0001d);

        assertFalse(
                item.has(
                        "key"));

        assertFalse(
                item.has(
                        "typeId"));
    }

    @Test
    public void api3SummaryReadsIdentityAndWeight() {
        OpenScaleProvider.JsonSummary summary =
                OpenScaleProvider.summarizeValuesJson(
                        "[{\"identity\":\"builtin.weight\",\"value\":70.5},"
                                + "{\"identity\":\"ble.ecw\",\"value\":26.3}]");

        assertEquals(
                2,
                summary.count);

        assertTrue(
                summary.identities.contains(
                        "builtin.weight"));

        assertTrue(
                summary.identities.contains(
                        "ble.ecw"));

        assertNotNull(
                summary.weightKg);

        assertEquals(
                70.5d,
                summary.weightKg,
                0.0001d);
    }

    @Test
    public void legacyKeyOnlyJsonIsIgnored() {
        OpenScaleProvider.JsonSummary summary =
                OpenScaleProvider.summarizeValuesJson(
                        "[{\"key\":\"WEIGHT\",\"value\":70.5}]");

        assertEquals(
                0,
                summary.count);

        assertTrue(
                summary.identities.isEmpty());

        assertNull(
                summary.weightKg);
    }

    @Test
    public void optionalBleValuesDoNotDetermineCompleteness() {
        String json =
                "["
                        + "{\"identity\":\"builtin.weight\",\"value\":70.5},"
                        + "{\"identity\":\"builtin.bmi\",\"value\":22.0},"
                        + "{\"identity\":\"builtin.body_fat\",\"value\":18.0},"
                        + "{\"identity\":\"builtin.water\",\"value\":55.0},"
                        + "{\"identity\":\"builtin.muscle\",\"value\":45.0},"
                        + "{\"identity\":\"builtin.lbm\",\"value\":57.8},"
                        + "{\"identity\":\"builtin.bone\",\"value\":3.0},"
                        + "{\"identity\":\"builtin.visceral_fat\",\"value\":7.0},"
                        + "{\"identity\":\"builtin.bmr\",\"value\":1600.0},"
                        + "{\"identity\":\"builtin.impedance\",\"value\":500.0},"
                        + "{\"identity\":\"builtin.impedance_low\",\"value\":450.0},"
                        + "{\"identity\":\"builtin.protein\",\"value\":18.0}"
                        + "]";

        OpenScaleProvider.JsonSummary summary =
                OpenScaleProvider.summarizeValuesJson(
                        json);

        Set<String> missing =
                OpenScaleProvider.missingRequiredIdentities(
                        summary,
                        70.5f);

        assertTrue(
                missing.isEmpty());
    }

    @Test
    public void wrongWeightFailsCompleteness() {
        OpenScaleProvider.JsonSummary summary =
                OpenScaleProvider.summarizeValuesJson(
                        "[{\"identity\":\"builtin.weight\",\"value\":70.5}]");

        Set<String> missing =
                OpenScaleProvider.missingRequiredIdentities(
                        summary,
                        71.0f);

        assertTrue(
                missing.contains(
                        "builtin.weight"));
    }
}
