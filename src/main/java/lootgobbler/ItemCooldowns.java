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
 * raw. The Fitted Protective Matrix armor (and its Neo version, 180 s, ItemSound.MATRIX) fires by itself: on a hit
 * below 50% of your life it gives you Invulnerable and Dazed at once, which the game tick sees (armorTick).
 */
public final class ItemCooldowns {
    public static final int REFINERY = 44495;   // Coral Juice Refinery (0xadcf, equip_design.xml)
    public static final int ALIEN_GENERATOR = 48164;   // Miniature Alien Generator (0xbc24, equip.xml)
    public static final int MATRIX = 0x0485, NEO_MATRIX = 0xdc90;   // Fitted Protective Matrix, its Neo version

    private record Watched(String name, long cooldownMs, ItemSound sound) {
    }

    private static final Map<Integer, Watched> WATCHED = Map.of(
            REFINERY, new Watched("Coral Juice Refinery", 15_000, ItemSound.REFINERY),
            ALIEN_GENERATOR, new Watched("Miniature Alien Generator", 45_000, ItemSound.ALIEN),
            MATRIX, new Watched("Fitted Protective Matrix", 180_000, ItemSound.MATRIX),
            NEO_MATRIX, new Watched("Neo Fitted Protective Matrix", 180_000, ItemSound.MATRIX));
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "item-cooldowns");
        t.setDaemon(true);
        return t;
    });
    private static final Map<Integer, ScheduledFuture<?>> ready = new HashMap<>();

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
    private static long matrixFired;

    /**
     * Every game tick: wearing a Fitted Protective Matrix, the game putting Invulnerable on you right after a hit,
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
        if (!fresh || (type != MATRIX && type != NEO_MATRIX)) return;
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
        used(type, "it fired: Invulnerable" + (dazed ? " and Dazed" : "") + " at " + (max > 0 ? 100 * hp / max : 0) + "% life");
    }

    /** You used an item: for a watched one, its sound plays when it can be used again (a new use starts over). */
    static synchronized void used(int itemType, String how) {
        Watched w = WATCHED.get(itemType);
        if (w == null) return;
        ScheduledFuture<?> old = ready.get(itemType);
        if (old != null) old.cancel(false);
        ready.put(itemType, TIMER.schedule(() -> {
            AppLog.log("items", w.name() + " ready again");
            w.sound().play();
        }, w.cooldownMs(), TimeUnit.MILLISECONDS));
        AppLog.log("items", w.name() + " used (" + how + "), ready again in " + w.cooldownMs() / 1000 + " s");
    }
}
