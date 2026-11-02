package lootgobbler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What the notifications bell shows: one entry per key (posting the same key again replaces it, e.g. "3 players
 * waiting" becomes "4 players waiting"), newest first, and whether something new came in since the bell was last
 * opened. Not saved: the sources post again after a restart.
 */
public final class Notifications {
    public record Notification(String key, String title, String text, long time, Runnable open) {
    }

    private static final Notifications INSTANCE = new Notifications();
    private final List<Notification> list = new ArrayList<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private boolean unread;

    public static Notifications get() {
        return INSTANCE;
    }

    private Notifications() {
    }

    /** Adds or replaces the notification with this key; it counts as new (the red dot) if fresh is set. */
    public void post(String key, String title, String text, Runnable open, boolean fresh) {
        synchronized (this) {
            list.removeIf(n -> n.key().equals(key));
            list.add(0, new Notification(key, title, text, System.currentTimeMillis(), open));
            if (fresh) unread = true;
        }
        fire();
    }

    public void remove(String key) {
        boolean changed;
        synchronized (this) {
            changed = list.removeIf(n -> n.key().equals(key));
            if (list.isEmpty()) unread = false;
        }
        if (changed) fire();
    }

    public synchronized List<Notification> all() {
        return new ArrayList<>(list);
    }

    public synchronized boolean unread() {
        return unread;
    }

    /** The bell was opened: the red dot goes away. */
    public void markRead() {
        synchronized (this) {
            if (!unread) return;
            unread = false;
        }
        fire();
    }

    public void onChange(Runnable listener) {
        listeners.add(listener);
    }

    private void fire() {
        for (Runnable r : listeners) r.run();
    }
}
