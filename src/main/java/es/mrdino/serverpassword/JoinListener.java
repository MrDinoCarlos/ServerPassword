package es.mrdino.serverpassword;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

public class JoinListener implements Listener {

    private final JavaPlugin plugin;
    private final AuthManager auth;
    private final LockdownManager lock;
    private final StealthManager stealth;

    public JoinListener(JavaPlugin plugin, AuthManager auth, LockdownManager lock, StealthManager stealth) {
        this.plugin = plugin;
        this.auth = auth;
        this.lock = lock;
        this.stealth = stealth;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player player = e.getPlayer();

        // Reset per-connection state, but keep a valid remembered session.
        if (auth.beginJoin(player)) {
            lock.cleanupSession(player);
            return;
        }

        // ✅ No anunciar join todavía
        e.setJoinMessage(null);

        // ✅ Marcar join pendiente para anunciar al autenticar
        stealth.markPendingJoinAnnounce(player);

        // ✅ Asegurar sesión limpia y lock
        lock.lock(player);

        // ✅ Si ya hay otros locked, que el nuevo no los vea
        stealth.hideLockedFrom(player, lock.getLockedPlayersOnline());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        e.setQuitMessage(null);

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
