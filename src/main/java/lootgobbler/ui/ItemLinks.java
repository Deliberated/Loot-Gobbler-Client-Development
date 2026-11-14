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
    private static final java.util.List<IntConsumer> openListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** Client property on a linked icon: the item's id (e.g. for the Newcomer's Guide to find a UT or ST). */
    static final String ITEM = "lootgobbler.itemLink";

    private ItemLinks() {
    }

    /** Set once by the main window: switches to the Inventory tab and shows the item there. */
    public static void setOpener(IntConsumer open) {
        opener = open;
    }

    public static void open(int itemId) {
        if (opener != null) opener.accept(itemId);
        for (IntConsumer l : openListeners) l.accept(itemId);
    }

    /** Called after an item was opened in the Inventory. */
    static void onOpen(IntConsumer listener) {
        openListeners.add(listener);
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
        c.putClientProperty(ITEM, itemId);
        // only the icon itself: a label stretched by its layout would otherwise take clicks around the icon too
        Clicks.onClick(c, (java.awt.event.MouseEvent e) -> {
            if (SwingUtilities.isLeftMouseButton(e) && iconArea(c).contains(e.getPoint())) open(itemId);
        });
        c.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override
            public void mouseMoved(java.awt.event.MouseEvent e) {
                c.setCursor(iconArea(c).contains(e.getPoint()) ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : null);
            }
        });
        return c;
    }

    /** Where c draws its item icon (a label's icon), in c's coordinates; all of c for anything else. */
    static Rectangle iconArea(JComponent c) {
        if (c instanceof JLabel l && l.getIcon() != null) {
            Insets in = l.getInsets();
            Rectangle view = new Rectangle(in.left, in.top, l.getWidth() - in.left - in.right, l.getHeight() - in.top - in.bottom);
            Rectangle icon = new Rectangle(), text = new Rectangle();
            SwingUtilities.layoutCompoundLabel(l, l.getFontMetrics(l.getFont()), l.getText(), l.getIcon(),
                    l.getVerticalAlignment(), l.getHorizontalAlignment(), l.getVerticalTextPosition(),
                    l.getHorizontalTextPosition(), view, icon, text, l.getText() == null || l.getText().isEmpty() ? 0 : l.getIconTextGap());
            return icon;
        }
        return new Rectangle(0, 0, c.getWidth(), c.getHeight());
    }
}
