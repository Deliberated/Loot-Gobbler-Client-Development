package lootgobbler;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Untiered Player Experience (UPE) runs: characters played with only what they find themselves.
 * <ul>
 * <li>The loadout (weapon, ability, armor, ring) holds only UT and ST items, besides the gear the character was
 * created with.</li>
 * <li>Whatever is equipped came out of a loot bag, or was forged or crafted from things that did, during the run (ST
 * set tokens and pieces included).</li>
 * <li>Nothing from the vault (vault chest, gift chest, potion storage, spoils, materials) goes into the loadout, and
 * no stat potion from there is drunk. Anything may be carried in the inventory.</li>
 * <li>No trading at all.</li>
 * <li>Nothing bought (shop) or given (daily login rewards, reward chests, battle pass milestones). A Tinkerer
 * quest's reward counts only if everything handed in for it came from this run; a battle pass mission's reward only
 * if its progress was at 0 when this character began it and all of it was made by this character during the run.
 * Such rewards may also be taken from the gift chest, once each.</li>
 * </ul>
 * Every item a character holds gets an origin when it arrives: the gear it was created with, a loot bag, crafting from
 * clean things, the vault, a trade, or unknown (it arrived while the sniffer did not see it). Only a proven
 * violation breaks a run: an item of unknown origin keeps the benefit of the doubt, unless the vault records show the
 * very same item (with the same enchantments) leaving the vault while the character was not watched. Without
 * enchantments that cannot be told apart from a copy of its own, so it only gets a note.
 * <p>
 * A character the sniffer sees from its creation (first seen at level 1) is a potential UPE until it breaks a rule;
 * marking it makes it one. Any other living character can be marked too: its run is then followed from that moment
 * (what it wears then counts as its starting gear). Saved in upe.json and synced with the server.
 */
public final class Upe implements Syncable {
    /** Where an item a character holds came from. */
    public static final String STARTER = "S", DROP = "D", CRAFTED = "C", VAULT = "V", TRADE = "T", FORGED_TAINTED = "F",
            REWARD = "B", UNKNOWN = "U";
    /**
     * gUPE trades: GUILD came from a guild mate's standing gUPE run and was gathered in it (their trade report says
     * so); CHECKING came in a trade whose partner's report is still awaited.
     */
    public static final String GUILD = "G", CHECKING = "P";

    /*
     * The game modes. Two categories: PPE (any tier, own drops) and UPE (untiered gear) with its variants. Every mode
     * can be played without a pet (a run's noPet setting, once its own mode NPE), and has a guild version too (a "g"
     * in front: gPPE, gUPE, gSTPE...),
     * which may trade with guild mates on a guild run what their runs gathered. A run's mode is that code.
     */
    public static final String PPE = "PPE", UPE = "UPE";
    /** No longer a mode of its own (now a PPE with noPet): runs saved in it are moved over. */
    static final String OLD_NPE = "NPE";
    public static final String PUPE = "PUPE", STPE = "STPE", SHINY_UPE = "ShinyUPE", DIVINE_PE = "DivinePE";
    public static final String GUPE = "gUPE";
    public static final List<String> CATEGORIES = List.of(PPE, UPE);
    public static final List<String> UPE_VARIANTS = List.of(UPE, PUPE, STPE, SHINY_UPE, DIVINE_PE);
    /** Every mode a run can be in, in the order they are listed. */
    public static final List<String> MODES = List.of(PPE, "g" + PPE, UPE, "g" + UPE, PUPE, "g" + PUPE, STPE, "g" + STPE,
            SHINY_UPE, "g" + SHINY_UPE, DIVINE_PE, "g" + DIVINE_PE);

    /** The guild version of a mode (trades with guild mates on a guild run allowed). */
    public static boolean guild(String mode) {
        return mode != null && mode.startsWith("g");
    }

    /** The mode without its guild part: PPE or one of the UPE variants. */
    public static String base(String mode) {
        return mode == null ? UPE : guild(mode) ? mode.substring(1) : mode;
    }

    /** PPE or UPE. */
    public static String category(String mode) {
        String b = base(mode);
        return UPE_VARIANTS.contains(b) ? UPE : b;
    }

    /** Whether this mode (its base) has a guild version: all do. */
    public static boolean guildable(String mode) {
        return true;
    }

    /** The mode of this base, with or without its guild version. */
    public static String withGuild(String mode, boolean guild) {
        String b = base(mode);
        return guild && guildable(b) ? "g" + b : b;
    }

    /** How a mode is written for people: gUPE, Shiny UPE, gDivine UPE... */
    public static String label(String mode) {
        String b = base(mode);
        String l = SHINY_UPE.equals(b) ? "Shiny UPE" : DIVINE_PE.equals(b) ? "Divine PE" : b;
        return (guild(mode) ? "g" : "") + l;
    }

    /** A category or mode as the Game Modes tab explains it. */
    public record ModeInfo(String code, String name, String summary, List<String> rules) {
    }

    /** What every mode asks before a run may start. */
    public static final String PREREQUISITE = "A run can start only on a fresh character the sniffer saw from its creation, "
            + "created with upgrades disabled: a tier 0 weapon, ability and armor. That starting gear never counts against the rules.";

    /** The categories (for the list on the left). */
    public static final List<ModeInfo> CATEGORY_INFO = List.of(
            new ModeInfo(PPE, "Pet Player Experience", "Play with nothing but what the character finds itself: any tier, from its own drops.", List.of()),
            new ModeInfo(UPE, "Untiered Player Experience", "Like a PPE, but the loadout holds untiered gear only: UT and ST, or stricter (pure: UT only, ST, shiny), or divine gear of any tier.", List.of()));

    /** The rules every mode shares: where things may come from. */
    private static final List<String> OWN_RULES = List.of(
            "Whatever is equipped came out of a loot bag during the run, or was forged from such things (ST set tokens and pieces included).",
            "Nothing from the vault: vault chest, gift chest, potion storage, spoils and materials may be carried, but never equipped.",
            "Stat potions only from drops: none from the vault, the potion storage or other players.",
            "Loot boosts (Loot Drop and Loot Tier Potions, drop rate and enchanted drop boosts) only if gathered in the run. Oryx runes, experience boosts and other items are free to use.",
            "No trading (the guild version allows trades with guild mates). Nothing bought in the shop, no daily login rewards, reward chests or battle pass milestones in the loadout.",
            "Tinkerer quests count if everything handed in came from this run; battle pass missions if they began at 0 with this character and it solved them alone. Their rewards may be taken from the gift chest once.",
            "Anything the sniffer could not see keeps the benefit of the doubt, unless the vault records prove otherwise.");

    /** What the guild version adds. */
    public static final List<String> GUILD_RULES = List.of(
            "Trading is allowed with guild mates of your Loot Gobbler guild who play a guild mode themselves (gPPE, gUPE, gSTPE...).",
            "Only items gathered in the run may change hands: dropped, crafted from the run's own things, or traded in from another guild run.",
            "Both sides need Loot Gobbler running: each reports the trade to the server and checks the other's report, where the partner's run got the items. Until it arrives, items from the trade are marked as checking; without a fitting report within 5 minutes the trade is illegal and the run is lost.",
            "Trading with anyone else (a run without the guild version, other Loot Gobbler friends, players without Loot Gobbler) loses the run.",
            "A run may switch its guild version off again as long as it never equipped or used an item traded from a guild mate.");

    /** Each mode without its guild part: what the loadout may hold, then the rules all share. */
    public static final List<ModeInfo> MODE_INFO = List.of(
            new ModeInfo(PPE, "Pet Player Experience", "Any tier is fine, as long as the character found it itself.",
                    concat(List.of("The loadout may hold items of any tier, if they came from the run itself."), OWN_RULES)),
            new ModeInfo(UPE, "Untiered Player Experience", "Untiered gear from the character's own drops.",
                    concat(List.of("The loadout (weapon, ability, armor, ring) holds only UT and ST items, besides the starting gear."), OWN_RULES)),
            new ModeInfo(PUPE, "Pure UPE", "Only untiered (UT) items in the loadout: no set items.",
                    concat(List.of("The loadout holds only UT items, besides the starting gear: no ST, no tiered items."), OWN_RULES)),
            new ModeInfo(STPE, "ST Player Experience", "Only set gear (ST) in the loadout.",
                    concat(List.of("The loadout holds only ST items, besides the starting gear."), OWN_RULES)),
            new ModeInfo(SHINY_UPE, "Shiny UPE", "Only shiny UT items in the loadout.",
                    concat(List.of("The loadout holds only shiny UT items, besides the starting gear (ST items have no shiny versions)."), OWN_RULES)),
            new ModeInfo(DIVINE_PE, "Divine Player Experience", "Only divine items (four enchantment slots) in the loadout, of any tier.",
                    concat(List.of("The loadout holds only divine items (four enchantment slots), of any tier, besides the starting gear."), OWN_RULES)));

    /** What playing without a pet adds (any mode can, an NPE for a PPE). */
    public static final List<String> NO_PET_RULES = List.of(
            "No pet may follow the character, from its creation to the end of the run. Loot Gobbler sees the pets in the character list at each login; a pet there loses the run.");

    /** The info of a mode (its base; the guild rules are GUILD_RULES). */
    public static ModeInfo info(String mode) {
        String b = base(mode);
        return MODE_INFO.stream().filter(m -> m.code().equals(b)).findFirst().orElse(MODE_INFO.get(1));
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> l = new ArrayList<>(a);
        l.addAll(b);
        return List.copyOf(l);
    }

    /** Origins that may not go into the loadout (nor be drunk, nor go into the forge for a clean result). */
    static boolean tainted(String o) {
        return VAULT.equals(o) || TRADE.equals(o) || FORGED_TAINTED.equals(o) || REWARD.equals(o);
    }

    /** Like tainted, for a run in this mode: a guild mate's trade is clean in a guild mode only; one still checked is not. */
    static boolean tainted(String o, String mode) {
        if (GUILD.equals(o)) return !guild(mode);
        return CHECKING.equals(o) || tainted(o);
    }

    /** Gathered in the run itself, so it may be handed to a gUPE guild mate. */
    static boolean ownForTrade(String o) {
        return DROP.equals(o) || CRAFTED.equals(o) || GUILD.equals(o) || STARTER.equals(o);
    }

    public static final class Run {
        public int charId;
        public String mode = UPE;        // which challenge: see MODES
        public boolean fresh;            // seen by the sniffer from its creation
        public boolean marked;           // played as a UPE (the player said so)
        public long markedAt;            // when marked or unmarked last
        public String broken;            // why the run is over, null while it holds
        public long brokenAt;
        public List<String> notes = new ArrayList<>();   // doubts that are no proof (shown, but the run holds)
        public long lastSeen;            // the sniffer last saw the character
        public int[] items = new int[0];  // its slots then (equipment, inventory, backpack)
        public int[][] enchants = new int[0][];
        public String[] origin = new String[0];
        public long[] since = new long[0];   // for UNKNOWN: since when it may have been there unseen
        public int level;
        public int[] baseStats;
        /**
         * Rewards of quests solved cleanly during the run that may be taken out of the gift chest (the game puts them
         * there when the inventory is full): each entry allows one item, any of those listed (a reward of choice
         * lists them all). Taking one out uses the entry up.
         */
        public List<int[]> allowed = new ArrayList<>();
        /** What the run gathered itself: every item that came to it as a drop or was made cleanly, in order. */
        public List<Gathered> gathered = new ArrayList<>();
        /** Equipped or used an item traded from a guild mate: it can no longer switch back from gUPE to UPE. */
        public boolean guildLocked;
        /** Its trades (gUPE), reported to the server so the partners can check them; the last weeks only. */
        public List<Trade> trades = new ArrayList<>();
        /**
         * Created with upgrades disabled: its starting weapon, ability and armor were tier 0. Null: not known (a run
         * from before this was checked, whose starting gear is no longer worn), which gets the benefit of the doubt.
         */
        public Boolean t0Start;
        /** The character list showed a pet following it (the last look). */
        public boolean pet;
        /** Played without a pet: the extra setting any mode can have (an NPE once was its own mode). */
        public boolean noPet;

        /** Can play a game mode at all: seen from its creation, created with upgrades disabled. */
        public boolean eligible() {
            return fresh && !Boolean.FALSE.equals(t0Start);
        }

        /** A run still standing: not broken, and either marked or eligible from the start. */
        public boolean standing() {
            return broken == null && (marked || eligible());
        }

        /** Eligible, nothing against it, but not marked: a run it could still start. */
        public boolean potential() {
            return eligible() && !marked && broken == null;
        }

        /** Whether it could start a run in this mode now (without a pet: only while none follows it). */
        public boolean canStart(String mode, boolean noPet) {
            return potential() && MODES.contains(mode) && !(noPet && pet);
        }
    }

    /** One side of a trade: an item, its enchantments and whether the run that gave it had gathered it itself. */
    public static final class TradeItem {
        public int item;
        public int[] enchants;
        public boolean clean;
    }

    /**
     * A trade of a gUPE run as both its partners report it: when, with whom, the mode and whether the run stood then,
     * what went each way, and the verdict of the check against the partner's report (CHECKING, "ok" or "illegal").
     */
    public static final class Trade {
        public long at;
        public int charId;
        public String me = "", partner = "";
        public String mode = GUPE;
        public boolean standing;
        public List<TradeItem> gave = new ArrayList<>(), got = new ArrayList<>();
        public String status = CHECKING;
        public String why;
    }

    /** One item a run gathered: when, how (a drop, crafted / a clean reward) and where. */
    public static final class Gathered {
        public int item;
        public int[] enchants;
        public long at;
        public String how;     // DROP or CRAFTED
        public String where = "";
    }

    /** An item that left a vault while nobody watched: it was there at one look and gone at the next. */
    static final class Left {
        int item;
        int[] enchants;
        long from, to;
        String vault;
    }

    /** Who solved a battle pass mission: the character its progress began on, and whether it stayed clean. */
    static final class MissionRun {
        int charId;
        boolean fromZero;   // its progress was at 0 when this character began it
        boolean clean;      // every step was made by this character during a standing run
        long startedAt;
    }

    private static final class State {
        int version = 1;
        Map<Integer, Run> runs = new HashMap<>();
        List<Left> left = new ArrayList<>();
        Map<String, MissionRun> missions = new HashMap<>();   // by "season:mission"
    }

    private static final Path FILE = Path.of("upe.json");
    private static final Gson GSON = new GsonBuilder().create();
    private static final long KEEP_LEFT_MS = 180L * 24 * 60 * 60 * 1000;
    private static final long CONTEXT_MS = 10_000;   // how long a forge, a trade or a move explains a new item
    private static final int LOADOUT = 4;

    private State state = new State();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    /** Origins announced by the game's answers to moves and forging, per slot of your character, until seen. */
    private record Pending(int charId, int slot, int item, String origin, long at, boolean gain) {
    }

    private final List<Pending> pending = new ArrayList<>();
    private long forgedAt, tradedAt, rewardAt, questAt;
    private String rewardWhat = "", questOrigin = CRAFTED;
    /** Per quest: what was handed in for it since its last reward, as {charId, 1 if clean}. */
    private final Map<Integer, List<int[]>> questParts = new HashMap<>();
    private String forgedOrigin = CRAFTED;
    private List<String> forgeInputs = List.of();   // the origins of what the last forge request put in
    private boolean inVaultMap;
    private String where = "";

    /** The map you are on (for what a UPE gathers). */
    public synchronized void where(String map) {
        where = map == null ? "" : map;
    }

    /** Notes an item as gathered by the run if its origin is its own: a drop, or made cleanly. */
    private void gathered(Run run, int item, int[] ench, String origin, long now) {
        if (item < 1 || !(DROP.equals(origin) || CRAFTED.equals(origin) || GUILD.equals(origin))
                || !(run.eligible() || run.marked)) return;
        Gathered g = new Gathered();
        g.item = item;
        g.enchants = ench;
        g.at = now;
        g.how = origin;
        g.where = where;
        run.gathered.add(g);
    }

    /** What the rules need to know about items; the game data, or something simpler in tests. */
    interface Items {
        /** UT or ST: allowed in the loadout. */
        boolean untiered(int id);

        String name(int id);

        /** The stat potion's stat (0 life ... 7 wisdom), or -1 if it is none. */
        int potionStat(int id);

        /** A loot boost consumable (Loot Drop / Tier Potion, drop rate or enchanted drop accelerators...). */
        default boolean lootBoost(int id) {
            return false;
        }

        /** A set item (ST). */
        default boolean st(int id) {
            return false;
        }

        /** A shiny variant. */
        default boolean shiny(int id) {
            return false;
        }

        /** Its tier as a number (weapons, abilities, armor, rings); -1 for UT, ST and what has none. */
        default int tier(int id) {
            return -1;
        }
    }

    private static final Items GAME_ITEMS = new Items() {
        public boolean untiered(int id) {
            ItemDb.Item it = ItemDb.get().item(id);
            return it != null && (it.isUT() || it.isST());
        }

        public String name(int id) {
            ItemDb.Item it = ItemDb.get().item(id);
            return it == null || it.name.isBlank() ? "item " + id : it.name;
        }

        public int potionStat(int id) {
            tomato.realmshark.enums.StatPotion p = tomato.realmshark.enums.StatPotion.getPotion(id);
            return p == null ? -1 : p.ordinal();
        }

        public boolean lootBoost(int id) {
            ItemDb.Item it = ItemDb.get().item(id);
            return it != null && it.lootBoost;
        }

        public boolean st(int id) {
            ItemDb.Item it = ItemDb.get().item(id);
            return it != null && it.isST();
        }

        public boolean shiny(int id) {
            ItemDb.Item it = ItemDb.get().item(id);
            return it != null && it.isShiny();
        }

        public int tier(int id) {
            ItemDb.Item it = ItemDb.get().item(id);
            if (it == null || it.isUT() || it.isST()) return -1;
            try {
                return Integer.parseInt(it.tier.trim());
            } catch (NumberFormatException e) {
                return -1;
            }
        }
    };

    // made after GAME_ITEMS: a static made earlier would see it still null
    private static final Upe INSTANCE = new Upe();

    Items items = GAME_ITEMS;
    private final boolean persist;
    /** Uploads the runs soon (a trade report the partner waits for), and starts checking a trade; tests replace them. */
    Runnable reportSoon = () -> ServerSync.get().uploadSoon();
    java.util.function.Consumer<Trade> checker = t -> GuildUpe.get().check(t);

    public static Upe get() {
        return INSTANCE;
    }

    private Upe() {
        this(true);
    }

    Upe(boolean persist) {
        this.persist = persist;
        if (!persist || !Files.exists(FILE)) return;
        try (Reader r = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            State s = GSON.fromJson(r, State.class);
            if (s != null) state = s;
            if (state.runs == null) state.runs = new HashMap<>();
            if (state.left == null) state.left = new ArrayList<>();
            if (state.missions == null) state.missions = new HashMap<>();
            for (Run run : state.runs.values()) repair(run);
        } catch (Exception e) {
            System.err.println("Could not read " + FILE + ": " + e.getMessage());
        }
    }

    private static void repair(Run run) {
        if (run.notes == null) run.notes = new ArrayList<>();
        if (run.mode == null || run.mode.isBlank()) run.mode = UPE;   // runs from before there were modes
        if (OLD_NPE.equals(run.mode)) {   // NPE is a PPE without a pet now
            run.mode = PPE;
            run.noPet = true;
        }
        if (run.allowed == null) run.allowed = new ArrayList<>();
        if (run.gathered == null) run.gathered = new ArrayList<>();
        if (run.trades == null) run.trades = new ArrayList<>();
        if (run.items == null) run.items = new int[0];
        int n = run.items.length;
        if (run.origin == null || run.origin.length != n) run.origin = filled(n, UNKNOWN);
        if (run.since == null || run.since.length != n) run.since = new long[n];
        if (run.enchants == null || run.enchants.length != n) run.enchants = new int[n][];
    }

    // ---- what the sniffer sees ----------------------------------------------------------------------------------

    /** Whether you are in your vault: an item that turns up there without a move seen came out of it. */
    public synchronized void inVault(boolean vault) {
        inVaultMap = vault;
    }

    /**
     * Your character as the sniffer sees it now (every few seconds): its slots (equipment, inventory, backpack)
     * with their enchantments. firstSight: the character was never seen before (a level 1 character then was just
     * created). Gives each new item its origin and checks the loadout.
     */
    public void seen(int charId, int level, int[] slots, int[][] enchants, int[] baseStats, boolean firstSight) {
        if (charId <= 0 || slots == null) return;
        boolean changed;
        synchronized (this) {
            long now = System.currentTimeMillis();
            Run run = state.runs.get(charId);
            int n = slots.length;
            int[][] ench = enchants != null && enchants.length == n ? enchants : new int[n][];
            if (run == null || run.lastSeen == 0) {   // first look (a run made by an older version had none)
                boolean marked = run != null && run.marked;
                run = new Run();
                run.charId = charId;
                run.fresh = firstSight && level <= 1;
                run.marked = marked && run.fresh;   // an older version let any character be marked
                run.items = slots.clone();
                run.enchants = ench.clone();
                run.origin = filled(n, run.fresh ? STARTER : UNKNOWN);
                run.since = new long[n];
                run.lastSeen = now;
                run.level = level;
                run.baseStats = baseStats;
                if (run.fresh) run.t0Start = startsAtT0(run);
                state.runs.put(charId, run);
                if (run.fresh) AppLog.log("upe", "New character " + charId + " seen from its creation: "
                        + (!Boolean.FALSE.equals(run.t0Start) ? "it can start a game mode" : "created with upgrades enabled, so no game mode"));
                save();
                changed = true;
            } else {
                changed = update(run, level, slots, ench, baseStats, now);
            }
        }
        if (changed) fire();
    }

    /**
     * Whether the starting gear still worn (weapon, ability, armor, of STARTER origin) is tier 0: upgrades were
     * disabled. Null if none of it is worn any more (then it cannot be told).
     */
    private Boolean startsAtT0(Run run) {
        boolean any = false;
        for (int i = 0; i < Math.min(3, run.items.length); i++) {
            if (run.items[i] < 1 || !STARTER.equals(run.origin[i])) continue;
            int tier = items.tier(run.items[i]);
            if (tier > 0) return false;
            if (tier == 0) any = true;
        }
        return any ? Boolean.TRUE : null;
    }

    private boolean update(Run run, int level, int[] slots, int[][] ench, int[] baseStats, long now) {
        repair(run);
        if (run.fresh && run.t0Start == null) {   // a run from before this was checked: its starting gear, if still worn
            Boolean t0 = startsAtT0(run);
            if (t0 != null) {
                run.t0Start = t0;
                save();
            }
        }
        int n = slots.length;
        int[] old = run.items;
        String[] oldOrigin = run.origin;
        long[] oldSince = run.since;
        boolean gap = now - run.lastSeen > 5 * 60_000;   // not watched for a while: what changed came unseen
        String[] origin = new String[n];
        long[] since = new long[n];
        boolean[] taken = new boolean[old.length];   // old slots whose item went elsewhere
        boolean changed = false;
        // unchanged slots keep their origin
        for (int i = 0; i < n; i++) {
            if (i < old.length && old[i] == slots[i]) {
                origin[i] = oldOrigin[i];
                since[i] = oldSince[i];
                taken[i] = true;
            }
        }
        for (int i = 0; i < n; i++) {
            if (origin[i] != null) continue;
            int item = slots[i];
            changed = true;
            if (item < 1) {
                origin[i] = UNKNOWN;
                continue;
            }
            Pending p = takePending(run.charId, i, item, now);
            if (p != null) {
                origin[i] = p.origin();
                if (p.gain()) gathered(run, item, ench[i], origin[i], now);   // not a move within its own slots
                continue;
            }
            int from = -1;   // moved from another slot of its own
            for (int j = 0; j < old.length && from < 0; j++) {
                if (!taken[j] && old[j] == item && (j >= n || slots[j] != item)) from = j;
            }
            if (from >= 0) {
                taken[from] = true;
                origin[i] = oldOrigin[from];
                since[i] = oldSince[from];
                continue;
            }
            if (!gap && now - forgedAt < CONTEXT_MS) origin[i] = forgedOrigin;
            else if (!gap && now - questAt < CONTEXT_MS) {
                origin[i] = questOrigin;
                questParts.remove(lastQuest);   // the reward came: its hand-ins start over
                if (CRAFTED.equals(questOrigin)) useAllowance(run, item);   // it came straight to the inventory
            }
            else if (!gap && now - rewardAt < CONTEXT_MS) origin[i] = REWARD;
            else if (!gap && now - tradedAt < CONTEXT_MS) origin[i] = guild(run.mode) && run.standing() ? CHECKING : TRADE;
            else if (!gap && inVaultMap) {   // taken out of the vault without a move we saw
                origin[i] = useAllowance(run, item) ? CRAFTED : VAULT;
            }
            else {
                origin[i] = UNKNOWN;
                since[i] = gap ? run.lastSeen : now;
            }
            gathered(run, item, ench[i], origin[i], now);   // made from clean things, or a clean reward
        }
        run.items = slots.clone();
        run.enchants = ench.clone();
        run.origin = origin;
        run.since = since;
        if (gap) changed |= potionsWhileAway(run, level, baseStats, now);
        run.lastSeen = now;
        run.level = level;
        if (baseStats != null) run.baseStats = baseStats;
        changed |= check(run, now);
        if (changed) save();
        return changed;
    }

    /**
     * After a time unseen: stats that went up without a level up came from potions. If stat potions of that stat
     * left a vault in the same time, they may have been drunk by this character: a note (another character may have
     * taken them).
     */
    private boolean potionsWhileAway(Run run, int level, int[] baseStats, long now) {
        if (!run.standing() || baseStats == null || run.baseStats == null || level != run.level || level < 20) return false;
        boolean changed = false;
        for (int stat = 0; stat < Math.min(8, baseStats.length); stat++) {
            if (baseStats[stat] <= run.baseStats[stat]) continue;
            for (Left l : state.left) {
                if (items.potionStat(l.item) != stat || l.to < run.lastSeen || l.from > now) continue;
                changed |= note(run, items.name(l.item) + " left the " + vaultName(l.vault) + " (" + span(l)
                        + ") while this character's stats went up unseen");
                break;
            }
        }
        return changed;
    }

    /**
     * A move you asked for and the game confirmed: what each of the two slots holds now. A slot of your character
     * that got an item from a container learns where it came from (a loot bag, the vault). playerId: your
     * character's object; section: the vault container's section, if the other side is one; lootBag: whether the
     * other side is a loot bag.
     */
    public void moved(int charId, int playerId, int fromObj, int fromSlot, int fromItemNow, int toObj, int toSlot, int toItemNow,
                      String fromSection, boolean fromBag, String toSection, boolean toBag) {
        if (charId <= 0) return;
        boolean changed = false;
        synchronized (this) {
            Run run = state.runs.get(charId);
            if (run == null) return;
            long now = System.currentTimeMillis();
            // the item now in a slot of yours came from the other side of the move
            if (fromObj == playerId && fromItemNow > 0) {
                String o = toObj == playerId ? originAt(run, toSlot) : toBag ? DROP : fromVault(run, toSection, fromItemNow);
                changed |= announce(run, fromSlot, fromItemNow, o, now, toObj != playerId);
            }
            if (toObj == playerId && toItemNow > 0) {
                String o = fromObj == playerId ? originAt(run, fromSlot) : fromBag ? DROP : fromVault(run, fromSection, toItemNow);
                changed |= announce(run, toSlot, toItemNow, o, now, fromObj != playerId);
            }
            if (changed) {
                check(run, now);
                save();
            }
        }
        if (changed) fire();
    }

    /**
     * The origin of an item taken out of a vault container (section; null: not the vault): a clean quest reward the
     * run may still take from the gift chest (its allowance is used up then), else the vault.
     */
    private String fromVault(Run run, String section, int item) {
        if (section == null) return UNKNOWN;
        if (Holdings.GIFT_CHEST.equals(section) && useAllowance(run, item)) {
            AppLog.log("upe", "Character " + run.charId + " took its quest reward " + items.name(item) + " from the gift chest");
            return CRAFTED;
        }
        return VAULT;
    }

    /** Uses up the allowance that covers this item; false if none does. */
    private static boolean useAllowance(Run run, int item) {
        for (int[] a : run.allowed) {
            for (int x : a) {
                if (x == item) {
                    run.allowed.remove(a);
                    return true;
                }
            }
        }
        return false;
    }

    /** The slot's origin as last seen (UNKNOWN when out of range). */
    private static String originAt(Run run, int slot) {
        return slot >= 0 && slot < run.origin.length ? run.origin[slot] : UNKNOWN;
    }

    /**
     * Where the item now in a slot came from. If the sniffer already saw it there (the game's answer can come after
     * the stats), it is written right away; else it waits for the next look at the slots.
     */
    private boolean announce(Run run, int slot, int item, String origin, long now, boolean gain) {
        if (UNKNOWN.equals(origin)) return false;
        if (slot >= 0 && slot < run.items.length && run.items[slot] == item) {
            if (origin.equals(run.origin[slot])) return false;
            boolean wasOwn = DROP.equals(run.origin[slot]) || CRAFTED.equals(run.origin[slot]);
            run.origin[slot] = origin;
            run.since[slot] = 0;
            if (!wasOwn && gain) gathered(run, item, run.enchants[slot], origin, now);
            return true;
        }
        pending.removeIf(p -> now - p.at() > CONTEXT_MS);
        pending.add(new Pending(run.charId, slot, item, origin, now, gain));
        return false;
    }

    private Pending takePending(int charId, int slot, int item, long now) {
        pending.removeIf(p -> now - p.at() > CONTEXT_MS);
        for (Pending p : pending) {
            if (p.charId() == charId && p.slot() == slot && p.item() == item) {
                pending.remove(p);
                return p;
            }
        }
        return null;
    }

    /**
     * An item used (drunk, eaten...) from a slot of your character, or straight from a vault container (section not
     * null). Checked are stat potions and loot boosts (Oryx runes, experience boosts and the rest are free to use):
     * one from the vault, a trade (outside a gUPE), a purchase or a reward breaks the run. A loot boost must have
     * been gathered in the run.
     */
    public void used(int charId, int slot, int item, String section) {
        boolean potion = items.potionStat(item) >= 0, boost = items.lootBoost(item);
        if (charId <= 0 || !potion && !boost) return;
        boolean changed = false;
        synchronized (this) {
            Run run = state.runs.get(charId);
            if (run == null || !run.standing()) return;
            long now = System.currentTimeMillis();
            String name = items.name(item), verb = potion ? "Drank a " : "Used a ";
            if (section != null) changed = breakRun(run, verb + name + " from the " + section.toLowerCase(), now);
            else {
                String o = originAt(run, slot);
                if (VAULT.equals(o)) changed = breakRun(run, verb + name + " taken from the vault", now);
                else if (TRADE.equals(o)) changed = breakRun(run, verb + name + " got in a trade", now);
                else if (REWARD.equals(o)) changed = breakRun(run, verb + name + ", bought or given as a reward", now);
                else if (FORGED_TAINTED.equals(o)) changed = breakRun(run, verb + name + " made from things of the vault or a trade", now);
                else if (GUILD.equals(o) || CHECKING.equals(o)) {
                    if (!guild(run.mode)) changed = breakRun(run, verb + name + " traded from a guild mate (only a guild mode may)", now);
                    else if (!run.guildLocked) {
                        run.guildLocked = true;   // used a guild mate's item: no way back to UPE
                        changed = true;
                    }
                } else if (UNKNOWN.equals(o) && slot >= 0 && slot < run.items.length) {
                    Left l = leftVault(item, run.enchants[slot], run.since[slot], now);
                    if (l != null) changed = breakRun(run, verb + name + " that left the " + vaultName(l.vault)
                            + " while the sniffer was off (" + span(l) + ")", now);
                }
            }
            if (changed) save();
        }
        if (changed) fire();
    }

    // ---- trades ---------------------------------------------------------------------------------------------------

    /** The open trade window: the partner, both sides' slots (items, enchantments) and what each side offers. */
    private String tradePartner = "";
    private int[] myTrade = new int[0], theirTrade = new int[0];
    private int[][] myTradeEnch = new int[0][], theirTradeEnch = new int[0][];
    private boolean[] myOffer = new boolean[0], theirOffer = new boolean[0];
    private static final long KEEP_TRADES_MS = 30L * 24 * 60 * 60 * 1000;

    /** A trade window opened with this partner: what both sides hold, slot by slot (equipment, then inventory). */
    public synchronized void tradeStart(String partner, int[] mine, int[][] mineEnch, int[] theirs, int[][] theirsEnch) {
        tradePartner = partner == null ? "" : partner;
        myTrade = mine == null ? new int[0] : mine;
        theirTrade = theirs == null ? new int[0] : theirs;
        myTradeEnch = mineEnch == null ? new int[myTrade.length][] : mineEnch;
        theirTradeEnch = theirsEnch == null ? new int[theirTrade.length][] : theirsEnch;
        myOffer = new boolean[myTrade.length];
        theirOffer = new boolean[theirTrade.length];
    }

    /** What each side offers, as an accept says it (the last one before the trade goes through counts). */
    public synchronized void tradeOffers(boolean[] mine, boolean[] theirs) {
        if (mine != null) myOffer = mine;
        if (theirs != null) theirOffer = theirs;
    }

    /**
     * A trade went through while playing this character. Only a guild mode may trade; it notes the trade (what
     * it gave, whether its run had gathered it, what it got) and reports it to the server at once; what it got is
     * CHECKING until the partner's report confirms it (see GuildUpe).
     */
    public void traded(int charId, String me) {
        boolean changed = false;
        Trade report = null;
        synchronized (this) {
            tradedAt = System.currentTimeMillis();
            Run run = state.runs.get(charId);
            if (run != null && run.standing()) {
                repair(run);
                String partner = tradePartner.isBlank() ? "another player" : tradePartner;
                if (!guild(run.mode)) {
                    changed = breakRun(run, "Traded with " + partner, tradedAt);
                } else {
                    Trade t = new Trade();
                    t.at = tradedAt;
                    t.charId = charId;
                    t.me = me == null ? "" : me;
                    t.partner = tradePartner;
                    t.mode = run.mode;
                    t.standing = true;
                    for (int i = 0; i < myTrade.length; i++) {
                        if (i >= myOffer.length || !myOffer[i] || myTrade[i] < 1) continue;
                        t.gave.add(tradeItem(myTrade[i], i < myTradeEnch.length ? myTradeEnch[i] : null, ownForTrade(originAt(run, i))));
                    }
                    for (int i = 0; i < theirTrade.length; i++) {
                        if (i >= theirOffer.length || !theirOffer[i] || theirTrade[i] < 1) continue;
                        t.got.add(tradeItem(theirTrade[i], i < theirTradeEnch.length ? theirTradeEnch[i] : null, false));
                    }
                    if (t.got.isEmpty()) t.status = "ok";   // nothing came in: nothing to check
                    run.trades.add(t);
                    long cut = tradedAt - KEEP_TRADES_MS;
                    run.trades.removeIf(x -> x.at < cut);
                    report = t;
                    changed = true;
                    AppLog.log("upe", label(run.mode) + " trade with " + partner + ": gave " + t.gave.size() + ", got " + t.got.size()
                            + " item(s); waiting for " + partner + "'s report");
                }
                save();
            }
        }
        if (changed) fire();
        if (report != null) {
            reportSoon.run();   // the partner checks this report
            if (CHECKING.equals(report.status)) checker.accept(copyOf(report));
        }
    }

    private static TradeItem tradeItem(int item, int[] ench, boolean clean) {
        TradeItem t = new TradeItem();
        t.item = item;
        t.enchants = ench;
        t.clean = clean;
        return t;
    }

    private static Trade copyOf(Trade t) {
        return GSON.fromJson(GSON.toJson(t), Trade.class);
    }

    /**
     * The verdict on a gUPE trade, from the partner's report: ok makes what came in GUILD (gathered by the partner's
     * run, clean for this one); illegal makes it TRADE and loses the run.
     */
    public void tradeChecked(int charId, long at, boolean ok, String why) {
        boolean changed = false;
        synchronized (this) {
            Run run = state.runs.get(charId);
            if (run == null) return;
            repair(run);
            Trade t = run.trades.stream().filter(x -> x.at == at).findFirst().orElse(null);
            if (t == null || !CHECKING.equals(t.status)) return;
            long now = System.currentTimeMillis();
            t.status = ok ? "ok" : "illegal";
            t.why = why;
            for (int i = 0; i < run.origin.length; i++) {
                if (!CHECKING.equals(run.origin[i])) continue;
                run.origin[i] = ok ? GUILD : TRADE;
                if (ok) gathered(run, run.items[i], run.enchants[i], GUILD, now);
            }
            AppLog.log("upe", "Guild trade with " + t.partner + " checked: " + (ok ? "ok" : "illegal") + (why == null ? "" : " - " + why));
            if (!ok) breakRun(run, "Traded with " + (t.partner.isBlank() ? "another player" : t.partner) + ": " + why, now);
            check(run, now);
            save();
            changed = true;
        }
        if (changed) {
            fire();
            reportSoon.run();
        }
    }

    /** The gUPE trades still waiting for the partner's report (copies), e.g. to check them again after a restart. */
    public synchronized List<Trade> checking() {
        List<Trade> out = new ArrayList<>();
        for (Run r : state.runs.values()) {
            if (r.trades == null) continue;
            for (Trade t : r.trades) if (CHECKING.equals(t.status)) out.add(copyOf(t));
        }
        return out;
    }

    /** Every gUPE trade of a run (copies), newest first. */
    public synchronized List<Trade> trades(int charId) {
        Run r = state.runs.get(charId);
        if (r == null || r.trades == null) return List.of();
        return r.trades.stream().sorted((a, b) -> Long.compare(b.at, a.at)).map(Upe::copyOf).toList();
    }

    /**
     * Changes a run's mode. A run not started yet may take any mode it can start; a started one only turns its guild
     * version on or off (off only while it never equipped or used a guild mate's item).
     *
     * @return whether it switched
     */
    public boolean setMode(int charId, String mode) {
        if (!MODES.contains(mode)) return false;
        synchronized (this) {
            Run run = state.runs.get(charId);
            if (run == null || !(run.standing() || run.potential())) return false;
            repair(run);
            if (mode.equals(run.mode)) return true;
            if (run.marked) {
                if (!base(mode).equals(base(run.mode))) return false;   // the mode itself is set when the run starts
                if (!guild(mode) && run.guildLocked) return false;
            } else if (!run.canStart(mode, run.noPet)) return false;
            run.mode = mode;
            AppLog.log("upe", "Character " + charId + " plays a " + mode + " now");
            check(run, System.currentTimeMillis());
            save();
        }
        fire();
        return true;
    }

    /**
     * The game gave or sold you something (a shop purchase, a daily login reward, a reward chest, a battle pass or
     * mission claim): what turns up in the next seconds is no drop.
     */
    public synchronized void rewarded(String what) {
        rewardAt = System.currentTimeMillis();
        rewardWhat = what;
        AppLog.log("upe", "Reward or purchase: " + what);
    }

    /** Items handed in for a Tinkerer quest: the slots of your character they came from. */
    public synchronized void questHandIn(int charId, int questId, int[] slots, int[] rewards, boolean rewardOfChoice) {
        Run run = state.runs.get(charId);
        boolean clean = run != null && run.standing();
        if (run != null) for (int s : slots) if (tainted(originAt(run, s), run.mode)) clean = false;
        lastQuest = questId;
        lastQuestPart = new int[]{charId, clean ? 1 : 0};
        lastRewards = rewards == null ? new int[0] : rewards;
        lastChoice = rewardOfChoice;
    }

    private int lastQuest;
    private int[] lastQuestPart;
    private int[] lastRewards = new int[0];
    private boolean lastChoice;

    /**
     * The game's answer to a hand-in. The reward that may come with it is clean only if every hand-in for this quest
     * since its last reward was this character's, during its run, from clean items: a quest partly solved by another
     * character (or another run) does not count.
     */
    public synchronized void questAnswer(boolean ok) {
        if (!ok || lastQuestPart == null) return;
        List<int[]> parts = questParts.computeIfAbsent(lastQuest, k -> new ArrayList<>());
        parts.add(lastQuestPart);
        int charId = lastQuestPart[0];
        boolean clean = parts.stream().allMatch(p -> p[0] == charId && p[1] == 1);
        questOrigin = clean ? CRAFTED : REWARD;
        questAt = System.currentTimeMillis();
        lastQuestPart = null;
        Run run = state.runs.get(charId);
        if (clean && run != null && lastRewards.length > 0) {   // the rewards may land in the gift chest: allow them once
            repair(run);
            if (lastChoice) run.allowed.add(lastRewards.clone());
            else for (int r : lastRewards) if (r > 0) run.allowed.add(new int[]{r});
            save();
        }
        AppLog.log("upe", "Quest " + lastQuest + " hand-in by character " + charId + ": its reward is "
                + (clean ? "clean (all handed in during this run)" : "not clean (partly from elsewhere)"));
    }

    /**
     * Progress on a battle pass mission while playing this character. wasZero: the mission's progress was at 0
     * before this step (never started, or reset after a claim).
     */
    public synchronized void missionStep(String mission, int charId, boolean wasZero) {
        Run run = state.runs.get(charId);
        boolean standing = run != null && run.standing();
        MissionRun m = state.missions.get(mission);
        if (wasZero || m == null) {
            m = new MissionRun();
            m.charId = charId;
            m.fromZero = wasZero;
            m.clean = standing && wasZero;
            m.startedAt = System.currentTimeMillis();
            state.missions.put(mission, m);
            save();
        } else if (m.clean && (m.charId != charId || !standing)) {
            m.clean = false;   // partly solved by another character, or outside a standing run
            save();
        }
    }

    /**
     * A battle pass mission claimed while playing this character. Its rewards are clean if the mission began at 0
     * with this character and every step was its own, during its run: what turns up now is clean, and the items may
     * be taken from the gift chest once each. Otherwise they count as a reward (not allowed). rewardGroups: per
     * group the item ids, one of which is given.
     */
    public void missionClaimed(String mission, int charId, List<int[]> rewardGroups) {
        boolean changed = false;
        synchronized (this) {
            MissionRun m = mission == null ? null : state.missions.remove(mission);
            Run run = state.runs.get(charId);
            boolean clean = m != null && m.fromZero && m.clean && m.charId == charId && run != null && run.standing();
            questOrigin = clean ? CRAFTED : REWARD;   // the same as a quest's reward from here on
            questAt = System.currentTimeMillis();
            lastQuest = -1;
            if (clean && rewardGroups != null) {
                repair(run);
                for (int[] g : rewardGroups) if (g.length > 0) run.allowed.add(g.clone());
                changed = true;
            }
            AppLog.log("upe", "Mission " + mission + " claimed by character " + charId + ": its reward is "
                    + (clean ? "clean (begun at 0 and solved by this character in its run)"
                    : m == null ? "not clean (its progress was not followed)" : "not clean (" + (!m.fromZero ? "it had progress before"
                    : m.charId != charId ? "begun by another character" : "partly made elsewhere") + ")"));
            save();
        }
        if (changed) fire();
    }

    /** A forge request: the slots of your character that go into it. */
    public synchronized void forgeRequest(int charId, int[] slots) {
        Run run = state.runs.get(charId);
        List<String> in = new ArrayList<>();
        for (int s : slots) in.add(run == null ? UNKNOWN : originAt(run, s));
        forgeInputs = in;
        forgeMode = run == null ? UPE : run.mode;
    }

    private String forgeMode = UPE;

    /**
     * The forge's answer. What it made is clean if everything that went in was (dropped, crafted from drops, the
     * starting gear, or of unknown origin: the benefit of the doubt); made from the vault's or a trade's things it
     * is not. resultSlots/resultItems: where the results went, if the answer says so.
     */
    public void forgeResult(int charId, boolean success, int[] resultSlots, int[] resultItems) {
        if (!success) return;
        boolean changed = false;
        synchronized (this) {
            long now = System.currentTimeMillis();
            String mode = forgeMode;
            boolean clean = forgeInputs.stream().noneMatch(o -> tainted(o, mode));
            forgedOrigin = clean ? CRAFTED : FORGED_TAINTED;
            forgedAt = now;
            forgeInputs = List.of();
            Run run = state.runs.get(charId);
            if (run != null && resultSlots != null && resultItems != null) {
                for (int i = 0; i < Math.min(resultSlots.length, resultItems.length); i++) {
                    if (resultItems[i] > 0) changed |= announce(run, resultSlots[i], resultItems[i], forgedOrigin, now, true);
                }
                if (changed) {
                    check(run, now);
                    save();
                }
            }
        }
        if (changed) fire();
    }

    // ---- the vault records ----------------------------------------------------------------------------------------

    /** A slot of a vault place, as Holdings stores it: the item and its enchantments. */
    public record Held(int item, List<Integer> enchants) {
    }

    /**
     * A vault looked at again (by the sniffer, the char list or another PC): what is gone since the last look left
     * it in between. Then the runs' items of unknown origin are checked against it.
     */
    public void vaultChanged(String vault, long beforeAt, List<Held> before, long nowAt, List<Held> now) {
        if (before == null || now == null || beforeAt <= 0) return;
        Map<String, Integer> remaining = new HashMap<>();
        for (Held h : now) if (h.item() > 0) remaining.merge(key(h.item(), toArray(h.enchants())), 1, Integer::sum);
        boolean changed = false;
        synchronized (this) {
            for (Held h : before) {
                if (h.item() < 1) continue;
                int[] ench = toArray(h.enchants());
                String k = key(h.item(), ench);
                int left = remaining.getOrDefault(k, 0);
                if (left > 0) {
                    remaining.put(k, left - 1);
                    continue;
                }
                Left l = new Left();
                l.item = h.item();
                l.enchants = ench;
                l.from = beforeAt;
                l.to = nowAt;
                l.vault = vault;
                state.left.add(l);
                changed = true;
            }
            long cut = System.currentTimeMillis() - KEEP_LEFT_MS;
            state.left.removeIf(l -> l.to < cut);
            if (changed) {
                long t = System.currentTimeMillis();
                for (Run run : state.runs.values()) check(run, t);
                save();
            }
        }
        if (changed) fire();
    }

    /**
     * The record of this item leaving a vault in the time it may have arrived unseen (from since to until), or null.
     * Items without enchantments match any copy, so only one with the same (non-empty) enchantments is proof.
     */
    private Left leftVault(int item, int[] ench, long since, long until) {
        if (since <= 0) return null;
        for (Left l : state.left) {
            if (l.item != item || l.to < since || l.from > until) continue;
            if (ench != null && ench.length > 0 && Arrays.equals(sorted(ench), sorted(l.enchants))) return l;
        }
        return null;
    }

    /** Like leftVault, but any copy of the item (for a note, not proof). */
    private Left leftVaultAnyCopy(int item, long since, long until) {
        if (since <= 0) return null;
        for (Left l : state.left) if (l.item == item && l.to >= since && l.from <= until) return l;
        return null;
    }

    // ---- the rules ----------------------------------------------------------------------------------------------

    /** Checks the loadout of a standing run; true if anything changed (broken, or a note added). */
    private boolean check(Run run, long now) {
        if (!run.standing()) return false;
        boolean changed = prerequisites(run, now);
        for (int i = 0; i < Math.min(LOADOUT, run.items.length) && run.broken == null; i++) {
            int item = run.items[i];
            if (item < 1) continue;
            String o = run.origin[i], name = items.name(item);
            if (STARTER.equals(o)) continue;
            String misfit = misfit(run.mode, item, run.enchants[i]);
            if (misfit != null) {
                changed |= breakRun(run, "Equipped " + name + ", " + misfit, now);
            } else if (GUILD.equals(o) || CHECKING.equals(o)) {
                if (!guild(run.mode)) {
                    changed |= breakRun(run, "Equipped " + name + " traded from a guild mate (only a guild mode may)", now);
                } else if (!run.guildLocked) {
                    run.guildLocked = true;   // a guild mate's item in the loadout: no way back to UPE
                    changed = true;
                }
            } else if (VAULT.equals(o)) {
                changed |= breakRun(run, "Equipped " + name + " taken from the vault", now);
            } else if (TRADE.equals(o)) {
                changed |= breakRun(run, "Equipped " + name + " got in a trade", now);
            } else if (REWARD.equals(o)) {
                changed |= breakRun(run, "Equipped " + name + ", bought or given as a reward (not a drop)", now);
            } else if (FORGED_TAINTED.equals(o)) {
                changed |= breakRun(run, "Equipped " + name + ", forged from things of the vault or a trade", now);
            } else if (UNKNOWN.equals(o)) {
                Left l = leftVault(item, run.enchants[i], run.since[i], run.lastSeen);
                if (l != null) {
                    changed |= breakRun(run, "Equipped " + name + ": the same item, enchantments and all, left the "
                            + vaultName(l.vault) + " while the sniffer was off (" + span(l) + ")", now);
                } else {
                    Left any = leftVaultAnyCopy(item, run.since[i], run.lastSeen);
                    if (any != null) changed |= note(run, name + " is equipped, and a copy left the "
                            + vaultName(any.vault) + " in the same time (" + span(any) + "); it may be its own drop");
                }
            }
        }
        return changed;
    }

    /** Why this item may not be in the loadout of a run in this mode ("a tiered item"...), or null if it may. */
    String misfit(String mode, int item, int[] enchants) {
        String b = base(mode);
        if (PPE.equals(b)) return null;   // any tier
        if (DIVINE_PE.equals(b)) return enchants == null || enchants.length < 4 ? "not a divine item (four enchantment slots)" : null;   // any tier
        if (!items.untiered(item)) return "a tiered item";
        if (STPE.equals(b) && !items.st(item)) return "not a set item (ST)";
        if (PUPE.equals(b) && items.st(item)) return "a set item (ST): a pure UPE takes UT only";
        if (SHINY_UPE.equals(b) && !items.shiny(item)) return "not a shiny item";
        return null;
    }

    /** Ends a run that does not meet what its mode asks before it starts. */
    private boolean prerequisites(Run run, long now) {
        if (!run.marked || run.broken != null) return false;
        if (Boolean.FALSE.equals(run.t0Start)) return breakRun(run, "Created with upgrades enabled (its starting gear was not tier 0)", now);
        if (run.noPet && run.pet) return breakRun(run, "Played with a pet", now);
        return false;
    }

    private boolean breakRun(Run run, String why, long now) {
        if (run.broken != null) return false;
        run.broken = why;
        run.brokenAt = now;
        AppLog.log("upe", "Character " + run.charId + ": " + label(run.mode) + " lost - " + why);
        return true;
    }

    private static boolean note(Run run, String text) {
        if (run.notes.contains(text)) return false;
        run.notes.add(text);
        if (run.notes.size() > 20) run.notes.remove(0);
        return true;
    }

    // ---- what the player decides ---------------------------------------------------------------------------------

    /**
     * Marks a character as a UPE, or takes the mark off. Only a potential UPE can be marked: seen by the sniffer
     * from its creation, nothing against the rules. A broken run stays broken.
     */
    public void mark(int charId, boolean on) {
        mark(charId, on, null);
    }

    /** Starts (on) a run in this mode (null: keep the run's), or takes the mark off. */
    public void mark(int charId, boolean on, String mode) {
        mark(charId, on, mode, null);
    }

    /** Starts (on) a run in this mode, with or without a pet (null: keep the run's setting), or takes the mark off. */
    public void mark(int charId, boolean on, String mode, Boolean noPet) {
        synchronized (this) {
            Run run = state.runs.get(charId);
            long now = System.currentTimeMillis();
            if (run == null) return;
            repair(run);
            if (on) {
                String m = mode != null && MODES.contains(mode) ? mode : run.mode;
                boolean np = noPet != null ? noPet : run.noPet;
                if (!run.canStart(m, np)) return;   // only a fresh character, upgrades disabled, nothing against it
                run.mode = m;
                run.noPet = np;
                run.marked = true;
            } else {
                if (!run.marked) return;
                run.marked = false;
            }
            run.markedAt = now;
            AppLog.log("upe", "Character " + charId + (on ? " starts a " + label(run.mode) + (run.noPet ? " run without a pet" : " run") : ": " + label(run.mode) + " run ended"));
            check(run, now);
            save();
        }
        fire();
    }

    /**
     * Turns playing without a pet on or off. On only before the run starts (Loot Gobbler cannot tell whether a pet
     * followed it before); off any time.
     *
     * @return whether it changed
     */
    public boolean setNoPet(int charId, boolean noPet) {
        synchronized (this) {
            Run run = state.runs.get(charId);
            if (run == null || run.noPet == noPet) return false;
            if (noPet && (run.marked || !run.potential() || run.pet)) return false;
            run.noPet = noPet;
            AppLog.log("upe", "Character " + charId + (noPet ? " plays without a pet" : " may have a pet again"));
            save();
        }
        fire();
        return true;
    }

    /**
     * What the character list says of the characters' pets (charId: whether a pet follows it). A run without a pet
     * that has one is lost; a character not started yet can start one only while it has none.
     */
    public void pets(Map<Integer, Boolean> pets) {
        if (pets == null || pets.isEmpty()) return;
        boolean changed = false;
        synchronized (this) {
            long now = System.currentTimeMillis();
            for (Map.Entry<Integer, Boolean> e : pets.entrySet()) {
                Run run = state.runs.get(e.getKey());
                if (run == null || e.getValue() == null || run.pet == e.getValue()) continue;
                run.pet = e.getValue();
                changed = true;
                check(run, now);
            }
            if (changed) save();
        }
        if (changed) fire();
    }

    // ---- reading ----------------------------------------------------------------------------------------------

    /** The run of a character, or null if it has none (a copy: changing it changes nothing). */
    public synchronized Run run(int charId) {
        Run r = state.runs.get(charId);
        return r == null ? null : GSON.fromJson(GSON.toJson(r), Run.class);
    }

    /** Every run worth showing: potential, marked, or lost after being either (copies), newest character first. */
    public synchronized List<Run> runs() {
        return state.runs.values().stream().filter(r -> r.eligible() || r.marked).sorted((a, b) -> Integer.compare(b.charId, a.charId))
                .map(r -> {
                    Run copy = GSON.fromJson(GSON.toJson(r), Run.class);
                    repair(copy);
                    return copy;
                }).toList();
    }

    /** The runs marked as UPE by the player (lost ones too), newest character first (copies). */
    public synchronized List<Run> markedRuns() {
        return state.runs.values().stream().filter(r -> r.marked).sorted((a, b) -> Integer.compare(b.charId, a.charId))
                .map(r -> {
                    Run copy = GSON.fromJson(GSON.toJson(r), Run.class);
                    repair(copy);
                    return copy;
                }).toList();
    }

    /** Characters that could be a UPE but are not marked (seen from their creation, nothing against them). */
    public synchronized List<Integer> potential() {
        return state.runs.values().stream().filter(Run::potential).map(r -> r.charId).sorted().toList();
    }

    /** A short line about the run for tooltips. */
    public static String describe(Run run) {
        if (run == null || !run.fresh && !run.marked) return "Not eligible for a game mode: the sniffer did not see this character from its creation";
        StringBuilder sb = new StringBuilder();
        if (run.broken != null) {
            sb.append(label(run.mode)).append(" lost ").append(date(run.brokenAt)).append(": ").append(run.broken);
        } else if (run.marked) {
            sb.append(label(run.mode)).append(run.noPet ? " run without a pet" : " run").append(", followed since the character was created");
            if (!run.allowed.isEmpty()) sb.append("\nQuest rewards it may still take from the gift chest: ").append(run.allowed.size());
        } else if (Boolean.FALSE.equals(run.t0Start)) {
            sb.append("Not eligible for a game mode: created with upgrades enabled (its starting gear was not tier 0)");
        } else {
            sb.append("Can start a game mode: created with the sniffer running and upgrades disabled, nothing against it yet");
            if (run.pet) sb.append("\nIt has a pet, so it cannot start a run without one");
        }
        return sb.toString();
    }

    // ---- syncing ------------------------------------------------------------------------------------------------

    @Override
    public String syncKind() {
        return "upe";
    }

    @Override
    public String syncLabel() {
        return "UPE runs";
    }

    @Override
    public synchronized int syncCount() {
        return (int) state.runs.values().stream().filter(r -> r.marked || r.eligible()).count();
    }

    @Override
    public synchronized JsonElement exportSync() {
        return GSON.toJsonTree(state);
    }

    /**
     * Per character: a break stays (the earlier one wins), the newer mark decision wins, the newer look at the slots
     * wins, and seen-from-creation on either side counts. Vault records are joined.
     */
    @Override
    public boolean mergeSync(JsonElement remote) {
        if (remote == null || !remote.isJsonObject()) return false;
        State other;
        try {
            other = GSON.fromJson(remote, State.class);
        } catch (Exception e) {
            return false;
        }
        if (other == null) return false;
        boolean changed = false;
        synchronized (this) {
            if (other.runs != null) {
                for (Run o : other.runs.values()) {
                    if (o == null) continue;
                    repair(o);
                    Run mine = state.runs.get(o.charId);
                    if (mine == null) {
                        state.runs.put(o.charId, o);
                        changed = true;
                        continue;
                    }
                    String before = GSON.toJson(mine);
                    mergeRun(mine, o);
                    changed |= !before.equals(GSON.toJson(mine));
                }
            }
            if (other.left != null) {
                for (Left l : other.left) {
                    if (state.left.stream().noneMatch(m -> m.item == l.item && m.from == l.from && m.to == l.to
                            && Objects.equals(m.vault, l.vault) && Arrays.equals(m.enchants, l.enchants))) {
                        state.left.add(l);
                        changed = true;
                    }
                }
            }
            if (other.missions != null) {   // the later start wins; the same start stays clean only if clean on both
                for (Map.Entry<String, MissionRun> e : other.missions.entrySet()) {
                    MissionRun o = e.getValue(), mine = state.missions.get(e.getKey());
                    if (o == null) continue;
                    if (mine == null || o.startedAt > mine.startedAt) {
                        state.missions.put(e.getKey(), o);
                        changed = true;
                    } else if (o.startedAt == mine.startedAt && mine.clean && !o.clean) {
                        mine.clean = false;
                        changed = true;
                    }
                }
            }
            if (changed) save();
        }
        if (changed) fire();
        return changed;
    }

    static void mergeRun(Run mine, Run o) {
        mine.fresh |= o.fresh;
        if (Boolean.FALSE.equals(o.t0Start) || mine.t0Start == null) mine.t0Start = o.t0Start;
        mine.guildLocked |= o.guildLocked;
        if (o.trades != null) {   // the trades of both, a verdict over "checking"
            for (Trade t : o.trades) {
                Trade m = mine.trades.stream().filter(x -> x.at == t.at).findFirst().orElse(null);
                if (m == null) mine.trades.add(t);
                else if (CHECKING.equals(m.status) && !CHECKING.equals(t.status)) {
                    m.status = t.status;
                    m.why = t.why;
                }
            }
        }
        if (o.markedAt > mine.markedAt && o.mode != null) {
            mine.mode = o.mode;
            mine.noPet = o.noPet;
        }
        if (o.markedAt > mine.markedAt) {
            mine.marked = o.marked;
            mine.markedAt = o.markedAt;
        }
        if (o.broken != null && (mine.broken == null || o.brokenAt < mine.brokenAt)) {
            mine.broken = o.broken;
            mine.brokenAt = o.brokenAt;
        }
        for (String n : o.notes) if (!mine.notes.contains(n)) mine.notes.add(n);
        if (o.gathered != null) {   // what either PC saw it gather
            for (Gathered g : o.gathered) {
                if (mine.gathered.stream().noneMatch(x -> x.item == g.item && x.at == g.at)) mine.gathered.add(g);
            }
            mine.gathered.sort(java.util.Comparator.comparingLong(g -> g.at));
        }
        if (o.lastSeen > mine.lastSeen) {
            mine.lastSeen = o.lastSeen;
            mine.items = o.items;
            mine.enchants = o.enchants;
            mine.origin = o.origin;
            mine.since = o.since;
            mine.level = o.level;
            mine.baseStats = o.baseStats;
            mine.allowed = o.allowed;
            mine.pet = o.pet;
        }
    }

    @Override
    public void onChange(Runnable listener) {
        listeners.add(listener);
    }

    private void fire() {
        for (Runnable r : listeners) r.run();
    }

    private void save() {
        if (!persist) return;
        try {
            Path tmp = Path.of(FILE + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(state, w);
            }
            Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            System.err.println("Could not save " + FILE + ": " + e.getMessage());
        }
    }

    // ---- small helpers --------------------------------------------------------------------------------------------

    private static String[] filled(int n, String o) {
        String[] a = new String[n];
        Arrays.fill(a, o);
        return a;
    }

    private static int[] toArray(List<Integer> l) {
        return l == null ? new int[0] : l.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int[] sorted(int[] a) {
        int[] c = a == null ? new int[0] : a.clone();
        Arrays.sort(c);
        return c;
    }

    private static String key(int item, int[] ench) {
        return item + ":" + Arrays.toString(sorted(ench));
    }

    private static String vaultName(String vault) {
        return Holdings.SEASONAL_VAULT.equals(vault) ? "seasonal vault" : "vault";
    }

    private static String span(Left l) {
        return "between " + date(l.from) + " and " + date(l.to);
    }

    static String date(long t) {
        return new SimpleDateFormat("d MMM HH:mm").format(new Date(t));
    }
}
