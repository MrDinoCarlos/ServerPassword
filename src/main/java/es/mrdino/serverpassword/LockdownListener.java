package es.mrdino.serverpassword;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerMoveEvent;

public class LockdownListener implements Listener {
    private final LockdownManager lock;

    public LockdownListener(LockdownManager lock) {
        this.lock = lock;
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        var p = e.getPlayer();
        if (!lock.isLocked(p)) return;

        // Permite mirar (yaw/pitch), pero no moverse de bloque
        if (e.getFrom().getBlockX() == e.getTo().getBlockX()
                && e.getFrom().getBlockY() == e.getTo().getBlockY()
                && e.getFrom().getBlockZ() == e.getTo().getBlockZ()) {
            return;
        }

        e.setTo(e.getFrom());
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent e) {
        if (lock.isLocked(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onCmd(PlayerCommandPreprocessEvent e) {
        if (lock.isLocked(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler public void onBreak(BlockBreakEvent e){ if(lock.isLocked(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void onPlace(BlockPlaceEvent e){ if(lock.isLocked(e.getPlayer())) e.setCancelled(true); }

    @EventHandler
    public void onDamage(EntityDamageEvent e){
        if (e.getEntity() instanceof org.bukkit.entity.Player p && lock.isLocked(p)) e.setCancelled(true);
    }

    @EventHandler
    public void onDamageByEntity(org.bukkit.event.entity.EntityDamageByEntityEvent e) {
        if (e.getEntity() instanceof org.bukkit.entity.Player p && lock.isLocked(p)) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onTarget(org.bukkit.event.entity.EntityTargetEvent e) {
        if (e.getTarget() instanceof org.bukkit.entity.Player p && lock.isLocked(p)) {
            e.setCancelled(true);
        }
    }

}
