package es.mrdino.serverpassword;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;

public class Lang {
    private final JavaPlugin plugin;
    private final Map<String, YamlConfiguration> bundles = new HashMap<>();
    private final NamespacedKey preferenceKey;

    public Lang(JavaPlugin plugin) {
        this.plugin = plugin;
        this.preferenceKey = new NamespacedKey(plugin, "language");
        reload();
    }

    public void reload() {
        load("en");
        load("es");
        load("de");
    }

    private void load(String code) {
        YamlConfiguration defaults = null;
        try {
            defaults = ConfigFiles.bundled(plugin, "lang/" + code + ".yml");
            var destination = new java.io.File(plugin.getDataFolder(), "lang/" + code + ".yml");
            YamlConfiguration language = new YamlConfiguration();
            language.options().parseComments(true);
            boolean missing = !destination.isFile();
            if (!missing) language.load(destination);
            if (ConfigFiles.mergeMissing(language, defaults) || missing) ConfigFiles.save(destination.toPath(), language);
            bundles.put(code, language);
        } catch (Exception e) {
            plugin.getLogger().warning("Could not update lang/" + code + ".yml; keeping previous or bundled translations: " + e.getClass().getSimpleName());
            if (!bundles.containsKey(code) && defaults != null) bundles.put(code, defaults);
        }
    }

    /** Fallback language when no supported player preference is available. */
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
        String langCode = sender instanceof Player player ? language(player) : defaultLang();
        return tr(langCode, key, vars);
    }

    public String tr(String langCode, String key) {
        return tr(langCode, key, Map.of());
    }

    public String tr(String langCode, String key, Map<String, String> vars) {
        YamlConfiguration yml = bundles.getOrDefault(langCode, bundles.get("en"));
        if (yml == null) return color("&cMissing lang bundle");

        String raw = yml.getString(key, bundles.get("en").getString(key, key));
        if (raw == null) raw = key;

        String msg = color(raw);
        for (var e : vars.entrySet()) {
            msg = msg.replace("{" + e.getKey() + "}", e.getValue());
        }
        return msg;
    }

    private String language(Player player) {
        String preferred = player.getPersistentDataContainer().get(preferenceKey, PersistentDataType.STRING);
        if (preferred != null && bundles.containsKey(preferred)) return preferred;
        if (plugin.getConfig().getBoolean("language.auto-detect", true)) {
            String locale = player.getLocale().toLowerCase(java.util.Locale.ROOT).split("[_-]", 2)[0];
            if (bundles.containsKey(locale)) return locale;
        }
        return defaultLang();
    }

    void cycleLanguage(Player player) {
        var choices = java.util.List.of("en", "es", "de");
        String next = choices.get((choices.indexOf(language(player)) + 1) % choices.size());
        player.getPersistentDataContainer().set(preferenceKey, PersistentDataType.STRING, next);
    }

    /** Translated message with the configured prefix. */
    public String msg(CommandSender sender, String key) {
        return msg(sender, key, Map.of());
    }

    public String msg(CommandSender sender, String key, Map<String, String> vars) {
        return prefix(sender) + tr(sender, key, vars);
    }

    public static String color(String s) {
        if (s == null || s.isEmpty()) return "";

        char[] chars = s.toCharArray();
        for (int i = 0; i < chars.length - 1; i++) {
            if (chars[i] != '&') continue;

            char code = Character.toLowerCase(chars[i + 1]);
            if ("0123456789abcdefklmnorx".indexOf(code) < 0) continue;

            chars[i] = '\u00a7';
            chars[i + 1] = code;
        }
        return new String(chars);
    }
}
