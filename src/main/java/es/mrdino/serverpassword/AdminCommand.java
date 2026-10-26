package es.mrdino.serverpassword;

import org.bukkit.command.Command;
import org.bukkit.command.TabExecutor;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.IntStream;

public class AdminCommand implements TabExecutor {
    private static final List<String> COMMANDS = List.of("help", "settings", "set", "mode", "length", "max-length",
            "gui", "presentation", "input", "timeout", "attempts", "cooldown", "remember", "notify-admins", "reload");
    private final ServerPassPlugin plugin;
    private final Lang lang;

    public AdminCommand(ServerPassPlugin plugin, Lang lang) {
        this.plugin = plugin;
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
            sender.sendMessage(lang.msg(sender, "admin-help-policy"));
            sender.sendMessage(lang.msg(sender, "admin-help-interface"));
            sender.sendMessage(lang.msg(sender, "admin-help-limits"));
            return true;
        }

        String subcommand = args[0].toLowerCase(Locale.ROOT);
        if (subcommand.equals("settings")) {
            var config = plugin.getConfig();
            sender.sendMessage(lang.msg(sender, "admin-settings-policy", Map.of(
                    "mode", config.getString("password-policy.mode", "PIN"),
                    "length", String.valueOf(config.getInt("password-policy.pin-length", 4)),
                    "max", String.valueOf(config.getInt("modern-gui.max-length", 32)),
                    "active", !PasswordCredential.read(config).configured() ? "UNSET" : config.getBoolean("password.numeric") ? "PIN" : "TEXT")));
            sender.sendMessage(lang.msg(sender, "admin-settings-interface", Map.of(
                    "gui", config.getString("input-mode", "MODERN"),
                    "presentation", config.getString("modern-gui.presentation", "AUTO"),
                    "input", config.getString("modern-gui.input", "AUTO"),
                    "timeout", config.getBoolean("timeout.enabled", true) ? String.valueOf(config.getInt("timeout.seconds", 30)) : "OFF")));
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            var errors = plugin.reloadSettings();
            if (errors.isEmpty()) sender.sendMessage(lang.msg(sender, "admin-reloaded"));
            else {
                sender.sendMessage(lang.msg(sender, "admin-invalid-config"));
                errors.forEach(error -> sender.sendMessage("§c" + error));
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("set") && args.length >= 2) {
            String password = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
            plugin.changePassword(password, errors -> {
                if (errors.isEmpty()) sender.sendMessage(lang.msg(sender, "admin-password-set"));
                else {
                    sender.sendMessage(lang.msg(sender, "admin-invalid-config"));
                    errors.forEach(error -> sender.sendMessage("§c" + error));
                }
            });
            return true;
        }

        if (COMMANDS.contains(subcommand) && !List.of("help", "reload", "set").contains(subcommand)) {
            if (args.length != 2) { sender.sendMessage(lang.msg(sender, "admin-setting-usage", Map.of("setting", subcommand))); return true; }
            try {
                Map<String, Object> changes = settings(subcommand, args[1]);
                var errors = plugin.changeSettings(changes);
                if (!errors.isEmpty()) {
                    sender.sendMessage(lang.msg(sender, "admin-invalid-config"));
                    errors.forEach(error -> sender.sendMessage("§c" + error));
                } else {
                    sender.sendMessage(lang.msg(sender, "admin-setting-updated", Map.of("setting", subcommand, "value", args[1].toUpperCase(Locale.ROOT))));
                    if (subcommand.equals("mode") || subcommand.equals("length") && LoginSettings.pin(plugin.getConfig()))
                        sender.sendMessage(lang.msg(sender, "admin-policy-next-password"));
                }
            } catch (IllegalArgumentException invalid) {
                sender.sendMessage(lang.msg(sender, "admin-setting-invalid", Map.of("setting", subcommand)));
                sender.sendMessage("§c" + invalid.getMessage());
            }
            return true;
        }

        sender.sendMessage(lang.msg(sender, "admin-unknown-subcommand"));
        return true;
    }

    private Map<String, Object> settings(String command, String argument) {
        return switch (command) {
            case "mode" -> Map.of("password-policy.mode", choice(argument, "PIN", "TEXT"));
            case "length" -> LoginSettings.pin(plugin.getConfig())
                    ? Map.of("password-policy.pin-length", number(argument, 1, 9))
                    : Map.of("modern-gui.max-length", number(argument, 1, 128));
            case "max-length" -> Map.of("modern-gui.max-length", number(argument, 1, 128));
            case "gui" -> Map.of("input-mode", choice(argument, "MODERN", "LEGACY"));
            case "presentation" -> Map.of("modern-gui.presentation", choice(argument, "AUTO", "DIALOG", "INVENTORY"));
            case "input" -> Map.of("modern-gui.input", choice(argument, "AUTO", "NUMERIC", "TEXT"));
            case "timeout" -> argument.equalsIgnoreCase("off") ? Map.of("timeout.enabled", false)
                    : Map.of("timeout.enabled", true, "timeout.seconds", number(argument, 1, 86400));
            case "attempts" -> Map.of("max-attempts-per-join", number(argument, 1, Integer.MAX_VALUE));
            case "cooldown" -> Map.of("attempt-cooldown-seconds", number(argument, 0, Integer.MAX_VALUE));
            case "remember" -> Map.of("remember-session-minutes", number(argument, 0, Integer.MAX_VALUE));
            case "notify-admins" -> Map.of("updates.notify-admins", choice(argument, "ON", "OFF").equals("ON"));
            default -> throw new IllegalArgumentException("Use /spass help.");
        };
    }

    private static String choice(String value, String... choices) {
        for (String choice : choices) if (choice.equalsIgnoreCase(value)) return choice;
        throw new IllegalArgumentException("Allowed values: " + String.join(", ", choices));
    }

    private static int number(String value, int min, int max) {
        try {
            int number = Integer.parseInt(value);
            if (number >= min && number <= max) return number;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException("Enter a whole number between " + min + " and " + max + ".");
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("serverpassword.admin") || args.length == 0 || args.length > 2) return List.of();
        List<String> options = args.length == 1 ? COMMANDS : switch (args[0].toLowerCase(Locale.ROOT)) {
            case "mode" -> List.of("PIN", "TEXT");
            case "length" -> LoginSettings.pin(plugin.getConfig()) ? IntStream.rangeClosed(1, 9).mapToObj(String::valueOf).toList() : List.of("16", "32", "50", "64", "128");
            case "max-length" -> List.of("16", "32", "50", "64", "128");
            case "gui" -> List.of("MODERN", "LEGACY");
            case "presentation" -> List.of("AUTO", "DIALOG", "INVENTORY");
            case "input" -> List.of("AUTO", "NUMERIC", "TEXT");
            case "timeout" -> List.of("off", "15", "30", "60", "120");
            case "attempts" -> List.of("3", "5", "10");
            case "cooldown" -> List.of("0", "1", "2", "5");
            case "remember" -> List.of("0", "30", "60", "120");
            case "notify-admins" -> List.of("ON", "OFF");
            default -> List.of();
        };
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
