package dev.sebi.sbackups;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Server-sent events for the web interface. Events only say *what* changed ("backup" or "players", plus the
 * player's uuid); the page then re-fetches just that. All socket writes happen on one daemon thread, never on the
 * main thread, and nothing is sent while no browser is connected.
 */
final class Events {
    private static final int MAX_CLIENTS = 20;

    private final List<HttpExchange> clients = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService writer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "SBackups-events");
        t.setDaemon(true);
        return t;
    });

    Events() {
        writer.scheduleAtFixedRate(() -> send(": ping\n\n"), 15, 15, TimeUnit.SECONDS); // also drops dead clients
    }

    boolean full() {
        return clients.size() >= MAX_CLIENTS;
    }

    /** Call after the response headers were sent. */
    void add(HttpExchange ex) {
        clients.add(ex);
    }

    /** @param type "backup" (a backup was added/removed) or "players" (online state / pending restore changed) */
    void publish(String type, UUID uuid) {
        if (clients.isEmpty()) return;
        String json = "{\"type\":\"" + type + "\",\"uuid\":\"" + uuid + "\"}";
        try {
            writer.execute(() -> send("data: " + json + "\n\n"));
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // shutting down
        }
    }

    private void send(String frame) {
        byte[] bytes = frame.getBytes(StandardCharsets.UTF_8);
        for (HttpExchange c : clients) {
            try {
                var out = c.getResponseBody();
                out.write(bytes);
                out.flush();
            } catch (IOException e) {
                clients.remove(c);
                c.close();
            }
        }
    }

    void close() {
        writer.shutdownNow();
        clients.forEach(HttpExchange::close);
        clients.clear();
    }
}
