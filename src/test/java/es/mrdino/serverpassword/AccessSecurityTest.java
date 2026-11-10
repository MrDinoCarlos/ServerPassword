package es.mrdino.serverpassword;

import java.net.InetAddress;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AccessSecurityTest {
    private void deliver(Listener listener, Event event) throws Exception {
        for (var method : listener.getClass().getDeclaredMethods()) {
            EventHandler handler = method.getAnnotation(EventHandler.class);
            if (handler != null && method.getParameterTypes()[0].isInstance(event)) {
                if (handler.ignoreCancelled() && event instanceof org.bukkit.event.Cancellable c && c.isCancelled()) continue;
                method.invoke(listener, event);
            }
        }
    }

    @Test void aKickCancelledBeforeOrAfterListenersCannotUnlockAnUnauthenticatedPlayer() throws Exception {
        for (boolean cancelledBefore : new boolean[]{false, true}) {
            JavaPlugin plugin = mock(JavaPlugin.class);
            Server server = mock(Server.class);
            when(plugin.getConfig()).thenReturn(PasswordSecurityTest.defaults());
            when(plugin.getServer()).thenReturn(server);
            when(server.getScheduler()).thenReturn(mock(BukkitScheduler.class));
            Player player = mock(Player.class);
            when(player.getUniqueId()).thenReturn(UUID.randomUUID());
            when(player.isOnline()).thenReturn(true);
            when(player.getLocation()).thenReturn(new Location(mock(World.class), 0, 64, 0));
            AuthManager auth = mock(AuthManager.class);
            StealthManager stealth = mock(StealthManager.class);
            LockdownManager lock = new LockdownManager(plugin, auth, mock(Lang.class), stealth);
            lock.lock(player);
            JoinListener joins = new JoinListener(auth, lock, stealth, mock(SetupManager.class));
            PlayerKickEvent event = new PlayerKickEvent(player, "auth-timeout", "");
            event.setCancelled(cancelledBefore);
            deliver(joins, event);
            event.setCancelled(true);
            assertTrue(lock.isLocked(player));
            var command = new PlayerCommandPreprocessEvent(player, "/help", new java.util.HashSet<>());
            new LockdownListener(lock).onCmd(command);
            assertTrue(command.isCancelled());
            verify(auth, never()).clearSession(player);
            deliver(joins, new PlayerQuitEvent(player, ""));
            assertFalse(lock.isLocked(player));
            verify(auth).clearSession(player);
            verify(player).setInvulnerable(false);
        }
    }

    @Test void pendingSetupAlsoLocksRejectedPlayersIfAnotherPluginCancelsTheirKick() {
        var auth = mock(AuthManager.class);
        var lock = mock(LockdownManager.class);
        var player = mock(Player.class);
        new JoinListener(auth, lock, mock(StealthManager.class), mock(SetupManager.class)).initialize(player, null);
        verify(lock).lock(player);
    }

    private ServerPassPlugin startup(Server server) {
        ServerPassPlugin plugin = mock(ServerPassPlugin.class, CALLS_REAL_METHODS);
        doNothing().when(plugin).saveDefaultConfig();
        doReturn(mock(Logger.class)).when(plugin).getLogger();
        doReturn(server).when(plugin).getServer();
        return plugin;
    }

    private void assertGuardClosed(PluginManager manager) {
        var listeners = ArgumentCaptor.forClass(Listener.class);
        verify(manager, atLeastOnce()).registerEvents(listeners.capture(), any());
        StartupGuard guard = (StartupGuard) listeners.getAllValues().stream().filter(StartupGuard.class::isInstance).findFirst().orElseThrow();
        var event = mock(AsyncPlayerPreLoginEvent.class);
        guard.onPreLogin(event);
        verify(event).disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, StartupGuard.UNAVAILABLE);
    }

    @Test void invalidStartupConfigurationKeepsAdmissionClosedAndStopsTheServer() {
        Server server = mock(Server.class);
        PluginManager manager = mock(PluginManager.class);
        when(server.getPluginManager()).thenReturn(manager);
        ServerPassPlugin plugin = startup(server);
        doReturn(List.of("Invalid hash")).when(plugin).reloadSettings();
        try (var logs = mockStatic(PasswordLogProtection.class)) {
            logs.when(PasswordLogProtection::install).thenReturn(mock(PasswordLogProtection.class));
            plugin.onEnable();
            verify(server).shutdown();
            verify(manager, never()).disablePlugin(plugin);
            assertGuardClosed(manager);
        }
    }

    @Test void failureToInstallLogProtectionCannotStartAnUnprotectedServer() {
        Server server = mock(Server.class);
        PluginManager manager = mock(PluginManager.class);
        when(server.getPluginManager()).thenReturn(manager);
        ServerPassPlugin plugin = startup(server);
        try (var logs = mockStatic(PasswordLogProtection.class)) {
            logs.when(PasswordLogProtection::install).thenThrow(new IllegalStateException("Unavailable backend"));
            plugin.onEnable();
            verify(server).shutdown();
            assertGuardClosed(manager);
        }
    }

    @Test void activatingThePluginAuthenticatesExistingPlayersBeforeOpeningAdmission() {
        Server server = mock(Server.class);
        PluginManager manager = mock(PluginManager.class);
        when(server.getPluginManager()).thenReturn(manager);
        when(server.getOnlineMode()).thenReturn(true);
        Player player = mock(Player.class);
        doReturn(List.of(player)).when(server).getOnlinePlayers();
        ServerPassPlugin plugin = startup(server);
        doReturn(PasswordSecurityTest.defaults()).when(plugin).getConfig();
        doReturn(List.of()).when(plugin).reloadSettings();
        doReturn(null).when(plugin).getCommand(anyString());
        try (var languages = mockConstruction(Lang.class);
             var authentication = mockConstruction(AuthManager.class, (m, c) -> when(m.isPasswordConfigured()).thenReturn(true));
             var setup = mockConstruction(SetupManager.class);
             var stealth = mockConstruction(StealthManager.class);
             var locks = mockConstruction(LockdownManager.class);
             var interfaces = mockConstruction(LoginUI.class);
             var passwordSetup = mockConstruction(PasswordSetupUI.class);
             var updates = mockConstruction(UpdateChecker.class);
             var logs = mockStatic(PasswordLogProtection.class)) {
            logs.when(PasswordLogProtection::install).thenReturn(mock(PasswordLogProtection.class));
            plugin.onEnable();
            verify(authentication.constructed().get(0)).beginJoin(player);
            verify(locks.constructed().get(0)).lock(player);
            verify(server, never()).shutdown();
            var listeners = ArgumentCaptor.forClass(Listener.class);
            verify(manager, atLeastOnce()).registerEvents(listeners.capture(), eq(plugin));
            StartupGuard guard = (StartupGuard) listeners.getAllValues().stream().filter(StartupGuard.class::isInstance).findFirst().orElseThrow();
            var event = mock(AsyncPlayerPreLoginEvent.class);
            guard.onPreLogin(event);
            verifyNoInteractions(event);
            plugin.onDisable();
            assertGuardClosed(manager);
        }
    }
}
