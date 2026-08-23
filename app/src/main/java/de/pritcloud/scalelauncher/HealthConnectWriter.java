package de.pritcloud.scalelauncher;

import android.content.Context;
import android.health.connect.HealthConnectException;
import android.health.connect.HealthConnectManager;
import android.health.connect.InsertRecordsResponse;
import android.health.connect.datatypes.BasalMetabolicRateRecord;
import android.health.connect.datatypes.BodyFatRecord;
import android.health.connect.datatypes.BodyWaterMassRecord;
import android.health.connect.datatypes.BoneMassRecord;
import android.health.connect.datatypes.Device;
import android.health.connect.datatypes.HeightRecord;
import android.health.connect.datatypes.LeanBodyMassRecord;
import android.health.connect.datatypes.Metadata;
import android.health.connect.datatypes.Record;
import android.health.connect.datatypes.WeightRecord;
import android.health.connect.datatypes.units.Length;
import android.health.connect.datatypes.units.Mass;
import android.health.connect.datatypes.units.Percentage;
import android.health.connect.datatypes.units.Power;
import android.os.OutcomeReceiver;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Writes selected values from one completed S400 measurement to Android Health Connect. */
final class HealthConnectWriter {
    interface Callback {
        void onSuccess(int writtenRecordCount, String writtenValues);
        void onError(String message);
    }

    private static final class BuiltRecords {
        final List<Record> records = new ArrayList<>();
        final List<String> labels = new ArrayList<>();

        void add(Record record, String label) {
            records.add(record);
            labels.add(label);
        }

        String summary() {
            return String.join(", ", labels);
        }
    }

    private HealthConnectWriter() {}

    static void write(Context context,
                      long timestampMs,
                      String scaleMac,
                      float heightCm,
                      S400FinalMeasurement measurement,
                      S400BodyComposition.Result composition,
                      HealthConnectSelection selection,
                      Callback callback) {
        if (!HealthConnectSupport.isSupported()) {
            callback.onError(context.getString(R.string.health_connect_requires_android_14));
            return;
        }
        if (selection == null || selection.count() == 0) {
            callback.onError(context.getString(R.string.hc_writer_error_no_values));
            return;
        }
        if (!HealthConnectSupport.hasWritePermissions(context, selection)) {
            callback.onError(context.getString(R.string.hc_writer_error_permissions));
            return;
        }

        HealthConnectManager manager = context.getSystemService(HealthConnectManager.class);
        if (manager == null) {
            callback.onError(context.getString(R.string.hc_writer_error_service_unavailable));
            return;
        }

        try {
            BuiltRecords built = buildRecords(
                    context,
                    timestampMs,
                    scaleMac,
                    heightCm,
                    measurement,
                    composition,
                    selection);
            if (built.records.isEmpty()) {
                callback.onError(context.getString(R.string.hc_writer_error_no_valid_values));
                return;
            }
            int expectedCount = expectedRecordCount(selection);
            if (built.records.size() != expectedCount) {
                callback.onError(context.getString(
                        R.string.hc_writer_error_incomplete_records,
                        built.records.size(),
                        expectedCount));
                return;
            }

            manager.insertRecords(
                    built.records,
                    context.getMainExecutor(),
                    new OutcomeReceiver<InsertRecordsResponse, HealthConnectException>() {
                        @Override public void onResult(InsertRecordsResponse response) {
                            if (response == null || response.getRecords() == null) {
                                callback.onError(context.getString(
                                        R.string.hc_writer_error_no_confirmation));
                                return;
                            }
                            int count = response.getRecords().size();
                            if (count != built.records.size()) {
                                callback.onError(context.getString(
                                        R.string.hc_writer_error_partial_confirmation,
                                        count,
                                        built.records.size()));
                                return;
                            }
                            callback.onSuccess(count, built.summary());
                        }

                        @Override public void onError(HealthConnectException error) {
                            String detail = error.getMessage();
                            if (detail == null || detail.isBlank()) {
                                detail = context.getString(R.string.hc_writer_error_code, error.getErrorCode());
                            }
                            callback.onError(detail);
                        }
                    });
        } catch (SecurityException e) {
            callback.onError(context.getString(R.string.hc_writer_error_permissions_revoked));
        } catch (IllegalArgumentException e) {
            callback.onError(context.getString(
                    R.string.hc_writer_error_invalid_value,
                    safeMessage(context, e)));
        } catch (RuntimeException e) {
            callback.onError(context.getString(
                    R.string.hc_writer_error_with_type,
                    e.getClass().getSimpleName(),
                    safeMessage(context, e)));
        }
    }

    private static int expectedRecordCount(HealthConnectSelection selection) {
        int count = 0;
        if (selection.weight || selection.bmi) count++; // WeightRecord
        if (selection.bmi) count++;                     // HeightRecord
        if (selection.bodyFat) count++;
        if (selection.bodyWater) count++;
        if (selection.boneMass) count++;
        if (selection.leanBodyMass) count++;
        if (selection.basalMetabolicRate) count++;
        return count;
    }

    private static BuiltRecords buildRecords(Context context,
                                              long timestampMs,
                                              String scaleMac,
                                              float heightCm,
                                              S400FinalMeasurement measurement,
                                              S400BodyComposition.Result composition,
                                              HealthConnectSelection selection) {
        Instant time = Instant.ofEpochMilli(timestampMs);
        ZoneOffset offset = ZoneId.systemDefault().getRules().getOffset(time);
        String normalizedMac = scaleMac == null
                ? "unknown"
                : scaleMac.replace(":", "").toLowerCase(Locale.ROOT);
        String baseId = "s400-" + normalizedMac + "-" + timestampMs;

        Device scale = new Device.Builder()
                .setManufacturer("Xiaomi")
                .setModel("Body Composition Scale S400")
                .setType(Device.DEVICE_TYPE_SCALE)
                .build();

        BuiltRecords built = new BuiltRecords();

        // Health Connect has no separate BMI record. For the BMI option we write
        // the measured weight and configured height so receiving apps can derive BMI.
        if ((selection.weight || selection.bmi) && isPositive(measurement.weightKg)) {
            built.add(new WeightRecord.Builder(
                    metadata(baseId + "-weight", scale),
                    time,
                    kilograms(measurement.weightKg))
                    .setZoneOffset(offset)
                    .build(), selection.weight
                    ? context.getString(R.string.health_connect_weight)
                    : context.getString(R.string.hc_writer_label_weight_bmi));
        }

        if (selection.bmi && isValidHeight(heightCm)) {
            built.add(new HeightRecord.Builder(
                    metadata(baseId + "-height", scale),
                    time,
                    Length.fromMeters(heightCm / 100.0d))
                    .setZoneOffset(offset)
                    .build(), context.getString(R.string.hc_writer_label_height_bmi));
        }

        if (selection.bodyFat && isPercent(composition.bodyFatPercent)) {
            built.add(new BodyFatRecord.Builder(
                    metadata(baseId + "-body-fat", scale),
                    time,
                    Percentage.fromValue(composition.bodyFatPercent))
                    .setZoneOffset(offset)
                    .build(), context.getString(R.string.health_connect_body_fat));
        }

        if (selection.bodyWater && isPositive(composition.totalBodyWaterKg)) {
            built.add(new BodyWaterMassRecord.Builder(
                    metadata(baseId + "-body-water", scale),
                    time,
                    kilograms(composition.totalBodyWaterKg))
                    .setZoneOffset(offset)
                    .build(), context.getString(R.string.health_connect_body_water));
        }

        if (selection.boneMass && isPositive(composition.boneKg)) {
            built.add(new BoneMassRecord.Builder(
                    metadata(baseId + "-bone-mass", scale),
                    time,
                    kilograms(composition.boneKg))
                    .setZoneOffset(offset)
                    .build(), context.getString(R.string.health_connect_bone_mass));
        }

        if (selection.leanBodyMass && isPositive(composition.fatFreeMassKg)) {
            built.add(new LeanBodyMassRecord.Builder(
                    metadata(baseId + "-lean-body-mass", scale),
                    time,
                    kilograms(composition.fatFreeMassKg))
                    .setZoneOffset(offset)
                    .build(), context.getString(R.string.health_connect_lean_body_mass));
        }

        if (selection.basalMetabolicRate && isPositive(composition.basalMetabolicRateKcal)) {
            double watts = composition.basalMetabolicRateKcal * 4184.0d / 86_400.0d;
            built.add(new BasalMetabolicRateRecord.Builder(
                    metadata(baseId + "-bmr", scale),
                    time,
                    Power.fromWatts(watts))
                    .setZoneOffset(offset)
                    .build(), context.getString(R.string.health_connect_bmr));
        }

        return built;
    }

    private static Metadata metadata(String clientRecordId, Device scale) {
        return new Metadata.Builder()
                .setClientRecordId(clientRecordId)
                .setClientRecordVersion(1L)
                .setDevice(scale)
                .setRecordingMethod(Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED)
                .build();
    }

    private static Mass kilograms(double value) {
        return Mass.fromGrams(value * 1_000.0d);
    }

    private static boolean isValidHeight(float heightCm) {
        return Float.isFinite(heightCm) && heightCm >= 100f && heightCm <= 230f;
    }

    private static boolean isPositive(Float value) {
        return value != null && Float.isFinite(value) && value > 0f;
    }

    private static boolean isPositive(float value) {
        return Float.isFinite(value) && value > 0f;
    }

    private static boolean isPercent(Float value) {
        return value != null && Float.isFinite(value) && value >= 0f && value <= 100f;
    }

    private static String safeMessage(Context context, Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? context.getString(R.string.hc_writer_no_detail)
                : message;
    }
}
