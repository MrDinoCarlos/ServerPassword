package es.mrdino.serverpassword;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
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

        initialize(player, e);
    }

    /** Also protects players who were online before this plugin was enabled. */
    void initialize(Player player, PlayerJoinEvent announcement) {

        // Reset per-connection state, but keep a valid remembered session.
        boolean remembered = auth.beginJoin(player);
        if (!auth.isPasswordConfigured()) {
            if (!auth.isAuthed(player)) lock.lock(player);
            if (announcement != null && !auth.isAuthed(player)) PlatformCompat.suppressJoinMessage(announcement);
            setup.onJoin(player);
            return;
        }
        if (remembered) {
            lock.cleanupSession(player);
            return;
        }

        // Delay the join announcement until authentication succeeds.
        if (announcement != null) PlatformCompat.suppressJoinMessage(announcement);

        // Remember that a join announcement is pending.
        if (announcement != null) stealth.markPendingJoinAnnounce(player);

        // Lock the new connection.
        lock.lock(player);

        // Hide other unauthenticated players from this player.
        stealth.hideLockedFrom(player, lock.getLockedPlayersOnline());
    }

    // Quit cannot be cancelled. Kick can, even by a listener running after ours.
    @EventHandler(priority = EventPriority.LOWEST)
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

}
