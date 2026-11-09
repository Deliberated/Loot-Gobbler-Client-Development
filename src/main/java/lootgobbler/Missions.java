package lootgobbler;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The season's missions (the battle pass trees, e.g. The Whispers of Skuld), as the game's web API tells them with the
 * login token the sniffer sees: missions/getClientSeasons gives what each mission asks and rewards,
 * missions/getPlayerMissions your progress and what you claimed. While you play, the game connection sends small
 * progress steps (packet 165, "prog/season,mission,a:b:c"), added on top until the next fetch replaces them. The last
 * answers are kept in files, so the missions show before the next login too.
 */
public final class Missions {
    private static final Path DEFS_FILE = Path.of("missions-seasons.json");
    private static final Path PLAYER_FILE = Path.of("missions-player.xml");
    private static final long ASK_EVERY_MS = 5 * 60_000L;   // at most this often on its own (a login, a claim)
    private static final Gson GSON = new Gson();

    private static final class Holder {   // made on first use, after every static field above and below is set
        static final Missions INSTANCE = new Missions();
    }

    // ---- what the game says ------------------------------------------------------------------------------------

    public static final class Seasons {
        public List<Season> seasons = new ArrayList<>();
    }

    /** One mission tree (a battle pass can have several). */
    public static final class Season {
        public int id;
        public String name = "";
        public int current, available;   // 1 = the running one / one you can do
        public long endDate;             // unix seconds; 0 = not said
        public List<Mission> missions = new ArrayList<>();
        public com.google.gson.JsonObject gameData;   // the board: its background picture, mission positions

        /** The web address of the battle pass's picture (the board's background), or null. */
        public String backgroundUrl() {
            try {
                String url = gameData == null ? null : gameData.get("backgroundSprite").getAsString();
                return url != null && url.startsWith("https://") ? url : null;
            } catch (Exception e) {
                return null;
            }
        }
    }

    public static final class Mission {
        public int id;
        public String name = "", desc = "";
        public List<Cond> conds = new ArrayList<>();
        public String condSpread;        // conds with the same digit are alternatives ("one of"); null: all needed
        public List<Reward> rewards = new ArrayList<>();
        public String rewardsSpread;     // rewards with the same digit are alternatives (you pick one); null: all
        public JsonElement wornRestriction;   // "" or [{type, display}]: what must be equipped to make progress
        public String parentsStr = "";   // the missions it comes after, e.g. "9,18"
        public String icon = "";         // its picture in the game's sprites: "sheet/index", e.g. "lofiObjBig/528"

        /** The rewards grouped: within a group you get one of them, every group is given. */
        public List<List<Reward>> rewardGroups() {
            List<List<Reward>> out = new ArrayList<>();
            if (rewards == null) return out;
            if (rewardsSpread == null || rewardsSpread.length() != rewards.size()) {
                for (Reward r : rewards) out.add(List.of(r));
                return out;
            }
            Map<Character, List<Reward>> by = new java.util.LinkedHashMap<>();
            for (int i = 0; i < rewards.size(); i++) by.computeIfAbsent(rewardsSpread.charAt(i), k -> new ArrayList<>()).add(rewards.get(i));
            out.addAll(by.values());
            return out;
        }

        /** What must be equipped to make progress ("Orb"), or empty. */
        public List<String> worn() {
            List<String> out = new ArrayList<>();
            if (wornRestriction == null || !wornRestriction.isJsonArray()) return out;
            for (JsonElement e : wornRestriction.getAsJsonArray()) {
                if (!e.isJsonObject()) continue;
                JsonElement d = e.getAsJsonObject().get("display"), t = e.getAsJsonObject().get("type");
                String s = d != null && !d.isJsonNull() ? d.getAsString() : t != null && !t.isJsonNull() ? nice(t.getAsString()) : "";
                if (!s.isBlank()) out.add(s);
            }
            return out;
        }
        public double interval;          // repeats after this many days; 0 = once
        public int participants;         // 255 everyone, 2 seasonal characters only, 4 crucible only

        public boolean repeatable() {
            return interval > 0 || (name != null && name.contains("Repeatable"));
        }

        public List<Integer> parents() {
            List<Integer> out = new ArrayList<>();
            if (parentsStr == null) return out;
            for (String s : parentsStr.split("\\D+")) if (!s.isEmpty()) out.add(Integer.parseInt(s));
            return out;
        }

        /** The objectives grouped: within a group any one is enough, every group is needed. */
        public List<List<Integer>> groups() {
            List<List<Integer>> out = new ArrayList<>();
            if (conds == null) return out;
            if (condSpread == null || condSpread.length() != conds.size()) {   // no alternatives: every one is needed
                for (int i = 0; i < conds.size(); i++) out.add(List.of(i));
                return out;
            }
            Map<Character, List<Integer>> by = new java.util.LinkedHashMap<>();
            for (int i = 0; i < conds.size(); i++) by.computeIfAbsent(condSpread.charAt(i), k -> new ArrayList<>()).add(i);
            out.addAll(by.values());
            return out;
        }

        /** Whether progress meets every objective group. */
        public boolean complete(int[] progress) {
            List<List<Integer>> groups = groups();
            if (groups.isEmpty()) return false;
            for (List<Integer> g : groups) {
                boolean any = false;
                for (int i : g) if (i < progress.length && progress[i] >= conds.get(i).amount) any = true;
                if (!any) return false;
            }
            return true;
        }
    }

    public static final class Cond {
        public int type;
        public JsonElement target;   // a name, a difficulty range ("7-10") or a number, depending on the type
        public int amount;
        public Integer time;         // seconds, for timed runs

        public String target() {
            if (target == null || target.isJsonNull()) return "";
            return target instanceof JsonPrimitive p ? p.getAsString() : target.toString();
        }

        /** What it asks, as a sentence ("Complete 3 Ocean Trench"). */
        public String text() {
            String n = amount > 0 ? amount + " " : "";
            String t = nice(target());
            return switch (type) {
                case 1 -> "Defeat " + n + t;
                case 2 -> "Defeat " + n + "enemies";
                case 3 -> "Complete " + n + t;
                case 4 -> "Complete " + n + "dungeons of difficulty " + t;
                case 7 -> "Close " + n + "realms";
                case 12 -> "Complete " + n + (amount == 1 ? "dungeon" : "dungeons") + " of difficulty " + t;
                case 14 -> "Complete " + n + t + (time != null ? " in under " + clock(time) : "");
                case 15 -> "Gain " + String.format(Locale.ENGLISH, "%,d", amount) + " EXP";
                default -> t.isEmpty() ? "Objective" : "Complete " + n + t;
            };
        }
    }

    public static final class Reward {
        public int type;             // 1 battle pass points (amount), 2 an item (target = its id, amount = how many)
        public JsonElement target;
        public long amount;

        public int itemId() {
            try {
                return target == null || target.isJsonNull() ? 0 : Integer.parseInt(target.getAsString().trim());
            } catch (Exception e) {
                return 0;
            }
        }
    }

    /** Your state of one mission. */
    private static final class Prog {
        int[] progress = new int[0];
        int state;          // 0 working on it, 1 done (to claim), 2/3 claimed
        long claimedAt;     // unix seconds, 0 = never
        int claims;
        boolean everDone;   // claimed once: the missions after it stay open (a repeatable resets to 0)
    }

    // ---- what is shown ---------------------------------------------------------------------------------------

    public enum Status {CLAIMABLE, IN_PROGRESS, COOLDOWN, CLAIMED, LOCKED}

    /** One mission as shown: its definition, your progress and where it stands. */
    public record View(Season season, Mission mission, int[] progress, Status status, long resetsAt) {
    }

    private Seasons defs;
    private final Map<String, Prog> progs = new HashMap<>();   // by "season:mission"
    private final Set<String> locked = new HashSet<>(), unlocked = new HashSet<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile String token;
    private volatile long asked, fetched;   // when last asked / answered (ms)
    private volatile String problem;        // why the last fetch failed; null if it did not
    private volatile boolean fetching;

    public static Missions get() {
        return Holder.INSTANCE;
    }

    private Missions() {
        try {
            if (Files.exists(DEFS_FILE)) defs = parseDefs(Files.readString(DEFS_FILE, StandardCharsets.UTF_8));
            if (Files.exists(PLAYER_FILE)) {
                applyPlayer(Files.readString(PLAYER_FILE, StandardCharsets.UTF_8));
                fetched = Files.getLastModifiedTime(PLAYER_FILE).toMillis();
            }
        } catch (Exception e) {
            System.err.println("Could not read the missions: " + e.getMessage());
        }
    }

    public void onChange(Runnable listener) {
        listeners.add(listener);
    }

    private void fire() {
        for (Runnable r : listeners) r.run();
    }

    /** The login token: asks for the missions (at most every 5 minutes). */
    public void tokenReady(String token) {
        this.token = token;
        if (System.currentTimeMillis() - asked >= ASK_EVERY_MS) fetch();
    }

    public boolean canFetch() {
        return token != null;
    }

    public boolean fetching() {
        return fetching;
    }

    public long fetchedAt() {
        return fetched;
    }

    public String problem() {
        return problem;
    }

    /** Asks the game for the missions and your progress, in the background. */
    public void fetch() {
        String t = token;
        if (t == null || fetching) return;
        fetching = true;
        asked = System.currentTimeMillis();
        fire();
        Thread th = new Thread(() -> {
            try {
                String seasons = tomato.realmshark.HttpCharListRequest.webRequest(t, "missions/getClientSeasons");
                String player = tomato.realmshark.HttpCharListRequest.webRequest(t, "missions/getPlayerMissions");
                if (seasons == null || player == null || seasons.contains("<Error") || player.contains("<Error")) {
                    String why = seasons == null || player == null ? "no answer" : (seasons.contains("<Error") ? seasons : player).strip();
                    problem = why.length() > 200 ? why.substring(0, 200) : why;
                    AppLog.log("game", "Missions refused: " + problem);
                    return;
                }
                Seasons parsed = parseDefs(seasons);
                synchronized (this) {
                    defs = parsed;
                    progs.clear();
                    applyPlayer(player);
                }
                Files.writeString(DEFS_FILE, seasons, StandardCharsets.UTF_8);
                Files.writeString(PLAYER_FILE, player, StandardCharsets.UTF_8);
                fetched = System.currentTimeMillis();
                problem = null;
                AppLog.log("game", "Missions: " + parsed.seasons.size() + " trees, "
                        + parsed.seasons.stream().mapToInt(s -> s.missions.size()).sum() + " missions");
            } catch (Exception e) {
                problem = e.getMessage();
                AppLog.log("game", "Missions failed: " + e.getMessage());
            } finally {
                fetching = false;
                fire();
            }
        }, "missions");
        th.setDaemon(true);
        th.start();
    }

    private static Seasons parseDefs(String json) {
        Seasons s = GSON.fromJson(json, Seasons.class);
        if (s == null) s = new Seasons();
        if (s.seasons == null) s.seasons = new ArrayList<>();
        for (Season season : s.seasons) {
            if (season.missions == null) season.missions = new ArrayList<>();
            for (Mission m : season.missions) {
                if (m.conds == null) m.conds = new ArrayList<>();
                if (m.rewards == null) m.rewards = new ArrayList<>();
            }
        }
        return s;
    }

    private static final Pattern SEASON_BLOCK = Pattern.compile("<Season\\s[^>]*id=\"(\\d+)\"[^>]*>(.*?)</Season>", Pattern.DOTALL);

    /** Reads getPlayerMissions: per tree "mission,a:b:c,state,updated,claims,claimedAt|..." and the lock lists. */
    private synchronized void applyPlayer(String xml) {
        locked.clear();
        unlocked.clear();
        pairs(tag(xml, "LockedMissions"), locked);
        pairs(tag(xml, "UnlockedMissions"), unlocked);
        Matcher m = SEASON_BLOCK.matcher(xml);
        while (m.find()) {
            int tree = Integer.parseInt(m.group(1));
            for (String entry : m.group(2).split("\\|")) {
                String[] f = entry.trim().split(",");
                if (f.length < 3) continue;
                try {
                    Prog p = new Prog();
                    int id = Integer.parseInt(f[0].trim());
                    p.progress = ints(f[1]);
                    p.state = Integer.parseInt(f[2].trim());
                    if (f.length > 4) p.claims = Math.max(0, Integer.parseInt(f[4].trim()));
                    if (f.length > 5) p.claimedAt = Math.max(0, Long.parseLong(f[5].trim()));
                    Mission def = mission(tree, id);
                    p.everDone = p.state >= 2 || p.claims > 0 || p.claimedAt > 0;   // claimed: what comes after opens
                    progs.put(tree + ":" + id, p);
                } catch (NumberFormatException ignored) {
                }
            }
        }
    }

    /**
     * A progress step from the game connection (packet 165): "prog/season,mission,a:b:c|..." (after an optional
     * "pool/#"); the numbers are added to what you had. Ignored until the missions are known.
     */
    public void progressPacket(String s) {
        if (s == null) return;
        s = s.trim();
        if (s.startsWith("pool/#")) s = s.substring(6);
        if (!s.startsWith("prog/")) return;
        boolean changed = false;
        synchronized (this) {
            if (defs == null) return;
            for (String entry : s.substring(5).split("\\|")) {
                String[] f = entry.trim().split(",");
                if (f.length < 3) continue;
                try {
                    int tree = Integer.parseInt(f[0].trim()), id = Integer.parseInt(f[1].trim());
                    Mission def = mission(tree, id);
                    if (def == null) continue;
                    int[] step = ints(f[2]);
                    Prog p = progs.computeIfAbsent(tree + ":" + id, k -> new Prog());
                    if (p.progress.length < step.length) p.progress = java.util.Arrays.copyOf(p.progress, step.length);
                    boolean any = false;
                    for (int i = 0; i < step.length; i++) {
                        if (step[i] == 0) continue;
                        p.progress[i] += step[i];
                        any = true;
                    }
                    if (!any) continue;
                    changed = true;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (changed) fire();
    }

    /** You claimed a mission (packet 163): asked again shortly, to show what the game made of it. */
    public void claimed() {
        Thread th = new Thread(() -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ignored) {
            }
            asked = 0;
            fetch();
        }, "missions-claim");
        th.setDaemon(true);
        th.start();
    }

    public synchronized boolean hasData() {
        return defs != null && !defs.seasons.isEmpty();
    }

    /** The trees you can do (the running one first), each with its missions as shown. */
    public synchronized Map<Season, List<View>> views() {
        Map<Season, List<View>> out = new java.util.LinkedHashMap<>();
        if (defs == null) return out;
        List<Season> seasons = new ArrayList<>(defs.seasons);
        seasons.removeIf(s -> s.available == 0 && s.current == 0);
        seasons.sort((a, b) -> Integer.compare(b.current, a.current));
        long now = System.currentTimeMillis() / 1000;
        for (Season s : seasons) {
            List<View> list = new ArrayList<>();
            for (Mission m : s.missions) list.add(view(s, m, now));
            out.put(s, list);
        }
        return out;
    }

    private View view(Season s, Mission m, long now) {
        Prog p = progs.get(s.id + ":" + m.id);
        int[] progress = p == null ? new int[m.conds.size()] : java.util.Arrays.copyOf(p.progress, Math.max(p.progress.length, m.conds.size()));
        long resetsAt = 0;
        if (p != null && p.state >= 2) {
            if (m.repeatable() && p.claimedAt > 0) {
                resetsAt = p.claimedAt + Math.round(m.interval * 86_400);
                if (resetsAt > now) return new View(s, m, progress, Status.COOLDOWN, resetsAt);
                // the cooldown is over: open again, from what was counted since (the claim's progress is gone)
                return new View(s, m, new int[m.conds.size()], Status.IN_PROGRESS, 0);
            }
            return new View(s, m, progress, Status.CLAIMED, 0);
        }
        boolean started = p != null && (p.state >= 1 || java.util.Arrays.stream(p.progress).anyMatch(v -> v > 0));
        boolean engaged = started || (p != null && p.everDone);
        boolean parentsDone = true;
        for (int parent : m.parents()) if (!done(s.id, parent)) parentsDone = false;
        String key = s.id + ":" + m.id;
        boolean open = parentsDone || unlocked.contains(key) || engaged;
        if ((locked.contains(key) && !engaged) || !open) return new View(s, m, progress, Status.LOCKED, 0);
        if ((p != null && p.state == 1) || m.complete(progress)) return new View(s, m, progress, Status.CLAIMABLE, 0);
        return new View(s, m, progress, Status.IN_PROGRESS, 0);
    }

    private boolean done(int tree, int id) {
        Prog p = progs.get(tree + ":" + id);
        if (p == null) return false;
        Mission def = mission(tree, id);
        return p.everDone || p.state >= 2;   // claimed (finished but not claimed does not open the next)
    }

    private Mission mission(int tree, int id) {
        if (defs == null) return null;
        for (Season s : defs.seasons) {
            if (s.id != tree) continue;
            for (Mission m : s.missions) if (m.id == id) return m;
        }
        return null;
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private static int[] ints(String s) {
        String[] parts = s.trim().split(":");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }

    private static String tag(String xml, String name) {
        int open = xml.indexOf("<" + name);
        if (open < 0) return null;
        int start = xml.indexOf('>', open);
        if (start < 0 || xml.charAt(start - 1) == '/') return null;
        int end = xml.indexOf("</" + name + ">", start);
        return end < 0 ? null : xml.substring(start + 1, end);
    }

    private static void pairs(String section, Set<String> into) {
        if (section == null) return;
        for (String e : section.split(";")) {
            String[] f = e.trim().split(",");
            if (f.length == 2 && f[0].trim().matches("\\d+") && f[1].trim().matches("\\d+")) into.add(f[0].trim() + ":" + f[1].trim());
        }
    }

    /** "ADEPT_ENCOUNTER" as "Adept Encounter"; real names stay as they are. */
    static String nice(String target) {
        String t = target == null ? "" : target.trim();
        if (t.startsWith("BOSS_")) t = t.substring(5);
        if (t.isEmpty() || !t.matches("[A-Z0-9_]+")) return t;
        StringBuilder sb = new StringBuilder();
        for (String w : t.split("_")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(w.charAt(0)).append(w.substring(1).toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    private static String clock(int seconds) {
        return seconds >= 60 ? seconds / 60 + ":" + String.format(Locale.ROOT, "%02d", seconds % 60) + " min" : seconds + " s";
    }
}
