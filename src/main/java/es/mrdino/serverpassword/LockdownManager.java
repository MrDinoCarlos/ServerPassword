package es.mrdino.serverpassword;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class LockdownManager {
    private static final int LOCK_EFFECT_DURATION_TICKS = 20 * 60 * 10;

    private final JavaPlugin plugin;
    private final AuthManager auth;
    private final Lang lang;
    private final StealthManager stealth;

    private volatile PasswordUI ui;

    private final Map<UUID, Location> returnLocation = new HashMap<>();
    private final Map<UUID, Boolean> locked = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerState> previousState = new ConcurrentHashMap<>();

    public LockdownManager(JavaPlugin plugin, AuthManager auth, Lang lang, StealthManager stealth) {
        this.plugin = plugin;
        this.auth = auth;
        this.lang = lang;
        this.stealth = stealth;
    }

    public void setUi(PasswordUI ui) {
        this.ui = ui;
    }

    public boolean isLocked(Player p) {
        return locked.getOrDefault(p.getUniqueId(), false);
    }

    public boolean handleChat(Player player, String message) {
        PasswordUI current = ui;
        return current != null && current.handleChat(player, message);
    }

    public void ensureTemporaryFlight(Player player) {
        if (!isLocked(player)) return;
        if (!player.getAllowFlight()) player.setAllowFlight(true);
        player.setFallDistance(0.0f);
    }

    public void lock(Player p) {
        if (auth.isAuthed(p)) return;

        boolean effects = plugin.getConfig().getBoolean("lock.apply-blindness", true);
        previousState.computeIfAbsent(p.getUniqueId(), ignored -> captureState(p, effects));
        locked.put(p.getUniqueId(), true);

        // Record the player's current return location.
        updateReturnLocation(p);

        // Prevent vanilla's floating/flying kick while movement is frozen for authentication.
        ensureTemporaryFlight(p);

        if (effects) {
            // Long-lived effects without particles.
            addEffect(p, "BLINDNESS", 1);
            addEffect(p, "DARKNESS", 0);
            addEffect(p, "INVISIBILITY", 0);

            // Apply temporary protection and hide the player.
            p.setInvulnerable(true);
            p.setCollidable(false);
            p.setInvisible(true); // Player visibility is also managed with effects and hidePlayer.
            stealth.hideFromEveryone(p);
        }

        PlatformCompat.sendTitle(
                p,
                lang.tr(p, "locked-title"),
                lang.tr(p, "locked-subtitle"),
                0, 60, 10
        );

        // Open the login interface after the connection has settled.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (ui == null) return;
            if (p.isOnline() && isLocked(p) && !auth.isAuthed(p)) ui.open(p);
        }, 10L);
    }

    public void unlock(Player p) {
        locked.remove(p.getUniqueId());

        // Close the active login interface.
        if (ui != null) ui.forceClose(p);

        restoreState(p);

        // Release only this plugin's visibility restriction.
        stealth.showToEveryone(p);
        if (stealth.consumePendingJoinAnnounce(p)) {
            PlatformCompat.broadcast(plugin.getServer(), lang.tr(p, "join-message", Map.of("player", p.getName())));
        }

        String mode = plugin.getConfig().getString("success-teleport", "LAST_LOCATION");
        if ("SPAWN".equalsIgnoreCase(mode)) {
            p.teleport(p.getWorld().getSpawnLocation());
        } else {
            Location loc = returnLocation.get(p.getUniqueId());
            if (loc != null && !p.getLocation().equals(loc)) {
                p.teleport(loc);
            } else if (loc == null) {
                // Use the spawn if no return location was recorded.
                p.teleport(p.getWorld().getSpawnLocation());
            }
        }

        PlatformCompat.sendTitle(p, lang.tr(p, "success"), "", 0, 40, 10);
    }

    public void clear(Player p) {
        locked.remove(p.getUniqueId());
        returnLocation.remove(p.getUniqueId());
        if (ui != null) ui.forceClose(p);

        restoreState(p);
        stealth.showToEveryone(p);
        stealth.clearPendingJoinAnnounce(p);
    }

    public void shutdown() {
        for (Player player : new java.util.ArrayList<>(getLockedPlayersOnline())) {
            clear(player);
        }
        locked.clear();
        previousState.clear();
        returnLocation.clear();
    }

    public java.util.Collection<Player> getLockedPlayersOnline() {
        var list = new java.util.ArrayList<Player>();
        for (var p : plugin.getServer().getOnlinePlayers()) {
            if (isLocked(p)) list.add(p);
        }
        return list;
    }

    /**
     * Updates the saved return location to the player's current location.
     * Called when locking or disconnecting a player.
     */
    public void updateReturnLocation(Player player) {
        if (player == null) return;
        UUID uuid = player.getUniqueId();
        returnLocation.put(uuid, player.getLocation());
    }

    /**
     * Clears temporary authentication state while retaining the return location
     * for a later LAST_LOCATION teleport.
     */
    public void cleanupSession(Player player) {
        if (player == null) return;
        UUID uuid = player.getUniqueId();

        // Restore every temporary player flag before the connection is saved.
        locked.remove(uuid);
        restoreState(player);
        stealth.showToEveryone(player);
        if (ui != null) ui.cleanupSession(player);

        // Retain the last return location across connections.
        // returnLocation.remove(uuid);
    }

    private void addEffect(Player player, String effectName, int amplifier) {
        PotionEffectType type = effect(effectName);
        if (type == null) return;
        player.addPotionEffect(new PotionEffect(type, LOCK_EFFECT_DURATION_TICKS, amplifier, false, false));
    }

    private PlayerState captureState(Player player, boolean visualLockApplied) {
        return new PlayerState(
                visualLockApplied,
                PlayerInvulnerability.capture(player),
                player.isCollidable(),
                player.isInvisible(),
                player.getAllowFlight(),
                player.isFlying(),
                currentEffect(player, "BLINDNESS"),
                currentEffect(player, "DARKNESS"),
                currentEffect(player, "INVISIBILITY")
        );
    }

    private void restoreState(Player player) {
        PlayerState state = previousState.remove(player.getUniqueId());
        if (state == null) return;

        if (state.visualLockApplied()) {
            restoreEffect(player, "BLINDNESS", state.blindness());
            restoreEffect(player, "DARKNESS", state.darkness());
            restoreEffect(player, "INVISIBILITY", state.invisibility());
            player.setInvulnerable(state.invulnerable());
            player.setCollidable(state.collidable());
            player.setInvisible(state.invisible());
        }

        if (state.allowFlight()) {
            player.setAllowFlight(true);
            player.setFlying(state.flying());
        } else {
            if (player.isFlying()) player.setFlying(false);
            player.setAllowFlight(false);
        }
        player.setFallDistance(0.0f);
    }

    private PotionEffect currentEffect(Player player, String effectName) {
        PotionEffectType type = effect(effectName);
        return type == null ? null : player.getPotionEffect(type);
    }

    private void restoreEffect(Player player, String effectName, PotionEffect previousEffect) {
        PotionEffectType type = effect(effectName);
        if (type == null) return;
        player.removePotionEffect(type);
        if (previousEffect != null) {
            player.addPotionEffect(previousEffect);
        }
    }

    @SuppressWarnings("deprecation")
    private PotionEffectType effect(String name) {
        try {
            return PotionEffectType.getByName(name);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private record PlayerState(
            boolean visualLockApplied,
            boolean invulnerable,
            boolean collidable,
            boolean invisible,
            boolean allowFlight,
            boolean flying,
            PotionEffect blindness,
            PotionEffect darkness,
            PotionEffect invisibility
    ) {
    }
}
