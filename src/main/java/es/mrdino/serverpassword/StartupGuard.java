package es.mrdino.serverpassword;

import java.util.function.BooleanSupplier;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerLoginEvent;

/** Denies admission until configuration and all protection listeners are ready. */
final class StartupGuard implements Listener {
    static final String UNAVAILABLE = "Server access protection is unavailable. Contact the administrator.";
    private final BooleanSupplier ready;

    StartupGuard(BooleanSupplier ready) { this.ready = ready; }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (!ready.getAsBoolean()) event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, UNAVAILABLE);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLogin(PlayerLoginEvent event) {
        if (!ready.getAsBoolean()) event.disallow(PlayerLoginEvent.Result.KICK_OTHER, UNAVAILABLE);
    }
}
