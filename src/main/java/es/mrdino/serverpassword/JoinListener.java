package es.mrdino.serverpassword;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class JoinListener implements Listener {

    private final AuthManager auth;
    private final LockdownManager lock;
    private final StealthManager stealth;
    private final SetupManager setup;

    JoinListener(AuthManager auth, LockdownManager lock, StealthManager stealth, SetupManager setup) {
        this.auth = auth;
        this.lock = lock;
        this.stealth = stealth;
        this.setup = setup;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent e) {
        Player player = e.getPlayer();

        // Reset per-connection state, but keep a valid remembered session.
        boolean remembered = auth.beginJoin(player);
        if (!auth.isPasswordConfigured()) {
            if (!player.hasPermission("serverpassword.admin")) PlatformCompat.suppressJoinMessage(e);
            setup.onJoin(player);
            return;
        }
        if (remembered) {
            lock.cleanupSession(player);
            return;
        }

        // Delay the join announcement until authentication succeeds.
        PlatformCompat.suppressJoinMessage(e);

        // Remember that a join announcement is pending.
        stealth.markPendingJoinAnnounce(player);

        // Lock the new connection.
        lock.lock(player);

        // Hide other unauthenticated players from this player.
        stealth.hideLockedFrom(player, lock.getLockedPlayersOnline());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        PlatformCompat.suppressQuitMessage(e);

        Player player = e.getPlayer();

        // Save the last actual location on disconnect.
        lock.updateReturnLocation(player);

        // Clear temporary state while retaining the return location.
        lock.cleanupSession(player);
        auth.clearSession(player);
        stealth.clearPendingJoinAnnounce(player);
    }

    @EventHandler
    public void onKick(PlayerKickEvent e) {
        Player player = e.getPlayer();

        // Save the last actual location on kick.
        lock.updateReturnLocation(player);

        // Clear temporary state while retaining the return location.
        lock.cleanupSession(player);
        auth.clearSession(player);
        stealth.clearPendingJoinAnnounce(player);
    }
}
