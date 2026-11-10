package es.mrdino.serverpassword;

import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthManagerTest {
    private AuthManager auth;
    private Player player;
    private Server server;
    private org.bukkit.configuration.file.YamlConfiguration config;
    private final BlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();

    @BeforeEach void setup() {
        config = PasswordSecurityTest.defaults();
        config.set("ban.enabled", false);
        JavaPlugin plugin = mock(JavaPlugin.class);
        server = mock(Server.class);
        when(server.getOnlineMode()).thenReturn(true);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
            callbacks.add(call.getArgument(1));
            return mock(BukkitTask.class);
        });
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);
        auth = new AuthManager(plugin, mock(Lang.class));
    }

    @AfterEach void cleanup() { auth.shutdown(); }

    @Test void offlineAndProxyBackendsNeverRestoreRememberedUuidSessions() {
        auth.markAuthed(player);
        auth.clearSession(player);
        when(server.getOnlineMode()).thenReturn(false);
        Player impostor = mock(Player.class);
        UUID id = player.getUniqueId();
        when(impostor.getUniqueId()).thenReturn(id);
        assertFalse(auth.beginJoin(impostor));
        assertFalse(auth.isAuthed(impostor));
        auth.markAuthed(player);
        assertTrue(auth.isAuthed(player), "Entering the password still authorizes this offline connection");
        assertFalse(auth.isAuthed(impostor), "A different Player with the same UUID cannot inherit current authorization");
        auth.clearSession(player);
        assertFalse(auth.beginJoin(player));
    }

    @Test void anUnconfiguredOfflineServerCannotTrustAnImpersonatedAdministrator() {
        when(server.getOnlineMode()).thenReturn(false);
        auth.updateCredential(PasswordCredential.read(PasswordSecurityTest.freshDefaults()));
        when(player.hasPermission("serverpassword.admin")).thenReturn(true);
        assertFalse(auth.isAuthed(player));
        assertFalse(auth.beginJoin(player));
    }

    @Test void disablingRememberingImmediatelyRevokesStoredReconnectSessions() {
        auth.markAuthed(player);
        auth.clearSession(player);
        config.set("remember-session-minutes", 0);
        auth.updateCredential(PasswordCredential.read(config));
        assertFalse(auth.beginJoin(player));
    }

    @Test void noPasswordAllowsOnlyAdministratorsWithoutCreatingASession() {
        auth.updateCredential(PasswordCredential.read(PasswordSecurityTest.freshDefaults()));
        assertFalse(auth.isPasswordConfigured());
        assertFalse(auth.beginJoin(player));
        assertFalse(auth.isAuthed(player));
        when(player.hasPermission("serverpassword.admin")).thenReturn(true);
        assertTrue(auth.beginJoin(player));
        assertTrue(auth.isAuthed(player));
        auth.updateCredential(PasswordCredential.read(PasswordSecurityTest.defaults()));
        assertTrue(auth.isPasswordConfigured());
        assertFalse(auth.isAuthed(player), "Completing setup must require the administrator to authenticate too");
        assertFalse(auth.beginJoin(player));
    }

    @Test void anUnsetPasswordCannotAccumulateFailuresOrAuthenticateFromInput() {
        auth.updateCredential(PasswordCredential.read(PasswordSecurityTest.freshDefaults()));
        AtomicReference<AuthManager.AuthResult> result = new AtomicReference<>();
        auth.tryPasswordAsync(player, "1234", result::set);
        assertEquals(AuthManager.AuthResult.INVALID_INPUT, result.get());
        assertEquals(0, auth.getAttemptsThisJoin(player));
        assertTrue(callbacks.isEmpty());
    }

    @Test void passwordChangeInvalidatesActiveAndRememberedSessions() {
        auth.markAuthed(player);
        auth.clearSession(player);
        assertTrue(auth.beginJoin(player));
        var previous = PasswordCredential.read(PasswordSecurityTest.defaults());
        assertTrue(auth.updateCredential(new PasswordCredential(previous.hash(), 5, true, previous.legacyAlphabet())));
        assertFalse(auth.isAuthed(player));
        assertFalse(auth.beginJoin(player));
    }

    @Test void ordinaryReloadKeepsExistingSessions() {
        auth.markAuthed(player);
        assertFalse(auth.updateCredential(PasswordCredential.read(PasswordSecurityTest.defaults())));
        assertTrue(auth.isAuthed(player));
    }

    @Test void staleSuccessfulVerificationCannotAuthenticateAfterAChange() throws Exception {
        AtomicReference<AuthManager.AuthResult> result = new AtomicReference<>();
        auth.tryPasswordAsync(player, "1234", result::set);
        Runnable callback = callbacks.poll(10, TimeUnit.SECONDS);
        assertNotNull(callback);
        var previous = PasswordCredential.read(PasswordSecurityTest.defaults());
        auth.updateCredential(new PasswordCredential(previous.hash(), 5, true, previous.legacyAlphabet()));
        callback.run();
        assertNull(result.get());
        assertFalse(auth.isAuthed(player));
    }

    @Test void duplicateRequestsDoNotConsumeExtraAttempts() throws Exception {
        AtomicReference<AuthManager.AuthResult> first = new AtomicReference<>();
        AtomicReference<AuthManager.AuthResult> duplicate = new AtomicReference<>();
        auth.tryPasswordAsync(player, "wrong", first::set);
        auth.tryPasswordAsync(player, "wrong", duplicate::set);
        assertEquals(AuthManager.AuthResult.COOLDOWN, duplicate.get());
        Runnable callback = callbacks.poll(10, TimeUnit.SECONDS);
        assertNotNull(callback);
        callback.run();
        assertEquals(AuthManager.AuthResult.WRONG, first.get());
        assertEquals(1, auth.getAttemptsThisJoin(player));
    }

    @Test void aResultFromADisconnectedPlayerIsDiscarded() throws Exception {
        AtomicReference<AuthManager.AuthResult> result = new AtomicReference<>();
        auth.tryPasswordAsync(player, "1234", result::set);
        Runnable callback = callbacks.poll(10, TimeUnit.SECONDS);
        assertNotNull(callback);
        auth.clearSession(player);
        callback.run();
        assertNull(result.get());
        assertFalse(auth.isAuthed(player));
    }
}
