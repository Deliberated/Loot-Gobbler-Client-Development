package lootgobbler;

import javax.imageio.ImageIO;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The Loot Gobbler icon in every size Windows asks for (title bar 16, taskbar 24-48, alt-tab 64+), from
 * /icon/lootgobbler-<size>.png. The exe gets the same set via icon/lootgobbler.ico.
 */
public final class AppIcon {
    private static final int[] SIZES = {16, 24, 32, 48, 64, 128, 256};
    private static List<Image> images;

    private AppIcon() {
    }

    /**
     * All sizes, for Window.setIconImages (Windows picks the best fit instead of scaling one image).
     */
    public static synchronized List<Image> images() {
        if (images == null) {
            images = new ArrayList<>();
            for (int s : SIZES) {
                BufferedImage img = load(s);
                if (img != null) images.add(img);
            }
        }
        return images;
    }

    /**
     * One size from the set (16, 24, 32, 48, 64, 128 or 256), or null if missing.
     */
    public static BufferedImage load(int size) {
        try (InputStream in = AppIcon.class.getResourceAsStream("/icon/lootgobbler-" + size + ".png")) {
            return in == null ? null : ImageIO.read(in);
        } catch (Exception e) {
            return null;
        }
    }
}
