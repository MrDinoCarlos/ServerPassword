package es.mrdino.serverpassword;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;

import java.net.URL;
import java.util.Base64;
import java.util.UUID;

public final class SkullUtil {
    private SkullUtil() {}

    /**
     * Acepta:
     * - base64 JSON de textures (el típico "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5...")
     * - o una URL directa a textures.minecraft.net (opcional)
     *
     * Si no puede aplicarla, devuelve una cabeza normal.
     */
    public static ItemStack headFromTexture(String base64OrUrl) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);

        if (base64OrUrl == null || base64OrUrl.isBlank()) return head;

        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta == null) return head;

        try {
            String url = base64OrUrl;

            // Si parece base64, decodificamos y extraemos la URL
            if (!base64OrUrl.startsWith("http")) {
                String decoded = new String(Base64.getDecoder().decode(base64OrUrl));
                // buscamos el campo ... "url":"...."
                int i = decoded.indexOf("\"url\":\"");
                if (i >= 0) {
                    int start = i + "\"url\":\"".length();
                    int end = decoded.indexOf("\"", start);
                    if (end > start) url = decoded.substring(start, end);
                }
            }

            PlayerProfile profile = Bukkit.createPlayerProfile(UUID.randomUUID(), null);
            profile.getTextures().setSkin(new URL(url));
            meta.setOwnerProfile(profile);
            head.setItemMeta(meta);
        } catch (Exception ex) {
            // Si algo falla, dejamos la cabeza normal
            Bukkit.getLogger().warning("[ServerPassword] Could not apply head texture: " + ex.getMessage());
        }

        return head;
    }
}
