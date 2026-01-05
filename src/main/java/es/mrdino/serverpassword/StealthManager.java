package es.mrdino.serverpassword;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public class StealthManager {
    private final JavaPlugin plugin;

    // Guardamos si hay que emitir el join “tarde”
    private final Set<UUID> pendingJoinAnnounce = new HashSet<>();

    public StealthManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Oculta al jugador de TODOS (mundo + TAB) */
    public void hideFromEveryone(Player p) {
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.equals(p)) continue;
            other.hidePlayer(plugin, p);
        }
    }

    /** Vuelve a mostrar al jugador a TODOS (mundo + TAB) */
    public void showToEveryone(Player p) {
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.equals(p)) continue;
            other.showPlayer(plugin, p);
        }
    }

    /** Por si entra alguien nuevo mientras p está oculto: que tampoco lo vea */
    public void hideLockedFrom(Player viewer, Collection<Player> lockedPlayers) {
        for (Player locked : lockedPlayers) {
            if (viewer.equals(locked)) continue;
            viewer.hidePlayer(plugin, locked);
        }
    }

    public void markPendingJoinAnnounce(Player p) {
        pendingJoinAnnounce.add(p.getUniqueId());
    }

    public boolean consumePendingJoinAnnounce(Player p) {
        return pendingJoinAnnounce.remove(p.getUniqueId());
    }
}
