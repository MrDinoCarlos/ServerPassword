package es.mrdino.serverpassword;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

/** Password creation and confirmation without secret command arguments or editable fields. */
final class PasswordSetupUI implements Listener {
    private final ServerPassPlugin plugin;
    private final Lang lang;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<String, ItemStack> headTemplates = new HashMap<>();
    private final BukkitTask expiry;

    PasswordSetupUI(ServerPassPlugin plugin, Lang lang) {
        this.plugin = plugin;
        this.lang = lang;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        expiry = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            for (Session session : java.util.List.copyOf(sessions.values()))
                if (!allowed(session) || System.currentTimeMillis() >= session.expires) close(session);
        }, 20L, 20L);
    }

    void open(Player player) {
        if (!plugin.canSetPassword(player)) return;
        Session previous = sessions.get(player.getUniqueId());
        if (previous != null) close(previous);
        boolean pin = LoginSettings.pin(plugin.getConfig());
        String charset = pin ? PasswordCredential.DIGITS
                : plugin.getConfig().getString("modern-gui.virtual-keyboard.charset", PasswordCredential.LEGACY_ALPHABET);
        int maximum = pin ? plugin.getConfig().getInt("password-policy.pin-length", 4)
                : plugin.getConfig().getInt("modern-gui.max-length", 32);
        Session session = new Session(player, charset, maximum, pin,
                PlatformCompat.createInventory(player, 54, lang.tr(player, "secure-setup-menu")));
        session.bar = plugin.getServer().createBossBar("", BarColor.BLUE, BarStyle.SOLID);
        if (session.bar != null) session.bar.addPlayer(player);
        sessions.put(player.getUniqueId(), session);
        render(session);
        player.openInventory(session.inventory);
    }

    private boolean allowed(Session session) {
        return sessions.get(session.player.getUniqueId()) == session && session.player.isOnline()
                && System.currentTimeMillis() < session.expires && plugin.canSetPassword(session.player);
    }

    private ItemStack item(Material material, String name) {
        ItemStack item = new ItemStack(material);
        var meta = item.getItemMeta();
        PlatformCompat.setDisplayName(meta, name);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack icon(String path, Material fallback, String name) {
        String texture = plugin.getConfig().getString("code-gui." + path, "");
        if (texture.isBlank()) return item(fallback, name);
        ItemStack icon = headTemplates.computeIfAbsent(texture, SkullUtil::headFromTexture).clone();
        var meta = icon.getItemMeta();
        PlatformCompat.setDisplayName(meta, name);
        icon.setItemMeta(meta);
        return icon;
    }

    private String maskedCode(Session session) {
        if (!session.pin) return "*".repeat(Math.min(16, session.length)) + " (" + session.length + "/" + session.maximum + ")";
        StringBuilder slots = new StringBuilder("[ ");
        for (int index = 0; index < session.maximum; index++) slots.append(index < session.length ? "* " : "_ ");
        return slots.append(']').toString();
    }

    private void render(Session session) {
        for (int slot = 0; slot < 54; slot++)
            session.inventory.setItem(slot, item(Material.BLACK_STAINED_GLASS_PANE, " "));
        session.keys.clear();
        if (session.pin) {
            int[] digitSlots = {40, 12, 13, 14, 21, 22, 23, 30, 31, 32};
            for (int digit = 0; digit <= 9; digit++) {
                int slot = digitSlots[digit];
                session.inventory.setItem(slot, icon("textures." + digit, Material.LIGHT_BLUE_DYE, "§b§l" + digit));
                session.keys.put(slot, (char) ('0' + digit));
            }
        } else {
            for (int offset = 0; offset < 36 && session.page * 36 + offset < session.charset.length(); offset++) {
                char character = session.charset.charAt(session.page * 36 + offset);
                session.inventory.setItem(offset, icon("textures." + Character.toUpperCase(character), Material.PAPER,
                        character == ' ' ? lang.tr(session.player, "screen-space") : "§b" + character));
                session.keys.put(offset, character);
            }
            if (session.page > 0)
                session.inventory.setItem(47, icon("controls.down", Material.ARROW, lang.tr(session.player, "screen-previous")));
            if ((session.page + 1) * 36 < session.charset.length())
                session.inventory.setItem(48, icon("controls.up", Material.ARROW, lang.tr(session.player, "screen-next")));
        }
        session.inventory.setItem(45, icon("controls.clear", Material.BARRIER, lang.tr(session.player, "gui-clear")));
        session.inventory.setItem(46, icon("controls.down", Material.SHEARS, lang.tr(session.player, "screen-backspace")));
        String stage = lang.tr(session.player, session.confirming ? "secure-setup-repeat" : "secure-setup-enter");
        session.inventory.setItem(session.pin ? 4 : 49, item(Material.NAME_TAG, stage + " §f" + maskedCode(session)));
        session.inventory.setItem(52, item(Material.OAK_DOOR, lang.tr(session.player, "secure-setup-cancel")));
        session.inventory.setItem(53, icon("controls.submit", Material.LIME_WOOL, lang.tr(session.player, "gui-submit")));
        if (session.bar != null) {
            session.bar.setTitle(stage + " §f" + maskedCode(session));
            session.bar.setColor(session.confirming ? BarColor.YELLOW : BarColor.BLUE);
            session.bar.setProgress(session.length / (double) session.maximum);
        }
    }

    @EventHandler public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Session session = sessions.get(player.getUniqueId());
        if (session == null) return;
        event.setCancelled(true);
        if (event.getView().getTopInventory() != session.inventory || event.isShiftClick()
                || event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT) return;
        int slot = event.getRawSlot();
        long revision = session.revision;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (allowed(session) && session.revision == revision) click(session, slot);
            else if (!allowed(session)) close(session);
        });
    }

    private void click(Session session, int slot) {
        session.revision++;
        Character character = session.keys.get(slot);
        if (character != null) {
            if (session.length < session.maximum) session.input[session.length++] = character;
        } else switch (slot) {
            case 45 -> { Arrays.fill(session.input, '\0'); session.length = 0; }
            case 46 -> { if (session.length > 0) session.input[--session.length] = '\0'; }
            case 47 -> { if (!session.pin) session.page = Math.max(0, session.page - 1); }
            case 48 -> { if (!session.pin) session.page = Math.min((session.charset.length() - 1) / 36, session.page + 1); }
            case 52 -> { close(session); return; }
            case 53 -> { submit(session); return; }
            default -> { }
        }
        render(session);
    }

    private void submit(Session session) {
        if (session.length == 0 || session.pin && session.length != session.maximum) {
            session.player.sendMessage(lang.msg(session.player, "secure-setup-invalid"));
            return;
        }
        if (!session.confirming) {
            System.arraycopy(session.input, 0, session.first, 0, session.length);
            session.firstLength = session.length;
            Arrays.fill(session.input, '\0');
            session.length = 0;
            session.confirming = true;
            session.page = 0;
            render(session);
            return;
        }
        int difference = session.firstLength ^ session.length;
        for (int index = 0; index < session.input.length; index++) difference |= session.first[index] ^ session.input[index];
        if (difference != 0) {
            Arrays.fill(session.input, '\0');
            Arrays.fill(session.first, '\0');
            session.length = 0;
            session.firstLength = 0;
            session.confirming = false;
            session.player.sendMessage(lang.msg(session.player, "secure-setup-mismatch"));
            render(session);
            return;
        }
        String password = new String(session.input, 0, session.length);
        close(session);
        plugin.changePassword(password, errors -> {
            session.player.sendMessage(lang.msg(session.player, errors.isEmpty() ? "admin-password-set" : "secure-setup-invalid"));
        });
    }

    @EventHandler public void onDrag(InventoryDragEvent event) {
        if (sessions.containsKey(event.getWhoClicked().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler public void onClose(InventoryCloseEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null && event.getInventory() == session.inventory) erase(session);
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null) erase(session);
    }

    private void erase(Session session) {
        if (!sessions.remove(session.player.getUniqueId(), session)) return;
        Arrays.fill(session.input, '\0');
        Arrays.fill(session.first, '\0');
        session.length = session.firstLength = 0;
        session.keys.clear();
        if (session.bar != null) session.bar.removeAll();
        session.inventory.clear();
    }

    private void close(Session session) {
        erase(session);
        if (session.player.isOnline() && session.player.getOpenInventory().getTopInventory() == session.inventory)
            session.player.closeInventory();
    }

    void closeAll() { for (Session session : java.util.List.copyOf(sessions.values())) close(session); }
    void shutdown() { expiry.cancel(); closeAll(); headTemplates.clear(); }

    private static final class Session {
        final Player player;
        final String charset;
        final int maximum;
        final boolean pin;
        final Inventory inventory;
        final Map<Integer, Character> keys = new HashMap<>();
        BossBar bar;
        final char[] input = new char[128], first = new char[128];
        final long expires = System.currentTimeMillis() + 120_000L;
        boolean confirming;
        int length, firstLength, page;
        long revision;

        Session(Player player, String charset, int maximum, boolean pin, Inventory inventory) {
            this.player = player; this.charset = charset; this.maximum = maximum;
            this.pin = pin; this.inventory = inventory;
        }
    }
}
