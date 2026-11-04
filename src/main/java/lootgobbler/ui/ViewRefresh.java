package lootgobbler.ui;

import javax.swing.*;
import java.awt.event.HierarchyEvent;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Refreshes a view only when it can be seen. Data changes (from any thread) are collected: a view on screen
 * refreshes once on the UI thread however many changes came in meanwhile; a hidden view (another tab) only notes
 * that it is out of date and refreshes when it is shown again. So pages in other tabs do not rebuild in the
 * background every few seconds during play and keep the UI thread free for clicks.
 */
final class ViewRefresh implements Runnable {
    private final JComponent view;
    private final Runnable refresh;
    private final AtomicBoolean queued = new AtomicBoolean();
    private volatile boolean stale;

    private ViewRefresh(JComponent view, Runnable refresh) {
        this.view = view;
        this.refresh = refresh;
        view.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && view.isShowing() && stale) run();
        });
    }

    /** What to call when the view's data changed: refresh now if it is on screen, else once it is shown. */
    static Runnable of(JComponent view, Runnable refresh) {
        return new ViewRefresh(view, refresh);
    }

    @Override
    public void run() {
        if (!queued.compareAndSet(false, true)) return;   // a refresh is on its way already
        SwingUtilities.invokeLater(() -> {
            queued.set(false);
            if (view.isShowing()) {
                stale = false;
                refresh.run();
            } else {
                stale = true;
            }
        });
    }
}
