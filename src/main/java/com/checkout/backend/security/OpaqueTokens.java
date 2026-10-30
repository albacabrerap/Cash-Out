package com.checkout.backend.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Tokens al azar que el servidor entrega una vez y despues solo reconoce por su
 * hash: los de refresco y los de verificacion de correo. Si alguien lee la
 * base, los hashes no le sirven para entrar.
 */
public final class OpaqueTokens {

    private OpaqueTokens() {
    }

    public static String generate(SecureRandom random, int bytes) {
        byte[] value = new byte[bytes];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    /** SHA-256 en hexadecimal. Sin sal a proposito: el token ya es aleatorio y hay que poder buscarlo por su hash. */
    public static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 no esta disponible en esta JVM", ex);
        }
    }

}
