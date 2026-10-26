package es.mrdino.serverpassword;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class PasswordSecurityTest {
    static YamlConfiguration defaults() {
        var config = freshDefaults();
        config.set("password.hash", "pbkdf2-sha256$600000$2068Rn7UEvc0u0NZtb94RA==$TSw4lSsSpWZ2B4ggh6kFwaHvMZVn+tAjcC4i23qfFyQ=");
        config.set("password.length", 4);
        return config;
    }

    static YamlConfiguration freshDefaults() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                PasswordSecurityTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
    }

    @Test void freshInstallHasNoSharedPasswordAndRequiresSetup() {
        var config = freshDefaults();
        assertFalse(PasswordCredential.read(config).configured());
        assertEquals("", config.getString("password.hash"));
        assertEquals(0, config.getInt("password.length"));
        assertEquals(4, config.getInt("password-policy.pin-length"));
        assertTrue(LoginSettings.pin(config));
        assertFalse(config.isString("password"));
        assertTrue(LoginSettings.validate(config).isEmpty());
    }

    @Test void defaultPolicyRequiresExactlyFourAsciiDigitsAndPreservesLeadingZeros() {
        var config = freshDefaults();
        assertNull(LoginSettings.passwordError(config, "0411"));
        assertNotNull(LoginSettings.passwordError(config, "411"));
        assertNotNull(LoginSettings.passwordError(config, "04111"));
        assertNotNull(LoginSettings.passwordError(config, "ABCD"));
        assertNotNull(LoginSettings.passwordError(config, "０４１１"));
        config.set("password-policy.pin-length", 6);
        assertNull(LoginSettings.passwordError(config, "000123"));
        config.set("password-policy.mode", "TEXT");
        assertNull(LoginSettings.passwordError(config, "A text password!"));
    }

    @Test void upgradingInfersPolicyWithoutReplacingExistingCredentials() {
        var config = defaults();
        config.set("password-policy", null);
        config.set("password.length", 6);
        String hash = config.getString("password.hash");
        assertTrue(PasswordCredential.migrate(config));
        assertEquals(hash, config.getString("password.hash"));
        assertEquals("PIN", config.getString("password-policy.mode"));
        assertEquals(6, config.getInt("password-policy.pin-length"));
        config.set("password-policy", null);
        config.set("password.numeric", false);
        assertTrue(PasswordCredential.migrate(config));
        assertEquals("TEXT", config.getString("password-policy.mode"));
    }

    @Test void emptyOldPasswordMigratesToSetupWithoutAUsableCredential() {
        var config = freshDefaults();
        config.set("password", "");
        assertTrue(PasswordCredential.migrate(config));
        assertFalse(PasswordCredential.read(config).configured());
        assertTrue(LoginSettings.validate(config).isEmpty());
    }

    @Test void everyNewHashHasItsOwnSalt() {
        String first = PasswordHasher.hash("same password");
        String second = PasswordHasher.hash("same password");
        assertNotEquals(first, second);
        assertTrue(PasswordHasher.verify("same password", first));
        assertTrue(PasswordHasher.verify("same password", second));
    }

    @Test void malformedAndExcessivelyExpensiveHashesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> PasswordHasher.validate("invalid"));
        String valid = PasswordCredential.read(defaults()).hash();
        assertThrows(IllegalArgumentException.class, () -> PasswordHasher.validate(valid.replace("600000", "999999999")));
        assertThrows(IllegalArgumentException.class, () -> PasswordHasher.validate(valid.replace("600000", "1000")));
    }

    @Test void migrationReplacesPlaintextAndPreservesTheWorkingPassword() {
        var config = defaults();
        String password = "Migration-secret_42";
        config.set("password", password);
        assertTrue(PasswordCredential.migrate(config));
        assertFalse(config.saveToString().contains(password));
        assertTrue(PasswordHasher.verify(password, PasswordCredential.read(config).hash()));
        assertEquals(password.length(), config.getInt("password.length"));
        assertFalse(config.getBoolean("password.numeric"));
        assertFalse(PasswordCredential.migrate(config));
    }

    @Test void numericModeRejectsTextCredentials() {
        var config = defaults();
        config.set("password.numeric", false);
        config.set("modern-gui.input", "NUMERIC");
        assertTrue(LoginSettings.validate(config).stream().anyMatch(error -> error.contains("NUMERIC")));
    }

    @Test void textModeStillUsesTheKeypadForNumericPasswordsAndEnforcesNineDigits() {
        var config = defaults();
        config.set("password-policy.mode", "TEXT");
        config.set("modern-gui.input", "TEXT");
        assertTrue(LoginSettings.numeric(config, true));
        assertFalse(LoginSettings.numeric(config, false));
        assertNull(LoginSettings.passwordError(config, "000123456"));
        assertNotNull(LoginSettings.passwordError(config, "0001234567"));
        assertNull(LoginSettings.passwordError(config, "Text1234567890"));
        config.set("password.length", 10);
        assertTrue(LoginSettings.validate(config).stream().anyMatch(error -> error.contains("at most 9")));
        config.set("password.length", 4);
        config.set("password-policy.pin-length", 10);
        assertFalse(LoginSettings.validate(config).isEmpty());
    }

    @Test void countdownThresholdsAndEffectTogglesAreValidated() {
        var config = defaults();
        config.set("modern-gui.countdown.warning-seconds", 4);
        config.set("modern-gui.countdown.critical-seconds", 5);
        config.set("modern-gui.countdown.sounds", "yes");
        config.set("modern-gui.feedback.shake", "yes");
        assertTrue(LoginSettings.validate(config).size() >= 3);
    }

    @Test void legacyRejectsUnreachableCredentials() {
        var config = defaults();
        config.set("input-mode", "LEGACY");
        config.set("code-gui.charset", "ABC");
        assertFalse(LoginSettings.validate(config).isEmpty());
        config.set("code-gui.charset", PasswordCredential.LEGACY_ALPHABET);
        config.set("password.length", 10);
        assertFalse(LoginSettings.validate(config).isEmpty());
        config.set("password.length", 4);
        assertTrue(LoginSettings.validate(config).isEmpty());
    }

    @Test void invalidLimitsAndEnumValuesAreRejected() {
        var config = defaults();
        config.set("modern-gui.presentation", "UNKNOWN");
        config.set("modern-gui.max-length", 2);
        config.set("timeout.seconds", 0);
        assertTrue(LoginSettings.validate(config).size() >= 3);
    }

    @Test void incorrectlyTypedValuesAreNotSilentlyReplacedByFallbacks() {
        var config = defaults();
        config.set("timeout.seconds", "thirty");
        config.set("updates.notify-admins", "false");
        assertTrue(LoginSettings.validate(config).stream().anyMatch(error -> error.contains("timeout.seconds must be a whole number")));
        assertTrue(LoginSettings.validate(config).stream().anyMatch(error -> error.contains("updates.notify-admins must be true or false")));
    }
}
