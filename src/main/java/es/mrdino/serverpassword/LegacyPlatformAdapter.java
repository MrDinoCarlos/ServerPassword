package es.mrdino.serverpassword;

import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;

import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

@SuppressWarnings("deprecation")
final class LegacyPlatformAdapter implements PlatformAdapter {
    @Override
    public Inventory createInventory(InventoryHolder holder, int size, String title) {
        return Bukkit.createInventory(holder, size, title);
    }

    @Override
    public void setDisplayName(ItemMeta meta, String name) {
        meta.setDisplayName(name);
    }

    @Override
    public void kick(Player player, String message) {
        player.kickPlayer(message);
    }

    @Override
    public void sendTitle(Player player, String title, String subtitle, int fadeInTicks, int stayTicks, int fadeOutTicks) {
        player.sendTitle(title, subtitle, fadeInTicks, stayTicks, fadeOutTicks);
    }

    @Override
    public void broadcast(Server server, String message) {
        server.broadcastMessage(message);
    }

    @Override
    public void suppressJoinMessage(PlayerJoinEvent event) {
        event.setJoinMessage(null);
    }

    @Override
    public void suppressQuitMessage(PlayerQuitEvent event) {
        event.setQuitMessage(null);
    }

    @Override
    public void banIp(InetAddress address, String reason, Date expires) {
        Bukkit.getBanList(BanList.Type.IP).addBan(address.getHostAddress(), reason, expires, null);
    }

    @Override
    public void applySkullTexture(SkullMeta meta, String textureProperty, URL textureUrl) {
        UUID profileId = UUID.nameUUIDFromBytes(textureProperty.getBytes(StandardCharsets.UTF_8));
        PlayerProfile profile = Bukkit.createPlayerProfile(profileId, "ServerPassword");
        profile.getTextures().setSkin(textureUrl);
        meta.setOwnerProfile(profile);
    }

    @Override
    public Listener chatListener(LockdownManager lock) {
        return new Listener() {
            @EventHandler
            public void onChat(AsyncPlayerChatEvent event) {
                if (lock.isLocked(event.getPlayer())) {
                    event.setCancelled(true);
                }
            }
        };
    }
}
