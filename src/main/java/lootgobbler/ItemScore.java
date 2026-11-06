package lootgobbler;

import lootgobbler.GobblerServer.ScoreWeights;
import lootgobbler.GobblerServer.Tally;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * How many points a found item is worth, rounded to one decimal:
 * <pre>
 *   feed power weight * feed power / 100  +  difficulty weight * dungeon difficulty  +  rarity weight * rarity
 * </pre>
 * The rarity is the item's enchantment slots (0 to 4); the difficulty the game's rating of the dungeon it dropped in
 * (0 if unknown, e.g. the realm). The weights (0 to 1 each) are the server's, set by an admin; all 1 until loaded.
 */
public final class ItemScore {
    private static final ItemScore INSTANCE = new ItemScore();

    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile ScoreWeights weights = new ScoreWeights();
    private volatile boolean loading;

    public static ItemScore get() {
        return INSTANCE;
    }

    private ItemScore() {
    }

    public ScoreWeights weights() {
        return weights;
    }

    /** Called (on any thread) when the weights changed. */
    public void onChange(Runnable listener) {
        listeners.add(listener);
    }

    /** Asks the server for the weights (the Guild tab does on every refresh); listeners hear of a change. */
    public void reload() {
        GobblerServer server = GobblerServer.get();
        if (loading || server.user() == null || server.state() != GobblerServer.State.ONLINE) return;
        loading = true;
        server.scoring().whenComplete((w, e) -> {
            loading = false;
            if (w != null) set(w);
        });
    }

    /** Takes new weights (from the server, or after an admin saved them). */
    public void set(ScoreWeights w) {
        ScoreWeights old = weights;
        weights = w;
        if (old.feedPower != w.feedPower || old.difficulty != w.difficulty || old.rarity != w.rarity) {
            for (Runnable r : listeners) r.run();
        }
    }

    /** The item's points when found in that place with that many enchantments (-1 = unknown, counts as 0). */
    public double of(int itemId, String where, int enchants) {
        ScoreWeights w = weights;
        ItemDb.Item item = ItemDb.get().item(itemId);
        int feed = item == null ? 0 : Math.max(0, item.feedPower);
        Double difficulty = DungeonDifficulty.of(where);
        int rarity = Math.max(0, Math.min(4, enchants));
        double score = w.feedPower * (feed / 100.0) + w.difficulty * (difficulty == null ? 0 : difficulty) + w.rarity * rarity;
        return Math.round(score * 10) / 10.0;
    }

    /** The points of everything in a tally (each item rounded first, so the total matches the items shown). */
    public double total(List<Tally> tally) {
        double sum = 0;
        for (Tally t : tally) sum += t.count * of(t.itemId, t.where, t.enchants);
        return Math.round(sum * 10) / 10.0;
    }

    /**
     * What an item's points are made of, for a tooltip: its feed power, the dungeon's difficulty and its rarity, the
     * names on the left and the values on the right.
     */
    public static String breakdown(int itemId, String where, int enchants) {
        ItemDb.Item item = ItemDb.get().item(itemId);
        Double difficulty = DungeonDifficulty.of(where);
        int rarity = Math.max(0, Math.min(4, enchants));
        String rarityText = enchants < 0 ? "Unknown (0)" : lootgobbler.ui.EnchantBadge.RARITY[rarity] + " (" + rarity + ")";
        return "<html><table cellpadding=0 cellspacing=0>"
                + row("FP", item == null || item.feedPower < 0 ? "0" : String.format(Locale.ENGLISH, "%,d", item.feedPower))
                + row("Difficulty", difficulty == null ? "Unknown (0)" : format(difficulty))
                + row("Rarity", rarityText)
                + "</table></html>";
    }

    private static String row(String key, String value) {
        return "<tr><td align=left>" + key + ":</td><td align=right style='padding-left:16px'>" + value + "</td></tr>";
    }

    /** "12.5", or "24" for a whole number (thousands grouped). */
    public static String format(double points) {
        double tenths = Math.round(points * 10) / 10.0;
        return tenths == Math.rint(tenths) ? String.format(Locale.ENGLISH, "%,d", (long) tenths)
                : String.format(Locale.ENGLISH, "%,.1f", tenths);
    }

    /** "835" (rounded to whole points, thousands grouped): for totals. */
    public static String whole(double points) {
        return String.format(Locale.ENGLISH, "%,d", Math.round(points));
    }
}
