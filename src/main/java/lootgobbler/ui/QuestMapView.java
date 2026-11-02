package lootgobbler.ui;

import lootgobbler.GobblerServer;
import lootgobbler.GobblerServer.QuestMap;
import lootgobbler.GobblerServer.QuestMapClear;
import lootgobbler.GobblerServer.QuestMaps;
import lootgobbler.Themes;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.HierarchyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.awt.geom.QuadCurve2D;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Guild > Quest Map: the guild's chain of dungeons. Any guildie's clear of the current stage unlocks the next one;
 * after the last stage the guild earns a medal (not there yet). The current map is on top, past maps below it.
 * Each map is drawn in its own style (parchment, hex board, overworld, Oryx's tower, constellation) and one of four
 * color variations, all chosen by the server.
 */
public class QuestMapView extends JPanel {
    private static final int PAST = 20;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.ENGLISH);

    private final GobblerServer server = GobblerServer.get();
    private final Section section;
    private final JPanel body = new JPanel();
    private boolean loading;
    private long skew;   // server clock minus ours

    public QuestMapView() {
        super(new BorderLayout());
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setOpaque(false);
        body.setBorder(new EmptyBorder(12, 12, 12, 12));
        JPanel holder = new JPanel(new BorderLayout());
        holder.setOpaque(false);
        holder.add(body, BorderLayout.NORTH);
        JButton refresh = new JButton("Refresh");
        refresh.addActionListener(e -> load());
        section = new Section("Quest Map", Palette.GOLD, FitWidthPanel.scroll(holder, 24)).actions(refresh);
        add(section);
        message("Open this tab while logged in to a server to see your guild's quest map.");
    }

    /**
     * Loads the maps (the Guild tab calls this whenever it refreshes).
     */
    public void load() {
        if (loading) return;
        if (server.user() == null || server.state() != GobblerServer.State.ONLINE) {
            message("Log in to a server in the Server tab to see your guild's quest map.");
            return;
        }
        loading = true;
        server.questMaps(PAST).whenComplete((maps, e) -> SwingUtilities.invokeLater(() -> {
            loading = false;
            if (e != null) message(GuildPanel.loadError(e));
            else show(maps);
        }));
    }

    private void message(String text) {
        body.removeAll();
        JLabel l = new JLabel(text);
        l.setForeground(Palette.MUTED);
        body.add(l);
        body.revalidate();
        body.repaint();
    }

    private void show(QuestMaps maps) {
        skew = maps.now - System.currentTimeMillis();
        section.setTitle("Quest Map - " + maps.guild);
        body.removeAll();
        if (maps.current != null) body.add(card(maps.current, true));
        body.add(Box.createVerticalStrut(22));
        JLabel past = new JLabel("Past quest maps");
        past.setFont(new Font(Themes.HEAD_FONT, Font.PLAIN, 30));
        past.setForeground(Section.color("LootGobbler.gold", Palette.GOLD));
        past.setAlignmentX(LEFT_ALIGNMENT);
        body.add(past);
        body.add(Box.createVerticalStrut(8));
        if (maps.past.isEmpty()) {
            JLabel none = new JLabel("None yet: this is your guild's first quest map.");
            none.setForeground(Palette.MUTED);
            none.setAlignmentX(LEFT_ALIGNMENT);
            body.add(none);
        }
        for (QuestMap m : maps.past) {
            body.add(card(m, false));
            body.add(Box.createVerticalStrut(16));
        }
        body.revalidate();
        body.repaint();
    }

    private long now() {
        return System.currentTimeMillis() + skew;
    }

    // ---- one map --------------------------------------------------------------------------------------------

    private JComponent card(QuestMap m, boolean current) {
        int n = m.dungeons.size(), cleared = Math.min(m.clears.size(), n);
        boolean complete = m.completedAt != null || cleared == n, ended = now() >= m.endsAt;

        JLabel name = new JLabel(m.name);
        name.setFont(new Font(Themes.HEAD_FONT, Font.PLAIN, current ? 40 : 30));
        name.setForeground(Section.color("LootGobbler.gold", Palette.GOLD));
        String status;
        if (complete) status = "Completed on " + DAY_TIME.format(local(m.completedAt != null ? m.completedAt : m.clears.get(n - 1).at))
                + " - the guild earns the medal (coming soon)";
        else if (ended) status = "Ended " + DAY.format(local(m.endsAt)) + " with " + cleared + " of " + n + " stages cleared";
        else status = "Stage " + (cleared + 1) + " of " + n + "  ·  ends in " + left(m.endsAt - now());
        JLabel sub = new JLabel(DAY.format(local(m.startsAt)) + " - " + DAY.format(local(m.endsAt)) + "  ·  " + status);
        sub.setForeground(complete ? Palette.GREEN : Section.color("LootGobbler.muted", Palette.MUTED));
        JPanel head = column();
        head.add(left(name));
        head.add(left(sub));

        JLabel detail = new JLabel(" ");
        detail.setFont(detail.getFont().deriveFont(18f));
        MapCanvas canvas = new MapCanvas(m, !complete && !ended, i -> detail.setText(stageText(m, i)));
        detail.setText(stageText(m, canvas.selected));

        JPanel log = column();
        for (int i = 0; i < cleared; i++) {
            QuestMapClear c = m.clears.get(i);
            JLabel l = new JLabel((i + 1) + ".  " + m.dungeons.get(i) + "  -  " + c.ign + ", " + DAY_TIME.format(local(c.at)));
            l.setForeground(Section.color("LootGobbler.muted", Palette.MUTED));
            log.add(left(l));
        }

        JPanel p = column();
        p.add(left(head));
        p.add(Box.createVerticalStrut(8));
        p.add(left(canvas));
        p.add(Box.createVerticalStrut(8));
        p.add(left(detail));
        if (cleared > 0) {
            p.add(Box.createVerticalStrut(4));
            p.add(left(log));
        }
        JPanel card = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(Section.color("LootGobbler.paper", Color.WHITE));
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(Section.color("LootGobbler.line", Color.GRAY));
                g.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
                g.drawRect(1, 1, getWidth() - 3, getHeight() - 3);
            }
        };
        card.setBorder(new EmptyBorder(14, 16, 14, 16));
        card.add(p, BorderLayout.NORTH);
        card.setAlignmentX(LEFT_ALIGNMENT);
        return card;
    }

    private String stageText(QuestMap m, int i) {
        int n = m.dungeons.size(), cleared = m.clears.size();
        if (i >= n) return "The medal: earned by clearing all " + n + " stages (coming soon).";
        String head = "Stage " + (i + 1) + "  ·  " + m.dungeons.get(i) + "  -  ";
        boolean active = m.completedAt == null && now() < m.endsAt;
        if (i < cleared) {
            QuestMapClear c = m.clears.get(i);
            return head + "cleared by " + c.ign + ", " + DAY_TIME.format(local(c.at));
        }
        if (i == cleared && active) return head + "in progress: the first guildie to clear it moves everyone on";
        if (!active) return head + "not reached";
        return head + "locked until stage " + i + " is cleared";
    }

    private static java.time.ZonedDateTime local(long ms) {
        return Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault());
    }

    private static String left(long ms) {
        long h = Math.max(0, ms) / 3_600_000;
        return h >= 48 ? (h / 24) + " days" : h + " hours";
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

    // ---- palettes -------------------------------------------------------------------------------------------

    /**
     * A map's colors: background and second background, frame and ink (outlines), road ahead and walked, stage
     * cleared / current / locked, text on the map, and three decoration colors.
     */
    private record Pal(Color bg, Color bg2, Color frame, Color ink, Color road, Color walked, Color done,
                       Color current, Color locked, Color text, Color deco1, Color deco2, Color deco3) {
        static Pal of(int... c) {
            Color[] k = new Color[c.length];
            for (int i = 0; i < c.length; i++) k[i] = new Color(c[i]);
            return new Pal(k[0], k[1], k[2], k[3], k[4], k[5], k[6], k[7], k[8], k[9], k[10], k[11], k[12]);
        }
    }

    private static Pal palette(String style, int variant) {
        int v = Math.floorMod(variant, 4);
        return switch (style) {
            case "hex" -> new Pal[]{
                    Pal.of(0x101A20, 0x2E4A36, 0x0C1418, 0x0C1418, 0x34404A, 0xB08A3A, 0xE5B23A, 0x7FD0B0, 0x3A464E, 0xBFF0DC, 0x22402C, 0x3A464E, 0x22465C),
                    Pal.of(0x2A1E10, 0x9A7A44, 0x1A1208, 0x1A1208, 0x5A4A30, 0xE0B060, 0xF0C060, 0xFF8A3A, 0x5A4A34, 0xFFE0B0, 0x7A5A2A, 0x6A5A4A, 0x3A6A7A),
                    Pal.of(0x0E1A26, 0x9AB8C8, 0x081018, 0x081018, 0x4A6070, 0xE0F0FF, 0x9FD8F0, 0xFFFFFF, 0x4A5A68, 0xE0F0FF, 0x7A9AAA, 0x5A6A78, 0x3A6A8A),
                    Pal.of(0x1A0A08, 0x3A2018, 0x0A0404, 0x0A0404, 0x4A2A20, 0xFFB040, 0xFFB040, 0xF0503A, 0x4A3028, 0xFFD9A8, 0x2A1410, 0x4A3A34, 0xC8401A)}[v];
            case "overworld" -> new Pal[]{
                    Pal.of(0x7EC8F0, 0x6CC24A, 0x1A1A1A, 0x1A1A1A, 0x4FA83A, 0xFFF4C0, 0xE8483A, 0xF8D030, 0xA0A8A0, 0x1A1A1A, 0x4FA83A, 0x2E8B3A, 0x3A8EE0),
                    Pal.of(0xF0C890, 0xE0B060, 0x1A1A1A, 0x1A1A1A, 0xC89A40, 0xFFF4D0, 0xE8483A, 0x3AA0E0, 0xB0A890, 0x1A1A1A, 0xC8903A, 0x3A8A3A, 0x3A8EE0),
                    Pal.of(0xA8D0F0, 0xF4F8FF, 0x1A1A1A, 0x1A1A1A, 0xC8D8E8, 0x5A9AD0, 0xE8483A, 0xF8D030, 0xA0A8B0, 0x1A1A1A, 0xD0E0F0, 0x2E6A4A, 0x6AB0E0),
                    Pal.of(0x2A1838, 0x4A3040, 0x0A0608, 0x0A0608, 0x3A2830, 0xFFB040, 0xE8483A, 0xF8D030, 0x6A6070, 0xF4E6C6, 0x3A2434, 0x1A1018, 0xE8592A)}[v];
            case "oryx" -> new Pal[]{
                    Pal.of(0x120C1C, 0x2A1E3A, 0x07040C, 0x07040C, 0x4A4058, 0xE5B23A, 0xE5B23A, 0xB07BE0, 0x5A4E6A, 0xF4E6C6, 0xB8364A, 0x1E1430, 0x8A1E2E),
                    Pal.of(0x1A0808, 0x3A1818, 0x0A0303, 0x0A0303, 0x5A3030, 0xF0C060, 0xF0C060, 0xE8483A, 0x6A4A4A, 0xF4E0D0, 0xF0E8E0, 0x2A1010, 0x5A1A1A),
                    Pal.of(0x0C141E, 0x26384A, 0x04080C, 0x04080C, 0x4A5A6A, 0xE0F0FF, 0xE0F0FF, 0x9FD8F0, 0x4A5A68, 0xE0F0FF, 0xDCEBF2, 0x162636, 0x2D7AB0),
                    Pal.of(0x1A140A, 0x3A2E1A, 0x0A0804, 0x0A0804, 0x5A4A30, 0xF0C060, 0xF0C060, 0xFFD870, 0x5A503A, 0xFFF0D0, 0xF0C060, 0x2A2014, 0x6A1A2A)}[v];
            case "constellation" -> new Pal[]{
                    Pal.of(0x0B1026, 0x2A3060, 0x05081A, 0x05081A, 0x9FD8F0, 0xC9C4F0, 0xFFF2B0, 0x9FD8F0, 0x4A5080, 0xC9C4F0, 0x8049A8, 0xFFFFFF, 0x8A90C0),
                    Pal.of(0x06141A, 0x1A4A44, 0x020A0C, 0x020A0C, 0x7FF0C0, 0xB0F0D8, 0xD0FFD8, 0x7FF0C0, 0x3A6058, 0xB0F0D8, 0x2A8A6A, 0xFFFFFF, 0x6AA0A0),
                    Pal.of(0x1A0610, 0x4A1A34, 0x0A0206, 0x0A0206, 0xFF7A9A, 0xF0C0D0, 0xFFD0A0, 0xFF7A9A, 0x6A3048, 0xF0C0D0, 0xA8284A, 0xFFFFFF, 0xA07090),
                    Pal.of(0x041A20, 0x1A4A5A, 0x020C10, 0x020C10, 0x6AD8F0, 0xC0E8F0, 0xF0F8FF, 0x6AD8F0, 0x2A5A6A, 0xC0E8F0, 0x1A6A8A, 0xFFFFFF, 0x7AA8B8)}[v];
            case "neon" -> new Pal[]{   // cyan (default), orange, magenta, white
                    Pal.of(0x04070C, 0x0A1A24, 0x0E2A38, 0x000000, 0x1E4A5A, 0x6FF0FF, 0x6FF0FF, 0xFFFFFF, 0x2A4A58, 0xBFF8FF, 0x0F5A6E, 0xFF9A3A, 0x6FF0FF),
                    Pal.of(0x0C0604, 0x241208, 0x3A1A08, 0x000000, 0x5A2A10, 0xFF9A3A, 0xFF9A3A, 0xFFFFFF, 0x5A3A28, 0xFFD8B0, 0x6E2E0F, 0x6FF0FF, 0xFF9A3A),
                    Pal.of(0x0A040C, 0x1E0A24, 0x34103E, 0x000000, 0x4A1A5A, 0xFF5AE0, 0xFF5AE0, 0xFFFFFF, 0x4A2A58, 0xFFC8F4, 0x5A1A6E, 0x6FF0FF, 0xFF5AE0),
                    Pal.of(0x06080C, 0x141A22, 0x2A3440, 0x000000, 0x3A4450, 0xE8F4FF, 0xE8F4FF, 0x9FD8F0, 0x3A4450, 0xE8F4FF, 0x2A3A4A, 0xFF9A3A, 0xE8F4FF)}[v];
            case "adventure" -> new Pal[]{   // meadow (default), desert, snowfield, dusk
                    Pal.of(0x5CA83A, 0xF0DCA0, 0x1A1A1A, 0x1A1A1A, 0xC8B078, 0xF4E4B0, 0xE5B23A, 0x3AA0E0, 0x6A6A6A, 0xF4F4F4, 0x2E7A2E, 0x8A6A40, 0x3A7AD0),
                    Pal.of(0xD8B870, 0xF0E0B0, 0x1A1A1A, 0x1A1A1A, 0xB8986A, 0xFFF4D0, 0xE5B23A, 0xE8483A, 0x7A6A50, 0xF4F4F4, 0x6A8A3A, 0x9A6A3A, 0x3A8AB0),
                    Pal.of(0xE8F0F8, 0xC8D8E8, 0x1A1A1A, 0x1A1A1A, 0xA8B8C8, 0x5A9AD0, 0xE5B23A, 0xE8483A, 0x8A9AAA, 0xF4F4F4, 0x2E5A4A, 0x8A9AAA, 0x6AA0D0),
                    Pal.of(0x3A3A6A, 0x8A7AAA, 0x0A0A14, 0x0A0A14, 0x5A4A7A, 0xD8C8F0, 0xE5B23A, 0xF8D030, 0x5A5A6A, 0xF4F4F4, 0x2A2A4A, 0x5A4A6A, 0x2A4A8A)}[v];
            case "hunt" -> new Pal[]{   // earth (default), volcanic, tundra, swamp
                    Pal.of(0xC8B490, 0xE0CCA4, 0x4A3420, 0x2A1E12, 0x8A7050, 0x2A1E12, 0xB8282A, 0xE58A2A, 0x8A7A60, 0x2A1E12, 0x8AA060, 0xA89A80, 0x7AA8C0),
                    Pal.of(0x4A3028, 0x6A4434, 0x1A0E08, 0x1A0E08, 0x8A5A3A, 0xFFB040, 0xE8483A, 0xFFB040, 0x6A5048, 0xF4E0C8, 0x8A2A1A, 0x5A4A44, 0xD8501A),
                    Pal.of(0xD8E4EC, 0xEEF4F8, 0x2A3A4A, 0x1A2A3A, 0x7A8A9A, 0x2A4A6A, 0xB8282A, 0x2D7AB0, 0x9AAAB8, 0x1A2A3A, 0x9AB0A8, 0xB0B8C0, 0x8AB8D8),
                    Pal.of(0x6A7A4A, 0x86965C, 0x2A3018, 0x1A2010, 0x4A5A2A, 0xD8E070, 0xB8282A, 0xD8E070, 0x4A5438, 0xF0F0D0, 0x3A5A2A, 0x7A7A5A, 0x3A6A5A)}[v];
            case "hellforge" -> new Pal[]{   // hellfire (default), toxic, techbase, ember
                    Pal.of(0x140808, 0x2A1414, 0x4A3A36, 0x000000, 0x3A2020, 0xE8483A, 0x3CC060, 0xFF3A2A, 0xE5B23A, 0xF4D8C8, 0x5A4A44, 0xFF6A2A, 0x8A1A10),
                    Pal.of(0x0A120A, 0x1A2A18, 0x3A4A38, 0x000000, 0x1E3A1E, 0x7AF04A, 0x7AF04A, 0xB8FF5A, 0xE5B23A, 0xD8F4C8, 0x3A4A38, 0x7AF04A, 0x2A5A1A),
                    Pal.of(0x0C1014, 0x1C242C, 0x4A5460, 0x000000, 0x2A3440, 0x6AB0FF, 0x3CC060, 0x6AB0FF, 0xE5B23A, 0xD8E4F0, 0x4A5460, 0x6AB0FF, 0x2A3A4A),
                    Pal.of(0x140E04, 0x2A2010, 0x5A4A2A, 0x000000, 0x3A2A10, 0xFFC040, 0xFFC040, 0xFF7A2A, 0xE5B23A, 0xF4E4C0, 0x5A4A2A, 0xFF9A2A, 0x6A3A0A)}[v];
            default -> new Pal[]{   // parchment
                    Pal.of(0xE9D8B8, 0xD7BF94, 0x6B4E2E, 0x2C1D0E, 0x8C6C47, 0xB07D0A, 0xE5B23A, 0xE5B23A, 0x8C6C47, 0x4A3522, 0xB89A6A, 0x6E7F3E, 0x9DB8B0),
                    Pal.of(0xD8DCC0, 0xBFC6A0, 0x4A5A30, 0x1E2814, 0x7A8A58, 0x5A8A2A, 0xA8C94A, 0xC8E070, 0x7A8A58, 0x2E3A1E, 0x9AA078, 0x3E6A2E, 0x8FB8A8),
                    Pal.of(0xDCEBF2, 0xB6D4E3, 0x1B3A52, 0x0F2536, 0x7F9DB0, 0x2D7AB0, 0x5FB4DC, 0x9FD8F0, 0x7F9DB0, 0x1B3A52, 0x8FB3C8, 0x2F5A4A, 0xA9D2E6),
                    Pal.of(0x3A1C14, 0x5A2A1A, 0x1A0805, 0x1A0805, 0x6A4A3A, 0xFFB040, 0xF07A3A, 0xFFB040, 0x5A3A2E, 0xFFD9A8, 0x241008, 0x2A1410, 0xE8592A)}[v];
        };
    }

    private static final Color[] PORTALS = {new Color(0x8049A8), new Color(0x3C9347), new Color(0x2D7AB0), new Color(0xB8364A),
            new Color(0xE5B23A), new Color(0x3FC3C0), new Color(0xE0679C), new Color(0x8C6C47)};

    private static final Color LIGHT = new Color(0xF4E6C6);

    private static Color portal(String dungeon) {
        return PORTALS[Math.floorMod(dungeon.hashCode(), PORTALS.length)];
    }

    // ---- the painted map ------------------------------------------------------------------------------------

    /**
     * One quest map, painted in its style. Stages are clickable; the current one animates while the map runs.
     */
    private class MapCanvas extends JComponent {
        private static final int H = 380;
        private final QuestMap m;
        private final Pal p;
        private final int n, cleared;
        private final boolean active;
        private final java.util.function.IntConsumer onPick;
        private final List<Rectangle> hits = new ArrayList<>();
        int selected;
        private int phase;
        private final javax.swing.Timer timer = new javax.swing.Timer(80, e -> {
            phase++;
            repaint();
        });

        MapCanvas(QuestMap m, boolean active, java.util.function.IntConsumer onPick) {
            this.m = m;
            this.p = palette(m.style, m.variant);
            this.n = m.dungeons.size();
            this.cleared = Math.min(m.clears.size(), n);
            this.active = active;
            this.onPick = onPick;
            this.selected = Math.min(cleared, n);
            setToolTipText("");
            setPreferredSize(new Dimension(600, height()));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, height()));
            addHierarchyListener(e -> {
                if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) == 0 || !active) return;
                if (isShowing()) timer.start();
                else timer.stop();
            });
            Clicks.onClick(this, e -> {
                int i = hit(e.getPoint());
                if (i < 0) return;
                selected = i;
                onPick.accept(i);
                repaint();
            });
            addMouseMotionListener(new MouseAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    setCursor(Cursor.getPredefinedCursor(hit(e.getPoint()) >= 0 ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
                }
            });
        }

        private int height() {
            return m.style.equals("oryx") ? 150 + n * 58 + 40 : H;
        }

        private int hit(Point pt) {
            for (int i = 0; i < hits.size(); i++) if (hits.get(i) != null && hits.get(i).contains(pt)) return i;
            return -1;
        }

        @Override
        public String getToolTipText(MouseEvent e) {
            int i = hit(e.getPoint());
            return i < 0 ? null : stageText(m, i);
        }

        boolean done(int i) {
            return i < cleared;
        }

        boolean current(int i) {
            return active && i == cleared;
        }

        /** Stage positions (and the medal at index n) for the horizontal styles. */
        private Point[] points(int w, int h, boolean scatter) {
            Point[] pts = new Point[n + 1];
            int margin = 80;
            Random r = new Random(m.id * 31 + 7);
            for (int i = 0; i <= n; i++) {
                int x = margin + (w - 2 * margin) * i / n;
                double f = scatter ? 0.24 + r.nextDouble() * 0.42 : (i % 2 == 0 ? 0.66 : 0.34);
                pts[i] = new Point(x, (int) (h * f));
            }
            return pts;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            hits.clear();
            for (int i = 0; i <= n; i++) hits.add(null);
            int w = getWidth(), h = getHeight();
            switch (m.style) {
                case "hex" -> paintHex(g, w, h);
                case "overworld" -> paintOverworld(g, w, h);
                case "oryx" -> paintOryx(g, w, h);
                case "constellation" -> paintConstellation(g, w, h);
                case "neon" -> paintNeon(g, w, h);
                case "adventure" -> paintAdventure(g, w, h);
                case "hunt" -> paintHunt(g, w, h);
                case "hellforge" -> paintHellforge(g, w, h);
                default -> paintParchment(g, w, h);
            }
            g.setColor(p.frame);
            g.setStroke(new BasicStroke(4));
            g.drawRect(2, 2, w - 4, h - 4);
            g.dispose();
        }

        // -- parchment: a winding road between portal tiles, mountains and trees on old paper

        private void paintParchment(Graphics2D g, int w, int h) {
            g.setColor(p.bg);
            g.fillRect(0, 0, w, h);
            g.setColor(p.bg2);
            g.setStroke(new BasicStroke(4));
            g.drawRect(8, 8, w - 16, h - 16);
            Random r = new Random(m.id);
            for (int i = 0; i < 6; i++) {   // mountains and trees, kept to the top and bottom bands
                int x = r.nextInt(Math.max(1, w - 80)), y = i % 2 == 0 ? 20 + r.nextInt(30) : h - 90 + r.nextInt(30);
                g.setColor(p.deco1);
                g.fillPolygon(new int[]{x, x + 34, x + 68}, new int[]{y + 50, y, y + 50}, 3);
                g.setColor(p.deco2);
                int tx = (x + 220) % Math.max(1, w - 40);
                g.fillPolygon(new int[]{tx, tx + 11, tx + 22}, new int[]{y + 34, y + 4, y + 34}, 3);
                g.fillRect(tx + 9, y + 34, 4, 8);
            }
            g.setColor(p.deco3);
            g.fillOval(-60, h - 70, 260, 140);
            compass(g, w, h, p.frame);
            Point[] pts = points(w, h - 40, false);
            for (int i = 0; i < n; i++) {
                Point a = pts[i], b = pts[i + 1];
                QuadCurve2D road = new QuadCurve2D.Float(a.x, a.y, (a.x + b.x) / 2f, i % 2 == 0 ? Math.min(a.y, b.y) - 50 : Math.max(a.y, b.y) + 50, b.x, b.y);
                road(g, road, i);
            }
            for (int i = 0; i < n; i++) tile(g, pts[i], i, 56);
            medal(g, pts[n], p.frame);
        }

        /**
         * Dash offset that moves the dashes forward along a line as the animation runs. Java needs it 0 or more,
         * so it counts down within one dash pattern of the given length instead of going negative.
         */
        private float march(int pattern, int speed) {
            return pattern - 1 - Math.floorMod(phase * speed, pattern);
        }

        private void road(Graphics2D g, Shape s, int i) {
            boolean walked = i < cleared, next = current(i + 1) || (active && i == cleared);
            g.setColor(walked ? p.walked : p.road);
            g.setStroke(walked ? new BasicStroke(7, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    : new BasicStroke(6, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1, new float[]{12, 12}, next ? march(24, 2) : 0));
            g.draw(s);
        }

        /** A square portal tile with its number, a check when cleared, a padlock while locked, and its name. */
        private void tile(Graphics2D g, Point c, int i, int size) {
            boolean d = done(i), cur = current(i);
            int x = c.x - size / 2, y = c.y - size / 2;
            if (cur) glow(g, c, size, p.current);
            g.setColor(d || cur ? p.bg : p.bg2);
            g.fillRect(x, y, size, size);
            g.setColor(d ? p.done : cur ? p.current : p.locked);
            g.setStroke(new BasicStroke(4));
            g.drawRect(x, y, size, size);
            Color pc = d || cur ? portal(m.dungeons.get(i)) : p.locked;
            g.setColor(pc);
            g.setStroke(new BasicStroke(5));
            g.drawRect(x + 12, y + 12, size - 24, size - 24);
            g.fillRect(c.x - 5, c.y - 5, 10, 10);
            badge(g, x - 8, y - 8, String.valueOf(i + 1), p.ink, LIGHT);
            if (d) check(g, x + size - 12, y + size - 12);
            if (!d && !cur) padlock(g, x + size - 10, y + size - 10);
            if (d || cur) label(g, c.x, y + size + 8, m.dungeons.get(i), d ? p.done : p.current, p.ink, p.ink);
            else label(g, c.x, y + size + 8, m.dungeons.get(i), p.text, p.bg2, p.ink);
            selection(g, new Rectangle(x - 6, y - 6, size + 12, size + 12), i);
        }

        // -- hex board: a trail of hexes across a honeycomb of terrain

        private void paintHex(Graphics2D g, int w, int h) {
            g.setColor(p.bg);
            g.fillRect(0, 0, w, h);
            // a board that fills the width; the stages (and the medal last) spread evenly over its columns, the
            // road between two stages runs along the earlier stage's row and switches row halfway
            double r = 40, step = Math.sqrt(3) * r;
            int cols = Math.max(2 * n + 2, (int) ((w - 2 * r) / (1.5 * r)) + 1), rows = (int) (h / step) + 1;
            double x0 = (w - (1.5 * r * (cols - 1) + 2 * r)) / 2 + r, y0 = step / 2 + 6;
            int mid = Math.max(1, rows / 2 - 1);
            int[] stageCol = new int[n + 1], stageRow = new int[n + 1];
            for (int i = 0; i <= n; i++) {
                stageCol[i] = 1 + (int) Math.round(i * (cols - 2.0) / n);
                stageRow[i] = i % 2 == 0 ? mid : mid + 1;
            }
            int[][] trail = new int[cols][rows];   // 0 terrain, 1 road walked, 2 road ahead, 3 + stage: a stage
            for (int[] col : trail) java.util.Arrays.fill(col, 0);
            trail[0][stageRow[0]] = 1;
            for (int i = 0; i <= n; i++) {
                trail[stageCol[i]][stageRow[i]] = 3 + i;
                if (i == n) break;
                int from = stageCol[i] + 1, to = stageCol[i + 1] - 1, half = (from + to) / 2;
                for (int c = from; c <= to; c++) trail[c][c <= half ? stageRow[i] : stageRow[i + 1]] = i < cleared ? 1 : 2;
            }
            Random rnd = new Random(m.id);
            Color[] terrain = {p.bg2, p.bg2, p.deco1, p.deco2, p.deco3};
            Point[] centers = new Point[n + 1];
            for (int c = 0; c < cols; c++) {
                for (int row = 0; row < rows; row++) {
                    double cx = x0 + c * 1.5 * r, cy = y0 + row * step + (c % 2 == 1 ? step / 2 : 0);
                    Color t = terrain[rnd.nextInt(terrain.length)];
                    int kind = trail[c][row];
                    if (kind >= 3) {
                        centers[kind - 3] = new Point((int) cx, (int) cy);
                        continue;   // drawn on top below
                    }
                    Path2D hx = hexagon(cx, cy, r - 1.5);
                    g.setColor(kind == 1 ? p.walked : kind == 2 ? p.road : t);
                    g.fill(hx);
                    g.setColor(p.ink);
                    g.setStroke(new BasicStroke(2));
                    g.draw(hx);
                    if (kind == 2) {   // the road ahead: a dot, so it reads as road and not as rock
                        g.setColor(p.locked.brighter());
                        g.fillOval((int) cx - 5, (int) cy - 5, 10, 10);
                    }
                }
            }
            for (int i = 0; i <= n; i++) {
                Point c = centers[i];
                if (c == null) continue;
                boolean d = i < n && done(i), cur = i < n && current(i), medal = i == n;
                boolean won = medal && cleared == n;
                if (cur) glow(g, c, (int) (r * 1.8), p.current);
                Color edge = d || won ? p.done : cur ? p.current : p.locked;
                g.setColor(edge);
                g.fill(hexagon(c.x, c.y, r - 1));
                g.setColor(d ? p.bg : cur ? p.bg : p.bg);
                g.fill(hexagon(c.x, c.y, r - 8));
                g.setColor(d || won ? p.done : cur ? p.current : p.locked);
                g.setFont(new Font(Themes.HEAD_FONT, Font.PLAIN, (int) (r * 0.9)));
                String t = medal ? "?" : String.valueOf(i + 1);
                FontMetrics fm = g.getFontMetrics();
                g.drawString(t, c.x - fm.stringWidth(t) / 2, c.y + fm.getAscent() / 2 - 2);
                if (medal) {
                    label(g, c.x, (int) (c.y + r), "Guild medal", p.ink, edge, edge);
                } else {
                    label(g, c.x, (int) (c.y + r), m.dungeons.get(i), p.ink, edge, edge);
                    selection(g, new Rectangle((int) (c.x - r), (int) (c.y - r), (int) (2 * r), (int) (2 * r)), i);
                }
            }
        }

        private Path2D hexagon(double cx, double cy, double r) {
            Path2D path = new Path2D.Double();
            for (int k = 0; k < 6; k++) {
                double a = Math.PI / 3 * k;
                if (k == 0) path.moveTo(cx + r * Math.cos(a), cy + r * Math.sin(a));
                else path.lineTo(cx + r * Math.cos(a), cy + r * Math.sin(a));
            }
            path.closePath();
            return path;
        }

        // -- overworld: an island with level dots on a dotted path, and a castle at the end

        private void paintOverworld(Graphics2D g, int w, int h) {
            g.setColor(p.bg);
            g.fillRect(0, 0, w, h);
            g.setStroke(new BasicStroke(4));
            int cloud = (phase / 3) % 60;
            for (int[] c : new int[][]{{w / 4, 18, 110}, {w * 2 / 3, 34, 84}}) {
                g.setColor(Color.WHITE);
                g.fillRoundRect(c[0] + (active ? cloud : 0), c[1], c[2], 32, 22, 22);
                g.setColor(p.ink);
                g.drawRoundRect(c[0] + (active ? cloud : 0), c[1], c[2], 32, 22, 22);
            }
            g.setColor(p.bg2);
            g.fillRoundRect(14, 70, w - 28, h + 60, 160, 160);
            g.setColor(p.ink);
            g.drawRoundRect(14, 70, w - 28, h + 60, 160, 160);
            Random r = new Random(m.id);
            for (int i = 0; i < 4; i++) {   // hills and round trees
                int x = 40 + r.nextInt(Math.max(1, w - 160)), y = 92 + r.nextInt(30);
                g.setColor(p.deco1);
                g.fillArc(x, y, 100, 80, 0, 180);
                g.setColor(p.ink);
                g.drawArc(x, y, 100, 80, 0, 180);
                int tx = 60 + r.nextInt(Math.max(1, w - 120)), ty = h - 70 - r.nextInt(30);
                g.setColor(p.deco2);
                g.fillOval(tx, ty, 28, 28);
                g.setColor(p.ink);
                g.drawOval(tx, ty, 28, 28);
                g.fillRect(tx + 11, ty + 28, 6, 12);
            }
            Point[] pts = points(w, h - 20, false);
            for (int i = 0; i < n; i++) {   // dotted path: walked dots light up
                Point a = pts[i], b = pts[i + 1];
                for (int t = 1; t <= 5; t++) {
                    int x = a.x + (b.x - a.x) * t / 6, y = a.y + (b.y - a.y) * t / 6;
                    g.setColor(i < cleared ? p.walked : p.road);
                    g.fillOval(x - 6, y - 6, 12, 12);
                    g.setColor(p.ink);
                    g.setStroke(new BasicStroke(3));
                    g.drawOval(x - 6, y - 6, 12, 12);
                }
            }
            for (int i = 0; i < n; i++) {
                Point c = pts[i];
                boolean d = done(i), cur = current(i);
                g.setColor(p.ink);
                g.fillOval(c.x - 26, c.y - 21, 52, 52);   // drop shadow
                g.setColor(d ? p.done : cur ? p.current : p.locked);
                g.fillOval(c.x - 26, c.y - 26, 52, 52);
                g.setColor(p.ink);
                g.setStroke(new BasicStroke(5));
                g.drawOval(c.x - 26, c.y - 26, 52, 52);
                g.setFont(new Font(Themes.HEAD_FONT, Font.PLAIN, 28));
                String t = String.valueOf(i + 1);
                g.drawString(t, c.x - g.getFontMetrics().stringWidth(t) / 2, c.y + 9);
                if (d) {   // planted flag
                    g.fillRect(c.x + 16, c.y - 60, 4, 40);
                    g.setColor(p.done);
                    g.fillRect(c.x + 20, c.y - 60, 22, 15);
                    g.setColor(p.ink);
                    g.setStroke(new BasicStroke(3));
                    g.drawRect(c.x + 20, c.y - 60, 22, 15);
                }
                if (cur) {   // a guildie bouncing on the current level
                    int by = c.y - 72 - ((phase / 4) % 2) * 8;
                    g.setColor(p.done);
                    g.fillRect(c.x - 10, by, 20, 8);
                    g.setColor(new Color(0xF8C890));
                    g.fillRect(c.x - 10, by + 8, 20, 14);
                    g.setColor(new Color(0x3A6AE0));
                    g.fillRect(c.x - 12, by + 22, 24, 16);
                    g.setColor(p.ink);
                    g.setStroke(new BasicStroke(3));
                    g.drawRect(c.x - 10, by, 20, 22);
                    g.drawRect(c.x - 12, by + 22, 24, 16);
                }
                label(g, c.x, c.y + 34, m.dungeons.get(i), p.ink, Color.WHITE, p.ink);
                selection(g, new Rectangle(c.x - 30, c.y - 30, 60, 60), i);
            }
            castle(g, pts[n]);
        }

        private void castle(Graphics2D g, Point c) {
            boolean won = cleared == n;
            int x = c.x - 45, y = c.y - 50;
            g.setColor(new Color(0xC8C8C8));
            g.fillRect(x, y + 20, 90, 70);
            for (int k = 0; k < 4; k++) g.fillRect(x + k * 26, y + 6, 14, 16);
            g.setColor(p.ink);
            g.setStroke(new BasicStroke(4));
            g.drawRect(x, y + 20, 90, 70);
            g.fillRoundRect(x + 28, y + 52, 34, 40, 30, 30);
            g.fillRect(x + 43, y - 30, 4, 36);
            g.setColor(won ? p.current : p.locked);
            g.fillRect(x + 47, y - 30, 26, 18);
            g.setFont(new Font(Themes.HEAD_FONT, Font.PLAIN, 26));
            g.drawString("?", x + 39, y + 82);
            label(g, c.x, y + 96, "Medal · soon", Color.WHITE, p.ink, p.ink);
        }

        // -- Oryx's tower: a floor per stage, climbed from the bottom, his throne on top

        private void paintOryx(Graphics2D g, int w, int h) {
            g.setColor(p.bg);
            g.fillRect(0, 0, w, h);
            g.setColor(p.deco1);
            g.fillOval(40, 30, 60, 60);
            g.setColor(p.deco2);
            g.fillPolygon(new int[]{0, w / 8, w / 5, w / 3, w / 3, 0}, new int[]{h - 200, h - 260, h - 210, h - 280, h, h}, 6);
            g.fillPolygon(new int[]{w, w * 7 / 8, w * 4 / 5, w * 2 / 3, w * 2 / 3, w}, new int[]{h - 180, h - 250, h - 200, h - 270, h, h}, 6);
            int tw = Math.min(w - 40, 640), tx = (w - tw) / 2, floorH = 58, throneH = 130, top = 12;
            // throne room
            bricks(g, tx, top, tw, throneH, p.bg2);
            g.setColor(p.deco3);
            for (int bx : new int[]{tx + tw / 6, tx + tw * 5 / 6 - 34}) {
                g.fillPolygon(new int[]{bx, bx + 34, bx + 34, bx + 17, bx}, new int[]{top + 30, top + 30, top + 110, top + 96, top + 110}, 5);
            }
            g.setColor(p.frame);
            g.fillRect(tx + tw / 2 - 46, top + 26, 92, 100);
            boolean won = cleared == n;
            g.setColor(won ? p.done : p.locked);
            g.fillOval(tx + tw / 2 - 26, top + 50, 52, 52);
            g.setColor(p.frame);
            g.setFont(new Font(Themes.HEAD_FONT, Font.PLAIN, 32));
            g.drawString("?", tx + tw / 2 - 7, top + 86);
            hits.set(n, new Rectangle(tx, top, tw, throneH));
            label(g, tx + tw / 2, top + throneH - 24, "THRONE · guild medal soon", p.text, p.frame, p.frame, tw);
            // floors, stage 1 at the bottom
            for (int i = 0; i < n; i++) {
                int fy = top + throneH + (n - 1 - i) * floorH;
                boolean d = done(i), cur = current(i);
                bricks(g, tx, fy, tw, floorH, d ? p.bg2.brighter() : cur ? p.bg2 : p.bg2.darker());
                g.setColor(p.frame);
                g.fillRect(tx + 10, fy + 8, 56, floorH - 16);
                g.setColor(d ? p.done : cur ? p.current : p.locked);
                g.setFont(new Font(Themes.HEAD_FONT, Font.PLAIN, 34));
                String num = roman(i + 1);
                g.drawString(num, tx + 38 - g.getFontMetrics().stringWidth(num) / 2, fy + floorH / 2 + 11);
                g.setColor(new Color(0, 0, 0, 170));
                g.fillRect(tx + 76, fy + 8, tw - 76 - 120, floorH - 16);
                g.setFont(new Font(Themes.BODY_FONT, Font.PLAIN, 22));
                g.setColor(d || cur ? p.text : p.locked);
                g.drawString(m.dungeons.get(i), tx + 86, fy + 30);
                g.setFont(new Font(Themes.BODY_FONT, Font.PLAIN, 15));
                g.drawString(d ? "cleared by " + m.clears.get(i).ign : cur ? "any guildie can clear it" : "locked", tx + 86, fy + 46);
                // the gate
                int gw = 40, gh = floorH - 10, gx = tx + tw - 84, gy = fy + floorH - gh;   // an arched gate on the floor
                if (d || cur) {   // torches
                    boolean flick = (phase / 3 + i) % 2 == 0;
                    g.setColor(new Color(0xF0A030));
                    g.fillOval(gx - 16, gy + (flick ? 2 : 4), 9, flick ? 15 : 12);
                    g.fillOval(gx + gw + 7, gy + (flick ? 4 : 2), 9, flick ? 12 : 15);
                }
                if (cur) glow(g, new Point(gx + gw / 2, gy + gh / 2), gh, p.current);
                Shape gate = new java.awt.geom.Area(new java.awt.geom.Ellipse2D.Float(gx, gy, gw, gw));
                ((java.awt.geom.Area) gate).add(new java.awt.geom.Area(new Rectangle(gx, gy + gw / 2, gw, gh - gw / 2)));
                g.setColor(cur ? p.current.darker() : p.frame);
                g.fill(gate);
                if (!d && !cur) {   // portcullis
                    g.setColor(p.locked);
                    for (int b = gx + 5; b < gx + gw - 3; b += 8) g.fillRect(b, gy + 6, 3, gh - 6);
                }
                if (d) check(g, gx + gw + 4, gy + gh - 22);
                Rectangle floor = new Rectangle(tx, fy, tw, floorH);
                hits.set(i, floor);
                if (selected == i) {
                    g.setColor(p.current);
                    g.setStroke(new BasicStroke(3));
                    g.drawRect(floor.x + 2, floor.y + 2, floor.width - 4, floor.height - 4);
                }
            }
            g.setColor(p.frame);
            int baseY = top + throneH + n * floorH;
            g.fillRect(tx, baseY, tw, h - baseY);
            g.setColor(p.locked);
            g.setFont(new Font(Themes.BODY_FONT, Font.PLAIN, 15));
            String ent = "ENTRANCE · CLIMB FROM HERE";
            g.drawString(ent, tx + (tw - g.getFontMetrics().stringWidth(ent)) / 2, baseY + 20);
        }

        private void bricks(Graphics2D g, int x, int y, int w, int h, Color fill) {
            g.setColor(fill);
            g.fillRect(x, y, w, h);
            g.setColor(p.frame);
            for (int by = y; by < y + h; by += 22) {
                g.fillRect(x, by, w, 3);
                int off = ((by - y) / 22) % 2 == 0 ? 0 : 22;
                for (int bx = x + off; bx < x + w; bx += 44) g.fillRect(bx, by, 3, Math.min(22, y + h - by));
            }
            g.setStroke(new BasicStroke(4));
            g.drawRect(x, y, w, h);
        }

        private String roman(int k) {
            return new String[]{"I", "II", "III", "IV", "V", "VI", "VII", "VIII"}[Math.min(7, k - 1)];
        }

        // -- constellation: stars that join into a figure as stages are cleared

        private void paintConstellation(Graphics2D g, int w, int h) {
            g.setColor(p.bg);
            g.fillRect(0, 0, w, h);
            g.setColor(new Color(p.deco1.getRed(), p.deco1.getGreen(), p.deco1.getBlue(), 40));
            g.fillOval(w / 6, h / 5, w * 2 / 3, h * 3 / 5);
            Random r = new Random(m.id);
            for (int i = 0; i < 90; i++) {   // star field
                int x = r.nextInt(Math.max(1, w)), y = r.nextInt(h), s = i % 6 == 0 ? 3 : 2;
                boolean dim = active && (phase / 6 + i) % 9 == 0;
                g.setColor(dim ? p.deco3.darker() : i % 3 == 0 ? p.deco2 : p.deco3);
                g.fillRect(x, y, s, s);
            }
            g.setFont(new Font(Themes.HEAD_FONT, Font.PLAIN, 30));
            g.setColor(p.bg2);
            g.drawString(m.name.toUpperCase(Locale.ROOT), 18, 36);
            Point[] pts = points(w, h - 30, true);
            for (int i = 0; i < n; i++) {   // lines only between cleared stars; the next one dashed
                Point a = pts[i], b = pts[i + 1];
                if (i + 1 <= cleared - 1 || (i < cleared && i + 1 == n && cleared == n)) {
                    g.setColor(p.walked);
                    g.setStroke(new BasicStroke(3));
                    g.drawLine(a.x, a.y, b.x, b.y);
                } else if (active && i == cleared - 1) {
                    g.setColor(p.road);
                    g.setStroke(new BasicStroke(2, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1, new float[]{6, 6}, march(12, 1)));
                    g.drawLine(a.x, a.y, b.x, b.y);
                }
            }
            for (int i = 0; i < n; i++) {
                Point c = pts[i];
                boolean d = done(i), cur = current(i);
                int size = d ? 40 : cur ? 46 + (int) (6 * Math.sin(phase * 0.25)) : 18;
                if (d || cur) glow(g, c, size, d ? p.done : p.current);
                g.setColor(d ? p.done : cur ? p.current : p.locked);
                g.fill(star(c.x, c.y, size / 2.0, 4));
                Color cap = d ? p.done : cur ? p.current : p.locked;
                g.setFont(new Font(Themes.BODY_FONT, Font.PLAIN, 18));
                String t = (i + 1) + " · " + m.dungeons.get(i);
                FontMetrics fm = g.getFontMetrics();
                t = fit(t, fm, (w - 160) / Math.max(1, n));
                g.setColor(cap);
                g.drawString(t, c.x - fm.stringWidth(t) / 2, c.y + 40);
                selection(g, new Rectangle(c.x - 28, c.y - 28, 56, 56), i);
            }
            Point mc = pts[n];
            boolean won = cleared == n;
            g.setColor(won ? p.done : p.bg2);
            g.fill(star(mc.x, mc.y, 44, 8));
            g.setColor(won ? p.ink : p.locked);
            g.setFont(new Font(Themes.HEAD_FONT, Font.PLAIN, 28));
            g.drawString("?", mc.x - 6, mc.y + 9);
        }

        private Shape star(double cx, double cy, double r, int points) {
            Path2D path = new Path2D.Double();
            for (int k = 0; k < points * 2; k++) {
                double a = Math.PI / points * k - Math.PI / 2, rr = k % 2 == 0 ? r : r * 0.32;
                if (k == 0) path.moveTo(cx + rr * Math.cos(a), cy + rr * Math.sin(a));
                else path.lineTo(cx + rr * Math.cos(a), cy + rr * Math.sin(a));
            }
            path.closePath();
            return path;
        }

        // -- neon grid: a light trail with right-angle turns across a glowing perspective grid

        private void paintNeon(Graphics2D g, int w, int h) {
            g.setColor(p.bg);
            g.fillRect(0, 0, w, h);
            int horizon = (int) (h * 0.2), vx = w / 2;
            g.setColor(p.bg2);
            g.fillRect(0, horizon, w, h - horizon);
            g.setStroke(new BasicStroke(1));
            g.setColor(p.deco1);
            int scroll = active ? phase % 12 : 0;
            for (int k = 0; k <= 12; k++) {   // horizontal lines, closer together towards the horizon; they roll forward
                double t = (k + scroll / 12.0) / 12.0;
                int y = horizon + (int) ((h - horizon) * t * t);
                g.drawLine(0, y, w, y);
            }
            for (int k = -16; k <= 16; k++) g.drawLine(vx, horizon, vx + k * w / 8, h);
            neonLine(g, 0, horizon, w, horizon, p.deco3, 2);
            Point[] pts = points(w, h - 30, false);
            for (int i = 0; i < n; i++) {   // the trail: across, then up or down, like a light cycle
                Point a = pts[i], b = pts[i + 1];
                boolean walked = i < cleared;
                if (walked) {
                    neonLine(g, a.x, a.y, b.x, a.y, p.walked, 4);
                    neonLine(g, b.x, a.y, b.x, b.y, p.walked, 4);
                } else {
                    boolean next = active && i == cleared;
                    g.setColor(p.road);
                    g.setStroke(new BasicStroke(3, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1, new float[]{10, 8}, next ? march(18, 2) : 0));
                    g.drawPolyline(new int[]{a.x, b.x, b.x}, new int[]{a.y, a.y, b.y}, 3);
                }
            }
            for (int i = 0; i < n; i++) {
                Point c = pts[i];
                boolean d = done(i), cur = current(i);
                Color col = d ? p.done : cur ? p.current : p.locked;
                if (cur) glow(g, c, 46, p.walked);
                Polygon diamond = new Polygon(new int[]{c.x, c.x + 26, c.x, c.x - 26}, new int[]{c.y - 26, c.y, c.y + 26, c.y}, 4);
                g.setColor(p.bg);
                g.fill(diamond);
                if (d || cur) {
                    g.setColor(new Color(col.getRed(), col.getGreen(), col.getBlue(), 70));
                    g.setStroke(new BasicStroke(9));
                    g.draw(diamond);
                }
                g.setColor(col);
                g.setStroke(new BasicStroke(3));
                g.draw(diamond);
                g.setFont(new Font(Themes.HEAD_FONT, Font.PLAIN, 24));
                String t = String.valueOf(i + 1);
                g.drawString(t, c.x - g.getFontMetrics().stringWidth(t) / 2, c.y + 8);
                label(g, c.x, c.y + 34, m.dungeons.get(i).toUpperCase(Locale.ROOT), col, p.bg, d || cur ? col : p.road);
                selection(g, new Rectangle(c.x - 30, c.y - 30, 60, 60), i);
            }
            Point mc = pts[n];   // the medal: a glowing disc
            boolean won = cleared == n;
            Color disc = won ? p.done : p.locked;
            if (won) glow(g, mc, 56, p.done);
            g.setColor(p.bg);
            g.fillOval(mc.x - 28, mc.y - 28, 56, 56);
            g.setColor(disc);
            g.setStroke(new BasicStroke(4));
            g.drawOval(mc.x - 28, mc.y - 28, 56, 56);
            g.drawOval(mc.x - 14, mc.y - 14, 28, 28);
            g.setFont(new Font(Themes.HEAD_FONT, Font.PLAIN, 22));
            g.drawString("?", mc.x - 5, mc.y + 8);
            label(g, mc.x, mc.y + 36, "MEDAL · SOON", disc, p.bg, disc);
        }

        /** A line with a soft glow under it. */
        private void neonLine(Graphics2D g, int x1, int y1, int x2, int y2, Color c, float width) {
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 60));
            g.setStroke(new BasicStroke(width * 3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(x1, y1, x2, y2);
            g.setColor(c);
            g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(x1, y1, x2, y2);
        }

        // -- hero's overworld: a top-down tile map, cave entrances, and fog over the land not yet explored

        private void paintAdventure(Graphics2D g, int w, int h) {
            int t = 30, cols = Math.max(n + 7, w / t + 1), rows = Math.max(6, h / t + 1);   // room for every stage
            Random r = new Random(m.id);
            int[] sc = new int[n + 1], sr = new int[n + 1];
            for (int i = 0; i <= n; i++) {
                sc[i] = 2 + (int) Math.round(i * (cols - 6.0) / n);
                sr[i] = i % 2 == 0 ? (int) (rows * 0.62) : (int) (rows * 0.3);
            }
            int[][] path = new int[cols][rows];   // 0 land, 1 walked path, 2 path ahead
            for (int i = 0; i < n; i++) {
                int kind = i < cleared ? 1 : 2;
                for (int c = Math.min(sc[i], sc[i + 1]); c <= Math.max(sc[i], sc[i + 1]); c++) path[c][sr[i]] = kind;
                for (int row = Math.min(sr[i], sr[i + 1]); row <= Math.max(sr[i], sr[i + 1]); row++) path[sc[i + 1]][row] = kind;
            }
            for (int c = 0; c < cols; c++) {
                for (int row = 0; row < rows; row++) {
                    int x = c * t, y = row * t;
                    g.setColor(p.bg);
                    g.fillRect(x, y, t, t);
                    if (path[c][row] > 0) {
                        g.setColor(path[c][row] == 1 ? p.walked : p.road);
                        g.fillRect(x, y, t, t);
                        continue;
                    }
                    int roll = r.nextInt(100);
                    boolean nearStage = false;
                    for (int i = 0; i <= n; i++) nearStage |= Math.abs(c - sc[i]) <= 1 && Math.abs(row - sr[i]) <= 1;
                    if (nearStage) continue;
                    if (roll < 14) {   // tree
                        g.setColor(p.deco1);
                        g.fillOval(x + 3, y + 2, t - 6, t - 8);
                        g.setColor(p.ink);
                        g.setStroke(new BasicStroke(2));
                        g.drawOval(x + 3, y + 2, t - 6, t - 8);
                        g.fillRect(x + t / 2 - 2, y + t - 7, 4, 6);
                    } else if (roll < 20) {   // mountain
                        g.setColor(p.deco2);
                        g.fillPolygon(new int[]{x, x + t / 2, x + t}, new int[]{y + t, y + 2, y + t}, 3);
                        g.setColor(p.ink);
                        g.setStroke(new BasicStroke(2));
                        g.drawPolygon(new int[]{x, x + t / 2, x + t}, new int[]{y + t, y + 2, y + t}, 3);
                    } else if (roll < 24) {   // pond
                        g.setColor(p.deco3);
                        g.fillRect(x + 1, y + 1, t - 2, t - 2);
                    }
                }
            }
            if (active || cleared < n) {   // fog over what is not explored yet
                int edge = (sc[Math.min(cleared, n)] + 2) * t;
                g.setColor(new Color(0, 0, 0, 120));
                g.fillRect(edge, 0, w - edge, h);
            }
            for (int i = 0; i < n; i++) {
                Point c = new Point(sc[i] * t + t / 2, sr[i] * t + t / 2);
                boolean d = done(i), cur = current(i);
                int x = c.x - 26, y = c.y - 24;
                g.setColor(p.deco2);   // a rock face with a cave entrance
                g.fillRoundRect(x, y, 52, 44, 14, 14);
                g.setColor(p.ink);
                g.setStroke(new BasicStroke(3));
                g.drawRoundRect(x, y, 52, 44, 14, 14);
                g.fillArc(x + 13, y + 14, 26, 36, 0, 180);
                g.fillRect(x + 13, y + 31, 26, 13);
                g.setColor(d ? p.done : cur ? p.current : p.locked);
                g.setFont(new Font(Themes.HEAD_FONT, Font.PLAIN, 20));
                String num = String.valueOf(i + 1);
                g.drawString(num, c.x - g.getFontMetrics().stringWidth(num) / 2, y + 40);
                if (d) check(g, x + 40, y - 8);
                if (cur && (phase / 5) % 4 != 3) {   // the hero, blinking now and then
                    int hy = y - 30 - ((phase / 4) % 2) * 3;
                    g.setColor(p.current);
                    g.fillPolygon(new int[]{c.x - 9, c.x + 9, c.x + 3}, new int[]{hy + 8, hy + 8, hy - 4}, 3);
                    g.setColor(new Color(0xF8C890));
                    g.fillRect(c.x - 7, hy + 8, 14, 10);
                    g.setColor(p.current.darker());
                    g.fillRect(c.x - 8, hy + 18, 16, 12);
                    g.setColor(p.ink);
                    g.setStroke(new BasicStroke(2));
                    g.drawRect(c.x - 8, hy + 8, 16, 22);
                }
                label(g, c.x, y + 50, m.dungeons.get(i), p.text, p.ink, p.ink);
                selection(g, new Rectangle(x - 4, y - 4, 60, 52), i);
            }
            Point ch = new Point(sc[n] * t + t / 2, sr[n] * t + t / 2);   // the medal: a treasure chest
            boolean won = cleared == n;
            g.setColor(new Color(0x8A5A28));
            g.fillRect(ch.x - 24, ch.y - 14, 48, 30);
            g.fillArc(ch.x - 24, ch.y - 28, 48, 28, 0, 180);
            g.setColor(won ? p.done : p.locked);
            g.fillRect(ch.x - 24, ch.y - 6, 48, 6);
            g.fillRect(ch.x - 5, ch.y - 10, 10, 14);
            g.setColor(p.ink);
            g.setStroke(new BasicStroke(3));
            g.drawRect(ch.x - 24, ch.y - 14, 48, 30);
            g.drawArc(ch.x - 24, ch.y - 28, 48, 28, 0, 180);
            label(g, ch.x, ch.y + 22, "Medal · soon", p.text, p.ink, p.ink);
        }

        // -- hunting grounds: numbered zones on a survey map, a camp at the start, the apex monster at the end

        private void paintHunt(Graphics2D g, int w, int h) {
            g.setColor(p.bg);
            g.fillRect(0, 0, w, h);
            Random r = new Random(m.id);
            Color[] regions = {p.deco1, p.deco2, p.deco3};
            for (int k = 0; k < 7; k++) {   // terrain regions
                Color c = regions[k % 3];
                g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 120));
                g.fillOval(r.nextInt(Math.max(1, w)) - 120, r.nextInt(h) - 80, 220 + r.nextInt(200), 140 + r.nextInt(120));
            }
            g.setColor(p.frame);   // a wooden frame
            g.setStroke(new BasicStroke(12));
            g.drawRect(6, 6, w - 12, h - 12);
            g.setColor(p.ink);
            g.setStroke(new BasicStroke(2));
            g.drawRect(14, 14, w - 28, h - 28);
            Point[] pts = points(w, h - 30, true);
            // camp before zone 1
            Point camp = new Point(Math.max(34, pts[0].x - 50), pts[0].y - 40);
            g.setColor(new Color(0xD8C8A0));
            g.fillPolygon(new int[]{camp.x - 16, camp.x, camp.x + 16}, new int[]{camp.y + 12, camp.y - 14, camp.y + 12}, 3);
            g.setColor(p.ink);
            g.drawPolygon(new int[]{camp.x - 16, camp.x, camp.x + 16}, new int[]{camp.y + 12, camp.y - 14, camp.y + 12}, 3);
            g.drawLine(camp.x, camp.y - 14, camp.x, camp.y + 12);
            for (int i = 0; i < n; i++) {   // tracks between zones
                Point a = pts[i], b = pts[i + 1];
                boolean walked = i < cleared;
                double len = a.distance(b);
                for (double s = 44; s < len - 44; s += 16) {
                    int x = (int) (a.x + (b.x - a.x) * s / len), y = (int) (a.y + (b.y - a.y) * s / len);
                    g.setColor(walked ? p.walked : new Color(p.road.getRed(), p.road.getGreen(), p.road.getBlue(), 160));
                    g.fillOval(x - 3, y - 3, 6, 6);
                }
            }
            for (int i = 0; i < n; i++) {
                Point c = pts[i];
                boolean d = done(i), cur = current(i);
                Polygon zone = new Polygon();
                for (int k = 0; k < 7; k++) {   // an uneven zone outline
                    double a = Math.PI * 2 * k / 7, rr = 38 + ((m.id + i * 7 + k * 13) % 9);
                    zone.addPoint((int) (c.x + rr * Math.cos(a)), (int) (c.y + rr * Math.sin(a)));
                }
                if (cur) glow(g, c, 70, p.current);
                g.setColor(d || cur ? p.bg2 : new Color(p.bg2.getRed(), p.bg2.getGreen(), p.bg2.getBlue(), 150));
                g.fill(zone);
                g.setColor(cur ? p.current : p.ink);
                g.setStroke(new BasicStroke(cur ? 4 : 2));
                g.draw(zone);
                g.setColor(d || cur ? p.ink : p.locked);
                g.setFont(new Font(Themes.HEAD_FONT, Font.PLAIN, 34));
                String num = String.valueOf(i + 1);
                g.drawString(num, c.x - g.getFontMetrics().stringWidth(num) / 2, c.y + 11);
                if (cur) {   // claw marks: being hunted
                    g.setColor(p.current);
                    g.setStroke(new BasicStroke(4, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    for (int k = -1; k <= 1; k++) g.drawLine(c.x + 20 + k * 8, c.y - 34, c.x + 32 + k * 8, c.y - 14);
                }
                if (d) stamp(g, new Point(c.x, c.y + 22));   // under the number
                label(g, c.x, c.y + 44, m.dungeons.get(i), p.text, p.bg2, p.ink);
                selection(g, new Rectangle(c.x - 42, c.y - 42, 84, 84), i);
            }
            Point ap = pts[n];   // the apex monster's skull
            boolean won = cleared == n;
            g.setColor(won ? p.done : p.locked);
            g.fillPolygon(new int[]{ap.x - 22, ap.x - 34, ap.x - 12}, new int[]{ap.y - 10, ap.y - 40, ap.y - 18}, 3);
            g.fillPolygon(new int[]{ap.x + 22, ap.x + 34, ap.x + 12}, new int[]{ap.y - 10, ap.y - 40, ap.y - 18}, 3);
            g.setColor(p.bg2);
            g.fillOval(ap.x - 24, ap.y - 24, 48, 44);
            g.setColor(p.ink);
            g.setStroke(new BasicStroke(3));
            g.drawOval(ap.x - 24, ap.y - 24, 48, 44);
            g.fillOval(ap.x - 14, ap.y - 8, 10, 10);
            g.fillOval(ap.x + 4, ap.y - 8, 10, 10);
            g.setFont(new Font(Themes.HEAD_FONT, Font.PLAIN, 18));
            g.drawString("?", ap.x - 4, ap.y + 16);
            label(g, ap.x, ap.y + 26, "Apex · medal soon", p.text, p.bg2, p.ink);
        }

        /** A red "CLEARED" stamp, a little tilted. */
        private void stamp(Graphics2D g, Point c) {
            Graphics2D s = (Graphics2D) g.create();
            s.rotate(-0.25, c.x, c.y);
            s.setColor(new Color(p.done.getRed(), p.done.getGreen(), p.done.getBlue(), 210));
            s.setStroke(new BasicStroke(3));
            s.drawRoundRect(c.x - 40, c.y - 12, 80, 24, 8, 8);
            s.setFont(new Font(Themes.HEAD_FONT, Font.PLAIN, 20));
            String t = "CLEARED";
            s.drawString(t, c.x - s.getFontMetrics().stringWidth(t) / 2, c.y + 7);
            s.dispose();
        }

        // -- hellforge: rooms of a burning facility joined by corridors, with status lights and hazard doors

        private void paintHellforge(Graphics2D g, int w, int h) {
            g.setColor(p.bg);
            g.fillRect(0, 0, w, h);
            g.setColor(p.deco1);   // metal plates with rivets
            g.setStroke(new BasicStroke(1));
            for (int x = 0; x < w; x += 64) g.drawLine(x, 0, x, h);
            for (int y = 0; y < h; y += 48) g.drawLine(0, y, w, y);
            for (int x = 6; x < w; x += 64) for (int y = 6; y < h; y += 48) g.fillOval(x, y, 4, 4);
            for (int k = 0; k < 4; k++) {   // lava glow from below
                Color c = p.deco2;
                g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 22 + k * 8));
                g.fillRect(0, h - 70 + k * 16, w, 70 - k * 16);
            }
            hazard(g, 0, 0, w, 12);
            hazard(g, 0, h - 12, w, 12);
            Point[] pts = points(w, h - 40, false);
            for (int i = 0; i < n; i++) {   // corridors
                Point a = pts[i], b = pts[i + 1];
                int mx = (a.x + b.x) / 2;
                int[] xs = {a.x, mx, mx, b.x}, ys = {a.y, a.y, b.y, b.y};
                g.setColor(p.road);
                g.setStroke(new BasicStroke(16, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
                g.drawPolyline(xs, ys, 4);
                g.setColor(p.frame);
                g.setStroke(new BasicStroke(2));
                if (i < cleared) {
                    g.setColor(p.walked);
                    g.setStroke(new BasicStroke(3));
                    g.drawPolyline(xs, ys, 4);
                }
            }
            for (int i = 0; i < n; i++) {
                Point c = pts[i];
                boolean d = done(i), cur = current(i);
                int rw = 92, rh = 62, x = c.x - rw / 2, y = c.y - rh / 2;
                g.setColor(p.bg2);
                g.fillRect(x, y, rw, rh);
                g.setColor(p.frame);
                g.setStroke(new BasicStroke(5));
                g.drawRect(x, y, rw, rh);
                g.setColor(p.text);
                g.setFont(new Font(Themes.BODY_FONT, Font.PLAIN, 14));
                g.drawString("SECTOR " + (i + 1), x + 8, y + 18);
                g.setFont(new Font(Themes.HEAD_FONT, Font.PLAIN, 28));
                String num = String.format("%02d", i + 1);
                g.drawString(num, x + 8, y + rh - 10);
                boolean blink = !cur || (phase / 4) % 2 == 0;   // status light
                g.setColor(d ? p.done : cur ? (blink ? p.current : p.bg2) : p.locked);
                g.fillOval(x + rw - 22, y + 8, 14, 14);
                if (cur && blink) glow(g, new Point(x + rw - 15, y + 15), 14, p.current);
                if (!d && !cur) hazard(g, x + rw - 36, y + rh - 18, 30, 12);   // a locked door
                label(g, c.x, y + rh + 8, m.dungeons.get(i), p.text, p.bg, d ? p.done : cur ? p.current : p.frame);
                selection(g, new Rectangle(x - 4, y - 4, rw + 8, rh + 8), i);
            }
            Point ex = pts[n];   // the exit room holds the medal
            boolean won = cleared == n;
            g.setColor(p.bg2);
            g.fillRect(ex.x - 40, ex.y - 36, 80, 72);
            g.setColor(won ? p.done : p.deco3);
            g.setStroke(new BasicStroke(5));
            g.drawRect(ex.x - 40, ex.y - 36, 80, 72);
            g.setFont(new Font(Themes.HEAD_FONT, Font.PLAIN, 36));
            g.drawString("?", ex.x - 8, ex.y + 12);
            label(g, ex.x, ex.y + 44, "EXIT · medal soon", p.text, p.bg, won ? p.done : p.deco3);
            g.setFont(new Font(Themes.HEAD_FONT, Font.PLAIN, 26));   // HUD counter
            g.setColor(p.text);
            g.drawString("SECTORS " + cleared + "/" + n, 22, h - 22);
        }

        /** Yellow and black warning stripes. */
        private void hazard(Graphics2D g, int x, int y, int w, int h) {
            Graphics2D s = (Graphics2D) g.create();
            s.clipRect(x, y, w, h);
            s.setColor(new Color(0x1A1A1A));
            s.fillRect(x, y, w, h);
            s.setColor(p.locked);
            for (int k = x - h; k < x + w + h; k += 16) s.fillPolygon(new int[]{k, k + 8, k + 8 + h, k + h}, new int[]{y + h, y + h, y, y}, 4);
            s.dispose();
        }

        // -- shared pieces

        private void medal(Graphics2D g, Point c, Color ink) {
            boolean won = cleared == n;
            g.setColor(new Color(0xB8364A));
            g.fillPolygon(new int[]{c.x - 16, c.x - 4, c.x - 10, c.x - 22}, new int[]{c.y - 50, c.y - 50, c.y - 20, c.y - 20}, 4);
            g.setColor(new Color(0x2D7AB0));
            g.fillPolygon(new int[]{c.x + 4, c.x + 16, c.x + 22, c.x + 10}, new int[]{c.y - 50, c.y - 50, c.y - 20, c.y - 20}, 4);
            g.setColor(won ? p.done : p.bg2);
            g.fillOval(c.x - 28, c.y - 26, 56, 56);
            g.setColor(ink);
            g.setStroke(new BasicStroke(4));
            g.drawOval(c.x - 28, c.y - 26, 56, 56);
            g.setFont(new Font(Themes.HEAD_FONT, Font.PLAIN, 30));
            g.drawString("?", c.x - 6, c.y + 12);
            label(g, c.x, c.y + 38, "Guild medal · soon", p.text, p.bg, ink);
        }

        private void glow(Graphics2D g, Point c, int size, Color color) {
            double pulse = 0.5 + 0.5 * Math.sin(phase * 0.2);
            for (int k = 3; k >= 1; k--) {
                int pad = (int) (k * (6 + 4 * pulse));
                g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 40));
                g.fillOval(c.x - size / 2 - pad, c.y - size / 2 - pad, size + 2 * pad, size + 2 * pad);
            }
        }

        private void badge(Graphics2D g, int x, int y, String text, Color bg, Color fg) {
            g.setColor(bg);
            g.fillRect(x, y, 24, 24);
            g.setColor(fg);
            g.setFont(new Font(Themes.BODY_FONT, Font.PLAIN, 18));
            g.drawString(text, x + 12 - g.getFontMetrics().stringWidth(text) / 2, y + 18);
        }

        private void check(Graphics2D g, int x, int y) {
            g.setColor(new Color(0x3C9347));
            g.fillRect(x - 2, y - 2, 24, 24);
            g.setColor(p.ink);
            g.setStroke(new BasicStroke(2));
            g.drawRect(x - 2, y - 2, 24, 24);
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(3));
            g.drawPolyline(new int[]{x + 3, x + 8, x + 16}, new int[]{y + 10, y + 15, y + 5}, 3);
        }

        private void padlock(Graphics2D g, int x, int y) {
            g.setColor(p.locked);
            g.fillRect(x - 2, y + 4, 20, 16);
            g.setColor(p.ink);
            g.setStroke(new BasicStroke(3));
            g.drawRect(x - 2, y + 4, 20, 16);
            g.drawArc(x + 2, y - 6, 12, 18, 0, 180);
        }

        /** A name pill centered under a stage, shortened to fit between neighbours. */
        private void label(Graphics2D g, int cx, int y, String text, Color fg, Color bg, Color border) {
            label(g, cx, y, text, fg, bg, border, Math.max(70, (getWidth() - 160) / Math.max(1, n) - 4));
        }

        private void label(Graphics2D g, int cx, int y, String text, Color fg, Color bg, Color border, int max) {
            g.setFont(new Font(Themes.BODY_FONT, Font.PLAIN, 18));
            FontMetrics fm = g.getFontMetrics();
            text = fit(text, fm, max);
            int tw = fm.stringWidth(text) + 14, x = cx - tw / 2;
            g.setColor(bg);
            g.fillRect(x, y, tw, 24);
            g.setColor(border);
            g.setStroke(new BasicStroke(2));
            g.drawRect(x, y, tw, 24);
            g.setColor(fg);
            g.drawString(text, x + 7, y + 18);
        }

        private String fit(String text, FontMetrics fm, int max) {
            if (fm.stringWidth(text) <= max) return text;
            while (text.length() > 3 && fm.stringWidth(text + "..") > max) text = text.substring(0, text.length() - 1);
            return text + "..";
        }

        private void selection(Graphics2D g, Rectangle r, int i) {
            hits.set(i, r);
            if (selected != i) return;
            g.setColor(p.text);
            g.setStroke(new BasicStroke(2, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1, new float[]{5, 4}, 0));
            g.drawRect(r.x - 4, r.y - 4, r.width + 8, r.height + 8);
        }

        private void compass(Graphics2D g, int w, int h, Color c) {
            g.setColor(c);
            g.setStroke(new BasicStroke(3));
            g.drawRect(w - 70, h - 70, 50, 50);
            g.setFont(new Font(Themes.BODY_FONT, Font.PLAIN, 20));
            g.drawString("N", w - 50, h - 38);
        }
    }
}
