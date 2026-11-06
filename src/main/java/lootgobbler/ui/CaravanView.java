package lootgobbler.ui;

import lootgobbler.CharacterStore;
import lootgobbler.CollectionStore;
import lootgobbler.GobblerServer;
import lootgobbler.GobblerServer.Caravan;
import lootgobbler.GobblerServer.CaravanItem;
import lootgobbler.GobblerServer.CaravanMember;
import lootgobbler.ItemDb;
import lootgobbler.ItemScore;
import lootgobbler.ServerSync;
import lootgobbler.Themes;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.HierarchyEvent;
import java.awt.geom.AffineTransform;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.List;

/**
 * Guild > Loot Caravan: a night road where every guild member drives a wagon piled with the items they found in
 * the chosen range, sitting up front as their most played character. The member with the highest score (the points
 * of their items, see ItemScore) leads, right behind the pack ox, with the score on their wagon; members with fewer
 * than five items walk behind the caravan with a backpack. Clicking a member
 * shows their items per bag color and best finds below the picture.
 * <p>
 * Your own drops land on your wagon the moment they drop and are uploaded within seconds; guildies' drops show up
 * with the check every minute while the caravan is on screen.
 */
public class CaravanView extends JPanel {
    private static final com.google.gson.Gson GSON = new com.google.gson.Gson();
    private static final NumberFormat NUM = NumberFormat.getIntegerInstance();
    private static final String[] RANGES = {"week", "month", "all"}, RANGE_LABELS = {"This week", "This month", "All time"};
    private static final int WALK_BELOW = 5;   // fewer items than this: on foot
    /** Bag colors, rarest first: the order of the bag counts, and the pile order from the top down. */
    private static final List<String> BAG_ORDER = List.of("White", "Orange", "Red", "Gold", "Teal", "Blue", "Purple",
            "Egg Basket", "Pink", "Brown", "Other");
    private static final Map<String, Color> BAG_COLORS = Map.ofEntries(Map.entry("White", new Color(0xF4F4F4)),
            Map.entry("Orange", new Color(0xF08A2A)), Map.entry("Red", new Color(0xD8285A)),
            Map.entry("Gold", new Color(0xE5B23A)), Map.entry("Teal", new Color(0x3FC3C0)),
            Map.entry("Blue", new Color(0x4C8FE0)), Map.entry("Purple", new Color(0x8049A8)),
            Map.entry("Pink", new Color(0xE0679C)), Map.entry("Brown", new Color(0x8C6C47)));

    private final GobblerServer server = GobblerServer.get();
    private final Section section;
    private final Scene scene = new Scene(this::pick);
    private final JPanel detail = new JPanel(new GridLayout(1, 2, 16, 0));
    private final JLabel status = new JLabel();
    private String range = RANGES[0];
    private Caravan caravan;
    private long picked = -1;   // user id of the member shown below
    private boolean loading, again, stale;   // again: reload once the current one is done; stale: reload when shown
    private String shown;   // the caravan as last drawn, to skip redrawing when unchanged

    public CaravanView() {
        super(new BorderLayout());
        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setOpaque(false);
        body.setBorder(new EmptyBorder(12, 12, 12, 12));

        JScrollPane road = new JScrollPane(scene, ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        road.setBorder(BorderFactory.createLineBorder(Section.color("LootGobbler.line", Color.GRAY), 2));
        road.getHorizontalScrollBar().setUnitIncrement(24);
        int h = Scene.HEIGHT + road.getHorizontalScrollBar().getPreferredSize().height + 4;
        road.setPreferredSize(new Dimension(400, h));
        road.setMaximumSize(new Dimension(Integer.MAX_VALUE, h));
        road.setAlignmentX(LEFT_ALIGNMENT);
        body.add(road);
        body.add(Box.createVerticalStrut(8));
        status.setForeground(Palette.MUTED);
        status.setAlignmentX(LEFT_ALIGNMENT);
        body.add(status);
        body.add(Box.createVerticalStrut(12));
        detail.setOpaque(false);
        detail.setAlignmentX(LEFT_ALIGNMENT);
        body.add(detail);

        JPanel holder = new JPanel(new BorderLayout());
        holder.setOpaque(false);
        holder.add(body, BorderLayout.NORTH);
        Segmented ranges = new Segmented(true, 0, i -> {
            range = RANGES[i];
            load();
        }, RANGE_LABELS);
        JButton refresh = new JButton("Refresh");
        refresh.addActionListener(e -> load());
        section = new Section("Loot Caravan", Palette.GOLD, FitWidthPanel.scroll(holder, 24)).actions(ranges, refresh);
        add(section);
        message("Open this tab while logged in to a server to see your guild.");

        CollectionStore.get().onDrop((id, find) -> SwingUtilities.invokeLater(() -> dropped(id, find)));
        ServerSync.get().onUploaded(kind -> {   // your drops reached the server
            if (kind.equals("collection")) SwingUtilities.invokeLater(this::changed);
        });
        ItemScore.get().onChange(() -> SwingUtilities.invokeLater(() -> {   // an admin changed the weights
            if (caravan != null) show(caravan);
        }));
        new javax.swing.Timer(QuestMapView.POLL_MS, e -> {   // guildies' drops
            if (isShowing()) load();
        }).start();
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing() && stale) load();
        });
    }

    /** Your data on the server changed: reload now if visible, else when shown. */
    private void changed() {
        if (isShowing()) load();
        else stale = true;
    }

    /** One of your drops: onto your wagon right away (the server counts it once the upload is in). */
    private void dropped(int itemId, CollectionStore.Find find) {
        GobblerServer.User me = server.user();
        if (caravan == null || me == null) return;
        List<CaravanMember> members = new ArrayList<>(caravan.members);
        CaravanMember m = members.stream().filter(x -> x.id == me.id).findFirst().orElse(null);
        if (m == null) {
            m = new CaravanMember();
            m.id = me.id;
            m.ign = me.ign;
            members.add(m);
        }
        CaravanItem item = new CaravanItem();
        item.itemId = itemId;
        item.bag = bagName(find.bag);
        item.time = find.time;
        item.where = find.where;
        item.enchants = find.enchantIds != null ? find.enchantIds.size() : find.enchants;
        m.items = new ArrayList<>(m.items);
        m.items.add(0, item);
        if (!m.tally.isEmpty() || m.total == 0) {   // older servers send no tally: the score comes from the items then
            m.tally = new ArrayList<>(m.tally);
            GobblerServer.Tally t = m.tally.stream().filter(x -> x.itemId == itemId && x.enchants == item.enchants
                    && Objects.equals(x.where, item.where)).findFirst().orElse(null);
            if (t == null) {
                t = new GobblerServer.Tally();
                t.itemId = itemId;
                t.where = item.where;
                t.enchants = item.enchants;
                m.tally.add(t);
            }
            t.count++;
        }
        if (m.items.size() > 120) m.items.remove(m.items.size() - 1);
        m.byBag = new HashMap<>(m.byBag);
        m.byBag.merge(item.bag, 1, Integer::sum);
        m.total++;
        m.lastDrop = find.time;
        caravan.members = members;
        show(caravan);
    }

    /** The bag color the server files a drop under: "Boosted Orange" is Orange, unknown is Other. */
    private static String bagName(String bag) {
        String b = bag == null ? "" : bag.trim();
        if (b.startsWith("Boosted ")) b = b.substring("Boosted ".length()).trim();
        return b.isEmpty() ? "Other" : Character.toUpperCase(b.charAt(0)) + b.substring(1);
    }

    /**
     * Loads the caravan for the chosen range (the Guild tab calls this whenever it refreshes).
     */
    public void load() {
        if (loading) {
            again = true;
            return;
        }
        stale = false;
        if (server.user() == null || server.state() != GobblerServer.State.ONLINE) {
            message("Log in to a server in the Server tab to see your guild.");
            return;
        }
        loading = true;
        String asked = range;
        server.caravan(asked).whenComplete((c, e) -> SwingUtilities.invokeLater(() -> {
            loading = false;
            if (e != null) message(GuildPanel.loadError(e));
            else if (asked.equals(range)) show(c);
            if (again || !asked.equals(range)) {   // asked again, or the range changed while loading
                again = false;
                load();
            }
        }));
    }

    private void message(String text) {
        caravan = null;
        shown = null;
        scene.setMembers(List.of());
        status.setText(text);
        detail.removeAll();
        detail.revalidate();
        detail.repaint();
    }

    private void show(Caravan c) {
        caravan = c;
        c.members = byScore(c.members);
        String json = GSON.toJson(c) + GSON.toJson(ItemScore.get().weights());
        if (json.equals(shown)) return;
        shown = json;
        section.setTitle("Loot Caravan - " + c.guild);
        int found = c.members.stream().mapToInt(m -> m.total).sum();
        GobblerServer.User me = server.user();
        int mine = me == null ? 0 : c.members.stream().filter(m -> m.id == me.id).mapToInt(m -> m.total).sum();
        status.setText(NUM.format(found) + " items  ·  you: " + NUM.format(mine) + "  ·  click a member for details");
        if (c.members.stream().noneMatch(m -> m.id == picked)) picked = c.members.isEmpty() ? -1 : c.members.get(0).id;
        scene.picked = picked;
        scene.setMembers(c.members);
        showDetail();
    }

    /** Highest score first, then most items, then by name. */
    private static List<CaravanMember> byScore(List<CaravanMember> members) {
        List<CaravanMember> out = new ArrayList<>(members);
        out.sort(Comparator.comparingDouble((CaravanMember x) -> -score(x)).thenComparingInt(x -> -x.total)
                .thenComparing(x -> x.ign.toLowerCase(Locale.ROOT)));
        return out;
    }

    /** A member's points in the range: their tally, or the items listed if the server sends no tally. */
    static double score(CaravanMember m) {
        if (!m.tally.isEmpty()) return ItemScore.get().total(m.tally);
        double sum = 0;
        for (CaravanItem it : m.items) sum += points(it);
        return Math.round(sum * 10) / 10.0;
    }

    static double points(CaravanItem it) {
        return ItemScore.get().of(it.itemId, it.where, it.enchants);
    }

    private void pick(CaravanMember m) {
        picked = m.id;
        scene.picked = picked;
        scene.repaint();
        showDetail();
    }

    // ---- the member below the picture ------------------------------------------------------------------------

    private void showDetail() {
        detail.removeAll();
        CaravanMember m = caravan == null ? null : caravan.members.stream().filter(x -> x.id == picked).findFirst().orElse(null);
        if (m != null) {
            detail.add(memberCard(m));
            detail.add(bestFinds(m));
        }
        detail.revalidate();
        detail.repaint();
    }

    private JComponent memberCard(CaravanMember m) {
        JPanel p = column();
        int place = caravan.members.indexOf(m) + 1;
        String rangeText = RANGE_LABELS[Arrays.asList(RANGES).indexOf(range)].toUpperCase(Locale.ROOT);
        JLabel where = new JLabel((m.total >= WALK_BELOW ? "WAGON #" + place : "ON FOOT") + "  ·  " + NUM.format(m.total)
                + (m.total == 1 ? " ITEM" : " ITEMS") + "  ·  " + rangeText);
        where.putClientProperty("FlatLaf.styleClass", "small");
        where.setForeground(Palette.MUTED);
        p.add(left(where));
        JLabel name = new JLabel(m.ign);
        name.setFont(new Font(Themes.HEAD_FONT, Font.PLAIN, 44));
        name.setForeground(Section.color("LootGobbler.gold", Palette.GOLD));
        p.add(left(name));
        JLabel points = new JLabel(ItemScore.whole(score(m)) + " points");
        points.setFont(new Font(Themes.HEAD_FONT, Font.PLAIN, 30));
        points.setToolTipText("The points of all items found in this range");
        p.add(left(points));
        String played = m.character == null ? "Most played: unknown"
                : "Most played: " + m.character.className + "  ·  " + NUM.format(m.character.fame) + " fame";
        p.add(left(new JLabel(played)));
        p.add(left(muted(m.lastDrop == null ? "No drops in this range" : "Last drop " + ago(m.lastDrop))));
        p.add(Box.createVerticalStrut(10));
        JPanel counts = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        counts.setOpaque(false);
        for (String bag : BAG_ORDER) {
            Integer n = m.byBag.get(bag);
            if (n == null || n == 0) continue;
            JLabel l = new JLabel(NUM.format(n), ProfileView.bagIcon(bag, 28), SwingConstants.LEFT);
            l.setFont(new Font(Themes.HEAD_FONT, Font.PLAIN, 30));
            l.setToolTipText(n + (n == 1 ? " item from " : " items from ") + bag.toLowerCase(Locale.ROOT) + " bags");
            l.setBorder(new EmptyBorder(0, 0, 0, 18));
            counts.add(l);
        }
        if (counts.getComponentCount() == 0) counts.add(muted("No items yet."));
        p.add(left(counts));
        return card(p);
    }

    /**
     * The items of the range worth the most points; newest first among equals.
     */
    private JComponent bestFinds(CaravanMember m) {
        JPanel p = column();
        JLabel head = new JLabel("Best finds");
        head.setFont(new Font(Themes.HEAD_FONT, Font.PLAIN, 30));
        head.setForeground(Section.color("LootGobbler.gold", Palette.GOLD));
        p.add(left(head));
        p.add(Box.createVerticalStrut(4));
        List<CaravanItem> best = new ArrayList<>(m.items);
        best.sort(Comparator.comparingDouble((CaravanItem it) -> -points(it)));   // stable: keeps newest first
        if (best.isEmpty()) p.add(left(muted("Nothing found in this range.")));
        for (CaravanItem it : best.subList(0, Math.min(4, best.size()))) {
            JLabel name = new JLabel(itemName(it.itemId), CharactersView.sprite(it.itemId, 36), SwingConstants.LEFT);
            name.setIconTextGap(12);
            JLabel sub = muted(ItemScore.format(points(it)) + " pts  ·  " + it.bag + " bag"
                    + (it.where == null || it.where.isBlank() ? "" : "  ·  " + it.where) + "  ·  " + ago(it.time));
            sub.setBorder(new EmptyBorder(0, 48, 0, 0));
            if (mine(m)) {   // click: the item in Inventory (other players' items do nothing for now)
                p.add(left(ItemLinks.link(name, it.itemId)));
                p.add(left(ItemLinks.link(sub, it.itemId)));
            } else {
                p.add(left(name));
                p.add(left(sub));
            }
            p.add(Box.createVerticalStrut(6));
        }
        return card(p);
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private static int rarity(CaravanItem it) {
        int i = BAG_ORDER.indexOf(it.bag);
        return i < 0 ? BAG_ORDER.size() : i;
    }

    private static Color bagColor(String bag) {
        return BAG_COLORS.getOrDefault(bag, new Color(0x9A815A));
    }

    /** Whether this member is you. */
    private boolean mine(CaravanMember m) {
        GobblerServer.User me = server.user();
        return me != null && m.id == me.id;
    }

    /** A pile item's area in the scene, and whether it lies on your wagon. */
    private record ItemHit(Rectangle area, int itemId, double points, boolean mine) {
    }

    private static String itemName(int id) {
        ItemDb.Item info = ItemDb.get().item(id);
        return info != null && !info.name.isEmpty() ? info.name : "Item " + id;
    }

    /**
     * A find time (the player's local time, "2026-11-01T16:08:28") as "12 min ago".
     */
    private static String ago(String time) {
        try {
            return CharactersView.ago(LocalDateTime.parse(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
        } catch (Exception e) {
            return time == null ? "" : time;
        }
    }

    private static JComponent card(JComponent content) {
        JPanel p = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(Section.color("LootGobbler.paper", Color.WHITE));
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(Section.color("LootGobbler.line", Color.GRAY));
                g.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
                g.drawRect(1, 1, getWidth() - 3, getHeight() - 3);
            }
        };
        p.setBorder(new EmptyBorder(14, 18, 14, 18));
        p.add(content, BorderLayout.NORTH);
        return p;
    }

    private static JLabel muted(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(Palette.MUTED);
        return l;
    }

    private static JPanel column() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);
        return p;
    }

    private static <T extends JComponent> T left(T c) {
        c.setAlignmentX(LEFT_ALIGNMENT);
        return c;
    }

    // ---- the picture -----------------------------------------------------------------------------------------

    /**
     * The painted road: sky, hills, the ox, one wagon per member with enough items, then the members on foot.
     * Animates (wheels, bobbing loot, walking) while it is on screen. Also used small on the Profile, with just
     * your own wagon (zoomed out, any click opens the caravan).
     */
    static class Scene extends JComponent implements Scrollable {
        static final int HEIGHT = 440;
        private static final int PAD = 28, OX_W = 120, BAR = 28, STEP_W = 46, WAGON_W = 186, BED_H = 34, WALKER_W = 80, DRIVER = 64;
        private static final int ITEM = 24, ITEM_GAP = 2, PILE_COLS = 5, MAX_PILE = 40;
        private static final Color SKY = new Color(0x141B2B), STAR = new Color(0xF4E6C6), HILL_FAR = new Color(0x1E2A2A),
                HILL_NEAR = new Color(0x24301F), ROAD = new Color(0x4A3522), ROAD_EDGE = new Color(0x6B4E2E),
                RUT = new Color(0x3A2818), WOOD = new Color(0x8C6C47), WOOD_DARK = new Color(0x6B4E2E),
                INK = new Color(0x2C1D0E), BONE = new Color(0xE9D8B8), BLANKET = new Color(0xB8364A),
                FLAG_2 = new Color(0xD2B988), FLAG_3 = new Color(0xC8814A), TAG = new Color(0x2E2116);
        private static final Font PLATE_FONT = new Font(Themes.BODY_FONT, Font.PLAIN, 19);
        private static final Font FLAG_FONT = new Font(Themes.BODY_FONT, Font.PLAIN, 17);

        private List<CaravanMember> riders = List.of(), walkers = List.of();
        private final Map<Long, Image> drivers = new HashMap<>();   // flipped character sprite per member
        private final Map<Integer, Icon> items = new HashMap<>();
        private final List<Map.Entry<Rectangle, CaravanMember>> hits = new ArrayList<>();
        private final List<ItemHit> itemHits = new ArrayList<>();   // pile items
        private boolean paintingMine;   // the wagon being painted is yours (only your items open the Inventory)
        private final long[][] stars = new long[40][2];
        private int phase;
        private final java.util.function.Consumer<CaravanMember> onPick;   // null for a click beside the members
        long picked = -1;   // the member drawn highlighted
        private double zoom = 1;
        private static final double MAX_FIT = 1.7;   // the caravan's size while only a few wagons drive
        private static final int FIT_MARGIN = 120;    // free space left and right of an enlarged caravan
        private AffineTransform view = new AffineTransform();   // caravan coordinates -> screen, as last drawn
        private boolean anyClick;
        private int plateW;   // the widest score plate, as last drawn
        private final javax.swing.Timer timer = new javax.swing.Timer(70, e -> {
            phase++;
            repaint();
        });

        Scene(java.util.function.Consumer<CaravanMember> onPick) {
            this.onPick = onPick;
            Random r = new Random(7);
            for (long[] s : stars) {
                s[0] = r.nextInt(10_000);   // x in 1/10000 of the width
                s[1] = 8 + r.nextInt(150);
            }
            setToolTipText("");
            addHierarchyListener(e -> {
                if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) == 0) return;
                if (isShowing()) timer.start();
                else timer.stop();
            });
            Clicks.onClick(this, e -> {
                ItemHit item = itemAt(e.getPoint());
                if (item != null) {   // an item on a pile: yours shows in Inventory, others' do nothing (for now)
                    if (item.mine()) ItemLinks.open(item.itemId());
                    return;
                }
                CaravanMember m = at(e.getPoint());
                if (m != null || anyClick) onPick.accept(m);
            });
            addMouseMotionListener(new MouseAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    ItemHit item = itemAt(e.getPoint());
                    boolean hand = anyClick || (item != null ? item.mine() : at(e.getPoint()) != null);
                    setCursor(Cursor.getPredefinedCursor(hand ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
                }
            });
        }

        /** Drawn at this scale; a click anywhere calls onPick (with null beside the members). */
        Scene small(double zoom) {
            this.zoom = zoom;
            anyClick = true;
            return this;
        }

        void setMembers(List<CaravanMember> members) {
            // small (the Profile's own wagon): always on the wagon right behind the ox, even with few items
            riders = members.stream().filter(m -> anyClick || m.total >= WALK_BELOW).toList();
            walkers = members.stream().filter(m -> !anyClick && m.total < WALK_BELOW).toList();
            drivers.clear();
            revalidate();
            repaint();
        }

        private CaravanMember at(Point p) {
            p = toScene(p);
            for (Map.Entry<Rectangle, CaravanMember> h : hits) if (h.getKey().contains(p)) return h.getValue();
            return null;
        }

        /** The pile item under p (not on the Profile's small wagon, where any click opens the caravan); null if none. */
        private ItemHit itemAt(Point p) {
            if (anyClick) return null;
            Point q = toScene(p);
            for (ItemHit h : itemHits) if (h.area().contains(q)) return h;
            return null;
        }

        @Override
        public String getToolTipText(MouseEvent e) {
            ItemHit item = itemAt(e.getPoint());
            if (item != null) return itemName(item.itemId()) + "  ·  " + ItemScore.format(item.points()) + " pts"
                    + (item.mine() ? "  (click: show in Inventory)" : "");
            CaravanMember m = at(e.getPoint());
            if (m == null) return null;
            String cls = m.character == null ? "" : " (" + m.character.className + ")";
            return m.ign + cls + ": " + ItemScore.whole(score(m)) + " points, " + m.total + (m.total == 1 ? " item" : " items");
        }

        /** A point on screen in the caravan's own (unzoomed) coordinates, where the hit areas are. */
        private Point toScene(Point p) {
            try {
                java.awt.geom.Point2D q = view.inverseTransform(p, null);
                return new Point((int) Math.floor(q.getX()), (int) Math.floor(q.getY()));
            } catch (java.awt.geom.NoninvertibleTransformException e) {
                return p;
            }
        }

        /** Width of the ox, the wagons with their steps and the walkers, without the padding. */
        private int contentWidth() {
            int w = OX_W + riders.size() * (BAR + WAGON_W + STEP_W) + (walkers.isEmpty() ? 0 : 46);
            for (CaravanMember m : walkers) w += walkerWidth(m);
            return w;
        }

        /** How high the caravan reaches above the road: the tallest pile, or the drivers and the ox. */
        private int contentHeight() {
            int rows = 0;
            for (CaravanMember m : riders) rows = Math.max(rows, (Math.min(m.items.size(), MAX_PILE) + PILE_COLS - 1) / PILE_COLS);
            return Math.max(150, 32 + BED_H + rows * (ITEM + ITEM_GAP) + 12);
        }

        /**
         * How much the caravan is enlarged: up to MAX_FIT while it fits the visible width (with a margin on both
         * sides) and the sky; less as wagons are added, down to 1. Below that the scroll bar takes over. The
         * Profile's small wagon is never enlarged.
         */
        private double fit() {
            if (anyClick) return 1;
            double width = getParent() instanceof JViewport v ? v.getWidth() : getWidth();
            double byWidth = (width - 2 * FIT_MARGIN) / contentWidth();
            double byHeight = (HEIGHT - 34 - 16) / (double) contentHeight();
            return Math.max(1, Math.min(MAX_FIT, Math.min(byWidth, byHeight)));
        }

        @Override
        public Dimension getPreferredSize() {
            // each wagon on a bar behind the one before (or the ox), with its step at the back
            int w = PAD + contentWidth() + PAD;
            if (zoom != 1) return new Dimension((int) Math.ceil(w * zoom), (int) Math.ceil(HEIGHT * zoom));
            return new Dimension(Math.max(400, w), HEIGHT);
        }

        private static String walkerTag(CaravanMember m) {
            return m.ign + " · " + plate(m);
        }

        /**
         * A member on foot gets room for their figure and their name tag.
         */
        private int walkerWidth(CaravanMember m) {
            return Math.max(WALKER_W, getFontMetrics(FLAG_FONT).stringWidth(walkerTag(m)) + 24);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.scale(zoom, zoom);
            int w = (int) Math.ceil(getWidth() / zoom), ground = HEIGHT - 34;
            paintBackdrop(g2, w);
            hits.clear();
            itemHits.clear();
            if (riders.isEmpty() && walkers.isEmpty()) {
                g2.dispose();
                return;
            }
            // the caravan (not the sky and road) enlarged around the road line, centered while it fits
            double f = fit();
            double drawn = contentWidth() * f, start = anyClick || drawn + 2 * PAD > w ? PAD : (w - drawn) / 2;
            view = AffineTransform.getScaleInstance(zoom, zoom);
            view.translate(start, ground);
            view.scale(f, f);
            view.translate(-PAD, -ground);
            g2.translate(start, ground);
            g2.scale(f, f);
            g2.translate(-PAD, -ground);
            int x = PAD;
            paintOx(g2, x, ground);
            x += OX_W;
            int biggest = Math.max(1, riders.stream().mapToInt(r -> r.items.size()).max().orElse(1));
            double scale = Math.max(1, biggest / (double) MAX_PILE);   // the biggest pile fills a wagon
            g2.setFont(PLATE_FONT);
            plateW = 0;   // every score plate as wide as the widest, so they line up
            for (CaravanMember r : riders) plateW = Math.max(plateW, g2.getFontMetrics().stringWidth(plate(r)) + 10);
            for (int i = 0; i < riders.size(); i++) {
                g2.setColor(INK);
                int bar = i == 0 ? BAR : STEP_W + BAR;   // the bar runs under the step of the wagon in front
                g2.fillRect(x, ground - 40, bar, 4);
                x += bar;
                paintWagon(g2, riders.get(i), i, x, ground, scale);
                x += WAGON_W;
            }
            if (!walkers.isEmpty()) x += 46;
            for (int i = 0; i < walkers.size(); i++) {
                int slot = walkerWidth(walkers.get(i));
                paintWalker(g2, walkers.get(i), i, x, slot, ground);
                x += slot;
            }
            g2.dispose();
        }

        private void paintBackdrop(Graphics2D g2, int w) {
            g2.setColor(SKY);
            g2.fillRect(0, 0, w, HEIGHT);
            for (int i = 0; i < stars.length; i++) {
                boolean dim = (phase / 12 + i) % 7 == 0;   // a slow twinkle
                g2.setColor(dim ? STAR.darker().darker() : STAR);
                g2.fillRect((int) (stars[i][0] * w / 10_000), (int) stars[i][1], 4, 4);
            }
            int mx = w - Math.max(120, w / 7);
            g2.setColor(new Color(244, 230, 198, 30));
            g2.fillRect(mx - 6, 28, 60, 60);
            g2.setColor(STAR);
            g2.fillRect(mx, 34, 48, 48);
            g2.setColor(FLAG_2);
            g2.fillRect(mx + 8, 44, 10, 10);
            g2.fillRect(mx + 28, 62, 6, 6);
            hills(g2, w, HEIGHT - 150, 120, HILL_FAR, new int[]{70, 40, 55, 20, 50, 35, 60, 25, 50, 30, 55});
            hills(g2, w, HEIGHT - 110, 90, HILL_NEAR, new int[]{50, 30, 60, 35, 55, 30, 60, 40, 60});
            g2.setColor(ROAD);
            g2.fillRect(0, HEIGHT - 112, w, 112);
            g2.setColor(ROAD_EDGE);
            g2.fillRect(0, HEIGHT - 112, w, 4);
            int scroll = (phase * 2) % 52;   // the road moves past, the caravan travels left
            for (int x = -52 + scroll; x < w; x += 52) g2.fillRect(x, HEIGHT - 50, 26, 4);
            g2.setColor(RUT);
            for (int x = -40 + (phase * 2) % 40; x < w; x += 40) g2.fillRect(x, HEIGHT - 22, 14, 4);
        }

        /**
         * A row of hills: heights in percent of the band, from the top, spread evenly over the width.
         */
        private void hills(Graphics2D g2, int w, int top, int height, Color color, int[] peaks) {
            Polygon p = new Polygon();
            for (int i = 0; i < peaks.length; i++) p.addPoint(i * w / (peaks.length - 1), top + height - height * (100 - peaks[i]) / 100);
            p.addPoint(w, top + height);
            p.addPoint(0, top + height);
            g2.setColor(color);
            g2.fillPolygon(p);
        }

        private void paintOx(Graphics2D g2, int x, int ground) {
            int y = ground - 96 - (phase / 4 % 2) * 2;
            int step = (phase / 4 % 2 == 0 ? 3 : -3);
            block(g2, x + 22, y + 22, 76, 40, WOOD);                 // body
            block(g2, x, y + 10, 30, 28, WOOD);                      // head, facing left
            g2.setColor(new Color(0x1A120A));
            g2.fillRect(x + 6, y + 18, 5, 5);
            block(g2, x + 4, y + 2, 6, 10, BONE);                    // horns
            block(g2, x + 20, y + 2, 6, 10, BONE);
            block(g2, x + 30 + step, y + 62, 10, 30, WOOD_DARK);     // legs
            block(g2, x + 80 - step, y + 62, 10, 30, WOOD_DARK);
            block(g2, x + 38, y + 18, 40, 10, BLANKET);
            g2.setColor(INK);
            g2.fillRect(x + 98, y + 40, 22, 4);                      // harness
        }

        private void paintWagon(Graphics2D g2, CaravanMember m, int index, int x, int ground, double scale) {
            int bedY = ground - 32 - BED_H;
            int bob = (int) Math.round(2 * Math.sin(phase * 0.25 + index));        // the driver
            int lootBob = (int) Math.round(2 * Math.sin(phase * 0.06 + index));    // the pile: much slower
            boolean isPicked = m.id == picked;
            GobblerServer.User me = GobblerServer.get().user();
            paintingMine = me != null && m.id == me.id;

            // the pile: rarest on top
            List<CaravanItem> loot = new ArrayList<>(m.items);
            loot.sort(Comparator.comparingInt(CaravanView::rarity));
            int n = Math.min(loot.size(), Math.max(1, (int) Math.round(loot.size() / scale)));
            // a pile too big for the wagon: every item once (rarest first) with its copies as a number, instead of
            // leaving copies out (three kimonos showed as one)
            Map<Integer, Integer> copies = new HashMap<>();
            List<CaravanItem> pile;
            if (n < loot.size()) {
                Map<Integer, CaravanItem> once = new LinkedHashMap<>();
                for (CaravanItem it : loot) {
                    once.putIfAbsent(it.itemId, it);
                    copies.merge(it.itemId, 1, Integer::sum);
                }
                pile = new ArrayList<>(once.values());
                if (pile.size() > n) pile = new ArrayList<>(pile.subList(0, n));
            } else {
                pile = new ArrayList<>(loot);
            }
            Collections.reverse(pile);   // drawn from the bottom up
            int pileX = x + 52, pileW = PILE_COLS * (ITEM + ITEM_GAP);
            int top = bedY;
            if (lootStyle > 0) top = paintLootMockup(g2, m, pile, pileX + pileW / 2, bedY + lootBob);
            for (int i = 0; i < (lootStyle > 0 ? 0 : pile.size()); i++) {
                int row = i / PILE_COLS, col = i % PILE_COLS;
                int inRow = Math.min(PILE_COLS, pile.size() - row * PILE_COLS);
                int rowX = pileX + (pileW - inRow * (ITEM + ITEM_GAP)) / 2;
                int ix = rowX + col * (ITEM + ITEM_GAP), iy = bedY - (row + 1) * (ITEM + ITEM_GAP) + lootBob;
                top = Math.min(top, iy);
                CaravanItem it = pile.get(i);
                itemHits.add(new ItemHit(new Rectangle(ix, iy, ITEM, ITEM), it.itemId, points(it), paintingMine));
                Icon icon = items.computeIfAbsent(it.itemId, id -> CharactersView.sprite(id, ITEM));
                if (icon != null) icon.paintIcon(this, g2, ix + (ITEM - icon.getIconWidth()) / 2, iy + (ITEM - icon.getIconHeight()) / 2);
                int c = copies.getOrDefault(it.itemId, 1);
                if (c > 1) paintCopies(g2, String.valueOf(c), ix + ITEM, iy + ITEM);
            }

            // the step at the back with the stones of the enchanted items
            paintStep(g2, m, x + WAGON_W, bedY + BED_H - 4);

            // the driver up front, facing the way the caravan goes; the bed hides their legs
            Image driver = driver(m);
            if (driver != null) g2.drawImage(driver, x - 4, bedY - DRIVER + 14 + bob, null);

            // bed with the name plate
            block(g2, x, bedY, WAGON_W, BED_H, WOOD);
            g2.setColor(WOOD_DARK);
            g2.fillRect(x + 3, bedY + BED_H - 9, WAGON_W - 6, 6);
            g2.setFont(PLATE_FONT);
            FontMetrics fm = g2.getFontMetrics();
            String name = m.ign;
            while (fm.stringWidth(name) > WAGON_W - 20 && name.length() > 3) name = name.substring(0, name.length() - 1);
            g2.setColor(new Color(0x1A120A));
            if (!anyClick) {   // the score, painted on the body left of the name (not on the Profile's single wagon)
                String rank = plate(m);
                Color plate = index == 0 ? Section.color("LootGobbler.gold", Palette.GOLD) : index == 1 ? FLAG_2 : index == 2 ? FLAG_3 : BONE;
                int rw = Math.max(plateW, fm.stringWidth(rank) + 10);
                g2.setColor(plate);
                g2.fillRect(x + 6, bedY + 5, rw, 21);
                g2.setColor(INK);
                g2.drawRect(x + 6, bedY + 5, rw - 1, 20);
                g2.setColor(new Color(0x1A120A));
                g2.drawString(rank, x + 6 + (rw - fm.stringWidth(rank)) / 2, bedY + 22);
                int free = WAGON_W - rw - 20;
                while (fm.stringWidth(name) > free && name.length() > 3) name = name.substring(0, name.length() - 1);
                g2.drawString(name, x + rw + 10 + (free - fm.stringWidth(name)) / 2, bedY + 22);
            } else {
                g2.drawString(name, x + (WAGON_W - fm.stringWidth(name)) / 2, bedY + 22);
            }
            if (isPicked) {
                g2.setColor(Section.color("LootGobbler.goldHover", Palette.GOLD));
                g2.setStroke(new BasicStroke(3));
                g2.drawRect(x - 3, bedY - 3, WAGON_W + 5, BED_H + 5);
                g2.setStroke(new BasicStroke(1));
            }

            wheel(g2, x + 15 + 19, ground - 19);
            wheel(g2, x + WAGON_W - 15 - 19, ground - 19);
            hits.add(Map.entry(new Rectangle(x, top, WAGON_W, ground - top), m));
        }

        /** The score on a wagon's plate, in whole points. */
        private static String plate(CaravanMember m) {
            return ItemScore.whole(score(m));
        }

        /** A small count at an item's bottom right corner (right, bottom), outlined so it reads on any sprite. */
        private static void paintCopies(Graphics2D g2, String text, int right, int bottom) {
            Graphics2D g = (Graphics2D) g2.create();
            g.setFont(FLAG_FONT.deriveFont(13f));
            FontMetrics fm = g.getFontMetrics();
            int x = right - fm.stringWidth(text) + 2, y = bottom + 2;
            g.setColor(INK);
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) g.drawString(text, x + dx, y + dy);
            g.setColor(Color.WHITE);
            g.drawString(text, x, y);
            g.dispose();
        }

        private static final Color[] STONE = {null, new Color(0x4CD15A), new Color(0x3FB6F2), new Color(0xB05CF0), new Color(0xF2C232)};

        /**
         * A wooden step at the back of the wagon with one stone per enchanted item, in its rarity's color (green
         * uncommon, blue rare, purple legendary, gold divine), rarest on top; many go into a chest (see paintHoard).
         * Nothing on it while the server does not send the items' enchantments.
         */
        private void paintStep(Graphics2D g2, CaravanMember m, int x, int y) {
            List<Integer> stones = new ArrayList<>();
            for (CaravanItem it : m.items) if (it.enchants > 0) stones.add(Math.min(4, it.enchants));
            stones.sort(Comparator.naturalOrder());   // common at the bottom, rarest on top
            block(g2, x, y, STEP_W, 7, WOOD);          // the step
            g2.setColor(INK);
            g2.fillRect(x + 2, y + 6, 3, 8);           // brackets under it
            g2.fillRect(x + STEP_W - 5, y + 6, 3, 8);
            paintHoard(g2, stones, m.id, x + STEP_W / 2, y);
        }

        /** MOCKUP: how the loot sits on the wagon. 0 rows (as now), 1 mound, 2 sacks per bag color, 3 showcase, 4 gentle heap. */
        static int lootStyle = 0;

        /** MOCKUP: paints the pile (bottom up, rarest last) centered on cx, standing on bed; returns its top. */
        private int paintLootMockup(Graphics2D g2, CaravanMember m, List<CaravanItem> pile, int cx, int bed) {
            int top = bed;
            Random rnd = new Random(m.id * 7919L);
            switch (lootStyle) {
                case 1, 4 -> {   // a mound: rows narrowing upwards (4: wider and lower, items turned a little)
                    boolean gentle = lootStyle == 4;
                    int n0 = pile.size(), sp = gentle ? ITEM : ITEM - 2, rise = gentle ? ITEM - 8 : ITEM - 5;
                    // the base grows with the pile: a pyramid (mound) or a wide, low heap (gentle)
                    int width = gentle ? Math.max(3, Math.min(9, (n0 + 2) / 3))
                            : Math.max(3, Math.min(8, (int) Math.ceil((Math.sqrt(8.0 * n0 + 1) - 1) / 2)));
                    int row = 0, i = 0;
                    while (i < pile.size()) {
                        int inRow = Math.min(Math.max(1, width - (gentle ? row / 2 : row)), pile.size() - i);
                        if (row > 0 && inRow < 2 && pile.size() - i > 1) inRow = Math.min(2, pile.size() - i);
                        for (int k = 0; k < inRow; k++, i++) {
                            int ix = cx - (inRow - 1) * sp / 2 + k * sp - ITEM / 2, iy = bed - ITEM - row * rise;
                            double angle = 0;
                            if (gentle) {
                                ix += rnd.nextInt(5) - 2;
                                iy += rnd.nextInt(3) - 1;
                                angle = Math.toRadians(rnd.nextInt(31) - 15);
                            }
                            top = Math.min(top, iy);
                            lootItem(g2, pile.get(i).itemId, ix, iy, angle);
                        }
                        row++;
                    }
                }
                case 2 -> {   // one sack per bag color, its rarest items looking out at the top, the count on it
                    Map<String, List<CaravanItem>> byBag = new LinkedHashMap<>();
                    for (String b : BAG_ORDER) for (CaravanItem it : pile) if (b.equals(it.bag)) byBag.computeIfAbsent(b, k -> new ArrayList<>()).add(it);
                    List<Map.Entry<String, List<CaravanItem>>> sacks = new ArrayList<>(byBag.entrySet());
                    if (sacks.size() > 4) sacks = sacks.subList(0, 4);
                    int sw = 34, gap = 4, total = sacks.size() * sw + (sacks.size() - 1) * gap, sx = cx + 14 - total / 2;
                    for (Map.Entry<String, List<CaravanItem>> s : sacks) {
                        Color c = bagColor(s.getKey());
                        int sh = 30 + Math.min(16, s.getValue().size() * 2), sy = bed - sh;
                        List<CaravanItem> items = s.getValue();
                        for (int k = 0; k < Math.min(3, items.size()); k++) {   // peeking out
                            int ix = sx + sw / 2 - ITEM / 2 + (k - 1) * 11, iy = sy - ITEM + 10 - (k == 1 ? 5 : 0);
                            top = Math.min(top, iy);
                            lootItem(g2, items.get(items.size() - 1 - k).itemId, ix, iy, Math.toRadians((k - 1) * 14));
                        }
                        g2.setColor(INK);
                        g2.fillRoundRect(sx, sy, sw, sh, 14, 14);
                        g2.setColor(c);
                        g2.fillRoundRect(sx + 3, sy + 3, sw - 6, sh - 6, 10, 10);
                        g2.setColor(c.darker());
                        g2.fillRect(sx + 6, sy + 6, sw - 12, 3);   // the tied neck
                        g2.setFont(FLAG_FONT);
                        String t = String.valueOf(items.size());
                        FontMetrics fm = g2.getFontMetrics();
                        g2.setColor(INK);
                        g2.drawString(t, sx + (sw - fm.stringWidth(t)) / 2, sy + sh - 7);
                        sx += sw + gap;
                    }
                }
                case 3 -> {   // a showcase rack: the six best items on two shelves, "+N" for the rest
                    int rw = 6 * 0 + 3 * (ITEM + 8) + 8, rx = cx - rw / 2, shelf2 = bed - 6, shelf1 = shelf2 - ITEM - 12;
                    block(g2, rx, shelf1 - ITEM - 10, 5, shelf2 - shelf1 + ITEM + 16, WOOD_DARK);        // posts
                    block(g2, rx + rw - 5, shelf1 - ITEM - 10, 5, shelf2 - shelf1 + ITEM + 16, WOOD_DARK);
                    block(g2, rx, shelf1, rw, 6, WOOD);
                    block(g2, rx, shelf2, rw, 6, WOOD);
                    top = shelf1 - ITEM - 10;
                    int shown = Math.min(6, pile.size());
                    for (int k = 0; k < shown; k++) {
                        CaravanItem it = pile.get(pile.size() - 1 - k);   // rarest first, top shelf
                        int ix = rx + 6 + (k % 3) * (ITEM + 8), iy = (k < 3 ? shelf1 : shelf2) - ITEM;
                        lootItem(g2, it.itemId, ix, iy, 0);
                    }
                    if (pile.size() > shown) {
                        String t = "+" + (pile.size() - shown);
                        g2.setFont(FLAG_FONT);
                        FontMetrics fm = g2.getFontMetrics();
                        int pw = fm.stringWidth(t) + 10;
                        block(g2, rx + rw + 4, shelf2 - 18, pw, 22, BONE);
                        g2.setColor(INK);
                        g2.drawString(t, rx + rw + 9, shelf2 - 2);
                    }
                }
                default -> {
                }
            }
            return top;
        }

        /** MOCKUP: one loot item at (x, y), turned by angle, with its hit area. */
        private void lootItem(Graphics2D g2, int itemId, int x, int y, double angle) {
            itemHits.add(new ItemHit(new Rectangle(x, y, ITEM, ITEM), itemId, ItemScore.get().of(itemId, "", 0), paintingMine));
            Icon icon = items.computeIfAbsent(itemId, id -> CharactersView.sprite(id, ITEM));
            if (icon == null) return;
            Graphics2D g = (Graphics2D) g2.create();
            g.translate(x + ITEM / 2, y + ITEM / 2);
            if (angle != 0) {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                g.rotate(angle);
            }
            icon.paintIcon(this, g, -icon.getIconWidth() / 2, -icon.getIconHeight() / 2);
            g.dispose();
        }

        private static final int GEM = 11;
        private static final int LOOSE = 12;   // stones heaped without a chest
        /**
         * The enchanted items' stones on the step, standing on (cx, y): up to 12 as a loose pile; 13 to 50 in a chest
         * with a smaller pile looking out, one stone for every full 4 of a rarity (4 purple: 1 purple, 7: still 1);
         * more in an open golden chest, one for every full 10.
         */
        private void paintHoard(Graphics2D g2, List<Integer> stones, long seed, int cx, int y) {
            if (stones.isEmpty()) return;
            int per = stones.size() <= LOOSE ? 1 : stones.size() <= 50 ? 4 : 10;
            List<Integer> shown = new ArrayList<>();
            int[] count = new int[5];
            for (int s : stones) count[s]++;
            for (int r = 1; r <= 4; r++) for (int i = 0; i < count[r] / per; i++) shown.add(r);
            if (per == 1) {
                paintPile(g2, shown, seed, cx, y, 40);
                return;
            }
            // an open chest: lid and inside behind, the gems in it, the front over the bottom of the pile
            boolean gold = per == 10;
            Color body = gold ? GOLD_BODY : WOOD, band = gold ? GOLD_BAND : INK, lock = gold ? BONE : GOLD_BODY;
            int w = 38, h = 20, x = cx - w / 2, rim = y - h;
            block(g2, x + 2, rim - 15, w - 4, 15, gold ? GOLD_BAND : WOOD_DARK);   // the lid, tipped back
            g2.setColor(band);
            g2.fillRect(x + 8, rim - 12, 3, 12);
            g2.fillRect(x + w - 11, rim - 12, 3, 12);
            g2.setColor(new Color(0x1E140A));                                  // the dark inside
            g2.fillRect(x + 3, rim - 2, w - 6, 6);
            paintPile(g2, shown, seed, cx, rim + 3, 30);   // the first row a third in the chest, the rest above
            block(g2, x, rim, w, h, body);                                     // the front
            g2.setColor(band);
            g2.fillRect(x + 8, rim + 3, 3, h - 6);                              // bands
            g2.fillRect(x + w - 11, rim + 3, 3, h - 6);
            g2.fillRect(x + 3, rim + 3, w - 6, 2);                              // rim edge
            g2.setColor(INK);
            g2.fillRect(cx - 4, rim + 4, 8, 9);                                 // the lock
            g2.setColor(lock);
            g2.fillRect(cx - 2, rim + 6, 4, 5);
        }

        private static final Color GOLD_BODY = new Color(0xE5B23A), GOLD_BAND = new Color(0xB0306E);

        /** Gems heaped on (cx, bottom): rows narrowing upwards, each gem turned and nudged a little, overlapping. */
        private void paintPile(Graphics2D g2, List<Integer> gems, long seed, int cx, int bottom, int width) {
            Random rnd = new Random(seed * 31 + gems.size());
            int step = GEM - 4;   // closer than a gem is wide: they overlap
            int perRow = Math.max(1, width / step), row = 0, i = 0;
            List<int[]> placed = new ArrayList<>();   // x, y, rarity, angle in degrees
            while (i < gems.size()) {
                int inRow = Math.min(Math.max(1, perRow - row), gems.size() - i);
                int rowW = (inRow - 1) * step;
                for (int k = 0; k < inRow; k++, i++) {
                    int gx = cx - rowW / 2 + k * step + rnd.nextInt(3) - 1;
                    int gy = bottom - GEM / 2 - row * (GEM - 5) + rnd.nextInt(3) - 1;
                    placed.add(new int[]{gx, gy, gems.get(i), rnd.nextInt(360)});
                }
                row++;
            }
            for (int[] p : placed) gem(g2, p[0], p[1], STONE[p[2]], Math.toRadians(p[3]));
        }

        /** A faceted gem around (cx, cy), turned by angle. */
        private static void gem(Graphics2D g2, int cx, int cy, Color c, double angle) {
            Graphics2D g = (Graphics2D) g2.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            g.translate(cx, cy);
            g.rotate(angle);
            int r = GEM / 2;
            Polygon d = new Polygon(new int[]{0, r, 0, -r}, new int[]{-r, 0, r, 0}, 4);
            g.setColor(c);
            g.fillPolygon(d);
            g.setColor(c.darker());
            g.fillPolygon(new int[]{0, r, 0}, new int[]{0, 0, r}, 3);   // shaded facet
            g.setColor(INK);
            g.drawPolygon(d);
            g.setColor(c.brighter().brighter());
            g.fillRect(-2, -3, 2, 2);   // shine
            g.dispose();
        }

        private void wheel(Graphics2D g2, int cx, int cy) {
            g2.setColor(WOOD_DARK);
            g2.fillOval(cx - 19, cy - 19, 38, 38);
            g2.setColor(INK);
            g2.setStroke(new BasicStroke(5));
            g2.drawOval(cx - 17, cy - 17, 34, 34);
            g2.setStroke(new BasicStroke(4));
            double a = -phase * 0.18;   // rolling left
            for (int i = 0; i < 2; i++) {
                double s = a + i * Math.PI / 2;
                int dx = (int) Math.round(Math.cos(s) * 14), dy = (int) Math.round(Math.sin(s) * 14);
                g2.drawLine(cx - dx, cy - dy, cx + dx, cy + dy);
            }
            g2.setStroke(new BasicStroke(1));
        }

        private void paintWalker(Graphics2D g2, CaravanMember m, int index, int x, int slot, int ground) {
            int step = ((phase / 4 + index) % 2) * 2;
            int top = ground - DRIVER + 2 - step, fx = x + (slot - DRIVER) / 2;
            // backpack behind (they face left, so it hangs on the right)
            block(g2, fx + 40, top + 22, 18, 24, WOOD);
            g2.setColor(WOOD_DARK);
            g2.fillRect(fx + 44, top + 38, 10, 6);
            Image sprite = driver(m);
            if (sprite != null) g2.drawImage(sprite, fx, top, null);
            else block(g2, fx + 12, top + 10, 24, 34, Palette.MUTED);
            String tag = walkerTag(m);
            g2.setFont(FLAG_FONT);
            FontMetrics fm = g2.getFontMetrics();
            int tw = fm.stringWidth(tag) + 10, tx = x + (slot - tw) / 2, ty = ground - 2;
            g2.setColor(TAG);
            g2.fillRect(tx, ty, tw, 20);
            g2.setColor(m.id == picked ? Section.color("LootGobbler.goldHover", Palette.GOLD) : ROAD_EDGE);
            g2.drawRect(tx, ty, tw - 1, 19);
            g2.setColor(FLAG_2);
            g2.drawString(tag, tx + 5, ty + 15);
            hits.add(Map.entry(new Rectangle(x, top, slot, ground + 20 - top), m));
        }

        /**
         * The member's most played character, mirrored so it faces left; null if unknown.
         */
        private Image driver(CaravanMember m) {
            if (m.character == null) return null;
            return drivers.computeIfAbsent(m.id, id -> {
                CharacterStore.Char c = new CharacterStore.Char();
                c.classType = m.character.classType;
                c.skin = m.character.skin;
                c.tex1 = m.character.tex1;
                c.tex2 = m.character.tex2;
                Icon icon = CharactersView.skin(c, DRIVER);
                if (icon == null || icon.getIconWidth() <= 0) return null;
                int w = icon.getIconWidth(), h = icon.getIconHeight();
                BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = img.createGraphics();
                g.translate(w, 0);
                g.scale(-1, 1);
                icon.paintIcon(this, g, 0, 0);
                g.dispose();
                return img;
            });
        }

        /**
         * A filled block with a dark pixel outline.
         */
        private void block(Graphics2D g2, int x, int y, int w, int h, Color fill) {
            g2.setColor(INK);
            g2.fillRect(x, y, w, h);
            g2.setColor(fill);
            g2.fillRect(x + 3, y + 3, w - 6, h - 6);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return 24;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return visible.width - 48;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {   // fill the width while the caravan is short
            return getParent() instanceof JViewport v && v.getWidth() > getPreferredSize().width;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return true;
        }
    }
}
