package lootgobbler.ui;

import lootgobbler.Themes;
import lootgobbler.Updater;
import lootgobbler.Updater.Notes;
import tomato.version.Version;
import util.PropertiesManager;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.event.HyperlinkEvent;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.net.URI;
import java.util.List;

/**
 * Shown once after Loot Gobbler was updated (in-app or with a new setup): the version change and the release notes
 * of every version in between, from the GitHub releases. Also opened from Info > Release Notes with every release.
 */
public class WhatsNewDialog extends JDialog {
    private static final String KEY_LAST_RUN = "lastRunVersion";
    private JEditorPane text;

    /** from: the version updated from; null for every release (Info > Release Notes), notes null while loading. */
    private WhatsNewDialog(Window owner, String from, List<Notes> notes, String error) {
        super(owner, from == null ? "Release Notes" : "Loot Gobbler updated", ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        JPanel box = new JPanel(new BorderLayout(0, 14));
        box.setBackground(ui("paper"));
        box.setBorder(new CompoundBorder(new LineBorder(ui("line"), 2), new EmptyBorder(22, 28, 20, 28)));

        JPanel head = new JPanel();
        head.setOpaque(false);
        head.setLayout(new BoxLayout(head, BoxLayout.Y_AXIS));
        JLabel title = new JLabel(from == null ? "Release Notes" : "Loot Gobbler was updated!");
        title.setFont(new Font(Themes.HEAD_FONT, Font.PLAIN, 40));
        title.setForeground(ui("gold"));
        // old -> new; the arrow is drawn, the pixel font has no arrow character
        Font versionFont = new Font(Themes.BODY_FONT, Font.PLAIN, 28);
        JLabel versions = new JLabel(from, new Arrow(ui("gold"), 22), SwingConstants.LEFT);
        versions.setHorizontalTextPosition(SwingConstants.LEFT);   // text, then the arrow
        versions.setIconTextGap(12);
        versions.setFont(versionFont);
        versions.setForeground(ui("ink"));
        JLabel to = new JLabel(Version.VERSION);
        to.setFont(versionFont);
        to.setForeground(ui("ink"));
        JPanel versionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        versionRow.setOpaque(false);
        versionRow.setAlignmentX(LEFT_ALIGNMENT);
        versionRow.add(versions);
        versionRow.add(Box.createHorizontalStrut(12));
        versionRow.add(to);
        title.setAlignmentX(LEFT_ALIGNMENT);
        head.add(title);
        head.add(Box.createVerticalStrut(4));
        if (from != null) {
            head.add(versionRow);
        } else {   // every release: which one you have
            JLabel yours = new JLabel("Every release, the latest first. You have " + Version.VERSION + ".");
            yours.setFont(new Font(Themes.BODY_FONT, Font.PLAIN, 22));
            yours.setForeground(ui("ink"));
            yours.setAlignmentX(LEFT_ALIGNMENT);
            head.add(yours);
        }
        box.add(head, BorderLayout.NORTH);

        text = new JEditorPane("text/html", notes == null ? "<html><body><p>Loading the release notes...</p></body></html>"
                : html(notes, error));
        text.setEditable(false);
        text.setFocusable(false);   // read-only: no blinking caret (links still work)
        text.setOpaque(false);
        text.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true);
        text.setFont(new Font(Themes.BODY_FONT, Font.PLAIN, 20));
        text.setForeground(ui("ink"));
        text.addHyperlinkListener(e -> {
            if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED && e.getURL() != null) open(e.getURL().toString());
        });
        JScrollPane scroll = new JScrollPane(text);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setPreferredSize(new Dimension(1050, from == null ? 520 : 330));   // wide enough that most notes fit on one line
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        box.add(scroll, BorderLayout.CENTER);

        JButton all = new JButton(from == null ? "ON GITHUB" : "ALL RELEASE NOTES");
        all.addActionListener(e -> {
            if (from == null) open(Updater.releasesPage());
            else showAll(owner);
        });
        JButton ok = new JButton(from == null ? "CLOSE" : "NICE");
        ok.putClientProperty("FlatLaf.styleClass", "primary");
        ok.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(all);
        buttons.add(ok);
        box.add(buttons, BorderLayout.SOUTH);

        JPanel outer = new JPanel(new BorderLayout());
        outer.setBorder(new EmptyBorder(16, 16, 16, 16));
        outer.add(box);
        setContentPane(outer);
        getRootPane().setDefaultButton(ok);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        pack();
        SwingUtilities.invokeLater(() -> text.scrollRectToVisible(new Rectangle(0, 0, 1, 1)));   // start at the top
        setLocationRelativeTo(owner);
    }

    /**
     * On start, before the welcome dialog: if this version is newer than the one that ran last, fetch the notes in
     * the background and show them. The first start ever only remembers the version (the welcome dialog covers
     * that). Versions before 0.4.3 did not remember theirs: if you used Loot Gobbler before (the welcome dialog was
     * shown), the notes of this version are shown, coming from the release before it. then runs once they are
     * closed, or at once when there are none to show (e.g. the welcome and the Newcomer's Guide come after them).
     */
    public static void showAfterUpdate(Window owner, Runnable then) {
        String last = PropertiesManager.getProperty(KEY_LAST_RUN);
        boolean usedBefore = "true".equals(PropertiesManager.getProperty("welcomeShown"));
        PropertiesManager.setProperties(KEY_LAST_RUN, Version.VERSION);
        boolean known = last != null && !last.isBlank();
        if (known ? Updater.compare(Version.VERSION, last) <= 0 : !usedBefore) {
            SwingUtilities.invokeLater(then);
            return;
        }
        Thread t = new Thread(() -> {
            List<Notes> notes = List.of();
            String from = known ? last : "an older version";
            String error = null;
            try {
                if (known) {
                    notes = Updater.releaseNotes(last, Version.VERSION);
                } else {   // unknown old version: this release's notes, from the release before it
                    List<Notes> all = Updater.releaseNotes("v0", Version.VERSION);
                    notes = all.stream().filter(n -> Updater.compare(n.version(), Version.VERSION) == 0).toList();
                    if (all.size() > 1) from = all.get(1).version();
                }
            } catch (Exception e) {
                error = "The release notes could not be loaded right now.";
            }
            List<Notes> shown = notes;
            String old = from, problem = error;
            SwingUtilities.invokeLater(() -> {
                new WhatsNewDialog(owner, old, shown, problem).setVisible(true);   // modal: returns once closed
                then.run();
            });
        }, "whats-new");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Info > Release Notes: every published release, the latest at the top. Opens at once and fills in when GitHub
     * answers.
     */
    public static void showAll(Window owner) {
        WhatsNewDialog dialog = new WhatsNewDialog(owner, null, null, null);
        Thread t = new Thread(() -> {
            List<Notes> notes = List.of();
            String error = null;
            try {
                notes = Updater.releaseNotes("v0", "v999999");
            } catch (Exception e) {
                error = "The release notes could not be loaded right now. Check your connection, or open them on GitHub.";
            }
            String html = html(notes, error);
            SwingUtilities.invokeLater(() -> {
                dialog.text.setText(html);
                SwingUtilities.invokeLater(() -> dialog.text.scrollRectToVisible(new Rectangle(0, 0, 1, 1)));
            });
        }, "release-notes");
        t.setDaemon(true);
        t.start();
        dialog.setVisible(true);
    }

    /**
     * The notes as HTML: per version a heading with its date, then the notes, whose "- " lines become a list.
     */
    private static String html(List<Notes> notes, String error) {
        StringBuilder sb = new StringBuilder("<html><body>");
        if (error != null) sb.append("<p>").append(escape(error)).append("</p>");
        else if (notes.isEmpty()) sb.append("<p>No release notes were published for this version.</p>");
        for (Notes n : notes) {
            // the date at full size: shrunk, the pixel font draws a 6 that looks like an 8
            String date = date(n.published());
            sb.append("<h2>").append(escape(n.version())).append(date.isEmpty() ? ""
                    : "&nbsp;&nbsp;<font color='#938B79'>" + date + "</font>").append("</h2>");
            boolean list = false;
            for (String raw : n.body().split("\\r?\\n")) {
                String line = raw.trim();
                boolean item = line.startsWith("- ") || line.startsWith("* ");
                if (item && !list) sb.append("<ul>");
                if (!item && list) sb.append("</ul>");
                list = item;
                if (item) sb.append("<li>").append(inline(line.substring(2))).append("</li>");
                else if (line.startsWith("#")) sb.append("<b>").append(inline(line.replaceFirst("^#+\\s*", ""))).append("</b><br>");
                else if (!line.isEmpty()) sb.append(inline(line)).append("<br>");
            }
            if (list) sb.append("</ul>");
        }
        return sb.append("</body></html>").toString();
    }

    /**
     * GitHub's publish time ("2026-11-01T15:44:44Z") as "1 Nov 2026" in local time, or "" if it cannot be read.
     */
    private static String date(String published) {
        try {
            return java.time.Instant.parse(published).atZone(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH));
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Escapes a line and keeps the bits of Markdown used in release notes: **bold**, `code` and [links](url).
     */
    private static String inline(String s) {
        return escape(s).replaceAll("\\*\\*(.+?)\\*\\*", "<b>$1</b>").replaceAll("`(.+?)`", "<code>$1</code>")
                .replaceAll("\\[(.+?)]\\((https?://[^)\\s]+)\\)", "<a href='$2'>$1</a>");
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static void open(String url) {
        try {
            Desktop.getDesktop().browse(URI.create(url));
        } catch (Exception ignore) {
        }
    }

    /**
     * A right-pointing arrow in blocky pixel style: a shaft and a stepped head.
     */
    private static class Arrow implements Icon {
        private final Color color;
        private final int size;

        Arrow(Color color, int size) {
            this.color = color;
            this.size = size;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            int p = Math.max(2, size / 8);              // one "pixel" of the arrow
            int mid = y + size / 2 - p / 2;
            g.setColor(color);
            g.fillRect(x, mid, size - 2 * p, p);        // shaft
            for (int i = 0; i < 4; i++) {               // head, narrowing towards the tip
                int hx = x + size - (4 - i) * p;
                g.fillRect(hx, mid - (3 - i) * p, p, (2 * (3 - i) + 1) * p);
            }
        }

        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size;
        }
    }

    private static Color ui(String key) {
        Color c = UIManager.getColor("LootGobbler." + key);
        return c != null ? c : Color.GRAY;
    }
}
