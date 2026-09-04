package de.pritcloud.scalelauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Local identity and trusted ScaleLauncher peers.
 *
 * Peer secrets are encrypted with an Android Keystore key before they are
 * persisted. Secrets must never be written to EventLog.
 */
final class PeerTrustStore {
    private static final String PREFS = "peer_trust_v1";
    private static final String KEY_LOCAL_ID = "local_device_id";
    private static final String KEY_PEERS = "trusted_peers";
    private static final String KEYSTORE_ALIAS =
            "ScaleLauncherPeerTrustKeyV1";

    private static final SecureRandom RANDOM = new SecureRandom();

    static final class Peer {
        final String deviceId;
        final String label;
        final byte[] sharedSecret;

        Peer(String deviceId,
             String label,
             byte[] sharedSecret) {
            this.deviceId = deviceId;
            this.label = label;
            this.sharedSecret =
                    sharedSecret == null
                            ? new byte[0]
                            : sharedSecret.clone();
        }
    }

    private PeerTrustStore() {}

    static String localDeviceId(Context context) {
        SharedPreferences prefs =
                context.getSharedPreferences(
                        PREFS,
                        Context.MODE_PRIVATE);

        String stored =
                prefs.getString(KEY_LOCAL_ID, "");

        if (isValidDeviceId(stored)) {
            return stored;
        }

        String created = UUID.randomUUID().toString();

        prefs.edit()
                .putString(KEY_LOCAL_ID, created)
                .commit();

        return created;
    }

    static String localDeviceLabel(Context context) {
        String manufacturer =
                Build.MANUFACTURER == null
                        ? ""
                        : Build.MANUFACTURER.trim();

        String model =
                Build.MODEL == null
                        ? ""
                        : Build.MODEL.trim();

        String base =
                (manufacturer + " " + model).trim();

        if (base.isBlank()) {
            base = "Android";
        }

        String id = localDeviceId(context);
        String suffix =
                id.substring(0, Math.min(4, id.length()))
                        .toUpperCase(Locale.ROOT);

        return base + " " + suffix;
    }

    static List<Peer> load(Context context) {
        List<Peer> result = new ArrayList<>();

        String encoded =
                context.getSharedPreferences(
                                PREFS,
                                Context.MODE_PRIVATE)
                        .getString(KEY_PEERS, "");

        if (encoded == null || encoded.isBlank()) {
            return result;
        }

        try {
            JSONArray array = new JSONArray(encoded);

            for (int index = 0;
                 index < array.length();
                 index++) {

                JSONObject object =
                        array.optJSONObject(index);

                if (object == null) continue;

                String deviceId =
                        object.optString(
                                "deviceId",
                                "");

                String label =
                        object.optString(
                                "label",
                                "");

                String encryptedSecret =
                        object.optString(
                                "secret",
                                "");

                if (!isValidDeviceId(deviceId)
                        || encryptedSecret.isBlank()) {
                    continue;
                }

                byte[] secret;

                try {
                    secret =
                            decryptSecret(
                                    encryptedSecret);
                } catch (RuntimeException exception) {
                    continue;
                }

                if (secret.length != 32) continue;

                result.add(
                        new Peer(
                                deviceId,
                                label,
                                secret));
            }
        } catch (JSONException ignored) {
        }

        return result;
    }

    static Peer find(Context context,
                     String deviceId) {
        if (!isValidDeviceId(deviceId)) {
            return null;
        }

        for (Peer peer : load(context)) {
            if (peer.deviceId.equals(deviceId)) {
                return peer;
            }
        }

        return null;
    }

    static boolean isTrusted(Context context,
                             String deviceId) {
        return find(context, deviceId) != null;
    }

    static int count(Context context) {
        return load(context).size();
    }

    static void trust(Context context,
                      String deviceId,
                      String label,
                      byte[] sharedSecret) {
        if (!isValidDeviceId(deviceId)) {
            throw new IllegalArgumentException(
                    "Invalid peer device ID");
        }

        if (sharedSecret == null
                || sharedSecret.length != 32) {
            throw new IllegalArgumentException(
                    "Peer secret must contain 32 bytes");
        }

        JSONArray array = new JSONArray();

        for (Peer peer : load(context)) {
            if (peer.deviceId.equals(deviceId)) {
                continue;
            }

            array.put(toJson(peer));
        }

        array.put(
                trustedPeerJson(
                        deviceId,
                        label,
                        sharedSecret));

        context.getSharedPreferences(
                        PREFS,
                        Context.MODE_PRIVATE)
                .edit()
                .putString(
                        KEY_PEERS,
                        array.toString())
                .commit();
    }

    static void remove(Context context,
                       String deviceId) {
        JSONArray array = new JSONArray();

        for (Peer peer : load(context)) {
            if (!peer.deviceId.equals(deviceId)) {
                array.put(toJson(peer));
            }
        }

        context.getSharedPreferences(
                        PREFS,
                        Context.MODE_PRIVATE)
                .edit()
                .putString(
                        KEY_PEERS,
                        array.toString())
                .commit();

        PeerOutboxRoomStore.removePeer(
                context,
                deviceId);

        PeerInboxDedupRoomStore.removePeer(
                context,
                deviceId);

        HouseholdProfileRoomStore.removeOwner(
                context,
                deviceId);

        RemotePendingMeasurementRoomStore.removeCollector(
                context,
                deviceId);
    }

    static byte[] newSharedSecret() {
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        return secret;
    }

    static boolean isValidDeviceId(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }

        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static JSONObject toJson(Peer peer) {
        return trustedPeerJson(
                peer.deviceId,
                peer.label,
                peer.sharedSecret);
    }

    private static JSONObject trustedPeerJson(
            String deviceId,
            String label,
            byte[] secret) {
        JSONObject object = new JSONObject();

        try {
            object.put(
                    "deviceId",
                    deviceId);
            object.put(
                    "label",
                    label == null ? "" : label);
            object.put(
                    "secret",
                    encryptSecret(secret));

            return object;
        } catch (JSONException exception) {
            throw new IllegalStateException(
                    "Could not encode peer trust",
                    exception);
        }
    }

    private static String encryptSecret(
            byte[] secret) {
        try {
            Cipher cipher =
                    Cipher.getInstance(
                            "AES/GCM/NoPadding");

            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    getOrCreateKey());

            byte[] ciphertext =
                    cipher.doFinal(secret);

            return Base64.encodeToString(
                            cipher.getIV(),
                            Base64.NO_WRAP)
                    + "."
                    + Base64.encodeToString(
                            ciphertext,
                            Base64.NO_WRAP);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Could not encrypt peer secret",
                    exception);
        }
    }

    private static byte[] decryptSecret(
            String encoded) {
        try {
            String[] parts =
                    encoded.split(
                            "\\.",
                            2);

            if (parts.length != 2) {
                throw new IllegalArgumentException(
                        "Invalid encrypted peer secret");
            }

            byte[] iv =
                    Base64.decode(
                            parts[0],
                            Base64.NO_WRAP);

            byte[] ciphertext =
                    Base64.decode(
                            parts[1],
                            Base64.NO_WRAP);

            Cipher cipher =
                    Cipher.getInstance(
                            "AES/GCM/NoPadding");

            cipher.init(
                    Cipher.DECRYPT_MODE,
                    getOrCreateKey(),
                    new GCMParameterSpec(
                            128,
                            iv));

            return cipher.doFinal(ciphertext);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Could not decrypt peer secret",
                    exception);
        }
    }

    private static SecretKey getOrCreateKey()
            throws Exception {
        KeyStore keyStore =
                KeyStore.getInstance(
                        "AndroidKeyStore");

        keyStore.load(null);

        java.security.Key existing =
                keyStore.getKey(
                        KEYSTORE_ALIAS,
                        null);

        if (existing instanceof SecretKey) {
            return (SecretKey) existing;
        }

        KeyGenerator generator =
                KeyGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_AES,
                        "AndroidKeyStore");

        generator.init(
                new KeyGenParameterSpec.Builder(
                        KEYSTORE_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT
                                | KeyProperties.PURPOSE_DECRYPT)
                        .setKeySize(256)
                        .setBlockModes(
                                KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(
                                KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build());

        return generator.generateKey();
    }
}
