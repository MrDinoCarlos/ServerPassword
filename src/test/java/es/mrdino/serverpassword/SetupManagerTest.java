package es.mrdino.serverpassword;

import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SetupManagerTest {
    private SetupManager setup;
    private AuthManager auth;
    private Player admin;
    private ConsoleCommandSender console;
    private Runnable delayedWarning;

    @BeforeEach void prepare() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(plugin.getConfig()).thenReturn(PasswordSecurityTest.freshDefaults());
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTaskLater(eq(plugin), any(Runnable.class), eq(20L))).thenAnswer(call -> {
            delayedWarning = call.getArgument(1);
            return mock(BukkitTask.class);
        });
        console = mock(ConsoleCommandSender.class);
        when(server.getConsoleSender()).thenReturn(console);
        admin = mock(Player.class);
        when(admin.isOnline()).thenReturn(true);
        when(admin.hasPermission("serverpassword.admin")).thenReturn(true);
        auth = mock(AuthManager.class);
        when(auth.isAuthed(admin)).thenReturn(true);
        Lang lang = mock(Lang.class);
        when(lang.msg(any(CommandSender.class), anyString())).thenAnswer(call -> call.getArgument(1));
        when(lang.tr(any(CommandSender.class), anyString())).thenAnswer(call -> call.getArgument(1));
        when(lang.msg(any(CommandSender.class), anyString(), anyMap())).thenAnswer(call -> {
            Map<String, String> values = call.getArgument(2);
            return call.getArgument(1) + ":" + values.get("digits");
        });
        setup = new SetupManager(plugin, auth, lang);
    }

    @Test void consoleReceivesTheCommandAndConfiguredPinLength() {
        setup.warnConsole();
        verify(console).sendMessage("setup-warning");
        verify(console).sendMessage("secure-setup-console");
        verify(console).sendMessage("setup-policy");
    }

    @Test void adminWarningArrivesOnceAfterJoinAndStopsWhenSetupIsCompleted() {
        setup.onJoin(admin);
        verify(admin, never()).sendMessage(anyString());
        assertNotNull(delayedWarning);
        delayedWarning.run();
        verify(admin).sendMessage("setup-warning");
        verify(admin).sendMessage("secure-setup-pin:4");
        setup.onJoin(admin);
        when(auth.isPasswordConfigured()).thenReturn(true);
        delayedWarning.run();
        setup.warnConsole();
        verify(admin, times(1)).sendMessage("setup-warning");
        verifyNoInteractions(console);
    }

    @Test void normalPlayersCannotJoinBeforeAnAdministratorSetsAPassword() {
        Player player = mock(Player.class);
        try (var platform = mockStatic(PlatformCompat.class)) {
            setup.onJoin(player);
            platform.verify(() -> PlatformCompat.kick(player, "setup-wait"));
        }
        assertNull(delayedWarning);
    }
}
