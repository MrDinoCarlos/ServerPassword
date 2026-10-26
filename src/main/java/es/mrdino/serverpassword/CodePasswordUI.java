package es.mrdino.serverpassword;

import org.bukkit.Bukkit;
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
import java.util.function.Predicate;

public class CodePasswordUI implements Listener, PasswordUI {

    private final JavaPlugin plugin;
    private final AuthManager auth;
    private final LockdownManager lock;
    private final Lang lang;
    private final Predicate<Player> enabled;

    private final Map<UUID, Inventory> openInv = new HashMap<>();
    private final Map<UUID, int[]> indicesByPlayer = new HashMap<>();

    private final Map<Character, String> charTextures = new HashMap<>();
    private final Map<String, String> controlTextures = new HashMap<>();

    // Prevent reopening when the plugin closes the menu.
    private final Set<UUID> internalClose = new HashSet<>();

    // Timeout
    private final Map<UUID, Integer> timeoutTask = new HashMap<>();
    private final Map<UUID, Integer> timeLeft = new HashMap<>();

    public CodePasswordUI(JavaPlugin plugin, AuthManager auth, LockdownManager lock, Lang lang) {
        this(plugin, auth, lock, lang, player -> LoginSettings.legacy(plugin.getConfig()));
    }

    CodePasswordUI(JavaPlugin plugin, AuthManager auth, LockdownManager lock, Lang lang, Predicate<Player> enabled) {
        this.plugin = plugin;
        this.auth = auth;
        this.lock = lock;
        this.lang = lang;
        this.enabled = enabled;
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

        // Flat controls: up, down, submit, clear, filler and slash.
        ConfigurationSection controls = plugin.getConfig().getConfigurationSection("code-gui.controls");
        if (controls != null) {
            for (String k : controls.getKeys(false)) {
                if (controls.isConfigurationSection(k)) continue; // tries/max/timer etc.
                controlTextures.put(k.toLowerCase(Locale.ROOT), controls.getString(k, ""));
            }
        }
    }

    private String charset() {
        if (auth.passwordIsNumeric()) return PasswordCredential.DIGITS;
        String configured = plugin.getConfig().getString("code-gui.charset", "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ");
        return (configured == null || configured.isEmpty()) ? "0123456789" : configured;
    }

    private String guiTitle() {
        String t = plugin.getConfig().getString("code-gui.title", "&6&lServer Password");
        return Lang.color(t);
    }

    private int codeLength() {
        int len = auth.passwordLength();
        if (len < 1) len = 1;

        int max = auth.passwordIsNumeric() ? LoginSettings.MAX_PIN_LENGTH : plugin.getConfig().getInt("code-gui.max-length", 9);
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
        int s = plugin.getConfig().getInt("timeout.start-display-at",
                plugin.getConfig().getInt("timeout.display-from", 25));
        if (s < 0) s = 0;
        if (s > 99) s = 99;
        return s;
    }

    private String timeoutExpireAction() {
        return plugin.getConfig().getString("timeout.on-expire", "KICK");
    }

    private String inventoryCloseAction() {
        return plugin.getConfig().getString("code-gui.on-close", "REOPEN");
    }

    private int reopenDelayTicks() {
        int ticks = plugin.getConfig().getInt("code-gui.reopen-delay-ticks", 2);
        return Math.max(1, Math.min(20, ticks));
    }

    public void open(Player p) {
        if (!enabled.test(p)) return;
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

        Inventory inv = PlatformCompat.createInventory(p, 54, guiTitle());
        fillBackground(inv);

        int sc = startCol(len);

        for (int i = 0; i < len; i++) {
            inv.setItem(9 + sc + i, controlItem("up", Material.ARROW, "§a▲"));
            inv.setItem(27 + sc + i, controlItem("down", Material.ARROW, "§c▼"));
            inv.setItem(18 + sc + i, charHead(set.charAt(idx[i] % set.length())));
        }

        // Localized button labels.
        inv.setItem(45, controlItem("clear", Material.BARRIER, lang.tr(p, "gui-clear")));
        inv.setItem(53, controlItem("submit", Material.LIME_WOOL, lang.tr(p, "gui-submit")));

        // Represent attempts using the original head textures.
        inv.setItem(48, triesUsedHead(p));
        inv.setItem(49, triesSlashHead());
        inv.setItem(50, triesMaxHead());

        openInv.put(p.getUniqueId(), inv);

        // Start the timeout and render the clock.
        startTimeout(p);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline() || !lock.isLocked(p) || auth.isAuthed(p)) return;
            if (enabled.test(p) && openInv.get(p.getUniqueId()) == inv) p.openInventory(inv);
        }, 1L);
    }

    /** Closes the menu without reopening it. */
    public void forceClose(Player p) {
        stopTimeout(p);

        UUID id = p.getUniqueId();
        Inventory ours = openInv.remove(id);
        indicesByPlayer.remove(id);

        if (ours != null) {
            internalClose.add(id);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (p.isOnline() && p.getOpenInventory().getTopInventory() == ours) p.closeInventory();
                Bukkit.getScheduler().runTaskLater(plugin, () -> internalClose.remove(id), 2L);
            });
        }
    }

    public void cleanupSession(Player p) {
        UUID id = p.getUniqueId();
        stopTimeout(p);
        openInv.remove(id);
        indicesByPlayer.remove(id);
        internalClose.remove(id);
    }

    public void shutdown() {
        for (Integer taskId : new ArrayList<>(timeoutTask.values())) {
            Bukkit.getScheduler().cancelTask(taskId);
        }
        timeoutTask.clear();
        timeLeft.clear();

        for (UUID id : new ArrayList<>(openInv.keySet())) {
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline()) continue;

            internalClose.add(id);
            player.closeInventory();
        }

        openInv.clear();
        indicesByPlayer.clear();
        internalClose.clear();
    }

    private void fillBackground(Inventory inv) {
        String tex = controlTextures.getOrDefault("filler", "");
        if (tex != null && !tex.isBlank()) {
            ItemStack head = SkullUtil.headFromTexture(tex);
            ItemMeta m = head.getItemMeta();
            PlatformCompat.setDisplayName(m, " ");
            head.setItemMeta(m);
            for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, head);
            return;
        }

        ItemStack glass = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta m = glass.getItemMeta();
        PlatformCompat.setDisplayName(m, " ");
        glass.setItemMeta(m);
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, glass);
    }

    private ItemStack controlItem(String key, Material fallbackMat, String fallbackName) {
        String tex = controlTextures.getOrDefault(key, "");
        ItemStack it = (tex != null && !tex.isBlank())
                ? SkullUtil.headFromTexture(tex)
                : new ItemStack(fallbackMat);

        ItemMeta m = it.getItemMeta();
        PlatformCompat.setDisplayName(m, fallbackName);
        it.setItemMeta(m);
        return it;
    }

    private ItemStack charHead(char c) {
        String tex = charTextures.getOrDefault(c, "");
        ItemStack head = (tex != null && !tex.isBlank())
                ? SkullUtil.headFromTexture(tex)
                : new ItemStack(Material.PLAYER_HEAD);

        ItemMeta m = head.getItemMeta();
        PlatformCompat.setDisplayName(m, "§b§l" + c);
        head.setItemMeta(m);
        return head;
    }

    // -------------------------
    // Dynamic attempt counter heads.
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
        PlatformCompat.setDisplayName(m, "§7§l/");
        head.setItemMeta(m);
        return head;
    }

    private ItemStack numberedHead(String basePath, int number, String fallbackName) {
        String tex = plugin.getConfig().getString(basePath + "." + number, "");

        ItemStack head = (tex != null && !tex.isBlank())
                ? SkullUtil.headFromTexture(tex)
                : new ItemStack(Material.PLAYER_HEAD);

        ItemMeta m = head.getItemMeta();
        PlatformCompat.setDisplayName(m, fallbackName);
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
    // Timeout counter.
    // -------------------------

    private void startTimeout(Player p) {
        if (!enabled.test(p) || !timeoutEnabled()) return;

        stopTimeout(p);

        int total = timeoutSeconds();
        timeLeft.put(p.getUniqueId(), total);

        // Render the initial countdown.
        updateTimerItem(p);

        int taskId = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!enabled.test(p)) { stopTimeout(p); return; }
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
            startTimeout(p);
            return;
        }

        forceClose(p);
        // Localized timeout message.
        PlatformCompat.kick(p, lang.tr(p, "auth-timeout"));
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
        PlatformCompat.setDisplayName(m, "§e§l" + seconds + "s");
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
        if (openInv.isEmpty()) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!enabled.test(p)) return;
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
            auth.tryPasswordAsync(p, code, result -> {
            if (openInv.get(p.getUniqueId()) != ours || !lock.isLocked(p)) return;

            if (result == AuthManager.AuthResult.SUCCESS) {
                forceClose(p);
                lock.unlock(p);
                return;
            }

            updateTries(p);

            if (result == AuthManager.AuthResult.BANNED) {
                forceClose(p);
                PlatformCompat.kick(p, lang.tr(p, "banned"));
                return;
            }

            if (result == AuthManager.AuthResult.MAX_ATTEMPTS) {
                forceClose(p);
                String action = plugin.getConfig().getString("on-fail-action", "KICK");
                if ("KICK".equalsIgnoreCase(action)) PlatformCompat.kick(p, lang.tr(p, "kicked"));
                else {
                    auth.resetAttempts(p);
                    open(p);
                }
            }
            });
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
        if (openInv.isEmpty()) return;
        if (!(e.getPlayer() instanceof Player p)) return;
        if (!enabled.test(p)) return;

        UUID id = p.getUniqueId();
        if (internalClose.contains(id)) return;

        Inventory ours = openInv.get(id);
        if (ours == null) return;
        if (e.getInventory() != ours) return;

        if (lock.isLocked(p) && !auth.isAuthed(p)) {
            if ("KICK".equalsIgnoreCase(inventoryCloseAction())) {
                stopTimeout(p);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!enabled.test(p)) return;
                    if (p.isOnline() && lock.isLocked(p) && !auth.isAuthed(p)) {
                        PlatformCompat.kick(p, lang.tr(p, "auth-required"));
                    }
                });
                return;
            }

            reopenExisting(p, ours);
            return;
        }

        cleanupSession(p);
    }

    private void reopenExisting(Player p, Inventory inv) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            UUID id = p.getUniqueId();
            if (!p.isOnline() || !lock.isLocked(p) || auth.isAuthed(p)) return;
            if (!enabled.test(p) || openInv.get(id) != inv) return;
            if (p.getOpenInventory().getTopInventory() == inv) return;
            p.openInventory(inv);
        }, reopenDelayTicks());
    }
}
