package dev.sebi.sbackups;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Token-protected admin API plus the single-page UI. State-changing calls need the token and a same-origin request. */
final class WebServer {
    private static final String COOKIE = "sbtoken";
    private static final int MAX_BODY = 64 * 1024;

    private final BackupService service;
    private final byte[] token;
    private final String iconTemplate;
    private final HttpServer server;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private final byte[] page;

    private record LoginReq(String token) {}

    private record RestoreReq(Sections sections) {}

    private record GiveReq(String section, int index) {}

    private static final class HttpError extends RuntimeException {
        final int status;

        HttpError(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    WebServer(BackupService service, Settings s) throws IOException {
        this.service = service;
        this.token = s.webToken().getBytes(StandardCharsets.UTF_8);
        this.iconTemplate = s.iconUrlTemplate();
        try (InputStream in = WebServer.class.getResourceAsStream("/web/index.html")) {
            page = in.readAllBytes();
        }
        server = HttpServer.create(new InetSocketAddress(s.webBind(), s.webPort()), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "SBackups-web");
            t.setDaemon(true);
            return t;
        }));
        server.start();
    }

    int port() {
        return server.getAddress().getPort();
    }

    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if (!path.startsWith("/api/")) {
                if (method.equals("GET") && (path.equals("/") || path.equals("/index.html"))) {
                    send(ex, 200, "text/html; charset=utf-8", page);
                } else {
                    send(ex, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
                }
                return;
            }
            if (!method.equals("GET")) checkOrigin(ex);
            String[] seg = path.substring(5).split("/");
            if (path.equals("/api/login") && method.equals("POST")) {
                login(ex);
                return;
            }
            if (!authed(ex)) throw new HttpError(401, "Not logged in");
            json(ex, 200, route(ex, method, seg));
        } catch (HttpError e) {
            json(ex, e.status, Map.of("error", e.getMessage()));
        } catch (JsonSyntaxException | IllegalArgumentException e) {
            json(ex, 400, Map.of("error", "Bad request: " + e.getMessage()));
        } catch (Exception e) {
            Bukkit.getLogger().warning("[SBackups] web request failed: " + e);
            json(ex, 500, Map.of("error", "Internal error"));
        }
    }

    private Object route(HttpExchange ex, String method, String[] seg) throws Exception {
        if (seg.length == 1 && seg[0].equals("config") && method.equals("GET")) {
            Settings s = service.settings();
            return Map.of("iconUrlTemplate", iconTemplate, "restoreDefaults", s.restoreDefaults());
        }
        if (seg.length == 1 && seg[0].equals("players") && method.equals("GET")) return players();
        if (seg.length < 3 || !seg[0].equals("players")) throw new HttpError(404, "Not found");

        UUID uuid = UUID.fromString(seg[1]);
        String sub = seg[2];
        if (seg.length == 3) {
            switch (method + " " + sub) {
                case "GET backups" -> {
                    return service.list(uuid);
                }
                case "GET current" -> {
                    return await(service.onMain(() -> ItemJson.sections(Snapshot.capture(online(uuid)))));
                }
                case "POST backup" -> {
                    var meta = await(service.onMain(() -> service.snapshotMain(online(uuid), Reason.MANUAL)))
                            .get(10, TimeUnit.SECONDS);
                    return meta.isPresent() ? meta.get() : Map.of("unchanged", true);
                }
                case "DELETE pending" -> {
                    service.store().clearPending(uuid);
                    return Map.of("ok", true);
                }
                default -> throw new HttpError(404, "Not found");
            }
        }
        if (!sub.equals("backups") || !BackupStore.ID.matcher(seg[3]).matches()) throw new HttpError(404, "Not found");
        String id = seg[3];
        Meta meta = service.find(uuid, id).orElseThrow(() -> new HttpError(404, "No such backup"));

        if (seg.length == 4 && method.equals("GET")) {
            Snapshot snap = service.read(uuid, id);
            Map<String, Object> out = new LinkedHashMap<>(await(service.onMain(() -> ItemJson.sections(snap))));
            out.put("meta", meta);
            return out;
        }
        if (seg.length == 4 && method.equals("DELETE")) {
            service.delete(uuid, id);
            return Map.of("ok", true);
        }
        if (seg.length == 5 && method.equals("POST") && seg[4].equals("restore")) {
            Sections sections = body(ex, RestoreReq.class).sections();
            if (sections == null || !sections.any()) throw new HttpError(400, "Select at least one section");
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) {
                service.restoreLater(uuid, id, sections);
                return Map.of("queued", true);
            }
            Snapshot snap = service.read(uuid, id);
            await(service.onMain(() -> service.restoreMain(online(uuid), snap, sections))).get(10, TimeUnit.SECONDS);
            return Map.of("restored", true);
        }
        if (seg.length == 5 && method.equals("POST") && seg[4].equals("give")) {
            GiveReq req = body(ex, GiveReq.class);
            Snapshot snap = service.read(uuid, id);
            await(service.onMain(() -> {
                Player p = online(uuid);
                byte[] data;
                int size;
                switch (String.valueOf(req.section())) {
                    case "main" -> { data = snap.main(); size = Snapshot.MAIN_SIZE; }
                    case "armor" -> { data = snap.armor(); size = Snapshot.ARMOR_SIZE; }
                    case "offhand" -> { data = snap.offhand(); size = 1; }
                    case "ender" -> { data = snap.enderChest(); size = Snapshot.ENDER_SIZE; }
                    default -> throw new HttpError(400, "Unknown section");
                }
                if (req.index() < 0 || req.index() >= size) throw new HttpError(400, "Bad slot");
                ItemStack item = Snapshot.unpack(data, size)[req.index()];
                if (item == null) throw new HttpError(400, "Slot is empty");
                return service.giveMain(p, item);
            })).get(10, TimeUnit.SECONDS);
            return Map.of("given", true);
        }
        throw new HttpError(404, "Not found");
    }

    private Player online(UUID uuid) {
        Player p = Bukkit.getPlayer(uuid);
        if (p == null) throw new HttpError(409, "Player must be online for this action");
        return p;
    }

    private List<Map<String, Object>> players() throws Exception {
        return await(service.onMain(() -> {
            List<UUID> all = new ArrayList<>(service.store().players());
            for (Player p : Bukkit.getOnlinePlayers()) if (!all.contains(p.getUniqueId())) all.add(p.getUniqueId());
            List<Map<String, Object>> out = new ArrayList<>();
            for (UUID u : all) {
                List<Meta> backups = service.list(u);
                String name = Bukkit.getOfflinePlayer(u).getName();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("uuid", u.toString());
                m.put("name", name != null ? name : u.toString());
                m.put("online", Bukkit.getPlayer(u) != null);
                m.put("count", backups.size());
                m.put("latest", backups.isEmpty() ? 0 : backups.get(0).time());
                m.put("pending", service.store().hasPending(u));
                out.add(m);
            }
            return out;
        }));
    }

    private static <T> T await(java.util.concurrent.CompletableFuture<T> f) throws Exception {
        try {
            return f.get(15, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            throw e;
        } catch (TimeoutException e) {
            throw new HttpError(504, "Server thread did not answer in time");
        }
    }

    // --- auth ---

    private void login(HttpExchange ex) throws IOException {
        LoginReq req = body(ex, LoginReq.class);
        if (req == null || req.token() == null || !tokenMatches(req.token())) {
            try {
                Thread.sleep(500); // slows down guessing
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            throw new HttpError(401, "Wrong token");
        }
        ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + req.token() + "; HttpOnly; SameSite=Strict; Path=/");
        json(ex, 200, Map.of("ok", true));
    }

    private boolean authed(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Authorization");
        if (h != null && h.startsWith("Bearer ") && tokenMatches(h.substring(7).trim())) return true;
        String cookies = ex.getRequestHeaders().getFirst("Cookie");
        if (cookies != null) {
            for (String c : cookies.split(";")) {
                String[] kv = c.trim().split("=", 2);
                if (kv.length == 2 && kv[0].equals(COOKIE) && tokenMatches(kv[1])) return true;
            }
        }
        return false;
    }

    private boolean tokenMatches(String given) {
        return MessageDigest.isEqual(token, given.getBytes(StandardCharsets.UTF_8));
    }

    /** Browsers send Origin on cross-site writes; it must name this very host. */
    private void checkOrigin(HttpExchange ex) {
        String origin = ex.getRequestHeaders().getFirst("Origin");
        String host = ex.getRequestHeaders().getFirst("Host");
        if (origin != null && (host == null || !origin.replaceFirst("^https?://", "").equals(host))) {
            throw new HttpError(403, "Cross-origin request refused");
        }
    }

    // --- plumbing ---

    private <T> T body(HttpExchange ex, Class<T> type) throws IOException {
        byte[] raw = ex.getRequestBody().readNBytes(MAX_BODY + 1);
        if (raw.length > MAX_BODY) throw new HttpError(413, "Body too large");
        T value = gson.fromJson(new String(raw, StandardCharsets.UTF_8), type);
        if (value == null) throw new HttpError(400, "Missing body");
        return value;
    }

    private void json(HttpExchange ex, int status, Object body) throws IOException {
        send(ex, status, "application/json; charset=utf-8", gson.toJson(body).getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(status, body.length);
        try (var out = ex.getResponseBody()) {
            out.write(body);
        }
    }
}
