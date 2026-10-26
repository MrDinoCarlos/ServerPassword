package es.mrdino.serverpassword;

import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatPasswordUITest {
    private JavaPlugin plugin;
    private AuthManager auth;
    private LockdownManager lock;
    private Lang lang;
    private Player player;
    private ChatPasswordUI ui;
    private Runnable tick;
    private final Queue<Runnable> scheduled = new ArrayDeque<>();
    private final AtomicLong clock = new AtomicLong(1_000_000);

    @BeforeEach void setup() {
        plugin = mock(JavaPlugin.class);
        var config = PasswordSecurityTest.defaults();
        config.set("password-policy.mode", "TEXT");
        config.set("password.numeric", false);
        when(plugin.getConfig()).thenReturn(config);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
            scheduled.add(call.getArgument(1));
            return mock(BukkitTask.class);
        });
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), eq(20L), eq(20L))).thenAnswer(call -> {
            tick = call.getArgument(1);
            return mock(BukkitTask.class);
        });
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);
        lock = mock(LockdownManager.class);
        when(lock.isLocked(player)).thenReturn(true);
        auth = mock(AuthManager.class);
        lang = mock(Lang.class);
        when(lang.msg(any(CommandSender.class), anyString())).thenAnswer(call -> call.getArgument(1));
        when(lang.msg(any(CommandSender.class), anyString(), anyMap())).thenAnswer(call -> call.getArgument(1));
        when(lang.tr(any(CommandSender.class), anyString())).thenAnswer(call -> call.getArgument(1));
        ui = new ChatPasswordUI(plugin, auth, lock, lang, clock::get);
        ui.open(player);
    }

    @AfterEach void cleanup() { ui.shutdown(); }

    private void answer(AuthManager.AuthResult result) {
        doAnswer(call -> { ((Consumer<AuthManager.AuthResult>) call.getArgument(2)).accept(result); return null; })
                .when(auth).tryPasswordAsync(eq(player), anyString(), any());
    }

    @Test void textLoginOpensNoGuiAndPreservesExactCaseSpacesAndSymbols() {
        assertTrue(ui.handleChat(player, "  My password_!  "));
        verify(auth, never()).tryPasswordAsync(any(), any(), any());
        scheduled.remove().run();
        verify(auth).tryPasswordAsync(eq(player), eq("  My password_!  "), any());
        verify(player, never()).openInventory(any(org.bukkit.inventory.Inventory.class));
        verify(player, never()).openAnvil(any(), anyBoolean());
        verify(player, never()).sendMessage(contains("My password_!"));
    }

    @Test void successfulChatUnlocksOnceAndStopsCapturingOrdinaryMessages() {
        answer(AuthManager.AuthResult.SUCCESS);
        ui.handleChat(player, "correct password");
        scheduled.remove().run();
        verify(lock).unlock(player);
        verify(player).sendMessage("success");
        assertFalse(ui.handleChat(player, "ordinary chat"));
    }

    @Test void wrongInputAndCooldownKeepLoginLockedAndDoNotEchoThePassword() {
        answer(AuthManager.AuthResult.WRONG);
        ui.handleChat(player, "wrong password");
        scheduled.remove().run();
        verify(player).sendMessage("wrong-password");
        answer(AuthManager.AuthResult.COOLDOWN);
        ui.handleChat(player, "another password");
        scheduled.remove().run();
        verify(player).sendMessage("cooldown");
        verify(lock, never()).unlock(any());
        verify(player, never()).sendMessage(contains("wrong password"));
    }

    @Test void invalidInputDoesNotConsumeAnAttempt() {
        ui.handleChat(player, "x".repeat(129));
        scheduled.remove().run();
        verify(auth, never()).tryPasswordAsync(any(), any(), any());
        verify(player).sendMessage("screen-invalid-input");
    }

    @Test void reopeningLoginDoesNotRestartTheTimeoutAndExpirationCancelsPendingVerification() {
        try (var platform = mockStatic(PlatformCompat.class)) {
            clock.addAndGet(20_000);
            ui.open(player);
            verify(player, times(1)).sendMessage("chat-login-prompt");
            clock.addAndGet(10_000);
            tick.run();
            platform.verify(() -> PlatformCompat.kick(player, "auth-timeout"));
            verify(auth).cancelPending(player);
            assertFalse(ui.handleChat(player, "too late"));
        }
    }

    @Test void timeoutReopenPromptsAgainWithoutCreatingAnInventory() {
        plugin.getConfig().set("timeout.on-expire", "REOPEN");
        clock.addAndGet(30_000);
        tick.run();
        verify(player).sendMessage("chat-login-expired");
        assertTrue(ui.handleChat(player, "new attempt"));
        scheduled.remove().run();
        verify(auth).tryPasswordAsync(eq(player), eq("new attempt"), any());
        verify(player, never()).openInventory(any(org.bukkit.inventory.Inventory.class));
    }

    @Test void attemptLimitsKickOrStartANewRoundAccordingToConfiguration() {
        answer(AuthManager.AuthResult.MAX_ATTEMPTS);
        plugin.getConfig().set("on-fail-action", "REOPEN");
        ui.handleChat(player, "wrong");
        scheduled.remove().run();
        verify(auth).resetAttempts(player);
        verify(player).sendMessage("chat-login-retry");
        plugin.getConfig().set("on-fail-action", "KICK");
        try (var platform = mockStatic(PlatformCompat.class)) {
            ui.handleChat(player, "wrong again");
            scheduled.remove().run();
            platform.verify(() -> PlatformCompat.kick(player, "kicked"));
        }
    }

    @Test void bannedResultsDisconnectWithoutFurtherInput() {
        answer(AuthManager.AuthResult.BANNED);
        try (var platform = mockStatic(PlatformCompat.class)) {
            ui.handleChat(player, "wrong");
            scheduled.remove().run();
            platform.verify(() -> PlatformCompat.kick(player, "banned"));
            assertFalse(ui.handleChat(player, "still wrong"));
        }
    }

    @Test void queuedMessagesCannotVerifyAfterCleanupOrAReplacementSession() {
        ui.handleChat(player, "old message");
        ui.cleanupSession(player);
        ui.open(player);
        scheduled.remove().run();
        verify(auth, never()).tryPasswordAsync(any(), any(), any());
    }

    @Test void delayedSuccessCannotUnlockAfterTheChatSessionWasRemoved() {
        var callback = new java.util.concurrent.atomic.AtomicReference<Consumer<AuthManager.AuthResult>>();
        doAnswer(call -> { callback.set(call.getArgument(2)); return null; })
                .when(auth).tryPasswordAsync(eq(player), anyString(), any());
        ui.handleChat(player, "test");
        scheduled.remove().run();
        ui.cleanupSession(player);
        callback.get().accept(AuthManager.AuthResult.SUCCESS);
        verify(lock, never()).unlock(any());
    }

    @Test void authenticationChatIsCancelledAndClearedBeforeOtherListenersSeeIt() throws Exception {
        var listener = new LegacyPlatformAdapter().chatListener(lock);
        Player other = mock(Player.class);
        var event = new org.bukkit.event.player.AsyncPlayerChatEvent(true, player, "private secret",
                new java.util.HashSet<>(java.util.Set.of(other)));
        listener.getClass().getMethod("onChat", event.getClass()).invoke(listener, event);
        assertTrue(event.isCancelled());
        assertEquals("", event.getMessage());
        assertTrue(event.getRecipients().isEmpty());
        verify(lock).handleChat(player, "private secret");
    }

    @Test void authenticatedPlayersKeepTheirNormalChat() throws Exception {
        when(lock.isLocked(player)).thenReturn(false);
        var listener = new LegacyPlatformAdapter().chatListener(lock);
        var event = new org.bukkit.event.player.AsyncPlayerChatEvent(true, player, "Hello everyone",
                new java.util.HashSet<>());
        listener.getClass().getMethod("onChat", event.getClass()).invoke(listener, event);
        assertFalse(event.isCancelled());
        assertEquals("Hello everyone", event.getMessage());
        verify(lock, never()).handleChat(any(), anyString());
    }
}
