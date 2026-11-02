package lootgobbler.ui;

import lootgobbler.CharacterStore;
import lootgobbler.CharacterStore.Char;
import lootgobbler.CharacterStore.Sample;
import lootgobbler.CollectionStore;
import lootgobbler.DpsHistory;
import lootgobbler.ExaltStore;
import lootgobbler.ItemDb;
import lootgobbler.PetStore;
import lootgobbler.Themes;
import tomato.realmshark.enums.CharacterClass;
import tomato.realmshark.enums.LootBags;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Profile tab. Overview: your player name and account summary, your recently most played character, the last
 * 24 hours, recent drops and runs, top dungeons, exaltation and (later, from the server) daily quests. Plus the
 * Dungeon Stats page passed in.
 */
public class ProfileView extends SubTabs {
    private static final NumberFormat NUM = NumberFormat.getIntegerInstance();
    private static final long DAY_MS = 24 * 3_600_000L;
    private static final int RECENT = 5;
    private static final Font HEAD = new Font(Themes.HEAD_FONT, Font.PLAIN, 30);
    private static final Font BIG = new Font(Themes.HEAD_FONT, Font.PLAIN, 42);

    private record DropRow(int item, String bag, String where, long time) {
    }

    private final JPanel body = new JPanel();
    private String shown;   // what is on screen, so the frequent store updates only rebuild on a real change

    private final IntConsumer openCharacter;   // shows a character in the Characters tab

    public ProfileView(JComponent dungeonStats, IntConsumer openCharacter) {
        this.openCharacter = openCharacter;
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setOpaque(false);
        body.setBorder(new EmptyBorder(16, 20, 20, 20));
        JPanel holder = new JPanel(new BorderLayout());   // keeps the content at its natural height
        holder.setOpaque(false);
        holder.add(body, BorderLayout.NORTH);
        page("Overview", FitWidthPanel.scroll(holder, 24));
        page("Dungeon Stats", dungeonStats);
        done();

        Runnable later = () -> SwingUtilities.invokeLater(this::refresh);
        CharacterStore.get().onChange(later);
        DpsHistory.get().onChange(later);
        CollectionStore.get().onChange(later);
        ExaltStore.get().onChange(later);
        PetStore.get().onChange(later);
        DungeonStatsPanel.onUpdate(later);
        new javax.swing.Timer(60_000, e -> refresh()).start();   // "x min ago" and the 24 hour window move on by themselves
        refresh();
    }

    @Override
    public void updateUI() {
        super.updateUI();
        shown = null;   // theme colors are read while building: rebuild with the new theme
        if (body != null) SwingUtilities.invokeLater(this::refresh);
    }

    private void refresh() {
        long now = System.currentTimeMillis(), since = now - DAY_MS;
        CharacterStore store = CharacterStore.get();
        String name = store.playerName();
        List<Char> chars = store.all();
        Char most = store.mostPlayed();
        List<DpsHistory.Run> runs = DpsHistory.get().runs();
        List<DropRow> allDrops = allDrops();
        List<DropRow> drops = new ArrayList<>(allDrops.subList(0, Math.min(RECENT, allDrops.size())));
        List<Map.Entry<String, Integer>> dungeons = DungeonStatsPanel.topDungeons(RECENT);
        int pets = PetStore.get().all().size();

        List<Object> parts = new ArrayList<>(List.of(name == null ? "" : name, pets, drops, dungeons, now / 60_000));
        for (Char c : chars) parts.add(List.of(c.charId, c.alive, c.fame, c.level, c.skin, c.samples.size()));
        for (int i = 0; i < Math.min(RECENT, runs.size()); i++) parts.add(runs.get(i).start);
        for (CharacterClass cls : CharacterClass.CHAR_CLASS_LIST)
            parts.add(ExaltStore.total(ExaltStore.get().progress(cls.getId())));
        String state = parts.hashCode() + "";
        if (state.equals(shown)) return;
        shown = state;

        body.removeAll();
        body.add(row(2, playerCard(name, chars, pets), mostPlayedCard(store, most, since)));
        body.add(Box.createVerticalStrut(20));
        body.add(left(heading("Last 24 hours")));
        body.add(Box.createVerticalStrut(8));
        body.add(todayTiles(store, chars, runs, allDrops, since));
        body.add(Box.createVerticalStrut(20));
        body.add(row(2, recentDrops(drops), recentRuns(runs, allDrops)));
        body.add(Box.createVerticalStrut(20));
        body.add(row(3, topDungeons(dungeons), exaltation(), dailyQuests()));
        body.revalidate();
        body.repaint();
    }

    // ---- cards -----------------------------------------------------------------------------------------------

    private JComponent playerCard(String name, List<Char> chars, int pets) {
        JPanel p = column();
        p.add(left(caption("PLAYER")));
        JLabel player = new JLabel(name != null ? name : "Unknown player");
        player.setFont(new Font(Themes.HEAD_FONT, Font.PLAIN, 56));
        player.setForeground(gold());
        p.add(left(player));
        if (name == null) p.add(left(muted("Start the sniffer and enter the game once to show your name.")));

        List<Char> alive = chars.stream().filter(c -> c.alive).toList();
        long fame = alive.stream().mapToLong(c -> c.fame).sum();
        int classes = CharacterClass.CHAR_CLASS_LIST.length;
        long exalted = Arrays.stream(CharacterClass.CHAR_CLASS_LIST)
                .filter(c -> ExaltStore.total(ExaltStore.get().progress(c.getId())) == ExaltStore.MAX_PER_CLASS).count();
        p.add(Box.createVerticalStrut(8));
        p.add(left(soft(alive.size() + " characters   ·   " + NUM.format(fame) + " alive fame")));
        p.add(left(soft(exalted + " / " + classes + " classes exalted   ·   " + pets + " pets")));
        long last = chars.stream().mapToLong(c -> c.lastPlayed).max().orElse(0);
        if (last > 0) p.add(left(muted("Last played " + CharactersView.ago(last))));
        return card(p);
    }

    /**
     * The character's skin, class and progress, why it was picked (fame gained today, or played most recently),
     * and its fame over the last 24 hours. Clicking it opens the character in the Characters tab.
     */
    private JComponent mostPlayedCard(CharacterStore store, Char c, long since) {
        if (c == null) {
            JPanel p = column();
            p.add(left(caption("MOST PLAYED · 24H")));
            p.add(left(muted("Log in once so your character list is loaded.")));
            return card(p);
        }
        long gain = store.gainedToday(c);
        JLabel sprite = new JLabel(CharactersView.skin(c, 96));
        sprite.setVerticalAlignment(SwingConstants.TOP);

        JPanel text = column();
        text.add(left(caption("MOST PLAYED · 24H")));
        JLabel cls = new JLabel(c.className + "   " + c.statsMaxed + "/8");
        cls.setFont(cls.getFont().deriveFont(30f));
        text.add(left(cls));
        JLabel progress = new JLabel(c.level < 20 ? "Level " + c.level : NUM.format(c.fame) + " Fame");
        progress.setFont(progress.getFont().deriveFont(22f));
        progress.setForeground(gold());
        text.add(left(progress));
        JLabel why = gain > 0 ? new JLabel("+" + NUM.format(gain) + " fame in the last 24h")
                : muted(c.lastPlayed == 0 ? "not played yet" : "last played " + CharactersView.ago(c.lastPlayed));
        if (gain > 0) why.setForeground(Palette.GREEN);
        text.add(left(why));
        List<Sample> samples = c.samples.stream().filter(s -> s.t >= since).toList();
        if (samples.size() > 1) {
            text.add(Box.createVerticalStrut(6));
            text.add(left(new Sparkline(samples)));
        }

        JPanel inner = new JPanel(new BorderLayout(18, 0));
        inner.setOpaque(false);
        inner.add(sprite, BorderLayout.WEST);
        inner.add(text, BorderLayout.CENTER);
        JComponent card = card(inner);
        card.setToolTipText("Open in Characters");
        card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        int id = c.charId;
        Clicks.onClick(card, () -> openCharacter.accept(id));
        return card;
    }

    private JComponent todayTiles(CharacterStore store, List<Char> chars, List<DpsHistory.Run> runs,
                                  List<DropRow> drops, long since) {
        long fame = 0, playMs = 0;
        int gainers = 0, sessions = 0;
        for (Char c : chars) {
            long g = store.gainedToday(c);
            if (g > 0) {
                fame += g;
                gainers++;
            }
            for (CharacterStore.Session s : store.sessions(c, since)) {
                playMs += s.durationMs();
                sessions++;
            }
        }
        List<DpsHistory.Run> today = runs.stream().filter(r -> r.start >= since && !r.guildHall).toList();
        long kinds = today.stream().map(r -> r.dungeon).distinct().count();

        JPanel tiles = new JPanel(new GridLayout(1, 5, 12, 0));
        tiles.setOpaque(false);
        tiles.add(tile("FAME GAINED", (fame > 0 ? "+" : "") + NUM.format(fame),
                gainers == 0 ? "no fame yet" : "on " + gainers + (gainers == 1 ? " character" : " characters")));
        tiles.add(tile("DUNGEONS", NUM.format(today.size()), kinds == 0 ? "none yet" : kinds + " different"));
        tiles.add(bagTile("WHITE BAGS", "White", drops, since));
        tiles.add(bagTile("ORANGE BAGS", "Orange", drops, since));
        tiles.add(tile("PLAYTIME", duration(playMs),
                sessions == 0 ? "not played" : sessions + (sessions == 1 ? " session" : " sessions")));
        return left(tiles);
    }

    /**
     * Bags of one color dropped in the last 24 hours (items found in the same second and place count as one
     * bag), and the dungeon most of them came from.
     */
    private JComponent bagTile(String label, String color, List<DropRow> all, long since) {
        Map<String, String> bags = new LinkedHashMap<>();   // bag key -> where
        for (DropRow d : all) {
            if (d.time() >= since && color.equals(baseBag(d.bag()))) bags.put(d.time() / 1000 + "|" + d.where(), d.where());
        }
        Map<String, Long> byPlace = new HashMap<>();
        for (String w : bags.values()) byPlace.merge(w, 1L, Long::sum);
        String best = byPlace.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        return tile(label, NUM.format(bags.size()), best == null ? "none yet" : "most in " + best);
    }

    private JComponent recentDrops(List<DropRow> drops) {
        JPanel p = column();
        p.add(left(heading("Recent drops")));
        p.add(Box.createVerticalStrut(6));
        if (drops.isEmpty()) p.add(left(muted("Drops of collectible items show up here.")));
        for (DropRow d : drops) {
            JLabel icon = new JLabel(CharactersView.sprite(d.item(), 40));
            icon.setPreferredSize(new Dimension(44, 44));
            JPanel text = column();
            text.add(left(new JLabel(itemName(d.item()))));
            JLabel where = muted((d.bag().isEmpty() ? "Drop" : d.bag() + " bag") + (d.where().isEmpty() ? "" : "  ·  " + d.where()));
            where.setIcon(bagIcon(d.bag(), 18));
            text.add(left(where));
            JLabel ago = soft(CharactersView.ago(d.time()));
            JPanel line = new JPanel(new BorderLayout(12, 0));
            line.setOpaque(false);
            line.setBorder(new EmptyBorder(6, 0, 6, 0));
            line.add(icon, BorderLayout.WEST);
            line.add(text, BorderLayout.CENTER);
            line.add(ago, BorderLayout.EAST);
            p.add(left(line));
        }
        return card(p);
    }

    /**
     * The last runs: clear time, your best boss DPS, your damage rank on the main boss, and the bags that dropped
     * while you were in there.
     */
    private JComponent recentRuns(List<DpsHistory.Run> runs, List<DropRow> drops) {
        JPanel p = column();
        p.add(left(heading("Recent runs")));
        p.add(Box.createVerticalStrut(6));
        if (runs.isEmpty()) {
            p.add(left(muted("Dungeons you clear show up here.")));
            return card(p);
        }
        JPanel table = new JPanel(new GridBagLayout());
        table.setOpaque(false);
        GridBagConstraints g = new GridBagConstraints();
        g.anchor = GridBagConstraints.WEST;
        g.insets = new Insets(4, 0, 4, 14);
        String[] head = {"DUNGEON", "TIME", "BOSS DPS", "RANK", "LOOT"};
        for (int i = 0; i < head.length; i++) {
            g.gridx = i;
            g.weightx = i == 0 ? 1 : 0;
            g.fill = i == 0 ? GridBagConstraints.HORIZONTAL : GridBagConstraints.NONE;
            table.add(caption(head[i]), g);
        }
        for (int r = 0; r < Math.min(RECENT, runs.size()); r++) {
            DpsHistory.Run run = runs.get(r);
            g.gridy = r + 1;
            g.gridx = 0;
            g.weightx = 1;
            g.fill = GridBagConstraints.HORIZONTAL;
            table.add(new JLabel(run.dungeon), g);
            g.weightx = 0;
            g.fill = GridBagConstraints.NONE;
            g.gridx = 1;
            table.add(soft(clock(run.durationMs)), g);
            g.gridx = 2;
            table.add(new JLabel(shortDps(run.myPeakDps())), g);
            g.gridx = 3;
            table.add(rank(run), g);
            g.gridx = 4;
            table.add(runLoot(run, drops), g);
        }
        p.add(left(table));
        return card(p);
    }

    private static JLabel rank(DpsHistory.Run run) {
        if (run.guildHall) return soft(run.bosses.size() + (run.bosses.size() == 1 ? " loadout" : " loadouts"));
        DpsHistory.Boss boss = run.mainBoss();
        DpsHistory.PlayerRow me = boss == null ? null : boss.me();
        if (me == null) return muted("-");
        int rank = boss.rankOf(me);
        JLabel l = new JLabel("#" + rank + " of " + boss.players.size());
        l.setForeground(rank == 1 ? Palette.GREEN : rank <= 3 ? gold() : soft("").getForeground());
        return l;
    }

    private static JComponent runLoot(DpsHistory.Run run, List<DropRow> drops) {
        JPanel bags = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        bags.setOpaque(false);
        long end = run.start + run.durationMs + 5_000;   // the run is saved a moment after the last bag dropped
        Set<String> seen = new HashSet<>();
        for (DropRow d : drops) {
            if (d.time() < run.start || d.time() > end) continue;
            if (seen.add(d.time() / 1000 + "|" + d.bag())) {
                Icon icon = bagIcon(d.bag(), 22);
                if (icon != null) bags.add(new JLabel(icon));
            }
        }
        if (bags.getComponentCount() == 0) bags.add(muted("-"));
        return bags;
    }

    private JComponent topDungeons(List<Map.Entry<String, Integer>> dungeons) {
        JPanel p = column();
        p.add(left(heading("Top dungeons")));
        p.add(Box.createVerticalStrut(6));
        if (dungeons.isEmpty()) p.add(left(muted("Run some dungeons to see them here.")));
        int max = dungeons.isEmpty() ? 1 : Math.max(1, dungeons.get(0).getValue());
        for (Map.Entry<String, Integer> d : dungeons) {
            JPanel line = new JPanel(new BorderLayout());
            line.setOpaque(false);
            line.add(new JLabel(d.getKey()), BorderLayout.WEST);
            line.add(soft(NUM.format(d.getValue())), BorderLayout.EAST);
            p.add(left(line));
            p.add(Box.createVerticalStrut(2));
            p.add(left(new Bar(d.getValue() / (double) max)));
            p.add(Box.createVerticalStrut(8));
        }
        return card(p);
    }

    /**
     * Classes with every exaltation, a strip with one cell per class (full, started, none), and the class closest
     * to being fully exalted.
     */
    private JComponent exaltation() {
        JPanel p = column();
        p.add(left(heading("Exaltation")));
        p.add(Box.createVerticalStrut(6));
        if (ExaltStore.get().isEmpty()) {
            p.add(left(muted("Visit the nexus once so your exaltations are loaded.")));
            return card(p);
        }
        CharacterClass[] classes = CharacterClass.CHAR_CLASS_LIST;
        int[] totals = new int[classes.length];
        int full = 0, closest = -1;
        for (int i = 0; i < classes.length; i++) {
            totals[i] = ExaltStore.total(ExaltStore.get().progress(classes[i].getId()));
            if (totals[i] == ExaltStore.MAX_PER_CLASS) full++;
            else if (closest < 0 || totals[i] > totals[closest]) closest = i;
        }
        JLabel count = new JLabel(full + "");
        count.setFont(BIG);
        JLabel of = soft("/ " + classes.length + " classes maxed");
        of.setFont(of.getFont().deriveFont(24f));
        JPanel line = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        line.setOpaque(false);
        line.add(count);
        line.add(of);
        p.add(left(line));
        p.add(Box.createVerticalStrut(6));
        p.add(left(new ExaltStrip(totals)));
        p.add(Box.createVerticalStrut(10));
        if (closest >= 0) {
            int left = ExaltStore.MAX_PER_CLASS - totals[closest];
            p.add(left(soft("Closest next: " + CharacterClass.getName(classes[closest].getId()) + ", "
                    + left + (left == 1 ? " exaltation" : " exaltations") + " left")));
        } else {
            p.add(left(soft("Every class is fully exalted!")));
        }
        return card(p);
    }

    private JComponent dailyQuests() {
        JPanel p = column();
        p.add(left(heading("Daily quests")));
        p.add(Box.createVerticalStrut(6));
        p.add(left(muted("Coming soon from the Loot Gobbler server.")));
        return card(p);
    }

    // ---- data ------------------------------------------------------------------------------------------------

    /**
     * Every recorded drop, newest first.
     */
    private static List<DropRow> allDrops() {
        List<DropRow> out = new ArrayList<>();
        ZoneId zone = ZoneId.systemDefault();
        for (Map.Entry<Integer, CollectionStore.Entry> e : CollectionStore.get().snapshot().entrySet()) {
            for (CollectionStore.Find f : e.getValue().history()) {
                if (!CollectionStore.DROP.equals(f.source) || f.time == null) continue;
                try {
                    long t = LocalDateTime.parse(f.time).atZone(zone).toInstant().toEpochMilli();
                    out.add(new DropRow(e.getKey(), f.bag, f.where, t));
                } catch (Exception ignore) {
                }
            }
        }
        out.sort(Comparator.comparingLong(DropRow::time).reversed());
        return out;
    }

    /**
     * "Boosted White" -> "White".
     */
    private static String baseBag(String bag) {
        return bag == null ? "" : bag.startsWith("Boosted ") ? bag.substring(8) : bag;
    }

    /**
     * The bag's sprite, from its name as stored with a find ("White", "Boosted Orange"); null if unknown.
     */
    static Icon bagIcon(String bag, int size) {
        if (bag == null || bag.isEmpty()) return null;
        String name = bag.startsWith("Boosted ") ? "B." + bag.substring(8) : bag;
        for (LootBags b : LootBags.values()) {
            if (name.equals(LootBags.lootBagName(b.getId()))) return CharactersView.sprite(b.getId(), size);
        }
        return null;
    }

    private static String itemName(int id) {
        ItemDb.Item info = ItemDb.get().item(id);
        return info != null ? info.name : "Item " + id;
    }

    private static String duration(long ms) {
        long m = ms / 60_000;
        return m < 60 ? m + "m" : m / 60 + "h " + m % 60 + "m";
    }

    private static String clock(long ms) {
        long s = ms / 1000;
        return s / 60 + ":" + String.format("%02d", s % 60);
    }

    private static String shortDps(double v) {
        if (v <= 0) return "-";
        return v >= 1000 ? String.format(Locale.ENGLISH, "%.1fk", v / 1000) : NUM.format(Math.round(v));
    }

    // ---- building blocks -------------------------------------------------------------------------------------

    /**
     * Equal-width cards side by side, as tall as the tallest.
     */
    private static JComponent row(int columns, JComponent... cards) {
        JPanel p = new JPanel(new GridLayout(1, columns, 16, 0));
        p.setOpaque(false);
        for (JComponent c : cards) p.add(c);
        return left(p);
    }

    /**
     * A panel in the theme's paper color with a line border; its content sits at the top.
     */
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

    private static JComponent tile(String label, String value, String sub) {
        JPanel p = column();
        p.add(left(caption(label)));
        JLabel v = new JLabel(value);
        v.setFont(BIG);
        p.add(left(v));
        p.add(left(soft(sub)));
        return card(p);
    }

    private static JLabel heading(String text) {
        JLabel l = new JLabel(text);
        l.setFont(HEAD);
        l.setForeground(gold());
        return l;
    }

    private static JLabel caption(String text) {
        JLabel l = new JLabel(text);
        l.putClientProperty("FlatLaf.styleClass", "small");
        l.setForeground(Section.color("LootGobbler.muted", Palette.MUTED));
        return l;
    }

    private static JLabel soft(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(Section.color("LootGobbler.inkSoft", Palette.MUTED));
        return l;
    }

    private static JLabel muted(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(Palette.MUTED);
        return l;
    }

    private static Color gold() {
        return Section.color("LootGobbler.gold", Palette.GOLD);
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

    /**
     * Fame of a character over the samples given, as a stepped line.
     */
    private static class Sparkline extends JComponent {
        private final List<Sample> samples;

        Sparkline(List<Sample> samples) {
            this.samples = samples;
            setPreferredSize(new Dimension(240, 40));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
            setToolTipText("Fame over the last 24 hours");
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            int w = getWidth(), h = getHeight() - 4;
            long t0 = samples.get(0).t, t1 = Math.max(t0 + 1, samples.get(samples.size() - 1).t);
            long lo = samples.stream().mapToLong(s -> s.fame).min().orElse(0);
            long hi = Math.max(lo + 1, samples.stream().mapToLong(s -> s.fame).max().orElse(1));
            g2.setColor(Section.color("LootGobbler.line", Color.GRAY));
            g2.fillRect(0, h + 2, w, 2);
            g2.setColor(gold());
            g2.setStroke(new BasicStroke(3));
            int px = 0, py = 0;
            for (int i = 0; i < samples.size(); i++) {
                Sample s = samples.get(i);
                int x = (int) ((s.t - t0) * (w - 2) / (t1 - t0)) + 1;
                int y = h - (int) ((s.fame - lo) * (h - 2) / (hi - lo)) - 1;
                if (i > 0) {
                    g2.drawLine(px, py, x, py);   // stepped: fame stays until the next sample
                    g2.drawLine(x, py, x, y);
                }
                px = x;
                py = y;
            }
            g2.dispose();
        }
    }

    /**
     * A thin horizontal bar, filled to the given share.
     */
    private static class Bar extends JComponent {
        private final double share;

        Bar(double share) {
            this.share = share;
            setPreferredSize(new Dimension(100, 10));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 10));
        }

        @Override
        protected void paintComponent(Graphics g) {
            g.setColor(Section.color("LootGobbler.header", Color.LIGHT_GRAY));
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setColor(gold());
            g.fillRect(0, 0, (int) Math.round(getWidth() * share), getHeight());
        }
    }

    /**
     * One cell per class: gold when fully exalted, darker gold when started, empty otherwise.
     */
    private static class ExaltStrip extends JComponent {
        private final int[] totals;

        ExaltStrip(int[] totals) {
            this.totals = totals;
            setPreferredSize(new Dimension(200, 18));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 18));
        }

        @Override
        protected void paintComponent(Graphics g) {
            int n = totals.length, gap = 3;
            if (n == 0) return;
            double cell = (getWidth() - gap * (n - 1)) / (double) n;
            Color full = gold(), started = Section.color("LootGobbler.goldDark", Palette.GOLD_DARK),
                    none = Section.color("LootGobbler.header", Color.LIGHT_GRAY);
            for (int i = 0; i < n; i++) {
                g.setColor(totals[i] == ExaltStore.MAX_PER_CLASS ? full : totals[i] > 0 ? started : none);
                int x = (int) Math.round(i * (cell + gap));
                g.fillRect(x, 0, (int) Math.round(cell), getHeight());
            }
        }
    }
}
