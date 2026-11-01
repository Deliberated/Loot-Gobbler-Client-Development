package lootgobbler.ui;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

/**
 * A row of mutually exclusive toggle buttons (e.g. GRID | LIVE | LOG). Joined segments sit edge to edge.
 */
public class Segmented extends JPanel {
    private final List<JToggleButton> buttons = new ArrayList<>();

    public Segmented(boolean joined, int selected, IntConsumer onSelect, String... labels) {
        super(new FlowLayout(FlowLayout.LEFT, joined ? 0 : 6, 0));
        setOpaque(false);
        ButtonGroup group = new ButtonGroup();
        for (int i = 0; i < labels.length; i++) {
            int index = i;
            JToggleButton b = new JToggleButton(labels[i].toUpperCase(Locale.ROOT), i == selected);
            b.setFocusable(false);
            b.addActionListener(e -> onSelect.accept(index));
            group.add(b);
            buttons.add(b);
            add(b);
        }
    }

    public void select(int index) {
        if (index >= 0 && index < buttons.size()) buttons.get(index).setSelected(true);
    }
}
