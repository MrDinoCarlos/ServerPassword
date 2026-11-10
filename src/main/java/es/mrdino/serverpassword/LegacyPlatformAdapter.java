package es.mrdino.serverpassword;

import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
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

    @Override public void setLore(ItemMeta meta, java.util.List<String> lore) { meta.setLore(lore); }
    @Override public org.bukkit.inventory.InventoryView openTextEditor(Player player) {
        return player.openAnvil(null, true);
    }
    @Override public String anvilText(Player player, Inventory inventory) {
        return ((org.bukkit.inventory.AnvilInventory) inventory).getRenameText();
    }
    @Override public void freeAnvil(Player player, Inventory inventory) {
        ((org.bukkit.inventory.AnvilInventory) inventory).setRepairCost(0);
        ((org.bukkit.inventory.AnvilInventory) inventory).setMaximumRepairCost(Integer.MAX_VALUE);
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
        var textures = profile.getTextures();
        textures.setSkin(textureUrl);
        // Commit the texture data explicitly before assigning the head's owner profile.
        profile.setTextures(textures);
        meta.setOwnerProfile(profile);
    }

    @Override
    public Listener chatListener(LockdownManager lock) {
        return new Listener() {
            @EventHandler(priority = EventPriority.LOWEST)
            public void onChat(AsyncPlayerChatEvent event) {
                if (lock.isLocked(event.getPlayer())) {
                    event.setCancelled(true);
                    event.setMessage("");
                    try { event.getRecipients().clear(); }
                    catch (UnsupportedOperationException immutableRecipients) { /* Cancellation still blocks broadcast. */ }
                }
            }
        };
    }
}
