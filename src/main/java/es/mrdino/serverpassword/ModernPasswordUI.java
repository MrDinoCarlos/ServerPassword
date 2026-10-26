package es.mrdino.serverpassword;

import org.bukkit.Material;
import org.bukkit.Sound;
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
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Consumer;

/** Native login state, with a compatibility callback when dialogs are unavailable. */
final class ModernPasswordUI implements Listener, PasswordUI {
    private static final int PAGE_SIZE = 36;
    private static final int KEYBOARD_PAGE_SIZE = 30;
    private static final String KEYBOARD_LETTERS = "qwertyuiopasdfghjklzxcvbnm";
    private final JavaPlugin plugin;
    private final AuthManager auth;
    private final LockdownManager lock;
    private final Lang lang;
    private final NativeLoginScreen dialogs;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<String, ItemStack> headTemplates = new HashMap<>();
    private final BukkitTask ticker;
    private final LongSupplier clock;
    private Consumer<Player> fallback;
    private boolean compatibilityWarningShown;

    ModernPasswordUI(JavaPlugin plugin, AuthManager auth, LockdownManager lock, Lang lang) {
        this(plugin, auth, lock, lang, loadDialogs(plugin), System::currentTimeMillis);
    }

    ModernPasswordUI(JavaPlugin plugin, AuthManager auth, LockdownManager lock, Lang lang,
                     NativeLoginScreen dialogs, LongSupplier clock) {
        this.plugin = plugin;
        this.auth = auth;
        this.lock = lock;
        this.lang = lang;
        this.dialogs = dialogs;
        this.clock = clock;
        ticker = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    static NativeLoginScreen loadDialogs(JavaPlugin plugin) {
        try {
            Class.forName("io.papermc.paper.dialog.Dialog", false, plugin.getClass().getClassLoader());
            return (NativeLoginScreen) Class.forName("es.mrdino.serverpassword.PaperDialogScreen")
                    .getConstructor(JavaPlugin.class).newInstance(plugin);
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return null;
        }
    }

    void warnCompatibility() {
        if (dialogs == null && !compatibilityWarningShown) {
            compatibilityWarningShown = true;
            plugin.getLogger().warning("Native custom screens require Paper 1.21.7 or newer and a compatible client. "
                    + "They are unavailable on this server; PIN login will use the classic up/down selector, "
                    + "and text passwords will be entered privately in chat.");
        }
    }

    boolean supports(Player player) {
        return !"INVENTORY".equalsIgnoreCase(plugin.getConfig().getString("modern-gui.presentation", "AUTO"))
                && dialogs != null && dialogs.supports(player);
    }

    void setFallback(Consumer<Player> fallback) { this.fallback = fallback; }

    private int maxLength() {
        int limit = plugin.getConfig().getInt("modern-gui.max-length", 32);
        return auth.passwordIsNumeric() ? Math.min(LoginSettings.MAX_PIN_LENGTH,
                auth.passwordLength() > 0 ? Math.min(limit, auth.passwordLength()) : limit) : limit;
    }
    private int timeoutSeconds() { return plugin.getConfig().getInt("timeout.seconds", 30); }
    private boolean timed() { return plugin.getConfig().getBoolean("timeout.enabled", true); }
    private String charset() {
        return plugin.getConfig().getString("modern-gui.virtual-keyboard.charset",
                "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz -_!?@#$%&*+=./:");
    }
    private boolean virtualKeyboard() { return plugin.getConfig().getBoolean("modern-gui.virtual-keyboard.enabled", true); }

    @Override public void open(Player player) {
        if (!player.isOnline() || !lock.isLocked(player) || auth.isAuthed(player)) return;
        Session existing = sessions.get(player.getUniqueId());
        if (existing != null) { render(existing); return; }
        boolean numeric = LoginSettings.numeric(plugin.getConfig(), auth.passwordIsNumeric());
        String presentation = plugin.getConfig().getString("modern-gui.presentation", "AUTO");
        boolean nativeScreen = !"INVENTORY".equalsIgnoreCase(presentation) && dialogs != null && dialogs.supports(player);
        Session session = new Session(player, new LoginInput(numeric, maxLength(),
                !plugin.getConfig().getBoolean("modern-gui.hide-password-by-default", true)), nativeScreen);
        session.uppercase = charset().chars().noneMatch(Character::isLowerCase);
        session.symbols = charset().chars().noneMatch(character -> KEYBOARD_LETTERS.indexOf(Character.toLowerCase(character)) >= 0);
        session.deadline = clock.getAsLong() + timeoutSeconds() * 1000L;
        session.urgency = urgency(session);
        if (timed() && plugin.getConfig().getBoolean("modern-gui.countdown.bossbar", true)) {
            session.bar = plugin.getServer().createBossBar("", BarColor.BLUE, BarStyle.SOLID);
            session.bar.addPlayer(player);
        }
        sessions.put(player.getUniqueId(), session);
        updateBar(session);
        render(session);
    }

    private String message(Session session, String key) {
        String code = session.input.display();
        return lang.tr(session.player, key, Map.of("code", code.isEmpty() ? "_" : code,
                "length", String.valueOf(session.input.value().length()), "max-length", String.valueOf(maxLength()),
                "used", String.valueOf(auth.getAttemptsThisJoin(session.player)),
                "max", String.valueOf(plugin.getConfig().getInt("max-attempts-per-join", 3)),
                "seconds", timerColor(session) + secondsLeft(session),
                "cooldown", String.valueOf(Math.max(1, auth.cooldownSecondsLeft(session.player))),
                "slots", displayCode(session), "page", String.valueOf(session.page + 1)));
    }

    private String displayCode(Session session) {
        String color = session.feedback == AuthManager.AuthResult.SUCCESS ? "§a" : session.feedback != null ? "§c" : "§f";
        String code;
        if (session.input.numeric()) {
            StringBuilder slots = new StringBuilder();
            for (int index = 0; index < maxLength(); index++) {
                boolean compact = maxLength() > 6;
                if (index > 0) slots.append(compact ? " " : "  ");
                slots.append(compact ? "[" : "[ ");
                slots.append(index >= session.input.value().length() ? "_" : session.input.revealed()
                        ? session.input.value().substring(index, index + 1) : "■");
                slots.append(compact ? "]" : " ]");
            }
            code = slots.toString();
        } else {
            code = session.input.revealed() ? session.input.value() : "■".repeat(session.input.value().codePointCount(0, session.input.value().length()));
            int count = code.codePointCount(0, code.length());
            if (count > 22) code = "..." + code.substring(code.offsetByCodePoints(0, count - 22));
            code = "[ " + (code.isEmpty() ? "_" : code) + " ]";
        }
        // Fixed total padding moves only the code during feedback; the keypad stays still.
        int offset = session.feedback != null && session.feedback != AuthManager.AuthResult.SUCCESS
                && plugin.getConfig().getBoolean("modern-gui.feedback.shake", true)
                ? new int[]{0, -1, 1, -1, 0}[session.feedbackFrame] : 0;
        String padding = " ".repeat(2 + offset);
        String tail = " ".repeat(2 - offset);
        String rule = "─".repeat(session.input.numeric() ? Math.min(22, maxLength() * 4 + 2) : 22);
        String border = session.feedback == AuthManager.AuthResult.SUCCESS && session.feedbackFrame % 2 == 1 ? "§2" : color;
        return color + "§l" + padding + code + tail + "\n§r" + border + rule;
    }

    private int urgency(Session session) {
        if (!timed()) return 0;
        int seconds = secondsLeft(session);
        return seconds <= plugin.getConfig().getInt("modern-gui.countdown.critical-seconds", 5) ? 2
                : seconds <= plugin.getConfig().getInt("modern-gui.countdown.warning-seconds", 10) ? 1 : 0;
    }

    private String timerColor(Session session) { return switch (urgency(session)) { case 2 -> "§c"; case 1 -> "§6"; default -> "§b"; }; }

    private boolean textEntry(Session session) {
        return session.nativeScreen && !session.input.numeric() && !session.help && !session.options
                && !session.checking && session.feedback == null;
    }

    private int secondsLeft(Session session) {
        return (int) Math.max(0L, (session.deadline - clock.getAsLong() + 999L) / 1000L);
    }

    private void render(Session session) {
        if (sessions.get(session.player.getUniqueId()) != session) return;
        if (session.nativeScreen) {
            try { renderDialog(session); return; }
            catch (RuntimeException | LinkageError failure) {
                session.nativeScreen = false;
                session.editing = false;
                plugin.getLogger().log(java.util.logging.Level.WARNING,
                        "Native login dialog failed; using the classic PIN selector or private text chat for this connection.", failure);
                dialogs.close(session.player);
                if (fallback != null) {
                    forceClose(session.player);
                    fallback.accept(session.player);
                    return;
                }
            }
        }
        renderInventory(session);
    }

    private void renderDialog(Session session) {
        List<String> lines = new ArrayList<>();
        List<NativeLoginScreen.Button> buttons = new ArrayList<>();
        NativeLoginScreen.Layout layout = NativeLoginScreen.Layout.PANEL;
        NativeLoginScreen.Button primary;
        if (session.help) {
            lines.add(message(session, "screen-compact-help-1"));
            lines.add(message(session, "screen-compact-help-2"));
            lines.add(message(session, "screen-compact-help-3"));
            lines.add(message(session, timed() ? "screen-compact-meta" : "screen-compact-meta-untimed"));
            primary = new NativeLoginScreen.Button("BACK", message(session, "screen-compact-back"));
        } else if (session.options) {
            lines.add(message(session, "screen-compact-options"));
            lines.add(message(session, "screen-compact-code"));
            lines.add(message(session, timed() ? "screen-compact-meta" : "screen-compact-meta-untimed"));
            if (plugin.getConfig().getBoolean("modern-gui.allow-reveal", true))
                button(session, buttons, "REVEAL", session.input.revealed() ? "screen-hide" : "screen-show");
            button(session, buttons, "CLEAR", "gui-clear");
            button(session, buttons, "HELP", "screen-help");
            button(session, buttons, "LANGUAGE", "screen-language");
            compactButton(session, buttons, "EXIT", "screen-compact-disconnect", "screen-exit");
            primary = new NativeLoginScreen.Button("BACK", message(session, "screen-compact-back"));
        } else {
            lines.add(message(session, session.input.numeric() ? "screen-compact-pin" : "screen-inline-password"));
            if (!textEntry(session)) lines.add(message(session, "screen-compact-code"));
            lines.add(message(session, timed() ? "screen-compact-meta" : "screen-compact-meta-untimed"));
            lines.add("screen-ready".equals(session.status) ? " " : message(session, switch (session.status) {
                case "screen-checking" -> "screen-compact-checking";
                case "success" -> "screen-compact-success";
                case "wrong-password" -> "screen-compact-wrong";
                default -> session.status;
            }));
            if (session.input.numeric()) {
                layout = NativeLoginScreen.Layout.KEYPAD;
                for (int number = 1; number <= 9; number++) buttons.add(new NativeLoginScreen.Button("DIGIT:" + number, "§f§l" + number));
                buttons.add(new NativeLoginScreen.Button("BACKSPACE", "§e<-", message(session, "screen-backspace")));
                buttons.add(new NativeLoginScreen.Button("DIGIT:0", "§f§l0"));
                compactButton(session, buttons, "OPTIONS", "screen-compact-more", "screen-compact-options");
                buttons.add(new NativeLoginScreen.Button("CLEAR", "§cC", message(session, "gui-clear")));
                buttons.add(new NativeLoginScreen.Button(plugin.getConfig().getBoolean("modern-gui.allow-reveal", true) ? "REVEAL" : "HELP",
                        "§e?", message(session, plugin.getConfig().getBoolean("modern-gui.allow-reveal", true)
                        ? session.input.revealed() ? "screen-hide" : "screen-show" : "screen-help")));
                buttons.add(new NativeLoginScreen.Button("EXIT", "§cX", message(session, "screen-compact-disconnect")));
            } else if (session.keyboard) {
                layout = NativeLoginScreen.Layout.KEYBOARD;
                keyboardButtons(session, buttons);
            } else {
                if (virtualKeyboard()) compactButton(session, buttons, "KEYBOARD", "screen-compact-keyboard", "screen-keyboard");
                compactButton(session, buttons, "OPTIONS", "screen-compact-more", "screen-compact-options");
                compactButton(session, buttons, "EXIT", "screen-compact-disconnect", "screen-exit");
            }
            primary = new NativeLoginScreen.Button("SUBMIT", message(session, session.checking ? "screen-compact-checking" : "gui-submit"));
        }
        String heading = session.feedback == null && urgency(session) == 0 ? title()
                : (session.feedback == AuthManager.AuthResult.SUCCESS ? "§a" : session.feedback != null ? "§c" : timerColor(session))
                + "§l" + title().replaceAll("(?i)§[0-9a-fk-orx]", "");
        if (session.help) compactButton(session, buttons, "EXIT", "screen-compact-disconnect", "screen-exit");
        dialogs.show(session.player, new NativeLoginScreen.View(heading, lines, buttons, primary, layout,
                textEntry(session), session.input.value(), maxLength(), session.revision),
                (action, value) -> handle(session, action, value));
    }

    private void button(Session session, List<NativeLoginScreen.Button> buttons, String action, String key) {
        buttons.add(new NativeLoginScreen.Button(action, message(session, key)));
    }

    private void compactButton(Session session, List<NativeLoginScreen.Button> buttons, String action, String label, String tooltip) {
        buttons.add(new NativeLoginScreen.Button(action, message(session, label), message(session, tooltip)));
    }

    private String symbolCharacters() {
        StringBuilder symbols = new StringBuilder();
        for (char character : charset().toCharArray()) {
            if (character != ' ' && KEYBOARD_LETTERS.indexOf(Character.toLowerCase(character)) < 0 && symbols.indexOf(String.valueOf(character)) < 0)
                symbols.append(character);
        }
        return symbols.toString();
    }

    private void keyboardKey(List<NativeLoginScreen.Button> buttons, char character) {
        int index = charset().indexOf(character);
        buttons.add(index < 0 ? new NativeLoginScreen.Button("NONE", " ")
                : new NativeLoginScreen.Button("CHAR:" + index, "§f" + character));
    }

    private void keyboardButtons(Session session, List<NativeLoginScreen.Button> buttons) {
        if (session.symbols) {
            String symbols = symbolCharacters();
            for (int cell = 0; cell < KEYBOARD_PAGE_SIZE; cell++) {
                int index = session.page * KEYBOARD_PAGE_SIZE + cell;
                if (index < symbols.length()) keyboardKey(buttons, symbols.charAt(index));
                else buttons.add(new NativeLoginScreen.Button("NONE", " "));
            }
        } else {
            for (String row : List.of("qwertyuiop", "asdfghjkl ", "zxcvbnm.?!")) {
                for (char character : row.toCharArray()) {
                    if (character == ' ') buttons.add(new NativeLoginScreen.Button("NONE", " "));
                    else keyboardKey(buttons, session.uppercase ? Character.toUpperCase(character) : character);
                }
            }
        }
        buttons.add(new NativeLoginScreen.Button("SHIFT", "§fAa", message(session, "screen-compact-shift")));
        buttons.add(new NativeLoginScreen.Button("SYMBOLS", session.symbols ? "§bAb" : "§b#", message(session, "screen-compact-symbols")));
        buttons.add(new NativeLoginScreen.Button("SPACE", "§fSp", message(session, "screen-space")));
        buttons.add(new NativeLoginScreen.Button("BACKSPACE", "§e<-", message(session, "screen-backspace")));
        buttons.add(new NativeLoginScreen.Button("CLEAR", "§cC", message(session, "gui-clear")));
        buttons.add(new NativeLoginScreen.Button(session.symbols && session.page > 0 ? "PREVIOUS" : "NONE", "§f<", message(session, "screen-previous")));
        buttons.add(new NativeLoginScreen.Button(session.symbols && (session.page + 1) * KEYBOARD_PAGE_SIZE < symbolCharacters().length()
                ? "NEXT" : "NONE", "§f>", message(session, "screen-next")));
        buttons.add(new NativeLoginScreen.Button("EXIT", "§cX", message(session, "screen-compact-disconnect")));
        buttons.add(new NativeLoginScreen.Button("OPTIONS", "§f...", message(session, "screen-compact-options")));
        buttons.add(new NativeLoginScreen.Button("KEYBOARD", "§e^", message(session, "screen-keyboard-close")));
    }

    private String title() { return Lang.color(plugin.getConfig().getString("modern-gui.title", "&b&lServer Password")); }

    private ItemStack item(Material material, int amount, String name, String... lore) {
        ItemStack item = new ItemStack(material, Math.max(1, Math.min(64, amount)));
        return decorate(item, name, lore);
    }

    private ItemStack icon(String texturePath, Material fallback, int amount, String name, String... lore) {
        String texture = plugin.getConfig().getBoolean("modern-gui.inventory-custom-heads", true)
                ? plugin.getConfig().getString("code-gui." + texturePath, "") : "";
        if (texture.isBlank()) return item(fallback, amount, name, lore);
        if (headTemplates.size() >= 128) headTemplates.clear();
        ItemStack head = headTemplates.computeIfAbsent(texture, SkullUtil::headFromTexture).clone();
        return decorate(head, name, lore);
    }

    private ItemStack decorate(ItemStack item, String name, String... lore) {
        ItemMeta meta = item.getItemMeta();
        PlatformCompat.setDisplayName(meta, name);
        if (lore.length > 0) PlatformCompat.setLore(meta, List.of(lore));
        item.setItemMeta(meta);
        return item;
    }

    private void control(Session session, int slot, Material material, String action, String key) {
        String texture = switch (action) {
            case "CLEAR" -> "controls.clear";
            case "SUBMIT" -> "controls.submit";
            case "BACKSPACE", "PREVIOUS" -> "controls.down";
            case "NEXT" -> "controls.up";
            default -> "controls." + action.toLowerCase(java.util.Locale.ROOT);
        };
        session.inventory.setItem(slot, icon(texture, material, 1, message(session, key)));
        session.actions.put(slot, action);
    }

    private void renderInventory(Session session) {
        if (session.editing) return;
        boolean opening = session.inventory == null || session.inventory.getSize() != 54;
        if (opening) session.inventory = PlatformCompat.createInventory(session.player, 54, title());
        session.actions.clear();
        Material background = session.feedback == AuthManager.AuthResult.SUCCESS ? Material.LIME_STAINED_GLASS_PANE
                : session.feedback != null ? Material.RED_STAINED_GLASS_PANE : Material.BLACK_STAINED_GLASS_PANE;
        ItemStack filler = item(background, 1, " ");
        for (int slot = 0; slot < 54; slot++) session.inventory.setItem(slot, filler);
        control(session, 0, Material.BOOK, "HELP", "screen-help");
        control(session, 6, Material.NAME_TAG, "LANGUAGE", "screen-language");
        session.inventory.setItem(4, item(Material.PAPER, 1, message(session, "screen-code"),
                message(session, "screen-length"), message(session, session.status)));
        session.inventory.setItem(7, icon("controls.tries." + auth.getAttemptsThisJoin(session.player),
                Material.TRIPWIRE_HOOK, 1, message(session, "screen-tries")));
        updateClock(session);
        if (session.help) {
            session.inventory.setItem(22, item(Material.WRITABLE_BOOK, 1, message(session, "screen-help"),
                    message(session, "screen-help-1"), message(session, "screen-help-2"), message(session, "screen-help-3")));
            control(session, 49, Material.ARROW, "BACK", "screen-back");
        } else {
            if (session.input.numeric()) {
                int[] slots = {12, 13, 14, 21, 22, 23, 30, 31, 32};
                for (int number = 1; number <= 9; number++) {
                    session.inventory.setItem(slots[number - 1], icon("textures." + number, Material.LIGHT_BLUE_DYE, number, "§f§l" + number));
                    session.actions.put(slots[number - 1], "DIGIT:" + number);
                }
                session.inventory.setItem(40, icon("textures.0", Material.SLIME_BALL, 1, "§f§l0"));
                session.actions.put(40, "DIGIT:0");
            } else if (session.keyboard) {
                String characters = charset();
                for (int index = session.page * PAGE_SIZE; index < Math.min(characters.length(), (session.page + 1) * PAGE_SIZE); index++) {
                    char character = characters.charAt(index);
                    int slot = 9 + index - session.page * PAGE_SIZE;
                    session.inventory.setItem(slot, icon("textures." + character, Material.PAPER, 1,
                            character == ' ' ? message(session, "screen-space") : "§b§l" + character));
                    session.actions.put(slot, "CHAR:" + index);
                }
                control(session, 50, Material.ARROW, "PREVIOUS", "screen-previous");
                control(session, 51, Material.ARROW, "NEXT", "screen-next");
            } else {
                control(session, 22, Material.WRITABLE_BOOK, "TYPE", "screen-type");
            }
            control(session, 45, Material.BARRIER, "CLEAR", "gui-clear");
            control(session, 46, Material.SHEARS, "BACKSPACE", "screen-backspace");
            if (plugin.getConfig().getBoolean("modern-gui.allow-reveal", true))
                control(session, 47, Material.ENDER_EYE, "REVEAL", session.input.revealed() ? "screen-hide" : "screen-show");
            if (!session.input.numeric()) {
                control(session, 48, Material.NAME_TAG, "TYPE", "screen-type");
                if (virtualKeyboard()) control(session, 49, Material.COMPARATOR, "KEYBOARD", session.keyboard ? "screen-keyboard-close" : "screen-keyboard");
            }
            control(session, 53, session.checking ? Material.YELLOW_WOOL : Material.LIME_WOOL,
                    "SUBMIT", session.checking ? "screen-checking" : "gui-submit");
        }
        control(session, 52, Material.OAK_DOOR, "EXIT", "screen-exit");
        if (session.player.getOpenInventory().getTopInventory() != session.inventory) {
            session.switching = true;
            try { session.player.openInventory(session.inventory); }
            finally { session.switching = false; }
        }
    }

    private void updateClock(Session session) {
        if (session.inventory != null && session.inventory.getSize() == 54)
            session.inventory.setItem(8, icon("controls.timer." + Math.min(secondsLeft(session), plugin.getConfig().getInt("timeout.start-display-at", 25)),
                    Material.CLOCK, timed() ? secondsLeft(session) : 1,
                    message(session, timed() ? "screen-timer" : "screen-no-timeout")));
    }

    private void updateBar(Session session) {
        if (session.bar == null) return;
        int seconds = secondsLeft(session);
        if (session.lastBarSeconds == seconds && session.status.equals(session.lastBarStatus)
                && session.feedback == session.lastBarFeedback) return;
        session.lastBarSeconds = seconds;
        session.lastBarStatus = session.status;
        session.lastBarFeedback = session.feedback;
        session.bar.setTitle(message(session, "screen-timer") + " §8| " + message(session, session.status));
        session.bar.setProgress(Math.max(0.0, Math.min(1.0, secondsLeft(session) / (double) timeoutSeconds())));
        session.bar.setColor(session.feedback == AuthManager.AuthResult.SUCCESS ? BarColor.GREEN
                : session.feedback != null || urgency(session) == 2 ? BarColor.RED : urgency(session) == 1 ? BarColor.YELLOW : BarColor.BLUE);
    }

    private void handle(Session session, String action, String value) {
        if (sessions.get(session.player.getUniqueId()) != session || !session.player.isOnline() || !lock.isLocked(session.player)) return;
        if ("NONE".equals(action)) return;
        if (action.equals("EXIT")) { PlatformCompat.kick(session.player, message(session, "auth-required")); return; }
        if (session.checking || session.feedback != null) return;
        // Every button response carries the latest native field, including unsent physical keystrokes.
        if (textEntry(session) && value != null && !session.input.replace(value)) {
            session.player.sendMessage(message(session, "screen-invalid-input"));
            return;
        }
        session.revision++;
        switch (action) {
            case "TYPE" -> {
                if (session.input.numeric()) return;
                if (session.nativeScreen) { render(session); return; }
                session.editing = true;
                session.help = false;
                session.options = false;
                if (session.nativeScreen) render(session);
                else openAnvil(session);
                return;
            }
            case "APPLY" -> {
                if (!session.input.replace(session.nativeScreen ? value : session.pendingText)) {
                    session.status = "screen-invalid-input";
                    session.player.sendMessage(message(session, session.status));
                    return;
                }
                session.editing = false;
                session.pendingText = null;
            }
            case "BACK" -> {
                session.help = false;
                session.options = false;
                session.editing = false;
            }
            case "HELP" -> { session.help = !session.help; session.editing = false; }
            case "OPTIONS" -> { session.options = !session.options; session.help = false; session.editing = false; }
            case "LANGUAGE" -> lang.cycleLanguage(session.player);
            case "REVEAL" -> {
                if (plugin.getConfig().getBoolean("modern-gui.allow-reveal", true)) session.input.toggleRevealed();
            }
            case "CLEAR" -> session.input.clear();
            case "BACKSPACE" -> session.input.backspace();
            case "KEYBOARD" -> {
                if (!virtualKeyboard() || session.input.numeric()) return;
                if (session.editing && !session.nativeScreen) session.input.replace(session.pendingText);
                session.editing = false;
                session.options = false;
                session.keyboard = !session.keyboard;
            }
            case "SHIFT" -> session.uppercase = !session.uppercase;
            case "SYMBOLS" -> { session.symbols = !session.symbols; session.page = 0; }
            case "SPACE" -> {
                if (charset().indexOf(' ') >= 0) session.input.append(' ');
            }
            case "PREVIOUS" -> session.page = Math.max(0, session.page - 1);
            case "NEXT" -> session.page = Math.min(session.nativeScreen
                    ? Math.max(0, (symbolCharacters().length() - 1) / KEYBOARD_PAGE_SIZE) : (charset().length() - 1) / PAGE_SIZE, session.page + 1);
            case "SUBMIT" -> { submit(session); return; }
            default -> {
                if (action.startsWith("DIGIT:")) session.input.append(action.charAt(6));
                else if (action.startsWith("CHAR:")) {
                    int index = Integer.parseInt(action.substring(5));
                    if (index >= 0 && index < charset().length()) session.input.append(charset().charAt(index));
                }
            }
        }
        session.status = "screen-ready";
        render(session);
    }

    private void submit(Session session) {
        if (session.input.value().isEmpty()) { session.status = "screen-empty"; render(session); return; }
        session.checking = true;
        session.status = "screen-checking";
        render(session);
        if (sessions.get(session.player.getUniqueId()) != session) return;
        auth.tryPasswordAsync(session.player, session.input.value(), result -> {
            if (sessions.get(session.player.getUniqueId()) != session) return;
            session.checking = false;
            session.revision++;
            if (result == AuthManager.AuthResult.COOLDOWN || result == AuthManager.AuthResult.INVALID_INPUT) {
                session.status = result == AuthManager.AuthResult.COOLDOWN ? "screen-cooldown" : "screen-invalid-input";
            } else {
                session.feedback = result;
                session.status = result == AuthManager.AuthResult.SUCCESS ? "success" : "wrong-password";
                int ticks = plugin.getConfig().getBoolean("modern-gui.feedback.enabled", true)
                        ? Math.max(1, Math.min(40, plugin.getConfig().getInt("modern-gui.feedback.duration-ticks", 10))) : 0;
                session.feedbackUntil = clock.getAsLong() + ticks * 50L;
                session.feedbackStarted = clock.getAsLong();
                session.feedbackFrame = 0;
                if (plugin.getConfig().getBoolean("modern-gui.feedback.sounds", true)) {
                    session.player.playSound(session.player.getLocation(), result == AuthManager.AuthResult.SUCCESS
                            ? Sound.ENTITY_PLAYER_LEVELUP : Sound.ENTITY_VILLAGER_NO, 0.6F, 1.2F);
                }
            }
            render(session);
            updateBar(session);
        });
    }

    private void openAnvil(Session session) {
        session.switching = true;
        try {
            InventoryView view = PlatformCompat.openTextEditor(session.player);
            if (view == null) throw new IllegalStateException("Anvil could not be opened.");
            session.inventory = view.getTopInventory();
            session.editorInventory = session.inventory;
            session.pendingText = session.input.value();
            session.inventory.setItem(0, item(Material.PAPER, 1, session.input.value().isEmpty() ? " " : session.input.value()));
            if (virtualKeyboard()) session.inventory.setItem(1, item(Material.BOOK, 1, message(session, "screen-keyboard")));
            PlatformCompat.freeAnvil(session.player, session.inventory);
            session.inventory.setItem(2, item(Material.LIME_DYE, 1, message(session, "screen-apply")));
        } catch (RuntimeException | LinkageError unavailable) {
            session.editing = false;
            session.keyboard = true;
            session.status = "screen-editor-unavailable";
            plugin.getLogger().warning("Text editor unavailable; using the vanilla virtual keyboard: " + unavailable.getClass().getSimpleName());
        } finally { session.switching = false; }
        if (!session.editing) render(session);
    }

    @EventHandler public void onPrepareAnvil(PrepareAnvilEvent event) {
        if (!(event.getView().getPlayer() instanceof Player player)) return;
        Session session = sessions.get(player.getUniqueId());
        if (session == null || !session.editing || session.inventory != event.getInventory()) return;
        session.pendingText = PlatformCompat.anvilText(player, event.getInventory());
        PlatformCompat.freeAnvil(player, event.getInventory());
        event.setResult(item(Material.LIME_DYE, 1, message(session, "screen-apply")));
    }

    @EventHandler public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Session session = sessions.get(player.getUniqueId());
        if (session == null || !lock.isLocked(player)) return;
        event.setCancelled(true);
        if (session.inventory != event.getView().getTopInventory() || event.isShiftClick()) return;
        String action = session.editing ? event.getRawSlot() == 2 ? "APPLY"
                : event.getRawSlot() == 1 && virtualKeyboard() ? "KEYBOARD" : null : session.actions.get(event.getRawSlot());
        if (event.getClick() == ClickType.NUMBER_KEY) {
            action = session.input.numeric() && !session.editing ? "DIGIT:" + (event.getHotbarButton() + 1) : null;
        } else if (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT) return;
        if (action == null) return;
        String selected = action;
        long revision = session.revision;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (session.revision == revision) handle(session, selected, null);
        });
    }

    @EventHandler public void onDrag(InventoryDragEvent event) {
        if (sessions.containsKey(event.getWhoClicked().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        Session session = sessions.get(player.getUniqueId());
        if (session != null && session.editorInventory == event.getInventory()) {
            // Virtual anvil inputs must never be returned to the real player inventory.
            session.editorInventory.clear();
            session.editorInventory = null;
        }
        if (session == null || session.switching || session.nativeScreen || session.inventory != event.getInventory()) return;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (sessions.get(player.getUniqueId()) != session || !player.isOnline() || !lock.isLocked(player)) return;
            if ("KICK".equalsIgnoreCase(plugin.getConfig().getString("modern-gui.on-close", "REOPEN"))) {
                PlatformCompat.kick(player, message(session, "auth-required"));
            } else {
                session.editing = false;
                session.revision++;
                render(session);
            }
        }, 2L);
    }

    private void tick() {
        for (Session session : new ArrayList<>(sessions.values())) {
            if (!session.player.isOnline() || !lock.isLocked(session.player)) { forceClose(session.player); continue; }
            if (session.feedback != null && clock.getAsLong() >= session.feedbackUntil) {
                AuthManager.AuthResult result = session.feedback;
                session.feedback = null;
                session.revision++;
                if (result == AuthManager.AuthResult.SUCCESS) { lock.unlock(session.player); continue; }
                if (result == AuthManager.AuthResult.BANNED) {
                    PlatformCompat.kick(session.player, message(session, "banned")); continue;
                }
                if (result == AuthManager.AuthResult.MAX_ATTEMPTS) {
                    if ("KICK".equalsIgnoreCase(plugin.getConfig().getString("on-fail-action", "KICK"))) {
                        PlatformCompat.kick(session.player, message(session, "kicked")); continue;
                    }
                    auth.resetAttempts(session.player);
                    session.deadline = clock.getAsLong() + timeoutSeconds() * 1000L;
                }
                session.input.clear();
                session.pendingText = null;
                session.status = "screen-ready";
                render(session);
            } else if (session.feedback != null) {
                long duration = Math.max(1, session.feedbackUntil - session.feedbackStarted);
                int frame = (int) Math.min(4, (clock.getAsLong() - session.feedbackStarted) * 5 / duration);
                if (frame != session.feedbackFrame) {
                    session.feedbackFrame = frame;
                    session.revision++;
                    render(session);
                }
            }
            if (timed() && secondsLeft(session) <= 0 && session.feedback != AuthManager.AuthResult.SUCCESS) {
                if ("REOPEN".equalsIgnoreCase(plugin.getConfig().getString("timeout.on-expire", "KICK"))) {
                    session.deadline = clock.getAsLong() + timeoutSeconds() * 1000L;
                    session.status = "screen-ready";
                    if (!session.editing) render(session);
                } else { PlatformCompat.kick(session.player, message(session, "auth-timeout")); continue; }
            }
            updateBar(session);
            countdownSound(session);
            // Urgency colors update on the live bar. Never replace a field that may have unsent text.
            int stage = urgency(session);
            if (stage != session.urgency) {
                session.urgency = stage;
                if (session.input.numeric() && session.feedback == null && !session.checking) {
                    session.revision++;
                    render(session);
                }
            }
            int seconds = secondsLeft(session);
            if (session.lastSeconds != seconds) {
                session.lastSeconds = seconds;
                // Native dialogs are replaced as a whole. Keep them stable; the BossBar is live.
                // Their time snapshot updates on user actions, without resetting focus or scrolling.
                if (!session.nativeScreen) updateClock(session);
            }
        }
    }

    private void countdownSound(Session session) {
        int seconds = secondsLeft(session);
        if (!timed() || urgency(session) == 0 || seconds <= 0 || session.feedback != null || session.checking
                || !plugin.getConfig().getBoolean("modern-gui.countdown.sounds", true)) return;
        long now = clock.getAsLong();
        if (now < session.nextCountdownSound) return;
        long interval = seconds <= 3 ? 250L : urgency(session) == 2 ? 500L : 1000L;
        session.nextCountdownSound = now + interval;
        session.player.playSound(session.player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.5F,
                urgency(session) == 2 ? (seconds <= 3 ? 1.8F : 1.4F) : 1.0F);
    }

    @Override public void forceClose(Player player) {
        Session session = sessions.remove(player.getUniqueId());
        if (session == null) return;
        if (session.bar != null) session.bar.removeAll();
        if (session.editorInventory != null) session.editorInventory.clear();
        session.input.clear();
        session.pendingText = null;
        if (dialogs != null && session.nativeScreen) dialogs.close(player);
        if (session.inventory != null && player.isOnline() && player.getOpenInventory().getTopInventory() == session.inventory) player.closeInventory();
    }
    @Override public void cleanupSession(Player player) { forceClose(player); }
    @Override public void shutdown() {
        ticker.cancel();
        for (Session session : new ArrayList<>(sessions.values())) forceClose(session.player);
        headTemplates.clear();
    }

    private static final class Session {
        final Player player;
        final LoginInput input;
        boolean nativeScreen;
        final Map<Integer, String> actions = new HashMap<>();
        Inventory inventory;
        Inventory editorInventory;
        BossBar bar;
        long deadline;
        long revision;
        long feedbackUntil;
        long feedbackStarted;
        long nextCountdownSound;
        int feedbackFrame;
        int urgency;
        int lastSeconds = -1;
        int lastBarSeconds = -1;
        int page;
        boolean keyboard;
        boolean editing;
        boolean help;
        boolean options;
        boolean uppercase;
        boolean symbols;
        boolean switching;
        boolean checking;
        String pendingText;
        String status = "screen-ready";
        String lastBarStatus;
        AuthManager.AuthResult lastBarFeedback;
        AuthManager.AuthResult feedback;
        Session(Player player, LoginInput input, boolean nativeScreen) {
            this.player = player;
            this.input = input;
            this.nativeScreen = nativeScreen;
        }
    }
}
