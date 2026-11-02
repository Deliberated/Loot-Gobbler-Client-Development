package lootgobbler.ui;

import assets.ImageBuffer;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/**
 * "Any ST item of this slot", as the forge asks for it: the outline of a basic item of the slot (short sword, shield,
 * leather armor, ring) filled flat grey, with a small orange "ST" in the corner.
 */
final class SlotIcons {
    private static final Map<String, Integer> SHAPES = Map.of("Weapon", 0xa00, "Ability", 0xa08, "Armor", 0xa0d, "Ring", 0xa24);
    private static final Color FILL = new Color(0xB8B0A0), ST = new Color(0xE07B2A);
    private static final Map<String, Icon> CACHE = new HashMap<>();   // EDT only

    private SlotIcons() {
    }

    /** The icon for any ST item of the slot ("Weapon", "Ability", "Armor", "Ring"), or null if unknown. */
    static Icon anySt(String slot, int size) {
        return CACHE.computeIfAbsent(slot + size, k -> make(slot, size));
    }

    private static Icon make(String slot, int size) {
        Integer id = SHAPES.get(slot);
        BufferedImage src;
        try {
            src = id == null ? null : ImageBuffer.getImage(id);
        } catch (Exception e) {
            src = null;
        }
        if (src == null) return null;
        int inner = size - 2;
        BufferedImage shape = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = shape.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(src, 1, 1, inner, inner, null);
        g.dispose();
        // flat fill where the item is, black outline around it
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (opaque(shape, x, y)) out.setRGB(x, y, FILL.getRGB());
                else if (opaque(shape, x - 1, y) || opaque(shape, x + 1, y) || opaque(shape, x, y - 1) || opaque(shape, x, y + 1)) {
                    out.setRGB(x, y, 0xff000000);
                }
            }
        }
        g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        g.setFont(new Font(lootgobbler.Themes.BODY_FONT, Font.PLAIN, Math.max(10, size / 2 - 2)));
        FontMetrics fm = g.getFontMetrics();
        int tx = size - fm.stringWidth("ST") - 1, ty = size - 2;
        g.setColor(Color.BLACK);
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) g.drawString("ST", tx + dx, ty + dy);
        g.setColor(ST);
        g.drawString("ST", tx, ty);
        g.dispose();
        return new ImageIcon(out);
    }

    private static boolean opaque(BufferedImage img, int x, int y) {
        return x >= 0 && y >= 0 && x < img.getWidth() && y < img.getHeight() && (img.getRGB(x, y) >>> 24) > 0;
    }
}
