package packets.incoming;

import packets.Packet;
import packets.reader.BufferReader;

import java.util.Arrays;

/**
 * Received when the player enters or updates their vault
 */
public class VaultContentPacket extends Packet {
    /**
     * If this is the last vault packet
     */
    public boolean lastVaultPacket;
    /**
     * Vault chest object ID
     */
    public int vaultChestObjectId;
    /**
     * material  ID
     */
    public int materialChestObjectId;
    /**
     * Gift chest object ID
     */
    public int giftChestObjectId;
    /**
     * Potion storage object ID
     */
    public int potionStorageObjectId;
    /**
     * Seasonal spoils object ID
     */
    public int seasonalSpoilChestObjectId;
    /**
     * The contents of the players vault, sent as an array of item object IDs or -1 if the slot is empty
     */
    public int[] vaultContents;
    /**
     * The material contents of the player
     */
    public int[] materialContents;
    /**
     * The contents of the player's gift vault
     */
    public int[] giftContents;
    /**
     * The contents of the player's potion vault
     */
    public int[] potionContents;
    /**
     * The contents of the player's seasonal spoils items
     */
    public int[] seasonalSpoilContent;
    /**
     * The cost in gold for the next upgrade to the vault
     */
    public short vaultUpgradeCost;
    /**
     * The cost in gold for the next upgrade to the potion vault
     */
    public short potionUpgradeCost;
    /**
     * The current slot size of the player's potion vault
     */
    public short currentPotionMax;
    /**
     * The size of the player's potion vault after they purchase the current upgrade
     */
    public short nextPotionMax;
    /**
     * String indicating enchantments in player's vault
     */
    public String vaultChestEnchants;
    /**
     * String indicating enchantments in player's gift vault
     */
    public String giftChestEnchants;
    /**
     * String indicating enchantments in player's spoils chest vault
     */
    public String spoilsChestEnchants;
    /**
     * Cost to upgrade materials
     */
    public short materialUpgradeCost;

    /**
     * True if the packet stopped decoding part way (the game changed its format); fields after that point keep
     * their empty defaults. [Loot Gobbler patch]
     */
    public boolean partial;
    /**
     * Extra short the current game sends after nextPotionMax (meaning unknown; 64 in captured packets).
     */
    public short unknownShort;

    @Override
    public void deserialize(BufferReader buffer) throws Exception {
        vaultContents = materialContents = giftContents = potionContents = seasonalSpoilContent = new int[0];
        vaultChestEnchants = giftChestEnchants = spoilsChestEnchants = "";
        // Tolerant decoding [Loot Gobbler patch]: the current game sends a changed layout after the vault contents,
        // so keep whatever decodes and stop at the first field that does not, instead of dropping the packet.
        try {
            lastVaultPacket = buffer.readBoolean();
            vaultChestObjectId = buffer.readCompressedInt();
            materialChestObjectId = buffer.readCompressedInt();
            giftChestObjectId = buffer.readCompressedInt();
            potionStorageObjectId = buffer.readCompressedInt();
            seasonalSpoilChestObjectId = buffer.readCompressedInt();

            vaultContents = readArray(buffer);
            materialContents = readArray(buffer);
            giftContents = readArray(buffer);
            potionContents = readArray(buffer);
            seasonalSpoilContent = readArray(buffer);

            vaultUpgradeCost = buffer.readShort();
            materialUpgradeCost = buffer.readShort();
            potionUpgradeCost = buffer.readShort();
            currentPotionMax = buffer.readShort();
            nextPotionMax = buffer.readShort();
            unknownShort = buffer.readShort();   // added by the game in 2026 [Loot Gobbler patch]

            vaultChestEnchants = buffer.readString();
            giftChestEnchants = buffer.readString();
            spoilsChestEnchants = buffer.readString();
        } catch (Exception e) {
            partial = true;
        }
    }

    private static int[] readArray(BufferReader buffer) {
        int length = buffer.readCompressedInt();
        if (length < 0 || length > 5000) throw new IllegalStateException("implausible array length " + length);
        int[] array = new int[length];
        for (int i = 0; i < length; i++) {
            array[i] = buffer.readCompressedInt();
        }
        return array;
    }

    @Override
    public String toString() {
        return "VaultContentPacket{" +
                "\n   lastVaultPacket=" + lastVaultPacket +
                "\n   vaultChestObjectId=" + vaultChestObjectId +
                "\n   materialChestObjectId=" + materialChestObjectId +
                "\n   giftChestObjectId=" + giftChestObjectId +
                "\n   potionStorageObjectId=" + potionStorageObjectId +
                "\n   seasonalSpoilChestObjectId=" + seasonalSpoilChestObjectId +
                "\n   vaultContents=" + Arrays.toString(vaultContents) +
                "\n   materialContents=" + Arrays.toString(materialContents) +
                "\n   giftContents=" + Arrays.toString(giftContents) +
                "\n   potionContents=" + Arrays.toString(potionContents) +
                "\n   seasonalSpoilContent=" + Arrays.toString(seasonalSpoilContent) +
                "\n   vaultUpgradeCost=" + vaultUpgradeCost +
                "\n   potionUpgradeCost=" + potionUpgradeCost +
                "\n   currentPotionMax=" + currentPotionMax +
                "\n   nextPotionMax=" + nextPotionMax +
                "\n   vaultChestEnchants=" + vaultChestEnchants +
                "\n   giftChestEnchants=" + giftChestEnchants +
                "\n   spoilsChestEnchants=" + spoilsChestEnchants +
                "\n   materialUpgradeCost=" + materialUpgradeCost;
    }
}