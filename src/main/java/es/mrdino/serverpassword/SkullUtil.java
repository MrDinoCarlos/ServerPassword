package es.mrdino.serverpassword;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

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
            String value = base64OrUrl.trim();
            String textureProperty = value;
            String url;

            if (value.startsWith("http://") || value.startsWith("https://")) {
                url = URI.create(value).toASCIIString();
                String propertyJson = "{\"textures\":{\"SKIN\":{\"url\":\"" + url + "\"}}}";
                textureProperty = Base64.getEncoder().encodeToString(propertyJson.getBytes(StandardCharsets.UTF_8));
            } else {
                String decoded = new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
                url = extractTextureUrl(decoded);
            }

            PlatformCompat.applySkullTexture(meta, textureProperty, URI.create(url).toURL());
            head.setItemMeta(meta);
        } catch (Exception ex) {
            // Si algo falla, dejamos la cabeza normal
            Bukkit.getLogger().warning("[ServerPassword] Could not apply head texture: " + ex.getMessage());
        }

        return head;
    }

    private static String extractTextureUrl(String propertyJson) {
        int field = propertyJson.indexOf("\"url\":\"");
        if (field < 0) {
            throw new IllegalArgumentException("Texture property does not contain a URL");
        }

        int start = field + "\"url\":\"".length();
        int end = propertyJson.indexOf('"', start);
        if (end <= start) {
            throw new IllegalArgumentException("Texture property contains an invalid URL");
        }
        return propertyJson.substring(start, end);
    }
}
