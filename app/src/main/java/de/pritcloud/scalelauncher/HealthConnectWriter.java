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
import android.health.connect.datatypes.HeartRateRecord;
import android.health.connect.datatypes.LeanBodyMassRecord;
import android.health.connect.datatypes.Metadata;
import android.health.connect.datatypes.Record;
import android.health.connect.datatypes.WeightRecord;
import android.health.connect.datatypes.units.Mass;
import android.health.connect.datatypes.units.Percentage;
import android.health.connect.datatypes.units.Power;
import android.os.OutcomeReceiver;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Writes one completed S400 measurement directly to Android Health Connect. */
final class HealthConnectWriter {
    interface Callback {
        void onSuccess(int writtenRecordCount);
        void onError(String message);
    }

    private HealthConnectWriter() {}

    static void write(Context context,
                      long timestampMs,
                      String scaleMac,
                      S400Aggregator.Finalized measurement,
                      S400BodyComposition.Result composition,
                      Callback callback) {
        if (!HealthConnectSupport.isSupported()) {
            callback.onError("Health Connect benötigt Android 14 oder neuer");
            return;
        }
        if (!HealthConnectSupport.hasAllWritePermissions(context)) {
            callback.onError("Schreibrechte fehlen – in ScaleLauncher erneut verbinden");
            return;
        }

        HealthConnectManager manager = context.getSystemService(HealthConnectManager.class);
        if (manager == null) {
            callback.onError("Health-Connect-Systemdienst nicht verfügbar");
            return;
        }

        try {
            List<Record> records = buildRecords(
                    timestampMs,
                    scaleMac,
                    measurement,
                    composition);
            if (records.isEmpty()) {
                callback.onError("Keine gültigen Health-Connect-Werte vorhanden");
                return;
            }

            manager.insertRecords(
                    records,
                    context.getMainExecutor(),
                    new OutcomeReceiver<InsertRecordsResponse, HealthConnectException>() {
                        @Override public void onResult(InsertRecordsResponse response) {
                            int count = response == null || response.getRecords() == null
                                    ? records.size()
                                    : response.getRecords().size();
                            callback.onSuccess(count);
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

    private static List<Record> buildRecords(long timestampMs,
                                             String scaleMac,
                                             S400Aggregator.Finalized measurement,
                                             S400BodyComposition.Result composition) {
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

        List<Record> records = new ArrayList<>();

        if (isPositive(measurement.weightKg)) {
            records.add(new WeightRecord.Builder(
                    metadata(baseId + "-weight", scale),
                    time,
                    kilograms(measurement.weightKg))
                    .setZoneOffset(offset)
                    .build());
        }

        if (isPercent(composition.bodyFatPercent)) {
            records.add(new BodyFatRecord.Builder(
                    metadata(baseId + "-body-fat", scale),
                    time,
                    Percentage.fromValue(composition.bodyFatPercent))
                    .setZoneOffset(offset)
                    .build());
        }

        if (isPositive(composition.totalBodyWaterKg)) {
            records.add(new BodyWaterMassRecord.Builder(
                    metadata(baseId + "-body-water", scale),
                    time,
                    kilograms(composition.totalBodyWaterKg))
                    .setZoneOffset(offset)
                    .build());
        }

        if (isPositive(composition.boneKg)) {
            records.add(new BoneMassRecord.Builder(
                    metadata(baseId + "-bone-mass", scale),
                    time,
                    kilograms(composition.boneKg))
                    .setZoneOffset(offset)
                    .build());
        }

        if (isPositive(composition.fatFreeMassKg)) {
            records.add(new LeanBodyMassRecord.Builder(
                    metadata(baseId + "-lean-body-mass", scale),
                    time,
                    kilograms(composition.fatFreeMassKg))
                    .setZoneOffset(offset)
                    .build());
        }

        if (isPositive(composition.basalMetabolicRateKcal)) {
            double watts = composition.basalMetabolicRateKcal * 4184.0d / 86_400.0d;
            records.add(new BasalMetabolicRateRecord.Builder(
                    metadata(baseId + "-bmr", scale),
                    time,
                    Power.fromWatts(watts))
                    .setZoneOffset(offset)
                    .build());
        }

        if (measurement.heartRate != null
                && measurement.heartRate > 0
                && measurement.heartRate <= 300) {
            Instant endTime = time.plusSeconds(1);
            HeartRateRecord.HeartRateSample sample =
                    new HeartRateRecord.HeartRateSample(measurement.heartRate, time);
            records.add(new HeartRateRecord.Builder(
                    metadata(baseId + "-heart-rate", scale),
                    time,
                    endTime,
                    Collections.singletonList(sample))
                    .setStartZoneOffset(offset)
                    .setEndZoneOffset(offset)
                    .build());
        }

        return records;
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
