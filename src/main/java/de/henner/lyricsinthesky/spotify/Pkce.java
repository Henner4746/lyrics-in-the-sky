package de.henner.lyricsinthesky.spotify;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/** Proof Key for Code Exchange (RFC 7636): lets an app log in with OAuth without a client secret. */
final class Pkce {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();

    private Pkce() {}

    /** Random URL-safe string, used both as code verifier and as the anti-forgery state. */
    static String randomString(int bytes) {
        byte[] data = new byte[bytes];
        RANDOM.nextBytes(data);
        return BASE64_URL.encodeToString(data);
    }

    static String challenge(String verifier) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return BASE64_URL.encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 missing from the Java runtime", e);
        }
    }
}
