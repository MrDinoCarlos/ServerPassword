package es.mrdino.serverpassword;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class LockdownManager {
    private final JavaPlugin plugin;
    private final AuthManager auth;
    private final Lang lang;
    private final StealthManager stealth;


    private CodePasswordUI ui;

    private final Map<UUID, Location> returnLocation = new HashMap<>();
    private final Map<UUID, Boolean> locked = new HashMap<>();

    public LockdownManager(JavaPlugin plugin, AuthManager auth, Lang lang, StealthManager stealth) {
        this.plugin = plugin;
        this.auth = auth;
        this.lang = lang;
        this.stealth = stealth;
    }

    public void setUi(CodePasswordUI ui) { this.ui = ui; }

    public boolean isLocked(Player p) {
        return locked.getOrDefault(p.getUniqueId(), false);
    }

    public void lock(Player p) {
        if (auth.isAuthed(p)) return;

        locked.put(p.getUniqueId(), true);
        returnLocation.putIfAbsent(p.getUniqueId(), p.getLocation());

        boolean effects = plugin.getConfig().getBoolean("lock.apply-blindness", true);
        if (effects) {
            // Duración larga, sin partículas
            p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 20 * 60 * 10, 1, false, false));
            p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 20 * 60 * 10, 0, false, false));
            p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 20 * 60 * 10, 0, false, false));
            // invulnerable + oculto
            p.setInvulnerable(true);
            p.setCollidable(false);
            p.setInvisible(true); // (esto solo afecta a ciertas cosas; la invis real la hacemos con potion + hidePlayer)
            stealth.hideFromEveryone(p);

        }

        p.sendTitle(
                lang.tr(p, "locked-title"),
                lang.tr(p, "locked-subtitle"),
                0, 60, 10
        );

        // Abrir UI con delay
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (ui == null) return;
            if (p.isOnline() && isLocked(p) && !auth.isAuthed(p)) ui.open(p);
        }, 10L);
    }

    public void unlock(Player p) {
        locked.put(p.getUniqueId(), false);

        // Cerrar GUI si estaba abierta
        if (ui != null) ui.forceClose(p);

        p.removePotionEffect(PotionEffectType.BLINDNESS);
        p.removePotionEffect(PotionEffectType.DARKNESS);
        p.setInvulnerable(false);
        p.setCollidable(true);
        p.setInvisible(false);
        p.removePotionEffect(PotionEffectType.INVISIBILITY);
        // volver a mostrar
        stealth.showToEveryone(p);
        if (stealth.consumePendingJoinAnnounce(p)) {
            // Mensaje simple. Si quieres traducible lo hacemos con Lang.
            plugin.getServer().broadcastMessage("§e" + p.getName() + " joined the server");
        }



        String mode = plugin.getConfig().getString("success-teleport", "LAST_LOCATION");
        if ("SPAWN".equalsIgnoreCase(mode)) {
            p.teleport(p.getWorld().getSpawnLocation());
        } else {
            Location loc = returnLocation.get(p.getUniqueId());
            if (loc != null) p.teleport(loc);
        }

        p.sendTitle(lang.tr(p, "success"), "", 0, 40, 10);
    }

    public void clear(Player p) {
        locked.remove(p.getUniqueId());
        returnLocation.remove(p.getUniqueId());
        if (ui != null) ui.forceClose(p);

        p.removePotionEffect(PotionEffectType.BLINDNESS);
        p.removePotionEffect(PotionEffectType.DARKNESS);
    }

    public java.util.Collection<org.bukkit.entity.Player> getLockedPlayersOnline() {
        var list = new java.util.ArrayList<org.bukkit.entity.Player>();
        for (var p : plugin.getServer().getOnlinePlayers()) {
            if (isLocked(p)) list.add(p);
        }
        return list;
    }

}
