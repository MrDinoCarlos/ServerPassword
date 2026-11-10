import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class AuthProtectionProbe extends JavaPlugin {
    @Override public void onEnable() {
        if (!"127.0.0.1".equals(getServer().getIp()) || !"local-smoke".equals(System.getProperty("perplayerprofiles.integration")))
            throw new IllegalStateException("Only an isolated local fixture may run this probe");
        getServer().getScheduler().runTaskLater(this, () -> {
            try {
                Object plugin = Bukkit.getPluginManager().getPlugin("ServerPassword");
                var managerField = plugin.getClass().getDeclaredField("lockdownManager"); managerField.setAccessible(true);
                Object manager = managerField.get(plugin);
                Method capture = manager.getClass().getDeclaredMethod("captureState", Player.class, boolean.class); capture.setAccessible(true);
                Method restore = manager.getClass().getDeclaredMethod("restoreState", Player.class); restore.setAccessible(true);
                var previous = manager.getClass().getDeclaredField("previousState"); previous.setAccessible(true);
                for (String scenario : new String[]{"loading", "creative", "hurt-frames", "external-invulnerability", "normal"}) {
                    Object handle = createPlayer();
                    Player player = (Player) handle.getClass().getMethod("getBukkitEntity").invoke(handle);
                    Object listener = handle.getClass().getField("connection").get(handle);
                    var timeout = listener.getClass().getDeclaredField("clientLoadedTimeoutTimer"); timeout.setAccessible(true);
                    timeout.setInt(listener, scenario.equals("loading") ? 60 : 0);
                    Object abilities = handle.getClass().getMethod("getAbilities").invoke(handle);
                    abilities.getClass().getField("invulnerable").setBoolean(abilities, scenario.equals("creative"));
                    player.setNoDamageTicks(scenario.equals("hurt-frames") ? 10 : 0);
                    player.setGameMode(scenario.equals("creative") ? org.bukkit.GameMode.CREATIVE : org.bukkit.GameMode.SURVIVAL);
                    player.setInvulnerable(scenario.equals("external-invulnerability"));
                    Object captured = capture.invoke(manager, player, true);
                    var value = captured.getClass().getDeclaredMethod("invulnerable"); value.setAccessible(true);
                    boolean saved = (Boolean) value.invoke(captured);
                    ((Map<UUID,Object>) previous.get(manager)).put(player.getUniqueId(), captured);
                    player.setInvulnerable(true);
                    restore.invoke(manager, player);
                    player.setNoDamageTicks(0);
                    abilities.getClass().getField("invulnerable").setBoolean(abilities, false);
                    player.setGameMode(org.bukkit.GameMode.SURVIVAL);
                    timeout.setInt(listener, 0);
                    boolean permanent = (Boolean) handle.getClass().getMethod("isPermanentlyInvulnerable").invoke(handle);
                    boolean expected = scenario.equals("external-invulnerability");
                    boolean attackable = (Boolean) handle.getClass().getMethod("canBeSeenAsEnemy").invoke(handle);
                    if (permanent != expected || attackable == expected)
                        throw new AssertionError("Protection or targetability leaked in " + scenario);
                    getLogger().info("PROTECTION PASS " + scenario + " permanentAfterUnlock=" + permanent + " attackable=" + attackable);
                    if (scenario.equals("normal")) checkRepair((JavaPlugin) plugin, player, handle);
                }
            } catch (Exception failure) { getLogger().log(java.util.logging.Level.SEVERE, "PROTECTION PROBE FAILED", failure); }
            finally { if (!Boolean.getBoolean("serverpassword.probe.keep-running")) Bukkit.shutdown(); }
        }, 80L);
    }

    private void checkRepair(JavaPlugin plugin, Player player, Object handle) throws Exception {
        var attachment = player.addAttachment(this, "serverpassword.admin", true);
        try {
            player.setInvulnerable(true);
            plugin.getCommand("spass").execute(player, "spass", new String[]{"repairprotection"});
            if (!(Boolean) handle.getClass().getMethod("isPermanentlyInvulnerable").invoke(handle))
                throw new AssertionError("Protection repair bypassed authentication");
            var field = plugin.getClass().getDeclaredField("authManager"); field.setAccessible(true);
            Object auth = field.get(plugin);
            auth.getClass().getMethod("markAuthed", Player.class).invoke(auth, player);
            plugin.getCommand("spass").execute(player, "spass", new String[]{"repairprotection"});
            if ((Boolean) handle.getClass().getMethod("isPermanentlyInvulnerable").invoke(handle))
                throw new AssertionError("Protection repair did not clear the stale flag");
            auth.getClass().getMethod("clearSession", Player.class).invoke(auth, player);
            getLogger().info("PROTECTION REPAIR PASS authenticated-only command clears an existing stale flag");
        } finally { player.removeAttachment(attachment); }
    }

    private Object createPlayer() throws Exception {
        Object server = Bukkit.getServer().getClass().getMethod("getServer").invoke(Bukkit.getServer());
        Object world = Bukkit.getWorlds().get(0).getClass().getMethod("getHandle").invoke(Bukkit.getWorlds().get(0));
        Class<?> profileType = Class.forName("com.mojang.authlib.GameProfile");
        Object profile = profileType.getConstructor(UUID.class, String.class).newInstance(UUID.randomUUID(), "ProtectionProbe");
        Class<?> information = Class.forName("net.minecraft.server.level.ClientInformation");
        Class<?> playerType = Class.forName("net.minecraft.server.level.ServerPlayer");
        Object player = playerType.getConstructor(Class.forName("net.minecraft.server.MinecraftServer"), Class.forName("net.minecraft.server.level.ServerLevel"), profileType, information)
                .newInstance(server, world, profile, information.getMethod("createDefault").invoke(null));
        Class<?> flowType = Class.forName("net.minecraft.network.protocol.PacketFlow");
        Class<?> connectionType = Class.forName("net.minecraft.network.Connection");
        Object connection = connectionType.getConstructor(flowType).newInstance(Enum.valueOf((Class) flowType, "SERVERBOUND"));
        Class<?> cookieType = Class.forName("net.minecraft.server.network.CommonListenerCookie");
        Object cookie = cookieType.getMethod("createInitial", profileType, boolean.class).invoke(null, profile, false);
        Object listener = Class.forName("net.minecraft.server.network.ServerGamePacketListenerImpl")
                .getConstructor(Class.forName("net.minecraft.server.MinecraftServer"), connectionType, playerType, cookieType)
                .newInstance(server, connection, player, cookie);
        playerType.getField("connection").set(player, listener);
        return player;
    }
}
