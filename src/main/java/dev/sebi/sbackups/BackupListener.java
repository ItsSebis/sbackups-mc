package dev.sebi.sbackups;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

final class BackupListener implements Listener {
    private final BackupService service;

    BackupListener(BackupService service) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        service.playersChanged(p.getUniqueId());
        if (service.store().hasPending(p.getUniqueId())) {
            service.applyPendingMain(p);
        } else if (service.settings().onJoin()) {
            service.snapshotMain(p, Reason.JOIN);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    void onQuit(PlayerQuitEvent e) {
        service.playersChanged(e.getPlayer().getUniqueId());
        if (service.settings().onQuit()) service.snapshotMain(e.getPlayer(), Reason.QUIT);
    }

    /** At MONITOR the inventory is still full: drops are only removed after the event. */
    @EventHandler(priority = EventPriority.MONITOR)
    void onDeath(PlayerDeathEvent e) {
        if (service.settings().onDeath()) service.snapshotMain(e.getPlayer(), Reason.DEATH);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    void onWorldChange(PlayerChangedWorldEvent e) {
        if (service.settings().onWorldChange()) service.snapshotMain(e.getPlayer(), Reason.WORLD_CHANGE);
    }
}
