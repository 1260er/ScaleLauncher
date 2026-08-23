package de.pritcloud.scalelauncher;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/** Public Nearby endpoint metadata. Contains no secret data. */
final class PeerEndpointInfo {
    static final int VERSION = 1;

    final String deviceId;
    final String label;

    private PeerEndpointInfo(String deviceId,
                             String label) {
        this.deviceId = deviceId;
        this.label = label;
    }

    static PeerEndpointInfo local(
            android.content.Context context) {
        return new PeerEndpointInfo(
                PeerTrustStore.localDeviceId(context),
                PeerTrustStore.localDeviceLabel(context));
    }

    byte[] encode() {
        try {
            JSONObject object = new JSONObject();
            object.put("version", VERSION);
            object.put("deviceId", deviceId);
            object.put("label", label);

            return object.toString()
                    .getBytes(StandardCharsets.UTF_8);
        } catch (JSONException exception) {
            throw new IllegalStateException(
                    "Could not encode peer endpoint",
                    exception);
        }
    }

    static PeerEndpointInfo decode(byte[] data) {
        if (data == null
                || data.length == 0
                || data.length > 4096) {
            return null;
        }

        try {
            JSONObject object =
                    new JSONObject(
                            new String(
                                    data,
                                    StandardCharsets.UTF_8));

            if (object.optInt(
                    "version",
                    -1) != VERSION) {
                return null;
            }

            String deviceId =
                    object.optString(
                            "deviceId",
                            "");

            String label =
                    object.optString(
                            "label",
                            "").trim();

            if (!PeerTrustStore
                    .isValidDeviceId(deviceId)) {
                return null;
            }

            if (label.length() > 100) {
                label =
                        label.substring(
                                0,
                                100);
            }

            return new PeerEndpointInfo(
                    deviceId,
                    label);
        } catch (JSONException exception) {
            return null;
        }
    }
}
