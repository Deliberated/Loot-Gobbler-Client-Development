package oracle;

import assets.AssetExtractor;
import assets.resextractor.UnityExtractor;

import javax.imageio.ImageIO;
import javax.swing.JOptionPane;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.zip.CRC32;

/**
 * The Java app's extraction of the game data from the game's resources.assets, without its dialogs: into ./assets
 * (run in an empty folder). Then the pixels of each sprite sheet written, as "png name width height crc32", the crc of
 * every pixel as ARGB, 4 bytes big endian, row by row (PNG files themselves differ by encoder).
 * Usage: AssetOracle <resources.assets>
 */
public class AssetOracle {
    public static void main(String[] args) throws Throwable {
        Field pane = AssetExtractor.class.getDeclaredField("pane");
        pane.setAccessible(true);
        pane.set(null, new JOptionPane());   // setDisplay writes into it
        File[] folders = {new File("assets/flatbuffer/"), new File("assets/sprites/"), new File("assets/xml/")};
        new UnityExtractor().extract(new File(args[0]), folders);
        Method xml = AssetExtractor.class.getDeclaredMethod("extractAssetsFromXML");
        xml.setAccessible(true);
        xml.invoke(null);
        File[] pngs = new File("assets/sprites").listFiles((d, n) -> n.endsWith(".png"));
        Arrays.sort(pngs);
        for (File f : pngs) {
            BufferedImage im = ImageIO.read(f);
            CRC32 crc = new CRC32();
            for (int y = 0; y < im.getHeight(); y++) {
                for (int x = 0; x < im.getWidth(); x++) {
                    int p = im.getRGB(x, y);
                    crc.update(new byte[]{(byte) (p >>> 24), (byte) (p >>> 16), (byte) (p >>> 8), (byte) p});
                }
            }
            System.out.printf("png %s %d %d %08x%n", f.getName(), im.getWidth(), im.getHeight(), crc.getValue());
        }
    }
}
