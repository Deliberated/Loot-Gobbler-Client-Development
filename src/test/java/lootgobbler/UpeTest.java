package lootgobbler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The UPE rules on made-up items: 100-199 tiered, 1000+ untiered (UT/ST), 500 a stat potion (defense).
 * The player object is 7; object 50 a loot bag, 60 the vault chest.
 */
class UpeTest {
    private static final int ME = 7, BAG = 50, CHEST = 60, CHAR = 42;
    private Upe upe;
    private final List<Upe.Trade> checked = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        upe = new Upe(false);
        upe.items = new Upe.Items() {
            public boolean untiered(int id) {
                return id >= 1000;
            }

            public String name(int id) {
                return "Item" + id;
            }

            public int potionStat(int id) {
                return id == 500 ? 3 : -1;
            }

            public boolean lootBoost(int id) {
                return id == 600;
            }
        };
        upe.reportSoon = () -> {
        };
        upe.checker = t -> checked.add(t);
    }

    private static int[] slots(int... items) {
        int[] out = new int[12];
        java.util.Arrays.fill(out, -1);
        System.arraycopy(items, 0, out, 0, items.length);
        return out;
    }

    private void see(int... items) {
        upe.seen(CHAR, 5, slots(items), null, null, false);
    }

    /** A new character with its starting gear: weapon 100, ability 101, armor 102, no ring. */
    private void created() {
        upe.seen(CHAR, 1, slots(100, 101, 102, -1), null, null, true);
    }

    /** Taking an item out of a container into slot `to` (the game's answer), then the sniffer seeing it. */
    private void take(int container, String section, boolean bag, int fromSlot, int to, int item, int[] after) {
        upe.moved(CHAR, ME, container, fromSlot, -1, ME, to, item, section, bag, null, false);
        see(after);
    }

    @Test
    void aCharacterSeenFromItsCreationIsAPotentialUpeAndKeepsItsStartingGear() {
        created();
        assertTrue(upe.run(CHAR).potential());
        assertEquals(List.of(CHAR), upe.potential());
        see(100, 101, 102, -1);
        assertNull(upe.run(CHAR).broken);
    }

    @Test
    void aCharacterNotSeenFromItsCreationIsNoPotentialUpe() {
        see(100, 101, 102, -1);
        assertFalse(upe.run(CHAR).potential());
        assertTrue(upe.potential().isEmpty());
    }

    @Test
    void anUntieredDropInTheLoadoutIsFine() {
        created();
        take(BAG, null, true, 0, 4, 1001, new int[]{100, 101, 102, -1, 1001});
        upe.moved(CHAR, ME, ME, 4, 100, ME, 0, 1001, null, false, null, false);   // equip it: the weapon goes to slot 4
        see(1001, 101, 102, -1, 100);
        assertNull(upe.run(CHAR).broken);
    }

    @Test
    void aTieredItemInTheLoadoutBreaksIt() {
        created();
        take(BAG, null, true, 0, 4, 150, new int[]{100, 101, 102, -1, 150});
        see(100, 101, 102, 150);   // a tiered ring, from a drop
        assertTrue(upe.run(CHAR).broken.contains("tiered"));
    }

    @Test
    void aVaultItemMayBeCarriedButNotEquipped() {
        created();
        upe.mark(CHAR, true);
        take(CHEST, Holdings.VAULT_CHEST, false, 3, 4, 1002, new int[]{100, 101, 102, -1, 1002});
        assertNull(upe.run(CHAR).broken, "in the inventory it is fine");
        see(100, 101, 102, 1002);   // moved into the ring slot
        assertTrue(upe.run(CHAR).broken.contains("vault"));
    }

    @Test
    void anItemTurningUpInTheVaultWithoutAMoveSeenCameFromIt() {
        created();
        upe.inVault(true);
        see(100, 101, 102, 1003);
        assertTrue(upe.run(CHAR).broken.contains("vault"));
    }

    @Test
    void drinkingAPotionFromTheVaultBreaksItADroppedOneDoesNot() {
        created();
        take(BAG, null, true, 0, 4, 500, new int[]{100, 101, 102, -1, 500});
        upe.used(CHAR, 4, 500, null);
        assertNull(upe.run(CHAR).broken);
        see(100, 101, 102, -1);
        take(CHEST, Holdings.VAULT_CHEST, false, 0, 5, 500, new int[]{100, 101, 102, -1, -1, 500});
        upe.used(CHAR, 5, 500, null);
        assertTrue(upe.run(CHAR).broken.contains("Drank"));
    }

    @Test
    void drinkingStraightFromThePotionStorageBreaksIt() {
        created();
        upe.used(CHAR, 2, 500, "Potions");
        assertTrue(upe.run(CHAR).broken.contains("potions"));
    }

    @Test
    void aTradeBreaksIt() {
        created();
        upe.traded(CHAR, "Me");
        assertEquals("Traded with another player", upe.run(CHAR).broken);
    }

    @Test
    void forgingFromDropsIsCleanFromVaultItemsNot() {
        created();
        take(BAG, null, true, 0, 4, 1004, new int[]{100, 101, 102, -1, 1004});
        upe.forgeRequest(CHAR, new int[]{4});
        upe.forgeResult(CHAR, true, null, null);
        see(100, 101, 102, -1, -1, 1005);   // the forged item turns up
        see(100, 101, 102, 1005);
        assertNull(upe.run(CHAR).broken);

        take(CHEST, Holdings.VAULT_CHEST, false, 0, 4, 1006, new int[]{100, 101, 102, 1005, 1006});
        upe.forgeRequest(CHAR, new int[]{4});
        upe.forgeResult(CHAR, true, new int[]{5}, new int[]{1007});
        see(100, 101, 102, 1005, -1, 1007);
        see(100, 101, 102, 1007, -1, 1005);
        assertTrue(upe.run(CHAR).broken.contains("forged"));
    }

    @Test
    void anItemOfUnknownOriginKeepsTheBenefitOfTheDoubt() {
        created();
        upe.mark(CHAR, true);
        see(100, 101, 102, 1008);   // turned up while playing, no move seen
        assertNull(upe.run(CHAR).broken);
    }

    @Test
    void anEnchantedItemThatLeftTheVaultUnseenIsProof() throws Exception {
        created();
        upe.mark(CHAR, true);
        Upe.Run r = upe.run(CHAR);
        // played without the sniffer: the vault lost an enchanted ring, the character wears one with the same enchantments
        upe.vaultChanged(Holdings.VAULT, r.lastSeen - 1, List.of(new Upe.Held(1009, List.of(3, 7))), r.lastSeen + 1, List.of());
        int[][] ench = new int[12][];
        ench[3] = new int[]{7, 3};
        Thread.sleep(2);
        upe.seen(CHAR, 5, slots(100, 101, 102, 1009), ench, null, false);
        assertNull(upe.run(CHAR).broken, "seen while watched: no gap, so not from the vault unseen");

        Upe other = new Upe(false);
        other.items = upe.items;
        other.seen(CHAR, 1, slots(100, 101, 102, -1), null, null, true);
        other.mark(CHAR, true);
        Upe.Run run = other.run(CHAR);
        other.vaultChanged(Holdings.VAULT, run.lastSeen + 1000, List.of(new Upe.Held(1009, List.of(3, 7))), run.lastSeen + 2000, List.of());
        // seen again much later (a gap): lastSeen is rewound so the next look counts as after a gap
        java.lang.reflect.Field f = Upe.class.getDeclaredField("state");
        f.setAccessible(true);
        Object state = f.get(other);
        java.lang.reflect.Field runs = state.getClass().getDeclaredField("runs");
        runs.setAccessible(true);
        @SuppressWarnings("unchecked") java.util.Map<Integer, Upe.Run> map = (java.util.Map<Integer, Upe.Run>) runs.get(state);
        map.get(CHAR).lastSeen = run.lastSeen - 10 * 60_000;
        other.vaultChanged(Holdings.VAULT, run.lastSeen - 9 * 60_000, List.of(new Upe.Held(1009, List.of(3, 7))), run.lastSeen - 60_000, List.of());
        other.seen(CHAR, 5, slots(100, 101, 102, 1009), ench, null, false);
        assertNotNull(other.run(CHAR).broken);
        assertTrue(other.run(CHAR).broken.contains("left the vault"));
    }

    @Test
    void aBreakSyncsAndStays() {
        created();
        upe.traded(CHAR, "Me");
        Upe other = new Upe(false);
        other.items = upe.items;
        other.seen(CHAR, 1, slots(100, 101, 102, -1), null, null, true);
        assertTrue(other.mergeSync(upe.exportSync()));
        assertNotNull(other.run(CHAR).broken);
        upe.mergeSync(other.exportSync());   // its newer look at the slots may come back, the break stays
        assertEquals("Traded with another player", upe.run(CHAR).broken);
        assertFalse(upe.mergeSync(other.exportSync()), "nothing new the second time");
    }

    @Test
    void onlyAPotentialUpeCanBeMarked() {
        see(100, 101, 102, -1);   // not seen from its creation
        upe.mark(CHAR, true);
        assertFalse(upe.run(CHAR).marked);
        upe.mark(99, true);       // never seen at all
        assertNull(upe.run(99));
    }

    @Test
    void aBoughtOrRewardedItemMayNotBeEquipped() {
        created();
        upe.mark(CHAR, true);
        upe.rewarded("shop purchase");
        see(100, 101, 102, -1, 1010);
        assertNull(upe.run(CHAR).broken, "carrying it is fine");
        see(100, 101, 102, 1010);
        assertTrue(upe.run(CHAR).broken.contains("reward"));
    }

    @Test
    void aQuestSolvedEntirelyInTheRunGivesACleanReward() {
        created();
        take(BAG, null, true, 0, 4, 1011, new int[]{100, 101, 102, -1, 1011});
        upe.questHandIn(CHAR, 5, new int[]{4}, new int[]{1012}, false);
        upe.questAnswer(true);
        see(100, 101, 102, -1, 1012);   // the reward turns up where the hand-in was
        see(100, 101, 102, 1012);
        assertNull(upe.run(CHAR).broken);
    }

    @Test
    void aQuestPartlySolvedByAnotherCharacterGivesNoCleanReward() {
        created();
        upe.questHandIn(77, 6, new int[]{4}, new int[]{1014}, false);   // another character handed in part of it
        upe.questAnswer(true);
        take(BAG, null, true, 0, 4, 1013, new int[]{100, 101, 102, -1, 1013});
        upe.questHandIn(CHAR, 6, new int[]{4}, new int[]{1014}, false);
        upe.questAnswer(true);
        see(100, 101, 102, -1, 1014);
        see(100, 101, 102, 1014);
        assertTrue(upe.run(CHAR).broken.contains("reward"));
    }

    @Test
    void aCleanQuestRewardMayBeTakenFromTheGiftChestOnce() {
        created();
        upe.mark(CHAR, true);
        take(BAG, null, true, 0, 4, 1015, new int[]{100, 101, 102, -1, 1015});
        upe.questHandIn(CHAR, 8, new int[]{4}, new int[]{1016, 1017}, true);   // a reward of choice
        upe.questAnswer(true);
        see(100, 101, 102, -1);   // handed in; the reward went to the gift chest
        assertEquals(1, upe.run(CHAR).allowed.size());
        take(CHEST, Holdings.GIFT_CHEST, false, 0, 4, 1017, new int[]{100, 101, 102, -1, 1017});
        see(100, 101, 102, 1017);
        assertNull(upe.run(CHAR).broken, "its own clean reward");
        assertTrue(upe.run(CHAR).allowed.isEmpty(), "used up, the other choice too");
        take(CHEST, Holdings.GIFT_CHEST, false, 1, 4, 1016, new int[]{100, 101, 102, 1017, 1016});
        see(100, 101, 102, 1016, 1017);
        assertTrue(upe.run(CHAR).broken.contains("vault"), "a second one is a vault item");
    }

    @Test
    void aMissionBegunAtZeroAndSolvedByTheCharacterGivesACleanReward() {
        created();
        upe.mark(CHAR, true);
        upe.missionStep("3:12", CHAR, true);
        upe.missionStep("3:12", CHAR, false);
        upe.missionClaimed("3:12", CHAR, List.of(new int[]{1020}));
        see(100, 101, 102, -1);   // the reward went to the gift chest
        take(CHEST, Holdings.GIFT_CHEST, false, 0, 4, 1020, new int[]{100, 101, 102, -1, 1020});
        see(100, 101, 102, 1020);
        assertNull(upe.run(CHAR).broken);
    }

    @Test
    void aMissionWithProgressFromBeforeOrFromAnotherCharacterGivesNoCleanReward() {
        created();
        upe.mark(CHAR, true);
        upe.missionStep("3:13", CHAR, false);   // it had progress already
        upe.missionClaimed("3:13", CHAR, List.of(new int[]{1021}));
        see(100, 101, 102, -1, 1021);
        see(100, 101, 102, 1021);
        assertTrue(upe.run(CHAR).broken.contains("reward"));

        Upe other = new Upe(false);
        other.items = upe.items;
        other.seen(CHAR, 1, slots(100, 101, 102, -1), null, null, true);
        other.mark(CHAR, true);
        other.missionStep("3:14", CHAR, true);
        other.missionStep("3:14", 77, false);   // another character made some of it
        other.missionClaimed("3:14", CHAR, List.of(new int[]{1022}));
        other.moved(CHAR, ME, CHEST, 0, -1, ME, 4, 1022, Holdings.GIFT_CHEST, false, null, false);
        other.seen(CHAR, 5, slots(100, 101, 102, -1, 1022), null, null, false);
        other.seen(CHAR, 5, slots(100, 101, 102, 1022), null, null, false);
        assertTrue(other.run(CHAR).broken.contains("vault"));
    }

    @Test
    void theRunKeepsWhatItGatheredItselfAndNothingElse() {
        created();
        upe.mark(CHAR, true);
        upe.where("Snake Pit");
        take(BAG, null, true, 0, 4, 1030, new int[]{100, 101, 102, -1, 1030});           // a drop
        take(CHEST, Holdings.VAULT_CHEST, false, 0, 5, 1031, new int[]{100, 101, 102, -1, 1030, 1031});   // the vault's
        upe.moved(CHAR, ME, ME, 4, -1, ME, 6, 1030, null, false, null, false);           // moved in the inventory
        see(100, 101, 102, -1, -1, 1031, 1030);
        List<Upe.Gathered> got = upe.run(CHAR).gathered;
        assertEquals(1, got.size(), "the drop once, not the vault item nor the move");
        assertEquals(1030, got.get(0).item);
        assertEquals("Snake Pit", got.get(0).where);
        assertEquals(Upe.DROP, got.get(0).how);
    }

    /** A gUPE run that just traded away its dropped item 1040 (slot 4) for the partner's 1041. */
    private Upe.Trade guildTrade() {
        created();
        upe.mark(CHAR, true, Upe.GUPE);
        take(BAG, null, true, 0, 4, 1040, new int[]{100, 101, 102, -1, 1040});
        int[] mine = slots(100, 101, 102, -1, 1040);
        int[] theirs = slots(200, 201, 202, -1, 1041);
        upe.tradeStart("Friend", mine, null, theirs, null);
        boolean[] myOffer = new boolean[12], theirOffer = new boolean[12];
        myOffer[4] = true;
        theirOffer[4] = true;
        upe.tradeOffers(myOffer, theirOffer);
        upe.traded(CHAR, "Me");
        see(100, 101, 102, -1, 1041);   // the trade's item turns up
        return upe.trades(CHAR).get(0);
    }

    @Test
    void aUpeMayNotTradeAtAll() {
        created();
        upe.mark(CHAR, true);
        upe.tradeStart("Friend", slots(), null, slots(), null);
        upe.traded(CHAR, "Me");
        assertTrue(upe.run(CHAR).broken.contains("Traded with Friend"));
    }

    @Test
    void aGupeTradeIsReportedAndItsItemsWaitForThePartnersReport() {
        Upe.Trade t = guildTrade();
        assertEquals(1, checked.size(), "the check started");
        assertEquals("Friend", t.partner);
        assertEquals(1040, t.gave.get(0).item);
        assertTrue(t.gave.get(0).clean, "a drop of the run");
        assertEquals(1041, t.got.get(0).item);
        assertEquals(Upe.CHECKING, upe.run(CHAR).origin[4]);
        see(100, 101, 102, 1041);   // equipped while still checked: no verdict yet
        assertNull(upe.run(CHAR).broken);
    }

    @Test
    void aConfirmedTradeMakesItsItemsGuildCleanAndLocksTheMode() {
        Upe.Trade t = guildTrade();
        upe.tradeChecked(CHAR, t.at, true, null);
        assertEquals(Upe.GUILD, upe.run(CHAR).origin[4]);
        assertTrue(upe.run(CHAR).gathered.stream().anyMatch(g -> g.item == 1041), "counts as gathered");
        assertTrue(upe.setMode(CHAR, Upe.UPE), "not used yet: may go back");
        assertTrue(upe.setMode(CHAR, Upe.GUPE));
        see(100, 101, 102, 1041);
        assertNull(upe.run(CHAR).broken);
        assertTrue(upe.run(CHAR).guildLocked);
        assertFalse(upe.setMode(CHAR, Upe.UPE), "equipped a guild mate's item: stays a gUPE");
    }

    @Test
    void aGuildItemBreaksARunThatSwitchedBackToUpe() {
        Upe.Trade t = guildTrade();
        upe.tradeChecked(CHAR, t.at, true, null);
        assertTrue(upe.setMode(CHAR, Upe.UPE));
        see(100, 101, 102, 1041);
        assertTrue(upe.run(CHAR).broken.contains("only a guild mode"));
    }

    @Test
    void anIllegalTradeLosesTheRun() {
        Upe.Trade t = guildTrade();
        upe.tradeChecked(CHAR, t.at, false, "Friend is not in your Loot Gobbler guild");
        assertTrue(upe.run(CHAR).broken.contains("not in your Loot Gobbler guild"));
        assertEquals(Upe.TRADE, upe.run(CHAR).origin[4]);
    }

    @Test
    void theVerdictComesFromThePartnersReport() {
        Upe.Trade mine = new Upe.Trade();
        mine.at = 1_000_000;
        mine.me = "Me";
        mine.partner = "Friend";
        Upe.TradeItem got = new Upe.TradeItem();
        got.item = 1041;
        mine.got.add(got);

        Upe.Trade theirs = new Upe.Trade();
        theirs.at = mine.at + 3000;
        theirs.me = "Friend";
        theirs.partner = "Me";
        theirs.mode = Upe.GUPE;
        theirs.standing = true;
        Upe.TradeItem gave = new Upe.TradeItem();
        gave.item = 1041;
        gave.clean = true;
        theirs.gave.add(gave);
        GobblerServer.GuildUpeMember friend = new GobblerServer.GuildUpeMember();
        friend.ign = "friend";
        friend.trades = List.of(theirs);

        assertTrue(GuildUpe.verdict(mine, List.of(friend)).ok());
        assertFalse(GuildUpe.verdict(mine, List.of()).ok(), "not in the guild");
        GobblerServer.GuildUpeMember silent = new GobblerServer.GuildUpeMember();
        silent.ign = "Friend";
        assertNull(GuildUpe.verdict(mine, List.of(silent)), "no report yet: wait");
        gave.clean = false;
        assertFalse(GuildUpe.verdict(mine, List.of(friend)).ok(), "gave something not gathered in their run");
        gave.clean = true;
        theirs.standing = false;
        assertFalse(GuildUpe.verdict(mine, List.of(friend)).ok(), "their run was lost");
    }

    @Test
    void aLootBoostMustBeGatheredInTheRun() {
        created();
        upe.mark(CHAR, true);
        take(BAG, null, true, 0, 4, 600, new int[]{100, 101, 102, -1, 600});
        upe.used(CHAR, 4, 600, null);
        assertNull(upe.run(CHAR).broken, "a dropped one");
        see(100, 101, 102, -1);
        take(CHEST, Holdings.VAULT_CHEST, false, 0, 5, 600, new int[]{100, 101, 102, -1, -1, 600});
        upe.used(CHAR, 5, 600, null);
        assertTrue(upe.run(CHAR).broken.contains("Used a"));
    }

    @Test
    void otherItemsLikeOryxRunesAreFreeToUse() {
        created();
        upe.mark(CHAR, true);
        take(CHEST, Holdings.VAULT_CHEST, false, 0, 4, 700, new int[]{100, 101, 102, -1, 700});
        upe.used(CHAR, 4, 700, null);
        assertNull(upe.run(CHAR).broken);
    }

    /** An item picked up from a loot bag into this inventory slot, the rest as it was. */
    private void loot(int slot, int item) {
        int[] now = upe.run(CHAR).items.clone();
        now[slot] = item;
        take(BAG, null, true, 0, slot, item, now);
    }

    // ---- the game modes ---------------------------------------------------------------------------------------------

    /** Items: 100-102 starter gear of the given tier, 1000+ untiered; 1100+ ST, 1200+ shiny. */
    private void tiers(int starterTier) {
        Upe.Items base = upe.items;
        upe.items = new Upe.Items() {
            public boolean untiered(int id) {
                return base.untiered(id);
            }

            public String name(int id) {
                return base.name(id);
            }

            public int potionStat(int id) {
                return base.potionStat(id);
            }

            public boolean st(int id) {
                return id >= 1100 && id < 1200;
            }

            public boolean shiny(int id) {
                return id >= 1200;
            }

            public int tier(int id) {
                return id >= 100 && id <= 102 ? starterTier : id < 1000 ? 5 : -1;
            }
        };
    }

    @Test
    void upgradedStartingGearMakesACharacterIneligible() {
        tiers(3);
        created();
        assertFalse(upe.run(CHAR).eligible());
        upe.mark(CHAR, true, Upe.PPE);
        assertFalse(upe.run(CHAR).marked);
    }

    @Test
    void t0StartingGearCanStartAnyMode() {
        tiers(0);
        created();
        assertTrue(upe.run(CHAR).eligible());
        upe.mark(CHAR, true, "gSTPE");
        assertEquals("gSTPE", upe.run(CHAR).mode);
        see(100, 101, 102, -1);   // the starting gear never counts against it
        assertNull(upe.run(CHAR).broken);
    }

    @Test
    void aPpeMayEquipTieredDrops() {
        tiers(0);
        created();
        upe.mark(CHAR, true, Upe.PPE);
        loot(4, 150);
        see(150, 101, 102, -1, -1);
        assertNull(upe.run(CHAR).broken);
    }

    @Test
    void aStpeOnlyTakesSetItems() {
        tiers(0);
        created();
        upe.mark(CHAR, true, Upe.STPE);
        loot(4, 1100);
        see(1100, 101, 102, -1, -1);
        assertNull(upe.run(CHAR).broken, "an ST drop");
        loot(4, 1001);
        see(1100, 1001, 102, -1, -1);
        assertTrue(upe.run(CHAR).broken.contains("not a set item"));
    }

    @Test
    void aPureUpeTakesUtButNoSetItems() {
        tiers(0);
        created();
        upe.mark(CHAR, true, Upe.PUPE);
        loot(4, 1001);
        see(1001, 101, 102, -1, -1);
        assertNull(upe.run(CHAR).broken, "a UT drop");
        loot(4, 1100);
        see(1001, 1100, 102, -1, -1);
        assertTrue(upe.run(CHAR).broken.contains("set item"));
    }

    @Test
    void aShinyUpeOnlyTakesShinyItems() {
        tiers(0);
        created();
        upe.mark(CHAR, true, Upe.SHINY_UPE);
        loot(4, 1001);
        see(1001, 101, 102, -1, -1);
        assertTrue(upe.run(CHAR).broken.contains("not a shiny item"));
    }

    @Test
    void aDivinePeOnlyTakesFourSlotItemsOfAnyTier() {
        tiers(0);
        created();
        upe.mark(CHAR, true, Upe.DIVINE_PE);
        loot(4, 150);   // tiered: fine, if divine
        int[][] ench = new int[12][];
        ench[0] = new int[]{1, 2, 3, 4};
        upe.seen(CHAR, 5, slots(150, 101, 102, -1, -1), ench, null, false);
        assertNull(upe.run(CHAR).broken, "divine");
        ench[0] = new int[]{1, 2};
        upe.seen(CHAR, 5, slots(150, 101, 102, -1, -1), ench, null, false);
        assertTrue(upe.run(CHAR).broken.contains("not a divine item"));
    }

    @Test
    void aRunWithoutAPetNeedsNoPetAndLosesItWithOne() {
        tiers(0);
        created();
        upe.pets(java.util.Map.of(CHAR, true));
        upe.mark(CHAR, true, Upe.UPE, true);
        assertFalse(upe.run(CHAR).marked, "a pet: cannot start without one");
        upe.pets(java.util.Map.of(CHAR, false));
        upe.mark(CHAR, true, Upe.UPE, true);
        assertTrue(upe.run(CHAR).marked);
        assertTrue(upe.run(CHAR).noPet);
        upe.pets(java.util.Map.of(CHAR, true));
        assertTrue(upe.run(CHAR).broken.contains("pet"));
    }

    @Test
    void aPetIsFineForARunThatAllowsOne() {
        tiers(0);
        created();
        upe.mark(CHAR, true, Upe.PPE, false);
        upe.pets(java.util.Map.of(CHAR, true));
        assertNull(upe.run(CHAR).broken);
    }

    @Test
    void noPetCanBeGivenUpButNotTakenUpMidRun() {
        tiers(0);
        created();
        upe.mark(CHAR, true, Upe.PPE, false);
        assertFalse(upe.setNoPet(CHAR, true), "started with a pet allowed");
        Upe other = new Upe(false);
        other.items = upe.items;
        other.seen(CHAR, 1, slots(100, 101, 102, -1), null, null, true);
        other.mark(CHAR, true, Upe.PPE, true);
        assertTrue(other.setNoPet(CHAR, false));
        assertFalse(other.run(CHAR).noPet);
    }

    @Test
    void aStartedRunOnlyTogglesItsGuildVersion() {
        tiers(0);
        created();
        upe.mark(CHAR, true, Upe.UPE);
        assertFalse(upe.setMode(CHAR, Upe.PPE), "the mode is set at the start");
        assertTrue(upe.setMode(CHAR, Upe.GUPE));
    }

    @Test
    void modeLabels() {
        assertEquals("gShiny UPE", Upe.label("gShinyUPE"));
        assertEquals(Upe.UPE, Upe.category("gDivinePE"));
        assertEquals(Upe.PPE, Upe.category("gPPE"));
    }
}
