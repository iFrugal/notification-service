package com.lazydevs.notification.api.push;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * The one definition of how a push device token is hashed (DD-37).
 *
 * <p>A device token is personal data and is never logged. Logs, delivery
 * events and results carry {@link #hash(String)} instead:
 * {@code sha256:} followed by the first 16 lowercase hex characters (64 bits)
 * of the SHA-256 of the UTF-8 bytes of the stripped token. The value is
 * stable across JVMs and versions, so an application can hash its own copy
 * of a token to find the matching log line or event.
 *
 * <p>Within one tenant scope a 64-bit hash collision is the only way two
 * tokens share a hash: about 3 in 100 million per lookup at a million devices.
 *
 * @since 1.4.0
 */
public final class PushTokens {

    /** Prefix of every token hash, so a reader knows how it was made. */
    public static final String HASH_PREFIX = "sha256:";

    /** Hex characters of the SHA-256 digest kept in a token hash. */
    public static final int HASH_HEX_LENGTH = 16;

    private PushTokens() {
    }

    /**
     * The loggable form of a token: {@code sha256:<first 16 hex of SHA-256(token.strip())>}.
     *
     * @param token the device token (or Firebase installation id), not {@code null}
     * @return the hash, 23 characters long
     */
    public static String hash(String token) {
        return HASH_PREFIX + sha256Hex(token).substring(0, HASH_HEX_LENGTH);
    }

    /**
     * The full lowercase hex SHA-256 of the UTF-8 bytes of the stripped token,
     * for example as a lookup key that identifies the token without storing it twice.
     *
     * @param token the device token, not {@code null}
     * @return 64 lowercase hex characters
     */
    public static String sha256Hex(String token) {
        Objects.requireNonNull(token, "token");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.strip().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // Every JDK must provide SHA-256.
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
