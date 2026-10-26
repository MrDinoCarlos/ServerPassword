package es.mrdino.serverpassword;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.InetAddress;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class AuthManager {
    private final JavaPlugin plugin;
    private final Lang lang;

    private final Set<UUID> authed = new HashSet<>();
    private final Map<UUID, Long> rememberedUntilMs = new HashMap<>();
    private final Map<UUID, Integer> attemptsThisJoin = new HashMap<>();
    private final Map<String, Integer> failsByIp = new HashMap<>();
    private final Map<UUID, Long> cooldownUntilMs = new HashMap<>();
    private final Map<UUID, Long> pending = new HashMap<>();
    private PasswordCredential credential;
    private long credentialGeneration;
    private long requestSequence;
    private final ThreadPoolExecutor verifier = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(64), task -> {
                Thread thread = new Thread(task, "ServerPassword-hash");
                thread.setDaemon(true);
                return thread;
            });

    public int passwordLength() { return credential.length(); }
    public boolean passwordIsNumeric() { return credential.numeric(); }
    public boolean isPasswordConfigured() { return credential.configured(); }

    boolean updateCredential(PasswordCredential next) {
        boolean changed = !credential.equals(next);
        credential = next;
        if (changed) {
            credentialGeneration++;
            rememberedUntilMs.clear();
            authed.clear();
            pending.clear();
        }
        return changed;
    }

    public AuthManager(JavaPlugin plugin, Lang lang) {
        this.plugin = plugin;
        this.lang = lang;
        this.credential = PasswordCredential.read(plugin.getConfig());
    }

    public boolean isAuthed(Player p) {
        if (!isPasswordConfigured()) return p.hasPermission("serverpassword.admin");
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
        pending.remove(id);
        attemptsThisJoin.remove(id);
        cooldownUntilMs.remove(id);

        if (!isPasswordConfigured()) return p.hasPermission("serverpassword.admin");

        if (!hasRememberedSession(id)) {
            return false;
        }

        authed.add(id);
        return true;
    }

    public void clearSession(Player p) {
        pending.remove(p.getUniqueId());
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

    public void tryPasswordAsync(Player p, String pass, Consumer<AuthResult> callback) {
        if (!isPasswordConfigured() || pass == null || pass.length() > 128 || pass.chars().anyMatch(Character::isISOControl)) {
            callback.accept(AuthResult.INVALID_INPUT);
            return;
        }
        UUID id = p.getUniqueId();
        if (isOnCooldown(p) || pending.containsKey(id)) {
            callback.accept(AuthResult.COOLDOWN);
            return;
        }
        long request = ++requestSequence;
        long generation = credentialGeneration;
        PasswordCredential expected = credential;
        pending.put(id, request);
        try {
            verifier.execute(() -> {
                boolean correct = PasswordHasher.verify(pass, expected.hash());
                if (!plugin.isEnabled()) return;
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!Objects.equals(pending.get(id), request)) return;
                    pending.remove(id);
                    if (!p.isOnline() || generation != credentialGeneration) return;
                    callback.accept(finishAttempt(p, correct));
                });
            });
        } catch (RejectedExecutionException busy) {
            pending.remove(id);
            callback.accept(AuthResult.COOLDOWN);
        }
    }

    void createCredentialAsync(String password, String legacyAlphabet, Consumer<PasswordCredential> callback) {
        verifier.execute(() -> {
            PasswordCredential generated = PasswordCredential.create(password, legacyAlphabet);
            if (plugin.isEnabled()) plugin.getServer().getScheduler().runTask(plugin, () -> callback.accept(generated));
        });
    }

    void resetAttempts(Player player) {
        attemptsThisJoin.remove(player.getUniqueId());
    }

    void cancelPending(Player player) { pending.remove(player.getUniqueId()); }

    private AuthResult finishAttempt(Player p, boolean ok) {
        int maxAttempts = plugin.getConfig().getInt("max-attempts-per-join", 3);
        int cooldownSec = plugin.getConfig().getInt("attempt-cooldown-seconds", 2);

        if (ok) {
            markAuthed(p);
            return AuthResult.SUCCESS;
        }

        // fail
        attemptsThisJoin.put(p.getUniqueId(), getAttemptsThisJoin(p) + 1);
        cooldownUntilMs.put(p.getUniqueId(), System.currentTimeMillis() + cooldownSec * 1000L);

        // count per IP for ban threshold
        InetAddress address = (p.getAddress() != null) ? p.getAddress().getAddress() : null;
        String ip = (address != null) ? address.getHostAddress() : "unknown";
        failsByIp.put(ip, failsByIp.getOrDefault(ip, 0) + 1);

        boolean banEnabled = plugin.getConfig().getBoolean("ban.enabled", true);
        int banAfter = plugin.getConfig().getInt("ban.after-total-fails", 8);
        long banDuration = plugin.getConfig().getLong("ban.duration-seconds", 0);

        if (banEnabled && address != null && failsByIp.get(ip) >= banAfter) {
            banIp(address, banDuration);
            return AuthResult.BANNED;
        }

        if (getAttemptsThisJoin(p) >= maxAttempts) {
            return AuthResult.MAX_ATTEMPTS;
        }

        return AuthResult.WRONG;
    }

    private void banIp(InetAddress address, long durationSeconds) {
        Date expires = null;
        if (durationSeconds > 0) {
            expires = new Date(System.currentTimeMillis() + Duration.ofSeconds(durationSeconds).toMillis());
        }
        PlatformCompat.banIp(address, "Too many failed password attempts", expires);
    }

    public void shutdown() {
        verifier.shutdownNow();
        pending.clear();
        authed.clear();
        rememberedUntilMs.clear();
        attemptsThisJoin.clear();
        cooldownUntilMs.clear();
        failsByIp.clear();
    }

    public enum AuthResult { SUCCESS, WRONG, MAX_ATTEMPTS, COOLDOWN, BANNED, INVALID_INPUT }
}
