package de.pritcloud.scalelauncher;

import org.bouncycastle.math.ec.rfc7748.X25519;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

final class PeerPairingCrypto {
    static final int HELLO_LENGTH = 49;

    private static final int VERSION = 1;
    private static final SecureRandom RANDOM =
            new SecureRandom();

    static final class Session {
        final String deviceId;
        final byte[] privateKey;
        final byte[] publicKey;
        final byte[] hello;

        Session(String deviceId,
                byte[] privateKey,
                byte[] publicKey,
                byte[] hello) {
            this.deviceId = deviceId;
            this.privateKey = privateKey;
            this.publicKey = publicKey;
            this.hello = hello;
        }
    }

    static final class Remote {
        final String deviceId;
        final byte[] publicKey;

        Remote(String deviceId,
               byte[] publicKey) {
            this.deviceId = deviceId;
            this.publicKey = publicKey;
        }
    }

    static final class Result {
        final String securityCode;
        final byte[] sharedSecret;

        Result(String securityCode,
               byte[] sharedSecret) {
            this.securityCode = securityCode;
            this.sharedSecret = sharedSecret;
        }
    }

    private PeerPairingCrypto() {}

    static Session newSession(
            String deviceId) {
        UUID id =
                UUID.fromString(deviceId);

        byte[] privateKey =
                new byte[X25519.SCALAR_SIZE];

        byte[] publicKey =
                new byte[X25519.POINT_SIZE];

        X25519.generatePrivateKey(
                RANDOM,
                privateKey);

        X25519.scalarMultBase(
                privateKey,
                0,
                publicKey,
                0);

        ByteBuffer buffer =
                ByteBuffer.allocate(
                                HELLO_LENGTH)
                        .order(
                                ByteOrder.BIG_ENDIAN);

        buffer.put((byte) VERSION);
        buffer.putLong(
                id.getMostSignificantBits());
        buffer.putLong(
                id.getLeastSignificantBits());
        buffer.put(publicKey);

        return new Session(
                deviceId,
                privateKey,
                publicKey,
                buffer.array());
    }

    static Remote parseHello(
            byte[] hello) {
        if (hello == null
                || hello.length != HELLO_LENGTH) {
            return null;
        }

        ByteBuffer buffer =
                ByteBuffer.wrap(hello)
                        .order(
                                ByteOrder.BIG_ENDIAN);

        int version =
                buffer.get() & 0xff;

        if (version != VERSION) {
            return null;
        }

        UUID id =
                new UUID(
                        buffer.getLong(),
                        buffer.getLong());

        String deviceId =
                id.toString();

        if (!PeerTrustStore
                .isValidDeviceId(deviceId)) {
            return null;
        }

        byte[] publicKey =
                new byte[X25519.POINT_SIZE];

        buffer.get(publicKey);

        return new Remote(
                deviceId,
                publicKey);
    }

    static Result derive(
            Session local,
            Remote remote) {
        if (local == null
                || remote == null
                || local.deviceId.equals(
                        remote.deviceId)) {
            throw new IllegalArgumentException(
                    "Invalid peer handshake");
        }

        byte[] sharedSecret =
                new byte[32];

        boolean valid =
                X25519.calculateAgreement(
                        local.privateKey,
                        0,
                        remote.publicKey,
                        0,
                        sharedSecret,
                        0);

        if (!valid) {
            throw new IllegalStateException(
                    "Invalid X25519 agreement");
        }

        try {
            String first;
            String second;

            if (local.deviceId.compareTo(
                    remote.deviceId) < 0) {
                first = local.deviceId;
                second = remote.deviceId;
            } else {
                first = remote.deviceId;
                second = local.deviceId;
            }

            MessageDigest digest =
                    MessageDigest.getInstance(
                            "SHA-256");

            digest.update(
                    "ScaleLauncher-pair-code-v1"
                            .getBytes(
                                    StandardCharsets.UTF_8));

            digest.update(
                    first.getBytes(
                            StandardCharsets.UTF_8));

            digest.update(
                    second.getBytes(
                            StandardCharsets.UTF_8));

            digest.update(sharedSecret);

            byte[] hash =
                    digest.digest();

            int number =
                    ByteBuffer.wrap(hash)
                            .order(
                                    ByteOrder.BIG_ENDIAN)
                            .getInt()
                            & 0x7fffffff;

            String code =
                    String.format(
                            Locale.ROOT,
                            "%06d",
                            number % 1_000_000);

            return new Result(
                    code,
                    Arrays.copyOf(
                            sharedSecret,
                            sharedSecret.length));
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Could not derive pairing code",
                    exception);
        }
    }
}
