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

    public JoinListener(AuthManager auth, LockdownManager lock, StealthManager stealth) {
        this.auth = auth;
        this.lock = lock;
        this.stealth = stealth;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent e) {
        Player player = e.getPlayer();

        // Reset per-connection state, but keep a valid remembered session.
        if (auth.beginJoin(player)) {
            lock.cleanupSession(player);
            return;
        }

        // ✅ No anunciar join todavía
        PlatformCompat.suppressJoinMessage(e);

        // ✅ Marcar join pendiente para anunciar al autenticar
        stealth.markPendingJoinAnnounce(player);

        // ✅ Asegurar sesión limpia y lock
        lock.lock(player);

        // ✅ Si ya hay otros locked, que el nuevo no los vea
        stealth.hideLockedFrom(player, lock.getLockedPlayersOnline());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        PlatformCompat.suppressQuitMessage(e);

        Player player = e.getPlayer();

        // ✅ Guardar SIEMPRE la última posición real del jugador al salir
        lock.updateReturnLocation(player);

        // ✅ Limpiar estado temporal (sin borrar returnLocation)
        lock.cleanupSession(player);
        auth.clearSession(player);
        stealth.clearPendingJoinAnnounce(player);
    }

    @EventHandler
    public void onKick(PlayerKickEvent e) {
        Player player = e.getPlayer();

        // ✅ Guardar SIEMPRE la última posición real del jugador al salir (kick)
        lock.updateReturnLocation(player);

        // ✅ Limpiar estado temporal (sin borrar returnLocation)
        lock.cleanupSession(player);
        auth.clearSession(player);
        stealth.clearPendingJoinAnnounce(player);
    }
}
