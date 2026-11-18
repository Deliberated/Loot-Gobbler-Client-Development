package lootgobbler.ui;

import assets.IdToAsset;
import lootgobbler.CollectionStore;
import lootgobbler.ItemDb;
import lootgobbler.StSets;
import util.PropertiesManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.util.*;
import java.util.List;

/**
 * Collection tab: lifetime Grid, Live view and a Log of every collectible item obtained,
 * with progress, copy-as-text / copy-as-image and a recent list.
 */
public class CollectionPanel extends JPanel {
    private static final int ALL = 0, SHINY = 1, NORMAL = 2, REUSABLE = 3;   // REUSABLE: used again and again (quick slot)
    private static final String[] VIEWS = {"grid", "st", "log"};
    private static final int RECENT = 8;

    private final CollectionGrid grid = new CollectionGrid();
    private int gridFilter = ALL;
    private final JProgressBar progress = bar(null);
    private final JLabel progressText = new JLabel("Loading item data...");
    private final JPanel recent = new JPanel();
    private final DefaultTableModel logModel = new DefaultTableModel(new Object[]{"Date", "", "Item", "Count"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final CardLayout cards = new CardLayout();
    private final JPanel views = new JPanel(cards);
    private List<ItemDb.Item> universe = List.of();
    private boolean loaded = false;
    private final Segmented viewPicker;
    private Segmented gridFilterPicker;
    private final StSetsView stView = new StSetsView(item -> showHistory(item, true));
    private List<StSets.Set> stSets = List.of();
    private Section sideSection;
    private JButton backToRecent;
    private ItemDb.Item historyItem;   // item whose history replaces the Recent list, or null
    private final JTextField search = new JTextField();
    /** The UPE character whose gathered items are shown (0: the whole collection), and the column to pick it. */
    private int upeChar;
    private Map<Integer, CollectionStore.Entry> upeItems = Map.of();
    private final JPanel upeColumn = new JPanel();
    private final JScrollPane upeScroll;
    /** The What's New tour's example run: a character that just started, nothing found (nothing stored). */
    private static final int EXAMPLE = -1;
    private boolean example, columnBeforeExample;

    public CollectionPanel() {
        super(new BorderLayout(0, 8));
        setBorder(new EmptyBorder(8, 8, 8, 8));

        viewPicker = new Segmented(false, 0, i -> cards.show(views, VIEWS[i]), "Items", "ST Sets", "Log");
        // the white bag left of the pages opens a column to pick whose items are shown: all, or one UPE character's
        JLabel bag = new JLabel(CharactersView.sprite(tomato.realmshark.enums.LootBags.WHITE.getId(), 34));
        bag.setToolTipText("Show the whole collection or a UPE character's own finds");
        bag.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        bar.setOpaque(false);
        bar.add(bag);
        bar.add(viewPicker);
        add(bar, BorderLayout.NORTH);
        upeColumn.setLayout(new BoxLayout(upeColumn, BoxLayout.Y_AXIS));
        upeColumn.setOpaque(false);
        upeColumn.setBorder(new EmptyBorder(4, 4, 4, 4));
        upeScroll = new JScrollPane(upeColumn, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        upeScroll.setBorder(new EmptyBorder(0, 0, 0, 0));
        upeScroll.setOpaque(false);
        upeScroll.getViewport().setOpaque(false);
        upeScroll.setVisible(Boolean.parseBoolean(String.valueOf(PropertiesManager.getProperty(UPE_COLUMN))));
        add(upeScroll, BorderLayout.WEST);
        Clicks.onClick(bag, () -> {
            upeScroll.setVisible(!upeScroll.isVisible());
            PropertiesManager.setProperties(UPE_COLUMN, String.valueOf(upeScroll.isVisible()));
            if (upeScroll.isVisible()) renderUpeColumn(lootgobbler.Upe.get().markedRuns());   // built only while shown
            revalidate();
            repaint();
        });
        grid.onItemClick(item -> showHistory(item, false));
        views.setOpaque(false);
        views.add(gridView(), VIEWS[0]);
        views.add(stView, VIEWS[1]);
        views.add(logView(), VIEWS[2]);
        add(views, BorderLayout.CENTER);

        CollectionStore.get().onChange(ViewRefresh.of(this, this::refresh));   // only while it can be seen
        lootgobbler.Holdings.get().onChange(ViewRefresh.of(this, this::refresh));   // copies moved, came or went
        lootgobbler.Upe.get().onChange(ViewRefresh.of(this, this::refresh));       // a UPE gathered something
        Thread loader = new Thread(() -> {
            ItemDb db = ItemDb.get();
            List<StSets.Set> sets = StSets.get();
            SwingUtilities.invokeLater(() -> {
                universe = db.collectible();
                stSets = sets;
                loaded = true;
                refresh();
            });
        }, "ItemDb loader");
        loader.setDaemon(true);
        loader.start();
    }

    private JPanel gridView() {
        gridFilterPicker = new Segmented(true, 0, i -> {
            gridFilter = i;
            refresh();
        }, "All", "Shiny", "Normal", "Reusable");
        gridFilterPicker.setToolTipText("Reusable: items used again and again from a quick slot, e.g. the Coral Juice Refinery");
        search.putClientProperty("JTextField.placeholderText", "Search items or enchants");
        search.putClientProperty("JTextField.showClearButton", true);
        search.setToolTipText("Item name, type (e.g. Katana, Ring), UT / ST / T14, or an enchantment on items you own");
        search.setPreferredSize(new Dimension(220, search.getPreferredSize().height));
        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { refresh(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { refresh(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { refresh(); }
        });
        JPanel headerActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        headerActions.setOpaque(false);
        headerActions.add(search);
        headerActions.add(gridFilterPicker);
        Section main = new Section("Collection", Palette.GOLD, scroll(grid)).actions(headerActions);
        mainSection = main;
        GuideTargets.mark("inventory.grid", main);
        GuideTargets.mark("inventory.view", this);
        GuideTargets.mark("inventory.upeColumn", upeScroll);

        JPanel top = column(new Section("Progress", Palette.GREEN, progressBody("Collected", progress, progressText)),
                new Section("Actions", Palette.BLUE, actionsBody()));
        recent.setLayout(new BoxLayout(recent, BoxLayout.Y_AXIS));
        recent.setOpaque(false);
        recent.setBorder(new EmptyBorder(8, 10, 8, 10));
        JScrollPane sp = FitWidthPanel.scroll(recent, 16);
        JPanel sideBody = new JPanel(new BorderLayout());
        sideBody.add(sp);

        backToRecent = button("Recent", null, () -> {
            historyItem = null;
            refresh();
        });
        backToRecent.setVisible(false);
        sideSection = new Section("Recent", Palette.PURPLE, sideBody).actions(backToRecent);
        // keep the header (title, search box, All/Shiny/Normal) readable
        return split("collectionGrid", main, top, sideSection, grid).minMain(main.getPreferredSize().width + 24);
    }

    private JPanel logView() {
        JTable table = new JTable(logModel);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setRowHeight(36);
        table.setFillsViewportHeight(true);
        table.getTableHeader().setReorderingAllowed(false);
        table.setDefaultRenderer(Object.class, new LogRenderer());
        fixWidth(table, 0, 120);
        fixWidth(table, 1, 44);
        fixWidth(table, 3, 80);
        table.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        Clicks.onClick(table, e -> {
            int row = table.rowAtPoint(e.getPoint());
            if (row < 0) return;
            ItemDb.Item item = ItemDb.get().item((Integer) logModel.getValueAt(table.convertRowIndexToModel(row), 1));
            if (item != null) showHistory(item, true);
        });
        JScrollPane sp = new JScrollPane(table);
        sp.setBorder(null);
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.add(new Section("Collection · Log", Palette.GOLD, sp));
        return p;
    }

    private static final String UPE_COLUMN = "collectionUpeColumn";
    private JButton clearButton;   // off while a UPE's finds are shown: it clears the whole collection
    private Section mainSection;

    /**
     * The column of whose items are shown: the whole collection first, then every character marked as a UPE (lost
     * runs too, faded). The one shown is highlighted.
     */
    private void renderUpeColumn(List<lootgobbler.Upe.Run> runs) {
        upeColumn.removeAll();
        upeColumn.add(upeEntry(new JLabel(CharactersView.sprite(tomato.realmshark.enums.LootBags.WHITE.getId(), 40)),
                "All", "Your whole collection", 0, false));
        Map<Integer, lootgobbler.CharacterStore.Char> chars = new java.util.HashMap<>();
        for (lootgobbler.CharacterStore.Char c : lootgobbler.CharacterStore.get().all()) chars.put(c.charId, c);
        for (String mode : lootgobbler.Upe.MODES) {   // per mode: a gold line with its name, its characters below
            List<lootgobbler.Upe.Run> inMode = runs.stream().filter(r -> mode.equals(r.mode) && chars.containsKey(r.charId)).toList();
            if (inMode.isEmpty()) continue;
            upeColumn.add(modeLine(lootgobbler.Upe.label(mode)));
            for (lootgobbler.Upe.Run r : inMode) {
                lootgobbler.CharacterStore.Char c = chars.get(r.charId);
                String tip = "<html><b>" + c.className + "</b> (" + lootgobbler.Upe.label(mode) + ")<br>" + r.gathered.size() + " item"
                        + (r.gathered.size() == 1 ? "" : "s") + " gathered<br>"
                        + lootgobbler.Upe.describe(r).replace("&", "&amp;").replace("<", "&lt;").replace("\n", "<br>") + "</html>";
                upeColumn.add(upeEntry(new JLabel(CharactersView.skin(c, 40)), c.className, tip, r.charId, r.broken != null || !c.alive));
            }
        }
        if (example) {
            lootgobbler.CharacterStore.Char c = exampleChar();
            upeColumn.add(modeLine(lootgobbler.Upe.label(lootgobbler.Upe.UPE)));
            upeColumn.add(upeEntry(new JLabel(CharactersView.skin(c, 40)), c.className, "An example run: nothing found yet", EXAMPLE, false));
        }
        if (runs.isEmpty() && !example) {
            JLabel none = new JLabel("<html><div style='width:70px;text-align:center'>No game mode runs yet</div></html>");
            none.setForeground(Palette.MUTED);
            none.putClientProperty("FlatLaf.styleClass", "small");
            none.setAlignmentX(CENTER_ALIGNMENT);
            upeColumn.add(Box.createVerticalStrut(8));
            upeColumn.add(none);
        }
        upeColumn.revalidate();
        upeColumn.repaint();
    }

    /** A mode's heading in the column: its name in gold over a thin gold line. */
    private static JComponent modeLine(String mode) {
        Color gold = Section.color("LootGobbler.gold", Palette.GOLD);
        JLabel name = new JLabel(mode);
        name.putClientProperty("FlatLaf.styleClass", "small");
        name.setForeground(gold);
        name.setAlignmentX(LEFT_ALIGNMENT);
        JPanel line = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(gold);
                g.fillRect(0, 0, getWidth(), 2);
            }
        };
        line.setOpaque(false);
        line.setPreferredSize(new Dimension(80, 2));
        line.setMaximumSize(new Dimension(Integer.MAX_VALUE, 2));
        line.setAlignmentX(LEFT_ALIGNMENT);
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);
        p.setBorder(new EmptyBorder(10, 6, 4, 6));
        p.add(name);
        p.add(Box.createVerticalStrut(2));
        p.add(line);
        p.setMaximumSize(new Dimension(110, p.getPreferredSize().height));
        p.setAlignmentX(CENTER_ALIGNMENT);
        return p;
    }

    /** One entry of the column: its picture with a name below; the one shown has a gold frame. */
    private JComponent upeEntry(JLabel picture, String name, String tip, int charId, boolean faded) {
        boolean shown = upeChar == charId;
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(shown);
        p.setBackground(Palette.mix(Section.color("LootGobbler.gold", Palette.GOLD), Section.color("LootGobbler.paper", Color.DARK_GRAY), 0.25));
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(shown ? Section.color("LootGobbler.gold", Palette.GOLD) : new Color(0, 0, 0, 0), 2),
                new EmptyBorder(4, 6, 4, 6)));
        picture.setAlignmentX(CENTER_ALIGNMENT);
        JLabel label = new JLabel(name);
        label.putClientProperty("FlatLaf.styleClass", "small");
        label.setAlignmentX(CENTER_ALIGNMENT);
        if (faded) {
            label.setForeground(Palette.MUTED);
            picture.setEnabled(false);
        }
        p.add(picture);
        p.add(label);
        p.setAlignmentX(CENTER_ALIGNMENT);
        p.setMaximumSize(new Dimension(96, p.getPreferredSize().height));
        p.setToolTipText(tip);
        p.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        Clicks.onClick(p, () -> {
            upeChar = charId;
            historyItem = null;
            refresh();
        });
        JPanel holder = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 3));
        holder.setOpaque(false);
        holder.add(p);
        holder.setMaximumSize(new Dimension(110, holder.getPreferredSize().height));
        return holder;
    }

    /**
     * What a UPE run gathered, as collection entries: per item the copies it got (drops, things made cleanly), when
     * and where, newest last.
     */
    private static Map<Integer, CollectionStore.Entry> gathered(lootgobbler.Upe.Run run) {
        Map<Integer, CollectionStore.Entry> out = new java.util.HashMap<>();
        if (run == null) return out;
        for (lootgobbler.Upe.Gathered g : run.gathered) {
            ItemDb.Item item = ItemDb.get().item(g.item);
            if (item == null || !item.isCollectible()) continue;
            String time = java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(g.at), java.time.ZoneId.systemDefault())
                    .withNano(0).toString();
            CollectionStore.Entry e = out.computeIfAbsent(g.item, k -> new CollectionStore.Entry());
            if (e.first == null) e.first = time;
            e.last = time;
            e.count++;
            List<Integer> ench = g.enchants == null ? null : java.util.Arrays.stream(g.enchants).boxed().toList();
            CollectionStore.Find f = ench != null
                    ? CollectionStore.Find.of(lootgobbler.Upe.CRAFTED.equals(g.how) ? CollectionStore.FORGED : CollectionStore.DROP, ench, g.where, "")
                    : CollectionStore.Find.of(lootgobbler.Upe.CRAFTED.equals(g.how) ? CollectionStore.FORGED : CollectionStore.DROP, -1, g.where, "");
            f.time = time;
            f.at = g.at;
            if (e.history == null) e.history = new ArrayList<>();
            e.history.add(f);
        }
        return out;
    }

    private void refresh() {
        if (!loaded) return;
        List<lootgobbler.Upe.Run> runs = lootgobbler.Upe.get().markedRuns();
        lootgobbler.Upe.Run run = runs.stream().filter(r -> r.charId == upeChar).findFirst().orElse(null);
        if (run == null && !(example && upeChar == EXAMPLE)) upeChar = 0;   // shown no more (the mark taken off)
        if (upeScroll.isVisible()) renderUpeColumn(runs);
        upeItems = gathered(run);
        if (upeChar != 0) {
            Map<Integer, CollectionStore.Entry> mine = upeItems;
            grid.counts(id -> mine.containsKey(id) ? mine.get(id).count : 0, id -> mine.containsKey(id) ? mine.get(id).maxEnchants() : 0);
            stView.counts(id -> mine.containsKey(id) ? mine.get(id).maxEnchants() : 0);
            lootgobbler.CharacterStore.Char c = upeChar == EXAMPLE ? exampleChar()
                    : lootgobbler.CharacterStore.get().all().stream().filter(x -> x.charId == upeChar).findFirst().orElse(null);
            if (mainSection != null) mainSection.setTitle("Collection · " + (c == null ? "UPE" : c.className + " (UPE)"));
        } else {
            grid.counts(null, null);
            stView.counts(null);
            if (mainSection != null) mainSection.setTitle("Collection");
        }
        if (clearButton != null) {
            clearButton.setEnabled(upeChar == 0);
            clearButton.setToolTipText(upeChar == 0 ? null : "Shows a UPE character's finds: switch to All to clear the collection");
        }
        Map<Integer, CollectionStore.Entry> owned = collected();
        List<ItemDb.Item> g = matching(filter(gridFilter), owned);
        grid.setData(g, owned);
        stView.setData(stSets, owned);
        setProgress(progress, progressText, g, owned);

        List<Map.Entry<Integer, CollectionStore.Entry>> byLast = new ArrayList<>(owned.entrySet());
        byLast.sort((a, b) -> String.valueOf(b.getValue().last).compareTo(String.valueOf(a.getValue().last)));

        recent.removeAll();
        if (historyItem != null) {
            renderHistory(historyItem, upeChar != 0 ? upeItems.get(historyItem.id)
                    : CollectionStore.get().snapshot().get(historyItem.id));   // also when all are gone
        } else {
            sideSection.setTitle("Recent");
            backToRecent.setVisible(false);
            if (byLast.isEmpty()) recent.add(new JLabel("Nothing collected yet."));
            String day = null;
            for (int i = 0; i < Math.min(RECENT, byLast.size()); i++) {
                CollectionStore.Entry e = byLast.get(i).getValue();
                if (!e.lastDate().equals(day)) {   // a line with the day where a new day starts
                    day = e.lastDate();
                    recent.add(dayLine(day, i == 0));
                }
                recent.add(recentRow(byLast.get(i).getKey(), e));
                recent.add(Box.createVerticalStrut(4));
            }
        }
        recent.revalidate();
        recent.repaint();

        logModel.setRowCount(0);
        for (Map.Entry<Integer, CollectionStore.Entry> e : byLast) {
            logModel.addRow(new Object[]{e.getValue().firstDate(), e.getKey(), name(e.getKey()), e.getValue().count});
        }
    }

    /** Shows a UPE character's own finds (from the Game Modes tab): the column open with it picked. */
    public void showUpe(int charId) {
        upeChar = charId;
        historyItem = null;
        if (!upeScroll.isVisible()) {
            upeScroll.setVisible(true);
            PropertiesManager.setProperties(UPE_COLUMN, "true");
        }
        viewPicker.select(0);
        cards.show(views, VIEWS[0]);
        refresh();
        revalidate();
    }

    /**
     * The What's New tour: on, Items shows an example run (a Wizard that just started, nothing found yet) picked in
     * the column; off, as it was before.
     */
    public void showExample(boolean on) {
        if (on == example) return;
        example = on;
        if (on) {
            columnBeforeExample = upeScroll.isVisible();
            upeChar = EXAMPLE;
            historyItem = null;
            upeScroll.setVisible(true);   // not saved: it closes again afterwards if it was closed
            viewPicker.select(0);
            cards.show(views, VIEWS[0]);
        } else {
            if (upeChar == EXAMPLE) upeChar = 0;
            upeScroll.setVisible(columnBeforeExample);
        }
        refresh();
        revalidate();
        repaint();
    }

    private static lootgobbler.CharacterStore.Char exampleChar() {
        lootgobbler.CharacterStore.Char c = new lootgobbler.CharacterStore.Char();
        c.charId = EXAMPLE;
        c.classType = 782;   // Wizard
        c.className = "Wizard";
        return c;
    }

    /**
     * Shows an item clicked in another tab (see ItemLinks): scrolled to and pulsing in the grid, its finds beside it.
     */
    public void showItem(int itemId) {
        ItemDb.Item item = ItemDb.get().item(itemId);
        if (item != null) showHistory(item, true);
    }

    /**
     * Shows the clicked item's finds in place of the Recent list (on the Grid view).
     */
    private void showHistory(ItemDb.Item item, boolean highlight) {
        historyItem = item;
        viewPicker.select(0);
        cards.show(views, VIEWS[0]);
        refresh();
        if (highlight && !grid.pulse(item.id)) {   // hidden by the filter or search: show all, then highlight
            gridFilterPicker.select(ALL);
            gridFilter = ALL;
            search.setText("");
            refresh();
            grid.pulse(item.id);
        }
    }

    private void renderHistory(ItemDb.Item item, CollectionStore.Entry entry) {
        sideSection.setTitle("History");
        backToRecent.setVisible(true);

        recent.add(itemHeader(item, entry));
        recent.add(Box.createVerticalStrut(12));

        // where your copies are now: each as the game last showed its place (nothing guessed)
        lootgobbler.Holdings where = lootgobbler.Holdings.get();
        heading("WHERE THEY ARE");
        if (!where.known()) {
            recent.add(leftAligned(mutedLabel("Shown once Loot Gobbler has seen your characters (log in) and your vault.")));
        } else {
            List<lootgobbler.Holdings.Held> have = where.copies(item.id);
            if (have.isEmpty()) recent.add(leftAligned(mutedLabel("None on your characters or in your vaults.")));
            for (lootgobbler.Holdings.Held h : have) {
                recent.add(findRow(item.id, asFind(h), null));
                recent.add(Box.createVerticalStrut(6));
            }
            lootgobbler.Holdings.Gaps gaps = where.gaps();
            if (!gaps.none()) {
                JLabel hint = mutedLabel("Not seen yet: " + gapsText(gaps) + ". Their copies are not counted until then.");
                hint.setToolTipText("Play each character once (or visit the daily quest room or the pet yard, which read the character list) "
                        + "and visit your vaults.");
                recent.add(leftAligned(hint));
            }
        }
        if (entry == null) return;
        // what happened: drops (and chests opened), latest first; below, the copies used up
        Map<CollectionStore.Find, CollectionStore.Find> used = usedCopies(entry.history());
        List<CollectionStore.Find> finds = new ArrayList<>(entry.history());
        Collections.reverse(finds);   // latest at the top
        List<CollectionStore.Find> drops = finds.stream().filter(f -> CollectionStore.DROP.equals(f.source)).toList();
        heading("FOUND");
        if (drops.isEmpty()) {
            recent.add(leftAligned(mutedLabel("No drop seen" + (entry.first == null ? "" : "; first seen in your "
                    + "inventory or vault on " + entry.firstDate()) + ".")));
        }
        for (CollectionStore.Find f : drops) {
            recent.add(findRow(item.id, f, null));
            recent.add(Box.createVerticalStrut(6));
        }
        if (used.isEmpty()) return;
        heading("USED");
        for (CollectionStore.Find r : finds) {
            if (!CollectionStore.isRemoval(r)) continue;
            CollectionStore.Find was = used.get(r);
            recent.add(findRow(item.id, asUsed(r, was), null));
            recent.add(Box.createVerticalStrut(6));
        }
    }

    /**
     * Each removal (forge, feed) with the find of the copy it used up, if it can be told:
     * <ul>
     * <li>enchantments recorded with the removal: the latest earlier find with the same ones (and the same seasonal
     * flag, when both know it);</li>
     * <li>none recorded (before the client knew the slots): the copy last seen in your inventory before it, since
     * only items in your inventory can be forged or fed.</li>
     * </ul>
     *
     * @return removal to the find it used up (removals without one are keys with value null)
     */
    private static Map<CollectionStore.Find, CollectionStore.Find> usedCopies(List<CollectionStore.Find> history) {
        Map<CollectionStore.Find, CollectionStore.Find> used = new IdentityHashMap<>();
        Set<CollectionStore.Find> taken = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int i = 0; i < history.size(); i++) {
            CollectionStore.Find r = history.get(i);
            if (!CollectionStore.isRemoval(r)) continue;
            CollectionStore.Find match = null;
            for (int j = i - 1; j >= 0 && match == null; j--) {
                CollectionStore.Find f = history.get(j);
                if (CollectionStore.isRemoval(f) || taken.contains(f)) continue;
                boolean same = r.enchantIds != null ? r.enchantIds.equals(f.enchantIds)
                        && (r.seasonal == null || f.seasonal == null || r.seasonal.equals(f.seasonal))
                        : CollectionStore.INVENTORY.equals(f.source);
                if (same) match = f;
            }
            if (match != null) taken.add(match);
            used.put(r, match);
        }
        return used;
    }

    /** A line, then a small grey section title (USED, NOT FOUND) in the history list. */
    private JLabel heading(String text) {
        JSeparator line = new JSeparator();
        line.setAlignmentX(LEFT_ALIGNMENT);
        line.setMaximumSize(new Dimension(Integer.MAX_VALUE, 2));
        recent.add(Box.createVerticalStrut(6));
        recent.add(line);
        recent.add(Box.createVerticalStrut(8));
        JLabel title = leftAligned(new JLabel(text));
        title.putClientProperty("FlatLaf.styleClass", "small");
        title.setForeground(Palette.MUTED);
        recent.add(title);
        recent.add(Box.createVerticalStrut(6));
        return title;
    }

    private static JLabel mutedLabel(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(Palette.MUTED);
        return l;
    }

    /** "2 characters, your seasonal vault". */
    private static String gapsText(lootgobbler.Holdings.Gaps g) {
        List<String> parts = new ArrayList<>();
        if (g.characters() > 0) parts.add(g.characters() + (g.characters() == 1 ? " character" : " characters"));
        if (g.vault()) parts.add("your vault");
        if (g.seasonalVault()) parts.add("your seasonal vault");
        return String.join(", ", parts);
    }

    /**
     * A copy you have, as a history row: its slot ("Equipped", "Vault chest"...), season and enchantments, the
     * character or chest it is in, and when that place was last looked at.
     */
    private static CollectionStore.Find asFind(lootgobbler.Holdings.Held h) {
        CollectionStore.Find f = new CollectionStore.Find();
        f.source = h.charId() > 0 ? CollectionStore.INVENTORY : CollectionStore.VAULT;
        f.bag = h.slot();
        f.enchantIds = h.enchantIds();
        f.enchants = h.enchantIds() == null ? -1 : h.enchantIds().size();
        f.seasonal = h.seasonal();
        f.time = h.seenAt() <= 0 ? null : java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(h.seenAt()),
                java.time.ZoneId.systemDefault()).withNano(0).toString();
        if (h.charId() > 0) {
            lootgobbler.CharacterStore.get().all().stream().filter(c -> c.charId == h.charId()).findFirst()
                    .ifPresent(c -> f.seen = CollectionStore.Seen.character(c));
        } else {
            f.seen = CollectionStore.Seen.vault((lootgobbler.Holdings.SEASONAL_VAULT.equals(h.vault()) ? "seasonal " : "") + h.slot());
        }
        return f;
    }

    /** A removal shown as the copy it used up: the removal's time and source, the copy's enchantments and season. */
    private static CollectionStore.Find asUsed(CollectionStore.Find removal, CollectionStore.Find was) {
        if (was == null) return removal;
        CollectionStore.Find f = new CollectionStore.Find();
        f.time = removal.time;
        f.at = removal.at;
        f.source = removal.source;
        f.where = removal.where;
        f.bag = removal.bag;
        f.enchantIds = removal.enchantIds != null ? removal.enchantIds : was.enchantIds;
        f.enchants = removal.enchantIds != null ? removal.enchants : was.enchants;
        f.seasonal = removal.seasonal != null ? removal.seasonal : was.seasonal;
        f.seen = removal.seen != null ? removal.seen : was.seen;
        return f;
    }

    private static final Color UT_COLOR = new Color(0x8049A8), ST_COLOR = new Color(0xE07B2A), TIER_COLOR = new Color(0xB07D0A);

    /**
     * The top of the history: the item big, its kind (UT / ST / tier, slot, shiny), how often you found it,
     * the dungeon it drops in (when the game data says) and where you found it yourself.
     */
    private JComponent itemHeader(ItemDb.Item item, CollectionStore.Entry entry) {
        JLabel icon = new JLabel(CharactersView.sprite(item.id, 72));
        icon.setVerticalAlignment(SwingConstants.TOP);

        JPanel text = new JPanel();
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        text.setOpaque(false);
        JLabel name = new JLabel("<html>" + escapeHtml(item.name) + "</html>");
        name.setFont(name.getFont().deriveFont(22f));
        text.add(leftAligned(name));
        text.add(Box.createVerticalStrut(4));

        JPanel kind = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        kind.setOpaque(false);
        String tier = item.isUT() ? "UT" : item.isST() ? "ST" : item.tier.isEmpty() ? "" : "T" + item.tier;
        if (!tier.isEmpty()) kind.add(badge(tier, item.isUT() ? UT_COLOR : item.isST() ? ST_COLOR : TIER_COLOR));
        if (item.isShiny()) kind.add(badge("SHINY", new Color(0x3FA7C4)));
        kind.add(new JLabel(item.slotName()));
        kind.setBorder(new EmptyBorder(0, -6, 0, 0));   // FlowLayout puts its gap before the first tag too
        text.add(leftAligned(kind));
        text.add(Box.createVerticalStrut(6));

        int held = upeChar != 0 ? (upeItems.containsKey(item.id) ? upeItems.get(item.id).count : 0)   // the UPE's own
                : CollectionStore.get().held(item.id);   // the copies you have now, wherever they are
        JLabel count = new JLabel("Count: " + held);
        count.setFont(count.getFont().deriveFont(20f));   // smaller, the pixel font draws a 6 that looks like an 8
        count.setForeground(held == 0 ? Palette.MUTED : new Color(0xF5C542));
        text.add(leftAligned(count));

        // where it drops: from the game data, else the dungeons your own drops of it came from
        String origin = item.origin();
        String places = origin != null ? origin : foundIn(entry);
        if (!places.isEmpty()) text.add(leftAligned(fact("Drops in", places)));
        else {
            JLabel unknown = new JLabel("Drop location not known yet");
            unknown.setForeground(Palette.MUTED);
            text.add(leftAligned(unknown));
        }

        JPanel header = new JPanel(new BorderLayout(12, 0));
        header.setOpaque(false);
        header.setBorder(new EmptyBorder(4, 0, 10, 0));
        header.add(icon, BorderLayout.WEST);
        header.add(text, BorderLayout.CENTER);
        JPanel box = new JPanel(new BorderLayout());
        box.setOpaque(false);
        box.add(header, BorderLayout.CENTER);
        JSeparator line = new JSeparator();
        box.add(line, BorderLayout.SOUTH);
        box.setAlignmentX(LEFT_ALIGNMENT);
        box.setMaximumSize(new Dimension(Integer.MAX_VALUE, box.getPreferredSize().height));
        return box;
    }

    /**
     * The dungeons your drops of this item came from, most first: "Lost Halls ×3, The Shatters".
     */
    private static String foundIn(CollectionStore.Entry entry) {
        if (entry == null) return "";
        Map<String, Integer> places = new LinkedHashMap<>();
        for (CollectionStore.Find f : entry.history()) {
            if (CollectionStore.DROP.equals(f.source) && !f.where.isBlank()) places.merge(f.where, 1, Integer::sum);
        }
        return places.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(e -> e.getKey() + (e.getValue() > 1 ? " ×" + e.getValue() : ""))
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static JLabel fact(String caption, String value) {
        JLabel l = new JLabel("<html><font color='#938B79'>" + caption + ":</font> " + escapeHtml(value) + "</html>");
        return l;
    }

    /**
     * A small colored tag like "UT".
     */
    private static JLabel badge(String text, Color bg) {
        JLabel l = new JLabel(text);
        l.setOpaque(true);
        l.setBackground(bg);
        l.setForeground(Color.WHITE);
        l.setBorder(new EmptyBorder(1, 6, 1, 6));
        return l;
    }


    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static <T extends JComponent> T leftAligned(T c) {
        c.setAlignmentX(LEFT_ALIGNMENT);
        return c;
    }

    /** Where a find came from: "Vault chest", "Inventory", "Orange bag · Lost Halls", "Forged (used in the forge)". */
    private static String how(CollectionStore.Find f) {
        return switch (f.source) {
            case CollectionStore.VAULT -> f.bag.isEmpty() ? "Vault" : f.bag;
            case CollectionStore.INVENTORY -> f.bag.isEmpty() ? "Inventory" : f.bag;   // "Equipped", "Quick slot"...
            case CollectionStore.FORGED -> "Forged (used in the forge)";
            case CollectionStore.FED -> "Fed to a pet";
            case CollectionStore.DROPPED -> "Dropped on the ground";
            case CollectionStore.CONSUMED -> "Used up (forge or pet)";
            default -> (f.bag.isEmpty() ? "Drop" : f.bag + " bag") + (f.where.isEmpty() ? "" : "  ·  " + f.where);
        };
    }

    /**
     * One find of the history: icon with enchantment diamonds; where it came from with seasonal or not on the same
     * line and its enchantments below; the character (in its skin and dyes) or vault chest it was seen in last; and
     * how long ago.
     *
     * @param note a small grey addition after the place (e.g. "was: Inventory" for a used copy), or null
     */
    private static JPanel findRow(int itemId, CollectionStore.Find f, String note) {
        String how = how(f);
        // one line: the place (cut short with "..." in a narrow window), then seasonal or not and a grey note
        JLabel what = new JLabel(how);
        what.setToolTipText(how);
        what.setMinimumSize(new Dimension(30, what.getPreferredSize().height));
        JPanel line = new JPanel();
        line.setLayout(new BoxLayout(line, BoxLayout.X_AXIS));
        line.setOpaque(false);
        line.add(what);
        // seasonal or not: teal like the Characters tab's seasonal tag, red for non-seasonal
        if (f.seasonal != null) {
            JLabel season = new JLabel(f.seasonal ? "(seasonal)" : "(non-seasonal)");
            season.setForeground(f.seasonal ? new Color(0x2BD8B0) : new Color(0xE5636E));
            season.setToolTipText(f.seasonal ? "Found on a seasonal character" : "Found on a non-seasonal character");
            season.setBorder(new EmptyBorder(0, 10, 0, 0));
            line.add(season);
        }
        if (note != null) {
            JLabel gone = new JLabel("  · " + note);
            gone.setForeground(Palette.MUTED);
            line.add(gone);
        }
        line.add(Box.createHorizontalGlue());
        line.setMaximumSize(new Dimension(Integer.MAX_VALUE, line.getPreferredSize().height));
        JPanel text = new JPanel();
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        text.setOpaque(false);
        text.add(leftAligned(line));
        int count = f.enchantIds != null ? f.enchantIds.size() : Math.max(0, f.enchants);
        if (f.enchantIds != null && f.enchantIds.stream().anyMatch(id -> id >= 0)) {
            text.add(Box.createVerticalStrut(4));
            text.add(leftAligned(enchantIcons(f.enchantIds)));
        }
        // not known yet, which is not the same as "no slots" (a removal without them just shows nothing)
        if (f.enchantIds == null && f.enchants < 0 && !CollectionStore.isRemoval(f)) {
            JLabel unknown = new JLabel("enchantments unknown");
            unknown.putClientProperty("FlatLaf.styleClass", "small");
            unknown.setForeground(Palette.MUTED);
            unknown.setToolTipText("Filled in once the game sends this item's enchantments (character list at login, or Refresh in Characters)");
            text.add(leftAligned(unknown));
        }
        JPanel middle = new JPanel(new GridBagLayout());   // keeps the text block centered next to the icon
        middle.setOpaque(false);
        GridBagConstraints at = new GridBagConstraints();
        at.weightx = 1;
        at.fill = GridBagConstraints.HORIZONTAL;
        middle.add(text, at);

        // item icon with in-game style enchantment diamonds
        JLabel icon = new JLabel(EnchantBadge.icon(CollectionGrid.icon(itemId), 32, count));
        if (count > 0) icon.setToolTipText(count + (count == 1 ? " enchantment" : " enchantments") + " (" + EnchantBadge.RARITY[Math.min(4, count)] + ")");
        // how long ago, counted in days from today, big and gold on the right; exact time on hover
        JLabel ago = new JLabel(daysAgo(f.time));
        Font titleFont = UIManager.getFont("TabbedPane.font");   // same as section titles like "PROGRESS"
        if (titleFont != null) ago.setFont(titleFont.deriveFont(titleFont.getStyle()));
        ago.setForeground(new Color(0xF5C542));
        if (f.time != null) ago.setToolTipText(f.time.replace('T', ' '));
        // the time in a column of its own width, so the "seen" tiles of all rows line up
        ago.setHorizontalAlignment(SwingConstants.RIGHT);
        ago.setPreferredSize(new Dimension(ago.getFontMetrics(ago.getFont()).stringWidth("999 days") + 4,
                ago.getPreferredSize().height));
        JPanel right = new JPanel(new BorderLayout(16, 0));
        right.setOpaque(false);
        right.add(seenTile(f.seen), BorderLayout.WEST);
        right.add(ago, BorderLayout.EAST);
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        row.setBorder(new EmptyBorder(4, 0, 4, 0));
        row.add(icon, BorderLayout.WEST);
        row.add(middle, BorderLayout.CENTER);
        row.add(right, BorderLayout.EAST);
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        return row;
    }

    private static final int SEEN_TILE = 52, SEEN_SPRITE = 44;
    private static final int VAULT_CHEST = 0x0504;

    /**
     * Where the copy was seen last: the character in its skin and dyes as it looked then, or a vault chest, on the
     * row's own background; an empty space (same size, so the rows line up) until it has been seen with this version.
     */
    private static JComponent seenTile(CollectionStore.Seen seen) {
        Icon sprite = null;
        String tip = "Not seen since Loot Gobbler started remembering where copies are";
        if (seen != null && seen.charId > 0) {
            int sprite0 = seen.skin > 0 && !ItemDb.get().isTransformSkin(seen.skin) ? seen.skin : seen.classType;
            try {
                sprite = lootgobbler.Dyes.icon(sprite0, seen.tex1, seen.tex2, SEEN_SPRITE);
            } catch (Exception e) {
                sprite = CharactersView.sprite(sprite0, SEEN_SPRITE);
            }
            tip = "Last seen on your " + (seen.place.isEmpty() ? "character" : seen.place)
                    + (seen.seasonal ? " (seasonal)" : "") + " #" + seen.charId;
        } else if (seen != null) {
            sprite = CharactersView.sprite(VAULT_CHEST, SEEN_SPRITE);
            tip = "Last seen in your " + seen.place.toLowerCase(Locale.ROOT);
        }
        Icon shown = sprite;
        JLabel tile = new JLabel() {
            @Override
            protected void paintComponent(Graphics g) {
                if (shown != null) shown.paintIcon(this, g, (SEEN_TILE - shown.getIconWidth()) / 2, (SEEN_TILE - shown.getIconHeight()) / 2);
            }
        };
        Dimension d = new Dimension(SEEN_TILE, SEEN_TILE);
        tile.setPreferredSize(d);
        tile.setMinimumSize(d);
        tile.setMaximumSize(d);
        if (shown != null) tile.setToolTipText(tip);
        JPanel holder = new JPanel(new GridBagLayout());   // centered in the row's height
        holder.setOpaque(false);
        holder.add(tile);
        return holder;
    }

    /**
     * "today", "1 day", "63 days": calendar days between the find and today.
     */
    private static String daysAgo(String isoTime) {
        if (isoTime == null || isoTime.length() < 10) return "";
        try {
            long days = java.time.temporal.ChronoUnit.DAYS.between(
                    java.time.LocalDate.parse(isoTime.substring(0, 10)), java.time.LocalDate.now());
            return days <= 0 ? "today" : days == 1 ? "1 day" : days + " days";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * One icon per enchantment (hover for its name); the rarity color frames the row.
     */
    private static JPanel enchantIcons(List<Integer> ids) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        p.setOpaque(false);
        p.setBorder(new EmptyBorder(0, -4, 0, 0));   // FlowLayout puts its gap before the first icon too
        Color frame = EnchantBadge.COLORS[Math.min(4, ids.size())];
        for (int id : ids) {
            if (id < 0) continue;   // empty slot: counts for rarity, no icon
            Image img = lootgobbler.EnchantIcons.icon(id, 20);
            JLabel l = img != null ? new JLabel(new ImageIcon(img)) : new JLabel("?");
            l.setBorder(BorderFactory.createLineBorder(frame));
            l.setToolTipText(lootgobbler.EnchantIcons.name(id));
            p.add(l);
        }
        return p;
    }

    private List<ItemDb.Item> filter(int mode) {
        if (mode == ALL) return universe;
        if (mode == REUSABLE) return universe.stream().filter(i -> i.quickslotOnly).toList();
        return universe.stream().filter(i -> (mode == SHINY) == i.isShiny()).toList();
    }

    /**
     * Items matching the search box: by name, type (slot), UT / ST / T14 tag, "reusable" / "quick slot" for the
     * quick-slot items, or an enchantment found on them.
     */
    private List<ItemDb.Item> matching(List<ItemDb.Item> items, Map<Integer, CollectionStore.Entry> owned) {
        String q = search.getText().trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return items;
        return items.stream().filter(i -> i.name.toLowerCase(Locale.ROOT).contains(q)
                || i.slotName().toLowerCase(Locale.ROOT).contains(q)
                || i.tierTag().toLowerCase(Locale.ROOT).replace("(", "").replace(")", "").equals(q)
                || (!i.tier.isEmpty() && q.equals("t" + i.tier))
                || (i.quickslotOnly && ("reusable".startsWith(q) || "quick slot".startsWith(q) || "quickslot".startsWith(q)))
                || hasEnchant(owned.get(i.id), q)).toList();
    }

    private static boolean hasEnchant(CollectionStore.Entry entry, String q) {
        if (entry == null) return false;
        for (CollectionStore.Find f : entry.history()) {
            if (f.enchantIds == null) continue;
            for (int id : f.enchantIds) {
                if (id < 0) continue;
                String name = lootgobbler.EnchantIcons.name(id);
                if (name != null && name.toLowerCase(Locale.ROOT).contains(q)) return true;
            }
        }
        return false;
    }

    private static void setProgress(JProgressBar bar, JLabel text, List<ItemDb.Item> items,
                                    Map<Integer, CollectionStore.Entry> owned) {
        long have = items.stream().filter(i -> owned.containsKey(i.id)).count();
        double pct = items.isEmpty() ? 0 : 100.0 * have / items.size();
        bar.setValue((int) Math.round(pct * 10));
        text.setText(have + " / " + items.size() + "   " + String.format("%.1f%%", pct));
    }

    private JPanel actionsBody() {
        JPanel p = new JPanel(new GridLayout(0, 1, 0, 6));
        p.setBorder(new EmptyBorder(10, 10, 10, 10));
        p.add(button("Copy as text", null, this::copyText));
        p.add(button("Copy as image", null, this::copyImage));
        clearButton = button("Clear collection", "danger", this::clearCollection);
        p.add(clearButton);
        return p;
    }

    /**
     * The collection without entries that are not collectible gear (e.g. consumables recorded by older versions).
     */
    /** The collectible items you hold a copy of (all forged or fed away = not collected). */
    private Map<Integer, CollectionStore.Entry> collected() {
        if (upeChar != 0) return new java.util.HashMap<>(upeItems);   // a UPE character's own finds
        Map<Integer, CollectionStore.Entry> all = CollectionStore.get().snapshot();
        all.entrySet().removeIf(e -> {
            ItemDb.Item item = ItemDb.get().item(e.getKey());
            return item == null || !item.isCollectible() || CollectionStore.get().held(e.getKey()) == 0;
        });
        return all;
    }

    private void copyText() {
        Map<Integer, CollectionStore.Entry> owned = collected();
        List<String> lines = new ArrayList<>();
        for (Map.Entry<Integer, CollectionStore.Entry> e : owned.entrySet()) {
            ItemDb.Item item = ItemDb.get().item(e.getKey());
            String line = name(e.getKey());
            if (item != null && item.isShiny() && !line.contains("Shiny")) line += " (Shiny)";
            int n = CollectionStore.get().held(e.getKey());
            if (n > 1) line += " x" + n;
            lines.add(line);
        }
        lines.sort(String.CASE_INSENSITIVE_ORDER);
        StringBuilder sb = new StringBuilder("ROTMG Collection — " + owned.size() + " items\n");
        for (String l : lines) sb.append("- ").append(l).append('\n');
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(sb.toString()), null);
        JOptionPane.showMessageDialog(this, "Collection copied to the clipboard as text.", "Copy as text",
                JOptionPane.INFORMATION_MESSAGE);
    }

    /**
     * Copies the grid as one image, laid out with the same number of columns the grid shows right now.
     */
    private void copyImage() {
        int cols = grid.getWidth() > 0 ? grid.columns() : 12;
        Image img = grid.renderImage(cols);
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new ImageSelection(img), null);
        JOptionPane.showMessageDialog(this, "Collection grid copied to the clipboard as an image.", "Copy as image",
                JOptionPane.INFORMATION_MESSAGE);
    }

    private void clearCollection() {
        int ok = JOptionPane.showConfirmDialog(this, "Delete your whole collection history? This cannot be undone.",
                "Clear collection", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (ok == JOptionPane.YES_OPTION) CollectionStore.get().clear();
    }

    /**
     * A recently found item: its icon with the enchantments of the newest copy, and its name. Click: the item in
     * the grid (scrolled to, with the gold pulse) and its history.
     */
    private JPanel recentRow(int id, CollectionStore.Entry e) {
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        row.setAlignmentX(LEFT_ALIGNMENT);
        int enchants = 0;
        List<CollectionStore.Find> finds = e.history();
        for (int i = finds.size() - 1; i >= 0; i--) {   // the newest copy you found
            CollectionStore.Find f = finds.get(i);
            if (CollectionStore.isRemoval(f)) continue;
            enchants = f.enchantIds != null ? f.enchantIds.size() : Math.max(0, f.enchants);
            break;
        }
        JLabel name = new JLabel(name(id), EnchantBadge.icon(CollectionGrid.icon(id), 32, enchants), SwingConstants.LEFT);
        name.setIconTextGap(10);
        row.add(name, BorderLayout.CENTER);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        ItemDb.Item item = ItemDb.get().item(id);
        if (item != null) Clicks.onClick(row, () -> showHistory(item, true));
        return row;
    }

    /** The day the items below were found: "Today", "Yesterday" or "1 Nov", with a line (not above the first). */
    private static JComponent dayLine(String isoDate, boolean first) {
        String text = isoDate;
        try {
            java.time.LocalDate d = java.time.LocalDate.parse(isoDate), today = java.time.LocalDate.now();
            text = d.equals(today) ? "Today" : d.equals(today.minusDays(1)) ? "Yesterday"
                    : d.format(java.time.format.DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH));
        } catch (Exception ignore) {   // no date known
        }
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);
        p.setAlignmentX(LEFT_ALIGNMENT);
        if (!first) {
            p.add(Box.createVerticalStrut(4));
            JSeparator line = new JSeparator();
            line.setAlignmentX(LEFT_ALIGNMENT);
            line.setMaximumSize(new Dimension(Integer.MAX_VALUE, 2));
            p.add(line);
        }
        p.add(Box.createVerticalStrut(4));
        JLabel l = new JLabel(text.toUpperCase(Locale.ROOT));
        l.putClientProperty("FlatLaf.styleClass", "small");
        l.setForeground(Palette.MUTED);
        l.setAlignmentX(LEFT_ALIGNMENT);
        p.add(l);
        p.add(Box.createVerticalStrut(4));
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
        return p;
    }

    private static String name(int id) {
        ItemDb.Item item = ItemDb.get().item(id);
        if (item != null && !item.name.isEmpty()) return item.name;
        String n = IdToAsset.objectName(id);
        return n == null ? "Item #" + id : n;
    }

    private static JPanel progressBody(String caption, JProgressBar bar, JLabel text) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(new EmptyBorder(10, 10, 10, 10));
        JLabel c = new JLabel(caption.toUpperCase(Locale.ROOT));
        c.putClientProperty("FlatLaf.styleClass", "small");
        c.setAlignmentX(LEFT_ALIGNMENT);
        bar.setAlignmentX(LEFT_ALIGNMENT);
        text.setAlignmentX(LEFT_ALIGNMENT);
        p.add(c);
        p.add(Box.createVerticalStrut(4));
        p.add(bar);
        p.add(Box.createVerticalStrut(4));
        p.add(text);
        return p;
    }

    private static JProgressBar bar(Color color) {
        JProgressBar b = new JProgressBar(0, 1000);
        if (color != null) b.setForeground(color);
        b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 12));
        b.setPreferredSize(new Dimension(200, 12));
        return b;
    }

    private static JButton button(String text, String style, Runnable action) {
        JButton b = new JButton(text.toUpperCase(Locale.ROOT));
        if (style != null) b.putClientProperty("FlatLaf.styleClass", style);
        b.addActionListener(e -> action.run());
        return b;
    }

    private static JScrollPane scroll(JComponent c) {
        JScrollPane sp = new JScrollPane(c);
        sp.setBorder(null);
        sp.getVerticalScrollBar().setUnitIncrement(CollectionGrid.CELL);
        return sp;
    }

    private static JPanel column(JComponent... parts) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) p.add(Box.createVerticalStrut(8));
            parts[i].setAlignmentX(LEFT_ALIGNMENT);
            p.add(parts[i]);
        }
        return p;
    }

    /**
     * Main area on the left, a resizable side column on the right (drag the gap; width saved per view), with the
     * top parts at natural height and the filler below.
     */
    private static SideSplit split(String name, JComponent main, JComponent sideTop, JComponent sideFill,
                                   CollectionGrid grid) {
        JPanel side = new JPanel(new BorderLayout(0, 8));
        side.setOpaque(false);
        side.add(sideTop, BorderLayout.NORTH);
        if (sideFill != null) side.add(sideFill, BorderLayout.CENTER);
        SideSplit split = new SideSplit(main, side, name, 380, 200);
        // the divider settles where the grid shows whole item columns
        if (grid.getParent() instanceof JViewport vp) split.setSnap(vp, CollectionGrid::snapWidth);
        return split;
    }

    private static void fixWidth(JTable t, int col, int w) {
        t.getColumnModel().getColumn(col).setMinWidth(w);
        t.getColumnModel().getColumn(col).setMaxWidth(w);
    }

    private static class LogRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                       boolean focus, int row, int column) {
            super.getTableCellRendererComponent(table, value, selected, false, row, column);
            setBorder(new EmptyBorder(0, 10, 0, 10));
            setIcon(null);
            setHorizontalAlignment(LEFT);
            if (!selected) {
                Color alt = UIManager.getColor("Table.alternateRowColor");
                setBackground(row % 2 == 1 && alt != null ? alt : table.getBackground());
                setForeground(table.getForeground());
            }
            if (column == 1) {
                Image icon = CollectionGrid.icon((Integer) value);
                setText("");
                setIcon(icon == null ? null : new ImageIcon(icon));
            } else if (column == 3) {
                setText("x" + value);
                setHorizontalAlignment(RIGHT);
                if (!selected) setForeground(Palette.GOLD);
            }
            return this;
        }
    }

    record ImageSelection(Image image) implements Transferable {   // also used by LoadoutCard
        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[]{DataFlavor.imageFlavor};
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return DataFlavor.imageFlavor.equals(flavor);
        }

        @Override
        public Object getTransferData(DataFlavor flavor) {
            return image;
        }
    }
}
