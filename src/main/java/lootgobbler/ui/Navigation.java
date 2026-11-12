package lootgobbler.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Back and forward through the views you opened (main tabs, pages inside them, the character selected...), with the
 * mouse's back and forward buttons, as in a browser.
 * <p>
 * Every view change you make is recorded as how to show that view again (visit). Back shows the one before, forward
 * the one after; opening something new after going back drops what was ahead. Showing a view again records nothing.
 */
final class Navigation {
    private static final int MAX = 60;
    private static final int BACK_BUTTON = 4, FORWARD_BUTTON = 5;

    private record Entry(String key, Runnable show) {
    }

    private static final List<Entry> back = new ArrayList<>(), ahead = new ArrayList<>();
    private static Entry current;
    private static boolean restoring;

    private Navigation() {
    }

    /**
     * Records that this view is now shown: key tells views apart (the same key twice in a row is one visit), show
     * brings it back.
     */
    static void visit(String key, Runnable show) {
        if (restoring) return;
        if (current != null && current.key().equals(key)) return;
        if (current != null) {
            back.add(current);
            if (back.size() > MAX) back.remove(0);
        }
        ahead.clear();
        current = new Entry(key, show);
    }

    static void back() {
        if (back.isEmpty()) return;
        if (current != null) ahead.add(current);
        current = back.remove(back.size() - 1);
        replay(current);
    }

    static void forward() {
        if (ahead.isEmpty()) return;
        if (current != null) back.add(current);
        current = ahead.remove(ahead.size() - 1);
        replay(current);
    }

    private static void replay(Entry e) {
        restoring = true;
        try {
            e.show().run();
        } finally {
            restoring = false;
        }
    }

    /** Makes the tabs and pages around c show it (a view inside another tab or page). */
    static void reveal(Component c) {
        for (Container p = c.getParent(); p != null; p = p.getParent()) {
            if (p instanceof SubTabs s) s.reveal(c);
            if (p instanceof TabBar t) t.reveal(c);
        }
    }

    /** Listens to the mouse's back and forward buttons everywhere in the app. */
    static void install() {
        Toolkit.getDefaultToolkit().addAWTEventListener(e -> {
            if (!(e instanceof MouseEvent m) || m.getID() != MouseEvent.MOUSE_RELEASED) return;
            if (m.getButton() == BACK_BUTTON) SwingUtilities.invokeLater(Navigation::back);
            else if (m.getButton() == FORWARD_BUTTON) SwingUtilities.invokeLater(Navigation::forward);
        }, AWTEvent.MOUSE_EVENT_MASK);
    }
}
