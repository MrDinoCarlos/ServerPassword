package es.mrdino.serverpassword;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class LoginSettings {
    static final int MAX_PIN_LENGTH = 9;
    static boolean legacy(FileConfiguration config) {
        String mode = config.getString("input-mode", "MODERN");
        return "LEGACY".equalsIgnoreCase(mode) || "CODE_GUI".equalsIgnoreCase(mode);
    }

    static boolean numeric(FileConfiguration config, boolean passwordNumeric) {
        // Numeric credentials always use the keypad, including when TEXT was selected.
        return passwordNumeric || "NUMERIC".equalsIgnoreCase(config.getString("modern-gui.input", "AUTO"));
    }

    static boolean pin(FileConfiguration config) {
        return "PIN".equalsIgnoreCase(config.getString("password-policy.mode", "PIN"));
    }

    static String passwordError(FileConfiguration config, String password) {
        int limit = legacy(config) ? config.getInt("code-gui.max-length", 9) : config.getInt("modern-gui.max-length", 32);
        if (password == null || password.isEmpty() || password.length() > limit || password.chars().anyMatch(Character::isISOControl))
            return "Password must contain 1-" + limit + " printable characters.";
        if (LoginInput.isNumeric(password) && password.length() > MAX_PIN_LENGTH)
            return "Numeric passwords may contain at most 9 digits, including in TEXT mode.";
        if (pin(config) && (!LoginInput.isNumeric(password) || password.length() != config.getInt("password-policy.pin-length", 4)))
            return "PIN mode requires exactly " + config.getInt("password-policy.pin-length", 4) + " digits (0-9). Change password-policy.mode to TEXT for text passwords.";
        if (!legacy(config) && numeric(config, LoginInput.isNumeric(password)) && !LoginInput.isNumeric(password))
            return "NUMERIC input accepts only 0-9. Select TEXT or AUTO first.";
        String alphabet = config.getString("code-gui.charset", PasswordCredential.LEGACY_ALPHABET);
        if (legacy(config) && !password.chars().allMatch(c -> alphabet.indexOf(c) >= 0))
            return "Password contains characters not available in the LEGACY GUI.";
        return null;
    }

    static List<String> validate(FileConfiguration config) {
        List<String> errors = new ArrayList<>();
        for (String section : List.of("password", "password-policy", "modern-gui", "modern-gui.virtual-keyboard", "modern-gui.feedback",
                "modern-gui.countdown", "timeout", "lock", "ban", "language", "updates", "code-gui")) {
            if (config.contains(section) && !config.isConfigurationSection(section))
                errors.add(section + " must be a YAML section.");
        }
        PasswordCredential password;
        try { password = PasswordCredential.read(config); }
        catch (IllegalArgumentException invalid) { errors.add(invalid.getMessage()); return errors; }
        oneOf(config, "input-mode", "MODERN", errors, "MODERN", "LEGACY", "CODE_GUI");
        oneOf(config, "default-language", "en", errors, "en", "es", "de");
        oneOf(config, "success-teleport", "LAST_LOCATION", errors, "SPAWN", "LAST_LOCATION");
        oneOf(config, "modern-gui.presentation", "AUTO", errors, "AUTO", "DIALOG", "INVENTORY");
        oneOf(config, "modern-gui.input", "AUTO", errors, "AUTO", "NUMERIC", "TEXT");
        oneOf(config, "password-policy.mode", "PIN", errors, "PIN", "TEXT");
        oneOf(config, "modern-gui.on-close", "REOPEN", errors, "REOPEN", "KICK");
        oneOf(config, "code-gui.on-close", "REOPEN", errors, "REOPEN", "KICK");
        oneOf(config, "timeout.on-expire", "KICK", errors, "KICK", "REOPEN");
        oneOf(config, "on-fail-action", "KICK", errors, "KICK", "REOPEN");
        int limit = config.getInt("modern-gui.max-length", 32);
        if (limit < 1 || limit > 128) errors.add("modern-gui.max-length must be between 1 and 128.");
        int pinLength = config.getInt("password-policy.pin-length", 4);
        if (pinLength < 1 || pinLength > MAX_PIN_LENGTH) errors.add("password-policy.pin-length must be between 1 and 9.");
        if (password.numeric() && password.length() > MAX_PIN_LENGTH)
            errors.add("Numeric passwords may contain at most 9 digits. Set a new password; the saved hash cannot be truncated.");
        if (pin(config)) {
            int available = legacy(config) ? config.getInt("code-gui.max-length", 9) : limit;
            if (pinLength > available) errors.add("password-policy.pin-length exceeds the selected interface's maximum length.");
        }
        if (legacy(config)) {
            int legacyLimit = config.getInt("code-gui.max-length", 9);
            if (legacyLimit < 1 || legacyLimit > 9 || password.length() > legacyLimit)
                errors.add("LEGACY passwords must fit code-gui.max-length (1-9).");
            String charset = config.getString("code-gui.charset", "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ");
            String requiredAlphabet = password.configured() ? password.legacyAlphabet() : pin(config) ? PasswordCredential.DIGITS : "";
            if ((password.configured() && requiredAlphabet.isEmpty()) || !requiredAlphabet.chars().allMatch(c -> charset.indexOf(c) >= 0))
                errors.add("code-gui.charset must cover password.legacy-alphabet; this credential may require MODERN input.");
        } else {
            if (password.length() > limit) errors.add("password exceeds modern-gui.max-length.");
            if (numeric(config, password.numeric()) && !password.numeric())
                errors.add("NUMERIC input requires a password containing only 0-9. Use AUTO or TEXT.");
        }
        if (config.getInt("timeout.seconds", 30) < 1) errors.add("timeout.seconds must be at least 1.");
        if (config.getInt("max-attempts-per-join", 3) < 1) errors.add("max-attempts-per-join must be at least 1.");
        if (config.getInt("attempt-cooldown-seconds", 2) < 0) errors.add("attempt-cooldown-seconds cannot be negative.");
        int feedback = config.getInt("modern-gui.feedback.duration-ticks", 10);
        if (feedback < 1 || feedback > 40) errors.add("modern-gui.feedback.duration-ticks must be between 1 and 40.");
        int warning = config.getInt("modern-gui.countdown.warning-seconds", 10);
        int critical = config.getInt("modern-gui.countdown.critical-seconds", 5);
        if (warning < 1 || critical < 1 || critical > warning)
            errors.add("Countdown thresholds must satisfy 1 <= critical-seconds <= warning-seconds.");
        for (String setting : List.of("updates.notify-admins", "language.auto-detect", "timeout.enabled",
                "modern-gui.allow-reveal", "modern-gui.hide-password-by-default", "modern-gui.virtual-keyboard.enabled", "modern-gui.inventory-custom-heads",
                "modern-gui.countdown.bossbar", "modern-gui.countdown.sounds", "modern-gui.feedback.enabled",
                "modern-gui.feedback.sounds", "modern-gui.feedback.shake",
                "lock.freeze-movement", "lock.apply-blindness", "ban.enabled")) {
            if (config.contains(setting) && !config.isBoolean(setting)) errors.add(setting + " must be true or false.");
        }
        for (String setting : List.of("password.length", "password-policy.pin-length", "modern-gui.max-length", "timeout.seconds",
                "timeout.start-display-at", "remember-session-minutes", "max-attempts-per-join", "attempt-cooldown-seconds",
                "ban.after-total-fails", "ban.duration-seconds", "modern-gui.feedback.duration-ticks", "code-gui.max-length",
                "code-gui.reopen-delay-ticks", "modern-gui.countdown.warning-seconds", "modern-gui.countdown.critical-seconds")) {
            Object value = config.get(setting);
            if (value != null && !(value instanceof Integer) && !(value instanceof Long))
                errors.add(setting + " must be a whole number.");
        }
        if (config.getInt("remember-session-minutes", 120) < 0) errors.add("remember-session-minutes cannot be negative.");
        if (config.getInt("ban.after-total-fails", 5) < 1) errors.add("ban.after-total-fails must be at least 1.");
        if (config.getLong("ban.duration-seconds", 0) < 0) errors.add("ban.duration-seconds cannot be negative.");
        if (config.getString("modern-gui.virtual-keyboard.charset", "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz -_!?@#$%&*+=./:").isEmpty())
            errors.add("modern-gui.virtual-keyboard.charset cannot be empty.");
        return errors;
    }

    private static void oneOf(FileConfiguration config, String path, String fallback, List<String> errors, String... values) {
        String value = config.getString(path, fallback);
        for (String accepted : values) if (accepted.equalsIgnoreCase(value)) return;
        errors.add(path + " must be one of " + String.join(", ", values) + ".");
    }
}
