package lootgobbler;

import packets.PacketType;
import packets.data.SlotObjectData;
import packets.data.enums.PetUpgradeType;
import packets.incoming.ForgeResultPacket;
import packets.outgoing.ForgeRequestPacket;
import packets.outgoing.pets.PetUpgradeRequestPacket;
import packets.packetcapture.register.Register;

import java.util.ArrayList;
import java.util.List;

/**
 * Items that leave you, taken off the collection (see CollectionStore.recordRemoved):
 * <ul>
 * <li>Forging: the forge request lists the items put in; they count as gone once the forge reports success.</li>
 * <li>Feeding a pet: the feed request lists the items fed (there is no answer to wait for).</li>
 * <li>Dropping an item on the ground. Picking it up again counts it again (the inventory scan sees one copy more
 * than you hold).</li>
 * </ul>
 */
public final class ItemUse {
    /** A collectible item put into the forge or fed, and the copy it was (from the slot it came from). */
    private record Used(int id, CollectionStore.Find copy) {
    }

    private static List<Used> forging = List.of();   // the items of the last forge request, until its result

    private ItemUse() {
    }

    public static void install() {
        Register.INSTANCE.register(PacketType.FORGE_REQUEST, p -> forgeRequest((ForgeRequestPacket) p));
        Register.INSTANCE.register(PacketType.FORGE_RESULT, p -> forgeResult((ForgeResultPacket) p));
        Register.INSTANCE.register(PacketType.PETUPGRADEREQUEST, p -> petUpgrade((PetUpgradeRequestPacket) p));
        Register.INSTANCE.register(PacketType.INVDROP, p -> dropped((packets.outgoing.InvDropPacket) p));
        // things the game sells or gives (no drops), and Tinkerer quest hand-ins: for UPE runs
        Register.INSTANCE.register(PacketType.BUYRESULT, p -> {
            packets.incoming.BuyResultPacket b = (packets.incoming.BuyResultPacket) p;
            if (b.result == 0) Upe.get().rewarded("shop purchase");
        });
        Register.INSTANCE.register(PacketType.LOGIN_REWARD_MSG, p -> Upe.get().rewarded("daily login reward"));
        Register.INSTANCE.register(PacketType.CHEST_REWARD_RESULT, p -> Upe.get().rewarded("reward chest"));
        Register.INSTANCE.register(PacketType.CLAIM_BATTLE_PASS, p -> Upe.get().rewarded("battle pass claim"));
        Register.INSTANCE.register(PacketType.CLAIM_BP_MILESTONE_RESULT, p -> Upe.get().rewarded("battle pass milestone"));
        Register.INSTANCE.register(PacketType.REDEEM_EXALTATION_REWARD, p -> Upe.get().rewarded("exaltation reward"));
        // trades, for gUPE runs: the window, then what each side offers in the accepts
        Register.INSTANCE.register(PacketType.TRADESTART, p -> LootEvents.tradeStart((packets.incoming.TradeStartPacket) p));
        Register.INSTANCE.register(PacketType.ACCEPTTRADE, p -> {
            packets.outgoing.AcceptTradePacket a = (packets.outgoing.AcceptTradePacket) p;
            Upe.get().tradeOffers(a.clientOffer, a.partnerOffer);
        });
        Register.INSTANCE.register(PacketType.TRADEACCEPTED, p -> {
            packets.incoming.TradeAcceptedPacket a = (packets.incoming.TradeAcceptedPacket) p;
            Upe.get().tradeOffers(a.clientOffer, a.partnerOffer);
        });
        Register.INSTANCE.register(PacketType.QUEST_REDEEM, p -> {
            packets.outgoing.QuestRedeemPacket q = (packets.outgoing.QuestRedeemPacket) p;
            int[] slots = q.slots == null ? new int[0] : java.util.Arrays.stream(q.slots).mapToInt(s -> s.slotId).toArray();
            TinkererQuests.Quest quest = TinkererQuests.get().quests().stream()
                    .filter(x -> x.id.equals(q.questIdString)).findFirst().orElse(null);
            Upe.get().questHandIn(playingId(), q.questIdInt, slots, quest == null ? null : quest.rewards,
                    quest != null && quest.itemOfChoice);
        });
        Register.INSTANCE.register(PacketType.QUEST_REDEEM_RESPONSE,
                p -> Upe.get().questAnswer(((packets.incoming.QuestRedeemResponsePacket) p).ok));
    }

    private static synchronized void forgeRequest(ForgeRequestPacket p) {
        forging = used(p.dismantledItems);
        if (p.dismantledItems != null) {   // what goes in decides whether what comes out is clean (UPE runs)
            int[] slots = java.util.Arrays.stream(p.dismantledItems).mapToInt(s -> s.slotId).toArray();
            Upe.get().forgeRequest(playingId(), slots);
        }
        AppLog.log("items", "Forge request: " + slots(p.dismantledItems) + " -> " + name(p.resultItemType)
                + "; collectible: " + names(forging));
    }

    private static synchronized void forgeResult(ForgeResultPacket p) {
        List<Used> used = forging;
        forging = List.of();
        AppLog.log("items", "Forge result: " + (p.success ? "success" : "failed") + ", " + slots(p.results));
        if (p.results != null) {
            Upe.get().forgeResult(playingId(), p.success, java.util.Arrays.stream(p.results).mapToInt(s -> s.slotId).toArray(),
                    java.util.Arrays.stream(p.results).mapToInt(s -> s.objectType).toArray());
        } else {
            Upe.get().forgeResult(playingId(), p.success, null, null);
        }
        if (!p.success) {
            AppLog.log("items", "Forge failed, nothing taken off");
            return;
        }
        remove(used, CollectionStore.FORGED, "Forge");
    }

    private static void petUpgrade(PetUpgradeRequestPacket p) {
        AppLog.log("items", "Pet upgrade request: " + p.petTransType + ", " + slots(p.slotObjects));
        if (p.petTransType != PetUpgradeType.FeedPet) return;
        remove(used(p.slotObjects), CollectionStore.FED, "Pet");
    }

    /**
     * Packets the game uses for forging items away and feeding them (seen in November 2026), which RealmShark does
     * not know yet, so they arrive here raw (see PacketDebug.problem). After the 5 header bytes (length, id):
     * <ul>
     * <li>195, request: byte count, then per item: int object id (yours), int slot, int item type being used up;</li>
     * <li>196, answer: byte success, byte count, then per item: int object id, int slot, int item type now in the slot
     * (-1 = empty).</li>
     * </ul>
     * Feeding several items to a pet at once sends them all in one request (count 2 and more).
     * They do not say whether the item went into the forge or to a pet.
     */
    static final int USE_REQUEST = 195, USE_RESULT = 196;
    private static final java.util.Map<Integer, Used> using = new java.util.HashMap<>();   // slot -> item, until the answer

    static synchronized void raw(int type, byte[] data) {
        try {
            java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(data);   // big endian, like all game packets
            b.position(5);
            if (type == USE_REQUEST) {
                int count = b.get() & 0xFF;
                int[] upeSlots = new int[count];
                for (int i = 0; i < count && b.remaining() >= 12; i++) {
                    b.getInt();
                    int slot = b.getInt(), item = b.getInt();
                    upeSlots[i] = slot;
                    ItemDb.Item it = ItemDb.get().item(item);
                    AppLog.log("items", "Use request: slot " + slot + ": " + name(item) + " (" + item + ")");
                    ItemCooldowns.used(item, "use request, slot " + slot);
                    if (it != null && it.isCollectible()) using.put(slot, new Used(item, LootEvents.copyInSlot(slot, item)));
                }
                Upe.get().forgeRequest(playingId(), upeSlots);   // forging or feeding: what goes in (UPE runs)
            } else {
                boolean ok = b.get() == 1;
                int count = b.get() & 0xFF;
                List<Used> gone = new ArrayList<>();
                for (int i = 0; i < count && b.remaining() >= 12; i++) {
                    b.getInt();
                    int slot = b.getInt(), now = b.getInt();
                    Used u = using.remove(slot);
                    AppLog.log("items", "Use result: " + (ok ? "success" : "failed") + ", slot " + slot + " now "
                            + (now == -1 ? "empty" : name(now)));
                    if (ok && u != null && now != u.id()) gone.add(u);
                }
                Upe.get().forgeResult(playingId(), ok, null, null);   // what turns up now was made from it
                remove(gone, CollectionStore.CONSUMED, "Forge or pet");
            }
        } catch (Exception e) {
            AppLog.log("items", "Could not read packet " + type + ": " + e);
        }
    }

    /** The character you are playing, 0 if none. */
    private static int playingId() {
        CharacterStore.Char c = CharacterStore.get().playing();
        return c == null ? 0 : c.charId;
    }

    private static void dropped(packets.outgoing.InvDropPacket p) {
        SlotObjectData[] slot = {p.slotObject};
        AppLog.log("items", "Drop on the ground: " + slots(slot));
        remove(used(slot), CollectionStore.DROPPED, "Ground");
    }

    private static void remove(List<Used> items, String source, String where) {
        for (Used u : items) {
            boolean taken = CollectionStore.get().recordRemoved(u.id(), source, where, u.copy());
            AppLog.log("items", (source.equals(CollectionStore.FORGED) ? "Forged " : source.equals(CollectionStore.FED) ? "Fed "
                    : source.equals(CollectionStore.DROPPED) ? "Dropped " : "Used up ") + name(u.id())
                    + (u.copy() != null && u.copy().enchantIds != null ? " (" + u.copy().enchantIds.size() + " enchantments)" : "")
                    + (taken ? ", taken off the collection" : " (not in the collection)"));
        }
        if (!items.isEmpty()) ServerSync.get().uploadSoon();
    }

    /**
     * The collectible items among the slots (others, e.g. materials, are not in the collection anyway), each with
     * the copy that was in its slot at the last inventory scan.
     */
    private static List<Used> used(SlotObjectData[] slots) {
        List<Used> out = new ArrayList<>();
        if (slots == null) return out;
        for (SlotObjectData s : slots) {
            ItemDb.Item item = s == null ? null : ItemDb.get().item(s.objectType);
            if (item != null && item.isCollectible()) out.add(new Used(s.objectType, LootEvents.copyInSlot(s.slotId, s.objectType)));
        }
        return out;
    }

    /** Every slot of a packet as it came: "slot 14: Amulet of Dispersion (2878, object 1234)". For bug reports. */
    private static String slots(SlotObjectData[] slots) {
        if (slots == null || slots.length == 0) return "no slots";
        List<String> out = new ArrayList<>();
        for (SlotObjectData s : slots) {
            if (s == null) continue;
            CollectionStore.Find seen = LootEvents.copyInSlot(s.slotId, s.objectType);
            out.add("slot " + s.slotId + ": " + name(s.objectType) + " (" + s.objectType + ", object " + s.objectId + ")"
                    + (seen == null ? "" : " seen with " + (seen.enchantIds == null ? "unknown" : seen.enchantIds.size()) + " enchantments"));
        }
        return String.join("; ", out);
    }

    private static String names(List<Used> items) {
        return items.isEmpty() ? "no collectible items" : String.join(", ", items.stream()
                .map(u -> name(u.id()) + " (slot " + (u.copy() == null ? "?" : "ok") + ")").toList());
    }

    private static String name(int id) {
        ItemDb.Item item = ItemDb.get().item(id);
        return item == null ? "#" + id : item.name;
    }
}
