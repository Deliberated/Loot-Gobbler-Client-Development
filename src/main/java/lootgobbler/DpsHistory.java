package lootgobbler;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;
import packets.incoming.MapInfoPacket;
import packets.incoming.NotificationPacket;
import tomato.backend.data.Damage;
import tomato.backend.data.Entity;
import tomato.backend.data.TomatoData;
import tomato.gui.dps.shared.DeathParser;
import tomato.realmshark.enums.CharacterClass;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Boss damage per dungeon run, kept across restarts (dps_history.json). Tomato keeps full fight data only
 * in memory; this stores a small summary of each logged run plus a once-per-second snapshot of the live run.
 * record() and liveTick() are called on the packet thread, where Tomato's fight data is safe to read.
 */
public final class DpsHistory implements Syncable {
    public static final class PlayerRow {
        public String name = "";
        public int classType;
        public int damage;
        public int taken;
        public boolean died;
        public boolean me;
        public String guild;   // their guild in this run ("" none); null in runs saved before it was kept
        // the look the character had in this run: skin (0 = the class's own) and dye textures (0 = none, see Dyes)
        public int skin, tex1, tex2;

        /** Sprite object id: the skin worn, or the class itself. */
        public int spriteId() {
            // runs logged before transformations were skipped may hold one: the class then
            return skin > 0 && !ItemDb.get().isTransformSkin(skin) ? skin : classType;
        }

        public String className() {
            String n = CharacterClass.getName(classType);
            return n == null ? "?" : n;
        }
    }

    public static final class Boss {
        public String name = "";
        public int id;   // the boss's object id in its instance, 0 in runs saved before it was kept
        public int objectType;
        public int[] loadout;   // guild hall: your weapon, ability, armor, ring for this block (-1 = empty)
        public int maxHp;
        public int endHp = -1;   // its life when the run was saved (about 0 if it died); -1 = not kept (older runs)
        public long fightMs;
        public long fightStart;   // its first hit (epoch ms); 0 in runs saved before it was kept
        public List<PlayerRow> players = new ArrayList<>();

        public long totalDamage() {
            long t = 0;
            for (PlayerRow p : players) t += p.damage;
            return t;
        }

        public double dps(PlayerRow p) {
            return p.damage / Math.max(1.0, fightMs / 1000.0);
        }

        public double share(PlayerRow p) {
            long t = totalDamage();
            return t == 0 ? 0 : 100.0 * p.damage / t;
        }

        public PlayerRow me() {
            for (PlayerRow p : players) if (p.me) return p;
            return null;
        }

        public int rankOf(PlayerRow p) {
            return players.indexOf(p) + 1;
        }
    }

    public static final class Run {
        public String dungeon = "";
        public long start;
        public long durationMs;
        public boolean guildHall;      // a guild hall visit: one block per loadout you shot with
        public List<Boss> bosses = new ArrayList<>();

        /**
         * The boss with the most HP, usually the dungeon's final boss.
         */
        public Boss mainBoss() {
            return bosses.stream().max(Comparator.comparingInt(b -> b.maxHp)).orElse(null);
        }

        /**
         * Your best boss DPS in this run, or 0 if you did not damage a boss.
         */
        public double myPeakDps() {
            double best = 0;
            for (Boss b : bosses) {
                PlayerRow me = b.me();
                if (me != null) best = Math.max(best, b.dps(me));
            }
            return best;
        }
    }

    private static final Path FILE = Path.of("dps_history.json");
    private static final int MAX_RUNS = 300;
    private static final long LIVE_INTERVAL_MS = 1000;
    private static final String GUILD_HALL = "{s.guildhall}";
    public static final int WEAPON_RACK = 480;   // guild hall object; the icon of shooting range results
    private static final Gson GSON = new Gson();
    private static final DpsHistory INSTANCE = new DpsHistory();

    private final List<Run> runs = new ArrayList<>();   // newest first
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile Run live;
    private long lastLive;
    private int liveHits;   // enemies in the hit list at the last snapshot

    public static DpsHistory get() {
        return INSTANCE;
    }

    private DpsHistory() {
        if (!Files.exists(FILE)) return;
        try (Reader r = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            List<Run> loaded = GSON.fromJson(r, new TypeToken<ArrayList<Run>>() {
            }.getType());
            if (loaded != null) runs.addAll(loaded);
        } catch (Exception e) {
            System.err.println("Could not read " + FILE + ": " + e.getMessage());
        }
    }

    public static boolean isGuildHall(MapInfoPacket map) {
        if (map == null) return false;
        // "{s.guildhall}" as the game sends it; also accept a plain name, in case it is ever sent resolved
        return GUILD_HALL.equals(map.displayName) || (map.displayName + " " + map.name).toLowerCase(Locale.ROOT).contains("guild hall");
    }

    /**
     * Called when a logged dungeon or a guild hall visit ends. Runs without boss (or shooting range) damage are not kept.
     */
    public void record(MapInfoPacket map, Collection<Entity> hitList, Collection<NotificationPacket> deaths, long durationMs) {
        Run run = summarize(map, hitList, deaths, durationMs);
        live = null;
        liveHits = 0;
        if (run != null) {
            synchronized (this) {
                runs.add(0, run);
                while (runs.size() > MAX_RUNS) runs.remove(runs.size() - 1);
                save();
            }
        }
        fire();
        if (run != null && !run.guildHall) ServerSync.get().uploadSoon();   // the guild's quest map may move on
    }

    /** The newest saved run, or null. */
    public synchronized Run latest() {
        return runs.isEmpty() ? null : runs.get(0);
    }

    /**
     * Called every game tick; refreshes the live snapshot once per second, and right away when an enemy is hit for
     * the first time, so a boss shows up the moment it is damaged.
     */
    public void liveTick(TomatoData data) {
        long now = System.currentTimeMillis();
        Entity[] hits = data.getEntityHitList();
        if (now - lastLive < LIVE_INTERVAL_MS && hits.length == liveHits) return;
        lastLive = now;
        liveHits = hits.length;
        live = summarize(data.map, Arrays.asList(hits), data.getDeathNotifications(), data.dungeonTime());
        if (isGuildHall(data.map)) writeGuildHallDebug(data.map, hits);
        fire();
    }

    /**
     * Diagnostics for the shooting range: the map names and everything that took damage, with your share, in
     * guildhall-debug.txt next to the app.
     */
    private static void writeGuildHallDebug(MapInfoPacket map, Entity[] hits) {
        StringBuilder sb = new StringBuilder("Guild hall: name=").append(map.name).append(" displayName=")
                .append(map.displayName).append('\n');
        for (Entity e : hits) {
            if (e == null) continue;
            int mine = 0, all = 0;
            for (Damage d : e.getPlayerDamageList()) {
                if (d == null) continue;
                all += d.damage;
                if (d.owner != null && d.owner.isUser()) mine += d.damage;
            }
            sb.append(String.format("  %-6d %-28s labels=%s  damage=%d  yours=%d%n", e.objectType, e.name(),
                    CharacterClass.isPlayerCharacter(e.objectType) ? "player" : assets.IdToAsset.getIdLabel(e.objectType),
                    all, mine));
        }
        try {
            Files.writeString(Path.of("guildhall-debug.txt"), sb.toString());
        } catch (Exception ignore) {
        }
    }

    public Run live() {
        return live;
    }

    public synchronized List<Run> runs() {
        return new ArrayList<>(runs);
    }

    public void remove(Run run) {
        synchronized (this) {
            runs.remove(run);
            save();
        }
        fire();
    }

    public void clear() {
        synchronized (this) {
            runs.clear();
            save();
        }
        fire();
    }

    public void onChange(Runnable listener) {
        listeners.add(listener);
    }

    @Override
    public String syncKind() {
        return "dps_history";
    }

    @Override
    public String syncLabel() {
        return "DPS history";
    }

    @Override
    public synchronized int syncCount() {
        return runs.size();
    }

    @Override
    public synchronized JsonElement exportSync() {
        return GSON.toJsonTree(runs);
    }

    /**
     * Runs only the other side has are added (a run is identified by its start time and dungeon).
     */
    @Override
    public boolean mergeSync(JsonElement remote) {
        List<Run> theirs = GSON.fromJson(remote, new TypeToken<ArrayList<Run>>() {
        }.getType());
        if (theirs == null) return false;
        boolean changed = false;
        synchronized (this) {
            Set<String> known = new HashSet<>();
            for (Run r : runs) known.add(r.start + "|" + r.dungeon);
            for (Run r : theirs) {
                if (known.add(r.start + "|" + r.dungeon)) {
                    runs.add(r);
                    changed = true;
                }
            }
            if (changed) {
                runs.sort(Comparator.comparingLong((Run r) -> r.start).reversed());   // newest first
                while (runs.size() > MAX_RUNS) runs.remove(runs.size() - 1);
                save();
            }
        }
        if (changed) fire();
        return changed;
    }

    private void fire() {
        for (Runnable r : listeners) r.run();
    }

    static Run summarize(MapInfoPacket map, Collection<Entity> hitList, Collection<NotificationPacket> deaths, long durationMs) {
        if (map == null || hitList == null) return null;
        try {
            if (isGuildHall(map)) return shootingRange(hitList, durationMs);
            Map<String, Integer> deathMap = DeathParser.parseDeathsToMap(deaths);
            // bosses and minibosses, from the first hit on (even before their max HP is known)
            List<Entity> bosses = new ArrayList<>();
            for (Entity e : hitList) {
                if (e != null && !CharacterClass.isPlayerCharacter(e.objectType) && e.isBossMob()) bosses.add(e);
            }
            if (bosses.isEmpty()) return null;
            bosses.sort(Comparator.comparingLong(Entity::getFirstDamageTaken));

            Run run = newRun(map.name, durationMs);
            for (Entity e : bosses) {
                Boss b = new Boss();
                b.name = e.name();
                b.id = e.id;
                b.objectType = e.objectType;
                b.maxHp = e.maxHp();
                b.endHp = e.hp();
                b.fightMs = Math.max(0, e.getFightDuration());
                b.fightStart = Math.max(0, e.getFirstDamageTaken());
                for (Damage d : e.getPlayerDamageList()) {
                    if (d == null || d.owner == null || d.damage <= 0) continue;
                    PlayerRow p = new PlayerRow();
                    p.name = d.owner.name() == null ? "?" : d.owner.name();
                    p.classType = d.owner.objectType;
                    look(p, d.owner);
                    p.damage = d.damage;
                    p.taken = d.owner.damageTaken(e)[0];
                    p.died = DeathParser.isDead(deathMap, p.name);
                    p.me = d.owner.isUser();
                    String guild = d.owner.getStatGuild();
                    p.guild = guild == null ? "" : guild;
                    b.players.add(p);
                }
                if (!b.players.isEmpty()) run.bosses.add(b);
            }
            return run.bosses.isEmpty() ? null : run;
        } catch (Exception e) {
            // fight data changes while being read; skip this snapshot
            return null;
        }
    }

    /**
     * The skin and dyes the player's character wears now, as the game sends them. Not while an ability transforms
     * them (Evolutionary Boar...): that skin comes without dyes and would replace their own look.
     */
    private static void look(PlayerRow p, Entity e) {
        packets.data.StatData skin = e.stat.get(packets.data.enums.StatType.SKIN_ID);
        packets.data.StatData tex1 = e.stat.get(packets.data.enums.StatType.TEX1_STAT), tex2 = e.stat.get(packets.data.enums.StatType.TEX2_STAT);
        String key = p.name + "/" + p.classType;
        if (skin != null && ItemDb.get().isTransformSkin(skin.statValue)) {   // transformed: their look from before
            int[] seen = LOOKS.get(key);
            if (seen != null) {
                p.skin = seen[0];
                p.tex1 = seen[1];
                p.tex2 = seen[2];
            }
            return;
        }
        if (skin != null && skin.statValue > 0) p.skin = skin.statValue;
        if (tex1 != null) p.tex1 = CharacterStore.liveTexture(tex1.statValue);
        if (tex2 != null) p.tex2 = CharacterStore.liveTexture(tex2.statValue);
        LOOKS.put(key, new int[]{p.skin, p.tex1, p.tex2});
    }

    // each player's last own look (name/class -> skin, tex1, tex2), for while an ability transforms them
    private static final Map<String, int[]> LOOKS = new java.util.concurrent.ConcurrentHashMap<>();

    private static Run newRun(String dungeon, long durationMs) {
        Run run = new Run();
        run.dungeon = dungeon;
        run.durationMs = Math.max(0, durationMs);
        run.start = System.currentTimeMillis() - run.durationMs;
        return run;
    }

    /**
     * A guild hall visit: one block (shown with the weapon rack) per loadout you shot with, in order, holding your
     * damage. Every hit carries your equipment at that moment; a new block starts whenever it differs from your
     * previous hit. Nothing is shown until you hit something yourself.
     */
    private static Run shootingRange(Collection<Entity> hitList, long durationMs) {
        List<Damage> mine = new ArrayList<>();
        for (Entity e : hitList) {
            if (e == null || CharacterClass.isPlayerCharacter(e.objectType)) continue;
            for (Damage d : new ArrayList<>(e.getDamageList())) {
                if (d != null && d.owner != null && d.damage > 0 && d.owner.isUser()) mine.add(d);
            }
        }
        if (mine.isEmpty()) return null;
        mine.sort(Comparator.comparingLong(d -> d.time));

        Run run = newRun("Guild Hall", durationMs);
        run.guildHall = true;
        List<long[]> spans = new ArrayList<>();   // per block: {first hit, last hit}
        Boss block = null;
        for (Damage d : mine) {
            int[] loadout = d.ownerInvntory == null ? null : Arrays.copyOf(d.ownerInvntory, 4);
            if (block == null || !Arrays.equals(block.loadout, loadout)) {
                block = new Boss();
                block.id = run.bosses.size() + 1;
                block.name = "Loadout " + block.id;
                block.objectType = WEAPON_RACK;
                block.loadout = loadout;
                PlayerRow you = new PlayerRow();
                you.name = d.owner.name() == null ? "?" : d.owner.name();
                you.classType = d.owner.objectType;
                you.me = true;
                block.players.add(you);
                run.bosses.add(block);
                spans.add(new long[]{d.time, d.time});
            }
            block.players.get(0).damage += d.damage;
            spans.get(spans.size() - 1)[1] = d.time;
        }
        for (int i = 0; i < run.bosses.size(); i++) run.bosses.get(i).fightMs = Math.max(1, spans.get(i)[1] - spans.get(i)[0]);
        return run;
    }

    private void save() {
        try {
            Path tmp = Path.of(FILE + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(runs, w);
            }
            Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            System.err.println("Could not save " + FILE + ": " + e.getMessage());
        }
    }
}
