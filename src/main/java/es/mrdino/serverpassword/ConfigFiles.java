package es.mrdino.serverpassword;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Adds new defaults without replacing existing values, custom keys or user comments. */
final class ConfigFiles {
    static YamlConfiguration bundled(JavaPlugin plugin, String resource) throws IOException {
        try (var stream = plugin.getResource(resource)) {
            if (stream == null) throw new IOException("Missing bundled resource: " + resource);
            return YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
    }

    static boolean mergeMissing(ConfigurationSection target, ConfigurationSection defaults) {
        boolean changed = false;
        for (String key : defaults.getKeys(false)) {
            if (!target.contains(key)) {
                if (defaults.isConfigurationSection(key)) target.createSection(key);
                else target.set(key, defaults.get(key));
                target.setComments(key, defaults.getComments(key));
                target.setInlineComments(key, defaults.getInlineComments(key));
                changed = true;
            }
            if (target.isConfigurationSection(key) && defaults.isConfigurationSection(key))
                changed |= mergeMissing(target.getConfigurationSection(key), defaults.getConfigurationSection(key));
            if (target.getComments(key).isEmpty() && !defaults.getComments(key).isEmpty()) {
                target.setComments(key, defaults.getComments(key));
                changed = true;
            }
        }
        return changed;
    }

    static void save(Path destination, FileConfiguration configuration) throws IOException {
        Files.createDirectories(destination.toAbsolutePath().getParent());
        Path temporary = destination.resolveSibling(destination.getFileName() + ".tmp");
        Files.writeString(temporary, configuration.saveToString(), StandardCharsets.UTF_8);
        try { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
