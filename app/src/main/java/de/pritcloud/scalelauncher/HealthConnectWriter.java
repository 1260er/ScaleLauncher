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
                      S400Aggregator.Finalized measurement,
                      S400BodyComposition.Result composition,
                      HealthConnectSelection selection,
                      Callback callback) {
        if (!HealthConnectSupport.isSupported()) {
            callback.onError("Health Connect benötigt Android 14 oder neuer");
            return;
        }
        if (selection == null || selection.count() == 0) {
            callback.onError("Keine Health-Connect-Werte ausgewählt");
            return;
        }
        if (!HealthConnectSupport.hasWritePermissions(context, selection)) {
            callback.onError("Schreibrechte für die ausgewählten Werte fehlen");
            return;
        }

        HealthConnectManager manager = context.getSystemService(HealthConnectManager.class);
        if (manager == null) {
            callback.onError("Health-Connect-Systemdienst nicht verfügbar");
            return;
        }

        try {
            BuiltRecords built = buildRecords(
                    timestampMs,
                    scaleMac,
                    heightCm,
                    measurement,
                    composition,
                    selection);
            if (built.records.isEmpty()) {
                callback.onError("Keine gültigen ausgewählten Health-Connect-Werte vorhanden");
                return;
            }

            manager.insertRecords(
                    built.records,
                    context.getMainExecutor(),
                    new OutcomeReceiver<InsertRecordsResponse, HealthConnectException>() {
                        @Override public void onResult(InsertRecordsResponse response) {
                            int count = response == null || response.getRecords() == null
                                    ? built.records.size()
                                    : response.getRecords().size();
                            callback.onSuccess(count, built.summary());
                        }

                        @Override public void onError(HealthConnectException error) {
                            String detail = error.getMessage();
                            if (detail == null || detail.isBlank()) {
                                detail = "Fehlercode " + error.getErrorCode();
                            }
                            callback.onError(detail);
                        }
                    });
        } catch (SecurityException e) {
            callback.onError("Schreibrechte fehlen oder wurden entzogen");
        } catch (IllegalArgumentException e) {
            callback.onError("Ungültiger Messwert: " + safeMessage(e));
        } catch (RuntimeException e) {
            callback.onError(e.getClass().getSimpleName() + ": " + safeMessage(e));
        }
    }

    private static BuiltRecords buildRecords(long timestampMs,
                                              String scaleMac,
                                              float heightCm,
                                              S400Aggregator.Finalized measurement,
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
                    .build(), selection.weight ? "Gewicht" : "Gewicht (BMI)");
        }

        if (selection.bmi && isValidHeight(heightCm)) {
            built.add(new HeightRecord.Builder(
                    metadata(baseId + "-height", scale),
                    time,
                    Length.fromMeters(heightCm / 100.0d))
                    .setZoneOffset(offset)
                    .build(), "Größe (BMI)");
        }

        if (selection.bodyFat && isPercent(composition.bodyFatPercent)) {
            built.add(new BodyFatRecord.Builder(
                    metadata(baseId + "-body-fat", scale),
                    time,
                    Percentage.fromValue(composition.bodyFatPercent))
                    .setZoneOffset(offset)
                    .build(), "Körperfett");
        }

        if (selection.bodyWater && isPositive(composition.totalBodyWaterKg)) {
            built.add(new BodyWaterMassRecord.Builder(
                    metadata(baseId + "-body-water", scale),
                    time,
                    kilograms(composition.totalBodyWaterKg))
                    .setZoneOffset(offset)
                    .build(), "Körperwasser");
        }

        if (selection.boneMass && isPositive(composition.boneKg)) {
            built.add(new BoneMassRecord.Builder(
                    metadata(baseId + "-bone-mass", scale),
                    time,
                    kilograms(composition.boneKg))
                    .setZoneOffset(offset)
                    .build(), "Knochenmasse");
        }

        if (selection.leanBodyMass && isPositive(composition.fatFreeMassKg)) {
            built.add(new LeanBodyMassRecord.Builder(
                    metadata(baseId + "-lean-body-mass", scale),
                    time,
                    kilograms(composition.fatFreeMassKg))
                    .setZoneOffset(offset)
                    .build(), "fettfreie Masse");
        }

        if (selection.basalMetabolicRate && isPositive(composition.basalMetabolicRateKcal)) {
            double watts = composition.basalMetabolicRateKcal * 4184.0d / 86_400.0d;
            built.add(new BasalMetabolicRateRecord.Builder(
                    metadata(baseId + "-bmr", scale),
                    time,
                    Power.fromWatts(watts))
                    .setZoneOffset(offset)
                    .build(), "Grundumsatz");
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

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? "ohne Detailangabe" : message;
    }
}
