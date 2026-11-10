package es.mrdino.serverpassword;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;

/** An unset password admits administrators for setup and denies normal logins. */
final class SetupManager {
    private final JavaPlugin plugin;
    private final AuthManager auth;
    private final Lang lang;

    SetupManager(JavaPlugin plugin, AuthManager auth, Lang lang) {
        this.plugin = plugin;
        this.auth = auth;
        this.lang = lang;
    }

    void warnConsole() {
        if (!auth.isPasswordConfigured()) warn(plugin.getServer().getConsoleSender());
    }

    void onJoin(Player player) {
        if (auth.isPasswordConfigured()) return;
        if (!auth.isAuthed(player)) {
            PlatformCompat.kick(player, lang.tr(player, "setup-wait"));
            return;
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && !auth.isPasswordConfigured() && auth.isAuthed(player)) warn(player);
        }, 20L);
    }

    void onConfigurationChanged() {
        if (auth.isPasswordConfigured()) return;
        warnConsole();
        for (Player player : plugin.getServer().getOnlinePlayers()) onJoin(player);
    }

    private void warn(CommandSender sender) {
        Map<String, String> values = Map.of("digits", String.valueOf(plugin.getConfig().getInt("password-policy.pin-length", 4)));
        sender.sendMessage(lang.msg(sender, "setup-warning"));
        if (sender instanceof Player)
            sender.sendMessage(lang.msg(sender, LoginSettings.pin(plugin.getConfig()) ? "secure-setup-pin" : "secure-setup-text", values));
        else sender.sendMessage(lang.msg(sender, "secure-setup-console"));
        sender.sendMessage(lang.msg(sender, "setup-policy"));
    }
}
