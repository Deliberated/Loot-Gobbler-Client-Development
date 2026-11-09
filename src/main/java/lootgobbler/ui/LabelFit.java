package lootgobbler.ui;

import javax.swing.*;
import javax.swing.plaf.basic.BasicHTML;
import javax.swing.text.View;
import java.awt.*;

/**
 * Keeps every label in a section's body readable: a label wider than the room it has is wrapped onto more lines
 * (as HTML, with a preferred size of that room's width and the wrapped height) instead of being cut off; once it
 * fits again it is a single line again. Run after each layout of the body (see Section); a change only revalidates
 * when the result differs, so it settles after one more layout.
 * <p>
 * The room is measured from the body's right edge, less the width of whatever stands to the label's right on the
 * same line at each level (a value on the right, a button), so it does not depend on the label's own current width.
 * Labels in fixed-size parts (cards and tiles with a set size) are left alone.
 */
final class LabelFit {
    private static final String PLAIN = "LabelFit.plain", HTML = "LabelFit.html";
    private static final int GAP = 6, MIN_ROOM = 40;

    private LabelFit() {
    }

    private static final String PENDING = "LabelFit.pending";
    /** Client property: a label sized by its own view (e.g. a title that shrinks to fit), left alone here. */
    static final String SKIP = "LabelFit.skip";

    /**
     * Fits the body's labels right after the layout in progress (changing them while Swing lays out would be marked
     * valid with it and never laid out again). Several layouts in a row ask once.
     */
    static void fitLater(JComponent body) {
        if (body.getClientProperty(PENDING) != null) return;
        body.putClientProperty(PENDING, Boolean.TRUE);
        SwingUtilities.invokeLater(() -> {
            body.putClientProperty(PENDING, null);
            fit(body);
        });
    }

    static void fit(JComponent body) {
        if (body.getWidth() <= 0 || !body.isShowing() && body.getParent() == null) return;
        visit(body, body);
    }

    private static void visit(Container c, JComponent body) {
        for (Component child : c.getComponents()) {
            if (!child.isVisible()) continue;
            if (child instanceof JLabel l) fitLabel(l, body);
            else if (child instanceof Container k && !(child instanceof JScrollBar)) {
                if (child instanceof JComponent j && j.isPreferredSizeSet() && j.getClientProperty(HTML) == null) continue;
                visit(k, body);
            }
        }
    }

    private static void fitLabel(JLabel l, JComponent body) {
        if (l.getClientProperty(SKIP) != null) return;
        String text = l.getText();
        String ours = (String) l.getClientProperty(HTML);
        if (ours != null && !ours.equals(text)) {   // the view set a new text since: start over with it
            l.putClientProperty(HTML, null);
            l.putClientProperty(PLAIN, null);
            l.setPreferredSize(null);
            return;   // its own revalidation brings it back here
        }
        if (ours == null && l.isPreferredSizeSet()) return;   // sized by the view itself
        String plain = ours != null ? (String) l.getClientProperty(PLAIN) : text;
        if (plain == null || plain.isBlank()) return;
        boolean isHtml = BasicHTML.isHTMLString(plain);
        String html = isHtml ? plain : "<html>" + escape(plain) + "</html>";

        Insets in = l.getInsets();
        Icon icon = l.getIcon();
        int iconW = icon == null ? 0 : icon.getIconWidth() + l.getIconTextGap();
        int natural = ours == null ? l.getPreferredSize().width   // its one-line width as the view set it
                : SwingUtilities.computeStringWidth(l.getFontMetrics(l.getFont()), plain) + iconW + in.left + in.right;
        if (ours == null) {   // only labels that are cut off: narrower than their text, or past the body's edge
            int rightEdge = SwingUtilities.convertPoint(l.getParent(), l.getX() + l.getWidth(), 0, body).x;
            boolean cut = l.getWidth() < natural || rightEdge > body.getWidth() - body.getInsets().right;
            if (!cut) return;
        }
        int room = room(l, body);
        if (room < MIN_ROOM) return;
        View view = BasicHTML.createHTMLView(l, html);
        if (isHtml && ours != null) natural = (int) Math.ceil(view.getPreferredSpan(View.X_AXIS)) + iconW + in.left + in.right;

        if (natural <= room) {   // fits on one line
            if (ours != null) {
                l.putClientProperty(HTML, null);
                l.putClientProperty(PLAIN, null);
                l.setPreferredSize(null);
                l.setText(plain);
            }
            return;
        }
        int textW = Math.max(10, room - iconW - in.left - in.right);
        view.setSize(textW, 0);
        int h = (int) Math.ceil(view.getPreferredSpan(View.Y_AXIS));
        if (icon != null) h = Math.max(h, icon.getIconHeight());
        Dimension want = new Dimension(room, h + in.top + in.bottom);
        if (html.equals(ours) && want.equals(l.getPreferredSize())) return;   // already so
        l.putClientProperty(PLAIN, plain);
        l.putClientProperty(HTML, html);
        if (!html.equals(text)) l.setText(html);
        l.setPreferredSize(want);
        l.revalidate();
    }

    /**
     * The width the label may take: from its left edge to the body's right edge, less what stands to its right on
     * the same line, at every level up to the body (not counting rows that wrap on their own, see WrapLayout).
     */
    private static int room(JLabel l, JComponent body) {
        int x = SwingUtilities.convertPoint(l.getParent(), l.getX(), 0, body).x;
        int right = body.getWidth() - body.getInsets().right;
        int reserved = 0;
        Component c = l;
        while (c != null && c != body) {
            Container p = c.getParent();
            if (p == null) break;
            if (!(p.getLayout() instanceof WrapLayout)) {
                for (Component s : p.getComponents()) {
                    if (s == c || !s.isVisible() || s.getWidth() <= 0) continue;
                    boolean sameLine = s.getY() < c.getY() + Math.max(1, c.getHeight()) && s.getY() + s.getHeight() > c.getY();
                    if (sameLine && s.getX() >= c.getX() + 1) reserved += s.getPreferredSize().width + GAP;
                }
            }
            // inner panels may be sized by their content, so only a scroll pane's visible part bounds it further
            if (p instanceof JViewport v) right = Math.min(right, SwingUtilities.convertPoint(v, v.getWidth(), 0, body).x);
            c = p;
        }
        return right - x - reserved;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>");
    }
}
