package de.pritcloud.scalelauncher;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Transport-neutral wire representation of one raw S400 FINAL measurement.
 *
 * Deliberately contains no user name, birth date, body composition,
 * openScale identifiers, Health Connect data or S400 login token.
 */
final class PeerMeasurementPayload {
    static final int VERSION = 1;
    static final String TYPE = "s400_measurement";

    final String measurementId;
    final String scaleMac;
    final long timestampMs;
    final float weightKg;
    final float impedanceHigh;
    final float impedanceLow;
    final Integer scaleProfileId;

    private PeerMeasurementPayload(String measurementId,
                                   String scaleMac,
                                   long timestampMs,
                                   float weightKg,
                                   float impedanceHigh,
                                   float impedanceLow,
                                   Integer scaleProfileId) {
        this.measurementId = measurementId;
        this.scaleMac = scaleMac;
        this.timestampMs = timestampMs;
        this.weightKg = weightKg;
        this.impedanceHigh = impedanceHigh;
        this.impedanceLow = impedanceLow;
        this.scaleProfileId = scaleProfileId;
    }

    static PeerMeasurementPayload fromMeasurement(
            String scaleMac,
            S400FinalMeasurement measurement) {
        if (measurement == null
                || !measurement.isComplete()
                || measurement.impedanceLow == null) {
            throw new IllegalArgumentException(
                    "Incomplete S400 measurement");
        }

        PeerMeasurementPayload payload =
                new PeerMeasurementPayload(
                        measurement.measurementId,
                        normalizeMac(scaleMac),
                        measurement.timestampMs,
                        measurement.weightKg,
                        measurement.impedanceHigh,
                        measurement.impedanceLow,
                        measurement.scaleProfileId);

        if (!payload.isValid()) {
            throw new IllegalArgumentException(
                    "Invalid peer measurement payload");
        }
        return payload;
    }

    String encode() {
        if (!isValid()) {
            throw new IllegalStateException(
                    "Invalid peer measurement payload");
        }

        try {
            JSONObject object = new JSONObject();
            object.put("version", VERSION);
            object.put("type", TYPE);
            object.put("measurementId", measurementId);
            object.put("scaleMac", scaleMac);
            object.put("timestampMs", timestampMs);
            object.put("weightKg", weightKg);
            object.put("impedanceHigh", impedanceHigh);
            object.put("impedanceLow", impedanceLow);

            if (scaleProfileId != null) {
                object.put("scaleProfileId", scaleProfileId);
            }

            return object.toString();
        } catch (JSONException exception) {
            throw new IllegalStateException(
                    "Could not encode peer measurement",
                    exception);
        }
    }

    static PeerMeasurementPayload decode(String encoded) {
        if (encoded == null || encoded.isBlank()) return null;

        try {
            JSONObject object = new JSONObject(encoded);

            if (object.optInt("version", -1) != VERSION
                    || !TYPE.equals(object.optString("type", ""))) {
                return null;
            }

            Integer scaleProfileId =
                    object.has("scaleProfileId")
                            && !object.isNull("scaleProfileId")
                            ? object.optInt("scaleProfileId")
                            : null;

            PeerMeasurementPayload payload =
                    new PeerMeasurementPayload(
                            object.optString("measurementId", ""),
                            normalizeMac(
                                    object.optString("scaleMac", "")),
                            object.optLong("timestampMs", 0L),
                            (float) object.optDouble(
                                    "weightKg",
                                    Double.NaN),
                            (float) object.optDouble(
                                    "impedanceHigh",
                                    Double.NaN),
                            (float) object.optDouble(
                                    "impedanceLow",
                                    Double.NaN),
                            scaleProfileId);

            return payload.isValid() ? payload : null;
        } catch (JSONException exception) {
            return null;
        }
    }

    S400FinalMeasurement toMeasurement() {
        if (!isValid()) {
            throw new IllegalStateException(
                    "Invalid peer measurement payload");
        }

        return new S400FinalMeasurement(
                measurementId,
                weightKg,
                impedanceHigh,
                impedanceLow,
                timestampMs,
                scaleProfileId);
    }

    boolean isValid() {
        return measurementId != null
                && !measurementId.isBlank()
                && S400GattProtocol.isValidMacAddress(scaleMac)
                && timestampMs > 0L
                && Float.isFinite(weightKg)
                && weightKg > 0f
                && Float.isFinite(impedanceHigh)
                && impedanceHigh > 0f
                && Float.isFinite(impedanceLow)
                && impedanceLow > 0f;
    }

    private static String normalizeMac(String value) {
        return value == null
                ? ""
                : value.trim().toUpperCase(java.util.Locale.ROOT);
    }
}
