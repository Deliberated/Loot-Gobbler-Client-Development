package lootgobbler.ui;

import util.PropertiesManager;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * Remembers a window's position, size and maximized state in the settings file and restores it on start.
 * A saved position is only used if it is still on a connected screen.
 */
public final class WindowMemory {
    private static final String KEY_BOUNDS = "windowBounds";
    private static final String KEY_MAXIMIZED = "windowMaximized";

    /**
     * Restores the saved bounds (or the default size, centered) and keeps them up to date from now on.
     */
    public static void attach(JFrame frame, int defaultWidth, int defaultHeight) {
        Rectangle saved = parse(PropertiesManager.getProperty(KEY_BOUNDS));
        if (saved != null && onScreen(saved)) {
            frame.setBounds(saved);
        } else {
            // the default is shrunk to the screen's free area (minus the taskbar) on smaller screens
            Rectangle free = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
            frame.setSize(Math.min(defaultWidth, free.width), Math.min(defaultHeight, free.height));
            frame.setLocationRelativeTo(null);
        }
        if ("true".equals(PropertiesManager.getProperty(KEY_MAXIMIZED))) {
            frame.setExtendedState(frame.getExtendedState() | Frame.MAXIMIZED_BOTH);
        }

        Timer save = new Timer(600, e -> save(frame));   // settings file is written once you stop moving
        save.setRepeats(false);
        frame.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                save.restart();
            }

            @Override
            public void componentMoved(ComponentEvent e) {
                save.restart();
            }
        });
        frame.addWindowStateListener(e -> save.restart());
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                save(frame);
            }
        });
    }

    private static void save(JFrame frame) {
        boolean maximized = (frame.getExtendedState() & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH;
        PropertiesManager.setProperties(KEY_MAXIMIZED, Boolean.toString(maximized));
        if (!maximized && (frame.getExtendedState() & Frame.ICONIFIED) == 0) {   // keep the normal-size bounds
            Rectangle b = frame.getBounds();
            PropertiesManager.setProperties(KEY_BOUNDS, b.x + "," + b.y + "," + b.width + "," + b.height);
        }
    }

    private static Rectangle parse(String s) {
        if (s == null) return null;
        try {
            String[] p = s.split(",");
            Rectangle r = new Rectangle(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()),
                    Integer.parseInt(p[2].trim()), Integer.parseInt(p[3].trim()));
            return r.width >= 200 && r.height >= 150 ? r : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * True if the window's title bar area would be visible on one of the screens.
     */
    private static boolean onScreen(Rectangle r) {
        Rectangle titleBar = new Rectangle(r.x + 40, r.y, Math.max(1, r.width - 80), 30);
        for (GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            if (d.getDefaultConfiguration().getBounds().intersects(titleBar)) return true;
        }
        return false;
    }

    private WindowMemory() {
    }
}
