package lootgobbler.ui;

import java.awt.*;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The parts of the window the Newcomer's Guide points at, by name. Each view marks its part when it builds it (views
 * that rebuild mark the new one), so the guide finds what is on the screen now.
 */
public final class GuideTargets {
    private static final Map<String, WeakReference<Component>> MARKED = new ConcurrentHashMap<>();

    private GuideTargets() {
    }

    /** Marks c as the part with this name, and returns it. */
    public static <T extends Component> T mark(String name, T c) {
        MARKED.put(name, new WeakReference<>(c));
        return c;
    }

    /** The part with this name, if it is on the screen now; else null. */
    static Component showing(String name) {
        WeakReference<Component> ref = MARKED.get(name);
        Component c = ref == null ? null : ref.get();
        return c != null && c.isShowing() ? c : null;
    }

    /** The part with this name, shown or not (e.g. a menu); else null. */
    static Component any(String name) {
        WeakReference<Component> ref = MARKED.get(name);
        return ref == null ? null : ref.get();
    }
}
