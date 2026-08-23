package de.pritcloud.scalelauncher;

import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Authenticated encryption for raw peer measurement payloads.
 *
 * The stored pairing secret is never written to logs or transferred.
 */
final class PeerMeasurementCrypto {
    private static final int VERSION = 1;

    private static final String CONTEXT =
            "ScaleLauncher-peer-measurement-v1";

    private static final byte[] HKDF_SALT =
            "ScaleLauncher-peer-measurement-salt-v1"
                    .getBytes(StandardCharsets.UTF_8);

    private static final SecureRandom RANDOM =
            new SecureRandom();

    private PeerMeasurementCrypto() {}

    static String encrypt(
            String senderDeviceId,
            byte[] sharedSecret,
            PeerMeasurementPayload payload) {

        if (!PeerTrustStore.isValidDeviceId(senderDeviceId)) {
            throw new IllegalArgumentException(
                    "Invalid sender device ID");
        }

        if (sharedSecret == null
                || sharedSecret.length != 32) {
            throw new IllegalArgumentException(
                    "Invalid peer secret");
        }

        if (payload == null
                || !payload.isValid()) {
            throw new IllegalArgumentException(
                    "Invalid measurement payload");
        }

        byte[] nonce = new byte[12];
        RANDOM.nextBytes(nonce);

        byte[] key = deriveKey(sharedSecret);

        try {
            Cipher cipher =
                    Cipher.getInstance(
                            "AES/GCM/NoPadding");

            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(
                            key,
                            "AES"),
                    new GCMParameterSpec(
                            128,
                            nonce));

            cipher.updateAAD(
                    aad(senderDeviceId));

            byte[] ciphertext =
                    cipher.doFinal(
                            payload.encode()
                                    .getBytes(
                                            StandardCharsets.UTF_8));

            JSONObject envelope =
                    new JSONObject();

            envelope.put(
                    "version",
                    VERSION);

            envelope.put(
                    "senderDeviceId",
                    senderDeviceId);

            envelope.put(
                    "nonce",
                    Base64.encodeToString(
                            nonce,
                            Base64.NO_WRAP));

            envelope.put(
                    "ciphertext",
                    Base64.encodeToString(
                            ciphertext,
                            Base64.NO_WRAP));

            return envelope.toString();
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Could not encrypt peer measurement",
                    exception);
        } finally {
            Arrays.fill(
                    key,
                    (byte) 0);
        }
    }

    static String senderDeviceId(
            String encoded) {

        if (encoded == null
                || encoded.isBlank()) {
            return null;
        }

        try {
            JSONObject envelope =
                    new JSONObject(encoded);

            if (envelope.optInt(
                    "version",
                    -1) != VERSION) {
                return null;
            }

            String sender =
                    envelope.optString(
                            "senderDeviceId",
                            "");

            return PeerTrustStore
                    .isValidDeviceId(sender)
                    ? sender
                    : null;
        } catch (Exception exception) {
            return null;
        }
    }

    static PeerMeasurementPayload decrypt(
            String encoded,
            PeerTrustStore.Peer peer) {

        if (encoded == null
                || encoded.isBlank()
                || peer == null
                || peer.sharedSecret == null
                || peer.sharedSecret.length != 32) {
            return null;
        }

        byte[] key = null;

        try {
            JSONObject envelope =
                    new JSONObject(encoded);

            if (envelope.optInt(
                    "version",
                    -1) != VERSION) {
                return null;
            }

            String sender =
                    envelope.optString(
                            "senderDeviceId",
                            "");

            if (!peer.deviceId.equals(sender)) {
                return null;
            }

            byte[] nonce =
                    Base64.decode(
                            envelope.optString(
                                    "nonce",
                                    ""),
                            Base64.NO_WRAP);

            byte[] ciphertext =
                    Base64.decode(
                            envelope.optString(
                                    "ciphertext",
                                    ""),
                            Base64.NO_WRAP);

            if (nonce.length != 12
                    || ciphertext.length < 16) {
                return null;
            }

            key = deriveKey(
                    peer.sharedSecret);

            Cipher cipher =
                    Cipher.getInstance(
                            "AES/GCM/NoPadding");

            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(
                            key,
                            "AES"),
                    new GCMParameterSpec(
                            128,
                            nonce));

            cipher.updateAAD(
                    aad(sender));

            byte[] plaintext =
                    cipher.doFinal(
                            ciphertext);

            return PeerMeasurementPayload.decode(
                    new String(
                            plaintext,
                            StandardCharsets.UTF_8));
        } catch (Exception exception) {
            return null;
        } finally {
            if (key != null) {
                Arrays.fill(
                        key,
                        (byte) 0);
            }
        }
    }

    private static byte[] deriveKey(
            byte[] sharedSecret) {

        byte[] prk = null;

        try {
            Mac extract =
                    Mac.getInstance(
                            "HmacSHA256");

            extract.init(
                    new SecretKeySpec(
                            HKDF_SALT,
                            "HmacSHA256"));

            prk =
                    extract.doFinal(
                            sharedSecret);

            Mac expand =
                    Mac.getInstance(
                            "HmacSHA256");

            expand.init(
                    new SecretKeySpec(
                            prk,
                            "HmacSHA256"));

            expand.update(
                    CONTEXT.getBytes(
                            StandardCharsets.UTF_8));

            expand.update(
                    (byte) 1);

            return expand.doFinal();
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Could not derive peer measurement key",
                    exception);
        } finally {
            if (prk != null) {
                Arrays.fill(
                        prk,
                        (byte) 0);
            }
        }
    }

    private static byte[] aad(
            String senderDeviceId) {

        return (CONTEXT
                + "|"
                + VERSION
                + "|"
                + senderDeviceId)
                .getBytes(
                        StandardCharsets.UTF_8);
    }
}
