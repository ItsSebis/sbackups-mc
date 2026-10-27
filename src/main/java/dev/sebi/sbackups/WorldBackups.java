package dev.sebi.sbackups;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Runs the world backup/restore scripts for the web interface, one job at a time. The scripts do all the real work
 * (RCON, rsync, restic); this class only starts them, keeps the tail of their output and reports the result.
 */
final class WorldBackups {
    /** A restic snapshot id (short or full) or "latest". */
    static final Pattern SNAPSHOT = Pattern.compile("latest|[0-9a-f]{8,64}");
    private static final int OUTPUT_LINES = 60;
    private static final int LINE_CHARS = 500;
    private static final long JOB_TIMEOUT_HOURS = 3;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    record Job(String kind, String detail, long started) {}

    record Result(String kind, String detail, boolean ok, long started, long ended, String output) {}

    private final Supplier<Settings> settings;
    private final Events events;
    private final Logger log;
    private final ExecutorService runner = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SBackups-world");
        t.setDaemon(true);
        return t;
    });

    private volatile Job running;
    private volatile Process process;
    private volatile Result last;

    WorldBackups(Supplier<Settings> settings, Events events, Logger log) {
        this.settings = settings;
        this.events = events;
        this.log = log;
    }

    Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("running", running);
        m.put("last", last);
        return m;
    }

    /** Snapshots newest first, as {id, shortId, time}. Reads the repository, so it can take a few seconds. */
    List<Map<String, Object>> snapshots() throws IOException, InterruptedException {
        Output out = run(List.of(settings.get().worldRestoreCommand(), "--json"), 120, false);
        if (out.exit != 0) throw new IOException("Listing snapshots failed: " + out.tail());
        JsonElement parsed = JsonParser.parseString(out.text.isBlank() ? "[]" : out.text);
        JsonArray arr = parsed.isJsonArray() ? parsed.getAsJsonArray() : new JsonArray();
        List<Map<String, Object>> list = new ArrayList<>();
        for (JsonElement e : arr) {
            var o = e.getAsJsonObject();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", o.get("id").getAsString());
            m.put("shortId", o.has("short_id") ? o.get("short_id").getAsString() : o.get("id").getAsString().substring(0, 8));
            m.put("time", o.get("time").getAsString());
            list.add(m);
        }
        list.sort(Comparator.comparing((Map<String, Object> m) -> (String) m.get("time")).reversed());
        return list;
    }

    /** @return false if a job is already running */
    synchronized boolean startBackup() {
        if (running != null) return false;
        start(new Job("backup", "", System.currentTimeMillis()), List.of(settings.get().worldBackupCommand()));
        return true;
    }

    /**
     * Restores into a new folder below world.restore-dir, never onto the live world.
     *
     * @return the target folder, or null if a job is already running
     */
    synchronized Path startRestore(String id) {
        if (running != null) return null;
        if (!SNAPSHOT.matcher(id).matches()) throw new IllegalArgumentException("Bad snapshot id");
        Settings s = settings.get();
        Path target = Path.of(s.worldRestoreDir()).resolve(STAMP.format(LocalDateTime.now()) + "-" + (id.length() > 8 ? id.substring(0, 8) : id));
        start(new Job("restore", target.toString(), System.currentTimeMillis()),
                List.of(s.worldRestoreCommand(), target.toString(), id));
        return target;
    }

    private void start(Job job, List<String> command) {
        running = job;
        events.publish("world", null);
        runner.execute(() -> {
            Result result;
            try {
                if (job.kind().equals("restore")) Files.createDirectories(Path.of(settings.get().worldRestoreDir()));
                Output out = run(command, JOB_TIMEOUT_HOURS * 3600, true);
                result = new Result(job.kind(), job.detail(), out.exit == 0, job.started(), System.currentTimeMillis(), out.tail());
            } catch (IOException | RuntimeException e) {
                result = new Result(job.kind(), job.detail(), false, job.started(), System.currentTimeMillis(), String.valueOf(e.getMessage()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                result = new Result(job.kind(), job.detail(), false, job.started(), System.currentTimeMillis(), "Interrupted");
            }
            if (!result.ok()) log.warning("World " + job.kind() + " failed: " + result.output());
            last = result;
            running = null;
            events.publish("world", null);
        });
    }

    private record Output(int exit, String text, Deque<String> lines) {
        String tail() {
            return String.join("\n", lines);
        }
    }

    /**
     * @param job true for a backup/restore job: stderr is merged into the output and the process is killed by
     *            {@link #stop()}. false for a listing, where stdout must stay clean JSON and stderr only serves as
     *            the error message.
     */
    private Output run(List<String> command, long timeoutSeconds, boolean job) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(command).redirectErrorStream(job).start();
        if (job) process = p;
        StringBuilder text = new StringBuilder();
        Deque<String> lines = new ArrayDeque<>();
        // Reader threads, so the timeout also covers a script that hangs without printing anything.
        Thread out = reader(p.getInputStream(), text, lines);
        Thread err = job ? null : reader(p.getErrorStream(), null, lines);
        try {
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroy(); // SIGTERM: world-backup.sh's EXIT trap turns saving back on
                if (!p.waitFor(30, TimeUnit.SECONDS)) p.destroyForcibly();
                out.join(2000);
                synchronized (lines) {
                    lines.addLast("Timed out after " + timeoutSeconds + " seconds");
                }
                return new Output(-1, "", lines);
            }
            out.join(2000);
            if (err != null) err.join(2000);
        } finally {
            if (job) process = null;
        }
        synchronized (lines) {
            return new Output(p.exitValue(), text.toString(), new ArrayDeque<>(lines));
        }
    }

    /** Collects a stream line by line: the last lines into {@code lines}, everything (capped) into {@code text}. */
    private static Thread reader(java.io.InputStream stream, StringBuilder text, Deque<String> lines) {
        Thread t = new Thread(() -> {
            try (var in = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    synchronized (lines) {
                        if (text != null && text.length() < 4 * 1024 * 1024) text.append(line).append('\n');
                        if (lines.size() == OUTPUT_LINES) lines.removeFirst();
                        lines.addLast(line.length() > LINE_CHARS ? line.substring(0, LINE_CHARS) + "…" : line);
                    }
                }
            } catch (IOException ignored) {
                // process went away
            }
        }, "SBackups-world-output");
        t.setDaemon(true);
        t.start();
        return t;
    }

    void stop() {
        Process p = process;
        if (p != null) p.destroy();
        runner.shutdownNow();
    }
}
