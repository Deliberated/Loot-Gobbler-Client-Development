package lootgobbler.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Pages inside a main tab, picked with a row of buttons on top (like Characters / Classes).
 */
public class SubTabs extends JPanel {
    private final CardLayout cards = new CardLayout();
    private final JPanel pages = new JPanel(cards);
    private final List<String> names = new ArrayList<>();

    public SubTabs() {
        super(new BorderLayout(0, 8));
        setBorder(new EmptyBorder(8, 8, 8, 8));
        pages.setOpaque(false);
        add(pages, BorderLayout.CENTER);
    }

    public SubTabs page(String name, Component page) {
        names.add(name);
        pages.add(page, name);
        return this;
    }

    /**
     * Builds the button row once all pages are added; the first page is shown.
     */
    public SubTabs done() {
        add(new Segmented(false, 0, i -> cards.show(pages, names.get(i)), names.toArray(new String[0])),
                BorderLayout.NORTH);
        return this;
    }
}
