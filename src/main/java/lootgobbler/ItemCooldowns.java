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
 * Quickslot items with a cooldown: when one can be used again (its Cooldown in the game data after each use), its
 * sound plays. The Coral Juice Refinery (15 s, ItemSound.REFINERY) and the Miniature Alien Generator (45 s,
 * ItemSound.ALIEN). A use is seen as the use packet you send for it, the classic one or the newer kind ItemUse reads
 * raw.
 */
public final class ItemCooldowns {
    public static final int REFINERY = 44495;   // Coral Juice Refinery (0xadcf, equip_design.xml)
    public static final int ALIEN_GENERATOR = 48164;   // Miniature Alien Generator (0xbc24, equip.xml)

    private record Watched(String name, long cooldownMs, ItemSound sound) {
    }

    private static final Map<Integer, Watched> WATCHED = Map.of(
            REFINERY, new Watched("Coral Juice Refinery", 15_000, ItemSound.REFINERY),
            ALIEN_GENERATOR, new Watched("Miniature Alien Generator", 45_000, ItemSound.ALIEN));
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
