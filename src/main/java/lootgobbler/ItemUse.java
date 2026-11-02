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
    }

    private static synchronized void forgeRequest(ForgeRequestPacket p) {
        forging = used(p.dismantledItems);
        AppLog.log("items", "Forge request: " + names(forging) + " -> " + name(p.resultItemType));
    }

    private static synchronized void forgeResult(ForgeResultPacket p) {
        List<Used> used = forging;
        forging = List.of();
        if (!p.success) {
            AppLog.log("items", "Forge failed, nothing taken off");
            return;
        }
        remove(used, CollectionStore.FORGED, "Forge");
    }

    private static void petUpgrade(PetUpgradeRequestPacket p) {
        if (p.petTransType != PetUpgradeType.FeedPet) return;
        remove(used(p.slotObjects), CollectionStore.FED, "Pet");
    }

    private static void remove(List<Used> items, String source, String where) {
        for (Used u : items) {
            boolean taken = CollectionStore.get().recordRemoved(u.id(), source, where, u.copy());
            AppLog.log("items", (source.equals(CollectionStore.FORGED) ? "Forged " : "Fed ") + name(u.id())
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

    private static String names(List<Used> items) {
        return items.isEmpty() ? "no collectible items" : String.join(", ", items.stream()
                .map(u -> name(u.id()) + " (slot " + (u.copy() == null ? "?" : "ok") + ")").toList());
    }

    private static String name(int id) {
        ItemDb.Item item = ItemDb.get().item(id);
        return item == null ? "#" + id : item.name;
    }
}
