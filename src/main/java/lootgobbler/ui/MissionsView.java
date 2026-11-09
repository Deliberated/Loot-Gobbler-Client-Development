package lootgobbler.ui;

import lootgobbler.ItemDb;
import lootgobbler.Missions;
import lootgobbler.Missions.Mission;
import lootgobbler.Missions.Season;
import lootgobbler.Missions.Status;
import lootgobbler.Missions.View;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.geom.QuadCurve2D;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Profile tab, Quests page: the season's missions (the battle pass trees) as a list, grouped by where they stand, each
 * row with its kind, state, objectives with bars and rewards. Clicking a locked mission draws dotted arrows from the
 * missions it needs, and from theirs in turn, down to the ones you can do now; each mission clicked gets its own color.
 */
public class MissionsView extends JPanel {
    // not gold: that frames the claimable ones
    private static final Color[] PATH_COLORS = {new Color(0x3FC4D6), new Color(0xE0679C), new Color(0x7BD35A),
            new Color(0xE88A3A), new Color(0x8C7CF0), new Color(0xF2F2F2)};
    private static final int GUTTER = 64;   // room left of the rows for the arrows
    private static final Color IN_PROGRESS = new Color(0x4C9BE8), CLAIMABLE = new Color(0xF2C230), CLAIMED = new Color(0x5FB04A);

    private final Board body = new Board();
    private final JButton refresh = new JButton("Refresh");
    private final Section section;
    private final List<String> picked = new ArrayList<>();           // locked missions clicked ("season:mission"), in order
    private final Map<String, View> views = new HashMap<>();         // every mission shown, by key
    private boolean allMissions;                                      // false: only those your character can do
    private lootgobbler.CharacterStore.Char character;               // the one you play (or played last)

    public MissionsView() {
        super(new BorderLayout());
        setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setOpaque(false);
        body.setBorder(new EmptyBorder(14, 16, 16, 16));
        JPanel holder = new JPanel(new BorderLayout());
        holder.setOpaque(false);
        holder.add(body, BorderLayout.NORTH);
        refresh.setFocusable(false);
        refresh.addActionListener(e -> Missions.get().fetch());
        Segmented which = new Segmented(true, 0, i -> {
            allMissions = i == 1;
            rebuild();
        }, "This character", "All missions");
        section = new Section("Season missions", Palette.GREEN, FitWidthPanel.scroll(holder, 24)).actions(which, refresh);
        add(section);
        later = ViewRefresh.of(this, this::rebuild);
        Missions.get().onChange(later);
        lootgobbler.TinkererQuests.get().onChange(later);
        lootgobbler.CharacterStore.get().onChange(later);   // another character: other missions apply
        Runnable tick = later;
        new Timer(60_000, e -> tick.run()).start();   // cooldowns and "x min ago" move on
        rebuild();
    }

    private static String key(View v) {
        return v.season().id + ":" + v.mission().id;
    }

    private void rebuild() {
        Missions missions = Missions.get();
        body.removeAll();
        body.rows.clear();
        views.clear();
        refresh.setEnabled(missions.canFetch() && !missions.fetching());
        refresh.setToolTipText(missions.canFetch() ? "Ask the game again" : "Log in to the game first");
        section.note(missions.fetching() ? "  loading..." : missions.fetchedAt() > 0 ? "  updated " + ago(missions.fetchedAt()) : "");
        if (missions.problem() != null) body.add(left(muted("The game did not answer: " + missions.problem())));
        Map<Season, List<View>> all = missions.views();
        lootgobbler.CharacterStore.Char playing = lootgobbler.CharacterStore.get().playing();
        character = playing != null ? playing : lootgobbler.CharacterStore.get().lastPlayedChar();

        // the cards on top: the Tinkerer and each battle pass, picked to show their quests
        JPanel cards = new JPanel(new FlowLayout(FlowLayout.CENTER, 14, 0));   // centered
        cards.setOpaque(false);
        List<lootgobbler.TinkererQuests.Quest> tinkerer = openTinkererQuests();
        long seen = lootgobbler.TinkererQuests.get().seenAt();
        cards.add(filterCard(TINKERER_KEY, "Tinkerer", seen == 0 ? "Visit the Daily Quest Room"
                : tinkerer.size() + (tinkerer.size() == 1 ? " quest" : " quests"), seen == 0 ? "" : "seen " + ago(seen), null, tinkererIcon()));
        if (!all.isEmpty()) {   // a gold line between the Tinkerer and the battle passes, with room on both sides
            cards.add(Box.createHorizontalStrut(8));
            JComponent line = new JComponent() {   // as between the packs of a character's inventory: small end caps
                @Override
                protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    Color gold = Section.color("LootGobbler.gold", Palette.GOLD);
                    g2.setColor(new Color(gold.getRed(), gold.getGreen(), gold.getBlue(), 170));
                    g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    int x = getWidth() / 2, y0 = 2, y1 = getHeight() - 3;
                    g2.drawLine(x, y0, x, y1);
                    g2.drawLine(x - 3, y0, x + 3, y0);
                    g2.drawLine(x - 3, y1, x + 3, y1);
                    g2.dispose();
                }
            };
            line.setPreferredSize(new Dimension(8, ART_H + 24));
            cards.add(line);
            cards.add(Box.createHorizontalStrut(8));
        }
        seasonKeys.clear();
        for (Season s : all.keySet()) seasonKeys.add("season:" + s.id);
        for (Map.Entry<Season, List<View>> e : all.entrySet()) {
            Season s = e.getKey();
            int claimed = 0;
            for (View v : e.getValue()) if (done(v)) claimed++;
            long left = s.endDate - System.currentTimeMillis() / 1000;
            String ends = s.endDate <= 0 ? "" : left > 0 ? "ends in " + span(left) : "ended";
            cards.add(filterCard("season:" + s.id, s.name, ends, claimed + "/" + e.getValue().size() + " claimed", art(s), null));
        }
        body.add(left(cards));
        body.add(Box.createVerticalStrut(12));
        body.add(left(goldLine()));
        if (all.isEmpty()) {
            JLabel wait = muted(missions.fetching() ? "Asking the game for the battle pass missions..."
                    : "The battle pass missions load when you log in to the game (enter the Nexus or your vault).");
            wait.setBorder(new EmptyBorder(8, 0, 0, 0));
            body.add(left(wait));
        }

        // the missions of the battle passes picked: claimed ones (and those on cooldown) left out
        List<View> claimable = new ArrayList<>(), rest = new ArrayList<>();
        for (Map.Entry<Season, List<View>> e : all.entrySet()) {
            boolean shown = !hidden.contains("season:" + e.getKey().id);
            for (View v : e.getValue()) {
                views.put(key(v), v);
                if (done(v) || !shown) continue;
                (v.status() == Status.CLAIMABLE ? claimable : rest).add(v);
            }
        }
        picked.removeIf(k -> !views.containsKey(k) || !hasPath(views.get(k)) || hidden.contains("season:" + views.get(k).season().id));
        Set<String> pathKeys = new HashSet<>();
        for (Path p : paths()) pathKeys.addAll(p.missions());
        // only what your character can do (a mission on a drawn path stays, faded, so the path is whole)
        if (!allMissions && character != null) {
            claimable.removeIf(v -> forYou(v.mission()) != null && !pathKeys.contains(key(v)));
            rest.removeIf(v -> forYou(v.mission()) != null && !pathKeys.contains(key(v)));
        }
        java.util.Comparator<View> order = (a, b) -> a.season() == b.season() ? Integer.compare(a.mission().id, b.mission().id)
                : Integer.compare(b.season().current, a.season().current);
        claimable.sort(order);
        rest.sort(order);
        if (rest.stream().anyMatch(this::hasPath)) {
            JLabel hint = muted("Click a locked mission to see the missions that lead to it.");
            hint.setBorder(new EmptyBorder(6, 0, 0, 0));
            body.add(left(hint));
        }
        JPanel rows = new JPanel();
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        rows.setOpaque(false);
        rows.setBorder(new EmptyBorder(10, GUTTER, 0, GUTTER));   // the arrows run left of the rows (as much room right: centered)
        int index = 0;
        for (View v : claimable) {
            JComponent row = row(v, index++);
            body.rows.put(key(v), row);
            rows.add(left(row));
        }
        if (!hidden.contains(TINKERER_KEY)) for (lootgobbler.TinkererQuests.Quest q : tinkerer) rows.add(left(tinkererRow(q)));
        for (View v : rest) {
            JComponent row = row(v, index++);
            body.rows.put(key(v), row);
            rows.add(left(row));
        }
        if (rows.getComponentCount() == 0) {
            JLabel none = muted(hidden.size() > all.size() ? "Pick the Tinkerer or a battle pass above to see their quests."
                    : "Nothing to do here right now.");
            none.setBorder(new EmptyBorder(8, 0, 0, 0));
            body.add(left(none));
        }
        JPanel middle = new JPanel(new GridBagLayout());   // the list at its own width, in the middle
        middle.setOpaque(false);
        GridBagConstraints at = new GridBagConstraints();
        at.anchor = GridBagConstraints.NORTH;
        at.weightx = 1;
        middle.add(rows, at);
        body.add(left(middle));
        body.paths = paths();
        body.revalidate();
        body.repaint();
    }

    // ---- the cards on top ------------------------------------------------------------------------------------------

    private static final String TINKERER_KEY = "tinkerer";
    private static final int CARD_W = 230, ART_H = 110;
    // cards not picked: their quests are not shown. Either the Tinkerer or battle passes (one or more) are picked;
    // at first the battle passes
    private final Set<String> hidden = new HashSet<>(Set.of(TINKERER_KEY));
    private final Set<String> seasonKeys = new HashSet<>();   // the battle passes' cards ("season:<id>")

    /**
     * A card clicked: the Tinkerer shows only his quests; a battle pass while the Tinkerer is shown shows only that
     * pass, otherwise it is added or taken away (one stays picked).
     */
    private void pick(String key) {
        if (key.equals(TINKERER_KEY)) {
            hidden.clear();
            hidden.addAll(seasonKeys);
            return;
        }
        if (!hidden.contains(TINKERER_KEY)) {   // from the Tinkerer to this battle pass
            hidden.clear();
            hidden.add(TINKERER_KEY);
            for (String k : seasonKeys) if (!k.equals(key)) hidden.add(k);
            return;
        }
        if (hidden.contains(key)) {
            hidden.remove(key);
        } else {
            long shown = seasonKeys.stream().filter(k -> !hidden.contains(k)).count();
            if (shown > 1) hidden.add(key);   // the last one picked stays
        }
    }

    /**
     * A card to pick: a picture (or a sprite on a dark ground) with the title in its bottom left corner, a gold outline
     * when picked; below it, outside the frame, one text on the left and one on the right.
     */
    private JComponent filterCard(String key, String title, String leftText, String rightText, Image art, Icon sprite) {
        boolean on = !hidden.contains(key);
        JPanel card = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                if (!on) g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.4f));
                java.awt.geom.RoundRectangle2D area = new java.awt.geom.RoundRectangle2D.Double(1, 1, CARD_W - 3, ART_H - 1, 10, 10);
                Shape before = g2.getClip();
                g2.clip(area);
                g2.setColor(new Color(0x1A1420));
                g2.fill(area);
                if (art != null) {   // covering the picture area, cut evenly
                    double scale = Math.max((double) CARD_W / art.getWidth(null), (double) ART_H / art.getHeight(null));
                    int w = (int) Math.round(art.getWidth(null) * scale), h = (int) Math.round(art.getHeight(null) * scale);
                    g2.drawImage(art, (CARD_W - w) / 2, (ART_H - h) / 2 + 1, w, h, null);
                } else if (sprite != null) {
                    sprite.paintIcon(this, g2, (CARD_W - sprite.getIconWidth()) / 2, (ART_H - sprite.getIconHeight()) / 2 + 1);
                }
                // the title in the bottom left corner, on a dark fade so it reads on any picture
                g2.setPaint(new GradientPaint(0, ART_H - 44, new Color(0, 0, 0, 0), 0, ART_H, new Color(0, 0, 0, 190)));
                g2.fillRect(0, ART_H - 44, CARD_W, 46);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setFont(titleFont);
                FontMetrics fm = g2.getFontMetrics();
                int tx = 10, ty = ART_H - 9;
                g2.setColor(new Color(0, 0, 0, 200));
                g2.drawString(title, tx + 1, ty + 1);
                g2.setColor(on ? Section.color("LootGobbler.gold", Palette.GOLD) : Color.LIGHT_GRAY);
                g2.drawString(fit(title, fm, CARD_W - 20), tx, ty);
                g2.setClip(before);
                g2.setStroke(new BasicStroke(on ? 2.5f : 1f));
                g2.setColor(on ? CLAIMABLE : Palette.MUTED);
                g2.draw(area);
                g2.dispose();
            }
        };
        card.setOpaque(false);
        card.setBorder(new EmptyBorder(ART_H + 4, 4, 0, 4));
        JPanel text = new JPanel(new BorderLayout(8, 0));   // left and right under the frame
        text.setOpaque(false);
        text.add(small(muted(leftText)), BorderLayout.WEST);
        text.add(small(muted(rightText)), BorderLayout.EAST);
        card.add(text, BorderLayout.NORTH);
        card.setPreferredSize(new Dimension(CARD_W, ART_H + 4 + text.getPreferredSize().height));
        card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        card.setToolTipText(key.equals(TINKERER_KEY) ? (on ? null : "Show the Tinkerer's quests")
                : on ? "Click to leave this battle pass out (one stays)" : "Show this battle pass's missions");
        pressAnywhere(card, () -> {
            pick(key);
            rebuild();
        });
        return card;
    }

    private final Font titleFont = new JLabel().getFont().deriveFont(Font.BOLD, 17f);

    /** The text cut with "..." to fit the width. */
    private static String fit(String text, FontMetrics fm, int width) {
        if (fm.stringWidth(text) <= width) return text;
        String t = text;
        while (t.length() > 1 && fm.stringWidth(t + "...") > width) t = t.substring(0, t.length() - 1);
        return t + "...";
    }

    private static Icon tinkererIcon() {
        return CharactersView.sprite(lootgobbler.TinkererQuests.TINKERER, 84);
    }

    // the battle passes' pictures, downloaded once and kept in season-art
    private static final Map<String, Image> ART = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Set<String> LOADING = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private Runnable later = () -> {
    };

    /** The battle pass's picture, or null while it loads (the page is rebuilt when it is there). */
    private Image art(Season s) {
        String url = s.backgroundUrl();
        if (url == null) return null;
        Image img = ART.get(url);
        if (img != null || !LOADING.add(url)) return img;
        Runnable done = later;
        Thread t = new Thread(() -> {
            try {
                String ext = url.toLowerCase(Locale.ROOT).endsWith(".png") ? ".png" : ".jpg";
                java.nio.file.Path file = java.nio.file.Path.of("season-art", s.id + "-" + Integer.toHexString(url.hashCode()) + ext);
                if (!java.nio.file.Files.exists(file)) {
                    java.nio.file.Files.createDirectories(file.getParent());
                    try (java.io.InputStream in = new java.net.URL(url).openStream()) {
                        java.nio.file.Files.write(file, in.readAllBytes());
                    }
                }
                java.awt.image.BufferedImage read = javax.imageio.ImageIO.read(file.toFile());
                if (read != null) {
                    // kept small: the card is 230 wide
                    double scale = Math.min(1, 480.0 / read.getWidth());
                    java.awt.image.BufferedImage small = read;
                    if (scale < 1) {
                        small = new java.awt.image.BufferedImage((int) (read.getWidth() * scale), (int) (read.getHeight() * scale), java.awt.image.BufferedImage.TYPE_INT_RGB);
                        Graphics2D g = small.createGraphics();
                        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                        g.drawImage(read, 0, 0, small.getWidth(), small.getHeight(), null);
                        g.dispose();
                    }
                    ART.put(url, small);
                    SwingUtilities.invokeLater(done);
                }
            } catch (Exception e) {
                lootgobbler.AppLog.log("game", "Battle pass picture failed: " + e.getMessage());
            }
        }, "season-art");
        t.setDaemon(true);
        t.start();
        return null;
    }

    // ---- the Tinkerer's quests -----------------------------------------------------------------------------------

    /**
     * The Tinkerer's quests still to do (done ones left out, unless they repeat). The game sends his quests together
     * with every exchange of the room (skins for Celestial Stones, Divine Coins, runes...); his own are category 0.
     */
    private static List<lootgobbler.TinkererQuests.Quest> openTinkererQuests() {
        List<lootgobbler.TinkererQuests.Quest> out = new ArrayList<>();
        for (lootgobbler.TinkererQuests.Quest q : lootgobbler.TinkererQuests.get().quests()) {
            if (q.category == 0 && (!q.completed || q.repeatable)) out.add(q);
        }
        return out;
    }

    /** The items in a row, the same ones once with their count ("x4"). */
    private static JPanel itemsCounted(int[] ids) {
        Map<Integer, Integer> count = new LinkedHashMap<>();
        for (int id : ids) count.merge(id, 1, Integer::sum);
        JPanel items = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        items.setOpaque(false);
        for (Map.Entry<Integer, Integer> e : count.entrySet()) {
            JLabel l = itemLabel(e.getKey());
            if (e.getValue() > 1) {
                l.setText((l.getIcon() == null ? l.getText() + " " : "") + "x" + e.getValue());
                l.setIconTextGap(2);
            }
            items.add(l);
        }
        return items;
    }

    /** A Tinkerer quest as a row: what to hand in, and what you get. */
    private JComponent tinkererRow(lootgobbler.TinkererQuests.Quest q) {
        JPanel row = new JPanel(new GridBagLayout()) {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        row.setOpaque(false);
        row.setBorder(new EmptyBorder(6, 8, 6, 8));
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = 0;
        c.anchor = GridBagConstraints.WEST;
        Icon icon = q.rewards.length > 0 ? itemIcon(q.rewards[0], 36) : null;
        cell(row, c, 0, W_ICON, new JLabel(icon != null ? icon : CharactersView.sprite(lootgobbler.TinkererQuests.TINKERER, 36)));
        cell(row, c, 1, W_KIND, tag("TINKERER", new Color(0xA48CF0)));
        JPanel state = column();
        JLabel st = new JLabel(q.completed ? "Done" : "Hand in");
        st.setForeground(q.completed ? CLAIMED : IN_PROGRESS);
        state.add(st);
        String ends = endsIn(q.expiration);
        if (ends != null) state.add(small(muted(ends)));
        else if (q.repeatable) state.add(small(muted("↻ Repeatable")));
        cell(row, c, 2, W_STATE, state);
        JPanel name = column();
        JLabel n = new JLabel(q.name);
        n.setFont(n.getFont().deriveFont(17f));
        name.add(n);
        name.add(small(muted("The Tinkerer")));
        cell(row, c, 3, W_NAME, name);
        JPanel goal = column();
        goal.add(small(new JLabel("Hand in:")));
        JPanel items = itemsCounted(q.requirements);
        items.setAlignmentX(LEFT_ALIGNMENT);
        goal.add(items);
        cell(row, c, 4, W_GOAL, goal);
        JPanel rewards = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        rewards.setOpaque(false);
        rewards.add(muted("→  "));
        if (q.itemOfChoice && q.rewards.length > 1) rewards.add(muted("["));
        for (int i = 0; i < q.rewards.length; i++) {
            if (i > 0) rewards.add(q.itemOfChoice ? small(muted("or")) : muted("+"));
            rewards.add(itemLabel(q.rewards[i]));
        }
        if (q.itemOfChoice && q.rewards.length > 1) rewards.add(muted("]"));
        List<JComponent> parts = new ArrayList<>();
        for (Component part : rewards.getComponents()) parts.add((JComponent) part);
        cell(row, c, 5, W_REWARD, packed(parts, W_REWARD));
        if (q.description != null && !q.description.isBlank()) row.setToolTipText(q.description);
        return row;
    }

    /** "Ends in 5h 12m" from the quest's expiration (unix seconds or ms, as text), or null if it says none. */
    private static String endsIn(String expiration) {
        try {
            long t = (long) Double.parseDouble(expiration.trim());   // as the game sends it: "1796205599.0"
            if (t <= 0) return null;
            long left = (t > 100_000_000_000L ? t / 1000 : t) - System.currentTimeMillis() / 1000;
            return left > 0 ? "Ends in " + span(left) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Icon itemIcon(int id, int size) {
        Image img = id > 0 ? CollectionGrid.icon(id) : null;
        return img == null ? null : new ImageIcon(img.getScaledInstance(size, size, Image.SCALE_FAST));
    }

    private static JLabel itemLabel(int id) {
        ItemDb.Item item = id > 0 ? ItemDb.get().item(id) : null;
        Icon icon = itemIcon(id, 28);
        JLabel l = new JLabel(icon);
        String name = item != null && !item.name.isEmpty() ? item.name : "Item " + id;
        l.setToolTipText(name);
        if (icon == null) l.setText(name);
        return l;
    }

    // ---- your character -----------------------------------------------------------------------------------------

    /** Why your character makes no progress on a mission ("Needs a seasonal character"), or null if it does. */
    private String forYou(Mission m) {
        lootgobbler.CharacterStore.Char c = character;
        if (c == null) return null;
        int p = m.participants;
        boolean allowed = p == 0 || p == 255 || ((p & 2) != 0 && c.seasonal) || ((p & 4) != 0 && c.crucible)
                || ((p & 1) != 0 && !c.seasonal);
        if (!allowed) return (p & 4) != 0 && (p & 2) == 0 ? "Needs a crucible character" : (p & 2) != 0 ? "Needs a seasonal character" : "Not for this character";
        List<String> worn = m.worn();
        if (!worn.isEmpty()) {
            int[] slots = lootgobbler.ClassSlots.of(c.classType);
            if (slots != null) {
                for (String w : worn) {
                    boolean has = false;
                    for (int t : slots) if (lootgobbler.ItemDb.slotName(t).equalsIgnoreCase(w)) has = true;
                    if (!has) return "Needs a class with a " + w;
                }
            }
        }
        return null;
    }

    /** Who you play: the class picture, its name and whether it is seasonal or crucible. */
    private static JComponent characterLine(lootgobbler.CharacterStore.Char c, boolean now) {
        JPanel line = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        line.setOpaque(false);
        line.setBorder(new EmptyBorder(0, 0, 8, 0));
        JLabel who = new JLabel((now ? "Playing " : "Last played ") + (c.className == null || c.className.isEmpty() ? "character" : c.className),
                CharactersView.sprite(c.classType, 28), SwingConstants.LEFT);
        who.setFont(who.getFont().deriveFont(16f));
        line.add(who);
        String mode = c.crucible ? "Crucible" : c.seasonal ? "Seasonal" : "Regular";
        JLabel tag = muted("(" + mode + ")");
        line.add(tag);
        return line;
    }

    // ---- the paths to a locked mission ----------------------------------------------------------------------------

    /** The arrows for one clicked mission: from each mission it needs (and theirs in turn), in its color. */
    private record Path(Color color, List<String[]> edges, Set<String> missions, String target) {
    }

    private List<Path> paths() {
        List<Path> out = new ArrayList<>();
        for (int i = 0; i < picked.size(); i++) {
            String target = picked.get(i);
            List<String[]> edges = new ArrayList<>();
            Set<String> seen = new java.util.LinkedHashSet<>();
            walk(target, edges, seen);
            out.add(new Path(PATH_COLORS[i % PATH_COLORS.length], edges, seen, target));
        }
        return out;
    }

    /** Every unfinished mission this one comes after, as an arrow from it; on through those that wait for others. */
    private void walk(String key, List<String[]> edges, Set<String> seen) {
        if (!seen.add(key)) return;
        View v = views.get(key);
        if (v == null) return;
        for (View p : waitsFor(v)) {
            String pk = key(p);
            edges.add(new String[]{pk, key});
            if (p.status() == Status.LOCKED) walk(pk, edges, seen);
            else seen.add(pk);   // can be started now: where the path starts
        }
    }

    /** Claimed: the missions after it open only then (finished but not claimed is not enough). */
    private static boolean done(View v) {
        return v.status() == Status.CLAIMED || v.status() == Status.COOLDOWN;
    }

    /** The missions this one comes after that are not done yet (empty: it is next in its tree). */
    private List<View> waitsFor(View v) {
        List<View> out = new ArrayList<>();
        for (int parent : v.mission().parents()) {
            View p = views.get(v.season().id + ":" + parent);
            if (p != null && !done(p)) out.add(p);
        }
        return out;
    }

    /** A locked mission (it cannot be started yet) waiting for others: clicking it shows what leads to it. */
    private boolean hasPath(View v) {
        return v.status() == Status.LOCKED && !waitsFor(v).isEmpty();
    }

    private void clicked(View v) {
        if (!hasPath(v)) return;   // next in its tree (or done): nothing leads to it
        String k = key(v);
        if (!picked.remove(k)) picked.add(k);
        rebuild();
    }

    /** The list, with the arrows of the clicked missions drawn over it. */
    private static final class Board extends JPanel {
        final Map<String, JComponent> rows = new HashMap<>();
        List<Path> paths = List.of();

        @Override
        protected void paintChildren(Graphics g) {
            super.paintChildren(g);
            if (paths.isEmpty()) return;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            for (int i = 0; i < paths.size(); i++) {
                Path p = paths.get(i);
                g2.setColor(p.color());
                // the missions on the path framed in its color, the clicked one thicker
                for (String k : p.missions()) {
                    Rectangle r = rect(k);
                    if (r == null) continue;
                    g2.setStroke(new BasicStroke(k.equals(p.target()) ? 3f : 1.5f));
                    g2.drawRect(r.x + 1, r.y + 1, r.width - 3, r.height - 3);
                }
                g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[]{2f, 6f}, i * 2f));
                for (String[] e : p.edges()) {
                    Rectangle from = rect(e[0]), to = rect(e[1]);
                    if (from == null || to == null) continue;
                    arrow(g2, from, to, i);
                }
            }
            g2.dispose();
        }

        /** A bowed dotted arrow in the gutter, from the left edge of one row to the left edge of the other. */
        private void arrow(Graphics2D g2, Rectangle from, Rectangle to, int lane) {
            int x = from.x - 2;
            double y1 = from.getCenterY() + (lane - 1) * 3, y2 = to.getCenterY() + (lane - 1) * 3;
            double bow = Math.min(GUTTER - 8, 14 + Math.abs(y2 - y1) / 10.0 + lane * 5);
            double cx = x - bow * 2, cy = (y1 + y2) / 2;   // the curve's peak is half way out to the control point
            g2.draw(new QuadCurve2D.Double(x, y1, cx, cy, x, y2));
            // the head at the end, along the curve's last direction
            double dx = x - cx, dy = y2 - cy, len = Math.hypot(dx, dy);
            if (len == 0) return;
            dx /= len;
            dy /= len;
            double size = 9, wing = 5;
            Polygon head = new Polygon();
            head.addPoint((int) Math.round(x), (int) Math.round(y2));
            head.addPoint((int) Math.round(x - dx * size - dy * wing), (int) Math.round(y2 - dy * size + dx * wing));
            head.addPoint((int) Math.round(x - dx * size + dy * wing), (int) Math.round(y2 - dy * size - dx * wing));
            Stroke s = g2.getStroke();
            g2.setStroke(new BasicStroke(1f));
            g2.fill(head);
            g2.setStroke(s);
            // a dot where it starts
            g2.fill(new java.awt.geom.Ellipse2D.Double(x - 3.5, y1 - 3.5, 7, 7));
        }

        private Rectangle rect(String key) {
            JComponent row = rows.get(key);
            if (row == null || row.getParent() == null || !row.isShowing()) return null;
            return SwingUtilities.convertRectangle(row.getParent(), row.getBounds(), this);
        }
    }

    // ---- a row -------------------------------------------------------------------------------------------------

    // every row as wide (the rewards too, wrapping onto more lines), so the list stands in the middle
    private static final int W_ICON = 44, W_KIND = 92, W_STATE = 118, W_NAME = 230, W_GOAL = 250, W_REWARD = 330;

    /** The parts in lines left to right, a new line when the next part does not fit the width. */
    private static JComponent packed(List<JComponent> parts, int width) {
        JPanel lines = column();
        JPanel line = null;
        int x = 0;
        for (JComponent part : parts) {
            int w = part.getPreferredSize().width + 4;
            if (line == null || (x > 0 && x + w > width)) {
                line = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 1));
                line.setOpaque(false);
                line.setAlignmentX(LEFT_ALIGNMENT);
                lines.add(line);
                x = 0;
            }
            line.add(part);
            x += w;
        }
        return lines;
    }

    /** One mission: icon, kind, state, name and tree, objectives with bars, then its rewards. */
    private JComponent row(View v, int index) {
        Mission m = v.mission();
        String notForYou = character == null ? null : forYou(m);
        boolean faded = v.status() == Status.CLAIMED || v.status() == Status.COOLDOWN || v.status() == Status.LOCKED || notForYou != null;
        JPanel row = new JPanel(new GridBagLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                if (v.status() == Status.CLAIMABLE) {   // ready: a gold frame
                    g.setColor(CLAIMABLE);
                    g.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
                    g.drawRect(1, 1, getWidth() - 3, getHeight() - 3);
                }
            }

            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        row.setBorder(new EmptyBorder(6, 8, 6, 8));
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = 0;
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.NONE;

        JLabel icon = new JLabel(icon(m));
        cell(row, c, 0, W_ICON, icon);
        cell(row, c, 1, W_KIND, kind(m));

        JPanel state = column();
        JLabel st = new JLabel(stateText(v));
        st.setForeground(switch (v.status()) {
            case CLAIMABLE -> CLAIMABLE;
            case IN_PROGRESS -> IN_PROGRESS;
            case CLAIMED, COOLDOWN -> CLAIMED;
            default -> Palette.MUTED;
        });
        state.add(st);
        if (v.status() == Status.COOLDOWN) state.add(small(muted("Resets in " + span(Math.max(0, v.resetsAt() - System.currentTimeMillis() / 1000)))));
        else if (m.repeatable()) state.add(small(muted("↻ Repeatable")));
        boolean path = hasPath(v);
        if (path) {   // what it waits for: a click draws the way there
            int n = waitsFor(v).size();
            JLabel after = small(new JLabel("⤴ after " + n + (n == 1 ? " mission" : " missions")));
            after.setForeground(PATH_COLORS[0]);
            state.add(after);
        }
        cell(row, c, 2, W_STATE, state);

        JPanel name = column();
        JLabel n = new JLabel(m.name.replace("(Repeatable)", "").trim());
        n.setFont(n.getFont().deriveFont(17f));
        if (faded) n.setForeground(Palette.MUTED);
        name.add(n);
        name.add(small(muted(v.season().name)));
        if (notForYou != null) {   // your character makes no progress on it
            JLabel no = small(new JLabel(notForYou));
            no.setForeground(new Color(0xE0679C));
            name.add(no);
        }
        cell(row, c, 3, W_NAME, name);

        cell(row, c, 4, W_GOAL, goals(v));

        cell(row, c, 5, W_REWARD, rewards(m, faded));

        row.setCursor(Cursor.getPredefinedCursor(path ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
        if (path) row.setToolTipText("Show the missions that lead to this one");
        if (path) pressAnywhere(row, () -> clicked(v));
        return row;
    }

    /** The click on the row and everything in it (a label with a tooltip would otherwise take it). */
    private static void pressAnywhere(JComponent c, Runnable action) {
        Clicks.onPress(c, action);
        for (Component child : c.getComponents()) if (child instanceof JComponent j) pressAnywhere(j, action);
    }

    private static void cell(JPanel row, GridBagConstraints c, int x, int width, JComponent content) {
        JPanel box = new JPanel(new BorderLayout());
        box.setOpaque(false);
        box.add(content, BorderLayout.WEST);
        box.setPreferredSize(new Dimension(width, Math.max(36, content.getPreferredSize().height)));
        box.setMinimumSize(box.getPreferredSize());
        c.gridx = x;
        c.weightx = 0;
        row.add(box, c);
    }

    private static String stateText(View v) {
        return switch (v.status()) {
            case CLAIMABLE -> "Claimable";
            case IN_PROGRESS -> "In progress";
            case COOLDOWN, CLAIMED -> "Claimed";
            case LOCKED -> "Locked";
        };
    }

    private static final Map<String, Icon> ICONS = new HashMap<>();   // by "sheet/index"

    /** The mission's picture as the game shows it ("sheet/index" in its sprites); else the dungeon or a reward. */
    private static Icon icon(Mission m) {
        if (m.icon != null && m.icon.contains("/")) {
            Icon cached = ICONS.computeIfAbsent(m.icon, MissionsView::sprite);
            if (cached != null) return cached;
        }
        return fallbackIcon(m);
    }

    /** A sprite of the game's sheets, cut to its visible pixels and scaled up crisply to 36 px. */
    private static Icon sprite(String ref) {
        try {
            int slash = ref.lastIndexOf('/');
            java.awt.image.BufferedImage img = assets.ImageBuffer.getSpriteImage(ref.substring(0, slash), Integer.parseInt(ref.substring(slash + 1).trim()));
            if (img == null) return null;
            int x0 = img.getWidth(), y0 = img.getHeight(), x1 = -1, y1 = -1;
            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < img.getWidth(); x++) {
                    if ((img.getRGB(x, y) >>> 24) == 0) continue;
                    x0 = Math.min(x0, x);
                    y0 = Math.min(y0, y);
                    x1 = Math.max(x1, x);
                    y1 = Math.max(y1, y);
                }
            }
            if (x1 < 0) return null;
            java.awt.image.BufferedImage cut = img.getSubimage(x0, y0, x1 - x0 + 1, y1 - y0 + 1);
            int size = 36;
            double scale = (double) size / Math.max(cut.getWidth(), cut.getHeight());
            int w = Math.max(1, (int) Math.round(cut.getWidth() * scale)), h = Math.max(1, (int) Math.round(cut.getHeight() * scale));
            java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(cut, (size - w) / 2, (size - h) / 2, w, h, null);
            g.dispose();
            return new ImageIcon(out);
        } catch (Exception e) {
            return null;
        }
    }

    /** Without its own picture: the dungeon it asks for, else its first reward item. */
    private static Icon fallbackIcon(Mission m) {
        for (Missions.Cond c : m.conds) {
            if (c.type == 3 || c.type == 14) {
                Icon i = ProfileView.dungeonIcon(c.target(), 36);
                if (i != null) return i;
            }
        }
        for (Missions.Reward r : m.rewards) {
            if (r.type == 2 && r.itemId() > 0) {
                Image img = CollectionGrid.icon(r.itemId());
                if (img != null) return new ImageIcon(img.getScaledInstance(36, 36, Image.SCALE_FAST));
            }
        }
        return null;
    }

    /** Who can do it: everyone (REGULAR), seasonal characters, or crucible ones; as a colored tag. */
    private static JComponent kind(Mission m) {
        String text = m.participants == 2 ? "SEASONAL" : m.participants == 4 ? "CRUCIBLE" : "REGULAR";
        Color color = m.participants == 2 ? new Color(0x3FC48A) : m.participants == 4 ? new Color(0xE0574F) : new Color(0xE5B23A);
        return tag(text, color);
    }

    /** A tag: an outline and the text in the color, nothing filled (as the guildmates' marks in the DPS logger). */
    private static JComponent tag(String text, Color color) {
        // as the guildmates' marks in the DPS logger: an outline and the text in the color, nothing filled
        JLabel l = new JLabel(text, SwingConstants.CENTER) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setStroke(new BasicStroke(1.2f));
                g2.setColor(color);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 6, 6);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        l.setForeground(color);
        l.setFont(l.getFont().deriveFont(Font.BOLD, 13f));
        l.setBorder(new EmptyBorder(2, 6, 2, 6));
        JPanel at = new JPanel(new GridBagLayout());   // as tall as its text, centered in the row (not stretched)
        at.setOpaque(false);
        at.add(l);
        return at;
    }

    /** Each objective: what it asks, a bar with how far you are; "One of" before alternatives. */
    private static JComponent goals(View v) {
        Mission m = v.mission();
        JPanel p = column();
        boolean done = v.status() == Status.CLAIMED || v.status() == Status.COOLDOWN;
        for (String w : m.worn()) {
            JLabel need = small(new JLabel("Requires " + w + " equipped"));
            need.setForeground(CLAIMABLE);
            p.add(need);
        }
        if (m.conds.isEmpty() && m.desc != null && !m.desc.isBlank()) p.add(small(muted(m.desc)));
        // how the objectives add up: every one needed, one of them, or every group with a choice in it
        List<List<Integer>> groups = m.groups();
        if (groups.size() > 1) {
            boolean choices = groups.stream().anyMatch(g -> g.size() > 1);
            JLabel all = small(new JLabel(choices ? "All of these groups:" : "All of these:"));
            all.setForeground(CLAIMABLE);
            p.add(all);
        }
        for (List<Integer> g : groups) {
            if (g.size() > 1) {
                JLabel one = small(new JLabel("One of these:"));
                one.setForeground(new Color(0x3FC4D6));
                p.add(one);
            }
            for (int i : g) {
                Missions.Cond cond = m.conds.get(i);
                int need = Math.max(1, cond.amount);
                int have = done ? need : Math.min(i < v.progress().length ? v.progress()[i] : 0, need);
                JLabel text = small(new JLabel(cond.text()));
                if (v.status() == Status.LOCKED) text.setForeground(Palette.MUTED);
                p.add(text);
                JProgressBar bar = new JProgressBar(0, need);
                bar.setValue(have);
                bar.setStringPainted(true);
                bar.putClientProperty("FlatLaf.style", "arc: 14");   // round ends (the theme's bars are square)
                bar.setString(String.format(Locale.ENGLISH, "%,d/%,d", have, need));
                if (have >= need) bar.setForeground(CLAIMED);   // green: the white numbers stay readable
                bar.setPreferredSize(new Dimension(W_GOAL - 40, 14));
                bar.setMaximumSize(bar.getPreferredSize());
                bar.setAlignmentX(LEFT_ALIGNMENT);
                p.add(bar);
                p.add(Box.createVerticalStrut(2));
            }
        }
        return p;
    }

    /** "→ 4,500 BXP + [a or b] + c": the points and the items, alternatives in brackets. */
    private static JComponent rewards(Mission m, boolean faded) {
        List<JComponent> row = new ArrayList<>();
        row.add(muted("→  "));
        boolean first = true;
        for (List<Missions.Reward> group : m.rewardGroups()) {
            if (!first) row.add(muted("+"));
            first = false;
            if (group.size() > 1) row.add(muted("["));
            for (int i = 0; i < group.size(); i++) {
                if (i > 0) row.add(small(muted("or")));
                row.add(reward(group.get(i), faded));
            }
            if (group.size() > 1) row.add(muted("]"));
        }
        return packed(row, W_REWARD);
    }

    private static JComponent reward(Missions.Reward r, boolean faded) {
        if (r.type == 1) {
            JLabel l = new JLabel(String.format(Locale.ENGLISH, "%,d BXP", r.amount));
            l.setForeground(faded ? Palette.MUTED : Section.color("LootGobbler.gold", Palette.GOLD));
            l.setToolTipText("Battle pass points");
            return l;
        }
        int id = r.itemId();
        Image img = id > 0 ? CollectionGrid.icon(id) : null;
        ItemDb.Item item = id > 0 ? ItemDb.get().item(id) : null;
        JLabel l = new JLabel(r.amount > 1 ? "x" + r.amount : "", img == null ? null : new ImageIcon(img.getScaledInstance(28, 28, Image.SCALE_FAST)),
                SwingConstants.LEFT);
        l.setIconTextGap(2);
        l.setToolTipText(item != null && !item.name.isEmpty() ? item.name : "Item " + id);
        if (img == null && l.getText().isEmpty()) l.setText(item != null ? item.name : "Item " + id);
        return l;
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    private static String span(long seconds) {
        long d = seconds / 86_400, h = seconds % 86_400 / 3600, m = seconds % 3600 / 60;
        if (d > 0) return d + "d " + h + "h";
        if (h > 0) return h + "h " + m + "m";
        return Math.max(1, m) + "m";
    }

    private static String ago(long ms) {
        long s = (System.currentTimeMillis() - ms) / 1000;
        return s < 60 ? "just now" : span(s) + " ago";
    }

    private static JComponent goldLine() {
        JComponent line = new JComponent() {
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(Section.color("LootGobbler.gold", Palette.GOLD));
                g.fillRect(0, 1, getWidth() - 4, 2);
            }
        };
        line.setPreferredSize(new Dimension(10, 4));
        line.setMaximumSize(new Dimension(Integer.MAX_VALUE, 4));
        return line;
    }

    private static <T extends JComponent> T small(T c) {
        c.setFont(c.getFont().deriveFont(Math.max(11f, c.getFont().getSize2D() - 2)));
        return c;
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

    private static JComponent left(JComponent c) {
        c.setAlignmentX(LEFT_ALIGNMENT);
        return c;
    }
}
