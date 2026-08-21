package de.pritcloud.scalelauncher;

import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.macs.HMac;
import org.bouncycastle.crypto.modes.CCMBlockCipher;
import org.bouncycastle.crypto.modes.CCMModeCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.bouncycastle.crypto.params.KeyParameter;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Mi Home v2 session crypto for the S400.
 *
 * Independently implemented from the protocol description used by
 * nokistin/xiaomi-s400-live, Apache License 2.0.
 */
public final class S400GattCrypto {
    private static final byte[] LOGIN_INFO =
            "mible-login-info".getBytes(StandardCharsets.US_ASCII);

    private S400GattCrypto() {}

    public static final class SessionKeys {
        public final byte[] deviceKey;
        public final byte[] appKey;
        public final byte[] deviceIv;
        public final byte[] appIv;

        SessionKeys(byte[] deviceKey,
                    byte[] appKey,
                    byte[] deviceIv,
                    byte[] appIv) {
            this.deviceKey = deviceKey;
            this.appKey = appKey;
            this.deviceIv = deviceIv;
            this.appIv = appIv;
        }
    }

    public static SessionKeys deriveLoginKeys(byte[] token,
                                               byte[] appRandom,
                                               byte[] deviceRandom) {
        if (token == null || token.length != 12) {
            throw new IllegalArgumentException("Login token must contain 12 bytes");
        }
        if (appRandom == null || appRandom.length != 16
                || deviceRandom == null || deviceRandom.length != 16) {
            throw new IllegalArgumentException("Login random values must contain 16 bytes");
        }

        byte[] salt = concat(appRandom, deviceRandom);
        byte[] derived = new byte[64];

        HKDFBytesGenerator hkdf = new HKDFBytesGenerator(new SHA256Digest());
        hkdf.init(new HKDFParameters(token, salt, LOGIN_INFO));
        hkdf.generateBytes(derived, 0, derived.length);

        return new SessionKeys(
                Arrays.copyOfRange(derived, 0, 16),
                Arrays.copyOfRange(derived, 16, 32),
                Arrays.copyOfRange(derived, 32, 36),
                Arrays.copyOfRange(derived, 36, 40));
    }

    public static byte[] hmacSha256(byte[] key, byte[] data) {
        HMac hmac = new HMac(new SHA256Digest());
        hmac.init(new KeyParameter(key));
        hmac.update(data, 0, data.length);

        byte[] output = new byte[hmac.getMacSize()];
        hmac.doFinal(output, 0);
        return output;
    }

    public static byte[] decryptCmtp(SessionKeys keys, byte[] raw) {
        if (keys == null || raw == null || raw.length < 6) return null;

        try {
            byte[] iteration = Arrays.copyOfRange(raw, 0, 2);
            byte[] ciphertext = Arrays.copyOfRange(raw, 2, raw.length);

            byte[] nonce = concat(
                    keys.deviceIv,
                    new byte[4],
                    iteration,
                    new byte[2]);

            CCMModeCipher ccm =
                    CCMBlockCipher.newInstance(AESEngine.newInstance());
            ccm.init(false, new AEADParameters(
                    new KeyParameter(keys.deviceKey),
                    32,
                    nonce,
                    null));

            byte[] plaintext = new byte[ccm.getOutputSize(ciphertext.length)];
            int length = ccm.processBytes(
                    ciphertext, 0, ciphertext.length, plaintext, 0);
            length += ccm.doFinal(plaintext, length);

            return Arrays.copyOf(plaintext, length);
        } catch (Exception ignored) {
            return null;
        }
    }

    public static byte[] concat(byte[]... arrays) {
        int length = 0;
        for (byte[] array : arrays) length += array.length;

        byte[] output = new byte[length];
        int offset = 0;
        for (byte[] array : arrays) {
            System.arraycopy(array, 0, output, offset, array.length);
            offset += array.length;
        }
        return output;
    }
}
