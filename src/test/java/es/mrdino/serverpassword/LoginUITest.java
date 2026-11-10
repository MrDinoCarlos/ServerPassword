package es.mrdino.serverpassword;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LoginUITest {
    private JavaPlugin plugin;
    private AuthManager auth;
    private LockdownManager lock;
    private Lang lang;
    private Player player;
    private Inventory selector;
    private Logger logger;
    private LoginUI ui;
    private MockedStatic<Bukkit> bukkit;
    private MockedStatic<PlatformCompat> platform;
    private MockedStatic<SkullUtil> skulls;
    private MockedConstruction<ItemStack> items;
    private final Queue<Runnable> tasks = new ArrayDeque<>();
    private final Screen screen = new Screen();

    @BeforeEach void setup() {
        plugin = mock(JavaPlugin.class);
        var config = PasswordSecurityTest.defaults();
        config.set("modern-gui.countdown.bossbar", false);
        when(plugin.getConfig()).thenReturn(config);
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
        logger = mock(Logger.class);
        when(plugin.getLogger()).thenReturn(logger);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), anyLong(), anyLong())).thenReturn(mock(BukkitTask.class));
        when(scheduler.runTaskLater(eq(plugin), any(Runnable.class), anyLong())).thenAnswer(call -> {
            tasks.add(call.getArgument(1));
            return mock(BukkitTask.class);
        });
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
            tasks.add(call.getArgument(1));
            return mock(BukkitTask.class);
        });
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        platform = mockStatic(PlatformCompat.class);
        selector = mock(Inventory.class);
        when(selector.getSize()).thenReturn(54);
        platform.when(() -> PlatformCompat.createInventory(any(), eq(54), anyString())).thenReturn(selector);
        items = mockConstruction(ItemStack.class, (item, context) -> {
            when(item.getItemMeta()).thenReturn(mock(ItemMeta.class));
            when(item.clone()).thenReturn(item);
        });
        skulls = mockStatic(SkullUtil.class);
        skulls.when(() -> SkullUtil.headFromTexture(anyString())).thenAnswer(call -> new ItemStack(Material.PLAYER_HEAD));
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);
        InventoryView current = mock(InventoryView.class);
        when(current.getTopInventory()).thenReturn(mock(Inventory.class));
        when(player.getOpenInventory()).thenReturn(current);
        auth = mock(AuthManager.class);
        when(auth.passwordLength()).thenReturn(4);
        when(auth.passwordIsNumeric()).thenReturn(true);
        lock = mock(LockdownManager.class);
        when(lock.isLocked(player)).thenReturn(true);
        when(lock.getLockedPlayersOnline()).thenReturn(List.of(player));
        lang = mock(Lang.class);
        when(lang.msg(any(CommandSender.class), anyString())).thenAnswer(call -> call.getArgument(1));
        when(lang.msg(any(CommandSender.class), anyString(), anyMap())).thenAnswer(call -> call.getArgument(1));
        when(lang.tr(any(CommandSender.class), anyString())).thenAnswer(call -> call.getArgument(1));
        when(lang.tr(any(CommandSender.class), anyString(), anyMap())).thenAnswer(call -> call.getArgument(1));
    }

    @AfterEach void cleanup() {
        if (ui != null) ui.shutdown();
        skulls.close(); items.close(); platform.close(); bukkit.close();
    }

    private void drain() { while (!tasks.isEmpty()) tasks.remove().run(); }

    private void click(int slot) throws Exception {
        var field = LoginUI.class.getDeclaredField("modern");
        field.setAccessible(true);
        ModernPasswordUI modern = (ModernPasswordUI) field.get(ui);
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        InventoryView view = mock(InventoryView.class);
        when(view.getTopInventory()).thenReturn(selector);
        when(event.getView()).thenReturn(view);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getClick()).thenReturn(ClickType.LEFT);
        when(event.getRawSlot()).thenReturn(slot);
        modern.onClick(event);
        drain();
    }

    @Test void unsupportedNumericClientsUseAMaskedKeypadWithLeadingZeroes() throws Exception {
        screen.supported = false;
        plugin.getConfig().set("modern-gui.input", "TEXT");
        plugin.getConfig().set("code-gui.charset", "ABC");
        ui = new LoginUI(plugin, auth, lock, lang, screen);
        ui.open(player);
        drain();
        assertNull(screen.view);
        verify(player).openInventory(selector);
        for (int slot : new int[]{12, 13, 14, 21, 22, 23, 30, 31, 32, 40})
            verify(selector, atLeastOnce()).setItem(eq(slot), any(ItemStack.class));
        platform.verify(() -> PlatformCompat.setDisplayName(any(), eq("§b§l0")), never());
        click(40); click(40); click(12); click(13);
        click(53);
        verify(auth).tryPasswordAsync(eq(player), eq("0012"), any());
    }

    @Test void serversWithoutDialogApiUseAMaskedTextKeyboardAndNeverAcceptChatPasswords() {
        when(auth.passwordIsNumeric()).thenReturn(false);
        ui = new LoginUI(plugin, auth, lock, lang, null);
        ui.open(player);
        drain();
        verify(player, never()).sendMessage("chat-login-prompt");
        verify(player).openInventory(selector);
        verify(player, never()).openAnvil(any(), anyBoolean());
        assertFalse(ui.handleChat(player, "Text password!"));
        drain();
        verify(auth, never()).tryPasswordAsync(any(), anyString(), any());
    }

    @Test void oldClientsOnModernServersAlsoUseTheMaskedInventoryForText() {
        when(auth.passwordIsNumeric()).thenReturn(false);
        screen.supported = false;
        ui = new LoginUI(plugin, auth, lock, lang, screen);
        ui.open(player);
        assertNull(screen.view);
        verify(player, never()).sendMessage("chat-login-prompt");
        verify(player).openInventory(selector);
        verify(logger, never()).warning(anyString());
    }

    @Test void compatibleClientsUseTheVirtualKeyboardWithoutAnExposedNativeTextField() {
        when(auth.passwordIsNumeric()).thenReturn(false);
        ui = new LoginUI(plugin, auth, lock, lang, screen);
        ui.open(player);
        assertFalse(screen.view.textEntry());
        assertEquals("", screen.view.initialText());
        assertTrue(screen.view.buttons().stream().anyMatch(button -> button.action().equals("KEYBOARD")));
        verify(player, never()).sendMessage("chat-login-prompt");
        assertFalse(ui.handleChat(player, "normal message"));
    }

    @Test void theCompatibilityWarningOnlyAppearsAtStartupAcrossJoinsAndReloads() {
        when(auth.passwordIsNumeric()).thenReturn(false);
        ui = new LoginUI(plugin, auth, lock, lang, null);
        ui.open(player);
        ui.cleanupSession(player);
        ui.open(player);
        ui.reload();
        ui.reload();
        verify(logger, times(1)).warning(contains("1.21.7"));
        verify(player, never()).sendMessage(contains("1.21.7"));
    }

    @Test void returningToModernModeCannotReopenAnOldSelector() {
        plugin.getConfig().set("input-mode", "LEGACY");
        ui = new LoginUI(plugin, auth, lock, lang, screen);
        ui.open(player);
        plugin.getConfig().set("input-mode", "MODERN");
        ui.reload();
        assertNotNull(screen.view);
        drain();
        verify(player, never()).openInventory(selector);
    }

    @Test void nativeRenderingFailureKeepsTextInputInAMaskedInventory() {
        screen.fail = true;
        when(auth.passwordIsNumeric()).thenReturn(false);
        ui = new LoginUI(plugin, auth, lock, lang, screen);
        ui.open(player);
        verify(player, never()).sendMessage("chat-login-prompt");
        verify(player).openInventory(selector);
        verify(player, never()).openAnvil(any(), anyBoolean());
    }

    private static final class Screen implements NativeLoginScreen {
        boolean supported = true;
        boolean fail;
        View view;
        @Override public boolean supports(Player player) { return supported; }
        @Override public void show(Player player, View view, BiConsumer<String, String> handler) {
            if (fail) throw new IllegalArgumentException("Test dialog failure");
            this.view = view;
        }
        @Override public void close(Player player) { }
    }
}
