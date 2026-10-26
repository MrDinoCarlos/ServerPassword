package es.mrdino.serverpassword;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

final class PasswordHasher {
    private static final int ITERATIONS = 600_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    static String hash(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return "pbkdf2-sha256$" + ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt)
                + "$" + Base64.getEncoder().encodeToString(derive(password, salt, ITERATIONS));
    }

    static void validate(String encoded) { parse(encoded); }

    static boolean verify(String password, String encoded) {
        Parts parts = parse(encoded);
        byte[] actual = derive(password, parts.salt, parts.iterations);
        try { return MessageDigest.isEqual(parts.hash, actual); }
        finally { Arrays.fill(actual, (byte) 0); }
    }

    private static Parts parse(String encoded) {
        try {
            String[] parts = encoded.split("\\$", -1);
            if (parts.length != 4 || !parts[0].equals("pbkdf2-sha256")) throw new IllegalArgumentException();
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] hash = Base64.getDecoder().decode(parts[3]);
            if (iterations < ITERATIONS || iterations > 2_000_000 || salt.length < 16 || salt.length > 64 || hash.length != 32)
                throw new IllegalArgumentException();
            return new Parts(iterations, salt, hash);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("password.hash must be a valid PBKDF2-SHA256 hash (600000-2000000 iterations).");
        }
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        char[] characters = password.toCharArray();
        PBEKeySpec specification = new PBEKeySpec(characters, salt, iterations, 256);
        Arrays.fill(characters, '\0');
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(specification).getEncoded(); }
        catch (GeneralSecurityException failure) { throw new IllegalStateException("PBKDF2-SHA256 is unavailable.", failure); }
        finally { specification.clearPassword(); }
    }

    private record Parts(int iterations, byte[] salt, byte[] hash) { }
}
