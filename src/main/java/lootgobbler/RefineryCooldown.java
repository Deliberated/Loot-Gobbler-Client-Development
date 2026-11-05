package lootgobbler;

import packets.PacketType;
import packets.outgoing.UseItemPacket;
import packets.packetcapture.register.Register;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The Coral Juice Refinery (a quickslot item that heals and gives mana) can be used again 15 seconds after each use
 * (its Cooldown in the game data): when that time is up, its sound plays (ItemSound.REFINERY). A use is seen as the
 * use packet you send for it, the classic one or the newer kind ItemUse reads raw.
 */
public final class RefineryCooldown {
    public static final int REFINERY = 44495;   // Coral Juice Refinery (0xadcf, equip_design.xml)
    private static final long COOLDOWN_MS = 15_000;
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "refinery-cooldown");
        t.setDaemon(true);
        return t;
    });
    private static ScheduledFuture<?> ready;

    private RefineryCooldown() {
    }

    public static void install() {
        Register.INSTANCE.register(PacketType.USEITEM, p -> {
            UseItemPacket u = (UseItemPacket) p;
            if (u.slotObject != null) used(u.slotObject.objectType, "use packet, slot " + u.slotObject.slotId);
        });
    }

    /** You used an item: for the refinery, its sound plays when it can be used again (a new use starts over). */
    static synchronized void used(int itemType, String how) {
        if (itemType != REFINERY) return;
        if (ready != null) ready.cancel(false);
        ready = TIMER.schedule(() -> {
            AppLog.log("items", "Coral Juice Refinery ready again");
            ItemSound.REFINERY.play();
        }, COOLDOWN_MS, TimeUnit.MILLISECONDS);
        AppLog.log("items", "Coral Juice Refinery used (" + how + "), ready again in 15 s");
    }
}
