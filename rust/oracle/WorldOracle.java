package oracle;

import packets.Packet;
import packets.PacketType;
import packets.data.StatData;
import packets.data.WorldPosData;
import packets.incoming.MapInfoPacket;
import tomato.backend.TomatoPacketCapture;
import tomato.backend.data.Entity;
import tomato.backend.data.TomatoData;

import java.io.PrintStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.CRC32;

/**
 * The Java client's world state (TomatoData, fed by TomatoPacketCapture as the app feeds it) on a recording, for
 * comparing the Rust port: after each packet the app hands to TomatoPacketCapture, a line with the CRC-32 of the
 * whole state; the full state after each map change and at the end. Format: lg-world's `dump`.
 * <p>
 * Run it in an empty folder holding only a copy of the game's assets: the app's parts write their files into the
 * working folder. HELLO packets are never handed on (they make the app log in to the game's servers with your token),
 * and compare.sh points Java's network at a closed local port as well.
 */
final class WorldOracle {
    /** The packets the app registers TomatoPacketCapture for (Tomato.packetRegister), without HELLO. */
    private static final Set<PacketType> FED = Set.of(PacketType.CREATE_SUCCESS, PacketType.ENEMYHIT,
            PacketType.PLAYERSHOOT, PacketType.DAMAGE, PacketType.PLAYERHIT, PacketType.ENEMYSHOOT,
            PacketType.GROUNDDAMAGE, PacketType.AOE, PacketType.MOVE, PacketType.USEITEM, PacketType.DEATH,
            PacketType.SERVERPLAYERSHOOT, PacketType.UPDATE, PacketType.NEWTICK, PacketType.MAPINFO, PacketType.STASIS,
            PacketType.TEXT, PacketType.NOTIFICATION, PacketType.EXALTATION_BONUS_CHANGED, PacketType.VAULT_UPDATE,
            PacketType.INVSWAP, PacketType.INVRESULT, PacketType.QUEST_FETCH_RESPONSE, PacketType.TRADEDONE,
            PacketType.TRADEREQUESTED, PacketType.CRUCIBLE_RESPONSE,
            // what the app's own listeners (ItemUse) are registered for
            PacketType.FORGE_REQUEST, PacketType.FORGE_RESULT, PacketType.PETUPGRADEREQUEST, PacketType.INVDROP,
            PacketType.BUYRESULT, PacketType.LOGIN_REWARD_MSG, PacketType.CHEST_REWARD_RESULT, PacketType.CLAIM_BATTLE_PASS,
            PacketType.CLAIM_BP_MILESTONE_RESULT, PacketType.REDEEM_EXALTATION_REWARD, PacketType.TRADESTART,
            PacketType.ACCEPTTRADE, PacketType.TRADEACCEPTED, PacketType.QUEST_REDEEM, PacketType.QUEST_REDEEM_RESPONSE);
    /** TomatoPacketCapture's packets, in the order Tomato.packetRegister registers them (HELLO left out). */
    private static final PacketType[] CAPTURE_ORDER = {PacketType.CREATE_SUCCESS, PacketType.ENEMYHIT,
            PacketType.PLAYERSHOOT, PacketType.DAMAGE, PacketType.PLAYERHIT, PacketType.ENEMYSHOOT,
            PacketType.GROUNDDAMAGE, PacketType.AOE, PacketType.MOVE, PacketType.USEITEM, PacketType.DEATH,
            PacketType.SERVERPLAYERSHOOT, PacketType.UPDATE, PacketType.NEWTICK, PacketType.MAPINFO, PacketType.STASIS,
            PacketType.TEXT, PacketType.NOTIFICATION, PacketType.EXALTATION_BONUS_CHANGED, PacketType.VAULT_UPDATE,
            PacketType.INVSWAP, PacketType.INVRESULT, PacketType.QUEST_FETCH_RESPONSE, PacketType.TRADEDONE,
            PacketType.TRADEREQUESTED, PacketType.CRUCIBLE_RESPONSE};

    private final TomatoData data;
    private final TomatoPacketCapture capture;
    private final PrintStream out;

    WorldOracle(PrintStream out) {
        this.out = out;
        data = new TomatoData();
        capture = new TomatoPacketCapture(data);
        // the panels the packet handlers update, as TomatoGUI builds them (panels work without a screen; the window not)
        new tomato.gui.security.ParsePanelGUI();
        new tomato.gui.dps.DpsGUI(data);
        new tomato.gui.chat.ChatGUI(data);
        new tomato.gui.myinfo.MyInfoGUI(data);
        new tomato.gui.stats.LootGUI(data);
        try {
            Field quests = tomato.gui.TomatoGUI.class.getDeclaredField("questPanel");
            quests.setAccessible(true);
            quests.set(null, new tomato.gui.quest.QuestGUI());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        tomato.gui.security.ParsePanelGUI.editFont(new java.awt.Font("Monospaced", java.awt.Font.PLAIN, 12));   // as TomatoGUI
        data.bootload();
        // the app's Register as at startup: TomatoPacketCapture first (Tomato.loadControllers), then the app's own
        // listeners as TomatoGUI installs them
        packets.packetcapture.register.Register r = packets.packetcapture.register.Register.INSTANCE;
        for (PacketType t : CAPTURE_ORDER) r.register(t, capture::packetCapture);
        lootgobbler.PacketDebug.install();
        lootgobbler.ItemUse.install();
        lootgobbler.CharacterStore.install();
        lootgobbler.PlayerProfile.get().trackStSets();
        waitFor(lootgobbler.PlayerProfile.get(), "counting");   // it counts on a thread of its own: done before the clock runs
        lootgobbler.PlayerProfile.get().trackExalts();
        lootgobbler.ItemCooldowns.install();
        waitForGameFileEnemies();
        tomato.backend.data.AbilityScalingManager.getInstance().initialize();   // the app does it at startup
    }

    /** Waits until an AtomicBoolean field of the object is false again. */
    private static void waitFor(Object o, String field) {
        try {
            for (int i = 0; i < 600 && ((java.util.concurrent.atomic.AtomicBoolean) get(o, field)).get(); i++) Thread.sleep(50);
        } catch (ReflectiveOperationException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The app reads the dungeons' enemies on a thread of its own; wait for it, so the result does not hang on timing. */
    private static void waitForGameFileEnemies() {
        try {
            Class<?> c = Class.forName("tomato.backend.data.GameFileEnemies");
            java.lang.reflect.Method get = c.getDeclaredMethod("get");
            get.setAccessible(true);
            for (int i = 0; i < 600 && get.invoke(null) == null; i++) Thread.sleep(100);
        } catch (ReflectiveOperationException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean fed(int type) {
        return PacketType.containsKey(type) && FED.contains(PacketType.byOrdinal(type));
    }

    /** One decoded packet, as the app's Register hands it on: an exception ends its handling there. */
    void packet(String direction, int type, Packet packet) {
        String name = PacketType.byOrdinal(type).name();
        String problem = "";
        if (packet instanceof MapInfoPacket) full("before " + name, state());   // how the map left ended
        try {
            packets.packetcapture.register.Register.INSTANCE.emitPacketLogs(packet);
            waitFor(lootgobbler.PlayerProfile.get(), "counting");   // the ST set count runs on its own thread
        } catch (Throwable e) {
            if (System.getenv("WORLD_ORACLE_TRACE") != null) e.printStackTrace();
            StackTraceElement where = e.getStackTrace().length > 0 ? e.getStackTrace()[0] : null;
            System.err.println("world: " + name + ": " + e + (where == null ? "" : " at " + where));
            problem = " exception";   // the world stays as far as it got, as in the app
        }
        String state = state();
        CRC32 crc = new CRC32();
        crc.update(state.getBytes(StandardCharsets.UTF_8));
        out.printf("%s %d %s %08x%s%n", direction, type, name, crc.getValue(), problem);
        if (packet instanceof MapInfoPacket) full("after " + name, state);
    }

    /** A packet unknown to the packet table, as PacketProcessor hands it to the app (PacketDebug.problem). */
    void raw(String direction, int type, int size, byte[] data) {
        String problem = "";
        try {
            lootgobbler.PacketDebug.problem(type, size, data, "unknown id");
        } catch (Throwable e) {
            problem = " exception";
        }
        CRC32 crc = new CRC32();
        crc.update(state().getBytes(StandardCharsets.UTF_8));
        out.printf("%s %d %s %08x%s%n", direction, type, "UNKNOWN", crc.getValue(), problem);
    }

    void end() {
        full("end", state());
    }

    private void full(String when, String state) {
        out.println("state " + when);
        for (String line : state.split("\n")) out.println("  " + line);
    }

    /** The whole world state as text, one line per part and per entity, sorted. */
    String state() {
        try {
            StringBuilder b = new StringBuilder();
            MapInfoPacket map = (MapInfoPacket) get(data, "map");
            b.append("map ").append(map == null ? "null" : quote(map.name)).append(" guessed=").append(get(data, "mapGuessed"))
                    .append(" me=").append(get(data, "worldPlayerId")).append(" char=").append(get(data, "charId"))
                    .append(" time=").append(data.time).append(" player=").append(data.player == null ? "null" : data.player.id)
                    .append('\n');
            b.append("players ").append(ids(((Map<?, ?>) get(data, "playerList")).keySet())).append('\n');
            b.append("updated ").append(ids(data.playerListUpdated.keySet())).append('\n');
            b.append("dropped ").append(ids(((Map<?, ?>) get(data, "dropList")).keySet())).append('\n');
            b.append("crystals ").append(ids((Collection<?>) get(data, "crystalTracker"))).append('\n');
            b.append("bags ").append(ids((Collection<?>) get(data, "lootBags"))).append('\n');
            List<String>[] bags = new List[2];
            ArrayList<?>[] containers = (ArrayList<?>[]) get(data, "lootTickContainer");
            for (int i = 0; i < 2; i++) {
                bags[i] = new ArrayList<>();
                for (Object e : containers[i]) bags[i].add(String.valueOf(((Entity) e).id));
            }
            List<String> killed = new ArrayList<>();
            for (Object e : (List<?>) get(data, "killedEntitys")) killed.add(String.valueOf(((Entity) e).id));
            Object w = get(data, "lootAttribution");
            Object variant = get(w, "forcedVariantSuffix");
            b.append("loot toggle=").append(get(data, "lootTickToggle")).append(" bags=[").append(String.join(",", bags[0]))
                    .append("]/[").append(String.join(",", bags[1])).append("] killed=[").append(String.join(",", killed))
                    .append("] window=").append(get(w, "nextTickAttributionMobId")).append('/').append(get(w, "nextTickAttributionSeed"))
                    .append('/').append(get(w, "remainingAttributionTicks")).append('/').append(variant == null ? "null" : variant).append('\n');
            tomato.backend.data.DungeonStatData ds = (tomato.backend.data.DungeonStatData) get(data, "dungeonStatData");
            b.append("dungeonstats info=").append(ds.info == null ? "null" : quote((String) get(ds.info, "name"))).append('\n');
            for (Map.Entry<String, tomato.backend.data.DungeonStatData.DungeonInfo> e : ds.data.entrySet()) {
                List<String> damaged = new ArrayList<>();
                for (Map.Entry<?, ?> d : ((Map<?, ?>) get(e.getValue(), "entityDamaged")).entrySet()) damaged.add(d.getKey() + ":" + d.getValue());
                List<String> loot = new ArrayList<>();
                for (Map.Entry<?, ?> l : ((Map<?, ?>) get(e.getValue(), "entityLoot")).entrySet()) {
                    List<String> items = new ArrayList<>();
                    for (Map.Entry<?, ?> i : ((Map<?, ?>) get(l.getValue(), "lootList")).entrySet()) items.add(i.getKey() + ":" + i.getValue());
                    loot.add(l.getKey() + ":{" + String.join(",", items) + "}");
                }
                b.append("dungeon ").append(quote(e.getKey())).append(" entered=").append(get(e.getValue(), "enteredDungeon"))
                        .append(" damaged=[").append(String.join(",", damaged)).append("] loot=[").append(String.join(",", loot)).append("]\n");
            }
            Object rng = get(data, "rng");
            Class<?> crucible = tomato.backend.data.CrucibleBonusManager.class;
            List<String> mult = new ArrayList<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) staticField(crucible, "crucibleDamageMultipliers")).entrySet()) {
                mult.add(quote((String) e.getKey()) + ":" + String.format("%016x", Double.doubleToRawLongBits((Double) e.getValue())));
            }
            java.util.Collections.sort(mult);
            String id128 = (String) staticField(crucible, "currentPlayerCrucibleId128");
            String id155 = (String) staticField(crucible, "currentPlayerCrucibleId155");
            b.append("rng ").append(rng == null ? "null" : String.valueOf(get(rng, "seed"))).append(" crucible ")
                    .append(id128 == null ? "null" : quote(id128)).append(' ').append(id155 == null ? "null" : quote(id155))
                    .append(" [").append(String.join(",", mult)).append("]\n");
            for (Entity e : new TreeMap<>(data.entityList).values()) {
                b.append(entity(e)).append('\n');
                String fights = fights(e);
                if (fights != null) b.append(fights).append('\n');
            }
            records(b);
            return b.toString();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String entity(Entity e) throws ReflectiveOperationException {
        StringBuilder b = new StringBuilder("entity ").append(e.id).append(" type=").append(e.objectType)
                .append(" pos=").append(pos(e.pos)).append(" user=").append(get(e, "isUser"))
                .append(" player=").append(get(e, "isPlayer")).append(" char=").append(get(e, "charId"))
                .append(" base=").append(e.baseStats == null ? "null" : java.util.Arrays.toString(e.baseStats).replace(" ", ""))
                .append(" stats=");
        StatData[] stats = (StatData[]) get(e.stat, "stats");
        boolean first = true;
        for (StatData s : stats) {
            if (s == null) continue;
            b.append(first ? "" : ",").append(s.statTypeNum).append(':');
            b.append(s.stringStatValue != null ? quote(s.stringStatValue) : String.valueOf(s.statValue));
            b.append('/').append(s.statValueTwo);
            first = false;
        }
        return b.toString();
    }

    /** The app's records: characters, profile (lg-app's dump). */
    @SuppressWarnings("unchecked")
    private static void records(StringBuilder b) throws ReflectiveOperationException {
        lootgobbler.CharacterStore cs = lootgobbler.CharacterStore.get();
        Map<Integer, lootgobbler.CharacterStore.Char> chars = new TreeMap<>((Map<Integer, lootgobbler.CharacterStore.Char>) get(cs, "chars"));
        List<String> never = new ArrayList<>();
        for (Object i : new TreeSet<>((java.util.Set<Integer>) get(cs, "neverSeen"))) never.add(String.valueOf(i));
        String player = (String) get(cs, "playerName");
        StringBuilder c = new StringBuilder("characters current=" + get(cs, "currentCharId") + " player="
                + (player == null ? "null" : quote(player)) + " never=[" + String.join(",", never) + "]\n");
        for (lootgobbler.CharacterStore.Char x : chars.values()) {
            List<String> samples = new ArrayList<>();
            for (lootgobbler.CharacterStore.Sample s : x.samples) samples.add(s.t + ":" + s.fame);
            String dungeons = "null";
            if (x.dungeons != null) {
                List<String> d = new ArrayList<>();
                for (Map.Entry<String, Integer> e : new TreeMap<>(x.dungeons).entrySet()) d.add(quote(e.getKey()) + ":" + e.getValue());
                dungeons = "{" + String.join(",", d) + "}";
            }
            c.append("char ").append(x.charId).append(" cls=").append(x.classType).append(" name=").append(quote(x.className))
                    .append(" skin=").append(x.skin).append(" tex=").append(x.tex1).append('/').append(x.tex2)
                    .append(" pet=").append(x.petSkin).append('/').append(x.petName == null ? "null" : quote(x.petName))
                    .append(" lvl=").append(x.level).append(" hp=").append(x.maxHp).append(" fame=").append(x.fame)
                    .append(" maxed=").append(x.statsMaxed).append(" flags=").append(x.seasonal ? 1 : 0).append(x.crucible ? 1 : 0)
                    .append(x.ritual ? 1 : 0).append(x.favorite ? 1 : 0)
                    .append(" cl=").append(dbl(x.crucibleLoot)).append(" rl=").append(dbl(x.ritualLoot))
                    .append(" ld=").append(x.lootDropUntil).append(" lt=").append(x.lootTierUntil).append(" fav=").append(x.favoriteAt)
                    .append(" pack=").append(x.backpack ? 1 : 0).append(x.extender ? 1 : 0).append(" alive=").append(x.alive)
                    .append(" died=").append(x.diedBy == null ? "null" : quote(x.diedBy))
                    .append(" acct=").append(x.account == null ? "null" : quote(x.account))
                    .append(" aliveAt=").append(x.aliveAt).append(" played=").append(x.lastPlayed)
                    .append(" eq=").append(arr(x.equipment)).append(" ench=").append(arr(x.enchants))
                    .append(" eids=").append(arr2(x.enchantIds)).append(" slots=").append(arr(x.slots))
                    .append(" quick=").append(arr(x.quickslots)).append(" seids=").append(arr2(x.slotEnchantIds))
                    .append(" dungeons=").append(dungeons).append(" samples=[").append(String.join(",", samples)).append("]\n");
        }
        b.append(c);
        lootgobbler.PlayerProfile.Data p = lootgobbler.PlayerProfile.get().current();
        b.append("profile ign=").append(quote(p.ign)).append(" guild=").append(quote(p.guild)).append(" rank=")
                .append(p.guildRank).append(" at=").append(p.updatedAt)
                .append(" sts=").append(p.stSetsDone == null ? "null" : p.stSetsDone.toString().replace(" ", ""))
                .append(" exalts=").append(p.exaltChanges == null ? "null" : String.valueOf(p.exaltChanges.size())).append('\n');
        collection(b);
        holdings(b);
        upe(b);
    }

    @SuppressWarnings("unchecked")
    private static void upe(StringBuilder b) throws ReflectiveOperationException {
        lootgobbler.Upe u = lootgobbler.Upe.get();
        Object state = get(u, "state");
        b.append("upe forged=").append(get(u, "forgedAt")).append('/').append(get(u, "forgedOrigin"))
                .append(" traded=").append(get(u, "tradedAt")).append(" reward=").append(get(u, "rewardAt"))
                .append(" quest=").append(get(u, "questAt")).append('/').append(get(u, "questOrigin"))
                .append(" vaultmap=").append(get(u, "inVaultMap")).append(" where=").append(quote((String) get(u, "where")))
                .append(" pending=").append(((List<?>) get(u, "pending")).size()).append(" left=").append(((List<?>) get(state, "left")).size())
                .append(" missions=").append(((Map<?, ?>) get(state, "missions")).size()).append('\n');
        for (lootgobbler.Upe.Run r : new TreeMap<>((Map<Integer, lootgobbler.Upe.Run>) get(state, "runs")).values()) {
            List<String> notes = new ArrayList<>();
            for (String n : r.notes) notes.add(quote(n));
            List<String> ench = new ArrayList<>();
            for (int[] e : r.enchants) ench.add(arr(e));
            List<String> allowed = new ArrayList<>();
            for (int[] a : r.allowed) allowed.add(arr(a));
            List<String> gathered = new ArrayList<>();
            for (lootgobbler.Upe.Gathered g : r.gathered) gathered.add(g.item + "/" + arr(g.enchants) + "/" + g.at + "/" + g.how + "/" + quote(g.where));
            List<String> trades = new ArrayList<>();
            for (lootgobbler.Upe.Trade t : r.trades) trades.add(t.at + "/" + quote(t.partner) + "/" + t.gave.size() + "/" + t.got.size() + "/" + t.status);
            b.append("run ").append(r.charId).append(" mode=").append(r.mode).append(" fresh=").append(r.fresh)
                    .append(" marked=").append(r.marked).append('@').append(r.markedAt)
                    .append(" broken=").append(r.broken == null ? "null" : quote(r.broken)).append('@').append(r.brokenAt)
                    .append(" notes=").append(String.join("|", notes)).append(" seen=").append(r.lastSeen)
                    .append(" items=").append(arr(r.items)).append(" ench=[").append(String.join(",", ench)).append(']')
                    .append(" origin=[").append(String.join(",", r.origin)).append(']')
                    .append(" since=").append(java.util.Arrays.toString(r.since)).append(" lvl=").append(r.level)
                    .append(" base=").append(arr(r.baseStats)).append(" allowed=").append(String.join(";", allowed))
                    .append(" gathered=[").append(String.join(",", gathered)).append("] locked=").append(r.guildLocked)
                    .append(" trades=[").append(String.join(",", trades)).append("] t0=").append(r.t0Start)
                    .append(" pet=").append(r.pet).append(" nopet=").append(r.noPet).append('\n');
        }
    }

    private static String ints(java.util.List<Integer> l) {
        return l == null ? "null" : l.toString().replace(" ", "");
    }

    private static void collection(StringBuilder b) {
        Map<Integer, lootgobbler.CollectionStore.Entry> all = new TreeMap<>(lootgobbler.CollectionStore.get().snapshot());
        b.append("collection ").append(all.size()).append('\n');
        for (Map.Entry<Integer, lootgobbler.CollectionStore.Entry> e : all.entrySet()) {
            lootgobbler.CollectionStore.Entry x = e.getValue();
            String history = "null";
            if (x.history != null) {
                List<String> h = new ArrayList<>();
                for (lootgobbler.CollectionStore.Find f : x.history) {
                    String seen = f.seen == null ? "null" : f.seen.charId + "/" + quote(f.seen.place) + "/" + f.seen.classType + "/"
                            + f.seen.skin + "/" + f.seen.tex1 + "/" + f.seen.tex2 + "/" + f.seen.seasonal;
                    h.add("{" + quote(f.time) + " " + f.at + " " + quote(f.source) + " " + f.enchants + " " + quote(f.where) + " "
                            + quote(f.bag) + " " + ints(f.enchantIds) + " " + f.seasonal + " " + seen + "}");
                }
                history = "[" + String.join(",", h) + "]";
            }
            b.append("item ").append(e.getKey()).append(" first=").append(x.first == null ? "null" : quote(x.first))
                    .append(" last=").append(x.last == null ? "null" : quote(x.last)).append(" count=").append(x.count)
                    .append(" history=").append(history).append('\n');
        }
    }

    @SuppressWarnings("unchecked")
    private static void holdings(StringBuilder b) throws ReflectiveOperationException {
        lootgobbler.Holdings h = lootgobbler.Holdings.get();
        Object state = get(h, "state"), visit = get(h, "visit");
        String v = visit == null ? "null" : ((Map<?, ?>) get(visit, "sections")).size() + "/" + get(visit, "complete") + "/" + get(visit, "broken");
        b.append("holdings vault=").append(get(h, "mapIsVault")).append(" inVault=").append(get(h, "inVault"))
                .append(" visit=").append(v).append(" chests=").append(((Map<?, ?>) get(h, "chestSections")).size())
                .append(" pages=").append(((Map<?, ?>) get(h, "pages")).size()).append(" asked=").append(((List<?>) get(h, "asked")).size())
                .append(" gone=").append(((Map<?, ?>) get(state, "gone")).size()).append('\n');
        Map<String, Object> places = new TreeMap<>((Map<String, Object>) get(state, "places"));
        for (Map.Entry<String, Object> e : places.entrySet()) {
            Object p = e.getValue();
            b.append("place ").append(e.getKey()).append(" seen=").append(get(p, "seenAt")).append(" source=").append(get(p, "source"));
            for (Map.Entry<String, List<?>> sec : ((Map<String, List<?>>) get(p, "sections")).entrySet()) {
                List<String> slots = new ArrayList<>();
                for (Object slot : sec.getValue()) {
                    java.util.List<Integer> ench = (java.util.List<Integer>) get(slot, "enchantIds");
                    slots.add(get(slot, "item") + (ench == null ? "" : ints(ench)));
                }
                b.append(' ').append(sec.getKey()).append("=[").append(String.join(",", slots)).append(']');
            }
            b.append('\n');
        }
        java.nio.file.Path journal = java.nio.file.Path.of("holdings-journal.txt");
        if (java.nio.file.Files.exists(journal)) {
            try {
                for (String l : java.nio.file.Files.readAllLines(journal)) b.append("journal ").append(l).append('\n');
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static String dbl(Double d) {
        return d == null ? "null" : String.format("%016x", Double.doubleToRawLongBits(d));
    }

    private static String arr(int[] a) {
        return a == null ? "null" : java.util.Arrays.toString(a).replace(" ", "");
    }

    private static String arr2(int[][] a) {
        return a == null ? "null" : java.util.Arrays.deepToString(a).replace(" ", "");
    }

    /** Its name and the damage it took, as lg-world's dump writes them; null when there is nothing to tell. */
    private static String fights(Entity e) throws ReflectiveOperationException {
        String name = (String) get(e, "name");
        boolean fought = (long) get(e, "firstDamageTaken") != -1;
        java.util.List<?> damage = (java.util.List<?>) get(e, "damageList");
        if (name == null && !fought && !e.dammahCountered && damage.isEmpty() && e.playerDropped.isEmpty()) return null;
        List<String> records = new ArrayList<>();
        for (Object d : damage) records.add(record((tomato.backend.data.Damage) d));
        List<String> sums = new ArrayList<>();
        for (Map.Entry<?, ?> s : new TreeMap<>((Map<Integer, ?>) get(e, "damagePlayer")).entrySet()) {
            sums.add(s.getKey() + ":" + record((tomato.backend.data.Damage) s.getValue()));
        }
        List<String> dropped = new ArrayList<>();
        for (Map.Entry<Integer, tomato.backend.data.PlayerRemoved> d : new TreeMap<>(e.playerDropped).entrySet()) {
            dropped.add(d.getKey() + ":" + d.getValue().hp + "/" + d.getValue().max + "/"
                    + (d.getValue().name == null ? "null" : quote(d.getValue().name)));
        }
        return "  name=" + (name == null ? "null" : quote(name)) + " fought=" + fought + " dammah=" + e.dammahCountered
                + " damage=[" + String.join(",", records) + "] byplayer=[" + String.join(",", sums) + "] dropped=["
                + String.join(",", dropped) + "]";
    }

    private static String record(tomato.backend.data.Damage d) {
        StringBuilder b = new StringBuilder("{o=").append(d.owner == null ? "null" : String.valueOf(d.owner.id))
                .append(",src=").append(d.sourceType).append(",t=").append(d.ownerObjectType)
                .append(",n=").append(d.ownerObjectName == null ? "null" : quote(d.ownerObjectName)).append(",inv=");
        if (d.ownerInvntory == null) b.append("null");
        else b.append(java.util.Arrays.toString(d.ownerInvntory).replace(" ", ""));
        b.append(",ench=");
        if (d.ownerEnchants == null) b.append("null");
        else {
            List<String> q = new ArrayList<>();
            for (String c : d.ownerEnchants) q.add(quote(c));
            b.append('[').append(String.join(",", q)).append(']');
        }
        b.append(",ss=").append(d.ownerScalingStatType == null ? "null" : String.valueOf(d.ownerScalingStatType.get()))
                .append(",sv=").append(d.ownerScalingStatValue).append(",d=").append(d.damage)
                .append(",cd=").append(d.counterDmg).append(",ch=").append(d.counterHits)
                .append(",f=").append(d.oryx3GuardDmg ? 1 : 0).append(d.chancellorDammahDmg ? 1 : 0)
                .append(d.walledGardenReflectors ? 1 : 0).append('}');
        return b.toString();
    }

    private static Object staticField(Class<?> c, String name) throws ReflectiveOperationException {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    private static String pos(WorldPosData p) {
        return p == null ? "null" : String.format("%08x,%08x", Float.floatToRawIntBits(p.x), Float.floatToRawIntBits(p.y));
    }

    private static String ids(Collection<?> c) {
        List<String> l = new ArrayList<>();
        for (Object o : new TreeSet<>(c)) l.add(String.valueOf(o));
        return "[" + String.join(",", l) + "]";
    }

    private static String quote(String s) {
        StringBuilder o = new StringBuilder("\"");
        s.codePoints().forEach(c -> {
            if (c == '\\' || c == '"') o.append('\\').appendCodePoint(c);
            else if (c >= ' ' && c <= '~') o.appendCodePoint(c);
            else o.append("\\u{").append(Integer.toHexString(c)).append('}');
        });
        return o.append('"').toString();
    }

    private static Object get(Object o, String name) throws ReflectiveOperationException {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException ignored) {
                // look in the superclass
            }
        }
        throw new NoSuchFieldException(name);
    }
}
