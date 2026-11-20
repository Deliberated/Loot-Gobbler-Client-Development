package oracle;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeSet;
import java.util.zip.CRC32;

/**
 * Every object's sprite as the Java app cuts it (ImageBuffer.getImage, and the dye mask): "sprite <id> <w>x<h>
 * <crc of the ARGB pixels>", "empty" for its 8x8 blank, "throws" where Java throws. Run in a folder with ./assets.
 */
public class SpriteOracle {
    public static void main(String[] args) throws Exception {
        TreeSet<Integer> ids = new TreeSet<>();
        for (String line : Files.readAllLines(Path.of("assets", "ObjectID.list"))) {
            try {
                ids.add(Integer.parseInt(line.substring(0, line.indexOf(';'))));
            } catch (RuntimeException ignored) {
            }
        }
        StringBuilder out = new StringBuilder();
        for (int id : ids) {
            if (id <= 0) continue;
            out.append("sprite ").append(id).append(' ').append(describe(() -> assets.ImageBuffer.getImage(id))).append('\n');
            out.append("mask ").append(id).append(' ').append(describe(() -> assets.ImageBuffer.getMaskImage(id))).append('\n');
        }
        System.out.print(out);
    }

    interface Get {
        BufferedImage get() throws Exception;
    }

    static String describe(Get g) {
        BufferedImage im;
        try {
            im = g.get();
        } catch (Exception e) {
            return "throws";
        }
        if (im == null) return "none";
        if (im == assets.ImageBuffer.getEmptyImg()) return "empty";
        CRC32 crc = new CRC32();
        for (int y = 0; y < im.getHeight(); y++) {
            for (int x = 0; x < im.getWidth(); x++) {
                int p = im.getRGB(x, y);
                crc.update(new byte[]{(byte) (p >>> 24), (byte) (p >>> 16), (byte) (p >>> 8), (byte) p});
            }
        }
        return im.getWidth() + "x" + im.getHeight() + " " + String.format("%08x", crc.getValue());
    }
}
