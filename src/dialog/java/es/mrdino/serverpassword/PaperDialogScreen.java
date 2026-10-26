package es.mrdino.serverpassword;

import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

/** Compiled separately: never loaded by a client/server combination without dialog support. */
public final class PaperDialogScreen implements NativeLoginScreen, Listener {
    private static final LegacyComponentSerializer TEXT = LegacyComponentSerializer.legacySection();
    private final JavaPlugin plugin;
    private final Map<UUID, Binding> bindings = new HashMap<>();

    public PaperDialogScreen(JavaPlugin plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @Override public boolean supports(Player player) {
        int protocol = player.getProtocolVersion();
        // ViaVersion on the backend exposes the actual version instead of the translated one.
        org.bukkit.plugin.Plugin via = plugin.getServer().getPluginManager().getPlugin("ViaVersion");
        if (via != null && via.isEnabled()) {
            try {
                ClassLoader loader = via.getClass().getClassLoader();
                Object api = Class.forName("com.viaversion.viaversion.api.Via", true, loader).getMethod("getAPI").invoke(null);
                Class<?> apiType = Class.forName("com.viaversion.viaversion.api.ViaAPI", true, loader);
                protocol = (int) apiType.getMethod("getPlayerVersion", UUID.class).invoke(api, player.getUniqueId());
            } catch (ReflectiveOperationException | LinkageError ignored) {
                // Conservative fallback for installations with an unknown ViaVersion API.
                return false;
            }
        }
        return protocol >= 771; // Java 1.21.6: the first protocol with dialogs.
    }

    @Override public void show(Player player, View view, BiConsumer<String, String> handler) {
        Binding binding = bindings.get(player.getUniqueId());
        if (binding == null || binding.revision != view.revision()) {
            binding = new Binding(view.revision(), UUID.randomUUID().toString(), view, handler);
            bindings.put(player.getUniqueId(), binding);
        }
        var type = createType(view, binding.token);
        player.showDialog(Dialog.create(builder -> builder.empty().base(createBase(view))
                .type(type)));
    }

    static DialogType createType(View view, String token) {
        List<ActionButton> buttons = new ArrayList<>();
        for (int index = 0; index < view.buttons().size(); index++)
            buttons.add(actionButton(view.buttons().get(index), view.layout().buttonWidth(), token, index));
        ActionButton primary = view.primary() == null ? null : actionButton(view.primary(),
                Math.max(100, view.layout().columns() * view.layout().buttonWidth() + (view.layout().columns() - 1) * 2),
                token, view.buttons().size());
        // Paper rejects an empty multi-action list, even when the footer has an action.
        if (buttons.isEmpty()) return primary == null ? DialogType.notice() : DialogType.notice(primary);
        return DialogType.multiAction(buttons, primary, view.layout().columns());
    }

    private static ActionButton actionButton(Button button, int width, String token, int index) {
        return ActionButton.create(TEXT.deserialize(button.label()),
                button.tooltip() == null ? null : TEXT.deserialize(button.tooltip()), width,
                "NONE".equals(button.action()) ? null : DialogAction.customClick(Key.key("serverpassword", token + "/" + index), null));
    }

    static DialogBase createBase(View view) {
        var base = DialogBase.builder(TEXT.deserialize(view.title()))
                .canCloseWithEscape(false)
                .pause(false)
                .afterAction(DialogBase.DialogAfterAction.NONE)
                // One text widget keeps line spacing compact; separate bodies add large client margins.
                .body(List.of(DialogBody.plainMessage(TEXT.deserialize(String.join("\n", view.lines())), view.layout().bodyWidth())));
        if (view.textEntry()) {
            base.inputs(List.of(DialogInput.text("password", Component.empty()).labelVisible(false)
                    .initial(view.initialText()).maxLength(view.maxLength()).width(view.layout().bodyWidth()).build()));
        }
        return base.build();
    }

    @EventHandler public void onCustomClick(PlayerCustomClickEvent event) {
        if (!event.getIdentifier().namespace().equals("serverpassword")) return;
        if (!(event.getCommonConnection() instanceof PlayerGameConnection connection)) return;
        Player player = connection.getPlayer();
        Binding binding = bindings.get(player.getUniqueId());
        if (binding == null) return;
        String identifier = event.getIdentifier().value();
        if (!identifier.startsWith(binding.token + "/")) return;
        int index;
        try { index = Integer.parseInt(identifier.substring(binding.token.length() + 1)); }
        catch (NumberFormatException ignored) { return; }
        if (index < 0 || index > binding.view.buttons().size()) return;
        Button selected = index == binding.view.buttons().size() ? binding.view.primary() : binding.view.buttons().get(index);
        if (selected == null || "NONE".equals(selected.action())) return;
        String value = binding.view.textEntry() && event.getDialogResponseView() != null
                ? event.getDialogResponseView().getText("password") : null;
        String action = selected.action();
        // Actions always execute on the server thread and remain tied to this exact screen.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && bindings.get(player.getUniqueId()) == binding)
                binding.handler.accept(action, value);
        });
    }

    @Override public void close(Player player) {
        if (bindings.remove(player.getUniqueId()) != null && player.isOnline()) player.closeInventory();
    }

    private record Binding(long revision, String token, View view, BiConsumer<String, String> handler) { }
}
