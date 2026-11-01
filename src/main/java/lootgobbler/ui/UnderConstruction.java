package lootgobbler.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.LayerUI;
import java.awt.*;
import java.awt.event.InputEvent;

/**
 * Shows a page greyed out under a notice, and blocks every click and key press on it.
 */
public final class UnderConstruction {
    private UnderConstruction() {
    }

    public static JComponent wrap(JComponent page, String message) {
        JLabel notice = new JLabel(message, SwingConstants.CENTER);
        notice.setFont(notice.getFont().deriveFont(Font.BOLD, 18f));
        notice.setForeground(Palette.GOLD);
        notice.setBorder(new EmptyBorder(10, 10, 10, 10));

        JLayer<JComponent> layer = new JLayer<>(page, new LayerUI<>() {
            @Override
            public void installUI(JComponent c) {
                super.installUI(c);
                ((JLayer<?>) c).setLayerEventMask(AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_MOTION_EVENT_MASK
                        | AWTEvent.MOUSE_WHEEL_EVENT_MASK | AWTEvent.KEY_EVENT_MASK);
            }

            @Override
            public void uninstallUI(JComponent c) {
                ((JLayer<?>) c).setLayerEventMask(0);
                super.uninstallUI(c);
            }

            @Override
            public void eventDispatched(AWTEvent e, JLayer<? extends JComponent> l) {
                if (e instanceof InputEvent ie) ie.consume();   // nothing on the page can be used
            }

            @Override
            public void paint(Graphics g, JComponent c) {
                super.paint(g, c);
                Color bg = Section.color("Panel.background", Color.GRAY);
                g.setColor(new Color(bg.getRed(), bg.getGreen(), bg.getBlue(), 170));   // grey wash over the page
                g.fillRect(0, 0, c.getWidth(), c.getHeight());
            }
        });

        JPanel p = new JPanel(new BorderLayout());
        p.add(notice, BorderLayout.NORTH);
        p.add(layer, BorderLayout.CENTER);
        return p;
    }
}
