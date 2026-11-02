package lootgobbler.ui;

import assets.IdToAsset;
import lootgobbler.CollectionStore;
import lootgobbler.ItemDb;
import lootgobbler.StSets;

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
    private static final int ALL = 0, SHINY = 1, NORMAL = 2;
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

    public CollectionPanel() {
        super(new BorderLayout(0, 8));
        setBorder(new EmptyBorder(8, 8, 8, 8));

        viewPicker = new Segmented(false, 0, i -> cards.show(views, VIEWS[i]), "Items", "ST Sets", "Log");
        add(viewPicker, BorderLayout.NORTH);
        grid.onItemClick(item -> showHistory(item, false));
        views.setOpaque(false);
        views.add(gridView(), VIEWS[0]);
        views.add(stView, VIEWS[1]);
        views.add(logView(), VIEWS[2]);
        add(views, BorderLayout.CENTER);

        CollectionStore.get().onChange(() -> SwingUtilities.invokeLater(this::refresh));
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
        }, "All", "Shiny", "Normal");
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
        Section main = new Section("Collection · Items", Palette.GOLD, scroll(grid)).actions(headerActions);

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

    private void refresh() {
        if (!loaded) return;
        Map<Integer, CollectionStore.Entry> owned = collected();
        List<ItemDb.Item> g = matching(filter(gridFilter), owned);
        grid.setData(g, owned);
        stView.setData(stSets, owned);
        setProgress(progress, progressText, g, owned);

        List<Map.Entry<Integer, CollectionStore.Entry>> byLast = new ArrayList<>(owned.entrySet());
        byLast.sort((a, b) -> String.valueOf(b.getValue().last).compareTo(String.valueOf(a.getValue().last)));

        recent.removeAll();
        if (historyItem != null) {
            renderHistory(historyItem, CollectionStore.get().snapshot().get(historyItem.id));   // also when all are gone
        } else {
            sideSection.setTitle("Recent");
            backToRecent.setVisible(false);
            if (byLast.isEmpty()) recent.add(new JLabel("Nothing collected yet."));
            for (int i = 0; i < Math.min(RECENT, byLast.size()); i++) {
                recent.add(recentRow(byLast.get(i).getKey(), byLast.get(i).getValue()));
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

        if (entry == null) return;
        // the copies you have on top; below a line, the ones used up (forged, fed), each shown as the copy it was
        Map<CollectionStore.Find, CollectionStore.Find> used = usedCopies(entry.history());
        List<CollectionStore.Find> finds = new ArrayList<>(entry.history());
        Collections.reverse(finds);   // latest at the top
        for (CollectionStore.Find f : finds) {
            if (CollectionStore.isRemoval(f) || used.containsValue(f)) continue;
            recent.add(findRow(item.id, f, null));
            recent.add(Box.createVerticalStrut(6));
        }
        if (used.isEmpty()) return;
        JSeparator line = new JSeparator();
        line.setAlignmentX(LEFT_ALIGNMENT);
        line.setMaximumSize(new Dimension(Integer.MAX_VALUE, 2));
        recent.add(Box.createVerticalStrut(6));
        recent.add(line);
        recent.add(Box.createVerticalStrut(8));
        JLabel title = leftAligned(new JLabel("USED"));
        title.putClientProperty("FlatLaf.styleClass", "small");
        title.setForeground(Palette.MUTED);
        recent.add(title);
        recent.add(Box.createVerticalStrut(6));
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
        return f;
    }

    private static final Color UT_COLOR = new Color(0x8049A8), ST_COLOR = new Color(0xE07B2A), TIER_COLOR = new Color(0xB07D0A);

    /**
     * The top of the history: the item big, its kind (UT / ST / tier, slot, shiny), how often you found it,
     * the dungeon it drops in (when the game data says) and where you found it yourself.
     */
    private static JComponent itemHeader(ItemDb.Item item, CollectionStore.Entry entry) {
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

        String found = entry == null ? "Not found yet" : entry.count == 1 ? "Found 1 time" : "Found " + entry.count + " times";
        if (entry != null && entry.removed() > 0) found += "  ·  " + entry.held() + " left";
        JLabel count = new JLabel(found);
        count.setFont(count.getFont().deriveFont(20f));   // smaller, the pixel font draws a 6 that looks like an 8
        count.setForeground(entry == null ? Palette.MUTED : new Color(0xF5C542));
        text.add(leftAligned(count));

        String origin = item.origin();
        if (origin != null) text.add(leftAligned(fact("Drops in", origin)));
        String places = foundIn(entry);
        if (!places.isEmpty()) text.add(leftAligned(fact("You found it in", places)));
        if (origin == null && places.isEmpty()) {
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
            case CollectionStore.INVENTORY -> "Inventory";
            case CollectionStore.FORGED -> "Forged (used in the forge)";
            case CollectionStore.FED -> "Fed to a pet";
            default -> (f.bag.isEmpty() ? "Drop" : f.bag + " bag") + (f.where.isEmpty() ? "" : "  ·  " + f.where);
        };
    }

    /**
     * One find of the history: icon with enchantment diamonds, where it came from, seasonal or not, its
     * enchantments and how long ago.
     *
     * @param note a small grey addition after the place (e.g. "was: Inventory" for a used copy), or null
     */
    private static JPanel findRow(int itemId, CollectionStore.Find f, String note) {
        String how = how(f);
        // seasonal or not, behind the place: teal like the Characters tab's seasonal tag, red for non-seasonal
        String season = f.seasonal == null ? "" : f.seasonal
                ? "  <font color='#2BD8B0'>(seasonal)</font>" : "  <font color='#E5636E'>(non-seasonal)</font>";
        String goneNote = note == null ? "" : "  <font color='#938B79'>· " + escapeHtml(note) + "</font>";
        JLabel what = leftAligned(new JLabel("<html>" + escapeHtml(how) + season + goneNote + "</html>"));
        if (f.seasonal != null) what.setToolTipText(f.seasonal ? "Found on a seasonal character" : "Found on a non-seasonal character");
        JPanel text = new JPanel();
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        text.setOpaque(false);
        text.add(what);
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
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        row.add(icon, BorderLayout.WEST);
        row.add(middle, BorderLayout.CENTER);
        row.add(ago, BorderLayout.EAST);
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        return row;
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
        return universe.stream().filter(i -> (mode == SHINY) == i.isShiny()).toList();
    }

    /**
     * Items matching the search box: by name, type (slot), UT / ST / T14 tag, or an enchantment found on them.
     */
    private List<ItemDb.Item> matching(List<ItemDb.Item> items, Map<Integer, CollectionStore.Entry> owned) {
        String q = search.getText().trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return items;
        return items.stream().filter(i -> i.name.toLowerCase(Locale.ROOT).contains(q)
                || i.slotName().toLowerCase(Locale.ROOT).contains(q)
                || i.tierTag().toLowerCase(Locale.ROOT).replace("(", "").replace(")", "").equals(q)
                || (!i.tier.isEmpty() && q.equals("t" + i.tier))
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
        p.add(button("Clear collection", "danger", this::clearCollection));
        return p;
    }

    /**
     * The collection without entries that are not collectible gear (e.g. consumables recorded by older versions).
     */
    /** The collectible items you hold a copy of (all forged or fed away = not collected). */
    private static Map<Integer, CollectionStore.Entry> collected() {
        Map<Integer, CollectionStore.Entry> all = CollectionStore.get().snapshot();
        all.entrySet().removeIf(e -> {
            ItemDb.Item item = ItemDb.get().item(e.getKey());
            return item == null || !item.isCollectible() || e.getValue().held() == 0;
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
            if (e.getValue().held() > 1) line += " x" + e.getValue().held();
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

    private JPanel recentRow(int id, CollectionStore.Entry e) {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        row.setAlignmentX(LEFT_ALIGNMENT);
        Image icon = CollectionGrid.icon(id);
        JLabel name = new JLabel(name(id), icon == null ? null : new ImageIcon(icon.getScaledInstance(22, 22, Image.SCALE_REPLICATE)), SwingConstants.LEFT);
        name.setIconTextGap(8);
        JLabel date = new JLabel(e.lastDate());
        date.setForeground(Palette.MUTED);
        row.add(name, BorderLayout.CENTER);
        row.add(date, BorderLayout.EAST);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        return row;
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

    private record ImageSelection(Image image) implements Transferable {
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
