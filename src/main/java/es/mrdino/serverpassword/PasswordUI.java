package es.mrdino.serverpassword;

import org.bukkit.entity.Player;

interface PasswordUI {
    void open(Player player);
    void forceClose(Player player);
    void cleanupSession(Player player);
    void shutdown();
    default boolean handleChat(Player player, String message) { return false; }
}
