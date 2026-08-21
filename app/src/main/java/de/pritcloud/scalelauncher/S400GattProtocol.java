package de.pritcloud.scalelauncher;

import java.util.UUID;

/**
 * Xiaomi S400 Mi Home v2 GATT protocol constants.
 *
 * Protocol reference:
 * nokistin/xiaomi-s400-live, Apache License 2.0.
 */
public final class S400GattProtocol {
    private S400GattProtocol() {}

    public static final UUID UPNP =
            UUID.fromString("00000010-0000-1000-8000-00805f9b34fb");
    public static final UUID AVDTP =
            UUID.fromString("00000019-0000-1000-8000-00805f9b34fb");
    public static final UUID AVCTP =
            UUID.fromString("00000017-0000-1000-8000-00805f9b34fb");
    public static final UUID VEND1A =
            UUID.fromString("0000001a-0000-1000-8000-00805f9b34fb");
    public static final UUID CMTP =
            UUID.fromString("0000001b-0000-1000-8000-00805f9b34fb");
    public static final UUID VEND1C =
            UUID.fromString("0000001c-0000-1000-8000-00805f9b34fb");

    public static final UUID CLIENT_CHARACTERISTIC_CONFIG =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    public static final byte[] CMD_LOGIN =
            hex("24000000");
    public static final byte[] CMD_SEND_KEY =
            hex("0000000b0100");
    public static final byte[] CMD_SEND_INFO =
            hex("0000000a0200");

    public static final byte[] RCV_RDY =
            hex("00000101");
    public static final byte[] RCV_OK =
            hex("00000100");
    public static final byte[] CFM_LOGIN_OK =
            hex("21000000");

    public static boolean isValidLoginToken(String token) {
        return token != null && token.matches("(?i)^[0-9a-f]{24}$");
    }

    public static byte[] hex(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(
                    value.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }
}
