package es.mrdino.serverpassword;

import org.bukkit.configuration.file.FileConfiguration;

/** Metadata describes the input policy, never the plaintext or the characters actually used. */
record PasswordCredential(String hash, int length, boolean numeric, String legacyAlphabet) {
    static final String DIGITS = "0123456789";
    static final String LEGACY_ALPHABET = DIGITS + "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    boolean configured() { return !hash.isEmpty(); }

    static PasswordCredential create(String plaintext, String legacyAlphabet) {
        if (plaintext == null || plaintext.isEmpty() || plaintext.length() > 128
                || plaintext.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Password must contain 1-128 printable characters.");
        boolean numeric = LoginInput.isNumeric(plaintext);
        String policy = numeric ? DIGITS : plaintext.chars().allMatch(c -> legacyAlphabet.indexOf(c) >= 0) ? legacyAlphabet : "";
        return new PasswordCredential(PasswordHasher.hash(plaintext), plaintext.length(), numeric, policy);
    }

    static PasswordCredential read(FileConfiguration config) {
        if (!config.isString("password.hash")) throw new IllegalArgumentException("password.hash must be a string; use an empty string before setup.");
        String hash = config.getString("password.hash", "");
        int length = config.getInt("password.length", 0);
        if (hash.isEmpty()) {
            if (length != 0) throw new IllegalArgumentException("An unconfigured password must have password.length: 0.");
            return new PasswordCredential("", 0, true, DIGITS);
        }
        PasswordHasher.validate(hash);
        if (length < 1 || length > 128) throw new IllegalArgumentException("password.length must be between 1 and 128.");
        if (!config.isBoolean("password.numeric")) throw new IllegalArgumentException("password.numeric must be true or false.");
        return new PasswordCredential(hash, length, config.getBoolean("password.numeric"),
                config.getString("password.legacy-alphabet", ""));
    }

    static boolean migrate(FileConfiguration config) {
        boolean changed = false;
        if (config.isString("password")) {
            String plaintext = config.getString("password");
            PasswordCredential credential = plaintext.isEmpty() ? new PasswordCredential("", 0, true, DIGITS)
                    : create(plaintext, config.getString("code-gui.charset", LEGACY_ALPHABET));
            credential.write(config);
            changed = true;
        }
        // Existing passwords retain their input policy when the new defaults are merged.
        if (!config.contains("password-policy") && !config.getString("password.hash", "").isEmpty()) {
            PasswordCredential credential = read(config);
            config.set("password-policy.mode", credential.numeric() ? "PIN" : "TEXT");
            config.set("password-policy.pin-length", credential.numeric() ? credential.length() : 4);
            changed = true;
        }
        return changed;
    }

    void write(FileConfiguration config) {
        if (!config.isConfigurationSection("password")) config.set("password", null);
        config.set("password.hash", hash);
        config.set("password.length", length);
        config.set("password.numeric", numeric);
        config.set("password.legacy-alphabet", legacyAlphabet);
    }
}
