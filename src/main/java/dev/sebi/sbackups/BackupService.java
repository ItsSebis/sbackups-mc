package dev.sebi.sbackups;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Level;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * UI-agnostic core: the web server, the command and a future in-game GUI all go through this class.
 * Methods named *Main must be called on the main thread; futures complete on the IO thread.
 */
public final class BackupService {
    private final JavaPlugin plugin;
    private final BackupStore store;
    private final ExecutorService io;
    private volatile Settings settings;
    private final Events events = new Events();
    private final AtomicLong captures = new AtomicLong(), totalNanos = new AtomicLong(), maxNanos = new AtomicLong();

    BackupService(JavaPlugin plugin, BackupStore store, ExecutorService io, Settings settings) {
        this.plugin = plugin;
        this.store = store;
        this.io = io;
        this.settings = settings;
    }

    void shutdownEvents() {
        events.close();
    }

    void settings(Settings s) {
        this.settings = s;
    }

    public Settings settings() {
        return settings;
    }

    BackupStore store() {
        return store;
    }

    /** Captures on the main thread, writes asynchronously. Completes empty when a routine backup was a duplicate. */
    public CompletableFuture<Optional<Meta>> snapshotMain(Player p, Reason reason) {
        long t0 = System.nanoTime();
        Snapshot snap = Snapshot.capture(p);
        long took = System.nanoTime() - t0;
        captures.incrementAndGet();
        totalNanos.addAndGet(took);
        maxNanos.accumulateAndGet(took, Math::max);
        Location l = p.getLocation();
        UUID uuid = p.getUniqueId();
        String world = l.getWorld().getName();
        return CompletableFuture.supplyAsync(() -> {
            try {
                Optional<Meta> saved = store.save(uuid, reason, snap, world, l.getX(), l.getY(), l.getZ(), settings);
                if (saved.isPresent()) events.publish("backup", uuid);
                return saved;
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not back up " + p.getName(), e);
                throw new java.io.UncheckedIOException(e);
            }
        }, io);
    }

    /** Main-thread cost of taking snapshots since startup, for checking the plugin's tick impact. */
    public String timingSummary() {
        long n = captures.get();
        return n == 0 ? "no snapshots yet" : String.format("%d snapshots, main-thread time avg %.2f ms, max %.2f ms",
                n, totalNanos.get() / 1e6 / n, maxNanos.get() / 1e6);
    }

    public List<Meta> list(UUID uuid) {
        return store.list(uuid);
    }

    public Optional<Meta> find(UUID uuid, String id) {
        return store.find(uuid, id);
    }

    public Snapshot read(UUID uuid, String id) throws IOException {
        return store.read(uuid, id);
    }

    public boolean delete(UUID uuid, String id) throws IOException {
        boolean removed = store.delete(uuid, id);
        events.publish("backup", uuid);
        return removed;
    }

    Events events() {
        return events;
    }

    void clearPending(UUID uuid) {
        store.clearPending(uuid);
        events.publish("players", uuid);
    }

    /** Join/quit: told to browsers a couple of ticks later, when the online list already reflects the change. */
    void playersChanged(UUID uuid) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> events.publish("players", uuid), 2L);
    }

    /** Restores the selected sections to an online player after taking a pre-restore backup. */
    public CompletableFuture<Void> restoreMain(Player p, Snapshot snap, Sections sections) {
        CompletableFuture<Optional<Meta>> safety = snapshotMain(p, Reason.PRE_RESTORE);
        PlayerInventory inv = p.getInventory();
        if (sections.main()) inv.setStorageContents(Snapshot.unpack(snap.main(), Snapshot.MAIN_SIZE));
        if (sections.armor()) inv.setArmorContents(Snapshot.unpack(snap.armor(), Snapshot.ARMOR_SIZE));
        if (sections.offhand()) inv.setItemInOffHand(orAir(Snapshot.unpack(snap.offhand(), 1)[0]));
        if (sections.enderChest()) p.getEnderChest().setContents(Snapshot.unpack(snap.enderChest(), Snapshot.ENDER_SIZE));
        if (sections.xp()) {
            p.setLevel(snap.xpLevel());
            p.setExp(snap.xpProgress());
        }
        p.updateInventory();
        return safety.thenApply(m -> null);
    }

    /** Adds a single stack of a backup to an online player; leftovers drop at their feet. */
    public CompletableFuture<Void> giveMain(Player p, ItemStack item) {
        CompletableFuture<Optional<Meta>> safety = snapshotMain(p, Reason.PRE_RESTORE);
        p.getInventory().addItem(item.clone()).values()
                .forEach(left -> p.getWorld().dropItem(p.getLocation(), left));
        p.updateInventory();
        return safety.thenApply(m -> null);
    }

    /** Stores a restore to be applied when the (offline) player next joins. */
    public void restoreLater(UUID uuid, String id, Sections sections) throws IOException {
        store.savePending(uuid, id, sections);
        events.publish("players", uuid);
    }

    /** Applies and clears a pending restore, if any. Main thread. */
    void applyPendingMain(Player p) {
        Optional<BackupStore.Pending> pending = store.pending(p.getUniqueId());
        if (pending.isEmpty()) return;
        try {
            Snapshot snap = store.readPending(p.getUniqueId());
            restoreMain(p, snap, pending.get().sections()).whenComplete((v, err) -> {
                if (err == null) clearPending(p.getUniqueId());
            });
            plugin.getLogger().info("Applied pending restore " + pending.get().backupId() + " to " + p.getName());
            p.sendMessage("§aYour inventory was restored from a backup by an admin.");
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Pending restore for " + p.getName() + " failed", e);
        }
    }

    /** Runs {@code work} on the main thread and hands back its result (used by the web server). */
    <T> CompletableFuture<T> onMain(Supplier<T> work) {
        CompletableFuture<T> f = new CompletableFuture<>();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                f.complete(work.get());
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        return f;
    }

    private static ItemStack orAir(ItemStack i) {
        return i == null ? new ItemStack(Material.AIR) : i;
    }
}
