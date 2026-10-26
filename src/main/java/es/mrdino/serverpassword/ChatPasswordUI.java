package es.mrdino.serverpassword;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** Private chat login for clients without native text dialogs. */
final class ChatPasswordUI implements PasswordUI {
    private final JavaPlugin plugin;
    private final AuthManager auth;
    private final LockdownManager lock;
    private final Lang lang;
    private final LongSupplier clock;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final BukkitTask ticker;

    ChatPasswordUI(JavaPlugin plugin, AuthManager auth, LockdownManager lock, Lang lang) {
        this(plugin, auth, lock, lang, System::currentTimeMillis);
    }

    ChatPasswordUI(JavaPlugin plugin, AuthManager auth, LockdownManager lock, Lang lang, LongSupplier clock) {
        this.plugin = plugin;
        this.auth = auth;
        this.lock = lock;
        this.lang = lang;
        this.clock = clock;
        ticker = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    @Override public void open(Player player) {
        if (!player.isOnline() || !lock.isLocked(player) || auth.isAuthed(player)) return;
        if (sessions.containsKey(player.getUniqueId())) return;
        Session session = new Session(player, deadline());
        sessions.put(player.getUniqueId(), session);
        player.closeInventory();
        prompt(session);
    }

    private long deadline() { return clock.getAsLong() + plugin.getConfig().getInt("timeout.seconds", 30) * 1000L; }

    private void prompt(Session session) {
        session.player.sendMessage(lang.msg(session.player, "chat-login-prompt"));
        if (plugin.getConfig().getBoolean("timeout.enabled", true))
            session.player.sendMessage(lang.msg(session.player, "chat-login-timeout", Map.of(
                    "seconds", String.valueOf(Math.max(0, (session.deadline - clock.getAsLong() + 999) / 1000)))));
    }

    @Override public boolean handleChat(Player player, String message) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null || !lock.isLocked(player)) return false;
        // Chat events may be asynchronous. Capture the exact input, then use Bukkit on its server thread.
        plugin.getServer().getScheduler().runTask(plugin, () -> submit(session, message));
        return true;
    }

    private boolean current(Session session) {
        return sessions.get(session.player.getUniqueId()) == session && session.player.isOnline() && lock.isLocked(session.player);
    }

    private void submit(Session session, String password) {
        if (!current(session)) return;
        if (expired(session)) { expire(session); return; }
        Player player = session.player;
        if (password == null || password.isEmpty() || password.length() > plugin.getConfig().getInt("modern-gui.max-length", 32)) {
            player.sendMessage(lang.msg(player, "screen-invalid-input"));
            return;
        }
        auth.tryPasswordAsync(player, password, result -> {
            if (!current(session)) return;
            if (expired(session) && result != AuthManager.AuthResult.SUCCESS) { expire(session); return; }
            switch (result) {
                case SUCCESS -> {
                    player.sendMessage(lang.msg(player, "success"));
                    lock.unlock(player);
                    forceClose(player);
                }
                case WRONG -> player.sendMessage(lang.msg(player, "wrong-password", Map.of(
                        "used", String.valueOf(auth.getAttemptsThisJoin(player)),
                        "max", String.valueOf(plugin.getConfig().getInt("max-attempts-per-join", 3)))));
                case COOLDOWN -> player.sendMessage(lang.msg(player, "cooldown", Map.of(
                        "seconds", String.valueOf(auth.cooldownSecondsLeft(player)))));
                case INVALID_INPUT -> player.sendMessage(lang.msg(player, "screen-invalid-input"));
                case BANNED -> kick(session, "banned");
                case MAX_ATTEMPTS -> {
                    if ("KICK".equalsIgnoreCase(plugin.getConfig().getString("on-fail-action", "KICK"))) kick(session, "kicked");
                    else {
                        auth.resetAttempts(player);
                        session.deadline = deadline();
                        player.sendMessage(lang.msg(player, "chat-login-retry"));
                        prompt(session);
                    }
                }
            }
        });
    }

    private boolean expired(Session session) {
        return plugin.getConfig().getBoolean("timeout.enabled", true) && clock.getAsLong() >= session.deadline;
    }

    private void expire(Session session) {
        auth.cancelPending(session.player);
        if ("REOPEN".equalsIgnoreCase(plugin.getConfig().getString("timeout.on-expire", "KICK"))) {
            session.deadline = deadline();
            session.player.sendMessage(lang.msg(session.player, "chat-login-expired"));
            prompt(session);
        } else kick(session, "auth-timeout");
    }

    private void kick(Session session, String key) {
        forceClose(session.player);
        PlatformCompat.kick(session.player, lang.tr(session.player, key));
    }

    private void tick() {
        for (Session session : new ArrayList<>(sessions.values())) {
            if (!current(session) || auth.isAuthed(session.player)) { forceClose(session.player); continue; }
            if (expired(session)) expire(session);
        }
    }

    @Override public void forceClose(Player player) { sessions.remove(player.getUniqueId()); }
    @Override public void cleanupSession(Player player) { forceClose(player); }
    @Override public void shutdown() { ticker.cancel(); sessions.clear(); }

    private static final class Session {
        final Player player;
        long deadline;
        Session(Player player, long deadline) { this.player = player; this.deadline = deadline; }
    }
}
