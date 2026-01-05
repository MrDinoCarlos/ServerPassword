package es.mrdino.serverpassword;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class Lang {
    private final JavaPlugin plugin;
    private final Map<String, YamlConfiguration> bundles = new HashMap<>();

    public Lang(JavaPlugin plugin) {
        this.plugin = plugin;
        load("en");
        load("es");
    }

    private void load(String code) {
        try (var in = plugin.getResource("lang/" + code + ".yml")) {
            if (in == null) {
                plugin.getLogger().warning("Missing lang file: lang/" + code + ".yml");
                return;
            }
            var reader = new InputStreamReader(in, StandardCharsets.UTF_8);
            bundles.put(code, YamlConfiguration.loadConfiguration(reader));
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to load lang/" + code + ".yml: " + e.getMessage());
        }
    }

    /** Idioma base (por ahora global). Luego si quieres por jugador, lo ampliamos. */
    private String defaultLang() {
        return plugin.getConfig().getString("default-language", "en");
    }

    public String prefix(CommandSender sender) {
        return tr(sender, "prefix");
    }

    public String tr(CommandSender sender, String key) {
        return tr(sender, key, Map.of());
    }

    public String tr(CommandSender sender, String key, Map<String, String> vars) {
        String langCode = defaultLang();
        if (sender instanceof Player) {
            // En el futuro aquí puedes meter idioma por jugador
            langCode = defaultLang();
        }
        return tr(langCode, key, vars);
    }

    public String tr(String langCode, String key) {
        return tr(langCode, key, Map.of());
    }

    public String tr(String langCode, String key, Map<String, String> vars) {
        YamlConfiguration yml = bundles.getOrDefault(langCode, bundles.get("en"));
        if (yml == null) return color("&cMissing lang bundle");

        String raw = yml.getString(key, key);
        if (raw == null) raw = key;

        String msg = raw;
        for (var e : vars.entrySet()) {
            msg = msg.replace("{" + e.getKey() + "}", e.getValue());
        }
        return color(msg);
    }

    /** Mensaje con prefijo automático */
    public String msg(CommandSender sender, String key) {
        return msg(sender, key, Map.of());
    }

    public String msg(CommandSender sender, String key, Map<String, String> vars) {
        return prefix(sender) + tr(sender, key, vars);
    }

    public static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
