package es.mrdino.serverpassword;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import io.papermc.paper.ban.BanListType;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.ban.IpBanList;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.UUID;

final class PaperPlatformAdapter implements PlatformAdapter {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private static Component component(String legacyText) {
        return LEGACY.deserialize(legacyText == null ? "" : legacyText);
    }

    @Override
    public Inventory createInventory(InventoryHolder holder, int size, String title) {
        return Bukkit.createInventory(holder, size, component(title));
    }

    @Override
    public void setDisplayName(ItemMeta meta, String name) {
        meta.displayName(component(name));
    }

    @Override
    public void kick(Player player, String message) {
        player.kick(component(message));
    }

    @Override
    public void sendTitle(Player player, String title, String subtitle, int fadeInTicks, int stayTicks, int fadeOutTicks) {
        Title.Times times = Title.Times.times(
                Duration.ofMillis(fadeInTicks * 50L),
                Duration.ofMillis(stayTicks * 50L),
                Duration.ofMillis(fadeOutTicks * 50L)
        );
        player.showTitle(Title.title(component(title), component(subtitle), times));
    }

    @Override
    public void broadcast(Server server, String message) {
        server.broadcast(component(message));
    }

    @Override
    public void suppressJoinMessage(PlayerJoinEvent event) {
        event.joinMessage(null);
    }

    @Override
    public void suppressQuitMessage(PlayerQuitEvent event) {
        event.quitMessage(null);
    }

    @Override
    public void banIp(InetAddress address, String reason, Date expires) {
        IpBanList banList = Bukkit.getBanList(BanListType.IP);
        banList.addBan(address, reason, expires, null);
    }

    @Override
    public void applySkullTexture(SkullMeta meta, String textureProperty, URL textureUrl) {
        UUID profileId = UUID.nameUUIDFromBytes(textureProperty.getBytes(StandardCharsets.UTF_8));
        PlayerProfile profile = Bukkit.createProfile(profileId, "ServerPassword");
        profile.setProperty(new ProfileProperty("textures", textureProperty));
        meta.setPlayerProfile(profile);
    }

    @Override
    public Listener chatListener(LockdownManager lock) {
        return new Listener() {
            @EventHandler
            public void onChat(AsyncChatEvent event) {
                if (lock.isLocked(event.getPlayer())) {
                    event.setCancelled(true);
                }
            }
        };
    }
}
