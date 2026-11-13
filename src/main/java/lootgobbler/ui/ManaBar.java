package lootgobbler.ui;

import lootgobbler.PlayerVitals;

import javax.swing.*;
import java.awt.*;

/**
 * The mana overlay: a blue bar filled as far as your mana goes, with "MP points / maximum" on it, in a dark rounded
 * box. Always the same size. Hidden while the game reports no character.
 */
final class ManaBar extends JComponent {
    private static final int W = 170, H = 30, PAD = 5;
    private static final Color BOX = new Color(0x2A2620), EDGE = new Color(0x5A544A), TRACK = new Color(0x161B30),
            FILL_TOP = new Color(0x4C86F0), FILL_BOTTOM = new Color(0x1E4CB8), TEXT = new Color(0xF2ECDC);
    private int mp = -1, max = -1;

    /** sample: fixed values, to show what it looks like; else your live mana. */
    ManaBar(boolean sample) {
        setFont(UIManager.getFont("Label.font").deriveFont(Font.BOLD));
        if (sample) {
            mp = 182;
            max = 255;
            return;
        }
        setVisible(false);
        new Timer(100, e -> update()).start();   // mana changes every tick, without an event
    }

    private void update() {
        boolean on = PlayerVitals.fresh();
        int m = PlayerVitals.mp(), x = PlayerVitals.maxMp();
        if (on && (m != mp || x != max)) {
            mp = m;
            max = x;
            repaint();
        }
        if (on != isVisible()) {
            setVisible(on);
            if (getParent() != null) getParent().revalidate();
        }
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(W, H);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int w = getWidth() - 1, h = getHeight() - 1;
        g2.setColor(BOX);
        g2.fillRoundRect(0, 0, w, h, 10, 10);
        g2.setColor(EDGE);
        g2.drawRoundRect(0, 0, w, h, 10, 10);
        int bw = w - 2 * PAD, bh = h - 2 * PAD;
        g2.setColor(TRACK);
        g2.fillRoundRect(PAD, PAD, bw, bh, 6, 6);
        double share = max <= 0 ? 0 : Math.max(0, Math.min(1, mp / (double) max));
        int fw = (int) Math.round(bw * share);
        if (fw > 0) {
            g2.setPaint(new GradientPaint(0, PAD, FILL_TOP, 0, PAD + bh, FILL_BOTTOM));
            g2.fillRoundRect(PAD, PAD, fw, bh, 6, 6);
        }
        String text = "MP " + Math.max(0, mp) + " / " + Math.max(0, max);
        FontMetrics fm = g2.getFontMetrics(getFont());
        g2.setFont(getFont());
        int tx = (getWidth() - fm.stringWidth(text)) / 2, ty = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
        g2.setColor(new Color(0, 0, 0, 150));
        g2.drawString(text, tx + 1, ty + 1);
        g2.setColor(TEXT);
        g2.drawString(text, tx, ty);
        g2.dispose();
    }
}
