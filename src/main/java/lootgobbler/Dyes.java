package lootgobbler;

import assets.ImageBuffer;

import javax.swing.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Character sprites with their applied dyes and cloths, drawn like the game does: the sprite is scaled 5x, then
 * every pixel marked in its mask (red = clothing / Tex1, green = accessory / Tex2) takes the dye color, or the
 * cloth pattern tiled across the scaled sprite, darkened by the mask's intensity so the shading stays.
 * <p>
 * Texture codes (the Tex1 / Tex2 value of a dye or cloth item): high byte 1 = solid color 0xRRGGBB, 4 / 5 / 9 / 10 = tile
 * in the textile4x4 / 5x5 / 9x9 / 10x10 sheet with the low bits as index, 0 = none.
 */
public final class Dyes {
    private static final int SCALE = 5;
    private static final Pattern OBJECT = Pattern.compile("<Object type=\"(0x[0-9a-fA-F]+)\"[^>]*>(.*?)</Object>", Pattern.DOTALL);
    // clothing dyes / large cloths carry Tex1, accessory dyes / small cloths Tex2
    private static final Pattern TEX = Pattern.compile("<Tex[12]>(0x[0-9a-fA-F]+)</Tex[12]>");
    private static Map<Integer, Integer> textures;   // dye/cloth item type -> texture code
    private static final Map<String, ImageIcon> ICONS = new HashMap<>();   // EDT only

    private Dyes() {
    }

    /**
     * Texture code of a dye or cloth item, 0 if unknown.
     */
    public static synchronized int texture(int itemType) {
        if (textures == null) {
            textures = new HashMap<>();
            for (String file : new String[]{"assets/xml/dyes.xml", "assets/xml/textiles.xml"}) {
                try {
                    Matcher m = OBJECT.matcher(Files.readString(Path.of(file)));
                    while (m.find()) {
                        Matcher t = TEX.matcher(m.group(2));
                        if (t.find()) textures.put(Integer.decode(m.group(1)), (int) Long.parseLong(t.group(1).substring(2), 16));
                    }
                } catch (Exception e) {
                    System.err.println("Could not read " + file + ": " + e.getMessage());
                }
            }
        }
        return textures.getOrDefault(itemType, 0);
    }

    /**
     * The sprite with its dyes, outlined and scaled to size (pixel-sharp); without dyes or mask the plain sprite.
     */
    public static ImageIcon icon(int spriteId, int tex1, int tex2, int size) {
        if (tex1 == 0 && tex2 == 0) return ImageBuffer.getOutlinedIcon(spriteId, size);
        String key = spriteId + ":" + tex1 + ":" + tex2 + ":" + size;
        ImageIcon cached = ICONS.get(key);
        if (cached != null) return cached;
        ImageIcon icon;
        try {
            BufferedImage dyed = dyed(spriteId, tex1, tex2);
            icon = dyed == null ? ImageBuffer.getOutlinedIcon(spriteId, size) : new ImageIcon(outlined(dyed, size));
        } catch (Exception e) {
            icon = ImageBuffer.getOutlinedIcon(spriteId, size);
        }
        ICONS.put(key, icon);
        return icon;
    }

    /**
     * The sprite scaled 5x with the dyes applied, or null if it has no mask.
     */
    static BufferedImage dyed(int spriteId, int tex1, int tex2) throws Exception {
        BufferedImage src = ImageBuffer.getImage(spriteId), mask = ImageBuffer.getMaskImage(spriteId);
        if (src == null || mask == null) return null;
        BufferedImage t1 = textureImage(tex1), t2 = textureImage(tex2);
        int w = src.getWidth() * SCALE, h = src.getHeight() * SCALE;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int sx = x / SCALE, sy = y / SCALE;
                int p = src.getRGB(sx, sy);
                int m = sx < mask.getWidth() && sy < mask.getHeight() ? mask.getRGB(sx, sy) : 0;
                int r = (m >> 16) & 0xFF, g = (m >> 8) & 0xFF;
                if (r > 0 && t1 != null) p = shade(p, t1.getRGB(x % t1.getWidth(), y % t1.getHeight()), r);
                if (g > 0 && t2 != null) p = shade(p, t2.getRGB(x % t2.getWidth(), y % t2.getHeight()), g);
                out.setRGB(x, y, p);
            }
        }
        return out;
    }

    /**
     * The texture's color scaled by the mask intensity, keeping the sprite pixel's alpha.
     */
    private static int shade(int pixel, int texture, int intensity) {
        int r = ((texture >> 16) & 0xFF) * intensity / 255;
        int g = ((texture >> 8) & 0xFF) * intensity / 255;
        int b = (texture & 0xFF) * intensity / 255;
        return (pixel & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    private static BufferedImage textureImage(int code) throws Exception {
        int type = (code >>> 24) & 0xFF, value = code & 0xFFFFFF;
        if (type == 1) {
            BufferedImage solid = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            solid.setRGB(0, 0, 0xFF000000 | value);
            return solid;
        }
        if (type == 4 || type == 5 || type == 9 || type == 10) {
            return ImageBuffer.getSpriteImage("textile" + type + "x" + type, value);
        }
        return null;
    }

    /**
     * Scaled to size-2 with nearest neighbour, then a 1 px black outline around it (as ImageBuffer.getOutlinedIcon).
     */
    private static BufferedImage outlined(BufferedImage img, int size) {
        int inner = size - 2;
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        int[] a = new int[inner * inner];
        for (int y = 0; y < inner; y++) {
            for (int x = 0; x < inner; x++) {
                a[y * inner + x] = img.getRGB(x * img.getWidth() / inner, y * img.getHeight() / inner);
            }
        }
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int ix = x - 1, iy = y - 1;
                int p = ix >= 0 && iy >= 0 && ix < inner && iy < inner ? a[iy * inner + ix] : 0;
                if ((p >>> 24) != 0) {
                    out.setRGB(x, y, p);
                } else if (opaque(a, inner, ix + 1, iy) || opaque(a, inner, ix - 1, iy)
                        || opaque(a, inner, ix, iy + 1) || opaque(a, inner, ix, iy - 1)) {
                    out.setRGB(x, y, 0xFF000000);
                }
            }
        }
        return out;
    }

    private static boolean opaque(int[] a, int n, int x, int y) {
        return x >= 0 && y >= 0 && x < n && y < n && (a[y * n + x] >>> 24) != 0;
    }
}
