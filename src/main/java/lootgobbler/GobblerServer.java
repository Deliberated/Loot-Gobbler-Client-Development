package lootgobbler;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import tomato.version.Version;
import util.PropertiesManager;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Connection to a Loot Gobbler server (github.com/Deliberated/Loot-Gobbler-Server, API in its docs/API.md).
 * The address and the session token are saved in the settings file, so the client reconnects on start.
 * All calls are async; failures complete the future with a {@link ServerException}.
 */
public final class GobblerServer {
    /** The API version this client speaks; the server reports its own in /status. */
    public static final int API_VERSION = 1;

    public enum State {DISCONNECTED, CONNECTING, ONLINE, UNREACHABLE}

    public static final class Status {
        public String version = "";
        public int apiVersion;
        public boolean adminClaimed;
        public String registration = "";
    }

    public static final class User {
        public long id;
        public String ign = "";
        public String status = "";
        public String role = "user";
        public boolean root;
        public long createdAt;
        public Long lastLoginAt;

        public int rank() {
            return root ? 3 : switch (role) {
                case "admin" -> 2;
                case "moderator" -> 1;
                default -> 0;
            };
        }

        public boolean atLeastModerator() {
            return rank() >= 1;
        }

        public boolean atLeastAdmin() {
            return rank() >= 2;
        }

        public String roleName() {
            return root ? "Root admin" : switch (role) {
                case "admin" -> "Admin";
                case "moderator" -> "Moderator";
                default -> "Member";
            };
        }
    }

    /** An unused moderator token (the token itself is only shown once, when created). */
    public static final class OpenToken {
        public long id;
        public String note = "";
        public String createdBy = "";
        public long createdAt;
        public Long expiresAt;
    }

    public static final class IssuedToken {
        public long id;
        public String token = "";
        public String note = "";
        public long expiresAt;
    }

    /** Outcome of {@link #test()}. */
    public static final class TestResult {
        public long millis;          // round trip of the ping
        public String yourIp = "";   // the address the server sees for this PC
        public boolean privateIp;    // a LAN/Docker address: normal at home, a proxy problem from outside
        public boolean sessionSent;
        public User user;            // null if not logged in, or the session was no longer valid
        public Status status;
    }

    /** An error answer from the server (code is its stable "error" field), or "unreachable" if there was none. */
    public static final class ServerException extends RuntimeException {
        public final int status;
        public final String code;

        ServerException(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }

    private static final String KEY_URL = "serverUrl";
    private static final String KEY_TOKEN = "serverToken";
    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final GobblerServer INSTANCE = new GobblerServer();

    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile String url = blankToEmpty(PropertiesManager.getProperty(KEY_URL));
    private volatile String token = blankToEmpty(PropertiesManager.getProperty(KEY_TOKEN));
    private volatile State state = State.DISCONNECTED;
    private volatile Status status;
    private volatile User user;
    private volatile String error = "";

    public static GobblerServer get() {
        return INSTANCE;
    }

    private GobblerServer() {
    }

    public String url() {
        return url;
    }

    public State state() {
        return state;
    }

    /** What the server reported on connect, or null. */
    public Status status() {
        return status;
    }

    /** The logged-in account, or null. */
    public User user() {
        return user;
    }

    /** Why the last connect failed. */
    public String error() {
        return error;
    }

    public boolean encrypted() {
        return url.startsWith("https://");
    }

    public void onChange(Runnable listener) {
        listeners.add(listener);
    }

    /** Reconnects to the saved server, if there is one. Called on start. */
    public void reconnect() {
        if (!url.isEmpty()) connect(url);
    }

    /**
     * Saves the address, asks the server for its status and, if a session is saved, who is logged in.
     * "loot.example.com" is read as https://loot.example.com.
     */
    public CompletableFuture<Void> connect(String address) {
        String normalized = normalize(address);
        if (!normalized.equals(url)) {
            token = "";   // a session belongs to one server
            PropertiesManager.setProperties(KEY_TOKEN, "");
        }
        url = normalized;
        PropertiesManager.setProperties(KEY_URL, normalized);
        state = State.CONNECTING;
        status = null;
        user = null;
        error = "";
        fire();
        if (normalized.isEmpty()) {
            state = State.DISCONNECTED;
            fire();
            return CompletableFuture.completedFuture(null);
        }
        return call("GET", "/status", null, Status.class)
                .thenCompose(s -> {
                    status = s;
                    state = State.ONLINE;
                    if (token.isEmpty()) return CompletableFuture.completedFuture(null);
                    return call("GET", "/me", null, UserResponse.class)
                            .thenAccept(r -> user = r.user)
                            .exceptionally(e -> null);   // an expired session just means "log in again"
                })
                .handle((ok, e) -> {
                    if (e != null) {
                        state = State.UNREACHABLE;
                        error = message(e);
                    }
                    fire();
                    return null;
                });
    }

    /**
     * Connection test: asks for the server status, then pings it (the server logs each ping, so the host sees the
     * test arrive). Also updates the connection state.
     */
    public CompletableFuture<TestResult> test() {
        TestResult result = new TestResult();
        result.sessionSent = !token.isEmpty();
        return call("GET", "/status", null, Status.class).thenCompose(st -> {
            result.status = st;
            long start = System.nanoTime();
            return call("POST", "/ping", null, Ping.class).thenApply(p -> {
                result.millis = (System.nanoTime() - start) / 1_000_000;
                result.yourIp = p.yourIp == null ? "" : p.yourIp;
                result.privateIp = isPrivate(result.yourIp);
                result.user = p.user;
                return result;
            });
        }).whenComplete((r, e) -> {
            if (e == null) {
                status = r.status;
                state = State.ONLINE;
                error = "";
                if (r.sessionSent && r.user == null) {
                    signedOut();   // the server no longer knows the session
                    return;
                }
                if (r.user != null) user = r.user;
            } else {
                state = State.UNREACHABLE;
                error = message(e);
            }
            fire();
        });
    }

    private static boolean isPrivate(String ip) {
        if (ip.isEmpty() || !(ip.contains(":") || ip.matches("[0-9.]+"))) return false;   // only literals, no DNS
        try {
            java.net.InetAddress a = java.net.InetAddress.getByName(ip);
            return a.isSiteLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()
                    || (a.getAddress().length == 16 && (a.getAddress()[0] & 0xFE) == 0xFC);   // IPv6 fc00::/7
        } catch (Exception e) {
            return false;
        }
    }

    /** Forgets the server (and logs out of it). */
    public void disconnect() {
        if (!token.isEmpty()) call("POST", "/auth/logout", null, Void.class);
        token = "";
        PropertiesManager.setProperties(KEY_TOKEN, "");
        url = "";
        PropertiesManager.setProperties(KEY_URL, "");
        state = State.DISCONNECTED;
        status = null;
        user = null;
        fire();
    }

    /**
     * Creates an account. With a valid admin or moderator token it is active right away and logged in;
     * without, it waits for approval and the future holds the pending user.
     */
    public CompletableFuture<User> register(String ign, String password, String roleToken) {
        Map<String, String> body = roleToken.isBlank() ? Map.of("ign", ign, "password", password)
                : Map.of("ign", ign, "password", password, "token", roleToken.trim());
        return call("POST", "/auth/register", body, UserResponse.class).thenCompose(r -> {
            if (!"active".equals(r.user.status)) return CompletableFuture.completedFuture(r.user);
            return login(ign, password);
        });
    }

    public CompletableFuture<User> login(String ign, String password) {
        Map<String, String> body = Map.of("ign", ign, "password", password, "client", "Loot Gobbler " + Version.VERSION);
        return call("POST", "/auth/login", body, LoginResponse.class).thenApply(r -> {
            token = r.token;
            PropertiesManager.setProperties(KEY_TOKEN, r.token);
            user = r.user;
            fire();
            return r.user;
        });
    }

    public void logout() {
        if (!token.isEmpty()) call("POST", "/auth/logout", null, Void.class);
        signedOut();
    }

    /** Applies an admin or moderator token to the logged-in account. */
    public CompletableFuture<User> redeem(String roleToken) {
        return call("POST", "/me/redeem", Map.of("token", roleToken.trim()), UserResponse.class).thenApply(r -> {
            user = r.user;
            fire();
            return r.user;
        });
    }

    public CompletableFuture<List<User>> pendingRequests() {
        return call("GET", "/admin/requests", null, UsersResponse.class).thenApply(r -> r.users);
    }

    public CompletableFuture<List<User>> members() {
        return call("GET", "/admin/users", null, UsersResponse.class).thenApply(r -> r.users);
    }

    public CompletableFuture<Void> accept(long userId) {
        return call("POST", "/admin/requests/" + userId + "/accept", null, Void.class);
    }

    public CompletableFuture<Void> deny(long userId) {
        return call("POST", "/admin/requests/" + userId + "/deny", null, Void.class);
    }

    public CompletableFuture<Void> remove(long userId) {
        return call("DELETE", "/admin/users/" + userId, null, Void.class);
    }

    public CompletableFuture<Void> setRole(long userId, String role) {
        return call("PATCH", "/admin/users/" + userId + "/role", Map.of("role", role), Void.class);
    }

    public CompletableFuture<List<OpenToken>> moderatorTokens() {
        return call("GET", "/admin/moderator-tokens", null, TokensResponse.class).thenApply(r -> r.tokens);
    }

    public CompletableFuture<IssuedToken> createModeratorToken(String note, int validHours) {
        return call("POST", "/admin/moderator-tokens", Map.of("note", note.trim(), "validHours", validHours),
                IssuedToken.class);
    }

    public CompletableFuture<Void> revokeModeratorToken(long id) {
        return call("DELETE", "/admin/moderator-tokens/" + id, null, Void.class);
    }

    /** The message to show for a failed call. */
    public static String message(Throwable t) {
        while ((t instanceof CompletionException || t instanceof java.util.concurrent.ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        if (t instanceof ServerException) return t.getMessage();
        if (t instanceof java.net.ConnectException || t instanceof java.net.http.HttpConnectTimeoutException) {
            return "Server not reachable";
        }
        if (t instanceof java.net.UnknownHostException) return "Unknown address";
        if (t instanceof javax.net.ssl.SSLException) return "Secure connection failed (" + t.getMessage() + ")";
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }

    // --- HTTP ---

    // Plain classes, not records: the bundled Gson (2.9) cannot create records.
    private static final class UserResponse {
        User user;
    }

    private static final class UsersResponse {
        List<User> users = List.of();
    }

    private static final class TokensResponse {
        List<OpenToken> tokens = List.of();
    }

    private static final class Ping {
        String yourIp;
        User user;
    }

    private static final class LoginResponse {
        String token;
        User user;
    }

    private <T> CompletableFuture<T> call(String method, String path, Object body, Class<T> type) {
        HttpRequest.Builder req;
        try {
            req = HttpRequest.newBuilder(URI.create(url + "/api/v1" + path)).timeout(Duration.ofSeconds(20));
        } catch (IllegalArgumentException e) {
            return CompletableFuture.failedFuture(new ServerException(0, "bad_address", "That is not a valid address"));
        }
        req.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(GSON.toJson(body)));
        if (body != null) req.header("Content-Type", "application/json");
        if (!token.isEmpty()) req.header("Authorization", "Bearer " + token);
        return HTTP.sendAsync(req.build(), HttpResponse.BodyHandlers.ofString()).thenApply(res -> {
            if (res.statusCode() < 300) {
                if (type == Void.class || res.body().isBlank()) return null;
                return GSON.fromJson(res.body(), type);
            }
            ServerException e = error(res);
            if (res.statusCode() == 401 && !path.startsWith("/auth/")) signedOut();   // session expired or revoked
            throw e;
        });
    }

    private static ServerException error(HttpResponse<String> res) {
        try {
            JsonObject o = JsonParser.parseString(res.body()).getAsJsonObject();
            String code = o.has("error") ? o.get("error").getAsString() : "http_" + res.statusCode();
            String message = o.has("message") ? o.get("message").getAsString() : "HTTP " + res.statusCode();
            if (o.has("retryAfter")) message += " (" + minutes(o.get("retryAfter").getAsLong()) + ")";
            return new ServerException(res.statusCode(), code, message);
        } catch (Exception notJson) {
            // e.g. nginx proxy manager's own error page when the server container is down
            return new ServerException(res.statusCode(), "http_" + res.statusCode(), "HTTP " + res.statusCode());
        }
    }

    private static String minutes(long seconds) {
        long m = (seconds + 59) / 60;
        return m <= 1 ? "wait a minute" : "wait " + m + " minutes";
    }

    private void signedOut() {
        token = "";
        PropertiesManager.setProperties(KEY_TOKEN, "");
        user = null;
        fire();
    }

    private void fire() {
        for (Runnable r : listeners) r.run();
    }

    /** The address as it is saved: "loot.example.com/" becomes "https://loot.example.com". */
    public static String normalize(String address) {
        String s = address == null ? "" : address.trim();
        if (s.isEmpty()) return "";
        if (!s.contains("://")) s = "https://" + s;
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (s.endsWith("/api/v1")) s = s.substring(0, s.length() - "/api/v1".length());
        return s;
    }

    private static String blankToEmpty(String s) {
        return s == null ? "" : s.trim();
    }
}
