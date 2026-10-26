package es.mrdino.serverpassword;

import org.bukkit.command.Command;
import org.bukkit.command.TabExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.List;

public class ServerPassCommand implements TabExecutor {
    private final JavaPlugin plugin;
    private final AuthManager auth;
    private final LockdownManager lock;
    private final Lang lang;

    public ServerPassCommand(JavaPlugin plugin, AuthManager auth, LockdownManager lock, Lang lang) {
        this.plugin = plugin;
        this.auth = auth;
        this.lock = lock;
        this.lang = lang;
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        // Never expose passwords or Bukkit's default player-name suggestions.
        return List.of();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) return true;

        if (!auth.isPasswordConfigured()) {
            p.sendMessage(lang.msg(p, p.hasPermission("serverpassword.admin") ? "setup-warning" : "setup-wait"));
            if (p.hasPermission("serverpassword.admin")) p.sendMessage(lang.msg(p, "admin-help-1"));
            return true;
        }

        if (args.length < 1) {
            p.sendMessage(lang.msg(p, "serverpass-usage"));
            return true;
        }

        auth.tryPasswordAsync(p, String.join(" ", args), result -> {
        switch (result) {
            case SUCCESS -> {
                if (lock.isLocked(p)) {
                    lock.unlock(p);
                } else {
                    p.sendMessage(lang.msg(p, "success"));
                }
            }
            case COOLDOWN -> p.sendMessage(lang.msg(p, "cooldown", Map.of(
                    "seconds", String.valueOf(auth.cooldownSecondsLeft(p))
            )));
            case WRONG -> {
                int used = auth.getAttemptsThisJoin(p);
                int max = plugin.getConfig().getInt("max-attempts-per-join", 3);

                p.sendMessage(lang.msg(p, "wrong-password", Map.of(
                        "used", String.valueOf(used),
                        "max", String.valueOf(max)
                )));
            }
            case MAX_ATTEMPTS -> {
                String action = plugin.getConfig().getString("on-fail-action", "KICK");
                if ("KICK".equalsIgnoreCase(action)) {
                    PlatformCompat.kick(p, lang.tr(p, "kicked"));
                } else {
                    p.sendMessage(lang.msg(p, "kicked"));
                }
            }
            case BANNED -> PlatformCompat.kick(p, lang.tr(p, "banned"));
            case INVALID_INPUT -> p.sendMessage(lang.msg(p, "screen-invalid-input"));
        }
        });

        return true;
    }
}
