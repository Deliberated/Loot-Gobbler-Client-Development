package lootgobbler.ui;

import com.formdev.flatlaf.util.UIScale;

import javax.swing.*;
import javax.swing.plaf.UIResource;
import java.awt.*;

/**
 * Square-in-square check box used by the Loot Gobbler themes (CheckBox.icon in themes/common.properties).
 */
public class PixelCheckBoxIcon implements Icon, UIResource {
    private static final int SIZE = 13;

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        ButtonModel m = ((AbstractButton) c).getModel();
        int s = getIconWidth();
        int inset = Math.max(3, s / 4);
        Color gold = color("LootGobbler.gold", Palette.GOLD);
        Color goldDark = color("LootGobbler.goldDark", Palette.GOLD_DARK);

        g.setColor(m.isSelected() ? gold : color("LootGobbler.header", Color.LIGHT_GRAY));
        g.fillRect(x, y, s, s);
        if (m.isSelected()) {
            g.setColor(goldDark);
            g.fillRect(x + inset, y + inset, s - 2 * inset, s - 2 * inset);
        }
        g.setColor(m.isRollover() || m.isSelected() ? goldDark : color("LootGobbler.line", Color.GRAY));
        g.drawRect(x, y, s - 1, s - 1);
        if (!c.isEnabled()) {
            g.setColor(new Color(128, 128, 128, 110));
            g.fillRect(x, y, s, s);
        }
    }

    private static Color color(String key, Color fallback) {
        Color c = UIManager.getColor(key);
        return c == null ? fallback : c;
    }

    @Override
    public int getIconWidth() {
        return UIScale.scale(SIZE);
    }

    @Override
    public int getIconHeight() {
        return UIScale.scale(SIZE);
    }
}
