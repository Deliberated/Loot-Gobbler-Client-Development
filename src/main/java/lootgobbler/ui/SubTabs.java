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
    private Segmented buttons;

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
        buttons = new Segmented(false, 0, i -> cards.show(pages, names.get(i)), names.toArray(new String[0]));
        add(buttons, BorderLayout.NORTH);
        return this;
    }

    /**
     * Opens a page from code, as if its button was clicked.
     */
    public void show(String name) {
        int i = names.indexOf(name);
        if (i < 0) return;
        cards.show(pages, name);
        if (buttons != null) buttons.select(i);
    }
}
