package es.mrdino.serverpassword;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

final class LoginUI implements PasswordUI {
    private final JavaPlugin plugin;
    private final LockdownManager lock;
    private final AuthManager auth;
    private final CodePasswordUI legacy;
    private final ModernPasswordUI modern;
    private final Map<UUID, PasswordUI> active = new HashMap<>();

    LoginUI(JavaPlugin plugin, AuthManager auth, LockdownManager lock, Lang lang) {
        this(plugin, auth, lock, lang, ModernPasswordUI.loadDialogs(plugin));
    }

    LoginUI(JavaPlugin plugin, AuthManager auth, LockdownManager lock, Lang lang, NativeLoginScreen dialogs) {
        this.plugin = plugin;
        this.lock = lock;
        this.auth = auth;
        legacy = new CodePasswordUI(plugin, auth, lock, lang, this::usesClassic);
        modern = new ModernPasswordUI(plugin, auth, lock, lang, dialogs, System::currentTimeMillis);
        modern.warnCompatibility();
        plugin.getServer().getPluginManager().registerEvents(legacy, plugin);
        plugin.getServer().getPluginManager().registerEvents(modern, plugin);
    }

    @Override public void open(Player player) {
        if (!player.isOnline() || !lock.isLocked(player) || auth.isAuthed(player)) return;
        activate(player, LoginSettings.legacy(plugin.getConfig()) ? legacy : modern);
    }

    private boolean usesClassic(Player player) { return active.get(player.getUniqueId()) == legacy; }

    private void activate(Player player, PasswordUI target) {
        PasswordUI previous = active.get(player.getUniqueId());
        if (previous != target) {
            auth.cancelPending(player);
            if (previous != null) previous.forceClose(player);
            if (previous == legacy) player.closeInventory();
            active.put(player.getUniqueId(), target);
        }
        target.open(player);
    }

    void reload() {
        legacy.reloadTextures();
        for (Player player : lock.getLockedPlayersOnline()) {
            auth.cancelPending(player);
            forceClose(player);
            if (auth.isAuthed(player)) lock.unlock(player);
            else open(player);
        }
    }

    @Override public boolean handleChat(Player player, String message) { return false; }
    @Override public void forceClose(Player player) {
        active.remove(player.getUniqueId());
        legacy.forceClose(player); modern.forceClose(player);
    }
    @Override public void cleanupSession(Player player) {
        active.remove(player.getUniqueId());
        legacy.cleanupSession(player); modern.cleanupSession(player);
    }
    @Override public void shutdown() { active.clear(); legacy.shutdown(); modern.shutdown(); }
}
