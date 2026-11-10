package es.mrdino.serverpassword;

import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LockdownProtectionTest {
    @Test void unlockingAndDisconnectingRestoreOnlyTheExplicitFlagCapturedBeforeLogin() {
        for (boolean protectedBeforeLogin : new boolean[]{false, true}) {
            for (boolean disconnect : new boolean[]{false, true}) {
                var plugin = mock(JavaPlugin.class); var server = mock(Server.class);
                var config = new YamlConfiguration(); when(plugin.getConfig()).thenReturn(config);
                when(plugin.getServer()).thenReturn(server);
                when(server.getScheduler()).thenReturn(mock(BukkitScheduler.class));
                var player = mock(PlayerInvulnerabilityTest.ModernPlayer.class);
                when(player.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
                var location = new Location(mock(World.class), 0, 64, 0);
                when(player.getLocation()).thenReturn(location);
                when(player.isCollidable()).thenReturn(true);
                // This is true while a 26.3 client loads, even without an explicit invulnerability flag.
                when(player.isInvulnerable()).thenReturn(true);
                var explicit = new AtomicBoolean(protectedBeforeLogin);
                when(player.isMarkedInvulnerable()).thenAnswer(ignored -> explicit.get());
                doAnswer(call -> { explicit.set(call.getArgument(0)); return null; }).when(player).setInvulnerable(anyBoolean());
                var manager = new LockdownManager(plugin, mock(AuthManager.class), mock(Lang.class), mock(StealthManager.class));
                manager.lock(player);
                assertTrue(manager.isLocked(player)); assertTrue(explicit.get());
                if (disconnect) manager.cleanupSession(player); else manager.unlock(player);
                assertFalse(manager.isLocked(player));
                assertEquals(protectedBeforeLogin, explicit.get());
            }
        }
    }

    @Test void administrativeRepairRequiresAnAuthenticatedUnlockedPlayer() throws Exception {
        var plugin = mock(ServerPassPlugin.class, CALLS_REAL_METHODS);
        var lock = mock(LockdownManager.class); var auth = mock(AuthManager.class);
        var player = mock(org.bukkit.entity.Player.class);
        var lockField = ServerPassPlugin.class.getDeclaredField("lockdownManager"); lockField.setAccessible(true); lockField.set(plugin, lock);
        var authField = ServerPassPlugin.class.getDeclaredField("authManager"); authField.setAccessible(true); authField.set(plugin, auth);
        when(lock.isLocked(player)).thenReturn(true);
        assertFalse(plugin.repairProtection(player));
        when(lock.isLocked(player)).thenReturn(false);
        assertFalse(plugin.repairProtection(player));
        verify(player, never()).setInvulnerable(anyBoolean());
        when(auth.isAuthed(player)).thenReturn(true);
        assertTrue(plugin.repairProtection(player));
        verify(player).setInvulnerable(false);
        verify(player, never()).setInvisible(anyBoolean());
        verify(player, never()).setAllowFlight(anyBoolean());
    }
}
