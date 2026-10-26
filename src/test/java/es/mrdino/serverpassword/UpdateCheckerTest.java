package es.mrdino.serverpassword;

import com.google.gson.JsonParser;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.logging.Logger;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UpdateCheckerTest {
    @Test void disablingChatDoesNotDisableTheCheckerOrConsoleNotice() {
        Fixture fixture = new Fixture(false);
        fixture.check.run();
        fixture.notifier.run();
        verify(fixture.logger).info(contains("1.1.0"));
        verify(fixture.player, never()).sendMessage(anyString());
        fixture.checker.shutdown();
    }

    @Test void administratorIsNotifiedOnceAfterAuthentication() {
        Fixture fixture = new Fixture(true);
        fixture.check.run();
        verify(fixture.player, never()).sendMessage(anyString());
        when(fixture.auth.isAuthed(fixture.player)).thenReturn(true);
        fixture.notifier.run();
        fixture.notifier.run();
        verify(fixture.player, times(1)).sendMessage("UPDATE");
        fixture.checker.shutdown();
    }

    private static final class Fixture {
        final Player player = mock(Player.class);
        final AuthManager auth = mock(AuthManager.class);
        final Logger logger = mock(Logger.class);
        final UpdateChecker checker;
        Runnable check;
        Runnable notifier;

        Fixture(boolean notify) {
            var config = PasswordSecurityTest.defaults();
            config.set("updates.notify-admins", notify);
            JavaPlugin plugin = mock(JavaPlugin.class);
            Server server = mock(Server.class);
            BukkitScheduler scheduler = mock(BukkitScheduler.class);
            UUID id = UUID.randomUUID();
            when(player.getUniqueId()).thenReturn(id);
            when(player.isOnline()).thenReturn(true);
            when(player.hasPermission("serverpassword.admin")).thenReturn(true);
            when(plugin.getConfig()).thenReturn(config);
            when(plugin.getServer()).thenReturn(server);
            when(plugin.getDescription()).thenReturn(new PluginDescriptionFile("ServerPassword", "1.0.0", "example.Main"));
            when(plugin.getLogger()).thenReturn(logger);
            when(plugin.isEnabled()).thenReturn(true);
            when(server.getScheduler()).thenReturn(scheduler);
            when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
            when(server.getBukkitVersion()).thenReturn("1.20.1-R0.1-SNAPSHOT");
            when(server.getName()).thenReturn("Paper");
            when(server.getPlayer(id)).thenReturn(player);
            when(scheduler.runTaskTimerAsynchronously(eq(plugin), any(Runnable.class), anyLong(), anyLong())).thenAnswer(call -> {
                check = call.getArgument(1); return mock(BukkitTask.class);
            });
            when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), anyLong(), anyLong())).thenAnswer(call -> {
                notifier = call.getArgument(1); return mock(BukkitTask.class);
            });
            when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
                ((Runnable) call.getArgument(1)).run(); return mock(BukkitTask.class);
            });
            Lang lang = mock(Lang.class);
            when(lang.msg(eq(player), eq("update-available"), anyMap())).thenReturn("UPDATE");
            checker = new UpdateChecker(plugin, auth, mock(LockdownManager.class), lang, version -> JsonParser.parseString("""
                    [{"id":"new","version_number":"1.1.0","game_versions":["1.20.1"],"loaders":["paper"]}]
                    """).getAsJsonArray());
            PlayerJoinEvent join = mock(PlayerJoinEvent.class);
            when(join.getPlayer()).thenReturn(player);
            checker.onJoin(join);
        }
    }
}
