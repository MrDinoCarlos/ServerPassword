package es.mrdino.serverpassword;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public class CodePasswordUI implements Listener {

    private final JavaPlugin plugin;
    private final AuthManager auth;
    private final LockdownManager lock;
    private final Lang lang;

    private final Map<UUID, Inventory> openInv = new HashMap<>();
    private final Map<UUID, int[]> indicesByPlayer = new HashMap<>();

    private final Map<Character, String> charTextures = new HashMap<>();
    private final Map<String, String> controlTextures = new HashMap<>();

    // Evitar reabrir cuando cerramos nosotros
    private final Set<UUID> internalClose = new HashSet<>();

    // Timeout
    private final Map<UUID, Integer> timeoutTask = new HashMap<>();
    private final Map<UUID, Integer> timeLeft = new HashMap<>();

    public CodePasswordUI(JavaPlugin plugin, AuthManager auth, LockdownManager lock, Lang lang) {
        this.plugin = plugin;
        this.auth = auth;
        this.lock = lock;
        this.lang = lang;
        reloadTextures();
    }

    public void reloadTextures() {
        charTextures.clear();
        controlTextures.clear();

        ConfigurationSection chars = plugin.getConfig().getConfigurationSection("code-gui.textures");
        if (chars != null) {
            for (String key : chars.getKeys(false)) {
                if (key.length() != 1) continue;
                charTextures.put(key.charAt(0), chars.getString(key, ""));
            }
        }

        // Controls planos (up/down/submit/clear/filler/slash)
        ConfigurationSection controls = plugin.getConfig().getConfigurationSection("code-gui.controls");
        if (controls != null) {
            for (String k : controls.getKeys(false)) {
                if (controls.isConfigurationSection(k)) continue; // tries/max/timer etc.
                controlTextures.put(k.toLowerCase(Locale.ROOT), controls.getString(k, ""));
            }
        }
    }

    private String charset() {
        return plugin.getConfig().getString("code-gui.charset", "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ");
    }

    private String guiTitle() {
        String t = plugin.getConfig().getString("code-gui.title", "&6&lServer Password");
        return ChatColor.translateAlternateColorCodes('&', t);
    }

    private int codeLength() {
        String pass = auth.getServerPassword();
        int len = pass.length();
        if (len < 1) len = 1;

        int max = plugin.getConfig().getInt("code-gui.max-length", 9);
        if (max < 1) max = 1;
        if (max > 9) max = 9;

        return Math.min(len, max);
    }

    private int startCol(int len) {
        return (9 - len) / 2;
    }

    // -------------------------
    // Timeout config
    // -------------------------

    private boolean timeoutEnabled() {
        return plugin.getConfig().getBoolean("timeout.enabled", true);
    }

    private int timeoutSeconds() {
        int s = plugin.getConfig().getInt("timeout.seconds", 30);
        return Math.max(1, s);
    }

    private int displayFromSeconds() {
        int s = plugin.getConfig().getInt("timeout.display-from", 25);
        if (s < 0) s = 0;
        if (s > 99) s = 99;
        return s;
    }

    private String timeoutExpireAction() {
        return plugin.getConfig().getString("timeout.on-expire", "KICK");
    }

    public void open(Player p) {
        Inventory top = p.getOpenInventory().getTopInventory();
        Inventory ours = openInv.get(p.getUniqueId());
        if (ours != null && top == ours) return;

        int len = codeLength();
        String set = charset();
        if (set.isEmpty()) set = "0123456789";

        int[] idx = indicesByPlayer.get(p.getUniqueId());
        if (idx == null || idx.length != len) idx = new int[len];
        Arrays.fill(idx, 0);
        indicesByPlayer.put(p.getUniqueId(), idx);

        Inventory inv = Bukkit.createInventory(p, 54, guiTitle());
        fillBackground(inv);

        int sc = startCol(len);

        for (int i = 0; i < len; i++) {
            inv.setItem(9 + sc + i, controlItem("up", Material.ARROW, ChatColor.GREEN + "▲"));
            inv.setItem(27 + sc + i, controlItem("down", Material.ARROW, ChatColor.RED + "▼"));
            inv.setItem(18 + sc + i, charHead(set.charAt(idx[i] % set.length())));
        }

        // ✅ SOLO CAMBIO: textos traducibles
        inv.setItem(45, controlItem("clear", Material.BARRIER, lang.tr(p, "gui-clear")));
        inv.setItem(53, controlItem("submit", Material.LIME_WOOL, lang.tr(p, "gui-submit")));

        // Intentos como HEADS
        inv.setItem(48, triesUsedHead(p));
        inv.setItem(49, triesSlashHead());
        inv.setItem(50, triesMaxHead());

        openInv.put(p.getUniqueId(), inv);

        // Iniciar timeout + pintar contador en esquina superior izquierda (slot 0)
        startTimeout(p);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline() || !lock.isLocked(p) || auth.isAuthed(p)) return;
            p.openInventory(inv);
        }, 1L);
    }

    /** Cierra sin que se reabra */
    public void forceClose(Player p) {
        stopTimeout(p);

        UUID id = p.getUniqueId();
        Inventory ours = openInv.remove(id);
        indicesByPlayer.remove(id);

        if (ours != null) {
            internalClose.add(id);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (p.isOnline()) p.closeInventory();
                Bukkit.getScheduler().runTaskLater(plugin, () -> internalClose.remove(id), 2L);
            });
        }
    }

    private void fillBackground(Inventory inv) {
        String tex = controlTextures.getOrDefault("filler", "");
        if (tex != null && !tex.isBlank()) {
            ItemStack head = SkullUtil.headFromTexture(tex);
            ItemMeta m = head.getItemMeta();
            m.setDisplayName(" ");
            head.setItemMeta(m);
            for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, head);
            return;
        }

        ItemStack glass = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta m = glass.getItemMeta();
        m.setDisplayName(" ");
        glass.setItemMeta(m);
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, glass);
    }

    private ItemStack controlItem(String key, Material fallbackMat, String fallbackName) {
        String tex = controlTextures.getOrDefault(key, "");
        ItemStack it = (tex != null && !tex.isBlank())
                ? SkullUtil.headFromTexture(tex)
                : new ItemStack(fallbackMat);

        ItemMeta m = it.getItemMeta();
        m.setDisplayName(fallbackName);
        it.setItemMeta(m);
        return it;
    }

    private ItemStack charHead(char c) {
        String tex = charTextures.getOrDefault(c, "");
        ItemStack head = (tex != null && !tex.isBlank())
                ? SkullUtil.headFromTexture(tex)
                : new ItemStack(Material.PLAYER_HEAD);

        ItemMeta m = head.getItemMeta();
        m.setDisplayName(ChatColor.AQUA + "" + ChatColor.BOLD + c);
        head.setItemMeta(m);
        return head;
    }

    // -------------------------
    // Intentos: HEADS dinámicas
    // -------------------------

    private ItemStack triesUsedHead(Player p) {
        int used = auth.getAttemptsThisJoin(p);
        return numberedHead("code-gui.controls.tries", used, "§c§l" + used);
    }

    private ItemStack triesMaxHead() {
        int max = plugin.getConfig().getInt("max-attempts-per-join", 3);
        return numberedHead("code-gui.controls.max", max, "§c§l" + max);
    }

    private ItemStack triesSlashHead() {
        String tex = plugin.getConfig().getString("code-gui.controls.slash", "");
        ItemStack head = (tex != null && !tex.isBlank())
                ? SkullUtil.headFromTexture(tex)
                : new ItemStack(Material.PLAYER_HEAD);

        ItemMeta m = head.getItemMeta();
        m.setDisplayName("§7§l/");
        head.setItemMeta(m);
        return head;
    }

    private ItemStack numberedHead(String basePath, int number, String fallbackName) {
        String tex = plugin.getConfig().getString(basePath + "." + number, "");

        ItemStack head = (tex != null && !tex.isBlank())
                ? SkullUtil.headFromTexture(tex)
                : new ItemStack(Material.PLAYER_HEAD);

        ItemMeta m = head.getItemMeta();
        m.setDisplayName(fallbackName);
        head.setItemMeta(m);
        return head;
    }

    private void updateTries(Player p) {
        Inventory inv = openInv.get(p.getUniqueId());
        if (inv == null) return;

        inv.setItem(48, triesUsedHead(p));
        inv.setItem(49, triesSlashHead());
        inv.setItem(50, triesMaxHead());

        p.updateInventory();
    }

    // -------------------------
    // Timeout: contador en slot 0
    // -------------------------

    private void startTimeout(Player p) {
        if (!timeoutEnabled()) return;

        stopTimeout(p);

        int total = timeoutSeconds();
        timeLeft.put(p.getUniqueId(), total);

        // Pintar inicial
        updateTimerItem(p);

        int taskId = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!p.isOnline()) {
                stopTimeout(p);
                return;
            }
            if (!lock.isLocked(p) || auth.isAuthed(p)) {
                stopTimeout(p);
                return;
            }

            int left = timeLeft.getOrDefault(p.getUniqueId(), total);
            left--;
            timeLeft.put(p.getUniqueId(), left);

            updateTimerItem(p);

            if (left <= 0) {
                stopTimeout(p);
                onTimeoutExpire(p);
            }
        }, 20L, 20L).getTaskId();

        timeoutTask.put(p.getUniqueId(), taskId);
    }

    private void stopTimeout(Player p) {
        UUID id = p.getUniqueId();
        Integer t = timeoutTask.remove(id);
        if (t != null) Bukkit.getScheduler().cancelTask(t);
        timeLeft.remove(id);
    }

    private void onTimeoutExpire(Player p) {
        String action = timeoutExpireAction();
        if ("REOPEN".equalsIgnoreCase(action)) {
            open(p);
            return;
        }

        forceClose(p);
        // ✅ SOLO CAMBIO: kick traducible
        p.kickPlayer(lang.tr(p, "auth-timeout"));
    }

    private void updateTimerItem(Player p) {
        Inventory inv = openInv.get(p.getUniqueId());
        if (inv == null) return;

        int left = timeLeft.getOrDefault(p.getUniqueId(), timeoutSeconds());
        int displayFrom = displayFromSeconds();

        int show = Math.min(left, displayFrom);
        if (show < 0) show = 0;

        inv.setItem(8, timerHead(show));
        p.updateInventory();
    }

    private ItemStack timerHead(int seconds) {
        String tex = plugin.getConfig().getString("code-gui.controls.timer." + seconds, "");

        ItemStack head = (tex != null && !tex.isBlank())
                ? SkullUtil.headFromTexture(tex)
                : new ItemStack(Material.PLAYER_HEAD);

        ItemMeta m = head.getItemMeta();
        m.setDisplayName("§e§l" + seconds + "s");
        head.setItemMeta(m);
        return head;
    }

    private String currentCode(UUID id) {
        String set = charset();
        int len = codeLength();
        int[] idx = indicesByPlayer.getOrDefault(id, new int[len]);
        if (idx.length != len) idx = new int[len];

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) sb.append(set.charAt(idx[i] % set.length()));
        return sb.toString();
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!lock.isLocked(p)) return;

        Inventory ours = openInv.get(p.getUniqueId());
        if (ours == null) return;
        if (e.getView().getTopInventory() != ours) return;

        e.setCancelled(true);
        if (e.isShiftClick()) return;
        if (e.getClick() == ClickType.NUMBER_KEY) return;

        int slot = e.getRawSlot();
        if (slot < 0 || slot >= ours.getSize()) return;

        int len = codeLength();
        int sc = startCol(len);
        String set = charset();

        int[] idx = indicesByPlayer.get(p.getUniqueId());
        if (idx == null || idx.length != len) idx = new int[len];

        // Submit
        if (slot == 53) {
            String code = currentCode(p.getUniqueId());
            AuthManager.AuthResult result = auth.tryPassword(p, code);

            if (result == AuthManager.AuthResult.SUCCESS) {
                forceClose(p);
                lock.unlock(p);
                return;
            }

            updateTries(p);

            if (result == AuthManager.AuthResult.BANNED) {
                forceClose(p);
                p.kickPlayer(lang.tr(p, "banned"));
                return;
            }

            if (result == AuthManager.AuthResult.MAX_ATTEMPTS) {
                forceClose(p);
                String action = plugin.getConfig().getString("on-fail-action", "KICK");
                if ("KICK".equalsIgnoreCase(action)) p.kickPlayer(lang.tr(p, "kicked"));
            }
            return;
        }

        // Clear
        if (slot == 45) {
            Arrays.fill(idx, 0);
            for (int i = 0; i < len; i++) {
                ours.setItem(18 + sc + i, charHead(set.charAt(idx[i] % set.length())));
            }
            indicesByPlayer.put(p.getUniqueId(), idx);
            p.updateInventory();
            return;
        }

        // Up
        if (slot >= 9 + sc && slot < 9 + sc + len) {
            int i = slot - (9 + sc);
            idx[i] = (idx[i] + 1) % set.length();
            ours.setItem(18 + sc + i, charHead(set.charAt(idx[i] % set.length())));
            indicesByPlayer.put(p.getUniqueId(), idx);
            p.updateInventory();
            return;
        }

        // Down
        if (slot >= 27 + sc && slot < 27 + sc + len) {
            int i = slot - (27 + sc);
            idx[i]--;
            if (idx[i] < 0) idx[i] = set.length() - 1;
            ours.setItem(18 + sc + i, charHead(set.charAt(idx[i] % set.length())));
            indicesByPlayer.put(p.getUniqueId(), idx);
            p.updateInventory();
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;

        UUID id = p.getUniqueId();
        if (internalClose.contains(id)) return;

        Inventory ours = openInv.get(id);
        if (ours == null) return;
        if (e.getInventory() != ours) return;

        // Si está locked y cierra, kick (evita bypass y evita loops)
        if (lock.isLocked(p) && !auth.isAuthed(p)) {
            stopTimeout(p);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (p.isOnline() && lock.isLocked(p) && !auth.isAuthed(p)) {
                    // ✅ SOLO CAMBIO: kick traducible
                    p.kickPlayer(lang.tr(p, "auth-required"));
                }
            });
        } else {
            stopTimeout(p);
            openInv.remove(id);
            indicesByPlayer.remove(id);
        }
    }
}
