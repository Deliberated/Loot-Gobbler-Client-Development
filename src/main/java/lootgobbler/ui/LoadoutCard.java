package lootgobbler.ui;

import lootgobbler.CharacterStore;
import lootgobbler.CharacterStore.Char;
import lootgobbler.ItemDb;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * A character's loadout as a picture to share: a box tinted with the theme's accent, its top-right corner cut off
 * (transparent), the character with its class and fame, the reusable quick-slot items and the backpack in the top
 * right, and the four gear slots below like the game's inventory (enchantment marker bottom right). In the colors of
 * the theme picked. Copied to the clipboard from the Loadout box.
 */
final class LoadoutCard {
    private static final int SCALE = 2;   // drawn at twice the size, so it stays sharp when shared
    private static final int EDGE = 2;   // the box's gold edge, inside which the spacing is counted
    private static final int PAD = 14, CUT = 18, SKIN = 88, TILE = 56, TILE_GAP = 8, BAG = 50, BAG_GAP = 8, QUICK = 28, QUICK_GAP = 4;
    // the card is as wide as its content needs; its height is fixed (room for all quick slots)
    private static final int TEXT_W = 220, QUICK_W = 240;   // the most room the texts get (longer: a smaller font)
    // room for every quick slot, so a card has the same height however many are filled
    private static final int QUICK_ROWS = lootgobbler.LootEvents.QUICKSLOTS.length;

    private LoadoutCard() {
    }

    private static final String COPY = "Copy as image";

    /** A "Copy as image" button that puts the character's card on the clipboard, saying so for a moment. */
    static JButton copyButton(Char c) {
        JButton b = new JButton(COPY);
        b.setToolTipText("Copy this loadout as an image to share");
        b.setFocusable(false);
        // as an image with transparency: Java puts a PNG on the clipboard too, which keeps the cut corner see-through
        // (Discord, browsers, image editors); programs taking only a bitmap show it black
        b.addActionListener(e -> {
            try {
                BufferedImage img = render(c);
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new CollectionPanel.ImageSelection(img), null);
                b.setText("Copied");
            } catch (Exception ex) {
                b.setText("Could not copy");
            }
            Timer t = new Timer(1500, ev -> b.setText(COPY));
            t.setRepeats(false);
            t.start();
        });
        return b;
    }

    /** The theme's colors for the card, read when it is drawn. */
    private record Colors(Color paper, Color sand, Color line, Color ink, Color gold, Color goldLight, boolean dark) {
        static Colors now() {
            Color paper = Section.color("LootGobbler.paper", new Color(0x2A1E14));
            Color sand = Section.color("LootGobbler.sand", paper);
            Color gold = Section.color("LootGobbler.gold", new Color(0xB07D0A));
            return new Colors(paper, sand, Section.color("LootGobbler.line", gold.darker()),
                    Section.color("LootGobbler.ink", Color.WHITE), gold,
                    Section.color("LootGobbler.goldHover", gold.brighter()), luminance(paper) < 0.5);
        }
    }

    /** The card as an image; the cut corner is transparent. */
    static BufferedImage render(Char c) {
        Colors col = Colors.now();
        Font nameFont = new Font(lootgobbler.Themes.HEAD_FONT, Font.PLAIN, 30);
        Font fameFont = new Font(lootgobbler.Themes.BODY_FONT, Font.PLAIN, 20);
        Font smallFont = new Font(lootgobbler.Themes.BODY_FONT, Font.PLAIN, 16);
        BufferedImage scratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D m = scratch.createGraphics();

        String name = c.className == null || c.className.isBlank() ? "Character" : c.className;
        String fame = NumberFormat.getIntegerInstance().format(c.fame) + " Fame";
        List<Integer> quick = reusables(c);
        int bag = c.extender ? CharacterStore.EXTENDER_ITEM : c.backpack ? CharacterStore.BACKPACK_ITEM : -1;

        // the header (character left, quick slots right; always as tall as three quick slots) and below it the
        // gear slots with the backpack beside them; as wide as the wider of the two needs
        int quickH = QUICK_ROWS * QUICK + (QUICK_ROWS - 1) * QUICK_GAP;
        int headerH = Math.max(SKIN, quickH);
        nameFont = fit(nameFont, name, TEXT_W);
        fameFont = fit(fameFont, fame, TEXT_W);
        FontMetrics nameFm = m.getFontMetrics(nameFont), fameFm = m.getFontMetrics(fameFont);
        int quickW = 0;
        for (int id : quick) {
            String label = CharactersView.itemName(id);
            quickW = Math.max(quickW, QUICK + 6 + m.getFontMetrics(fit(smallFont, label, QUICK_W - QUICK - 6)).stringWidth(label));
        }
        m.dispose();
        // a skin drawn at 16x16 (more detailed than the classes' 8x8) gets the card's full height on the left, so its
        // pixels are about as big as those of an 8x8 character; the rest moves right of it
        int nativeSize = nativeSize(c);
        boolean big = nativeSize > 8;
        int skinSize = big ? headerH + 14 + TILE : SKIN;
        Icon skin = CharactersView.skin(c, skinSize);
        // the spacing counted in the character's own pixels, from what its sprite shows: between the box's edge and
        // the character one pixel if only a point of it reaches out that far (one or two pixels in its leftmost
        // column), else two; about two and a half between the character and what is right of it
        float charPx = skinSize / (float) nativeSize;
        int[] shownBox = skin == null ? null : shown(skin);
        int shownLeft = shownBox == null ? 0 : shownBox[0], shownRight = shownBox == null ? skinSize : shownBox[2] + 1;
        int leftPx = leftmostPixels(c) <= 2 ? 1 : 2;
        int sx = EDGE + Math.round(charPx * leftPx) - shownLeft;      // where the icon is drawn
        int charRight = sx + shownRight + Math.round(charPx * 2.5f);  // where the texts (and a 16x16 skin's gear row) start
        int rowX = big ? charRight : sx + shownLeft;                  // the gear row: under the 8x8 character, aligned with it
        int textW = Math.max(nameFm.stringWidth(name), fameFm.stringWidth(fame));
        int headerRight = charRight + textW + (quickW > 0 ? 32 + quickW : 0);
        int rowRight = rowX + 4 * TILE + 3 * TILE_GAP + (bag > 0 ? BAG_GAP + BAG : 0);
        int w = Math.max(headerRight, rowRight) + PAD + CUT / 2, h = PAD * 2 + headerH + 14 + TILE;
        BufferedImage img = new BufferedImage(w * SCALE, h * SCALE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.scale(SCALE, SCALE);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

        // the box: tinted with the accent, an accent edge with a lighter inner line, the top-right corner cut off
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Path2D box = outline(0.5f, w - 0.5f, h - 0.5f, CUT);
        g.setPaint(new GradientPaint(0, 0, mix(col.paper, col.gold, 0.16), 0, h, mix(col.paper, col.gold, 0.07)));
        g.fill(box);
        g.setStroke(new BasicStroke(2f));
        g.setColor(col.gold);
        g.draw(box);
        g.setStroke(new BasicStroke(1f));
        g.setColor(alpha(col.goldLight, 80));
        g.draw(outline(3f, w - 3f, h - 3f, CUT - 1.2f));
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);

        // top left (a 16x16 skin: the whole left side): the character in its skin and dyes, then class name and fame
        int y = PAD;
        int skinY = big ? y : y + (headerH - SKIN) / 2;
        if (skin != null) {
            int sy0 = skinY + (skinSize - skin.getIconHeight()) / 2;
            shadow(g, col, skin, sx, sy0, big ? 5 : 4);
            skin.paintIcon(null, g, sx, sy0);
        }
        int tx = charRight, textH = nameFm.getHeight() + fameFm.getHeight();
        int ty = y + Math.max(0, (headerH - textH) / 2);
        text(g, col, name, nameFont, col.ink, tx, ty + nameFm.getAscent());
        text(g, col, fame, fameFont, col.gold, tx, ty + nameFm.getHeight() + fameFm.getAscent());

        // top right: the reusable quick-slot items from the top down
        int rx = w - PAD - CUT / 2 - quickW;
        int qy = y + (headerH - quickH) / 2;
        for (int id : quick) {
            Icon q = CharactersView.sprite(id, QUICK);
            if (q != null) {
                int ix = rx + (QUICK - q.getIconWidth()) / 2, iy = qy + (QUICK - q.getIconHeight()) / 2;
                shadow(g, col, q, ix, iy, 2);
                q.paintIcon(null, g, ix, iy);
            }
            String label = CharactersView.itemName(id);
            Font f = fit(smallFont, label, QUICK_W - QUICK - 6);
            FontMetrics fm = g.getFontMetrics(f);
            text(g, col, label, f, col.ink, rx + QUICK + 6, qy + (QUICK + fm.getAscent() - fm.getDescent()) / 2);
            qy += QUICK + QUICK_GAP;
        }

        // below: weapon, ability, armor and ring as inventory slots
        int sy = y + headerH + 14;
        for (int slot = 0; slot < 4; slot++) {
            int id = c.equipment != null && slot < c.equipment.length ? c.equipment[slot] : -1;
            int[] enchants = CharactersView.enchantIds(c, slot);
            tile(g, col, rowX + slot * (TILE + TILE_GAP), sy, id, enchants == null ? 0 : enchants.length);
        }
        if (bag > 0) {   // the backpack beside the gear, set a little apart; without one, its place stays empty
            Icon b = CharactersView.sprite(bag, BAG);
            int bx = rowX + 4 * (TILE + TILE_GAP) - TILE_GAP + BAG_GAP;
            if (b != null) paintCentered(g, col, b, bx + BAG / 2, sy + TILE / 2);   // on the slots' middle
        }
        g.dispose();
        return img;
    }

    /**
     * A shadow behind an icon drawn at (x, y): its own shape in black, blurred, a little down and to the right.
     *
     * @param radius how far the blur spreads (and the offset), in card pixels
     */
    private static void shadow(Graphics2D g, Colors col, Icon icon, int x, int y, int radius) {
        // drawn at the image's real resolution, so the blur is smooth when the card is scaled up
        int pad = radius * 2 * SCALE, w = icon.getIconWidth() * SCALE + 2 * pad, h = icon.getIconHeight() * SCALE + 2 * pad;
        BufferedImage sil = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D sg = sil.createGraphics();
        sg.translate(pad, pad);
        sg.scale(SCALE, SCALE);
        icon.paintIcon(null, sg, 0, 0);
        sg.dispose();
        float strength = col.dark ? 0.8f : 0.45f;
        int[] px = sil.getRGB(0, 0, w, h, null, 0, w);
        for (int i = 0; i < px.length; i++) px[i] = Math.round((px[i] >>> 24) * strength) << 24;   // black, its alpha kept
        sil.setRGB(0, 0, w, h, px, 0, w);
        float[] kernel = gaussian(radius * SCALE);
        java.awt.image.ConvolveOp across = new java.awt.image.ConvolveOp(new java.awt.image.Kernel(kernel.length, 1, kernel));
        java.awt.image.ConvolveOp down = new java.awt.image.ConvolveOp(new java.awt.image.Kernel(1, kernel.length, kernel));
        BufferedImage blurred = down.filter(across.filter(sil, null), null);
        Graphics2D dg = (Graphics2D) g.create();
        dg.translate(x - pad / (float) SCALE + radius / 2f, y - pad / (float) SCALE + radius * 0.75f);
        dg.scale(1.0 / SCALE, 1.0 / SCALE);
        dg.drawImage(blurred, 0, 0, null);
        dg.dispose();
    }

    /** A normalized Gaussian blur kernel reaching radius pixels each way. */
    private static float[] gaussian(int radius) {
        float[] k = new float[radius * 2 + 1];
        float sigma = Math.max(1f, radius / 2f), sum = 0;
        for (int i = -radius; i <= radius; i++) sum += k[i + radius] = (float) Math.exp(-(i * i) / (2 * sigma * sigma));
        for (int i = 0; i < k.length; i++) k[i] /= sum;
        return k;
    }

    /** The box around an icon's visible pixels as {x0, y0, x1, y1}, or null if it shows nothing. */
    private static int[] shown(Icon icon) {
        BufferedImage im = new BufferedImage(icon.getIconWidth(), icon.getIconHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D ig = im.createGraphics();
        icon.paintIcon(null, ig, 0, 0);
        ig.dispose();
        int x0 = im.getWidth(), y0 = im.getHeight(), x1 = -1, y1 = -1;
        for (int y = 0; y < im.getHeight(); y++) {
            for (int x = 0; x < im.getWidth(); x++) {
                if ((im.getRGB(x, y) >>> 24) == 0) continue;
                x0 = Math.min(x0, x);
                y0 = Math.min(y0, y);
                x1 = Math.max(x1, x);
                y1 = Math.max(y1, y);
            }
        }
        return x1 < 0 ? null : new int[]{x0, y0, x1, y1};
    }

    /**
     * Paints an icon with the middle of what it shows (not of its square, which may be empty along an edge) at
     * (cx, cy).
     */
    private static void paintCentered(Graphics2D g, Colors col, Icon icon, int cx, int cy) {
        int[] box = shown(icon);
        if (box == null) return;   // nothing to show
        int x = cx - (box[0] + box[2] + 1) / 2, y = cy - (box[1] + box[3] + 1) / 2;
        shadow(g, col, icon, x, y, 3);
        icon.paintIcon(null, g, x, y);
    }

    /** The width of the character's sprite in the game files (8 for the classes, 16 for detailed skins), 8 if unknown. */
    private static int nativeSize(Char c) {
        try {
            BufferedImage im = assets.ImageBuffer.getImage(c.spriteId());
            return im == null ? 8 : im.getWidth();
        } catch (Exception e) {
            return 8;
        }
    }

    /** How many pixels of the character's sprite (in the game files) are in its leftmost column that shows any. */
    private static int leftmostPixels(Char c) {
        try {
            BufferedImage im = assets.ImageBuffer.getImage(c.spriteId());
            if (im == null) return 0;
            for (int x = 0; x < im.getWidth(); x++) {
                int n = 0;
                for (int y = 0; y < im.getHeight(); y++) if ((im.getRGB(x, y) >>> 24) != 0) n++;
                if (n > 0) return n;
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    /** The font, made smaller until the text fits the width. */
    private static Font fit(Font font, String text, int width) {
        BufferedImage scratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D m = scratch.createGraphics();
        Font f = font;
        while (f.getSize2D() > 10 && m.getFontMetrics(f).stringWidth(text) > width) f = f.deriveFont(f.getSize2D() - 1);
        m.dispose();
        return f;
    }

    /** The box's outline from (o, o) to (right, bottom), its top-right corner cut by cut. */
    private static Path2D outline(float o, float right, float bottom, float cut) {
        Path2D p = new Path2D.Float();
        p.moveTo(o, o);
        p.lineTo(right - cut, o);
        p.lineTo(right, o + cut);
        p.lineTo(right, bottom);
        p.lineTo(o, bottom);
        p.closePath();
        return p;
    }

    /** One gear slot: a rounded square a shade off the box, the item and its enchantment marker bottom right. */
    private static void tile(Graphics2D g, Colors col, int x, int y, int id, int enchants) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(col.dark ? mix(col.sand, Color.BLACK, 0.3) : mix(col.sand, col.line, 0.25));
        g.fillRoundRect(x, y, TILE, TILE, 8, 8);
        g.setColor(col.line);
        g.drawRoundRect(x, y, TILE - 1, TILE - 1, 8, 8);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        Icon item = id > 0 ? CharactersView.sprite(id, TILE - 10) : null;
        if (item != null) item.paintIcon(null, g, x + (TILE - item.getIconWidth()) / 2, y + (TILE - item.getIconHeight()) / 2);
        EnchantBadge.draw(g, x + TILE - 4, y + TILE - 4, enchants, 6);
    }

    /** Text, with a dark shadow on dark themes (on light ones it would only smudge). */
    private static void text(Graphics2D g, Colors col, String text, Font font, Color color, int x, int baseline) {
        g.setFont(font);
        if (col.dark) {
            g.setColor(Color.BLACK);
            g.drawString(text, x + 1, baseline + 1);
        }
        g.setColor(color);
        g.drawString(text, x, baseline);
    }

    /** The reusables in the quick slots (Coral Juice Refinery...), not potions; empty if none or never seen. */
    private static List<Integer> reusables(Char c) {
        List<Integer> out = new ArrayList<>();
        if (c.quickslots == null) return out;
        for (int id : c.quickslots) {
            ItemDb.Item it = id > 0 ? ItemDb.get().item(id) : null;
            if (it != null && it.quickslotOnly) out.add(id);
        }
        return out;
    }

    private static Color mix(Color a, Color b, double t) {
        return new Color((int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
                (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                (int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }

    private static Color alpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
    }

    private static double luminance(Color c) {
        return (0.2126 * c.getRed() + 0.7152 * c.getGreen() + 0.0722 * c.getBlue()) / 255;
    }
}
