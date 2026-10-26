package es.mrdino.serverpassword;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ConfigFilesTest {
    @TempDir Path temporary;

    @Test void newDefaultsPreserveValuesCustomKeysAndComments() throws Exception {
        YamlConfiguration existing = yaml("""
                # My own welcome message
                greeting: "Welcome to my server"
                # My own settings
                settings:
                  attempts: 8
                custom-key: "keep me"
                """);
        YamlConfiguration defaults = yaml("""
                greeting: "Default greeting"
                settings:
                  attempts: 3
                  # New option description
                  keyboard: true
                new-message: "New translation"
                """);
        var comments = existing.getComments("greeting");
        assertTrue(ConfigFiles.mergeMissing(existing, defaults));
        assertEquals("Welcome to my server", existing.getString("greeting"));
        assertEquals(8, existing.getInt("settings.attempts"));
        assertEquals("keep me", existing.getString("custom-key"));
        assertTrue(existing.getBoolean("settings.keyboard"));
        assertEquals("New translation", existing.getString("new-message"));
        assertEquals(comments, existing.getComments("greeting"));
        assertFalse(existing.getComments("settings.keyboard").isEmpty());
        Path destination = temporary.resolve("lang/es.yml");
        ConfigFiles.save(destination, existing);
        YamlConfiguration saved = new YamlConfiguration();
        saved.options().parseComments(true);
        saved.load(destination.toFile());
        assertEquals("Welcome to my server", saved.getString("greeting"));
        assertEquals(comments, saved.getComments("greeting"));
        assertFalse(ConfigFiles.mergeMissing(saved, defaults), "Updating twice must be idempotent");
    }

    @Test void mergingDoesNotSilentlyReplaceAnInvalidUserSection() throws Exception {
        YamlConfiguration existing = yaml("modern-gui: false\n");
        ConfigFiles.mergeMissing(existing, PasswordSecurityTest.defaults());
        assertFalse(existing.getBoolean("modern-gui"));
        assertTrue(LoginSettings.validate(existing).stream().anyMatch(error -> error.contains("modern-gui must be a YAML section")));
    }

    @Test void oldPasswordIsMigratedBeforeDefaultsAreAdded() throws Exception {
        YamlConfiguration existing = yaml("password: 'MyOldSecret42'\ninput-mode: MODERN\n");
        assertTrue(PasswordCredential.migrate(existing));
        ConfigFiles.mergeMissing(existing, PasswordSecurityTest.defaults());
        assertTrue(PasswordHasher.verify("MyOldSecret42", PasswordCredential.read(existing).hash()));
        assertFalse(existing.saveToString().contains("MyOldSecret42"));
        assertTrue(LoginSettings.validate(existing).isEmpty());
    }

    private static YamlConfiguration yaml(String source) throws Exception {
        var yaml = new YamlConfiguration();
        yaml.options().parseComments(true);
        yaml.loadFromString(source);
        return yaml;
    }
}
