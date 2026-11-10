package es.mrdino.serverpassword;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PasswordSetupUITest {
    private ServerPassPlugin plugin;
    private Player player;
    private Inventory inventory;
    private InventoryView view;
    private PasswordSetupUI ui;
    private MockedStatic<PlatformCompat> platform;
    private MockedConstruction<ItemStack> items;
    private MockedStatic<SkullUtil> skulls;
    private org.bukkit.boss.BossBar bar;
    private final Queue<Runnable> tasks = new ArrayDeque<>();
    private final List<String> visible = new ArrayList<>();

    @BeforeEach void setup() {
        plugin = mock(ServerPassPlugin.class);
        when(plugin.getConfig()).thenReturn(PasswordSecurityTest.defaults());
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        bar = mock(org.bukkit.boss.BossBar.class);
        when(server.createBossBar(anyString(), any(), any(), any(org.bukkit.boss.BarFlag[].class))).thenReturn(bar);
        when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), anyLong(), anyLong())).thenReturn(mock(BukkitTask.class));
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(c -> { tasks.add(c.getArgument(1)); return mock(BukkitTask.class); });
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);
        when(plugin.canSetPassword(player)).thenReturn(true);
        inventory = mock(Inventory.class);
        view = mock(InventoryView.class);
        when(view.getTopInventory()).thenReturn(inventory);
        when(player.getOpenInventory()).thenReturn(view);
        Lang lang = mock(Lang.class);
        when(lang.tr(any(org.bukkit.command.CommandSender.class), anyString())).thenAnswer(c -> c.getArgument(1));
        when(lang.msg(any(), anyString())).thenAnswer(c -> c.getArgument(1));
        platform = mockStatic(PlatformCompat.class);
        platform.when(() -> PlatformCompat.createInventory(eq(player), eq(54), anyString())).thenReturn(inventory);
        platform.when(() -> PlatformCompat.setDisplayName(any(), anyString())).thenAnswer(c -> { visible.add(c.getArgument(1)); return null; });
        items = mockConstruction(ItemStack.class, (m, c) -> {
            when(m.getItemMeta()).thenReturn(mock(ItemMeta.class));
            when(m.clone()).thenReturn(m);
            when(m.getType()).thenReturn((org.bukkit.Material) c.arguments().get(0));
        });
        skulls = mockStatic(SkullUtil.class);
        skulls.when(() -> SkullUtil.headFromTexture(anyString())).thenAnswer(c -> new ItemStack(org.bukkit.Material.PLAYER_HEAD));
        ui = new PasswordSetupUI(plugin, lang);
        ui.open(player);
    }

    @AfterEach void cleanup() { ui.shutdown(); skulls.close(); items.close(); platform.close(); }

    private void click(int slot) {
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getView()).thenReturn(view);
        when(event.getClick()).thenReturn(ClickType.LEFT);
        when(event.getRawSlot()).thenReturn(slot);
        ui.onClick(event);
        while (!tasks.isEmpty()) tasks.remove().run();
    }

    private void pin(String value) {
        int[] slots = {40, 12, 13, 14, 21, 22, 23, 30, 31, 32};
        for (char character : value.toCharArray()) click(slots[character - '0']);
    }

    private Object session() throws Exception {
        var field = PasswordSetupUI.class.getDeclaredField("sessions"); field.setAccessible(true);
        return ((Map<?, ?>) field.get(ui)).get(player.getUniqueId());
    }

    private char[] buffer(Object session, String name) throws Exception {
        var field = session.getClass().getDeclaredField(name); field.setAccessible(true); return (char[]) field.get(session);
    }

    @Test void creatingAPasswordRequiresMatchingConfirmationAndNeverDisplaysTheSecret() throws Exception {
        Object session = session();
        pin("0012"); click(53);
        verify(plugin, never()).changePassword(anyString(), any());
        pin("0012"); click(53);
        verify(plugin).changePassword(eq("0012"), any());
        assertTrue(visible.stream().noneMatch(text -> text.contains("0012")));
        assertTrue(visible.stream().anyMatch(text -> text.contains("* * * *")));
        verify(bar, atLeastOnce()).setColor(org.bukkit.boss.BarColor.YELLOW);
        verify(bar).removeAll();
        assertTrue(Arrays.equals(new char[128], buffer(session, "input")));
        assertTrue(Arrays.equals(new char[128], buffer(session, "first")));
        assertNull(session());
    }

    @Test void pinSetupHasVisibleDigitHeadsInAKeypadAndNoMeaninglessPageArrows() {
        int[] slots = {40, 12, 13, 14, 21, 22, 23, 30, 31, 32};
        for (int digit = 0; digit <= 9; digit++) {
            String texture = plugin.getConfig().getString("code-gui.textures." + digit);
            skulls.verify(() -> SkullUtil.headFromTexture(texture));
            verify(inventory).setItem(eq(slots[digit]), argThat(item -> item.getType() == org.bukkit.Material.PLAYER_HEAD));
        }
        for (int slot : new int[]{47, 48})
            verify(inventory, never()).setItem(eq(slot), argThat(item -> item.getType() == org.bukkit.Material.ARROW));
        platform.verify(() -> PlatformCompat.createInventory(player, 54, "secure-setup-menu"));
    }

    @Test void pinHeaderAndDecorationsCannotAccidentallyEnterADigit() {
        click(4); click(0); click(47); click(48);
        pin("0012"); click(53); pin("0012"); click(53);
        verify(plugin).changePassword(eq("0012"), any());
    }

    @Test void bundledSetupTitlesFitTheVanillaInventoryHeader() throws Exception {
        for (String language : List.of("en", "es", "de")) {
            var messages = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                    new java.io.InputStreamReader(getClass().getResourceAsStream("/lang/" + language + ".yml"),
                            java.nio.charset.StandardCharsets.UTF_8));
            assertTrue(messages.getString("secure-setup-menu").replaceAll("&.", "").length() <= 24, language);
        }
    }

    @Test void mismatchingConfirmationDoesNotChangeThePassword() throws Exception {
        pin("0012"); click(53); pin("9999"); click(53);
        verify(plugin, never()).changePassword(anyString(), any());
        verify(player).sendMessage("secure-setup-mismatch");
        assertTrue(Arrays.equals(new char[128], buffer(session(), "input")));
        assertTrue(Arrays.equals(new char[128], buffer(session(), "first")));
    }

    @Test void closingTheMenuErasesBothBuffersAndPreventsSubsequentSubmission() throws Exception {
        Object session = session();
        pin("0012"); click(53); pin("0012");
        InventoryCloseEvent close = mock(InventoryCloseEvent.class);
        when(close.getPlayer()).thenReturn(player); when(close.getInventory()).thenReturn(inventory);
        ui.onClose(close);
        click(53);
        verify(plugin, never()).changePassword(anyString(), any());
        assertTrue(Arrays.equals(new char[128], buffer(session, "first")));
        assertTrue(Arrays.equals(new char[128], buffer(session, "input")));
    }

    @Test void permissionOrAuthenticationRevocationCancelsASetupAlreadyInProgress() throws Exception {
        pin("0012"); click(53); pin("0012");
        when(plugin.canSetPassword(player)).thenReturn(false);
        click(53);
        verify(plugin, never()).changePassword(anyString(), any());
        assertNull(session());
    }
}
