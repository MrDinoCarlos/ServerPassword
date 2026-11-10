package es.mrdino.serverpassword;

import com.google.gson.JsonParser;
import com.google.gson.JsonArray;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Checks at startup and every 24 hours; join notices reuse the cached result. */
final class UpdateChecker implements Listener {
    static final long CHECK_INTERVAL_TICKS = 24L * 60 * 60 * 20;
    private final JavaPlugin plugin;
    private final AuthManager auth;
    private final LockdownManager lock;
    private final Lang lang;
    private final Set<UUID> pending = new HashSet<>();
    private final String installed;
    private final String minecraft;
    private final Set<String> loaders;
    private final BukkitTask checkTask;
    private final BukkitTask notifyTask;
    private final VersionSource source;
    private ModrinthVersions.Release available;
    private boolean checked;

    UpdateChecker(JavaPlugin plugin, AuthManager auth, LockdownManager lock, Lang lang) {
        this(plugin, auth, lock, lang, UpdateChecker::fetch);
    }

    UpdateChecker(JavaPlugin plugin, AuthManager auth, LockdownManager lock, Lang lang, VersionSource source) {
        this.plugin = plugin;
        this.auth = auth;
        this.lock = lock;
        this.lang = lang;
        this.source = source;
        installed = plugin.getDescription().getVersion();
        minecraft = plugin.getServer().getBukkitVersion().split("-", 2)[0];
        String name = plugin.getServer().getName().toLowerCase(java.util.Locale.ROOT);
        loaders = name.contains("paper") || name.contains("purpur") || name.contains("folia")
                ? Set.of("paper", "spigot", "bukkit") : Set.of("spigot", "bukkit");
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        checkTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, this::check, 40L, CHECK_INTERVAL_TICKS);
        notifyTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::notifyPending, 60L, 20L);
    }

    private void check() {
        try {
            var result = ModrinthVersions.newerCompatible(source.read(installed), installed, minecraft, loaders).orElse(null);
            if (result == null) plugin.getLogger().info("Modrinth: no newer compatible version found (installed " + installed + ", Minecraft " + minecraft + ").");
            else plugin.getLogger().info("New ServerPassword version on Modrinth: " + result.version() + " (installed " + installed + "). " + result.url());
            if (plugin.isEnabled()) plugin.getServer().getScheduler().runTask(plugin, () -> {
                available = result;
                checked = true;
                notifyPending();
            });
        } catch (Exception failure) {
            plugin.getLogger().warning("Could not check Modrinth for updates: " + failure.getClass().getSimpleName());
        }
    }

    private static JsonArray fetch(String installed) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create("https://api.modrinth.com/v2/project/server-password/version?include_changelog=false").toURL().openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestProperty("User-Agent", "MrDinoCarlos/ServerPassword/" + installed + " (https://mrdino.es/server-password/)");
            if (connection.getResponseCode() != 200) throw new java.io.IOException("HTTP " + connection.getResponseCode());
            byte[] data;
            try (var stream = connection.getInputStream()) { data = stream.readNBytes(2_000_001); }
            if (data.length > 2_000_000) throw new java.io.IOException("Version response too large");
            return JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonArray();
        } finally { if (connection != null) connection.disconnect(); }
    }

    @EventHandler(priority = EventPriority.MONITOR) public void onJoin(PlayerJoinEvent event) {
        pending.add(event.getPlayer().getUniqueId());
    }
    @EventHandler public void onQuit(PlayerQuitEvent event) { pending.remove(event.getPlayer().getUniqueId()); }

    private void notifyPending() {
        pending.removeIf(id -> {
            Player player = plugin.getServer().getPlayer(id);
            if (player == null || !player.isOnline()) return true;
            if (!plugin.getConfig().getBoolean("updates.notify-admins", true)) return true;
            if (!auth.isAuthed(player) || lock.isLocked(player) || !checked) return false;
            if (available != null && player.hasPermission("serverpassword.admin")) {
                player.sendMessage(lang.msg(player, "update-available", Map.of(
                        "current", installed, "latest", available.version(), "url", available.url())));
            }
            return true;
        });
    }

    void shutdown() { checkTask.cancel(); notifyTask.cancel(); pending.clear(); }

    @FunctionalInterface interface VersionSource { JsonArray read(String installed) throws Exception; }
}
