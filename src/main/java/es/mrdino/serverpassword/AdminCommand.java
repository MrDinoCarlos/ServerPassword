package es.mrdino.serverpassword;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

public class AdminCommand implements CommandExecutor {
    private final JavaPlugin plugin;
    private final AuthManager auth;
    private final Lang lang;

    public AdminCommand(JavaPlugin plugin, AuthManager auth, Lang lang) {
        this.plugin = plugin;
        this.auth = auth;
        this.lang = lang;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        if (!sender.hasPermission("serverpassword.admin")) {
            sender.sendMessage(lang.msg(sender, "admin-no-permission"));
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sender.sendMessage(lang.msg(sender, "admin-help-1"));
            sender.sendMessage(lang.msg(sender, "admin-help-2"));
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            plugin.reloadConfig();
            sender.sendMessage(lang.msg(sender, "admin-reloaded"));
            return true;
        }

        if (args[0].equalsIgnoreCase("set") && args.length >= 2) {
            plugin.getConfig().set("password", args[1]);
            plugin.saveConfig();
            sender.sendMessage(lang.msg(sender, "admin-password-set"));
            return true;
        }

        sender.sendMessage(lang.msg(sender, "admin-unknown-subcommand"));
        return true;
    }
}
