package de.pritcloud.scalelauncher;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    final String targetProfileId;
    final boolean requiresClaim;
    final boolean manualRescue;
    final List<String> candidateProfileIds;

    private PeerMeasurementPayload(String measurementId,
                                   String scaleMac,
                                   long timestampMs,
                                   float weightKg,
                                   float impedanceHigh,
                                   float impedanceLow,
                                   Integer scaleProfileId,
                                   String targetProfileId,
                                   boolean requiresClaim,
                                   boolean manualRescue,
                                   List<String> candidateProfileIds) {
        this.measurementId = measurementId;
        this.scaleMac = scaleMac;
        this.timestampMs = timestampMs;
        this.weightKg = weightKg;
        this.impedanceHigh = impedanceHigh;
        this.impedanceLow = impedanceLow;
        this.scaleProfileId = scaleProfileId;
        this.targetProfileId =
                targetProfileId == null
                        ? ""
                        : targetProfileId;
        this.requiresClaim = requiresClaim;
        this.manualRescue = manualRescue;
        this.candidateProfileIds =
                candidateProfileIds == null
                        ? List.of()
                        : List.copyOf(
                                candidateProfileIds);
    }

    static PeerMeasurementPayload forUniqueTarget(
            String scaleMac,
            S400FinalMeasurement measurement,
            String targetProfileId) {
        if (!UserProfile.isValidHouseholdProfileId(
                targetProfileId)) {
            throw new IllegalArgumentException(
                    "Invalid target household profile");
        }

        PeerMeasurementPayload payload =
                new PeerMeasurementPayload(
                        measurement.measurementId,
                        normalizeMac(scaleMac),
                        measurement.timestampMs,
                        measurement.weightKg,
                        measurement.impedanceHigh,
                        measurement.impedanceLow,
                        measurement.scaleProfileId,
                        targetProfileId,
                        false,
                        false,
                        List.of());

        if (!payload.isValid()) {
            throw new IllegalArgumentException(
                    "Invalid routed peer measurement");
        }

        return payload;
    }

    static PeerMeasurementPayload forClaim(
            String scaleMac,
            S400FinalMeasurement measurement,
            List<String> candidateProfileIds) {
        return forClaim(
                scaleMac,
                measurement,
                candidateProfileIds,
                false);
    }

    static PeerMeasurementPayload forManualRescue(
            String scaleMac,
            S400FinalMeasurement measurement,
            List<String> candidateProfileIds) {
        return forClaim(
                scaleMac,
                measurement,
                candidateProfileIds,
                true);
    }

    private static PeerMeasurementPayload forClaim(
            String scaleMac,
            S400FinalMeasurement measurement,
            List<String> candidateProfileIds,
            boolean manualRescue) {
        PeerMeasurementPayload payload =
                new PeerMeasurementPayload(
                        measurement.measurementId,
                        normalizeMac(scaleMac),
                        measurement.timestampMs,
                        measurement.weightKg,
                        measurement.impedanceHigh,
                        measurement.impedanceLow,
                        measurement.scaleProfileId,
                        "",
                        true,
                        manualRescue,
                        candidateProfileIds);

        if (!payload.isValid()) {
            throw new IllegalArgumentException(
                    "Invalid claim peer measurement");
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

            if (!targetProfileId.isBlank()) {
                object.put(
                        "targetProfileId",
                        targetProfileId);
            }

            object.put(
                    "requiresClaim",
                    requiresClaim);

            if (manualRescue) {
                object.put(
                        "manualRescue",
                        true);
            }

            if (!candidateProfileIds.isEmpty()) {
                JSONArray candidates =
                        new JSONArray();

                for (String profileId :
                        candidateProfileIds) {
                    candidates.put(
                            profileId);
                }

                object.put(
                        "candidateProfileIds",
                        candidates);
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

            List<String> candidateProfileIds =
                    new ArrayList<>();

            JSONArray candidates =
                    object.optJSONArray(
                            "candidateProfileIds");

            if (candidates != null) {
                for (int index = 0;
                     index < candidates.length();
                     index++) {
                    String profileId =
                            candidates.optString(
                                    index,
                                    "");

                    if (!profileId.isBlank()) {
                        candidateProfileIds.add(
                                profileId);
                    }
                }
            }

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
                            scaleProfileId,
                            object.optString(
                                    "targetProfileId",
                                    ""),
                            object.optBoolean(
                                    "requiresClaim",
                                    false),
                            object.optBoolean(
                                    "manualRescue",
                                    false),
                            candidateProfileIds);

            return payload.isValid() ? payload : null;
        } catch (JSONException exception) {
            return null;
        }
    }

    String transportMessageId() {
        if (!isValid()) {
            throw new IllegalStateException(
                    "Invalid peer measurement payload");
        }

        if (requiresClaim) {
            return manualRescue
                    ? "rescue:" + measurementId
                    : measurementId;
        }

        return "route:" + measurementId;
    }

    String routedPayloadFingerprint() {
        if (!isValid()
                || requiresClaim) {
            throw new IllegalStateException(
                    "Fingerprint requires routed peer measurement");
        }

        try {
            MessageDigest digest =
                    MessageDigest.getInstance(
                            "SHA-256");

            updateFingerprintString(
                    digest,
                    "scalelauncher-routed-measurement-v1");

            updateFingerprintInt(
                    digest,
                    VERSION);

            updateFingerprintString(
                    digest,
                    measurementId);

            updateFingerprintString(
                    digest,
                    scaleMac);

            updateFingerprintLong(
                    digest,
                    timestampMs);

            updateFingerprintInt(
                    digest,
                    Float.floatToIntBits(
                            weightKg));

            updateFingerprintInt(
                    digest,
                    Float.floatToIntBits(
                            impedanceHigh));

            updateFingerprintInt(
                    digest,
                    Float.floatToIntBits(
                            impedanceLow));

            if (scaleProfileId == null) {
                updateFingerprintInt(
                        digest,
                        0);
            } else {
                updateFingerprintInt(
                        digest,
                        1);

                updateFingerprintInt(
                        digest,
                        scaleProfileId);
            }

            updateFingerprintString(
                    digest,
                    targetProfileId);

            return fingerprintHex(
                    digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 unavailable",
                    exception);
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
                && measurementId.length() <= 200
                && S400GattProtocol.isValidMacAddress(scaleMac)
                && timestampMs > 0L
                && Float.isFinite(weightKg)
                && weightKg > 0f
                && Float.isFinite(impedanceHigh)
                && impedanceHigh > 0f
                && Float.isFinite(impedanceLow)
                && impedanceLow > 0f
                && validCandidateProfileIds(
                        candidateProfileIds)
                && (!manualRescue
                    || requiresClaim)
                && (requiresClaim
                    ? targetProfileId.isBlank()
                        && !candidateProfileIds.isEmpty()
                    : UserProfile.isValidHouseholdProfileId(
                            targetProfileId)
                        && candidateProfileIds.isEmpty());
    }

    private static void updateFingerprintString(
            MessageDigest digest,
            String value) {
        byte[] bytes =
                value.getBytes(
                        StandardCharsets.UTF_8);

        updateFingerprintInt(
                digest,
                bytes.length);

        digest.update(
                bytes);
    }

    private static void updateFingerprintInt(
            MessageDigest digest,
            int value) {
        digest.update(
                (byte) (value >>> 24));

        digest.update(
                (byte) (value >>> 16));

        digest.update(
                (byte) (value >>> 8));

        digest.update(
                (byte) value);
    }

    private static void updateFingerprintLong(
            MessageDigest digest,
            long value) {
        digest.update(
                (byte) (value >>> 56));

        digest.update(
                (byte) (value >>> 48));

        digest.update(
                (byte) (value >>> 40));

        digest.update(
                (byte) (value >>> 32));

        digest.update(
                (byte) (value >>> 24));

        digest.update(
                (byte) (value >>> 16));

        digest.update(
                (byte) (value >>> 8));

        digest.update(
                (byte) value);
    }

    private static String fingerprintHex(
            byte[] value) {
        StringBuilder result =
                new StringBuilder(
                        value.length * 2);

        for (byte item : value) {
            int unsigned =
                    item & 0xff;

            result.append(
                    Character.forDigit(
                            unsigned >>> 4,
                            16));

            result.append(
                    Character.forDigit(
                            unsigned & 0x0f,
                            16));
        }

        return result.toString();
    }

    private static boolean validCandidateProfileIds(
            List<String> profileIds) {
        if (profileIds == null
                || profileIds.size() > 100) {
            return false;
        }

        Set<String> unique =
                new HashSet<>();

        for (String profileId :
                profileIds) {
            if (!UserProfile.isValidHouseholdProfileId(
                    profileId)
                    || !unique.add(
                            profileId)) {
                return false;
            }
        }

        return true;
    }

    private static String normalizeMac(String value) {
        return value == null
                ? ""
                : value.trim().toUpperCase(java.util.Locale.ROOT);
    }
}
