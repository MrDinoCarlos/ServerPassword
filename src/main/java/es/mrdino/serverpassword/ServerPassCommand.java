package es.mrdino.serverpassword;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Map;

public class ServerPassCommand implements CommandExecutor {
    private final AuthManager auth;
    private final LockdownManager lock;
    private final Lang lang;

    public ServerPassCommand(AuthManager auth, LockdownManager lock, Lang lang) {
        this.auth = auth;
        this.lock = lock;
        this.lang = lang;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) return true;

        if (args.length < 1) {
            p.sendMessage(lang.msg(p, "serverpass-usage"));
            return true;
        }

        var result = auth.tryPassword(p, args[0]);

        switch (result) {
            case SUCCESS -> lock.unlock(p);
            case COOLDOWN -> p.sendMessage(lang.msg(p, "cooldown", Map.of(
                    "seconds", String.valueOf(auth.cooldownSecondsLeft(p))
            )));
            case WRONG -> {
                int used = auth.getAttemptsThisJoin(p);
                int max = p.getServer().getPluginManager().getPlugin("ServerPassword") != null
                        ? p.getServer().getPluginManager().getPlugin("ServerPassword").getConfig().getInt("max-attempts-per-join", 3)
                        : 3;

                p.sendMessage(lang.msg(p, "wrong-password", Map.of(
                        "used", String.valueOf(used),
                        "max", String.valueOf(max)
                )));
            }
            case MAX_ATTEMPTS -> p.sendMessage(lang.msg(p, "kicked"));
            case BANNED -> p.sendMessage(lang.msg(p, "banned"));
        }

        return true;
    }
}
