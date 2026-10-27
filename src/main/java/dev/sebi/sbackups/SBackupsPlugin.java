package dev.sebi.sbackups;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class SBackupsPlugin extends JavaPlugin {
    private ExecutorService io;
    private BackupService service;
    private WebServer web;
    private WorldBackups world;
    private static final int PER_TICK = 2;

    private BukkitTask periodic;
    private BukkitTask round;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        addMissingDefaults();
        ensureToken();

        io = Executors.newSingleThreadExecutor(r -> new Thread(r, "SBackups-io"));
        service = new BackupService(this, new BackupStore(getDataFolder().toPath().resolve("data"), getLogger()),
                io, Settings.from(getConfig()));

        world = new WorldBackups(service::settings, service.events(), getLogger());

        getServer().getPluginManager().registerEvents(new BackupListener(service), this);
        var command = new BackupCommand(this, service);
        getCommand("sbackup").setExecutor(command);
        getCommand("sbackup").setTabCompleter(command);

        start();
    }

    /** (Re)starts everything that depends on config values. */
    private void start() {
        Settings s = service.settings();
        if (s.periodicMinutes() > 0) {
            long ticks = s.periodicMinutes() * 60L * 20L;
            periodic = getServer().getScheduler().runTaskTimer(this, this::startRound, ticks, ticks);
        }
        if (s.webEnabled()) {
            try {
                web = new WebServer(service, world, s);
                getLogger().info("Admin web interface on http://" + s.webBind() + ":" + web.port());
            } catch (IOException e) {
                getLogger().severe("Could not start the web interface: " + e.getMessage());
            }
        }
    }

    /** Spreads a periodic round over several ticks (PER_TICK players each) instead of one big tick. */
    private void startRound() {
        if (round != null) round.cancel();
        Deque<UUID> queue = new ArrayDeque<>();
        for (Player p : getServer().getOnlinePlayers()) queue.add(p.getUniqueId());
        BukkitTask[] self = new BukkitTask[1];
        self[0] = round = getServer().getScheduler().runTaskTimer(this, () -> {
            for (int i = 0; i < PER_TICK && !queue.isEmpty(); i++) {
                Player p = getServer().getPlayer(queue.poll());
                if (p != null) service.snapshotMain(p, Reason.PERIODIC);
            }
            if (queue.isEmpty()) self[0].cancel();
        }, 1L, 1L);
    }

    private void stop() {
        if (periodic != null) periodic.cancel();
        if (round != null) round.cancel();
        periodic = null;
        round = null;
        if (web != null) web.stop();
        web = null;
    }

    void reload() {
        stop();
        reloadConfig();
        addMissingDefaults();
        ensureToken();
        service.settings(Settings.from(getConfig()));
        start();
    }

    /** Generates the web token on first start and writes it to config.yml. */
    private void ensureToken() {
        if (!getConfig().getString("web.token", "").isBlank()) return;
        byte[] raw = new byte[24];
        new SecureRandom().nextBytes(raw);
        getConfig().set("web.token", HexFormat.of().formatHex(raw));
        saveConfig();
        getLogger().info("Generated a web interface token, see web.token in plugins/SBackups/config.yml");
    }

    /** Adds keys (with their comments) introduced by newer versions to an existing config.yml. */
    private void addMissingDefaults() {
        var config = getConfig();
        var defaults = config.getDefaults();
        if (defaults == null) return;
        boolean changed = false;
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key) || config.contains(key, true)) continue;
            config.set(key, defaults.get(key));
            config.setComments(key, defaults.getComments(key));
            changed = true;
        }
        if (changed) saveConfig();
    }

    @Override
    public void onDisable() {
        stop();
        // Paper disables plugins before it kicks the players, so the quit event never reaches us on shutdown.
        if (service.settings().onQuit()) {
            for (Player p : getServer().getOnlinePlayers()) service.snapshotMain(p, Reason.QUIT);
        }
        world.stop();
        service.shutdownEvents();
        io.shutdown(); // flushes queued backups, e.g. the quit backups taken while the server stops
        try {
            if (!io.awaitTermination(15, TimeUnit.SECONDS)) getLogger().warning("Backups still writing at shutdown");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
