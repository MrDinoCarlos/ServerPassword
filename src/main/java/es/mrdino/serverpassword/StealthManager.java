package es.mrdino.serverpassword;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public class StealthManager {
    private final JavaPlugin plugin;

    // Track players whose join announcement is pending.
    private final Set<UUID> pendingJoinAnnounce = new HashSet<>();

    public StealthManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Hides the player from other players in the world and tab list. */
    public void hideFromEveryone(Player p) {
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.equals(p)) continue;
            other.hidePlayer(plugin, p);
        }
    }

    /** Releases this plugin's hide request while respecting other plugins' requests. */
    public void showToEveryone(Player p) {
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.equals(p)) continue;
            other.showPlayer(plugin, p);
        }
    }

    /** Hides unauthenticated players from a newly connected viewer. */
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

    public void clearPendingJoinAnnounce(Player p) {
        pendingJoinAnnounce.remove(p.getUniqueId());
    }
}
