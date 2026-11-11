package lootgobbler.ui;

import javax.swing.*;
import javax.swing.border.LineBorder;
import javax.swing.border.MatteBorder;
import java.awt.*;
import java.util.Locale;

/**
 * A titled panel: a header strip with a small colored square and an upper-case title, above a body.
 * Colors and fonts are re-read from the current theme whenever the look and feel changes.
 */
public class Section extends JPanel {
    private final JPanel header;
    private final JPanel actions;
    private final JLabel title;
    private final JLabel note = new JLabel();
    private final JPanel titleRow;
    private final JComponent body;

    public Section(String name, Color accent, JComponent body) {
        super(new BorderLayout());
        this.body = body;
        header = new JPanel(new BorderLayout());
        title = new JLabel(name.toUpperCase(Locale.ROOT), new Square(accent, 8), SwingConstants.LEFT);
        title.setIconTextGap(8);
        title.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        actions.setOpaque(false);
        titleRow = new JPanel(new BorderLayout());   // the title, and a note after it if there is one
        titleRow.setOpaque(false);
        titleRow.add(title, BorderLayout.WEST);
        titleRow.add(note, BorderLayout.CENTER);
        header.add(titleRow, BorderLayout.WEST);
        header.add(actions, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);
        applyTheme();
    }

    /** Something small right after the title (and its note), vertically centered: e.g. a reset button. */
    public Section afterTitle(JComponent c) {
        JPanel wrap = new JPanel(new GridBagLayout());
        wrap.setOpaque(false);
        wrap.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 6));
        wrap.add(c);
        titleRow.add(wrap, BorderLayout.EAST);
        return this;
    }

    public void setTitle(String name) {
        title.setText(name.toUpperCase(Locale.ROOT));
    }

    /**
     * A hint right after the title, in plain muted text (e.g. "(click the preferred stats)"); empty: none.
     */
    public Section note(String text) {
        note.setText(text);
        note.setForeground(Palette.MUTED);
        return this;
    }

    /**
     * Adds controls to the right end of the header strip.
     */
    public Section actions(JComponent... components) {
        for (JComponent c : components) actions.add(c);
        actions.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 4));
        return this;
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (header != null) applyTheme();   // null while the JPanel constructor runs
    }

    private void applyTheme() {
        Color line = color("Component.borderColor", Color.GRAY);
        setBorder(new LineBorder(line));
        header.setBackground(color("TableHeader.background", getBackground()));
        header.setBorder(new MatteBorder(0, 0, 1, 0, line));
        title.setForeground(color("TableHeader.foreground", getForeground()));
        Font f = UIManager.getFont("TabbedPane.font");
        if (f != null) title.setFont(f.deriveFont(f.getStyle()));
        if (body instanceof JPanel) body.setBackground(color("TextArea.background", getBackground()));
    }

    /**
     * Plain (non-UIResource) copy, so child updateUI() calls don't overwrite it with defaults.
     */
    static Color color(String key, Color fallback) {
        Color c = UIManager.getColor(key);
        return new Color((c == null ? fallback : c).getRGB());
    }

    /**
     * Small filled square used as a marker in headers, channel rows and the status bar.
     */
    public static class Square implements Icon {
        private final Color color;
        private final int size;

        public Square(Color color, int size) {
            this.color = color;
            this.size = size;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            g.setColor(color);
            g.fillRect(x, y, size, size);
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

    /** After each layout: labels in the body that do not fit are wrapped onto more lines (see LabelFit). */
    @Override
    protected void validateTree() {
        super.validateTree();
        LabelFit.fitLater(body);
    }
}
