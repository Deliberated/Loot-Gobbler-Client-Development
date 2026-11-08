package lootgobbler.ui;

import javax.swing.*;
import java.awt.*;

/**
 * Holder for scrolled content that always takes the viewport's width: it only ever scrolls vertically, and text
 * too long for the column is wrapped onto more lines (LabelFit) instead of widening the column or being cut off.
 * The content sits at the top (BorderLayout.NORTH).
 */
public class FitWidthPanel extends JPanel implements Scrollable {
    public FitWidthPanel(JComponent content) {
        super(new BorderLayout());
        add(content, BorderLayout.NORTH);
    }

    /**
     * A vertical-only scroll pane around the content, without border.
     */
    public static JScrollPane scroll(JComponent content, int unitIncrement) {
        JScrollPane sp = new JScrollPane(new FitWidthPanel(content), ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setBorder(null);
        sp.getVerticalScrollBar().setUnitIncrement(unitIncrement);
        return sp;
    }

    /** After each layout: text too long for the column is wrapped onto more lines (see LabelFit). */
    @Override
    protected void validateTree() {
        super.validateTree();
        LabelFit.fitLater(this);
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
        return 16;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
        return orientation == SwingConstants.VERTICAL ? visible.height : visible.width;
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return getParent() instanceof JViewport v && v.getHeight() > getPreferredSize().height;
    }
}
