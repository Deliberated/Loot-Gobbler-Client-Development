package lootgobbler;

import tomato.version.Version;

import javax.swing.*;
import java.awt.*;

/**
 * Update check. Placeholder until Loot Gobbler has a GitHub releases page; then this will compare
 * Version.VERSION with the latest release and offer the download.
 */
public final class Updater {
    private Updater() {
    }

    public static void check(Component parent) {
        // centered on the app window (or the dialog it was opened from), not on the button
        Window window = parent instanceof Window w ? w : SwingUtilities.getWindowAncestor(parent);
        JOptionPane.showMessageDialog(window != null ? window : parent,
                "You are running Loot Gobbler " + Version.VERSION + ".\n\n"
                        + "Update checks will be switched on once Loot Gobbler has a GitHub releases page.",
                "Check for updates", JOptionPane.INFORMATION_MESSAGE);
    }
}
