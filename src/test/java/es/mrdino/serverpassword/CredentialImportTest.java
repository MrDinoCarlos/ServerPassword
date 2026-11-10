package es.mrdino.serverpassword;

import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CredentialImportTest {
    @TempDir Path directory;

    private ServerPassPlugin plugin() throws Exception {
        ServerPassPlugin plugin = mock(ServerPassPlugin.class);
        for (var entry : java.util.Map.<String, Object>of("acceptedConfig", PasswordSecurityTest.defaults(),
                "authManager", mock(AuthManager.class), "codeUI", mock(LoginUI.class)).entrySet()) {
            var field = ServerPassPlugin.class.getDeclaredField(entry.getKey());
            field.setAccessible(true); field.set(plugin, entry.getValue());
        }
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        doCallRealMethod().when(plugin).getConfig();
        doCallRealMethod().when(plugin).importPassword();
        Files.writeString(directory.resolve("config.yml"), plugin.getConfig().saveToString());
        return plugin;
    }

    @Test void importsOnlyHashedCredentialsAndRemovesTheSuccessfulImport() throws Exception {
        ServerPassPlugin plugin = plugin();
        var credential = PasswordCredential.create("New-Secret_42", PasswordCredential.LEGACY_ALPHABET);
        var imported = new YamlConfiguration(); credential.write(imported);
        Files.writeString(directory.resolve("password-import.yml"), imported.saveToString());
        assertTrue(plugin.importPassword().isEmpty());
        assertFalse(Files.exists(directory.resolve("password-import.yml")));
        assertEquals(credential.hash(), plugin.getConfig().getString("password.hash"));
        String saved = Files.readString(directory.resolve("config.yml"));
        assertFalse(saved.contains("New-Secret_42"));
        assertTrue(PasswordHasher.verify("New-Secret_42", PasswordCredential.read(plugin.getConfig()).hash()));
    }

    @Test void plaintextImportCannotReplaceTheCurrentCredentialOrEchoTheSecret() throws Exception {
        ServerPassPlugin plugin = plugin();
        String before = Files.readString(directory.resolve("config.yml"));
        Files.writeString(directory.resolve("password-import.yml"), "password: 'Never-Echo-42'\n");
        var errors = plugin.importPassword();
        assertFalse(errors.isEmpty());
        assertFalse(String.join(" ", errors).contains("Never-Echo-42"));
        assertEquals(before, Files.readString(directory.resolve("config.yml")));
        assertTrue(Files.exists(directory.resolve("password-import.yml")));
    }
}
