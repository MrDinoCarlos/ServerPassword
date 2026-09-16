package es.mrdino.serverpassword;

import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.net.InetAddress;
import java.net.URL;
import java.util.Date;

final class PlatformCompat {
    private static final PlatformAdapter ADAPTER = createAdapter();

    private PlatformCompat() {
    }

    private static PlatformAdapter createAdapter() {
        try {
            Class.forName("io.papermc.paper.event.player.AsyncChatEvent", false, PlatformCompat.class.getClassLoader());
            Class<?> adapterClass = Class.forName(
                    "es.mrdino.serverpassword.PaperPlatformAdapter",
                    true,
                    PlatformCompat.class.getClassLoader()
            );
            return (PlatformAdapter) adapterClass.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return new LegacyPlatformAdapter();
        }
    }

    static Inventory createInventory(InventoryHolder holder, int size, String title) {
        return ADAPTER.createInventory(holder, size, title);
    }

    static void setDisplayName(ItemMeta meta, String name) {
        ADAPTER.setDisplayName(meta, name);
    }

    static void kick(Player player, String message) {
        ADAPTER.kick(player, message);
    }

    static void sendTitle(Player player, String title, String subtitle, int fadeInTicks, int stayTicks, int fadeOutTicks) {
        ADAPTER.sendTitle(player, title, subtitle, fadeInTicks, stayTicks, fadeOutTicks);
    }

    static void broadcast(Server server, String message) {
        ADAPTER.broadcast(server, message);
    }

    static void suppressJoinMessage(PlayerJoinEvent event) {
        ADAPTER.suppressJoinMessage(event);
    }

    static void suppressQuitMessage(PlayerQuitEvent event) {
        ADAPTER.suppressQuitMessage(event);
    }

    static void banIp(InetAddress address, String reason, Date expires) {
        ADAPTER.banIp(address, reason, expires);
    }

    static void applySkullTexture(SkullMeta meta, String textureProperty, URL textureUrl) {
        ADAPTER.applySkullTexture(meta, textureProperty, textureUrl);
    }

    static Listener chatListener(LockdownManager lock) {
        return ADAPTER.chatListener(lock);
    }
}
