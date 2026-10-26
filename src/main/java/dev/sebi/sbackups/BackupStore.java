package dev.sebi.sbackups;

import com.google.gson.Gson;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Files under {@code data/<uuid>/<epochMillis>-<reason>.sbk}: a gzip stream with a magic number, the {@link Meta}
 * header as JSON and the four item sections. Listings come from an in-memory cache of the headers.
 * All methods are synchronized; disk work is done by the caller on the IO thread.
 */
final class BackupStore {
    private static final int MAGIC = 0x53424B31; // "SBK1"
    static final Pattern ID = Pattern.compile("\\d{10,15}-[a-z-]{3,20}");

    private final Path root;
    private final Logger log;
    private final Gson gson = new Gson();
    private final Map<UUID, List<Meta>> cache = new HashMap<>();

    BackupStore(Path root, Logger log) {
        this.root = root;
        this.log = log;
    }

    private Path dir(UUID uuid) {
        return root.resolve(uuid.toString());
    }

    private Path file(UUID uuid, String id) {
        if (!ID.matcher(id).matches()) throw new IllegalArgumentException("Invalid backup id");
        return dir(uuid).resolve(id + ".sbk");
    }

    synchronized List<UUID> players() {
        List<UUID> out = new ArrayList<>();
        if (!Files.isDirectory(root)) return out;
        try (Stream<Path> s = Files.list(root)) {
            s.filter(Files::isDirectory).forEach(p -> {
                try {
                    out.add(UUID.fromString(p.getFileName().toString()));
                } catch (IllegalArgumentException ignored) {
                    // not a player directory
                }
            });
        } catch (IOException e) {
            log.warning("Could not list backups: " + e.getMessage());
        }
        return out;
    }

    /** Newest first. */
    synchronized List<Meta> list(UUID uuid) {
        return List.copyOf(load(uuid));
    }

    synchronized Optional<Meta> find(UUID uuid, String id) {
        return load(uuid).stream().filter(m -> m.id().equals(id)).findFirst();
    }

    private List<Meta> load(UUID uuid) {
        return cache.computeIfAbsent(uuid, u -> {
            List<Meta> out = new ArrayList<>();
            Path d = dir(u);
            if (Files.isDirectory(d)) {
                try (Stream<Path> s = Files.list(d)) {
                    s.filter(p -> p.toString().endsWith(".sbk")).forEach(p -> {
                        try (DataInputStream in = open(p)) {
                            out.add(readMeta(in).withSize(Files.size(p)));
                        } catch (IOException e) {
                            log.warning("Skipping unreadable backup " + p + ": " + e.getMessage());
                        }
                    });
                } catch (IOException e) {
                    log.warning("Could not list " + d + ": " + e.getMessage());
                }
            }
            out.sort(Comparator.comparingLong(Meta::time).reversed());
            return out;
        });
    }

    private static DataInputStream open(Path p) throws IOException {
        return new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(p))));
    }

    private Meta readMeta(DataInputStream in) throws IOException {
        if (in.readInt() != MAGIC) throw new IOException("Not a backup file");
        return gson.fromJson(in.readUTF(), Meta.class);
    }

    /**
     * Writes a backup, unless a routine backup would duplicate the latest one (then returns empty), and prunes.
     */
    synchronized Optional<Meta> save(UUID uuid, Reason reason, Snapshot snap, String world, double x, double y,
                                     double z, Settings settings) throws IOException {
        String hash = snap.hash();
        List<Meta> existing = load(uuid);
        if (reason.dedupe() && !existing.isEmpty() && existing.get(0).hash().equals(hash)) return Optional.empty();

        long time = System.currentTimeMillis();
        if (!existing.isEmpty()) time = Math.max(time, existing.get(0).time() + 1); // ids stay unique and ordered
        String id = time + "-" + reason.id();
        Meta meta = new Meta(id, time, reason.id(), world, x, y, z, hash, snap.itemCount(), snap.xpLevel(),
                snap.xpProgress(), 0);

        Files.createDirectories(dir(uuid));
        Path target = file(uuid, id);
        Path tmp = target.resolveSibling(id + ".tmp");
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(tmp))))) {
            out.writeInt(MAGIC);
            out.writeUTF(gson.toJson(meta));
            snap.write(out);
        }
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        Meta stored = meta.withSize(Files.size(target));
        existing.add(0, stored);
        prune(uuid, settings);
        enforceTotalSize(settings);
        return Optional.of(stored);
    }

    synchronized Snapshot read(UUID uuid, String id) throws IOException {
        Meta meta = find(uuid, id).orElseThrow(() -> new IOException("No such backup"));
        try (DataInputStream in = open(file(uuid, id))) {
            readMeta(in);
            return Snapshot.read(in, meta);
        }
    }

    synchronized boolean delete(UUID uuid, String id) throws IOException {
        Path f = file(uuid, id);
        boolean removed = Files.deleteIfExists(f);
        load(uuid).removeIf(m -> m.id().equals(id));
        return removed;
    }

    private void prune(UUID uuid, Settings settings) {
        for (Meta m : Retention.expired(load(uuid), settings, System.currentTimeMillis())) {
            try {
                delete(uuid, m.id());
            } catch (IOException e) {
                log.warning("Could not prune " + m.id() + ": " + e.getMessage());
            }
        }
    }

    /** Global safety cap: removes the oldest routine backups of all players until the total fits. */
    private void enforceTotalSize(Settings settings) {
        if (settings.maxTotalBytes() <= 0) return;
        record Entry(UUID uuid, Meta meta) {}
        List<Entry> routine = new ArrayList<>();
        long total = 0;
        for (UUID u : players()) {
            for (Meta m : load(u)) {
                total += m.size();
                if (m.reasonEnum().pool() == Reason.Pool.ROUTINE) routine.add(new Entry(u, m));
            }
        }
        if (total <= settings.maxTotalBytes()) return;
        routine.sort(Comparator.comparingLong(e -> e.meta().time()));
        for (Entry e : routine) {
            if (total <= settings.maxTotalBytes()) break;
            try {
                delete(e.uuid(), e.meta().id());
                total -= e.meta().size();
            } catch (IOException ex) {
                log.warning("Could not delete " + e.meta().id() + ": " + ex.getMessage());
            }
        }
    }

    // --- pending restores for offline players: pending/<uuid>.json (options) + pending/<uuid>.sbk (copy) ---

    record Pending(String backupId, Sections sections) {}

    synchronized void savePending(UUID uuid, String id, Sections sections) throws IOException {
        Path pd = root.resolveSibling("pending");
        Files.createDirectories(pd);
        Files.copy(file(uuid, id), pd.resolve(uuid + ".sbk"), StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(pd.resolve(uuid + ".json"), gson.toJson(new Pending(id, sections)));
    }

    synchronized Optional<Pending> pending(UUID uuid) {
        Path j = root.resolveSibling("pending").resolve(uuid + ".json");
        if (!Files.exists(j)) return Optional.empty();
        try {
            return Optional.of(gson.fromJson(Files.readString(j), Pending.class));
        } catch (IOException e) {
            log.warning("Unreadable pending restore " + j + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    synchronized Snapshot readPending(UUID uuid) throws IOException {
        Path p = root.resolveSibling("pending").resolve(uuid + ".sbk");
        if (!hasPending(uuid)) throw new IOException("No pending restore");
        // Header of the copy carries the xp values; reuse the same reader as for normal backups.
        try (DataInputStream in = open(p)) {
            Meta meta = readMeta(in);
            return Snapshot.read(in, meta);
        }
    }

    synchronized void clearPending(UUID uuid) {
        Path pd = root.resolveSibling("pending");
        try {
            Files.deleteIfExists(pd.resolve(uuid + ".json"));
            Files.deleteIfExists(pd.resolve(uuid + ".sbk"));
        } catch (IOException e) {
            log.warning("Could not clear pending restore: " + e.getMessage());
        }
    }

    synchronized boolean hasPending(UUID uuid) {
        return Files.exists(root.resolveSibling("pending").resolve(uuid + ".json"));
    }
}
