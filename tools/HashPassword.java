import java.io.Console;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Run locally with Java 17+: java tools/HashPassword.java /path/to/password-import.yml */
class HashPassword {
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || System.console() == null) {
            System.err.println("Run in a local interactive terminal: java tools/HashPassword.java /path/to/password-import.yml");
            System.err.println("Passwords are never accepted as arguments or echoed to the terminal.");
            System.exit(1);
        }
        Console console = System.console();
        char[] password = console.readPassword("New password (hidden): ");
        char[] confirmation = console.readPassword("Repeat password (hidden): ");
        PBEKeySpec specification = null;
        byte[] derived = null;
        try {
            if (password == null || confirmation == null || !Arrays.equals(password, confirmation))
                throw new IllegalArgumentException("Passwords do not match.");
            if (password.length < 1 || password.length > 128)
                throw new IllegalArgumentException("Password must contain 1-128 characters.");
            boolean numeric = true, legacy = true;
            for (char character : password) {
                if (Character.isISOControl(character)) throw new IllegalArgumentException("Control characters are not supported.");
                numeric &= character >= '0' && character <= '9';
                legacy &= "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ".indexOf(character) >= 0;
            }
            if (numeric && password.length > 9) throw new IllegalArgumentException("PINs may contain at most nine digits.");
            byte[] salt = new byte[16];
            new SecureRandom().nextBytes(salt);
            specification = new PBEKeySpec(password, salt, 600_000, 256);
            derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(specification).getEncoded();
            String hash = "pbkdf2-sha256$600000$" + Base64.getEncoder().encodeToString(salt) + "$"
                    + Base64.getEncoder().encodeToString(derived);
            String yaml = "password:\n  hash: '" + hash + "'\n  length: " + password.length
                    + "\n  numeric: " + numeric + "\n  legacy-alphabet: '"
                    + (numeric ? "0123456789" : legacy ? "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ" : "") + "'\n";
            Path output = Path.of(args[0]);
            // Refuse overwrites; restrict permissions where POSIX permissions are supported.
            try { Files.createFile(output, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))); }
            catch (UnsupportedOperationException windows) { Files.createFile(output); }
            Files.writeString(output, yaml, StandardCharsets.UTF_8);
            console.printf("Hashed credential saved. Put this file in plugins/ServerPassword/password-import.yml and run spass import in the server console.%n");
            console.printf("For text passwords, ensure the login keyboard charset contains every character you chose.%n");
        } finally {
            if (password != null) Arrays.fill(password, '\0');
            if (confirmation != null) Arrays.fill(confirmation, '\0');
            if (specification != null) specification.clearPassword();
            if (derived != null) Arrays.fill(derived, (byte) 0);
        }
    }
}
