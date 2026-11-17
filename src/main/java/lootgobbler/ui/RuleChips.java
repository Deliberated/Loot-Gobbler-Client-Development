package lootgobbler.ui;

import lootgobbler.Upe;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * A game mode's rules at a glance, as small tags: what its loadout may hold (UT, ST, tiered, shiny, divine) and where
 * things may come from (own drops, the vault, trades, the shop): allowed with a green tick, else crossed through in red.
 */
final class RuleChips {
    private RuleChips() {
    }

    /** One tag: its words, its color, and whether that is allowed. */
    record Chip(String text, Paint fill, Color edge, Color ink, boolean allowed, String tip, boolean sparkle) {
        Chip(String text, Paint fill, Color edge, Color ink, boolean allowed, String tip) {
            this(text, fill, edge, ink, allowed, tip, false);
        }
    }

    private static final Color UT = new Color(0x8E4FD0), ST = new Color(0xD9A21B), TIERED = new Color(0x5F6B7A),
            DIVINE = new Color(0xF2C232), SOURCE = new Color(0x4D5A3A), BAD = new Color(0x6B3530);

    private static Chip chip(String text, Color fill, Color ink, boolean allowed, String tip) {
        return new Chip(text, fill, fill.brighter(), ink, allowed, tip);
    }

    /** What the loadout of a run in this mode may hold. */
    static List<Chip> loadout(String mode) {
        String b = Upe.base(mode);
        List<Chip> l = new ArrayList<>();
        Paint shiny = new LinearGradientPaint(0, 0, 60, 0, new float[]{0f, 0.33f, 0.66f, 1f},
                new Color[]{new Color(0xFF7EC8), new Color(0xFFE27A), new Color(0x7DF3E1), new Color(0x8FA8FF)});
        switch (b) {
            case Upe.PPE -> {
                l.add(chip("Tiered", TIERED, Color.WHITE, true, "Tiered items (T0 to T14) may be equipped"));
                l.add(chip("UT", UT, Color.WHITE, true, "Untiered items may be equipped"));
                l.add(chip("ST", ST, new Color(0x3A2604), true, "Set items may be equipped"));
            }
            case Upe.PUPE -> {
                l.add(chip("UT", UT, Color.WHITE, true, "Only untiered (UT) items in the loadout"));
                l.add(chip("ST", ST, new Color(0x3A2604), false, "No set items (ST) in the loadout"));
                l.add(chip("Tiered", TIERED, Color.WHITE, false, "No tiered items in the loadout"));
            }
            case Upe.STPE -> {
                l.add(chip("ST", ST, new Color(0x3A2604), true, "Only set items (ST) in the loadout"));
                l.add(chip("UT", UT, Color.WHITE, false, "No untiered (UT) items in the loadout"));
                l.add(chip("Tiered", TIERED, Color.WHITE, false, "No tiered items in the loadout"));
            }
            case Upe.SHINY_UPE -> {
                l.add(new Chip("UT", shiny, Color.WHITE, new Color(0x3A2350), true, "Only shiny untiered (UT) items in the loadout", true));
                l.add(chip("ST", ST, new Color(0x3A2604), false, "No set items (ST): they have no shiny versions"));
                l.add(chip("Tiered", TIERED, Color.WHITE, false, "No tiered items in the loadout"));
            }
            case Upe.DIVINE_PE -> {
                l.add(chip("Divine", DIVINE, new Color(0x4A2E00), true, "Only divine items (four enchantment slots), of any tier"));
                l.add(chip("Common to Legendary", TIERED, Color.WHITE, false, "Nothing with fewer than four enchantment slots"));
            }
            default -> {   // UPE
                l.add(chip("UT", UT, Color.WHITE, true, "Untiered items may be equipped"));
                l.add(chip("ST", ST, new Color(0x3A2604), true, "Set items may be equipped"));
                l.add(chip("Tiered", TIERED, Color.WHITE, false, "No tiered items in the loadout"));
            }
        }
        l.add(chip("T0 start gear", TIERED.darker(), Color.WHITE, true, "The tier 0 gear it was created with never counts against the rules"));
        return l;
    }

    /** Where what it equips (and drinks) may come from. */
    static List<Chip> sources(boolean guild) {
        List<Chip> l = new ArrayList<>();
        l.add(chip("Own drops", new Color(0x3C9347), Color.WHITE, true, "Loot bags it got in the run, and what it forged from them"));
        l.add(chip("Vault", SOURCE, Color.WHITE, false, "Nothing from the vault, gift chest or potion storage in the loadout, no stat potions from there"));
        l.add(guild ? chip("Guild trades", new Color(0xB07D0A), Color.WHITE, true, "Trades with guild mates on a guild run, of things their runs gathered")
                : chip("Trades", SOURCE, Color.WHITE, false, "No trading"));
        l.add(chip("Shop & rewards", SOURCE, Color.WHITE, false, "Nothing bought, no login rewards, reward chests or battle pass milestones"));
        return l;
    }

    /** A row of tags after a small caption. */
    static JComponent row(String caption, List<Chip> chips) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        p.setOpaque(false);
        JLabel c = new JLabel(caption);
        c.setFont(c.getFont().deriveFont(16f));
        c.setForeground(Palette.MUTED);
        c.setPreferredSize(new Dimension(90, c.getPreferredSize().height));
        p.add(c);
        for (Chip ch : chips) p.add(new Tag(ch));
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));   // no taller in a column
        return p;
    }

    /** One tag drawn: a rounded plate with its words and a tick or cross; struck out and faded if not allowed. */
    private static final class Tag extends JComponent {
        private static final int H = 28, PAD = 9, MARK = 16, STAR = 14;
        private final Chip chip;

        Tag(Chip chip) {
            this.chip = chip;
            setFont(UIManager.getFont("Label.font").deriveFont(16f));
            FontMetrics fm = getFontMetrics(getFont());
            int mark = chip.allowed() ? 6 + MARK - 2 : 0;   // the tick; a forbidden tag is struck through instead
            int star = chip.sparkle() ? STAR + 4 : 0;
            setPreferredSize(new Dimension(PAD + star + fm.stringWidth(chip.text()) + mark + PAD, H + 2));
            setToolTipText(chip.tip());
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth() - 1;
            RoundRectangle2D plate = new RoundRectangle2D.Float(0.5f, 1, w, H - 1, 9, 9);
            if (!chip.allowed()) g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.8f));
            Graphics2D f = (Graphics2D) g.create();
            f.translate(0.5, 1);   // a gradient fill (shiny) starts at the tag's left
            f.setPaint(chip.fill());
            f.fill(new RoundRectangle2D.Float(0, 0, w, H - 1, 9, 9));
            f.dispose();
            g.setColor(chip.edge());
            g.draw(plate);
            g.setFont(getFont());
            FontMetrics fm = g.getFontMetrics();
            int tx = PAD;
            if (chip.sparkle()) {   // a shiny star before the words
                float cx = PAD + STAR / 2f, cy = 1 + (H - 1) / 2f, r = STAR / 2f, k = r * 0.24f;
                java.awt.geom.Path2D st = new java.awt.geom.Path2D.Float();
                st.moveTo(cx, cy - r);
                st.lineTo(cx + k, cy - k);
                st.lineTo(cx + r, cy);
                st.lineTo(cx + k, cy + k);
                st.lineTo(cx, cy + r);
                st.lineTo(cx - k, cy + k);
                st.lineTo(cx - r, cy);
                st.lineTo(cx - k, cy - k);
                st.closePath();
                g.setColor(Color.WHITE);
                g.fill(st);
                g.setStroke(new BasicStroke(1.4f));
                g.setColor(new Color(0x4B2F8F));
                g.draw(st);
                tx += STAR + 4;
            }
            g.setColor(chip.ink());
            g.drawString(chip.text(), tx, 1 + (H - 1 + fm.getAscent() - fm.getDescent()) / 2f);
            g.setComposite(AlphaComposite.SrcOver);
            if (chip.allowed()) {   // a green tick
                float mx = w - PAD + 2 - MARK, my = 1 + (H - 1 - MARK) / 2f;
                g.setColor(new Color(0x2E8B3E));
                g.fill(new Ellipse2D.Float(mx, my, MARK, MARK));
                g.setColor(Color.WHITE);
                g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(new java.awt.geom.Line2D.Float(mx + 4, my + 8.5f, mx + 7, my + 11.5f));
                g.draw(new java.awt.geom.Line2D.Float(mx + 7, my + 11.5f, mx + 12, my + 5));
            } else {   // crossed through: a bold red slash over the whole tag
                java.awt.geom.Line2D slash = new java.awt.geom.Line2D.Float(3, H - 2, w - 3, 3);
                g.setStroke(new BasicStroke(4.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.setColor(new Color(0x5A1410));
                g.draw(slash);
                g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.setColor(new Color(0xFF4A3D));
                g.draw(slash);
            }
            g.dispose();
        }
    }
}
