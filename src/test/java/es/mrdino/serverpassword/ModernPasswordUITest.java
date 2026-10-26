package es.mrdino.serverpassword;

import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ModernPasswordUITest {
    private ModernPasswordUI ui;
    private AuthManager auth;
    private LockdownManager lock;
    private Player player;
    private JavaPlugin plugin;
    private Lang lang;
    private Runnable tick;
    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final Screen screen = new Screen();

    @BeforeEach void setup() {
        var config = PasswordSecurityTest.defaults();
        config.set("modern-gui.countdown.bossbar", false);
        config.set("modern-gui.feedback.sounds", false);
        config.set("modern-gui.countdown.sounds", false);
        plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), anyLong(), anyLong())).thenAnswer(call -> {
            tick = call.getArgument(1);
            return mock(BukkitTask.class);
        });
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);
        auth = mock(AuthManager.class);
        when(auth.passwordIsNumeric()).thenReturn(true);
        when(auth.passwordLength()).thenReturn(4);
        lock = mock(LockdownManager.class);
        when(lock.isLocked(player)).thenReturn(true);
        lang = mock(Lang.class);
        when(lang.tr(any(CommandSender.class), anyString(), anyMap())).thenAnswer(call -> {
            String key = call.getArgument(1);
            Map<String, String> vars = call.getArgument(2);
            return switch (key) {
                case "screen-code", "screen-compact-code" -> "code=" + vars.get("code");
                case "screen-timer", "screen-compact-meta" -> "seconds=" + vars.get("seconds").replaceAll("§.", "");
                default -> key;
            };
        });
    }

    private void open(boolean numeric) {
        when(auth.passwordIsNumeric()).thenReturn(numeric);
        ui = new ModernPasswordUI(plugin, auth, lock, lang, screen, clock::get);
        ui.open(player);
    }

    @AfterEach void cleanup() { if (ui != null) ui.shutdown(); }

    @Test void keypadLeadingZeroesAndRevealUseTheSameBuffer() {
        open(true);
        screen.click("DIGIT:0");
        screen.click("DIGIT:1");
        assertTrue(screen.view.lines().contains("code=**"));
        screen.click("OPTIONS");
        screen.click("REVEAL");
        assertTrue(screen.view.lines().contains("code=01"));
        screen.click("REVEAL");
        assertTrue(screen.view.lines().contains("code=**"));
    }

    @Test void textEditorKeyboardAndHelpPreserveCodeAndDeadline() {
        open(false);
        assertTrue(screen.view.textEntry());
        int beforeTick = screen.shown;
        clock.addAndGet(5_000);
        tick.run();
        assertEquals(beforeTick, screen.shown, "Tick must not replace an active text input");
        screen.handler.accept("KEYBOARD", "Ab9!");
        screen.click("SHIFT");
        screen.click("CHAR:10"); // A in the default virtual keyboard.
        screen.click("OPTIONS");
        screen.click("HELP");
        screen.click("BACK");
        screen.click("OPTIONS");
        screen.click("REVEAL");
        assertTrue(screen.view.lines().contains("code=Ab9!A"));
        assertTrue(screen.view.lines().contains("seconds=25"));
    }

    @Test void emptySubmissionDoesNotConsumeAnAttempt() {
        open(true);
        screen.click("SUBMIT");
        verify(auth, never()).tryPasswordAsync(any(), anyString(), any());
        assertTrue(screen.view.lines().contains("screen-empty"));
    }

    @Test void successWaitsForFeedbackBeforeUnlocking() {
        doAnswer(call -> {
            Consumer<AuthManager.AuthResult> callback = call.getArgument(2);
            callback.accept(AuthManager.AuthResult.SUCCESS);
            return null;
        }).when(auth).tryPasswordAsync(eq(player), anyString(), any());
        open(true);
        screen.click("DIGIT:1");
        screen.click("SUBMIT");
        verify(lock, never()).unlock(player);
        assertTrue(screen.view.lines().contains("screen-compact-success"));
        clock.addAndGet(500);
        tick.run();
        verify(lock).unlock(player);
    }

    @Test void staleScreenCannotAuthenticateAfterCleanup() {
        open(true);
        screen.click("DIGIT:1");
        BiConsumer<String, String> stale = screen.handler;
        ui.cleanupSession(player);
        stale.accept("SUBMIT", null);
        verify(auth, never()).tryPasswordAsync(any(), anyString(), any());
    }

    @Test void reopeningTheSameSessionDoesNotRestartTheCountdown() {
        open(true);
        screen.click("DIGIT:1");
        clock.addAndGet(10_000);
        ui.open(player);
        assertTrue(screen.view.lines().contains("code=*"));
        assertTrue(screen.view.lines().contains("seconds=20"));
    }

    @Test void keypadKeepsItsThreeByFourNumbersWithAnExtraControlRow() {
        open(true);
        assertEquals(3, screen.view.layout().columns());
        assertTrue(screen.view.layout().buttonWidth() <= 48);
        assertEquals(java.util.List.of("DIGIT:1", "DIGIT:2", "DIGIT:3", "DIGIT:4", "DIGIT:5", "DIGIT:6",
                "DIGIT:7", "DIGIT:8", "DIGIT:9", "BACKSPACE", "DIGIT:0", "OPTIONS", "CLEAR", "REVEAL", "EXIT"),
                screen.view.buttons().stream().map(NativeLoginScreen.Button::action).toList());
        assertEquals("SUBMIT", screen.view.primary().action());
        assertEquals(4, screen.view.lines().size());
    }

    @Test void liveCountdownDoesNotReplaceTheDialogOrResetItsControls() {
        open(true);
        int initiallyShown = screen.shown;
        for (int second = 0; second < 5; second++) {
            clock.addAndGet(1_000);
            tick.run();
        }
        assertEquals(initiallyShown, screen.shown);
        screen.click("DIGIT:0");
        assertTrue(screen.view.lines().contains("seconds=25"));
        screen.click("OPTIONS");
        initiallyShown = screen.shown;
        clock.addAndGet(1_000);
        tick.run();
        assertEquals(initiallyShown, screen.shown);
        screen.click("BACK");
        assertTrue(screen.view.lines().contains("seconds=24"));
    }

    @Test void virtualKeyboardUsesCompactQwertyRowsAndSwitchesToSymbols() {
        open(false);
        screen.click("KEYBOARD");
        assertEquals(10, screen.view.layout().columns());
        assertTrue(screen.view.layout().buttonWidth() <= 24);
        assertEquals(40, screen.view.buttons().size());
        assertEquals("qwertyuiop", screen.view.buttons().subList(0, 10).stream()
                .map(button -> button.label().replaceAll("§.", "")).collect(java.util.stream.Collectors.joining()));
        screen.click("SHIFT");
        assertEquals("QWERTYUIOP", screen.view.buttons().subList(0, 10).stream()
                .map(button -> button.label().replaceAll("§.", "")).collect(java.util.stream.Collectors.joining()));
        screen.click("SYMBOLS");
        assertEquals("0123456789", screen.view.buttons().subList(0, 10).stream()
                .map(button -> button.label().replaceAll("§.", "")).collect(java.util.stream.Collectors.joining()));
    }

    @Test void openingTheKeyboardFromTheEditorPreservesItsUnappliedText() {
        open(false);
        screen.handler.accept("KEYBOARD", "draft text");
        assertTrue(screen.view.textEntry());
        assertEquals(NativeLoginScreen.Layout.KEYBOARD, screen.view.layout());
        assertEquals("draft text", screen.view.initialText());
        screen.click("OPTIONS");
        screen.click("REVEAL");
        assertTrue(screen.view.lines().contains("code=draft text"));
        verify(auth, never()).tryPasswordAsync(any(), anyString(), any());
    }

    @Test void physicalTypingAndVirtualKeysEditTheSameFieldWithoutApplyingAnEditor() {
        open(false);
        assertTrue(screen.view.textEntry());
        assertFalse(screen.view.buttons().stream().anyMatch(button -> button.action().equals("TYPE")));
        screen.handler.accept("KEYBOARD", "draft");
        screen.handler.accept("CHAR:10", "draft typed");
        assertTrue(screen.view.textEntry());
        assertEquals("draft typedA", screen.view.initialText());
        screen.handler.accept("BACKSPACE", "draft typedA!");
        assertEquals("draft typedA", screen.view.initialText());
        screen.handler.accept("SUBMIT", "final typed!");
        verify(auth).tryPasswordAsync(eq(player), eq("final typed!"), any());
    }

    @Test void numericCredentialsOverrideTextAndNeverAllowMoreThanNineDigits() {
        plugin.getConfig().set("modern-gui.input", "TEXT");
        when(auth.passwordLength()).thenReturn(9);
        open(true);
        assertFalse(screen.view.textEntry());
        assertEquals(9, screen.view.maxLength());
        for (int digit = 0; digit < 10; digit++) screen.click("DIGIT:0");
        screen.click("SUBMIT");
        verify(auth).tryPasswordAsync(eq(player), eq("000000000"), any());
    }

    @Test void helpAndBackRemainNativeAndKeepTheTypedPasswordAndDeadline() {
        open(false);
        screen.handler.accept("OPTIONS", "unfinished draft");
        screen.click("HELP");
        assertFalse(screen.view.buttons().isEmpty(), "Paper's multi-action dialog requires an action");
        clock.addAndGet(5_000);
        screen.click("BACK");
        assertTrue(screen.view.textEntry());
        assertEquals("unfinished draft", screen.view.initialText());
        assertTrue(screen.view.lines().contains("seconds=25"));
        verify(player, never()).openInventory(any(org.bukkit.inventory.Inventory.class));
    }

    @Test void wrongCodeShakesInRedThenClearsAndTheKeypadStaysInPlace() {
        when(lang.tr(eq(player), eq("screen-compact-code"), anyMap())).thenAnswer(call -> ((Map<?, ?>) call.getArgument(2)).get("slots"));
        doAnswer(call -> { ((Consumer<AuthManager.AuthResult>) call.getArgument(2)).accept(AuthManager.AuthResult.WRONG); return null; })
                .when(auth).tryPasswordAsync(eq(player), anyString(), any());
        open(true);
        screen.click("DIGIT:1");
        screen.click("SUBMIT");
        String first = screen.view.lines().get(1);
        var buttons = screen.view.buttons();
        assertTrue(first.contains("§c") && first.contains("■"));
        clock.addAndGet(100);
        tick.run();
        assertNotEquals(first, screen.view.lines().get(1));
        assertEquals(buttons, screen.view.buttons());
        assertEquals("1", screen.view.initialText());
        clock.addAndGet(400);
        tick.run();
        assertEquals("", screen.view.initialText());
        assertFalse(screen.view.lines().get(1).contains("■"));
        verify(lock, never()).unlock(player);
    }

    @Test void countdownUsesOrangeThenRedAndDoesNotReplaceAnUnsentTextField() {
        var bar = mock(org.bukkit.boss.BossBar.class);
        plugin.getConfig().set("modern-gui.countdown.bossbar", true);
        when(plugin.getServer().createBossBar(anyString(), any(), any(), any(org.bukkit.boss.BarFlag[].class))).thenReturn(bar);
        open(false);
        int before = screen.shown;
        clock.addAndGet(20_000);
        tick.run();
        verify(bar).setColor(org.bukkit.boss.BarColor.YELLOW);
        verify(bar).setTitle(contains("seconds=10"));
        clock.addAndGet(5_000);
        tick.run();
        verify(bar).setColor(org.bukkit.boss.BarColor.RED);
        assertEquals(before, screen.shown);
        screen.handler.accept("KEYBOARD", "never lost");
        assertEquals("never lost", screen.view.initialText());
        assertTrue(screen.view.title().startsWith("§c"));
    }

    @Test void countdownSoundsAccelerateWithoutBurstingOnLateTicks() {
        plugin.getConfig().set("modern-gui.countdown.sounds", true);
        open(true);
        clock.addAndGet(20_000);
        tick.run();
        clock.addAndGet(900);
        tick.run();
        verify(player, times(1)).playSound(nullable(org.bukkit.Location.class), eq(org.bukkit.Sound.BLOCK_NOTE_BLOCK_HAT), anyFloat(), eq(1.0F));
        clock.addAndGet(100);
        tick.run();
        verify(player, times(2)).playSound(nullable(org.bukkit.Location.class), eq(org.bukkit.Sound.BLOCK_NOTE_BLOCK_HAT), anyFloat(), eq(1.0F));
        clock.addAndGet(4_000);
        tick.run();
        clock.addAndGet(500);
        tick.run();
        verify(player, times(2)).playSound(nullable(org.bukkit.Location.class), eq(org.bukkit.Sound.BLOCK_NOTE_BLOCK_HAT), anyFloat(), eq(1.4F));
        clock.addAndGet(1_500);
        tick.run();
        clock.addAndGet(300);
        tick.run();
        verify(player, times(2)).playSound(nullable(org.bukkit.Location.class), eq(org.bukkit.Sound.BLOCK_NOTE_BLOCK_HAT), anyFloat(), eq(1.8F));
        plugin.getConfig().set("timeout.enabled", false);
        clock.addAndGet(1_000);
        tick.run();
        verify(player, times(6)).playSound(nullable(org.bukkit.Location.class), eq(org.bukkit.Sound.BLOCK_NOTE_BLOCK_HAT), anyFloat(), anyFloat());
    }

    @Test void everyScreenHasADirectDisconnectAction() {
        open(true);
        assertTrue(screen.view.buttons().stream().anyMatch(button -> button.action().equals("EXIT")));
        screen.click("OPTIONS");
        screen.click("HELP");
        assertTrue(screen.view.buttons().stream().anyMatch(button -> button.action().equals("EXIT")));
        ui.shutdown();
        open(false);
        assertTrue(screen.view.buttons().stream().anyMatch(button -> button.action().equals("EXIT")));
        screen.click("KEYBOARD");
        assertTrue(screen.view.buttons().stream().anyMatch(button -> button.action().equals("EXIT")));
    }

    @Test void translatedScreensFitTheSmallestVanillaGui() throws Exception {
        for (String locale : List.of("en", "es", "de")) {
            var translations = YamlConfiguration.loadConfiguration(new InputStreamReader(
                    getClass().getResourceAsStream("/lang/" + locale + ".yml"), StandardCharsets.UTF_8));
            when(lang.tr(any(CommandSender.class), anyString(), anyMap())).thenAnswer(call -> {
                String text = Lang.color(translations.getString(call.getArgument(1), call.getArgument(1)));
                Map<String, String> values = call.getArgument(2);
                for (var value : values.entrySet()) text = text.replace("{" + value.getKey() + "}", value.getValue());
                return text;
            });
            if (ui != null) ui.shutdown();
            open(true);
            screen.click("DIGIT:0");
            screen.click("DIGIT:4");
            checkAndExport(locale, "pin");
            screen.click("OPTIONS");
            checkAndExport(locale, "options");
            screen.click("HELP");
            checkAndExport(locale, "help");
            screen.click("BACK");
            checkAndExport(locale, "pin-back");
            when(auth.passwordLength()).thenReturn(9);
            ui.shutdown();
            open(true);
            checkAndExport(locale, "pin-nine");
            when(auth.passwordLength()).thenReturn(4);
            ui.shutdown();
            open(false);
            checkAndExport(locale, "password");
            screen.handler.accept("KEYBOARD", "Example");
            checkAndExport(locale, "keyboard");
            screen.click("SYMBOLS");
            checkAndExport(locale, "symbols");
            ui.shutdown();
            open(true);
            doAnswer(call -> { ((Consumer<AuthManager.AuthResult>) call.getArgument(2)).accept(AuthManager.AuthResult.WRONG); return null; })
                    .when(auth).tryPasswordAsync(eq(player), anyString(), any());
            screen.click("DIGIT:1");
            screen.click("SUBMIT");
            checkAndExport(locale, "wrong");
            clock.addAndGet(100);
            tick.run();
            checkAndExport(locale, "wrong-shake");
            clock.addAndGet(400);
            tick.run();
            doAnswer(call -> { ((Consumer<AuthManager.AuthResult>) call.getArgument(2)).accept(AuthManager.AuthResult.SUCCESS); return null; })
                    .when(auth).tryPasswordAsync(eq(player), anyString(), any());
            screen.click("DIGIT:1");
            screen.click("SUBMIT");
            checkAndExport(locale, "success");
        }
    }

    private void checkAndExport(String locale, String name) throws Exception {
        NativeLoginScreen.View view = screen.view;
        int columns = view.layout().columns();
        int rows = (view.buttons().size() + columns - 1) / columns;
        int gridHeight = rows == 0 ? 0 : rows * 20 + (rows - 1) * 2;
        int textHeight = 0;
        for (String line : view.lines()) {
            for (String part : line.split("\n", -1)) {
            // Seven pixels per character safely bounds the vanilla Latin font, including bold.
            int length = part.replaceAll("§.", "").length();
            textHeight += Math.max(1, (length * 7 + view.layout().bodyWidth() - 1) / view.layout().bodyWidth()) * 9;
            }
        }
        int contentHeight = textHeight + (rows == 0 ? 0 : 10 + gridHeight) + (view.textEntry() ? 40 : 0);
        assertTrue(contentHeight <= 240 - 66, locale + "/" + name + " would scroll at the minimum GUI height: " + contentHeight);
        assertTrue(columns * view.layout().buttonWidth() + (columns - 1) * 2 <= 320 - 20);
        for (var button : view.buttons()) {
            int labelWidth = button.label().replaceAll("§.", "").chars().map(character -> character == '.' ? 2 : character == ' ' ? 4 : 6).sum();
            assertTrue(labelWidth + 8 <= view.layout().buttonWidth(),
                    locale + "/" + name + " button could clip: " + button.label());
        }
        Path preview = Path.of("build/reports/login-ui", locale + "-" + name + ".json");
        Files.createDirectories(preview.getParent());
        Files.writeString(preview, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(view));
    }

    private static final class Screen implements NativeLoginScreen {
        View view;
        BiConsumer<String, String> handler;
        int shown;
        @Override public boolean supports(Player player) { return true; }
        @Override public void show(Player player, View view, BiConsumer<String, String> handler) {
            this.view = view;
            this.handler = handler;
            shown++;
        }
        @Override public void close(Player player) { }
        void click(String action) {
            assertTrue(view.buttons().stream().anyMatch(button -> button.action().equals(action))
                    || view.primary() != null && view.primary().action().equals(action), "Action is not visible: " + action);
            handler.accept(action, null);
        }
    }
}
