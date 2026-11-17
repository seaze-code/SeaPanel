package xyz.seaze.seapanel.web;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import xyz.seaze.seapanel.auth.AuthManager;
import xyz.seaze.seapanel.console.ConsoleBuffer;
import xyz.seaze.seapanel.service.PluginSecurityService;
import xyz.seaze.seapanel.service.ServerService;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;

public class WebDashboardServer {

    private static final Gson GSON = new Gson();

    private final String host;
    private final int port;
    private volatile String serverName;
    private final AuthManager authManager;
    private final ConsoleBuffer consoleBuffer;
    private final ServerService serverService;
    private final PluginSecurityService pluginSecurityService;
    private final Logger logger;

    private HttpServer httpServer;
    private ExecutorService executor;

    public WebDashboardServer(
            String host,
            int port,
            String serverName,
            AuthManager authManager,
            ConsoleBuffer consoleBuffer,
            ServerService serverService,
            PluginSecurityService pluginSecurityService,
            Logger logger
    ) {
        this.host = host;
        this.port = port;
        this.serverName = serverName;
        this.authManager = authManager;
        this.consoleBuffer = consoleBuffer;
        this.serverService = serverService;
        this.pluginSecurityService = pluginSecurityService;
        this.logger = logger;
    }

    public void setServerName(String serverName) {
        this.serverName = serverName;
    }

    public void start() throws IOException {
        InetSocketAddress addr = new InetSocketAddress(host, port);
        httpServer = HttpServer.create(addr, 0);
        executor = Executors.newCachedThreadPool();
        httpServer.setExecutor(executor);

        httpServer.createContext("/", this::handleStatic);
        httpServer.createContext("/api/", this::handleApi);
        httpServer.start();
    }

    public void stop() {
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private void handleStatic(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendText(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        String path = exchange.getRequestURI().getPath();
        if (path == null || path.equals("/") || path.isEmpty()) {
            path = "/index.html";
        }
        String resourcePath = switch (path) {
            case "/index.html" -> "/web/index.html";
            case "/style.css" -> "/web/style.css";
            case "/app.js" -> "/web/app.js";
            default -> null;
        };
        if (resourcePath == null) {
            // SPA fallback
            resourcePath = "/web/index.html";
            path = "/index.html";
        }

        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is == null) {
                sendText(exchange, 404, "text/plain", "Not Found");
                return;
            }
            byte[] data = is.readAllBytes();
            String contentType = switch (path) {
                case "/style.css" -> "text/css; charset=utf-8";
                case "/app.js" -> "application/javascript; charset=utf-8";
                default -> "text/html; charset=utf-8";
            };
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.sendResponseHeaders(200, data.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(data);
            }
        }
    }

    private void handleApi(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase();
        String path = exchange.getRequestURI().getPath();

        if ("OPTIONS".equals(method)) {
            addCorsHeaders(exchange);
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        try {
            if ("/api/login".equals(path) && "POST".equals(method)) {
                JsonObject body = readJsonBody(exchange);
                String password = body.has("password") ? body.get("password").getAsString() : "";
                if (authManager.verifyPassword(password)) {
                    String token = authManager.createSession();
                    JsonObject res = new JsonObject();
                    res.addProperty("ok", true);
                    res.addProperty("token", token);
                    res.addProperty("serverName", serverName);
                    sendJson(exchange, 200, res);
                } else {
                    sendError(exchange, 401, "Invalid password");
                }
                return;
            }

            if ("/api/auth/check".equals(path) && "GET".equals(method)) {
                String token = extractToken(exchange);
                JsonObject res = new JsonObject();
                res.addProperty("authenticated", authManager.isValidToken(token));
                res.addProperty("serverName", serverName);
                sendJson(exchange, 200, res);
                return;
            }

            if ("/api/logout".equals(path) && "POST".equals(method)) {
                authManager.revokeSession(extractToken(exchange));
                JsonObject res = new JsonObject();
                res.addProperty("ok", true);
                sendJson(exchange, 200, res);
                return;
            }

            if ("/api/server-icon".equals(path) && "GET".equals(method)) {
                Path iconPath = org.bukkit.Bukkit.getWorldContainer().toPath().resolve("server-icon.png");
                if (Files.isRegularFile(iconPath)) {
                    byte[] bytes = Files.readAllBytes(iconPath);
                    exchange.getResponseHeaders().set("Content-Type", "image/png");
                    exchange.getResponseHeaders().set("Cache-Control", "no-cache");
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(bytes);
                    }
                } else {
                    exchange.sendResponseHeaders(404, -1);
                }
                return;
            }

            // All routes below require authentication
            String token = extractToken(exchange);
            if (!authManager.isValidToken(token)) {
                sendError(exchange, 401, "Unauthorized");
                return;
            }

            if ("/api/stats".equals(path) && "GET".equals(method)) {
                sendJson(exchange, 200, serverService.getServerStats(serverName));
                return;
            }

            if ("/api/players".equals(path) && "GET".equals(method)) {
                JsonObject res = new JsonObject();
                res.add("players", serverService.getOnlinePlayers());
                sendJson(exchange, 200, res);
                return;
            }

            if (path.startsWith("/api/players/")) {
                String rest = path.substring("/api/players/".length());
                if (rest.endsWith("/action") && "POST".equals(method)) {
                    String idOrName = decode(rest.substring(0, rest.length() - "/action".length()));
                    JsonObject body = readJsonBody(exchange);
                    String action = body.has("action") ? body.get("action").getAsString() : "";
                    JsonObject res = serverService.performPlayerAction(idOrName, action, body);
                    sendJson(exchange, res.get("ok").getAsBoolean() ? 200 : 400, res);
                    return;
                } else if ("GET".equals(method) && !rest.contains("/")) {
                    String idOrName = decode(rest);
                    JsonObject detail = serverService.getPlayerDetail(idOrName);
                    if (detail == null) {
                        sendError(exchange, 404, "Player not found");
                    } else {
                        sendJson(exchange, 200, detail);
                    }
                    return;
                }
            }

            if ("/api/bans".equals(path) && "GET".equals(method)) {
                JsonObject res = new JsonObject();
                res.add("bans", serverService.getBannedPlayers());
                sendJson(exchange, 200, res);
                return;
            }

            if ("/api/bans/unban".equals(path) && "POST".equals(method)) {
                JsonObject body = readJsonBody(exchange);
                String target = body.has("target") ? body.get("target").getAsString() : "";
                if (target.isBlank()) {
                    sendError(exchange, 400, "Missing target");
                    return;
                }
                serverService.unbanPlayer(target);
                sendOk(exchange);
                return;
            }

            if ("/api/console".equals(path) && "GET".equals(method)) {
                long since = parseLongParam(exchange.getRequestURI().getQuery(), "since", 0L);
                List<ConsoleBuffer.LogEntry> entries = consoleBuffer.getRecent(since);
                JsonObject res = new JsonObject();
                JsonArray arr = new JsonArray();
                for (ConsoleBuffer.LogEntry e : entries) {
                    arr.add(GSON.toJsonTree(e));
                }
                res.add("logs", arr);
                sendJson(exchange, 200, res);
                return;
            }

            if ("/api/console/stream".equals(path) && "GET".equals(method)) {
                handleConsoleSse(exchange);
                return;
            }

            if ("/api/console/command".equals(path) && "POST".equals(method)) {
                JsonObject body = readJsonBody(exchange);
                String cmd = body.has("command") ? body.get("command").getAsString().trim() : "";
                if (cmd.startsWith("/")) {
                    cmd = cmd.substring(1);
                }
                if (cmd.isEmpty()) {
                    sendError(exchange, 400, "Empty command");
                    return;
                }
                boolean ok = serverService.dispatchCommand(cmd);
                JsonObject res = new JsonObject();
                res.addProperty("ok", ok);
                sendJson(exchange, 200, res);
                return;
            }

            if ("/api/worlds".equals(path) && "GET".equals(method)) {
                JsonObject res = new JsonObject();
                res.add("worlds", serverService.getWorlds());
                sendJson(exchange, 200, res);
                return;
            }

            if (path.startsWith("/api/worlds/") && path.endsWith("/action") && "POST".equals(method)) {
                String worldName = decode(path.substring("/api/worlds/".length(), path.length() - "/action".length()));
                JsonObject body = readJsonBody(exchange);
                String action = body.has("action") ? body.get("action").getAsString() : "";
                JsonObject res = serverService.performWorldAction(worldName, action, body);
                sendJson(exchange, res.get("ok").getAsBoolean() ? 200 : 400, res);
                return;
            }

            if ("/api/whitelist".equals(path)) {
                if ("GET".equals(method)) {
                    sendJson(exchange, 200, serverService.getWhitelist());
                    return;
                } else if ("POST".equals(method)) {
                    JsonObject body = readJsonBody(exchange);
                    String action = body.has("action") ? body.get("action").getAsString() : "";
                    switch (action) {
                        case "toggle" -> serverService.setWhitelistEnabled(
                                body.has("enabled") && body.get("enabled").getAsBoolean());
                        case "add" -> serverService.modifyWhitelistPlayer(
                                body.get("name").getAsString(), true);
                        case "remove" -> serverService.modifyWhitelistPlayer(
                                body.get("name").getAsString(), false);
                        default -> {
                            sendError(exchange, 400, "Invalid whitelist action");
                            return;
                        }
                    }
                    sendOk(exchange);
                    return;
                }
            }

            if ("/api/server/action".equals(path) && "POST".equals(method)) {
                JsonObject body = readJsonBody(exchange);
                String action = body.has("action") ? body.get("action").getAsString() : "";
                switch (action) {
                    case "broadcast" -> serverService.broadcast(
                            body.has("message") ? body.get("message").getAsString() : "");
                    case "save" -> serverService.saveAll();
                    case "gamemode" -> serverService.setDefaultGameMode(
                            body.has("mode") ? body.get("mode").getAsString() : "SURVIVAL");
                    case "stop" -> {
                        sendOk(exchange);
                        serverService.stopServer();
                        return;
                    }
                    default -> {
                        sendError(exchange, 400, "Unknown server action");
                        return;
                    }
                }
                sendOk(exchange);
                return;
            }

            if ("/api/plugins".equals(path) && "GET".equals(method)) {
                JsonObject res = new JsonObject();
                res.add("plugins", serverService.runSync(pluginSecurityService::listPlugins));
                sendJson(exchange, 200, res);
                return;
            }

            if ("/api/plugins/scan-all".equals(path) && "POST".equals(method)) {
                JsonObject res = new JsonObject();
                res.add("results", pluginSecurityService.scanAllPlugins());
                sendJson(exchange, 200, res);
                return;
            }

            if (path.startsWith("/api/plugins/")) {
                String rest = path.substring("/api/plugins/".length());
                if (rest.endsWith("/toggle") && "POST".equals(method)) {
                    String pluginName = decode(rest.substring(0, rest.length() - "/toggle".length()));
                    boolean enabled = serverService.runSync(() -> pluginSecurityService.togglePlugin(pluginName));
                    JsonObject res = new JsonObject();
                    res.addProperty("ok", true);
                    res.addProperty("enabled", enabled);
                    sendJson(exchange, 200, res);
                    return;
                }
                if (rest.endsWith("/scan") && "POST".equals(method)) {
                    String pluginName = decode(rest.substring(0, rest.length() - "/scan".length()));
                    sendJson(exchange, 200, pluginSecurityService.scanPlugin(pluginName));
                    return;
                }
                if (rest.endsWith("/files") && "GET".equals(method)) {
                    String scope = decode(rest.substring(0, rest.length() - "/files".length()));
                    JsonObject res = new JsonObject();
                    res.add("files", pluginSecurityService.listConfigFiles(scope));
                    sendJson(exchange, 200, res);
                    return;
                }
                if (rest.endsWith("/file")) {
                    String scope = decode(rest.substring(0, rest.length() - "/file".length()));
                    if ("GET".equals(method)) {
                        String filePath = parseStringParam(exchange.getRequestURI().getQuery(), "path", "");
                        JsonObject res = new JsonObject();
                        res.addProperty("path", filePath);
                        res.addProperty("content", pluginSecurityService.readConfigFile(scope, filePath));
                        sendJson(exchange, 200, res);
                        return;
                    } else if ("POST".equals(method)) {
                        JsonObject body = readJsonBody(exchange);
                        String filePath = body.has("path") ? body.get("path").getAsString() : "";
                        String content = body.has("content") ? body.get("content").getAsString() : "";
                        pluginSecurityService.writeConfigFile(scope, filePath, content);
                        sendOk(exchange);
                        return;
                    }
                }
            }

            sendError(exchange, 404, "Endpoint not found");
        } catch (Exception e) {
            logger.warning("API error on " + path + ": " + e.getMessage());
            sendError(exchange, 500, e.getMessage() != null ? e.getMessage() : "Internal Server Error");
        }
    }

    private void handleConsoleSse(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        addCorsHeaders(exchange);
        exchange.sendResponseHeaders(200, 0);

        BlockingQueue<ConsoleBuffer.LogEntry> queue = new LinkedBlockingQueue<>(200);
        Consumer<ConsoleBuffer.LogEntry> listener = queue::offer;
        consoleBuffer.addListener(listener);

        try (OutputStream os = exchange.getResponseBody()) {
            // Send initial heartbeat
            os.write(": connected\n\n".getBytes(StandardCharsets.UTF_8));
            os.flush();

            while (httpServer != null) {
                ConsoleBuffer.LogEntry entry = queue.poll(15, TimeUnit.SECONDS);
                if (entry != null) {
                    String payload = "data: " + GSON.toJson(entry) + "\n\n";
                    os.write(payload.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                } else {
                    os.write(": ping\n\n".getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
            }
        } catch (IOException | InterruptedException ignored) {
            // Client disconnected
        } finally {
            consoleBuffer.removeListener(listener);
        }
    }

    private String extractToken(HttpExchange exchange) {
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring("Bearer ".length()).trim();
        }
        String query = exchange.getRequestURI().getQuery();
        if (query != null) {
            for (String part : query.split("&")) {
                int idx = part.indexOf('=');
                if (idx > 0 && "token".equals(part.substring(0, idx))) {
                    return decode(part.substring(idx + 1));
                }
            }
        }
        return null;
    }

    private JsonObject readJsonBody(HttpExchange exchange) throws IOException {
        String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (raw.isBlank()) {
            return new JsonObject();
        }
        return JsonParser.parseString(raw).getAsJsonObject();
    }

    private void sendOk(HttpExchange exchange) throws IOException {
        JsonObject res = new JsonObject();
        res.addProperty("ok", true);
        sendJson(exchange, 200, res);
    }

    private void sendError(HttpExchange exchange, int status, String message) throws IOException {
        JsonObject res = new JsonObject();
        res.addProperty("ok", false);
        res.addProperty("error", message);
        sendJson(exchange, status, res);
    }

    private void sendJson(HttpExchange exchange, int status, JsonObject obj) throws IOException {
        byte[] bytes = GSON.toJson(obj).getBytes(StandardCharsets.UTF_8);
        addCorsHeaders(exchange);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendText(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void addCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
    }

    private long parseLongParam(String query, String key, long fallback) {
        if (query == null) return fallback;
        for (String part : query.split("&")) {
            int idx = part.indexOf('=');
            if (idx > 0 && key.equals(part.substring(0, idx))) {
                try {
                    return Long.parseLong(part.substring(idx + 1));
                } catch (NumberFormatException ignored) {}
            }
        }
        return fallback;
    }

    private String parseStringParam(String query, String key, String fallback) {
        if (query == null) return fallback;
        for (String part : query.split("&")) {
            int idx = part.indexOf('=');
            if (idx > 0 && key.equals(part.substring(0, idx))) {
                return decode(part.substring(idx + 1));
            }
        }
        return fallback;
    }

    private String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }
}
