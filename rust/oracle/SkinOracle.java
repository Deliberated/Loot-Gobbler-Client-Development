package oracle;

import java.awt.image.BufferedImage;

/** Every character's look as the Java app shows it (Dyes.icon at 96): "skin <charId> <w>x<h> <fnv of ARGB>". */
public class SkinOracle {
    public static void main(String[] args) {
        java.util.List<lootgobbler.CharacterStore.Char> all = new java.util.ArrayList<>(lootgobbler.CharacterStore.get().all());
        all.sort(java.util.Comparator.comparingInt(c -> c.charId));
        for (lootgobbler.CharacterStore.Char c : all) {
            javax.swing.ImageIcon i = lootgobbler.Dyes.icon(c.spriteId(), c.tex1, c.tex2, 96);
            BufferedImage im = new BufferedImage(i.getIconWidth(), i.getIconHeight(), BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = im.createGraphics();
            g.setComposite(java.awt.AlphaComposite.Src);
            g.drawImage(i.getImage(), 0, 0, null);
            g.dispose();
            long h = 0xcbf29ce484222325L;
            for (int y = 0; y < im.getHeight(); y++) {
                for (int x = 0; x < im.getWidth(); x++) {
                    int p = im.getRGB(x, y);
                    for (int b : new int[]{p >>> 24, (p >> 16) & 255, (p >> 8) & 255, p & 255}) {
                        h = (h ^ b) * 0x100000001b3L;
                    }
                }
            }
            System.out.printf("skin %d %dx%d %016x%n", c.charId, im.getWidth(), im.getHeight(), h);
        }
        System.exit(0);
    }
}
