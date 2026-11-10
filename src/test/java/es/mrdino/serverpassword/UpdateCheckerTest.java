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
    @Test void startupAndDailyChecksAreScheduledWhileJoinsOnlyReuseCachedResults() throws Exception {
        Fixture fixture = new Fixture(true);
        verify(fixture.scheduler).runTaskTimerAsynchronously(eq(fixture.plugin), any(Runnable.class), eq(40L), eq(1_728_000L));
        verify(fixture.scheduler, never()).runTaskLaterAsynchronously(any(), any(Runnable.class), anyLong());
        verifyNoInteractions(fixture.source);
        fixture.check.run();
        when(fixture.auth.isAuthed(fixture.player)).thenReturn(true);
        for (int join = 0; join < 4; join++) {
            PlayerJoinEvent event = mock(PlayerJoinEvent.class);
            when(event.getPlayer()).thenReturn(fixture.player);
            fixture.checker.onJoin(event);
            fixture.notifier.run();
            fixture.notifier.run();
        }
        verify(fixture.source, times(1)).read("1.0.0");
        verify(fixture.logger, times(1)).info(anyString());
        fixture.check.run(); // Scheduler's next run, after 24 hours.
        verify(fixture.source, times(2)).read("1.0.0");
        verify(fixture.logger, times(2)).info(anyString());
        fixture.checker.shutdown();
    }

    @Test void aFailedCheckWaitsForTheNextDailyRunWithoutImmediateRetries() throws Exception {
        Fixture fixture = new Fixture(true);
        when(fixture.source.read(anyString())).thenThrow(new java.io.IOException("Fixture connection failure"));
        fixture.check.run();
        for (int tick = 0; tick < 4; tick++) fixture.notifier.run();
        verify(fixture.source, times(1)).read("1.0.0");
        verify(fixture.logger, times(1)).warning(contains("IOException"));
        verify(fixture.scheduler, times(1)).runTaskTimerAsynchronously(eq(fixture.plugin), any(Runnable.class), eq(40L), eq(1_728_000L));
        verify(fixture.scheduler, never()).runTaskLaterAsynchronously(any(), any(Runnable.class), anyLong());
        fixture.checker.shutdown();
    }

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
        final JavaPlugin plugin = mock(JavaPlugin.class);
        final BukkitScheduler scheduler = mock(BukkitScheduler.class);
        final UpdateChecker.VersionSource source = mock(UpdateChecker.VersionSource.class);
        final UpdateChecker checker;
        Runnable check;
        Runnable notifier;

        Fixture(boolean notify) {
            var config = PasswordSecurityTest.defaults();
            config.set("updates.notify-admins", notify);
            Server server = mock(Server.class);
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
            try {
                when(source.read(anyString())).thenReturn(JsonParser.parseString("""
                        [{"id":"new","version_number":"1.1.0","game_versions":["1.20.1"],"loaders":["paper"]}]
                        """).getAsJsonArray());
            } catch (Exception impossible) { throw new AssertionError(impossible); }
            checker = new UpdateChecker(plugin, auth, mock(LockdownManager.class), lang, source);
            PlayerJoinEvent join = mock(PlayerJoinEvent.class);
            when(join.getPlayer()).thenReturn(player);
            checker.onJoin(join);
        }
    }
}
