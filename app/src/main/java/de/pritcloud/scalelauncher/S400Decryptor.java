package de.pritcloud.scalelauncher;

import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.modes.CCMBlockCipher;
import org.bouncycastle.crypto.modes.CCMModeCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * AES-CCM decoder for Xiaomi Body Composition Scale S400 advertisements.
 *
 * Based on the GPLv3 S400 implementation in openScale. The scale sends two
 * complementary packets per weighing:
 * A: weight + heart rate + high-frequency impedance
 * B: zero weight + low-frequency impedance
 */
public final class S400Decryptor {
    private S400Decryptor() {}

    public static final class Measurement {
        public final float weightKg;
        public final Float impedanceHigh;
        public final Float impedanceLow;
        public final Integer heartRate;

        Measurement(float weightKg, Float impedanceHigh, Float impedanceLow, Integer heartRate) {
            this.weightKg = weightKg;
            this.impedanceHigh = impedanceHigh;
            this.impedanceLow = impedanceLow;
            this.heartRate = heartRate;
        }

        public boolean isPacketA() {
            return weightKg > 0f && impedanceHigh != null;
        }

        public boolean isPacketB() {
            return weightKg == 0f && impedanceLow != null;
        }
    }

    public static Measurement decrypt(byte[] advertisementData, String macAddress, String bindKey) {
        if (!isValidBindKey(bindKey) || !isValidMacAddress(macAddress) || advertisementData == null) return null;
        final byte[] data;
        if (advertisementData.length == 26) data = slice(advertisementData, 2, 26);
        else if (advertisementData.length == 24) data = advertisementData;
        else return null;

        try {
            byte[] mac = hexToBytes(macAddress.replace(":", ""));
            byte[] key = hexToBytes(bindKey);
            byte[] reversedMac = new byte[6];
            for (int i = 0; i < 6; i++) reversedMac[i] = mac[5 - i];

            byte[] nonce = concat(reversedMac, slice(data, 2, 5), slice(data, data.length - 7, data.length - 4));
            byte[] encrypted = slice(data, 5, data.length - 7);
            byte[] mic = slice(data, data.length - 4, data.length);
            byte[] cipherText = concat(encrypted, mic);

            CCMModeCipher ccm = CCMBlockCipher.newInstance(AESEngine.newInstance());
            ccm.init(false, new AEADParameters(new KeyParameter(key), 32, nonce, new byte[]{0x11}));
            byte[] decrypted = new byte[ccm.getOutputSize(cipherText.length)];
            int length = ccm.processBytes(cipherText, 0, cipherText.length, decrypted, 0);
            length += ccm.doFinal(decrypted, length);
            return parse(slice(decrypted, 0, length));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Measurement parse(byte[] decrypted) {
        if (decrypted.length < 12) return null;
        byte[] object = slice(decrypted, 3, 12);
        int value = ByteBuffer.wrap(slice(object, 1, 5)).order(ByteOrder.LITTLE_ENDIAN).getInt();

        int weightRaw = value & 0x7ff;
        int heartRaw = (value >> 11) & 0x7f;
        int impedanceRaw = value >> 18;

        float weight = weightRaw / 10.0f;
        Integer heart = heartRaw >= 1 && heartRaw <= 126 ? heartRaw + 50 : null;
        Float impedanceHigh = weightRaw != 0 && impedanceRaw != 0 ? impedanceRaw / 10.0f : null;
        Float impedanceLow = weightRaw == 0 && impedanceRaw != 0 ? impedanceRaw / 10.0f : null;

        // Packet A carries weight/high impedance; packet B carries low impedance.
        if (weightRaw == 0 && impedanceLow == null) return null;
        return new Measurement(weight, impedanceHigh, impedanceLow, heart);
    }

    public static boolean isValidBindKey(String key) {
        return key != null && key.matches("(?i)^[0-9a-f]{32}$");
    }

    public static boolean isValidMacAddress(String mac) {
        return mac != null && mac.matches("(?i)^([0-9a-f]{2}:){5}[0-9a-f]{2}$");
    }

    private static byte[] hexToBytes(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }

    private static byte[] slice(byte[] source, int from, int to) {
        byte[] result = new byte[to - from];
        System.arraycopy(source, from, result, 0, result.length);
        return result;
    }

    private static byte[] concat(byte[]... arrays) {
        int size = 0;
        for (byte[] array : arrays) size += array.length;
        byte[] result = new byte[size];
        int offset = 0;
        for (byte[] array : arrays) {
            System.arraycopy(array, 0, result, offset, array.length);
            offset += array.length;
        }
        return result;
    }
}
