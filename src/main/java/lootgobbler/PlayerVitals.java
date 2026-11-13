package lootgobbler;

import packets.data.StatData;
import packets.data.enums.StatType;
import tomato.backend.data.TomatoData;

/** Your character's mana as of the latest game tick (for the mana overlay). */
public final class PlayerVitals {
    private static volatile int mp, maxMp;
    private static volatile long at;

    private PlayerVitals() {
    }

    /** Every game tick. */
    public static void tick(TomatoData data) {
        if (data == null || data.player == null) return;
        StatData m = data.player.stat.get(StatType.MP_STAT), max = data.player.stat.get(StatType.MAX_MP_STAT);
        if (m == null || max == null) return;
        mp = m.statValue;
        maxMp = max.statValue;
        at = System.currentTimeMillis();
    }

    public static int mp() {
        return mp;
    }

    public static int maxMp() {
        return maxMp;
    }

    /** Whether the game reported it lately (you are logged in and playing). */
    public static boolean fresh() {
        return maxMp > 0 && System.currentTimeMillis() - at < 5000;
    }
}
