package es.mrdino.serverpassword;

import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.*;

public class AuthManager {
    private final JavaPlugin plugin;
    private final Lang lang;

    private final Set<UUID> authed = new HashSet<>();
    private final Map<UUID, Long> rememberedUntilMs = new HashMap<>();
    private final Map<UUID, Integer> attemptsThisJoin = new HashMap<>();
    private final Map<String, Integer> failsByIp = new HashMap<>();
    private final Map<UUID, Long> cooldownUntilMs = new HashMap<>();

    public String getServerPassword() {
        return plugin.getConfig().getString("password", "changeme");
    }

    public AuthManager(JavaPlugin plugin, Lang lang) {
        this.plugin = plugin;
        this.lang = lang;
    }

    public boolean isAuthed(Player p) {
        UUID id = p.getUniqueId();
        if (authed.contains(id)) {
            return true;
        }
        if (hasRememberedSession(id)) {
            authed.add(id);
            return true;
        }
        return false;
    }

    public void markAuthed(Player p) {
        UUID id = p.getUniqueId();
        authed.add(id);
        rememberSession(id);
        attemptsThisJoin.remove(id);
        cooldownUntilMs.remove(id);
    }

    public boolean beginJoin(Player p) {
        UUID id = p.getUniqueId();
        authed.remove(id);
        attemptsThisJoin.remove(id);
        cooldownUntilMs.remove(id);

        if (!hasRememberedSession(id)) {
            return false;
        }

        authed.add(id);
        return true;
    }

    public void clearSession(Player p) {
        authed.remove(p.getUniqueId());
        attemptsThisJoin.remove(p.getUniqueId());
        cooldownUntilMs.remove(p.getUniqueId());
    }

    private void rememberSession(UUID id) {
        int minutes = plugin.getConfig().getInt("remember-session-minutes", 0);
        if (minutes <= 0) {
            rememberedUntilMs.remove(id);
            return;
        }

        long durationMs = Duration.ofMinutes(minutes).toMillis();
        rememberedUntilMs.put(id, System.currentTimeMillis() + durationMs);
    }

    private boolean hasRememberedSession(UUID id) {
        Long until = rememberedUntilMs.get(id);
        if (until == null) {
            return false;
        }
        if (until <= System.currentTimeMillis()) {
            rememberedUntilMs.remove(id);
            return false;
        }
        return true;
    }

    public int getAttemptsThisJoin(Player p) {
        return attemptsThisJoin.getOrDefault(p.getUniqueId(), 0);
    }

    public boolean isOnCooldown(Player p) {
        long now = System.currentTimeMillis();
        return cooldownUntilMs.getOrDefault(p.getUniqueId(), 0L) > now;
    }

    public long cooldownSecondsLeft(Player p) {
        long now = System.currentTimeMillis();
        long until = cooldownUntilMs.getOrDefault(p.getUniqueId(), 0L);
        return Math.max(0, (until - now + 999) / 1000);
    }

    public AuthResult tryPassword(Player p, String pass) {
        String expected = getServerPassword();
        int maxAttempts = plugin.getConfig().getInt("max-attempts-per-join", 3);
        int cooldownSec = plugin.getConfig().getInt("attempt-cooldown-seconds", 2);

        if (isOnCooldown(p)) {
            return AuthResult.COOLDOWN;
        }

        boolean ok = expected.equals(pass);

        if (ok) {
            markAuthed(p);
            return AuthResult.SUCCESS;
        }

        // fail
        attemptsThisJoin.put(p.getUniqueId(), getAttemptsThisJoin(p) + 1);
        cooldownUntilMs.put(p.getUniqueId(), System.currentTimeMillis() + cooldownSec * 1000L);

        // count per IP for ban threshold
        String ip = (p.getAddress() != null) ? p.getAddress().getAddress().getHostAddress() : "unknown";
        failsByIp.put(ip, failsByIp.getOrDefault(ip, 0) + 1);

        boolean banEnabled = plugin.getConfig().getBoolean("ban.enabled", true);
        int banAfter = plugin.getConfig().getInt("ban.after-total-fails", 8);
        long banDuration = plugin.getConfig().getLong("ban.duration-seconds", 0);

        if (banEnabled && !"unknown".equals(ip) && failsByIp.get(ip) >= banAfter) {
            banIp(ip, banDuration);
            return AuthResult.BANNED;
        }

        if (getAttemptsThisJoin(p) >= maxAttempts) {
            return AuthResult.MAX_ATTEMPTS;
        }

        return AuthResult.WRONG;
    }

    private void banIp(String ip, long durationSeconds) {
        Date expires = null;
        if (durationSeconds > 0) {
            expires = new Date(System.currentTimeMillis() + Duration.ofSeconds(durationSeconds).toMillis());
        }
        Bukkit.getBanList(BanList.Type.IP).addBan(ip, "Too many failed password attempts", expires, null);
    }

    public void shutdown() {
        authed.clear();
        rememberedUntilMs.clear();
        attemptsThisJoin.clear();
        cooldownUntilMs.clear();
    }

    public enum AuthResult { SUCCESS, WRONG, MAX_ATTEMPTS, COOLDOWN, BANNED }
}
