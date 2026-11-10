package es.mrdino.serverpassword;

import org.bukkit.command.Command;
import org.bukkit.command.TabExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import java.util.List;

public class ServerPassCommand implements TabExecutor {
    private final ServerPassPlugin plugin;
    private final Lang lang;

    public ServerPassCommand(ServerPassPlugin plugin, Lang lang) {
        this.plugin = plugin;
        this.lang = lang;
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        // Never expose passwords or Bukkit's default player-name suggestions.
        return List.of();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) return true;

        if (args.length != 0) {
            p.sendMessage(lang.msg(p, "secure-input-only"));
            return true;
        }
        plugin.openLogin(p);

        return true;
    }
}
