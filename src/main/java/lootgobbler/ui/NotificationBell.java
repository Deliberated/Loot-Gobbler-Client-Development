package lootgobbler.ui;

import lootgobbler.Notifications;
import lootgobbler.Notifications.Notification;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.List;

/**
 * The bell at the right end of the tab row. A red dot shows that something new happened; opening the bell lists
 * the notifications and takes the dot away. Clicking one goes to where it can be dealt with.
 */
public final class NotificationBell extends JComponent {
    private static final int SIZE = 36;
    private boolean hover;

    public NotificationBell() {
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setToolTipText("Notifications");
        Clicks.onClick(this, this::open);
        addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseEntered(java.awt.event.MouseEvent e) {
                hover = true;
                repaint();
            }

            @Override
            public void mouseExited(java.awt.event.MouseEvent e) {
                hover = false;
                repaint();
            }
        });
        Notifications.get().onChange(() -> SwingUtilities.invokeLater(this::repaint));
    }

    private void open() {
        Notifications.get().markRead();
        JPopupMenu menu = new JPopupMenu();
        List<Notification> all = Notifications.get().all();
        if (all.isEmpty()) {
            JLabel none = new JLabel("No notifications");
            none.setBorder(new EmptyBorder(10, 14, 10, 14));
            none.setForeground(Palette.MUTED);
            menu.add(none);
        }
        for (Notification n : all) {
            JMenuItem item = new JMenuItem("<html><b>" + esc(n.title()) + "</b><br><span style='color:#9a9a9a'>"
                    + esc(n.text()) + "</span></html>");
            item.setBorder(new EmptyBorder(6, 8, 6, 12));
            item.addActionListener(e -> n.open().run());
            menu.add(item);
        }
        menu.show(this, getWidth() - menu.getPreferredSize().width, getHeight());
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(SIZE, SIZE);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(SIZE, Integer.MAX_VALUE);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        double cx = getWidth() / 2.0, cy = getHeight() / 2.0;
        Color fg = UIManager.getColor("Label.foreground");
        if (fg == null) fg = Color.LIGHT_GRAY;
        g2.setColor(hover ? fg : new Color(fg.getRed(), fg.getGreen(), fg.getBlue(), 190));

        // the bell: a dome widening to a flared rim, the clapper below, a small knob on top
        Path2D bell = new Path2D.Double();
        bell.moveTo(cx - 7, cy + 4);
        bell.curveTo(cx - 6, cy + 1, cx - 6, cy - 1, cx - 6, cy - 3);
        bell.curveTo(cx - 6, cy - 7, cx - 3.5, cy - 9, cx, cy - 9);
        bell.curveTo(cx + 3.5, cy - 9, cx + 6, cy - 7, cx + 6, cy - 3);
        bell.curveTo(cx + 6, cy - 1, cx + 6, cy + 1, cx + 7, cy + 4);
        bell.closePath();
        g2.fill(bell);
        g2.fill(new RoundRectangle2D.Double(cx - 8.5, cy + 3.5, 17, 2.5, 2, 2));
        g2.fillOval((int) Math.round(cx - 2.5), (int) Math.round(cy + 6.5), 5, 4);
        g2.fillOval((int) Math.round(cx - 1.5), (int) Math.round(cy - 11), 3, 3);

        if (Notifications.get().unread()) {
            int d = 8, x = (int) Math.round(cx + 3), y = (int) Math.round(cy - 11);
            Container bg = getParent();
            while (bg != null && !bg.isOpaque()) bg = bg.getParent();
            g2.setColor(bg != null ? bg.getBackground() : Color.DARK_GRAY);
            g2.fillOval(x - 2, y - 2, d + 4, d + 4);   // a ring in the background color sets the dot off the bell
            g2.setColor(new Color(0xE5484D));
            g2.fillOval(x, y, d, d);
        }
        g2.dispose();
    }
}
