package es.mrdino.serverpassword;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
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
        // ✅ No anunciar join todavía
        e.setJoinMessage(null);

        // ✅ Marcar join pendiente para anunciar al autenticar
        stealth.markPendingJoinAnnounce(e.getPlayer());

        // ✅ Asegurar sesión limpia y lock
        auth.clearSession(e.getPlayer());
        lock.lock(e.getPlayer());

        // ✅ Si ya hay otros locked, que el nuevo no los vea
        stealth.hideLockedFrom(e.getPlayer(), lock.getLockedPlayersOnline());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        // Opcional: limpiar flags / sesiones si quieres
        // auth.clearSession(e.getPlayer());
    }
}
