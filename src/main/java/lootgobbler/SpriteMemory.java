package lootgobbler;

import javax.swing.SwingUtilities;
import java.lang.reflect.Field;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Keeps RealmShark's sprite sheets from holding memory: to cut out a sprite it decodes a whole sheet (characters and
 * map objects are 8192 x 4096, 128 MB each once decoded; all four together over 330 MB) and keeps it for good. The
 * icons Loot Gobbler shows are copies, cached on their own, so the sheets are only needed while new icons are made.
 * <p>
 * Every CHECK_S seconds this looks at which sheets are decoded; once the same ones have been there for a whole check
 * (no sheet loaded in between, so nothing is being drawn from them), it lets RealmShark drop them
 * (ImageBuffer.clear()). A sprite needed later decodes its sheet again (about half a second). RealmShark itself stays
 * unchanged: its list of decoded sheets is only read here.
 */
public final class SpriteMemory {
    private static final long CHECK_S = 30;
    private static Field sheets;
    private static String last = "";

    private SpriteMemory() {
    }

    /** Starts watching (once, on start). */
    public static void start() {
        try {
            sheets = assets.ImageBuffer.class.getDeclaredField("bigImages");
            sheets.setAccessible(true);
        } catch (Exception e) {
            AppLog.log("app", "Sprite sheets cannot be released: " + e.getMessage());
            return;
        }
        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sprite-memory");
            t.setDaemon(true);
            return t;
        });
        // on the UI thread, where nearly all sprites are made, so a sheet is not dropped while it is being cut
        exec.scheduleWithFixedDelay(() -> SwingUtilities.invokeLater(SpriteMemory::check), CHECK_S, CHECK_S, TimeUnit.SECONDS);
    }

    private static void check() {
        String loaded = loaded();
        if (loaded.isEmpty()) {
            last = "";
            return;
        }
        if (loaded.equals(last)) {   // unchanged for a whole check: idle
            assets.ImageBuffer.clear();
            last = "";
            AppLog.log("app", "Released decoded sprite sheets (" + loaded + ")");
        } else {
            last = loaded;
        }
    }

    /** Which sheets are decoded now, e.g. "1,3" (empty: none). */
    private static String loaded() {
        try {
            Object[] arr = (Object[]) sheets.get(null);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length; i++) {
                if (arr[i] != null) sb.append(sb.length() > 0 ? "," : "").append(i);
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
