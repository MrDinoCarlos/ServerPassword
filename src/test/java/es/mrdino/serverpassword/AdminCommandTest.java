package es.mrdino.serverpassword;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdminCommandTest {
    @TempDir Path directory;
    private ServerPassPlugin plugin;
    private CommandSender admin;
    private Command command;
    private AdminCommand executor;

    @BeforeEach void setup() throws Exception {
        plugin = mock(ServerPassPlugin.class);
        when(plugin.getLogger()).thenReturn(mock(java.util.logging.Logger.class));
        var config = PasswordSecurityTest.defaults();
        var field = ServerPassPlugin.class.getDeclaredField("acceptedConfig");
        field.setAccessible(true);
        field.set(plugin, config);
        doCallRealMethod().when(plugin).getConfig();
        doCallRealMethod().when(plugin).changeSettings(anyMap());
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        Files.writeString(directory.resolve("config.yml"), config.saveToString());
        admin = mock(CommandSender.class);
        when(admin.hasPermission("serverpassword.admin")).thenReturn(true);
        command = mock(Command.class);
        Lang lang = mock(Lang.class);
        when(lang.msg(any(), anyString())).thenAnswer(call -> call.getArgument(1));
        when(lang.msg(any(), anyString(), anyMap())).thenAnswer(call -> call.getArgument(1));
        executor = new AdminCommand(plugin, lang);
    }

    private void run(String... args) { assertTrue(executor.onCommand(admin, command, "spass", args)); }

    @Test void modeAndLengthAreSavedWithoutReplacingTheActivePassword() throws Exception {
        String hash = plugin.getConfig().getString("password.hash");
        run("mode", "TEXT");
        run("length", "64");
        assertEquals("TEXT", plugin.getConfig().getString("password-policy.mode"));
        assertEquals(64, plugin.getConfig().getInt("modern-gui.max-length"));
        assertEquals(hash, plugin.getConfig().getString("password.hash"));
        run("mode", "PIN");
        run("length", "6");
        var saved = YamlConfiguration.loadConfiguration(directory.resolve("config.yml").toFile());
        assertEquals(6, saved.getInt("password-policy.pin-length"));
        assertEquals(4, saved.getInt("password.length"));
        verify(admin, times(3)).sendMessage("admin-policy-next-password");
    }

    @Test void incompatibleSettingsLeaveTheFileAndAcceptedConfigUntouched() throws Exception {
        String original = Files.readString(directory.resolve("config.yml"));
        var config = plugin.getConfig();
        run("max-length", "2");
        assertSame(config, plugin.getConfig());
        assertEquals(original, Files.readString(directory.resolve("config.yml")));
        run("length", "10");
        run("timeout", "invalid");
        assertEquals(original, Files.readString(directory.resolve("config.yml")));
        verify(admin).sendMessage("admin-invalid-config");
        verify(admin, times(2)).sendMessage("admin-setting-invalid");
    }

    @Test void supportedInterfaceAndLimitCommandsApplyImmediately() {
        run("gui", "LEGACY");
        run("gui", "MODERN");
        run("presentation", "DIALOG");
        run("input", "TEXT");
        run("timeout", "off");
        assertFalse(plugin.getConfig().getBoolean("timeout.enabled"));
        run("timeout", "60");
        run("attempts", "5");
        run("cooldown", "0");
        run("remember", "30");
        run("notify-admins", "off");
        assertTrue(plugin.getConfig().getBoolean("timeout.enabled"));
        assertEquals(60, plugin.getConfig().getInt("timeout.seconds"));
        assertEquals(5, plugin.getConfig().getInt("max-attempts-per-join"));
        assertEquals(0, plugin.getConfig().getInt("attempt-cooldown-seconds"));
        assertEquals(30, plugin.getConfig().getInt("remember-session-minutes"));
        assertFalse(plugin.getConfig().getBoolean("updates.notify-admins"));
        assertEquals("DIALOG", plugin.getConfig().getString("modern-gui.presentation"));
        assertEquals("TEXT", plugin.getConfig().getString("modern-gui.input"));
    }

    @Test void configurationCommentsAndCustomKeysSurviveAdministrativeChanges() throws Exception {
        var config = plugin.getConfig();
        config.options().parseComments(true);
        config.set("custom-key", "keep me");
        config.setComments("custom-key", List.of("A user comment."));
        run("timeout", "45");
        String saved = Files.readString(directory.resolve("config.yml"));
        assertTrue(saved.contains("# A user comment."));
        assertTrue(saved.contains("keep me"));
    }

    @Test void completionIsPermissionAwareFilteredAndNeverSuggestsPasswords() {
        assertEquals(List.of("mode"), executor.onTabComplete(admin, command, "spass", new String[]{"mo"}));
        assertEquals(List.of("PIN", "TEXT"), executor.onTabComplete(admin, command, "spass", new String[]{"mode", ""}));
        assertEquals(List.of("TEXT"), executor.onTabComplete(admin, command, "spass", new String[]{"mode", "t"}));
        assertEquals(9, executor.onTabComplete(admin, command, "spass", new String[]{"length", ""}).size());
        assertEquals(List.of(), executor.onTabComplete(admin, command, "spass", new String[]{"set", ""}));
        var login = new ServerPassCommand(plugin, mock(Lang.class));
        assertEquals(List.of(), login.onTabComplete(admin, command, "serverpass", new String[]{""}));
        when(admin.hasPermission("serverpassword.admin")).thenReturn(false);
        assertEquals(List.of(), executor.onTabComplete(admin, command, "spass", new String[]{""}));
        run("mode", "TEXT");
        assertEquals("PIN", plugin.getConfig().getString("password-policy.mode"));
    }

    @Test void passwordCommandRejectsSecretArgumentsAndUsesTheMaskedSetupMenu() {
        run("set", "A", "text", "password!");
        verify(plugin, never()).changePassword(anyString(), any());
        verify(admin).sendMessage("secure-input-only");
        verify(admin, never()).sendMessage(contains("A text password!"));
        run("set");
        verify(plugin).openPasswordSetup(admin);
    }

    @Test void loginCommandOnlyOpensTheMenuAndNeverAcceptsSecretArguments() {
        var player = mock(org.bukkit.entity.Player.class);
        Lang messages = mock(Lang.class);
        when(messages.msg(any(), anyString())).thenAnswer(call -> call.getArgument(1));
        var login = new ServerPassCommand(plugin, messages);
        login.onCommand(player, command, "serverpass", new String[]{"secret-never-echo"});
        verify(plugin, never()).openLogin(any());
        verify(player).sendMessage("secure-input-only");
        verify(player, never()).sendMessage(contains("secret-never-echo"));
        login.onCommand(player, command, "serverpass", new String[]{});
        verify(plugin).openLogin(player);
    }

    @Test void protectionRepairIsExplicitPermissionCheckedAndOffersOnlineTargets() {
        var player = mock(org.bukkit.entity.Player.class);
        when(player.getName()).thenReturn("Owner");
        try (var bukkit = mockStatic(org.bukkit.Bukkit.class)) {
            bukkit.when(() -> org.bukkit.Bukkit.getPlayerExact("Owner")).thenReturn(player);
            bukkit.when(org.bukkit.Bukkit::getOnlinePlayers).thenReturn(List.of(player));
            assertEquals(List.of("Owner"), executor.onTabComplete(admin, command, "spass", new String[]{"repairprotection", "O"}));
            run("repairprotection"); verify(admin).sendMessage("admin-protection-usage");
            run("repairprotection", "Missing"); verify(admin).sendMessage("admin-player-offline");
            run("repairprotection", "Owner"); verify(admin).sendMessage("admin-player-locked");
            when(plugin.repairProtection(player)).thenReturn(true);
            run("repairprotection", "Owner"); verify(admin).sendMessage("admin-protection-repaired");
            when(admin.hasPermission("serverpassword.admin")).thenReturn(false);
            run("repairprotection", "Owner");
            verify(plugin, times(2)).repairProtection(player);
            verify(admin).sendMessage("admin-no-permission");
        }
    }
}
