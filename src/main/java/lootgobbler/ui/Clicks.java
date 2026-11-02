package lootgobbler.ui;

import javax.swing.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Click handling that tolerates a moving mouse. Swing's mouseClicked only fires when press and release happen on the
 * very same pixel; this fires when the left button is pressed and released anywhere on the component.
 */
public final class Clicks {
    private Clicks() {
    }

    public static void onClick(JComponent c, Runnable action) {
        onClick(c, e -> action.run());
    }

    /**
     * Same, with the release event, for components that look up what is under the mouse (tables, painted maps).
     */
    public static void onClick(JComponent c, java.util.function.Consumer<MouseEvent> action) {
        c.addMouseListener(new MouseAdapter() {
            private boolean pressed;

            @Override
            public void mousePressed(MouseEvent e) {
                pressed = SwingUtilities.isLeftMouseButton(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                boolean click = pressed && SwingUtilities.isLeftMouseButton(e) && c.contains(e.getPoint());
                pressed = false;
                if (click) action.accept(e);
            }
        });
    }
}
