import com.sun.net.httpserver.*;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Local HTTP adapter; identity fields are labels, not authentication. */
public final class TaskFlow {
    public static HttpServer start(TaskStore store, int port, Path web) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 32);
        ThreadPoolExecutor pool = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(128), new ThreadPoolExecutor.CallerRunsPolicy());
        server.setExecutor(pool);
        server.createContext("/", exchange -> {
            try {
                String path = exchange.getRequestURI().getPath();
                String method = exchange.getRequestMethod();
                if (path.equals("/") && method.equals("GET")) {
                    send(exchange, 200, Files.readString(web), "text/html; charset=utf-8"); return;
                }
                if (path.equals("/health") && method.equals("GET")) {
                    send(exchange, 200, "{\"status\":\"ok\"}", "application/json"); return;
                }
                if (path.equals("/api/tasks") && method.equals("GET")) {
                    send(exchange, 200, "[" + String.join(",", store.list().stream().map(TaskFlow::json).toList()) + "]", "application/json"); return;
                }
                if (!method.equals("POST")) { send(exchange, 404, "{\"error\":\"Unknown route\"}", "application/json"); return; }
                if (!exchange.getRequestHeaders().getFirst("Content-Type").startsWith("application/x-www-form-urlencoded"))
                    throw new IllegalArgumentException("Use form-urlencoded requests");
                byte[] bytes = exchange.getRequestBody().readNBytes(8193);
                if (bytes.length > 8192) { send(exchange, 413, "{\"error\":\"Request too large\"}", "application/json"); return; }
                Map<String, String> f = form(new String(bytes, StandardCharsets.UTF_8));
                TaskStore.Task task;
                int code = 200;
                if (path.equals("/api/tasks")) { task = store.create(f.get("title"), f.get("assignee")); code = 201; }
                else {
                    String[] parts = path.split("/");
                    if (parts.length != 5 || !parts[1].equals("api") || !parts[2].equals("tasks")) throw new NoSuchElementException("Unknown route");
                    int version = Integer.parseInt(f.get("version"));
                    if (parts[4].equals("move")) task = store.move(parts[3], version, TaskStore.Status.valueOf(f.get("status")));
                    else if (parts[4].equals("comments")) task = store.comment(parts[3], version, f.get("text"));
                    else throw new NoSuchElementException("Unknown route");
                }
                send(exchange, code, json(task), "application/json");
            } catch (TaskStore.Conflict e) { error(exchange, 409, e.getMessage()); }
              catch (NoSuchElementException e) { error(exchange, 404, e.getMessage()); }
              catch (IllegalArgumentException | NullPointerException e) { error(exchange, 400, "Invalid request fields"); }
              catch (Exception e) { error(exchange, 500, "Storage or server error"); }
            finally { exchange.close(); }
        });
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { server.stop(0); pool.shutdownNow(); }));
        server.start(); return server;
    }
    static Map<String, String> form(String body) {
        Map<String, String> fields = new HashMap<>();
        if (body.isEmpty()) return fields;
        for (String pair : body.split("&")) {
            String[] kv = pair.split("=", 2);
            String key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            String value = URLDecoder.decode(kv.length > 1 ? kv[1] : "", StandardCharsets.UTF_8);
            if (fields.putIfAbsent(key, value) != null) throw new IllegalArgumentException("Duplicate field");
        }
        return fields;
    }
    static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 32) b.append(String.format("\\u%04x", (int)c));
            else b.append(c);
        }
        return b.append('"').toString();
    }
    static String json(TaskStore.Task t) {
        return "{\"id\":" + quote(t.id()) + ",\"title\":" + quote(t.title()) + ",\"assignee\":" + quote(t.assignee())
            + ",\"status\":" + quote(t.status().name()) + ",\"version\":" + t.version() + ",\"updatedAt\":" + quote(t.updatedAt())
            + ",\"comments\":[" + String.join(",", t.comments().stream().map(TaskFlow::quote).toList()) + "]}";
    }
    static void error(HttpExchange x, int status, String message) throws IOException { send(x, status, "{\"error\":" + quote(message) + "}", "application/json"); }
    static void send(HttpExchange x, int status, String body, String type) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", type);
        x.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        x.getResponseHeaders().set("Cache-Control", "no-store");
        x.sendResponseHeaders(status, bytes.length); x.getResponseBody().write(bytes);
    }
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        start(new TaskStore(Path.of("data/tasks.tsv")), port, Path.of("web/index.html"));
        System.out.println("TaskFlow: http://127.0.0.1:" + port + " (local demo, no authentication)");
    }
}
