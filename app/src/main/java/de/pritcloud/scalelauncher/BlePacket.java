package de.pritcloud.scalelauncher;

import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.os.ParcelUuid;
import android.util.SparseArray;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class BlePacket {
    final String signature;
    final String details;
    final boolean activityPacket;
    final byte[] activityData;

    private BlePacket(String signature, String details, boolean activityPacket, byte[] activityData) {
        this.signature = signature;
        this.details = details;
        this.activityPacket = activityPacket;
        this.activityData = activityData;
    }

    static BlePacket from(ScanResult result) {
        ScanRecord record = result.getScanRecord();
        if (record == null) return new BlePacket("no-record", "Kein ScanRecord; RSSI " + result.getRssi(), false, null);

        StringBuilder stable = new StringBuilder();
        StringBuilder out = new StringBuilder();
        out.append("RSSI ").append(result.getRssi());
        out.append(" | Connectable ").append(result.isConnectable());
        out.append(" | Tx ").append(result.getTxPower());

        byte[] raw = record.getBytes();
        if (raw != null) {
            String hex = hex(raw);
            stable.append("RAW:").append(hex);
            out.append("\nRAW: ").append(hex);
        }

        SparseArray<byte[]> manufacturer = record.getManufacturerSpecificData();
        for (int i = 0; i < manufacturer.size(); i++) {
            int id = manufacturer.keyAt(i);
            String value = hex(manufacturer.valueAt(i));
            stable.append("|M").append(id).append(':').append(value);
            out.append("\nHersteller ").append(String.format(Locale.US, "0x%04X", id)).append(": ").append(value);
        }

        boolean activityPacket = false;
        byte[] activityData = null;
        Map<ParcelUuid, byte[]> serviceData = record.getServiceData();
        if (serviceData != null) {
            for (Map.Entry<ParcelUuid, byte[]> entry : serviceData.entrySet()) {
                String value = hex(entry.getValue());
                stable.append("|S").append(entry.getKey()).append(':').append(value);
                out.append("\nServiceData ").append(entry.getKey()).append(": ").append(value);
                String uuid = entry.getKey().toString().toLowerCase(Locale.US);
                byte[] data = entry.getValue();
                // Xiaomi S400: idle advertisements use a short FE95 packet beginning with 0x10.
                // Measurement/activity packets are longer and begin with 0x48.
                if (uuid.startsWith("0000fe95") && data != null
                        && (data.length > 11 || (data.length > 0 && (data[0] & 0xFF) == 0x48))) {
                    activityPacket = true;
                    activityData = data.clone();
                }
            }
        }

        List<ParcelUuid> uuids = record.getServiceUuids();
        if (uuids != null && !uuids.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (ParcelUuid uuid : uuids) names.add(uuid.toString());
            stable.append("|U").append(String.join(",", names));
            out.append("\nUUIDs: ").append(String.join(", ", names));
        }

        return new BlePacket(shortHash(stable.toString()), out.toString(), activityPacket, activityData);
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 6; i++) b.append(String.format(Locale.US, "%02X", digest[i]));
            return b.toString();
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode()).toUpperCase(Locale.US);
        }
    }

    private static String hex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "-";
        StringBuilder b = new StringBuilder(bytes.length * 3);
        for (byte value : bytes) b.append(String.format(Locale.US, "%02X ", value));
        return b.toString().trim();
    }

}
