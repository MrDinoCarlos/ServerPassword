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

interface PlatformAdapter {
    Inventory createInventory(InventoryHolder holder, int size, String title);

    void setDisplayName(ItemMeta meta, String name);

    void setLore(ItemMeta meta, java.util.List<String> lore);
    org.bukkit.inventory.InventoryView openTextEditor(Player player);
    String anvilText(Player player, Inventory inventory);
    void freeAnvil(Player player, Inventory inventory);

    void kick(Player player, String message);

    void sendTitle(Player player, String title, String subtitle, int fadeInTicks, int stayTicks, int fadeOutTicks);

    void broadcast(Server server, String message);

    void suppressJoinMessage(PlayerJoinEvent event);

    void suppressQuitMessage(PlayerQuitEvent event);

    void banIp(InetAddress address, String reason, Date expires);

    void applySkullTexture(SkullMeta meta, String textureProperty, URL textureUrl);

    Listener chatListener(LockdownManager lock);
}
