package oracle;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;

/**
 * Pictures of the Java app's window, without showing it: the window is built as the app builds it (TomatoGUI.create,
 * which stops where it would make the frame: there is no screen here), then each tab is painted into a PNG with the
 * menu bar above it. Run in a folder holding the app's data (a copy: the app may write there) and without network
 * (e.g. `unshare -rn`): views that talk to servers then show what they show offline.
 * Usage: UiOracle <out dir> <width> <height> [tab...]   (all tabs if none given; "Characters:<charId>" opens a character,
 * "Characters:classes" / "Characters:pets" those pages, "Battles:dps" / "Battles:overlay" those)
 */
public class UiOracle {
    public static void main(String[] args) throws Throwable {
        File out = new File(args[0]);
        out.mkdirs();
        int w = Integer.parseInt(args[1]), h = Integer.parseInt(args[2]);
        lootgobbler.AppLog.install();   // as the app starts: its log folder
        tomato.gui.TomatoGUI.loadThemePreset();
        lootgobbler.DungeonDifficulty.preload();   // as the app starts (the tiles show it)
        tomato.backend.data.TomatoData data = new tomato.backend.data.TomatoData();
        tomato.gui.TomatoGUI gui = new tomato.gui.TomatoGUI(data);
        SwingUtilities.invokeAndWait(() -> {
            try {
                gui.create();
            } catch (HeadlessException expected) {   // makeFrame: everything inside the window is there
                for (StackTraceElement s : expected.getStackTrace()) if (!s.getClassName().startsWith("java")) { System.err.println("headless at " + s); break; }
            }
        });
        JPanel main = (JPanel) field(gui, "mainPanel");
        JMenuBar menu = (JMenuBar) field(gui, "jMenuBar");
        lootgobbler.ui.TabBar tabs = find(main, lootgobbler.ui.TabBar.class);
        String[] names = args.length > 3 ? java.util.Arrays.copyOfRange(args, 3, args.length)
                : new String[]{"Profile", "Inventory", "Characters", "Game Modes", "Battles", "Social", "Guild", "Server", "Settings"};
        Thread.sleep(3000);   // views load in the background
        for (String name : names) {
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            String tab = name.contains(":") ? name.substring(0, name.indexOf(':')) : name;
            String arg = name.contains(":") ? name.substring(name.indexOf(':') + 1) : null;
            SwingUtilities.invokeAndWait(() -> {
                tabs.show(tab);
                if (arg != null && tab.equals("Inventory")) {
                    try {
                        Object cp = find(main, lootgobbler.ui.CollectionPanel.class);
                        Object picker = field(cp, "viewPicker");
                        picker.getClass().getMethod("select", int.class).invoke(picker, arg.equals("st") ? 1 : 2);
                        ((CardLayout) field(cp, "cards")).show((Container) field(cp, "views"), arg);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
                if (arg != null && tab.equals("Battles")) {   // "Battles:dps" / "Battles:overlay"
                    try {
                        Object v = find(main, lootgobbler.ui.DpsLoggerView.class);
                        int page = arg.equals("dps") ? 1 : 2;
                        Object picker = field(v, "picker");
                        picker.getClass().getMethod("select", int.class).invoke(picker, page);
                        ((CardLayout) field(v, "pages")).show((Container) field(v, "views"), arg.equals("dps") ? "DPS" : "Overlay");
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
                if (arg != null && tab.equals("Characters")) {
                    lootgobbler.ui.CharactersView cv = find(main, lootgobbler.ui.CharactersView.class);
                    if (arg.equals("classes")) cv.showClass(-1);
                    else if (arg.equals("pets")) pets(cv);
                    else cv.showCharacter(Integer.parseInt(arg));
                }
                JPanel root = new JPanel(new BorderLayout());
                root.add(menu, BorderLayout.NORTH);
                root.add(main, BorderLayout.CENTER);
                root.setSize(w, h);
                layout(root);
            });
            Thread.sleep(1500);   // what the tab loads when shown
            SwingUtilities.invokeAndWait(() -> {
                Container root = main.getParent();
                layout(root);
                Graphics2D g = img.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                root.printAll(g);
                g.dispose();
            });
            ImageIO.write(img, "png", new File(out, name.replace(' ', '_').replace(':', '-') + ".png"));
            System.out.println("tab " + name);
        }
        System.exit(0);
    }

    /** The Characters tab's Pets page (it has no method to open it). */
    static void pets(lootgobbler.ui.CharactersView cv) {
        try {
            Object picker = field(cv, "viewPicker");
            picker.getClass().getMethod("select", int.class).invoke(picker, 2);
            ((CardLayout) field(cv, "pages")).show((Container) field(cv, "views"), "pets");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static Object field(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    static <T> T find(Container c, Class<T> type) {
        for (Component k : c.getComponents()) {
            if (type.isInstance(k)) return type.cast(k);
            if (k instanceof Container kc) {
                T t = find(kc, type);
                if (t != null) return t;
            }
        }
        return null;
    }

    /** Lays out a tree that has no window (validate does nothing without one). */
    static void layout(Component c) {
        if (c instanceof Container k) {
            k.doLayout();
            for (Component child : k.getComponents()) layout(child);
        }
    }
}
