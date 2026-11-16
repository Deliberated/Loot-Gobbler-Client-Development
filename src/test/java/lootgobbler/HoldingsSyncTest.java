package lootgobbler;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Inventory and vault sync between two PCs: place by place, the later look wins, not the later sync.
 */
class HoldingsSyncTest {
    private static final Holdings.Items ITEMS = new Holdings.Items() {
        public boolean collectible(int id) {
            return id >= 1000;
        }

        public String name(int id) {
            return "Item" + id;
        }
    };

    private Holdings mainPc, laptop;

    private static Holdings pc() {
        Holdings h = new Holdings(null, false);
        h.items = ITEMS;
        Map<Integer, Boolean> living = new HashMap<>(Map.of(1, false, 2, false));
        h.living = () -> living;
        return h;
    }

    @BeforeEach
    void setUp() {
        mainPc = pc();
        laptop = pc();
    }

    private static int[] slots(int... items) {
        int[] out = new int[12];
        java.util.Arrays.fill(out, -1);
        System.arraycopy(items, 0, out, 0, items.length);
        return out;
    }

    private static Holdings.Listed listed(int charId, int... items) {
        return new Holdings.Listed(charId, slots(items), null, null);
    }

    /** The export with every place's look moved by delta ms (to make one side older). */
    private static JsonElement shifted(Holdings h, long delta) {
        JsonObject o = h.exportSync().deepCopy().getAsJsonObject();
        for (Map.Entry<String, JsonElement> p : o.getAsJsonObject("places").entrySet()) {
            JsonObject place = p.getValue().getAsJsonObject();
            place.addProperty("seenAt", place.get("seenAt").getAsLong() + delta);
        }
        return o;
    }

    @Test
    void placesOnlyTheOtherPcSawAreAdded() {
        mainPc.characters(List.of(listed(1, 1001, 1002), listed(2, 1003)));
        assertTrue(laptop.mergeSync(mainPc.exportSync()));
        assertEquals(1, laptop.count(1001));
        assertEquals(1, laptop.count(1003));
        assertFalse(laptop.mergeSync(mainPc.exportSync()), "nothing new the second time");
    }

    @Test
    void anOlderLookSyncedLaterDoesNotReplaceANewerOne() {
        laptop.characters(List.of(listed(1, 2000)));           // the laptop looked just now
        mainPc.characters(List.of(listed(1, 1001)));
        assertFalse(laptop.mergeSync(shifted(mainPc, -86_400_000L)), "the main PC's look is a day older");
        assertEquals(1, laptop.count(2000));
        assertEquals(0, laptop.count(1001));
    }

    @Test
    void aNewerLookReplacesAnOlderOne() {
        laptop.characters(List.of(listed(1, 2000)));
        mainPc.characters(List.of(listed(1, 1001)));
        assertTrue(laptop.mergeSync(shifted(mainPc, 60_000L)));
        assertEquals(0, laptop.count(2000));
        assertEquals(1, laptop.count(1001));
    }

    @Test
    void aCharacterGoneOnOnePcGoesOnTheOtherAndDoesNotComeBack() throws Exception {
        mainPc.characters(List.of(listed(1, 1001), listed(2, 1002)));
        laptop.mergeSync(mainPc.exportSync());
        Thread.sleep(5);
        mainPc.characters(List.of(listed(1, 1001)));            // character 2 died: it left the list
        assertEquals(0, mainPc.count(1002));

        assertTrue(laptop.mergeSync(mainPc.exportSync()));
        assertEquals(0, laptop.count(1002), "removed on the laptop too");
        assertFalse(mainPc.mergeSync(shifted(laptop, -1)), "an older look doesn't bring it back");
        assertEquals(0, mainPc.count(1002));
    }
}
