package lootgobbler;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
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

    /** One of the player's documents on the server (see Syncable). */
    public static final class DataItem {
        public long revision;
        public long updatedAt;
        public JsonElement data;
    }

    /** A guild member and their leaderboard numbers. */
    public static final class Member {
        public long id;
        public String ign = "";
        public int guildRank;
        public long lastActive;   // last upload of any data, epoch ms (0 = never)
        public int items;         // different UT/ST items collected
        public int finds;         // all copies found
        public long fame;         // current fame of all living characters
        public int exalts;        // exaltations over all classes
        public int characters;    // living characters
        public int maxed;         // living characters with all 8 stats maxed
        public int points;        // quest points this month
        public List<Tally> tally = List.of();   // every drop ever logged, for the item score
    }

    /** How often a member found an item in one place with that many enchantments (-1 = unknown). */
    public static final class Tally {
        public int itemId;
        public String where = "";
        public int enchants = -1;
        public int count;
    }

    /** The item score weights (0 to 1 each), set by an admin. */
    public static final class ScoreWeights {
        public double feedPower = 1, difficulty = 1, rarity = 1;
    }

    /** What the guild views compute with: baselineHpm is the hits per minute a run counts as average. */
    public static final class GuildSettings {
        public double baselineHpm = 50;
    }

    public static final class Leaderboard {
        public String guild = "";
        public List<Member> members = List.of();
    }

    /** One drop in the guild feed. time is the dropping player's local time ("2026-11-01T16:08:28"). */
    public static final class GuildDrop {
        public long userId;
        public String ign = "";
        public int itemId;
        public String time = "";
        public int enchants = -1;
        public String where = "";
        public String bag = "";
        public List<Integer> enchantIds;
    }

    public static final class GuildDrops {
        public String guild = "";
        public List<GuildDrop> drops = List.of();
    }

    /** A guild member's profile: characters (without fame history), exaltations and collection counts. */
    public static final class MemberProfile {
        public String guild = "";
        public Member member;
        public Map<String, CharacterStore.Char> characters = Map.of();
        public Map<String, int[]> exalts = Map.of();
        public Map<String, Integer> collection = Map.of();   // item id -> copies found
    }

    /** One item in a caravan member's loot. bag is the bag's color ("White", "Orange", ..., "Other"). */
    public static final class CaravanItem {
        public int itemId;
        public int enchants = -1;   // -1 = unknown (servers that do not send it yet)
        public String bag = "";
        public String time = "";
        public String where = "";
    }

    /** A caravan member's most played character in the range. */
    public static final class CaravanCharacter {
        public int classType;
        public String className = "";
        public int skin, tex1, tex2;
        public long fame;
    }

    /** A guild member in the loot caravan: items found in the range (counted per bag color), the items, most played character. */
    public static final class CaravanMember {
        public long id;
        public String ign = "";
        public int guildRank;
        public Map<String, Integer> byBag = Map.of();   // bag color -> items found
        public int total;                               // all items found
        public List<CaravanItem> items = List.of();   // newest first, at most 120
        public List<Tally> tally = List.of();         // all items of the range, for the score (empty from older servers)
        public String lastDrop;                        // the player's local time, null if none
        public CaravanCharacter character;             // null if unknown
    }

    public static final class Caravan {
        public String guild = "";
        public String range = "";
        public List<CaravanMember> members = List.of();   // most items first (the client sorts them by score)
    }

    /** Who cleared a quest map stage, and when (epoch ms). */
    public static final class QuestMapClear {
        public long userId;
        public String ign = "";
        public long at;
    }

    /**
     * A guild quest map: dungeons cleared in order by any member. style is "parchment", "hex", "overworld", "oryx" or
     * "constellation"; variant its color variation 0-3.
     */
    public static final class QuestMap {
        public long id;
        public String name = "";
        public String style = "";
        public int variant;
        public List<String> dungeons = List.of();
        public List<QuestMapClear> clears = List.of();   // one per cleared stage, in order
        public long startsAt, endsAt;
        public Long completedAt;
    }

    public static final class QuestMaps {
        public String guild = "";
        public long now;
        public QuestMap current;
        public List<QuestMap> past = List.of();   // newest first
    }

    /** What a quest asks for. count is required; dungeon (part of the name), minEnchants and bag narrow it down. */
    public static final class QuestParams {
        public long count;
        public String dungeon;
        public int minEnchants;
        public String bag;
    }

    public static final class QuestContribution {
        public String ign = "";
        public long amount;
    }

    /** A quest of this week with its progress. type: drops, new_items, dungeon_clears, boss_damage or fame. */
    public static final class Quest {
        public long id;
        public String title = "";
        public String type = "";
        public QuestParams params = new QuestParams();
        public int points;
        public long progress;
        public long target;
        public Long completedAt;
        public List<QuestContribution> contributors = List.of();   // guild quests, most first
    }

    public static final class Quests {
        public long weekStart, weekEnd;   // Monday 00:00 UTC to the next Monday
        public List<Quest> personal = List.of();
        public String guild;              // null while your guild is unknown
        public List<Quest> guildQuests = List.of();
        public String period = "";        // "2026-11": points count per month
        public int points;                // your points this period
    }

    /** A quest in the pool. status: pending (suggested), approved or denied; scope: personal or guild. */
    public static final class QuestTemplate {
        public long id;
        public String title = "";
        public String type = "";
        public QuestParams params = new QuestParams();
        public String scope = "";
        public int points;
        public String status = "";
        public String proposedBy = "";    // empty = built-in suggestion
        public long createdAt;
    }

    /** An error answer from the server (code is its stable "error" field). */
    public static final class ServerException extends RuntimeException {
        public final int status;
        public final String code;
        /** On a revision conflict: the document the server has. */
        public final DataItem current;

        ServerException(int status, String code, String message, DataItem current) {
            super(message);
            this.status = status;
            this.code = code;
            this.current = current;
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

    /** All of the player's documents on the server, by kind. */
    public CompletableFuture<Map<String, DataItem>> data() {
        return call("GET", "/me/data", null, DataResponse.class).thenApply(r -> r.items);
    }

    /**
     * Uploads a document. baseRevision is the revision it was based on (0 for a new one); if the server has a
     * different one, the call fails with a ServerException whose code is "revision_conflict" and whose
     * {@link ServerException#current} holds the server's version.
     *
     * @return Future holding the new revision.
     */
    public CompletableFuture<Long> putData(String kind, long baseRevision, JsonElement data) {
        JsonObject body = new JsonObject();
        body.addProperty("baseRevision", baseRevision);
        body.add("data", data);
        return call("PUT", "/me/data/" + kind, body, PutResponse.class).thenApply(r -> r.revision);
    }

    /**
     * Your guild's leaderboard. Fails with code "no_guild" until your client has seen your guild in the game.
     */
    public CompletableFuture<Leaderboard> leaderboard() {
        return call("GET", "/guild/leaderboard", null, Leaderboard.class);
    }

    /** Your guild's most recent drops, newest first (at most 200). */
    public CompletableFuture<GuildDrops> guildDrops(int limit) {
        return call("GET", "/guild/drops?limit=" + limit, null, GuildDrops.class);
    }

    /** The item score weights. */
    public CompletableFuture<ScoreWeights> scoring() {
        return call("GET", "/scoring", null, ScoreWeights.class);
    }

    /** Admins only: changes the item score weights (each 0 to 1); returns them as stored. */
    public CompletableFuture<ScoreWeights> putScoring(ScoreWeights w) {
        return call("PUT", "/admin/scoring", w, ScoreWeights.class);
    }

    /** The guild settings. */
    public CompletableFuture<GuildSettings> guildSettings() {
        return call("GET", "/guild/settings", null, GuildSettings.class);
    }

    /** Admins only: changes the guild settings (baselineHpm 1 to 1000); returns them as stored. */
    public CompletableFuture<GuildSettings> putGuildSettings(GuildSettings g) {
        return call("PUT", "/admin/guild/settings", g, GuildSettings.class);
    }

    /** Your guild's loot caravan; range is "week", "month" or "all". */
    public CompletableFuture<Caravan> caravan(String range) {
        return call("GET", "/guild/caravan?range=" + range, null, Caravan.class);
    }

    /** Your guild's current quest map (the server rolls a new one when the last has ended) and up to limit past ones. */
    public CompletableFuture<QuestMaps> questMaps(int limit) {
        return call("GET", "/guild/questmaps?limit=" + limit, null, QuestMaps.class);
    }

    /**
     * Your guildmates' looks in a run: what their own clients saved with their visit of that dungeon at that time
     * (source "visit"), else how their character of that class looks now (source "character").
     */
    public static final class RunLooks {
        public List<RunLook> looks = List.of();
    }

    public static final class RunLook {
        public String player = "";
        public int classType, skin, tex1, tex2;
        public String source = "";   // "visit" or "character"
        public boolean alive;
        public long lastPlayed;
        public int maxHp;
        public long[] hitAt = new long[0];   // the hits they took in the run, ms after its start (asked "from")
        public int[] hitDamage = new int[0];
        public long dealt;   // damage they dealt in the run (0 from older clients)
        public int[] quickUsed;   // items they used from a quick slot; null from older clients (not known)
        public long[] quickAt;   // when each was used, ms after the asked "from"; null from older servers
        public long moveAt;   // when their moved[0] second starts, ms after the asked "from"
        public int[] moved = new int[0];   // how far they walked, tenths of a tile per second (none from older clients)
    }

    /** A guildmate's most completed dungeon over all their characters, and the look of the one that did it most. */
    public static final class Favorite {
        public String dungeon = "";
        public int count;
        public int classType, skin, tex1, tex2;
    }

    /** Numbers about one guildmate: their favourite dungeon (all time), and over the days asked their deaths and more. */
    public static final class MemberFacts {
        public long userId;
        public String ign = "";
        public Favorite favorite;   // null: their client does not send completion counts
        public int deaths;          // characters that died
        public long fame;           // fame gained by all their characters
        public long dealt, taken;   // damage dealt and taken in dungeons
        public int hits;            // hits taken in dungeons ...
        public long inMs;           // ... in this much time spent in them
        public int exalts;          // exaltations earned in dungeons
        public Favorite look;       // the character they played last (only its look); null if unknown
        public int loot;            // items they found
        public int played;          // different characters they played
    }

    public static final class Facts {
        public int days;
        public List<MemberFacts> members = List.of();
    }

    /** Every guildmate's facts (yours too) over the last days. */
    public CompletableFuture<Facts> guildFacts(int days) {
        return call("GET", "/guild/facts?days=" + days, null, Facts.class);
    }

    /** Looks of guildmates who may have been in this dungeon between from and to (epoch ms). */
    public CompletableFuture<RunLooks> runLooks(String dungeon, long from, long to) {
        return call("GET", "/guild/looks?dungeon=" + java.net.URLEncoder.encode(dungeon, java.nio.charset.StandardCharsets.UTF_8)
                + "&from=" + from + "&to=" + to, null, RunLooks.class);
    }

    public CompletableFuture<MemberProfile> memberProfile(long userId) {
        return call("GET", "/guild/members/" + userId, null, MemberProfile.class);
    }

    /** Your quests of this week (personal and guild) and your points this month. */
    public CompletableFuture<Quests> quests() {
        return call("GET", "/quests", null, Quests.class);
    }

    /** Suggests a quest; it waits for an admin to accept or deny it. title "" = generated by the server. */
    public CompletableFuture<QuestTemplate> suggestQuest(String title, String type, QuestParams params, String scope) {
        JsonObject body = new JsonObject();
        body.addProperty("title", title.trim());
        body.addProperty("type", type);
        body.add("params", GSON.toJsonTree(params));
        body.addProperty("scope", scope);
        return call("POST", "/quests/suggestions", body, QuestTemplate.class);
    }

    /** Admins: the quest pool; status "" = all. */
    public CompletableFuture<List<QuestTemplate>> questPool(String status) {
        return call("GET", "/admin/quests" + (status.isEmpty() ? "" : "?status=" + status), null, QuestPool.class)
                .thenApply(r -> r.quests);
    }

    /** Admins: accepts a quest as "personal" or "guild" quest; points 0 = keep the suggested points. */
    public CompletableFuture<Void> approveQuest(long id, String scope, int points) {
        JsonObject body = new JsonObject();
        body.addProperty("scope", scope);
        body.addProperty("points", points);
        return call("POST", "/admin/quests/" + id + "/approve", body, Void.class);
    }

    /** Admins: denies a suggestion, or takes an accepted quest out of the pool. */
    public CompletableFuture<Void> denyQuest(long id) {
        return call("POST", "/admin/quests/" + id + "/deny", null, Void.class);
    }

    /** The cause behind CompletableFuture wrappers. */
    public static Throwable unwrap(Throwable t) {
        while ((t instanceof CompletionException || t instanceof java.util.concurrent.ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    /** The message to show for a failed call. */
    public static String message(Throwable t) {
        t = unwrap(t);
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

    private static final class DataResponse {
        Map<String, DataItem> items = Map.of();
    }

    private static final class QuestPool {
        List<QuestTemplate> quests = List.of();
    }

    private static final class PutResponse {
        long revision;
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
            return CompletableFuture.failedFuture(new ServerException(0, "bad_address", "That is not a valid address", null));
        }
        req.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(GSON.toJson(body)));
        if (body != null) req.header("Content-Type", "application/json");
        if (!token.isEmpty()) req.header("Authorization", "Bearer " + token);
        long start = System.nanoTime();
        return HTTP.sendAsync(req.build(), HttpResponse.BodyHandlers.ofString()).whenComplete((res, e) -> {
            // method, path and outcome only: request bodies can hold passwords and tokens
            String took = " (" + (System.nanoTime() - start) / 1_000_000 + " ms)";
            if (e != null) AppLog.log("server", method + " " + path + " failed: " + message(e) + took);
            else AppLog.log("server", method + " " + path + " -> " + res.statusCode() + took
                    + (res.statusCode() >= 300 ? " " + abbreviate(res.body()) : ""));
        }).thenApply(res -> {
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
            DataItem current = o.has("current") ? GSON.fromJson(o.get("current"), DataItem.class) : null;
            return new ServerException(res.statusCode(), code, message, current);
        } catch (Exception notJson) {
            // e.g. nginx proxy manager's own error page when the server container is down
            return new ServerException(res.statusCode(), "http_" + res.statusCode(), "HTTP " + res.statusCode(), null);
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

    private String logged = "";

    private void fire() {
        String now = state + (user == null ? "" : ", logged in as " + user.ign) + (error.isEmpty() ? "" : ", " + error);
        if (!now.equals(logged)) {
            logged = now;
            AppLog.log("server", "State " + now + (url.isEmpty() ? "" : " (" + url + ")"));
        }
        for (Runnable r : listeners) r.run();
    }

    private static String abbreviate(String body) {
        String s = body == null ? "" : body.replaceAll("\s+", " ").trim();
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
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
