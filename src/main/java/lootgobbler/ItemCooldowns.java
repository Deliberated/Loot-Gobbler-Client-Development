package lootgobbler;

import packets.PacketType;
import packets.outgoing.UseItemPacket;
import packets.packetcapture.register.Register;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Items with a cooldown: when one can be used again (its Cooldown in the game data after each use), its sound plays.
 * The quickslot items Coral Juice Refinery (15 s, ItemSound.REFINERY) and Miniature Alien Generator (45 s,
 * ItemSound.ALIEN): a use is seen as the use packet you send for it, the classic one or the newer kind ItemUse reads
 * raw. The Protective Matrix armors (Heavy, Magic and Fitted: heavy armor, robe, light armor; each also Shiny and Neo;
 * one cooldown of 180 s, ItemSound.MATRIX) fire by themselves: on a hit below 50% of your life they give you
 * Invulnerable and Dazed at once, which the game tick sees (armorTick); ItemSound.MATRIX_BREAK plays then.
 */
public final class ItemCooldowns {
    public static final int REFINERY = 44495;   // Coral Juice Refinery (0xadcf, equip_design.xml)
    public static final int ALIEN_GENERATOR = 48164;   // Miniature Alien Generator (0xbc24, equip.xml)
    /** The Protective Matrix armors (equip.xml), all with the same shield and one shared cooldown. */
    public static final Map<Integer, String> MATRICES = Map.of(
            0x0483, "Heavy Protective Matrix", 0x0484, "Magic Protective Matrix", 0x0485, "Fitted Protective Matrix",
            0x1D4A, "Heavy Protective Matrix Shiny", 0x1D49, "Magic Protective Matrix Shiny",
            0x1D48, "Fitted Protective Matrix Shiny",
            0xdc92, "Neo Heavy Protective Matrix", 0xdc91, "Neo Magic Protective Matrix", 0xdc90, "Neo Fitted Protective Matrix");

    private record Watched(String name, long cooldownMs, ItemSound sound) {
    }

    private static final Map<Integer, Watched> WATCHED = new HashMap<>();

    static {
        WATCHED.put(REFINERY, new Watched("Coral Juice Refinery", 15_000, ItemSound.REFINERY));
        WATCHED.put(ALIEN_GENERATOR, new Watched("Miniature Alien Generator", 45_000, ItemSound.ALIEN));
        MATRICES.forEach((type, name) -> WATCHED.put(type, new Watched(name, 180_000, ItemSound.MATRIX)));
    }
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "item-cooldowns");
        t.setDaemon(true);
        return t;
    });
    // by sound: the matrices share one cooldown, whichever of them fired
    private static final Map<ItemSound, ScheduledFuture<?>> ready = new HashMap<>();
    private static final Map<ItemSound, Running> running = new java.util.LinkedHashMap<>();

    /** A cooldown that is running: the item used (its type), its name, when it started and how long it takes (ms). */
    public record Running(int type, String name, long start, long durationMs) {
        public long left(long now) {
            return Math.max(0, start + durationMs - now);
        }
    }

    /** The cooldowns running now, in the order they started. */
    public static synchronized java.util.List<Running> running() {
        long now = System.currentTimeMillis();
        running.values().removeIf(r -> r.left(now) <= 0);
        return new java.util.ArrayList<>(running.values());
    }

    private ItemCooldowns() {
    }

    public static void install() {
        Register.INSTANCE.register(PacketType.USEITEM, p -> {
            UseItemPacket u = (UseItemPacket) p;
            if (u.slotObject != null) used(u.slotObject.objectType, "use packet, slot " + u.slotObject.slotId);
        });
    }

    // the matrix: its rule (hit below 50% life, cooldown 180 s), and what the last game tick showed
    private static final double MATRIX_LIFE = 0.5;
    private static final long MATRIX_COOLDOWN_MS = 180_000, HIT_LOOK_BACK_MS = 1500;
    private static boolean wasInvulnerable;
    private static volatile long matrixFired;
    private static volatile int matrixType = -1;   // the armor that fired last
    private static final java.util.List<Runnable> matrixListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** When a matrix fired last (ms, 0: not yet). */
    public static long matrixFired() {
        return matrixFired;
    }

    /** The matrix armor that fired last (its item type), -1 if none. */
    public static int matrixType() {
        return matrixType;
    }

    /** Whether the matrix that fired last is still charging (180 s after it fired). */
    public static boolean matrixCharging() {
        return matrixFired > 0 && System.currentTimeMillis() - matrixFired < MATRIX_COOLDOWN_MS;
    }

    /** Called when a matrix fires (on the packet thread). */
    public static void onMatrixFired(Runnable r) {
        matrixListeners.add(r);
    }

    /**
     * Every game tick: wearing a Protective Matrix (any of them), the game putting Invulnerable on you right after a hit,
     * together with Dazed (or with your life at half or less, should Dazed be blocked), is the armor firing; its
     * sound then plays when it is charged again. Not within its cooldown of the last time, when it cannot fire.
     */
    public static void armorTick(tomato.backend.data.TomatoData data) {
        if (data == null || data.player == null) return;
        packets.data.StatData armor = data.player.stat.get(packets.data.enums.StatType.INVENTORY_0_STAT.get() + 2);
        packets.data.StatData cond = data.player.stat.get(packets.data.enums.StatType.CONDITION_STAT);
        int c = cond == null ? 0 : cond.statValue;
        boolean invulnerable = (c & packets.data.enums.ConditionBits.INVULNERABLE.value()) != 0;
        boolean dazed = (c & packets.data.enums.ConditionBits.DAZED.value()) != 0;
        boolean fresh = invulnerable && !wasInvulnerable;
        wasInvulnerable = invulnerable;
        int type = armor == null ? -1 : armor.statValue;
        if (!fresh || !MATRICES.containsKey(type)) return;
        long now = System.currentTimeMillis();
        if (now - matrixFired < MATRIX_COOLDOWN_MS) return;
        boolean hit = false;
        for (tomato.backend.data.Damage d : data.player.getDamageList()) {
            if (d != null && d.damage > 2 && now - d.time <= HIT_LOOK_BACK_MS) {
                hit = true;
                break;
            }
        }
        int hp = data.player.hp(), max = data.player.maxHp();
        boolean low = max > 0 && hp <= max * MATRIX_LIFE;
        if (!hit || !(dazed || low)) {
            AppLog.log("items", "Invulnerable wearing the matrix, not counted: " + (!hit ? "no hit just before" : "not dazed, life above half"));
            return;
        }
        matrixFired = now;
        matrixType = type;
        ItemSound.MATRIX_BREAK.play();   // it broke: charged again in 180 s
        matrixListeners.forEach(Runnable::run);
        used(type, "it fired: Invulnerable" + (dazed ? " and Dazed" : "") + " at " + (max > 0 ? 100 * hp / max : 0) + "% life");
    }

    /** You used an item: for a watched one, its sound plays when it can be used again (a new use starts over). */
    static synchronized void used(int itemType, String how) {
        Watched w = WATCHED.get(itemType);
        if (w == null) return;
        ScheduledFuture<?> old = ready.get(w.sound());
        if (old != null) old.cancel(false);
        running.remove(w.sound());   // a new use goes to the end
        running.put(w.sound(), new Running(itemType, w.name(), System.currentTimeMillis(), w.cooldownMs()));
        ready.put(w.sound(), TIMER.schedule(() -> {
            AppLog.log("items", w.name() + " ready again");
            w.sound().play();
        }, w.cooldownMs(), TimeUnit.MILLISECONDS));
        AppLog.log("items", w.name() + " used (" + how + "), ready again in " + w.cooldownMs() / 1000 + " s");
    }
}
