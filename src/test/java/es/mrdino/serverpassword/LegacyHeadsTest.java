package es.mrdino.serverpassword;

import org.bukkit.Bukkit;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LegacyHeadsTest {
    @Test void originalHeadTexturesAreCommittedToTheOwnerProfileOnOlderVersions() throws Exception {
        PlayerProfile profile = mock(PlayerProfile.class);
        PlayerTextures textures = mock(PlayerTextures.class);
        SkullMeta meta = mock(SkullMeta.class);
        when(profile.getTextures()).thenReturn(textures);
        var url = URI.create("https://textures.minecraft.net/texture/24581d3959ac513b495349c4db2a36aa336c4e5a71e3f7d67ff66d3dfabd9da23").toURL();
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.createPlayerProfile(any(UUID.class), eq("ServerPassword"))).thenReturn(profile);
            new LegacyPlatformAdapter().applySkullTexture(meta, "original texture property", url);
        }
        var order = inOrder(textures, profile, meta);
        order.verify(textures).setSkin(url);
        order.verify(profile).setTextures(textures);
        order.verify(meta).setOwnerProfile(profile);
    }

    @Test void legacyOpenAndEventsCannotTakeOverModernLogin() {
        var plugin = mock(org.bukkit.plugin.java.JavaPlugin.class);
        when(plugin.getConfig()).thenReturn(PasswordSecurityTest.defaults());
        var ui = new CodePasswordUI(plugin, mock(AuthManager.class), mock(LockdownManager.class), mock(Lang.class));
        var player = mock(org.bukkit.entity.Player.class);
        var click = mock(org.bukkit.event.inventory.InventoryClickEvent.class);
        var close = mock(org.bukkit.event.inventory.InventoryCloseEvent.class);
        ui.open(player);
        ui.onClick(click);
        ui.onClose(close);
        verifyNoInteractions(player, click, close);
    }
}
