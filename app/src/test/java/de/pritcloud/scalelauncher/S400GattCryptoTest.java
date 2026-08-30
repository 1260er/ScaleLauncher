package de.pritcloud.scalelauncher;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

public final class S400GattCryptoTest {
    @Test
    public void derivesKnownLoginSessionKeys() {
        byte[] token =
                hex("000102030405060708090a0b");

        byte[] appRandom =
                hex("101112131415161718191a1b1c1d1e1f");

        byte[] deviceRandom =
                hex("202122232425262728292a2b2c2d2e2f");

        S400GattCrypto.SessionKeys keys =
                S400GattCrypto.deriveLoginKeys(
                        token,
                        appRandom,
                        deviceRandom);

        assertArrayEquals(
                hex("c155874e9070ceea1442268a104c32a2"),
                keys.deviceKey);

        assertArrayEquals(
                hex("e3d45937fa34418380b6747d1cac84e6"),
                keys.appKey);

        assertArrayEquals(
                hex("8d9e0842"),
                keys.deviceIv);

        assertArrayEquals(
                hex("c57b6232"),
                keys.appIv);
    }


    @Test
    public void decryptsKnownCmtpVector() {
        S400GattCrypto.SessionKeys keys =
                new S400GattCrypto.SessionKeys(
                        hex("404142434445464748494a4b4c4d4e4f"),
                        new byte[16],
                        hex("10111213"),
                        new byte[4]);

        byte[] raw =
                hex("2021c79aa56f8488ef8c5741fe5f5324a49a154d2010");

        assertArrayEquals(
                hex("00112233445566778899aabbccddeeff"),
                S400GattCrypto.decryptCmtp(
                        keys,
                        raw));
    }

    private static byte[] hex(String value) {
        byte[] result =
                new byte[value.length() / 2];

        for (int index = 0;
             index < result.length;
             index++) {
            result[index] =
                    (byte) Integer.parseInt(
                            value.substring(
                                    index * 2,
                                    index * 2 + 2),
                            16);
        }

        return result;
    }
}
