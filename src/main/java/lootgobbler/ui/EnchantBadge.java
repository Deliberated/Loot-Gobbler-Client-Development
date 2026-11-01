package lootgobbler.ui;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.InputStream;

/**
 * In-game enchantment marker in an item's bottom-right corner, using the game's rarity sprites
 * (resources /rarity/*.png): 1 uncommon, 2 rare, 3 legendary, 4 divine.
 */
public final class EnchantBadge {
    public static final String[] RARITY = {"Common", "Uncommon", "Rare", "Legendary", "Divine"};
    public static final Color[] COLORS = {new Color(0x9CA3AF), new Color(0x4CD15A), new Color(0x3FB6F2),
            new Color(0xB05CF0), new Color(0xF2C232)};

    private static final BufferedImage[] SPRITES = {null, load("uncommon"), load("rare"), load("legendary"), load("divine")};

    private static BufferedImage load(String name) {
        try (InputStream in = EnchantBadge.class.getResourceAsStream("/rarity/" + name + ".png")) {
            return in == null ? null : ImageIO.read(in);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Draws the rarity sprite for the given enchantment count, its bottom-right corner at (right, bottom).
     *
     * @param size marker scale hint: 5 = native pixel size, 10 = doubled (in between scales proportionally)
     */
    public static void draw(Graphics2D g, int right, int bottom, int count, int size) {
        if (count <= 0) return;
        BufferedImage sprite = SPRITES[Math.min(4, count)];
        if (sprite == null) return;
        float s = Math.max(1f, size / 5f);
        int w = Math.round(sprite.getWidth() * s), h = Math.round(sprite.getHeight() * s);
        Object old = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(sprite, right - w + 1, bottom - h + 1, w, h, null);
        if (old != null) g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, old);
    }

    /**
     * An item icon with the rarity marker drawn over it.
     */
    public static Icon icon(Image item, int size, int enchants) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                if (item != null) g.drawImage(item, x, y, size, size, null);
                draw((Graphics2D) g, x + size - 1, y + size - 1, enchants, size >= 64 ? 10 : 5);
            }

            @Override
            public int getIconWidth() {
                return size;
            }

            @Override
            public int getIconHeight() {
                return size;
            }
        };
    }

    private EnchantBadge() {
    }
}
