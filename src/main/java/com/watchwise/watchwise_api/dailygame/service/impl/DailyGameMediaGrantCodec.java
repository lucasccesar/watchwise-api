package com.watchwise.watchwise_api.dailygame.service.impl;

import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.dailygame.media.DailyGameImageVariant;
import com.watchwise.watchwise_api.dailygame.media.DailyGameMediaSlot;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/** Authenticated, encrypted grants that can be verified by every API instance. */
final class DailyGameMediaGrantCodec {
    private static final String KEY_DERIVATION_CONTEXT = "Watchwise/daily-game-media-grant/key/v1";
    private static final byte[] ASSOCIATED_DATA = "Watchwise/daily-game-media-grant/token/v1"
            .getBytes(StandardCharsets.US_ASCII);
    private static final byte VERSION = 1;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int TAG_BYTES = TAG_BITS / Byte.SIZE;
    private static final int MAX_SOURCE_PATH_BYTES = 2048;
    private static final int MAX_TOKEN_CHARS = 4096;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final SecretKeySpec encryptionKey;
    private final SecureRandom random = new SecureRandom();

    DailyGameMediaGrantCodec(String jwtSecret) {
        byte[] secretBytes = jwtSecret == null ? new byte[0] : jwtSecret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalArgumentException("JWT secret must contain at least 32 UTF-8 bytes");
        }
        this.encryptionKey = deriveEncryptionKey(secretBytes);
    }

    String encode(UUID userId, UUID challengeId, DailyGameMediaSlot slot, DailyGameImageVariant variant,
                  String sourcePath, Instant expiresAt) {
        String normalizedPath = TmdbClient.normalizeImagePath(sourcePath);
        byte[] pathBytes = normalizedPath.getBytes(StandardCharsets.US_ASCII);
        if (userId == null || challengeId == null || slot == null || variant == null || expiresAt == null
                || pathBytes.length == 0 || pathBytes.length > MAX_SOURCE_PATH_BYTES
                || !normalizedPath.equals(new String(pathBytes, StandardCharsets.US_ASCII))) {
            throw new BadRequestException("Invalid daily game media grant");
        }
        Grant grant = new Grant(userId, challengeId, slot, variant, normalizedPath,
                fingerprint(normalizedPath), expiresAt);
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(ASSOCIATED_DATA);
            byte[] ciphertext = cipher.doFinal(serialize(grant));
            byte[] token = new byte[1 + NONCE_BYTES + ciphertext.length];
            token[0] = VERSION;
            System.arraycopy(nonce, 0, token, 1, NONCE_BYTES);
            System.arraycopy(ciphertext, 0, token, 1 + NONCE_BYTES, ciphertext.length);
            return ENCODER.encodeToString(token);
        } catch (GeneralSecurityException | IOException failure) {
            throw new IllegalStateException("Could not create daily game media grant", failure);
        }
    }

    Grant decode(String token) {
        if (token == null || token.isEmpty() || token.length() > MAX_TOKEN_CHARS || !isBase64Url(token)) {
            return null;
        }
        try {
            byte[] encoded = DECODER.decode(token);
            if (!ENCODER.encodeToString(encoded).equals(token)
                    || encoded.length < 1 + NONCE_BYTES + TAG_BYTES + 32 || encoded[0] != VERSION) {
                return null;
            }
            byte[] nonce = Arrays.copyOfRange(encoded, 1, 1 + NONCE_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(encoded, 1 + NONCE_BYTES, encoded.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(ASSOCIATED_DATA);
            return deserialize(cipher.doFinal(ciphertext));
        } catch (GeneralSecurityException | IOException | RuntimeException invalidGrant) {
            // Malformed, unauthenticated, and expired grants all have the same public 404 behavior.
            return null;
        }
    }

    private static SecretKeySpec deriveEncryptionKey(byte[] secretBytes) {
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(secretBytes, "HmacSHA256"));
            byte[] derived = hmac.doFinal(KEY_DERIVATION_CONTEXT.getBytes(StandardCharsets.US_ASCII));
            return new SecretKeySpec(derived, "AES");
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("Required media grant key derivation is unavailable", impossible);
        }
    }

    private static byte[] serialize(Grant grant) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeLong(grant.userId().getMostSignificantBits());
            output.writeLong(grant.userId().getLeastSignificantBits());
            output.writeLong(grant.challengeId().getMostSignificantBits());
            output.writeLong(grant.challengeId().getLeastSignificantBits());
            output.writeUTF(grant.slot().name());
            output.writeUTF(grant.variant().name());
            output.writeLong(grant.expiresAt().toEpochMilli());
            byte[] path = grant.sourcePath().getBytes(StandardCharsets.US_ASCII);
            output.writeShort(path.length);
            output.write(path);
            output.write(HexFormat.of().parseHex(grant.sourceFingerprint()));
        }
        return bytes.toByteArray();
    }

    private static Grant deserialize(byte[] plaintext) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(plaintext))) {
            UUID userId = new UUID(input.readLong(), input.readLong());
            UUID challengeId = new UUID(input.readLong(), input.readLong());
            DailyGameMediaSlot slot = DailyGameMediaSlot.valueOf(input.readUTF());
            DailyGameImageVariant variant = DailyGameImageVariant.valueOf(input.readUTF());
            long expiresAtMillis = input.readLong();
            int pathLength = input.readUnsignedShort();
            if (pathLength == 0 || pathLength > MAX_SOURCE_PATH_BYTES || input.available() != pathLength + 32) {
                throw new IOException("Invalid daily game media grant payload");
            }
            byte[] pathBytes = input.readNBytes(pathLength);
            byte[] sourceFingerprint = input.readNBytes(32);
            if (pathBytes.length != pathLength || sourceFingerprint.length != 32 || input.available() != 0) {
                throw new IOException("Invalid daily game media grant payload");
            }
            for (byte value : pathBytes) {
                if (value < 0) {
                    throw new IOException("Invalid daily game media grant path");
                }
            }
            String sourcePath = new String(pathBytes, StandardCharsets.US_ASCII);
            if (!sourcePath.equals(TmdbClient.normalizeImagePath(sourcePath))
                    || !MessageDigest.isEqual(sourceFingerprint, sha256(pathBytes)) || expiresAtMillis <= 0) {
                throw new IOException("Invalid daily game media grant payload");
            }
            return new Grant(userId, challengeId, slot, variant, sourcePath,
                    HexFormat.of().formatHex(sourceFingerprint), Instant.ofEpochMilli(expiresAtMillis));
        }
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("Required SHA-256 implementation is unavailable", impossible);
        }
    }

    static String fingerprint(String path) {
        return HexFormat.of().formatHex(sha256(path.getBytes(StandardCharsets.US_ASCII)));
    }

    private static boolean isBase64Url(String token) {
        for (int index = 0; index < token.length(); index++) {
            char value = token.charAt(index);
            if (!(value >= 'A' && value <= 'Z') && !(value >= 'a' && value <= 'z')
                    && !(value >= '0' && value <= '9') && value != '_' && value != '-') {
                return false;
            }
        }
        return true;
    }

    record Grant(UUID userId, UUID challengeId, DailyGameMediaSlot slot, DailyGameImageVariant variant,
                 String sourcePath, String sourceFingerprint, Instant expiresAt) {}
}
