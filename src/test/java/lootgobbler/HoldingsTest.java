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
        h.entered(true);   // in the vault map, unless a test leaves it
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

    @Test
    void enchantmentsSentAloneApplyToTheItemThere() {
        h.live(1, live(slots(1001)));
        int[] none = slots(U);
        java.util.Arrays.fill(none, U);
        h.live(1, live(none, List.of(4, -1)));
        assertEquals(List.of(4, -1), h.copies(1001).get(0).enchantIds());
        assertEquals(1, h.count(1001));
    }

    @Test
    void theBestCopyYouHaveGivesTheRarity() {
        h.live(1, live(slots(1001, 1001, 1002), List.of(7), List.of(7, 8, -1, 9)));
        assertEquals(4, h.bestEnchants(1001));   // empty enchant slots count for rarity too
        assertEquals(-1, h.bestEnchants(1002));   // its enchantments are not known
        h.live(1, live(slots(1001, -1)));   // the four-slot copy is gone
        assertEquals(1, h.bestEnchants(1001));
    }

    // ---- what a tick carries ------------------------------------------------------------------------------------

    private static packets.data.StatData stat(packets.data.enums.StatType t, int value) {
        packets.data.StatData d = new packets.data.StatData();
        d.statTypeNum = t.get();
        d.statType = t;
        d.statValue = value;
        return d;
    }

    @Test
    void aTickOnlyCarriesTheSlotsItSent() {
        packets.data.StatData[] stats = {
                stat(packets.data.enums.StatType.INVENTORY_0_STAT, 1001),
                stat(packets.data.enums.StatType.byOrdinal(packets.data.enums.StatType.INVENTORY_0_STAT.get() + 5), -1),
                stat(packets.data.enums.StatType.BACKPACK_0_STAT, 1002),
                stat(packets.data.enums.StatType.MP_STAT, 120)};
        Holdings.Live l = LootEvents.liveSlots(stats, 20, false);
        assertEquals(1001, l.items()[0]);
        assertEquals(-1, l.items()[5]);
        assertEquals(1002, l.items()[12]);
        assertEquals(U, l.items()[1]);
        assertNull(l.quick());
        assertNull(l.enchantIds()[0]);
    }

    @Test
    void onEnteringAMapAnEmptyQuickSlotIsNotTaken() {
        packets.data.StatData[] stats = {stat(LootEvents.QUICKSLOTS[0], -1), stat(LootEvents.QUICKSLOTS[1], 1004)};
        Holdings.Live l = LootEvents.liveSlots(stats, 12, true);
        assertEquals(U, l.quick()[0]);
        assertEquals(1004, l.quick()[1]);
        assertEquals(-1, LootEvents.liveSlots(stats, 12, false).quick()[0]);   // in a tick it is
    }

    @Test
    void aTickWithoutSlotsCarriesNothing() {
        assertNull(LootEvents.liveSlots(new packets.data.StatData[]{stat(packets.data.enums.StatType.MP_STAT, 1)}, 12, false));
    }

    @Test
    void aTickCanCarryTheQuickSlotsAlone() {
        packets.data.StatData[] stats = {stat(LootEvents.QUICKSLOTS[1], 1004)};
        Holdings.Live l = LootEvents.liveSlots(stats, 12, false);
        assertEquals(U, l.quick()[0]);
        assertEquals(1004, l.quick()[1]);
        h.live(1, live(slots(1001)));
        h.live(1, l);
        assertEquals(1, h.count(1004));
        assertEquals(1, h.count(1001));
        assertEquals("Quick slot", h.copies(1004).get(0).slot());
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
        h.entered(true);   // back in the vault
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
    void aVaultPacketOutsideTheVaultIsNotYourVault() {
        h.vaultPacket(vault(new int[]{1001}, null, true));
        h.vaultSeason(false, "test");
        h.entered(false);   // the daily quest room
        h.vaultPacket(vault(new int[]{}, new int[]{}, true));
        h.vaultSeason(false, "test");
        assertEquals(1, h.count(1001));
    }

    @Test
    void aVisitWithoutVaultChestSlotsIsRefused() {
        h.vaultPacket(vault(new int[]{1001}, null, true));
        h.vaultSeason(false, "test");
        h.entered(true);
        h.vaultPacket(vault(new int[]{}, new int[]{1002}, true));
        h.vaultSeason(false, "test");
        assertEquals(1, h.count(1001));
        assertTrue(h.journal.lines.stream().anyMatch(l -> l.contains("no vault chest slots")));
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

    // ---- moves in the vault -------------------------------------------------------------------------------------

    private static final int ME = 50, CHEST = 60, GIFT = 61;

    /** In the regular vault: a vault chest of 16 slots (two pages), saved, with character 1 beside it. */
    private void inVault(int[] chest, int[] gift) {
        VaultContentPacket p = vault(chest, gift, true);
        p.vaultChestObjectId = CHEST;
        p.giftChestObjectId = GIFT;
        h.vaultPacket(p);
        h.vaultSeason(false, "test");
    }

    private static int[] chest16(int... firstSlots) {
        int[] c = new int[16];
        java.util.Arrays.fill(c, -1);
        System.arraycopy(firstSlots, 0, c, 0, firstSlots.length);
        return c;
    }

    /** A move and the game's answer: the two slots trade their items. */
    private void move(int fromObj, int fromSlot, int fromItem, int toObj, int toSlot, int toItem) {
        h.swap(ME, 1, fromObj, fromSlot, fromItem, toObj, toSlot, toItem);
        h.swapResult(true, 0, fromObj, fromSlot, toItem, toObj, toSlot, fromItem);
    }

    @Test
    void puttingAnItemIntoTheVaultMovesItThere() {
        h.live(1, live(slots(-1, -1, -1, -1, 1001), null, null, null, null, List.of(5, 6)));
        inVault(chest16(), null);
        move(ME, 4, 1001, CHEST, 11, -1);   // slot 11 counted from the start (the second page)
        h.live(1, live(slots(-1, -1, -1, -1, -1)));   // the tick after: the slot is empty
        assertEquals(1, h.count(1001));
        Holdings.Held there = h.copies(1001).get(0);
        assertEquals(Holdings.VAULT_CHEST, there.slot());
        assertEquals(List.of(5, 6), there.enchantIds());   // carried over from the character's slot
        assertEquals(1001, h.slots(Holdings.VAULT, Holdings.VAULT_CHEST).get(11).item);
    }

    @Test
    void takingAnItemOutOfTheVaultMovesItOut() {
        inVault(chest16(-1, -1, 1002), null);
        move(CHEST, 2, 1002, ME, 6, -1);
        h.live(1, live(slots(-1, -1, -1, -1, -1, -1, 1002)));
        assertEquals(1, h.count(1002));
        assertEquals("Inventory", h.copies(1002).get(0).slot());
    }

    @Test
    void movingWithinTheVaultKeepsTheEnchantments() {
        VaultContentPacket p = vault(chest16(1001), null, true);
        p.vaultChestObjectId = CHEST;
        p.vaultChestEnchants = ",,,,,,,,,,,,,,,";   // known: none
        h.vaultPacket(p);
        h.vaultSeason(false, "test");
        move(CHEST, 0, 1001, CHEST, 9, -1);
        assertEquals(-1, h.slots(Holdings.VAULT, Holdings.VAULT_CHEST).get(0).item);
        assertEquals(List.of(), h.slots(Holdings.VAULT, Holdings.VAULT_CHEST).get(9).enchantIds);
    }

    @Test
    void nothingChangesUntilTheGameAnswers() {
        inVault(chest16(1001), null);
        h.swap(ME, 1, CHEST, 0, 1001, ME, 4, -1);
        assertEquals(1001, h.slots(Holdings.VAULT, Holdings.VAULT_CHEST).get(0).item);
        h.swapResult(false, 0, CHEST, 0, 1001, ME, 4, -1);   // refused
        assertEquals(1001, h.slots(Holdings.VAULT, Holdings.VAULT_CHEST).get(0).item);
        assertTrue(h.journal.lines.stream().anyMatch(l -> l.contains("refused")));
    }

    @Test
    void theAnswerIsWhatCountsEvenIfTheStoredVaultDiffered() {
        inVault(chest16(1001), null);
        move(CHEST, 0, 1009, ME, 4, -1);   // the vault held 1001 there: the game says it is empty now
        assertEquals(-1, h.slots(Holdings.VAULT, Holdings.VAULT_CHEST).get(0).item);
    }

    @Test
    void theContainerShowsAChangeNoAnswerCovered() {
        inVault(chest16(1001), null);
        h.chestView(CHEST, Map.of(0, -1));   // one slot of the page shown changed
        assertEquals(0, h.count(1001));
    }

    @Test
    void aWholePageTellsWhichPageIsShown() {
        int[] c = chest16();
        for (int i = 8; i < 16; i++) c[i] = 2000 + i;
        inVault(c, null);
        Map<Integer, Integer> page1 = new HashMap<>();
        for (int i = 0; i < 8; i++) page1.put(i, c[8 + i]);
        h.chestView(CHEST, page1);   // you turned to page 1
        h.chestView(CHEST, Map.of(3, -1));   // slot 3 of that page emptied
        assertEquals(-1, h.slots(Holdings.VAULT, Holdings.VAULT_CHEST).get(11).item);
    }

    @Test
    void aWholePageMatchingNoneIsNotWritten() {
        inVault(chest16(1001), null);
        Map<Integer, Integer> strange = new HashMap<>();
        for (int i = 0; i < 8; i++) strange.put(i, 3000 + i);
        h.chestView(CHEST, strange);
        assertEquals(1, h.count(1001));
        assertEquals(0, h.count(3000));
    }

    @Test
    void movesAreOnlyFollowedInASavedVaultOfThisVisit() {
        h.live(1, live(slots(-1, -1, -1, -1, 1001)));
        VaultContentPacket p = vault(chest16(), null, true);
        p.vaultChestObjectId = CHEST;
        h.vaultPacket(p);   // the season is not known yet: not saved
        move(ME, 4, 1001, CHEST, 0, -1);
        assertTrue(h.journal.lines.stream().anyMatch(l -> l.contains("is not saved")));
        h.vaultSeason(false, "test");
        h.entered(false);   // left the vault
        move(ME, 4, 1001, CHEST, 0, -1);
        assertEquals(-1, h.slots(Holdings.VAULT, Holdings.VAULT_CHEST).get(0).item);
        assertFalse(h.isVaultObject(CHEST));
    }

    @Test
    void takingAGiftOutClosesTheGap() {
        inVault(chest16(), new int[]{1003, 1004, 1005, 1006});
        move(GIFT, 1, 1004, ME, 5, -1);
        List<Holdings.Slot> gifts = h.slots(Holdings.VAULT, Holdings.GIFT_CHEST);
        assertEquals(3, gifts.size());
        assertEquals(1005, gifts.get(1).item);   // moved up
        move(GIFT, 2, 1006, ME, 6, -1);   // the slot as the game counts it now
        assertEquals(List.of(1003, 1005), h.slots(Holdings.VAULT, Holdings.GIFT_CHEST).stream().map(x -> x.item).toList());
    }

    @Test
    void aGiftIsFoundNearItsSlotIfAGapWasMissed() {
        inVault(chest16(), new int[]{1003, 1004, 1005, 1006});
        move(GIFT, 2, 1006, ME, 5, -1);   // named slot 2, but it is in slot 3 here
        assertEquals(List.of(1003, 1004, 1005), h.slots(Holdings.VAULT, Holdings.GIFT_CHEST).stream().map(x -> x.item).toList());
    }

    // ---- the regular vault from the character list -------------------------------------------------------------

    private static final String LIST = """
            <Chars nextCharId="9" maxNumChars="20">
              <Char id="1"><Equipment>1001,-1</Equipment><Pet><Account><Name>Me</Name></Account></Pet><UniqueItemInfo><ItemData type="1001">x</ItemData></UniqueItemInfo></Char>
              <Account>
                <AccountId>1</AccountId>
                <Vault><Chest>2001#a1,-1,2002,-1,-1,-1,-1,-1</Chest><Chest>2003#a2,-1,-1,-1,-1,-1,-1,-1</Chest></Vault>
                <Gifts>2004#g1,2005</Gifts>
                <Potions>2591,-1</Potions>
                <MaterialStorage><Chest>5,-1,-1,-1,-1,-1,-1,-1</Chest></MaterialStorage>
                <UniqueItemInfo><ItemData id="a1" type="2001"></ItemData><ItemData id="a2" type="2003">AAIE_f_9__3__f8=</ItemData></UniqueItemInfo>
                <UniqueGiftItemInfo><ItemData id="g1"></ItemData></UniqueGiftItemInfo>
              </Account>
            </Chars>""";

    @Test
    void theCharacterListCarriesTheRegularVault() {
        Map<String, List<Holdings.Slot>> v = Holdings.vaultFromCharList(LIST);
        assertEquals(16, v.get(Holdings.VAULT_CHEST).size());
        assertEquals(2001, v.get(Holdings.VAULT_CHEST).get(0).item);
        assertEquals(2003, v.get(Holdings.VAULT_CHEST).get(8).item);
        assertEquals(List.of(), v.get(Holdings.VAULT_CHEST).get(0).enchantIds);   // known: none
        assertNull(v.get(Holdings.VAULT_CHEST).get(2).enchantIds);   // no instance: not known
        assertEquals(2, v.get(Holdings.GIFT_CHEST).size());
        assertEquals(1, v.get(Holdings.MATERIALS).size() / 8);
        assertNull(Holdings.vaultFromCharList("<Chars><Account><AccountId>1</AccountId></Account></Chars>"));
    }

    @Test
    void theListedVaultReplacesTheStoredOneButKeepsWhatItDoesNotCarry() {
        VaultContentPacket p = vault(new int[]{2009}, null, true);
        p.seasonalSpoilContent = new int[]{2010};
        h.vaultPacket(p);
        h.vaultSeason(false, "test");
        h.entered(false);   // left the vault: later, the list arrives in the quest room
        h.listedVault(Holdings.vaultFromCharList(LIST));
        assertEquals(0, h.count(2009));
        assertEquals(1, h.count(2001));
        assertEquals(1, h.count(2005));
        assertEquals(1, h.count(2010));   // the spoils are not in the list: kept
    }

    @Test
    void theListedVaultWaitsWhileYouAreInIt() {
        VaultContentPacket p = vault(new int[]{2009}, null, true);
        p.vaultChestObjectId = 60;
        h.vaultPacket(p);
        h.vaultSeason(false, "test");
        h.listedVault(Holdings.vaultFromCharList(LIST));
        assertEquals(1, h.count(2009));
        h.entered(false);
        h.listedVault(Holdings.vaultFromCharList(LIST));
        assertEquals(0, h.count(2009));
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
