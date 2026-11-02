package lootgobbler.ui;

import lootgobbler.ItemDb;

import javax.swing.*;
import java.awt.*;
import java.util.function.IntConsumer;

/**
 * Item icons elsewhere (Characters, Loot Caravan, Profile) that jump to the item in the Inventory tab when clicked:
 * scrolled to the middle of the grid with the gold pulse and its history shown, like a click on an ST set piece.
 */
public final class ItemLinks {
    private static IntConsumer opener;

    private ItemLinks() {
    }

    /** Set once by the main window: switches to the Inventory tab and shows the item there. */
    public static void setOpener(IntConsumer open) {
        opener = open;
    }

    public static void open(int itemId) {
        if (opener != null) opener.accept(itemId);
    }

    /**
     * Makes c open the item on click, with a hand cursor. Only for items the Inventory tracks (UTs and STs);
     * other items stay as they are.
     */
    public static <T extends JComponent> T link(T c, int itemId) {
        ItemDb.Item item = itemId > 0 ? ItemDb.get().item(itemId) : null;
        if (item == null) return c;
        if (!item.isCollectible()) {   // say why a click does nothing
            String note = "Not in the Inventory (it tracks UT/ST and top-tier items)";
            String tip = c.getToolTipText();
            if (tip == null || tip.isBlank()) c.setToolTipText(note);
            else if (tip.endsWith("</html>")) c.setToolTipText(tip.substring(0, tip.length() - 7) + "<br><i>" + note + "</i></html>");
            else c.setToolTipText(tip + " - " + note);
            return c;
        }
        c.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        Clicks.onClick(c, () -> open(itemId));
        return c;
    }
}
