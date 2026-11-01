package lootgobbler.ui;

import javax.swing.*;
import java.awt.*;

/**
 * Bottom bar: sniffer state (colored dot + text) on the left, Update and Start/Stop Sniffer buttons on the right.
 */
public class StatusBar extends JPanel {
    private final JLabel state = new JLabel();
    private final JButton sniffer = new JButton();

    public StatusBar(Runnable toggleSniffer) {
        super(new BorderLayout());
        putClientProperty("FlatLaf.styleClass", "statusBar");
        state.putClientProperty("FlatLaf.styleClass", "statusBar");
        state.setIconTextGap(8);
        sniffer.putClientProperty("FlatLaf.styleClass", "statusButton");
        sniffer.setFocusable(false);
        sniffer.addActionListener(e -> toggleSniffer.run());
        JButton update = new JButton("UPDATE");
        update.putClientProperty("FlatLaf.styleClass", "statusButton");
        update.setFocusable(false);
        update.setToolTipText("Check for a new Loot Gobbler version");
        update.addActionListener(e -> lootgobbler.Updater.check(this));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        buttons.setOpaque(false);
        buttons.add(update);
        buttons.add(Box.createHorizontalStrut(6));
        buttons.add(sniffer);
        add(state, BorderLayout.WEST);
        add(buttons, BorderLayout.EAST);
        setRunning(false);
    }

    public void setRunning(boolean running) {
        SwingUtilities.invokeLater(() -> {
            state.setIcon(new Section.Square(running ? Palette.GREEN : Palette.STONE, 10));
            state.setText(running ? "SNIFFING" : "SNIFFER OFF");
            sniffer.setText(running ? "STOP SNIFFER" : "START SNIFFER");
        });
    }
}
