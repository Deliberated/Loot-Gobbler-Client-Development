package lootgobbler;

import assets.ImageBuffer;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders a loot drop as a dark card image (PNG) for Discord: a headline, the drop context,
 * then one panel per item with its icon, tags, stats and description.
 */
public final class LootCard {
    public record Card(String title, String subtitle, String meta, List<LootEvents.Drop> items) {
    }

    private static final int WIDTH = 780, PAD = 26, ITEM_H = 150, ICON_BOX = 96;
    private static final Color BG = new Color(0x18181B), PANEL = new Color(0x232329), ICON_BG = new Color(0x1C1C20);
    private static final Color YELLOW = new Color(0xF5C518), WHITE = new Color(0xF4F4F5), GRAY = new Color(0x9CA3AF);
    private static final Color DIM = new Color(0x71717A), RULE = new Color(0x3F3F46);
    private static final Color GREEN = new Color(0x4ADE80), RED = new Color(0xF87171);
    private static final String[] RARITY = {"Common", "Uncommon", "Rare", "Legendary", "Divine"};
    private static final Color[] RARITY_COLOR = {new Color(0x9CA3AF), new Color(0x86EFAC), new Color(0x7DD3FC),
            new Color(0xC084FC), new Color(0xFCD34D)};

    public static byte[] png(Card card) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(render(card), "png", out);
        return out.toByteArray();
    }

    public static BufferedImage render(Card card) {
        int headerH = 118;
        int height = headerH + card.items().size() * (ITEM_H + 12) + PAD;
        BufferedImage img = new BufferedImage(WIDTH, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        g.setColor(BG);
        g.fillRect(0, 0, WIDTH, height);
        g.setColor(YELLOW);
        g.fillRect(0, 0, 6, height);

        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 28));
        g.drawString(card.title(), PAD, 46);
        g.setColor(WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
        g.drawString(card.subtitle(), PAD, 74);
        g.setColor(GRAY);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        g.drawString(card.meta(), PAD, 95);
        g.setColor(RULE);
        g.fillRect(PAD - 6, 106, WIDTH - 2 * PAD + 6, 1);

        int y = headerH;
        for (LootEvents.Drop d : card.items()) {
            drawItem(g, d, PAD - 8, y, WIDTH - 2 * PAD + 14);
            y += ITEM_H + 12;
        }
        g.dispose();
        return img;
    }

    private static void drawItem(Graphics2D g, LootEvents.Drop drop, int x, int y, int w) {
        ItemDb.Item item = ItemDb.get().item(drop.id());
        int rarity = Math.max(0, Math.min(4, drop.enchants()));
        Color rc = RARITY_COLOR[rarity];

        g.setColor(PANEL);
        g.fill(new RoundRectangle2D.Float(x, y, w, ITEM_H, 10, 10));

        int ix = x + 14, iy = y + (ITEM_H - ICON_BOX) / 2;
        g.setColor(ICON_BG);
        g.fill(new RoundRectangle2D.Float(ix, iy, ICON_BOX, ICON_BOX, 10, 10));
        g.setColor(rc);
        g.setStroke(new BasicStroke(3f));
        g.draw(new RoundRectangle2D.Float(ix + 1.5f, iy + 1.5f, ICON_BOX - 3, ICON_BOX - 3, 10, 10));
        g.setStroke(new BasicStroke(1f));
        drawSprite(g, drop.id(), ix + 10, iy + 10, ICON_BOX - 20);
        lootgobbler.ui.EnchantBadge.draw(g, ix + ICON_BOX - 7, iy + ICON_BOX - 7, rarity, 10);

        int tx = ix + ICON_BOX + 24, maxW = x + w - tx - 16;
        String name = item != null ? item.name : String.valueOf(assets.IdToAsset.objectName(drop.id()));
        g.setColor(WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
        g.drawString(fit(g, name, maxW), tx, y + 34);

        List<String> tags = new ArrayList<>();
        if (item != null) {
            tags.add(item.slotName());
            if (!item.tierTag().isEmpty()) tags.add(item.tierTag());
            if (item.soulbound) tags.add("Soulbound");
            if (item.isShiny()) tags.add("Shiny");
        }
        tags.add(RARITY[rarity]);
        g.setColor(YELLOW);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        g.drawString(fit(g, String.join("  ·  ", tags), maxW), tx, y + 58);

        int line = y + 82;
        if (item != null) {
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
            if (item.minDamage >= 0) {
                int cx = label(g, "Damage", tx, line, GRAY);
                g.setColor(WHITE);
                g.drawString(item.minDamage + (item.maxDamage > item.minDamage ? "-" + item.maxDamage : ""), cx + 8, line);
            } else if (!item.onEquip.isEmpty()) {
                int cx = label(g, "On Equip", tx, line, GRAY) + 10;
                for (ItemDb.Stat s : item.onEquip) {
                    String t = (s.amount() > 0 ? "+" : "") + s.amount() + " " + s.stat();
                    g.setColor(s.amount() > 0 ? GREEN : RED);
                    g.drawString(t, cx, line);
                    cx += g.getFontMetrics().stringWidth(t) + 14;
                }
            }
            List<String> extra = new ArrayList<>();
            if (item.mpCost >= 0) extra.add("MP Cost " + item.mpCost);
            if (item.feedPower >= 0) extra.add("Feed Power " + item.feedPower);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            g.setColor(DIM);
            if (!extra.isEmpty()) g.drawString(String.join("  ·  ", extra), tx, line + 20);
            if (!item.description.isEmpty()) {
                g.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 12));
                g.drawString(fit(g, item.description, maxW), tx, line + 40);
            }
        }
    }

    private static int label(Graphics2D g, String text, int x, int y, Color c) {
        g.setColor(c);
        g.drawString(text, x, y);
        return x + g.getFontMetrics().stringWidth(text);
    }

    private static void drawSprite(Graphics2D g, int id, int x, int y, int size) {
        try {
            BufferedImage sprite = ImageBuffer.getImage(id);
            if (sprite == null) return;
            Object old = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(sprite, x, y, size, size, null);
            if (old != null) g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, old);
        } catch (Exception ignored) {
            // missing sprite: leave the icon box empty
        }
    }

    private static String fit(Graphics2D g, String s, int maxW) {
        FontMetrics fm = g.getFontMetrics();
        if (fm.stringWidth(s) <= maxW) return s;
        while (!s.isEmpty() && fm.stringWidth(s + "...") > maxW) s = s.substring(0, s.length() - 1);
        return s + "...";
    }

    private LootCard() {
    }
}
