package lootgobbler;

import assets.ImageBuffer;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Enchantment names and icons from assets/xml/enchantments.xml (texture sheet + index per enchantment).
 */
public final class EnchantIcons {
    private record Def(String name, String sheet, int index) {
    }

    private static volatile Map<Integer, Def> defs;
    private static final Map<String, Image> ICONS = new HashMap<>();   // "id/size" -> scaled icon, EDT only

    public static String name(int enchantId) {
        Def d = defs().get(enchantId);
        return d == null ? "Enchantment #" + enchantId : d.name;
    }

    /**
     * The enchantment's icon scaled to the given size (pixel-sharp), or null if unknown.
     */
    public static Image icon(int enchantId, int size) {
        String key = enchantId + "/" + size;
        if (ICONS.containsKey(key)) return ICONS.get(key);
        Image img = null;
        Def d = defs().get(enchantId);
        if (d != null && d.sheet != null) {
            try {
                BufferedImage sprite = ImageBuffer.getSpriteImage(d.sheet, d.index);
                if (sprite != null) {
                    BufferedImage scaled = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D g = scaled.createGraphics();
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                    g.drawImage(sprite, 0, 0, size, size, null);
                    g.dispose();
                    img = scaled;
                }
            } catch (Exception ignored) {
                // sprite sheet not available: no icon
            }
        }
        ICONS.put(key, img);
        return img;
    }

    private static Map<Integer, Def> defs() {
        Map<Integer, Def> d = defs;
        if (d == null) {
            synchronized (EnchantIcons.class) {
                if (defs == null) defs = load(Path.of("assets", "xml", "enchantments.xml"));
                d = defs;
            }
        }
        return d;
    }

    private static Map<Integer, Def> load(Path file) {
        Map<Integer, Def> out = new HashMap<>();
        if (!Files.exists(file)) return out;
        XMLInputFactory f = XMLInputFactory.newInstance();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        try (InputStream in = Files.newInputStream(file)) {
            XMLStreamReader r = f.createXMLStreamReader(in);
            Integer type = null;
            String id = null, name = null, sheet = null;
            int index = 0;
            boolean inTexture = false;
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    switch (r.getLocalName()) {
                        case "Enchantment" -> {
                            id = r.getAttributeValue(null, "id");
                            String t = r.getAttributeValue(null, "type");
                            type = t == null ? null : Integer.decode(t.trim());
                            name = null;
                            sheet = null;
                            index = 0;
                        }
                        case "DisplayId" -> name = r.getElementText().trim();
                        case "Texture" -> inTexture = true;
                        case "File" -> {
                            String v = r.getElementText().trim();
                            if (inTexture) sheet = v;
                        }
                        case "Index" -> {
                            String v = r.getElementText().trim();
                            if (inTexture) index = Integer.decode(v);
                        }
                        default -> {
                        }
                    }
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    if (r.getLocalName().equals("Texture")) inTexture = false;
                    if (r.getLocalName().equals("Enchantment") && type != null) {
                        out.put(type, new Def(name != null ? name : id, sheet, index));
                    }
                }
            }
            r.close();
        } catch (Exception e) {
            System.err.println("EnchantIcons: could not read " + file + ": " + e.getMessage());
        }
        return out;
    }

    private EnchantIcons() {
    }
}
