package lootgobbler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import packets.incoming.VaultContentPacket;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HoldingsTest {
    /** Items from 1000 on are collectibles, below are potions and such; 900000 and up are not items at all. */
    private static final Holdings.Items ITEMS = new Holdings.Items() {
        public boolean collectible(int id) {
            return id >= 1000 && id < 900_000;
        }

        public String name(int id) {
            return id >= 900_000 ? "#" + id : "Item" + id;
        }
    };
    private static final int U = Holdings.Live.UNKNOWN;

    private Holdings h;
    private Map<Integer, Boolean> living;

    @BeforeEach
    void setUp() {
        h = new Holdings(null, false);
        h.items = ITEMS;
        living = new HashMap<>(Map.of(1, false, 2, false, 3, true));
        h.living = () -> living;
    }

    @SuppressWarnings("unchecked")
    private static Holdings.Live live(int[] items, List<Integer>... ench) {
        List<Integer>[] e = new List[items.length];
        for (int i = 0; i < ench.length && i < items.length; i++) e[i] = ench[i];
        return new Holdings.Live(items, e, null);
    }

    private static int[] slots(int... items) {
        int[] out = new int[12];
        java.util.Arrays.fill(out, -1);
        System.arraycopy(items, 0, out, 0, items.length);
        return out;
    }

    private static VaultContentPacket vault(int[] chest, int[] gift, boolean last) {
        VaultContentPacket p = new VaultContentPacket();
        p.vaultContents = chest;
        p.giftContents = gift;
        p.lastVaultPacket = last;
        return p;
    }

    @Test
    void theSharedStoreAsksTheItemDatabase() throws Exception {
        // the shared store is made while the class loads: the item database hook must exist by then
        java.lang.reflect.Field f = Holdings.class.getDeclaredField("INSTANCE");
        f.setAccessible(true);
        assertNotNull(((Holdings) f.get(null)).items);
    }

    // ---- characters ---------------------------------------------------------------------------------------------

    @Test
    void liveSlotsNotSentKeepWhatTheyHeld() {
        h.live(1, live(slots(1001, 1002, 5)));
        int[] next = slots(U, U, U, 1003);   // only slot 3 sent
        h.live(1, live(next));
        assertEquals(1, h.count(1001));
        assertEquals(1, h.count(1002));
        assertEquals(1, h.count(1003));
    }

    @Test
    void enchantmentsStayWhenTheSameItemComesWithoutThem() {
        h.live(1, live(slots(1001), List.of(7, 8)));
        h.live(1, live(slots(1001)));   // codes not sent this time
        assertEquals(List.of(7, 8), h.copies(1001).get(0).enchantIds());
        h.live(1, live(slots(1002)));   // another item in that slot: its enchantments are not known
        assertNull(h.copies(1002).get(0).enchantIds());
    }

    @Test
    void forgedFedOrDroppedIsAnEmptySlot() {
        h.live(1, live(slots(1001, 1001)));
        assertEquals(2, h.count(1001));
        h.live(1, live(slots(1001, -1)));
        assertEquals(1, h.count(1001));
        assertTrue(h.journal.lines.stream().anyMatch(l -> l.contains("Item1001 -> empty")));
    }

    @Test
    void movingBetweenSlotsKeepsTheCount() {
        h.live(1, live(slots(1001, -1)));
        h.live(1, live(slots(-1, 1001)));
        assertEquals(1, h.count(1001));
        assertEquals("Equipped", h.copies(1001).get(0).slot());
        h.live(1, live(slots(-1, -1, -1, -1, -1, 1001)));
        assertEquals("Inventory", h.copies(1001).get(0).slot());
    }

    @Test
    void characterListFillsCharactersNotSeenLive() {
        h.characters(List.of(new Holdings.Listed(1, slots(1001), null, null),
                new Holdings.Listed(2, slots(1001, 1002), null, null)));
        assertEquals(2, h.count(1001));
        assertEquals(1, h.count(1002));
    }

    @Test
    void characterListDoesNotOverwriteWhatWasJustSeenLive() {
        h.live(1, live(slots(1005)));
        h.characters(List.of(new Holdings.Listed(1, slots(1001), null, null),
                new Holdings.Listed(2, slots(1002), null, null)));
        assertEquals(1, h.count(1005));
        assertEquals(0, h.count(1001));
        assertEquals(1, h.count(1002));
    }

    @Test
    void charactersMissingFromTheListAreGone() {
        h.characters(List.of(new Holdings.Listed(1, slots(1001), null, null),
                new Holdings.Listed(2, slots(1002), null, null)));
        h.characters(List.of(new Holdings.Listed(1, slots(1001), null, null)));
        assertEquals(0, h.count(1002));
    }

    @Test
    void aListSharingNoCharacterRemovesNothing() {
        h.characters(List.of(new Holdings.Listed(1, slots(1001), null, null)));
        h.characters(List.of(new Holdings.Listed(7, slots(1002), null, null)));   // another account
        assertEquals(1, h.count(1001));
    }

    @Test
    void deadCharactersDoNotCount() {
        h.characters(List.of(new Holdings.Listed(1, slots(1001), null, null)));
        living.remove(1);
        h.live(2, live(slots(1009)));   // any change clears the cached counts
        assertEquals(0, h.count(1001));
        assertTrue(h.copies(1001).isEmpty());
    }

    @Test
    void onlyCollectiblesAreCounted() {
        h.live(1, live(slots(5, 6, 1001)));
        assertEquals(0, h.count(5));
        assertTrue(h.copies(5).isEmpty());
        assertEquals(1, h.count(1001));
    }

    // ---- vaults -------------------------------------------------------------------------------------------------

    @Test
    void aVaultInSeveralPacketsIsSavedWhole() {
        assertFalse(h.vaultPacket(vault(new int[]{1001, 1002}, new int[]{1003}, false)));
        assertEquals(0, h.count(1001));   // not before the last packet
        assertTrue(h.vaultPacket(vault(new int[]{}, new int[]{1004}, true)));
        assertTrue(h.vaultSeason(false, "test"));
        assertEquals(1, h.count(1001));
        assertEquals(1, h.count(1004));
        assertEquals(Holdings.GIFT_CHEST, h.copies(1004).get(0).slot());
        assertEquals(Holdings.VAULT, h.copies(1004).get(0).vault());
    }

    @Test
    void aVisitReplacesTheWholeVault() {
        h.vaultPacket(vault(new int[]{1001, 1002}, null, true));
        h.vaultSeason(false, "test");
        h.vaultPacket(vault(new int[]{1001, -1}, null, true));
        h.vaultSeason(false, "test");
        assertEquals(1, h.count(1001));
        assertEquals(0, h.count(1002));
    }

    @Test
    void aVisitWaitsUntilItsSeasonIsKnown() {
        h.vaultPacket(vault(new int[]{1001}, null, true));
        assertTrue(h.vaultWaiting());
        assertFalse(h.vaultSeason(null, "unknown"));
        assertEquals(0, h.count(1001));
        assertTrue(h.vaultSeason(true, "test"));
        assertEquals(Holdings.SEASONAL_VAULT, h.copies(1001).get(0).vault());
    }

    @Test
    void aVisitNotCompletedIsDroppedOnLeaving() {
        h.vaultPacket(vault(new int[]{1001}, null, false));
        h.entered(false);
        h.vaultPacket(vault(new int[]{1002}, null, true));
        h.vaultSeason(false, "test");
        assertEquals(0, h.count(1001));   // nothing from the visit left half read
        assertEquals(1, h.count(1002));
    }

    @Test
    void aPacketThatDidNotDecodeSpoilsTheVisit() {
        h.vaultPacket(vault(new int[]{1001, 1002}, null, true));
        h.vaultSeason(false, "test");
        VaultContentPacket bad = vault(new int[]{1001}, null, true);
        bad.partial = true;
        h.vaultPacket(bad);
        h.vaultSeason(false, "test");
        assertEquals(1, h.count(1002));   // the earlier vault stands
        h.vaultPacket(vault(new int[]{900_001, 900_002, 900_003}, null, true));   // garbage
        h.vaultSeason(false, "test");
        assertEquals(1, h.count(1002));
        assertTrue(h.journal.lines.stream().anyMatch(l -> l.contains("did not decode")));
    }

    @Test
    void aVisitThatIsReallyTheOtherVaultIsRefused() {
        int[] regular = new int[20], seasonal = new int[12];
        for (int i = 0; i < regular.length; i++) regular[i] = 2000 + i;
        for (int i = 0; i < seasonal.length; i++) seasonal[i] = 3000 + i;
        h.vaultPacket(vault(regular, null, true));
        h.vaultSeason(false, "test");
        h.vaultPacket(vault(seasonal, null, true));
        h.vaultSeason(true, "test");
        // the seasonal vault again, taken for the regular one
        h.vaultPacket(vault(seasonal, null, true));
        h.vaultSeason(false, "a wrong guess");
        assertEquals(1, h.count(2000));
        assertEquals(1, h.count(3000));
        assertTrue(h.journal.lines.stream().anyMatch(l -> l.contains("matches the seasonal vault")));
    }

    @Test
    void enchantmentsAreOnlyTakenWhenTheyLineUp() {
        VaultContentPacket p = vault(new int[]{1001, 1002}, null, true);
        p.vaultChestEnchants = ",";   // two entries, one per slot, both without enchantments
        h.vaultPacket(p);
        h.vaultSeason(false, "test");
        assertEquals(List.of(), h.copies(1001).get(0).enchantIds());
        VaultContentPacket q = vault(new int[]{1001, 1002}, null, true);
        q.vaultChestEnchants = "";   // one entry for two slots: not trusted
        h.vaultPacket(q);
        h.vaultSeason(false, "test");
        assertNull(h.copies(1001).get(0).enchantIds());
    }

    // ---- gaps and seeding ---------------------------------------------------------------------------------------

    @Test
    void gapsNameWhatWasNeverSeen() {
        h.live(1, live(slots(1001)));
        Holdings.Gaps g = h.gaps();
        assertEquals(2, g.characters());
        assertTrue(g.vault());
        assertTrue(g.seasonalVault());   // character 3 is seasonal
        h.vaultPacket(vault(new int[]{1001}, null, true));
        h.vaultSeason(false, "test");
        assertFalse(h.gaps().vault());
    }

    @Test
    void seedingOnlyHappensWhenNothingIsKnown() {
        h.seed(List.of(new Holdings.Listed(1, slots(1001), null, null)), Map.of(Holdings.VAULT, Map.of(1002, 2)));
        assertEquals(1, h.count(1001));
        assertEquals(2, h.count(1002));
        h.seed(List.of(new Holdings.Listed(2, slots(1003), null, null)), Map.of());
        assertEquals(0, h.count(1003));
    }
}
