package lootgobbler.ui;

import com.formdev.flatlaf.util.UIScale;
import lootgobbler.Updater;

import javax.swing.*;
import java.awt.*;

/**
 * Bottom bar: sniffer state and server connection (colored square + text each) on the left; the version (greyed
 * out), Update and Start/Stop Sniffer buttons on the right. Clicking the server state opens the Server tab.
 */
public class StatusBar extends JPanel {
    private static final int GLOW = 7;   // px of glow around UPDATE AVAILABLE

    private final JLabel state = new JLabel();
    private final JLabel server = new JLabel();
    private final JButton sniffer = new JButton();
    private final UpdateButton update = new UpdateButton();

    public StatusBar(Runnable toggleSniffer, Runnable openServer) {
        super(new BorderLayout());
        putClientProperty("FlatLaf.styleClass", "statusBar");
        state.putClientProperty("FlatLaf.styleClass", "statusBar");
        state.setIconTextGap(8);
        sniffer.putClientProperty("FlatLaf.styleClass", "statusButton");
        sniffer.setFocusable(false);
        sniffer.addActionListener(e -> toggleSniffer.run());
        update.addActionListener(e -> Updater.clicked(SwingUtilities.getWindowAncestor(this)));
        Updater.onChange(() -> SwingUtilities.invokeLater(update::refresh));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        buttons.setOpaque(false);
        buttons.setBorder(BorderFactory.createEmptyBorder(3, GLOW, 3, 0));   // room for the glow
        JLabel version = new JLabel(versionText());
        version.putClientProperty("FlatLaf.styleClass", "statusBar");
        Color muted = Palette.MUTED;   // fainter still than muted text: just there if you look for it
        version.setForeground(new Color(muted.getRed(), muted.getGreen(), muted.getBlue(), 110));
        version.setToolTipText(stable() ? "Release version (installed or portable app)"
                : "Development build (run from source, not a published release)");
        version.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 12));
        buttons.add(version);
        buttons.add(update);
        buttons.add(Box.createHorizontalStrut(6));
        buttons.add(sniffer);
        server.putClientProperty("FlatLaf.styleClass", "statusBar");
        server.setIconTextGap(8);
        server.setBorder(BorderFactory.createEmptyBorder(0, 24, 0, 0));
        server.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        Clicks.onClick(server, openServer);
        lootgobbler.GobblerServer.get().onChange(() -> SwingUtilities.invokeLater(this::showServer));
        showServer();
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        left.setOpaque(false);
        left.add(state);
        left.add(server);
        JPanel leftHolder = new JPanel(new GridBagLayout());   // vertically centered like the sniffer state was
        leftHolder.setOpaque(false);
        leftHolder.add(left);
        add(leftHolder, BorderLayout.WEST);
        add(buttons, BorderLayout.EAST);
        setRunning(false);
    }

    /**
     * The Loot Gobbler server: grey when none is set up yet, red when one is set up but not reachable (or still
     * connecting), green when connected.
     */
    private void showServer() {
        lootgobbler.GobblerServer s = lootgobbler.GobblerServer.get();
        String url = s.url();
        Color color;
        String text, tip;
        if (url == null || url.isBlank()) {
            color = Palette.STONE;
            text = "NO SERVER CONFIGURED YET";
            tip = "No Loot Gobbler server set up. Click to open the Server tab.";
        } else if (s.state() == lootgobbler.GobblerServer.State.ONLINE) {
            color = Palette.GREEN;
            text = "CONNECTED";
            lootgobbler.GobblerServer.User u = s.user();
            tip = "Connected to " + url + (u != null ? " as " + u.ign : " (not logged in)") + ". Click to open the Server tab.";
        } else {
            color = Palette.RED;
            text = "NO SERVER CONNECTION";
            String why = s.state() == lootgobbler.GobblerServer.State.CONNECTING ? "connecting..."
                    : s.error() == null || s.error().isBlank() ? "not connected" : s.error();
            tip = url + ": " + why + ". Click to open the Server tab.";
        }
        server.setIcon(new Section.Square(color, 10));
        server.setText(text);
        server.setToolTipText(tip);
    }

    /** E.g. "stable v0.5.5", or "build v0.5.6" when run from source. */
    private static String versionText() {
        return (stable() ? "stable " : "build ") + tomato.version.Version.VERSION;
    }

    /**
     * Whether this is a released app: the packaged exe (installer or portable zip), whose launcher sets
     * jpackage.app-path. Run from source (gradlew run, the IDE, a plain jar) it is a development build.
     */
    private static boolean stable() {
        return System.getProperty("jpackage.app-path") != null;
    }

    public void setRunning(boolean running) {
        SwingUtilities.invokeLater(() -> {
            state.setIcon(new Section.Square(running ? Palette.GREEN : Palette.STONE, 10));
            state.setText(running ? "SNIFFING" : "SNIFFER OFF");
            sniffer.setText(running ? "STOP SNIFFER" : "START SNIFFER");
        });
    }

    /**
     * The pulsing glow behind UPDATE AVAILABLE, painted by the bar so it can reach past the button's edges.
     */
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (!update.glowing()) return;
        Rectangle b = SwingUtilities.convertRectangle(update.getParent(), update.getBounds(), this);
        Graphics2D g2 = (Graphics2D) g.create();
        Color glow = Section.color("LootGobbler.goldHover", Palette.GOLD);
        int size = UIScale.scale(GLOW);
        for (int i = size; i >= 1; i--) {   // rings from the outside in, strongest next to the button
            double falloff = 1 - (i - 1) / (double) size;
            float a = (float) (update.pulse() * 0.45 * falloff * falloff);
            g2.setColor(new Color(glow.getRed(), glow.getGreen(), glow.getBlue(), Math.round(255 * a)));
            g2.fillRect(b.x - i, b.y - i, b.width + 2 * i, b.height + 2 * i);
        }
        g2.dispose();
    }

    /**
     * Update button. Fixed width that fits UPDATE AVAILABLE. Brighter and glowing when an update is available, a
     * progress bar while downloading.
     */
    private class UpdateButton extends JButton {
        private static final String WIDEST = "UPDATE AVAILABLE";
        private final Timer animation = new Timer(40, e -> StatusBar.this.repaint());
        private final Timer revert = new Timer(3000, e -> show(Updater.State.IDLE));
        private Updater.State shown = Updater.State.IDLE;
        private long glowStart;

        UpdateButton() {
            super("CHECK UPDATES");
            putClientProperty("FlatLaf.styleClass", "statusButton");
            setFocusable(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setRolloverEnabled(true);
            revert.setRepeats(false);
            show(Updater.State.IDLE);
        }

        void refresh() {
            show(Updater.state());
        }

        private void show(Updater.State s) {
            revert.stop();
            if (s == Updater.State.FAILED) revert.start();   // brief notice, then "check updates"
            shown = s;
            setText(switch (s) {
                case CHECKING -> "CHECKING...";
                case UP_TO_DATE -> "UP TO DATE";
                case AVAILABLE -> WIDEST;
                case DOWNLOADING -> "DOWNLOADING " + (int) (Updater.progress() * 100) + "%";
                case RESTARTING -> "RESTARTING...";
                case FAILED -> "UPDATE FAILED";
                default -> "CHECK UPDATES";   // not checked yet, or GitHub could not be reached
            });
            String message = Updater.message();
            setToolTipText(message.isBlank() ? "Check for a new Loot Gobbler version" : message);
            if (s == Updater.State.AVAILABLE) {
                if (!animation.isRunning()) {
                    glowStart = System.currentTimeMillis();
                    animation.start();
                }
            } else {
                animation.stop();
            }
            StatusBar.this.repaint();
        }

        boolean glowing() {
            return shown == Updater.State.AVAILABLE;
        }

        /**
         * Glow strength, 0.35 to 1, one slow pulse every 1.6 s.
         */
        double pulse() {
            double t = (System.currentTimeMillis() - glowStart) / 1600.0;
            return 0.35 + 0.65 * (0.5 - 0.5 * Math.cos(2 * Math.PI * t));
        }

        /**
         * A glint of light sliding across the button, left to right, every 3.2 s (it rests for the first half of
         * each cycle): a slanted band, bright in the middle and fading to its edges, like light on a gold coin.
         */
        private void paintShine(Graphics2D g2, int w, int h) {
            double t = ((System.currentTimeMillis() - glowStart) % 3200) / 3200.0;
            if (t < 0.55 || t > 0.85) return;
            double p = (t - 0.55) / 0.30;
            p = p < 0.5 ? 2 * p * p : 1 - 2 * (1 - p) * (1 - p);   // ease in and out
            int band = UIScale.scale(22), slant = h / 3;
            int x = (int) Math.round(-band - slant + p * (w + 2 * band + 2 * slant));
            Graphics2D s = (Graphics2D) g2.create();
            s.clipRect(0, 0, w, h);
            s.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color clear = new Color(255, 250, 225, 0), light = new Color(255, 250, 225, 215);
            s.setPaint(new LinearGradientPaint(x, 0, x + band, 0, new float[]{0f, 0.45f, 0.55f, 1f},
                    new Color[]{clear, light, light, clear}));
            s.fillPolygon(new int[]{x + slant, x + band + slant, x + band - slant, x - slant}, new int[]{0, 0, h, h}, 4);
            s.dispose();
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension d = super.getPreferredSize();
            FontMetrics fm = getFontMetrics(getFont());
            return new Dimension(Math.max(d.width, fm.stringWidth(WIDEST) + UIScale.scale(28)), d.height);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            Color gold = Section.color("LootGobbler.gold", Palette.GOLD);
            Color hover = Section.color("LootGobbler.goldHover", gold);
            Color dark = Section.color("LootGobbler.goldDark", Palette.GOLD_DARK);
            Color onGold = Section.color("LootGobbler.onAccent", Color.BLACK);
            boolean busy = shown == Updater.State.CHECKING || shown == Updater.State.RESTARTING;
            boolean pressed = getModel().isArmed() && getModel().isPressed();

            Color bg;
            if (shown == Updater.State.DOWNLOADING) bg = Section.color("LootGobbler.header", Color.DARK_GRAY);
            else if (busy || pressed) bg = dark;
            else if (shown == Updater.State.AVAILABLE || getModel().isRollover()) bg = hover;
            else bg = gold;
            g2.setColor(bg);
            g2.fillRect(0, 0, w, h);

            FontMetrics fm = g2.getFontMetrics(getFont());
            g2.setFont(getFont());
            String text = getText();
            int tx = (w - fm.stringWidth(text)) / 2, ty = (h - fm.getHeight()) / 2 + fm.getAscent();
            if (shown == Updater.State.DOWNLOADING) {
                // gold fill for the progress; the text is light on the dark part and dark on the gold part
                int fill = (int) Math.round(w * Updater.progress());
                g2.setColor(Section.color("LootGobbler.ink", Color.WHITE));
                g2.drawString(text, tx, ty);
                g2.setColor(gold);
                g2.fillRect(0, 0, fill, h);
                Graphics2D clip = (Graphics2D) g2.create();
                clip.clipRect(0, 0, fill, h);
                clip.setColor(onGold);
                clip.drawString(text, tx, ty);
                clip.dispose();
            } else {
                g2.setColor(onGold);
                g2.drawString(text, tx, ty);
            }
            if (shown == Updater.State.AVAILABLE) paintShine(g2, w, h);
            g2.setColor(Color.BLACK);
            g2.drawRect(0, 0, w - 1, h - 1);
            g2.dispose();
        }
    }
}
