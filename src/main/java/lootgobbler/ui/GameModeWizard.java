package lootgobbler.ui;

import lootgobbler.CharacterStore;
import lootgobbler.CharacterStore.Char;
import lootgobbler.Upe;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.IntConsumer;

/**
 * Starts a game mode run on a new character, step by step: pick the mode (PPE or a UPE variant; the guild version;
 * without a pet), then create the character in-game as the mode asks (sniffer running, upgrades disabled, no pet) while
 * the wizard waits for it, then the run starts by itself. A character created the wrong way is named, and the wizard
 * keeps waiting for the next one.
 */
final class GameModeWizard extends JDialog {
    private final IntConsumer openCharacter;
    private final CardLayout pages = new CardLayout();
    private final JPanel body = new JPanel(pages);
    private String category = Upe.UPE, variant = Upe.UPE;
    private boolean guild, noPet;

    // step 2
    private final JPanel checklist = column();
    private final JLabel snifferState = new JLabel();
    private final JLabel waiting = new JLabel();
    private final JLabel problem = new JLabel();
    private Set<Integer> before = new HashSet<>();
    private final Set<Integer> told = new HashSet<>();
    private boolean watching;
    private final Timer tick;

    // step 3
    private final JPanel done = column();

    private GameModeWizard(Window owner, IntConsumer openCharacter) {
        super(owner, "New game mode run", ModalityType.MODELESS);
        this.openCharacter = openCharacter;
        body.setBorder(new EmptyBorder(18, 22, 18, 22));
        setContentPane(body);
        renderChoose();
        body.add(new JPanel(), "create");
        body.add(done, "done");
        Runnable check = () -> SwingUtilities.invokeLater(this::lookForNewCharacter);
        Upe.get().onChange(check);
        CharacterStore.get().onChange(check);
        tick = new Timer(700, e -> animate());
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                watching = false;
                tick.stop();
            }
        });
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(860, 800);
        setLocationRelativeTo(owner);
    }

    /** Opens the wizard over this component's window. */
    static void open(Component over, IntConsumer openCharacter) {
        new GameModeWizard(SwingUtilities.getWindowAncestor(over), openCharacter).setVisible(true);
    }

    private String mode() {
        return Upe.withGuild(Upe.UPE.equals(category) ? variant : category, guild);
    }

    // ---- step 1: the mode ---------------------------------------------------------------------------------------

    private JPanel choose;

    private void renderChoose() {
        if (choose != null) body.remove(choose);
        choose = column();
        choose.add(left(stepLine(1)));
        choose.add(left(title("Choose your challenge")));
        choose.add(Box.createVerticalStrut(14));
        JPanel cats = new JPanel(new GridLayout(1, Upe.CATEGORY_INFO.size(), 10, 0));
        cats.setOpaque(false);
        for (Upe.ModeInfo m : Upe.CATEGORY_INFO) cats.add(categoryCard(m));
        cats.setAlignmentX(LEFT_ALIGNMENT);
        cats.setMaximumSize(new Dimension(Integer.MAX_VALUE, cats.getPreferredSize().height));
        choose.add(cats);
        if (Upe.UPE.equals(category)) {
            choose.add(Box.createVerticalStrut(16));
            JLabel vh = heading("Variant");
            vh.setFont(vh.getFont().deriveFont(18f));
            choose.add(left(vh));
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
            row.setOpaque(false);
            ButtonGroup g = new ButtonGroup();
            for (String v : Upe.UPE_VARIANTS) {
                JToggleButton b = new JToggleButton(Upe.label(v), v.equals(variant));
                b.setFont(b.getFont().deriveFont(24f));
                b.setToolTipText(Upe.info(v).summary());
                b.addActionListener(e -> {
                    variant = v;
                    renderChoose();
                });
                g.add(b);
                row.add(b);
            }
            choose.add(left(row));
        }
        // what the picked mode is, and its rules at a glance
        choose.add(Box.createVerticalStrut(10));
        choose.add(left(text(Upe.info(mode()).summary(), 20f, null)));
        choose.add(Box.createVerticalStrut(6));
        choose.add(left(RuleChips.row("Loadout", RuleChips.loadout(mode()))));
        choose.add(left(RuleChips.row("From", RuleChips.sources(guild))));
        choose.add(Box.createVerticalStrut(14));
        choose.add(left(setting(ModeBadge.shieldIcon(24), "Guild version: trade with Loot Gobbler guild mates (can always be opted into later)",
                guild, on -> guild = on)));
        choose.add(Box.createVerticalStrut(4));
        choose.add(left(setting(ModeBadge.noPetIcon(24), "No pet: play the whole run without a pet (NPE)", noPet, on -> noPet = on)));
        choose.add(Box.createVerticalStrut(14));
        JPanel preview = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        preview.setOpaque(false);
        JLabel badgeCaption = new JLabel("Its badge:");
        badgeCaption.setFont(badgeCaption.getFont().deriveFont(16f));
        badgeCaption.setForeground(Palette.MUTED);
        preview.add(badgeCaption);
        preview.add(new BadgeView(mode(), noPet, 1.5f));
        choose.add(left(preview));
        choose.add(Box.createVerticalGlue());
        choose.add(left(buttons(button("Cancel", null, this::dispose), button("Next: create the character", "primary", this::toCreate))));
        body.add(choose, "choose");
        pages.show(body, "choose");
        body.revalidate();
        body.repaint();
    }

    private JComponent categoryCard(Upe.ModeInfo m) {
        boolean on = m.code().equals(category);
        JPanel p = new JPanel() {
            @Override
            protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color paper = Section.color("LootGobbler.paper", Color.DARK_GRAY);
                g.setColor(on ? Palette.mix(gold(), paper, 0.22) : paper);
                g.fill(new RoundRectangle2D.Float(0, 0, getWidth() - 1, getHeight() - 1, 12, 12));
                g.setStroke(new BasicStroke(on ? 2.5f : 1f));
                g.setColor(on ? gold() : Section.color("LootGobbler.line", Color.GRAY));
                g.draw(new RoundRectangle2D.Float(1, 1, getWidth() - 3, getHeight() - 3, 12, 12));
                g.dispose();
            }
        };
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);
        p.setBorder(new EmptyBorder(12, 14, 12, 14));
        JPanel badge = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        badge.setOpaque(false);
        badge.add(new BadgeView(m.code(), 1.2f));
        p.add(left(badge));
        p.add(Box.createVerticalStrut(8));
        JLabel name = new JLabel(m.name());
        name.setFont(name.getFont().deriveFont(16f));
        p.add(left(name));
        p.add(Box.createVerticalStrut(4));
        p.add(left(text(m.summary(), 17f, Palette.MUTED, 150)));
        p.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        Clicks.onClick(p, () -> {
            category = m.code();
            renderChoose();
        });
        return p;
    }

    // ---- step 2: create the character -----------------------------------------------------------------------------

    private void toCreate() {
        before = new HashSet<>();
        for (Char c : CharacterStore.get().all()) before.add(c.charId);
        told.clear();
        String mode = mode();
        boolean npe = noPet;
        JPanel p = column();
        p.add(left(stepLine(2)));
        p.add(left(title("Create a new character for your " + Upe.label(mode))));
        p.add(Box.createVerticalStrut(4));
        p.add(left(text("Any class. Do these in-game; Loot Gobbler watches and starts the run as soon as the character enters the game.", 17f, Palette.MUTED)));
        p.add(Box.createVerticalStrut(16));

        checklist.removeAll();
        checklist.add(left(item(1, "Keep Loot Gobbler's sniffer running", snifferState,
                "It has to see the character from its very first moment, at level 1.", null)));
        checklist.add(Box.createVerticalStrut(12));
        checklist.add(left(item(2, "Turn upgrades OFF when you create it", null,
                "The character must start with a tier 0 weapon, ability and armor. That starting gear never counts against the rules.",
                new Toggle("Upgrades", false))));
        if (npe) {
            checklist.add(Box.createVerticalStrut(12));
            checklist.add(left(item(3, "No pet: leave it without one", null,
                    "No pet may follow it during the whole run. Loot Gobbler sees the pets in the character list at each login.",
                    new Toggle("Pet", false))));
        }
        checklist.add(Box.createVerticalStrut(12));
        checklist.add(left(item(npe ? 4 : 3, "Create the character and enter the game", waiting,
                "The run starts by itself, and the character shows up in the Game Modes tab.", null)));
        p.add(left(checklist));
        p.add(Box.createVerticalStrut(12));
        problem.setVisible(false);
        problem.setFont(problem.getFont().deriveFont(15f));
        problem.setForeground(Palette.RED);
        p.add(left(problem));
        p.add(Box.createVerticalGlue());
        p.add(left(buttons(button("Back", null, () -> {
            watching = false;
            tick.stop();
            pages.show(body, "choose");
        }), button("Cancel", null, this::dispose))));
        body.add(p, "create");
        pages.show(body, "create");
        watching = true;
        animate();
        tick.start();
        body.revalidate();
        body.repaint();
    }

    private int dots;

    /** The live parts of step 2: the sniffer's state and the waiting dots. */
    private void animate() {
        boolean on = StatusBar.snifferRunning();
        snifferState.setText(on ? "✔ running" : "✖ off - start it first");
        snifferState.setForeground(on ? Palette.GREEN : Palette.RED);
        dots = (dots + 1) % 4;
        waiting.setText("Waiting for a new character" + ".".repeat(dots));
        waiting.setForeground(gold());
    }

    /** A new character appeared: starts the run if it can, else says why not and waits for the next. */
    private void lookForNewCharacter() {
        if (!watching) return;
        String mode = mode();
        for (Char c : CharacterStore.get().all()) {
            if (before.contains(c.charId) || told.contains(c.charId)) continue;
            Upe.Run r = Upe.get().run(c.charId);
            if (r == null) continue;   // not looked at by the rules yet
            told.add(c.charId);
            String cls = c.className == null ? "character" : c.className;
            if (!r.fresh) {
                showProblem("Your new " + cls + " was not seen from its creation (level " + c.level + "). Create another one with the sniffer running.");
            } else if (Boolean.FALSE.equals(r.t0Start)) {
                showProblem("Your new " + cls + " was created with upgrades enabled. Create another one with upgrades OFF.");
            } else if (!r.canStart(mode, noPet)) {
                showProblem("Your new " + cls + " cannot start a " + Upe.label(mode) + (r.pet ? ": it has a pet." : "."));
            } else {
                Upe.get().mark(c.charId, true, mode, noPet);
                watching = false;
                tick.stop();
                renderDone(c, mode);
                return;
            }
        }
    }

    private void showProblem(String s) {
        problem.setText("<html><div style='width:680px'>⚠ " + esc(s) + "</div></html>");
        problem.setVisible(true);
        before.addAll(told);
    }

    // ---- step 3: done -------------------------------------------------------------------------------------------

    private void renderDone(Char c, String mode) {
        done.removeAll();
        done.add(left(stepLine(3)));
        done.add(left(title("Your run has started")));
        done.add(Box.createVerticalStrut(20));
        JPanel who = new JPanel(new FlowLayout(FlowLayout.LEFT, 16, 0));
        who.setOpaque(false);
        who.add(new JLabel(CharactersView.skin(c, 96)));
        JPanel about = column();
        JLabel cls = new JLabel(c.className);
        cls.setFont(cls.getFont().deriveFont(28f));
        about.add(left(cls));
        about.add(Box.createVerticalStrut(8));
        JPanel b = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        b.setOpaque(false);
        b.add(new BadgeView(mode, noPet, 1.5f));
        about.add(left(b));
        who.add(about);
        done.add(left(who));
        done.add(Box.createVerticalStrut(18));
        done.add(left(text("It plays a " + Upe.label(mode) + " (" + Upe.info(mode).name() + ") now. Loot Gobbler follows the rules "
                + "while the sniffer runs; what it finds shows under its name in Inventory > Collection.", 17f, null)));
        done.add(Box.createVerticalGlue());
        int id = c.charId;
        done.add(left(buttons(button("Open character", null, () -> {
            openCharacter.accept(id);
            dispose();
        }), button("Done", "primary", this::dispose))));
        pages.show(body, "done");
        body.revalidate();
        body.repaint();
    }

    // ---- pieces -------------------------------------------------------------------------------------------------

    /** A setting to tick: its box, the icon its badge shows, and the words (clicking them ticks it too). */
    private JComponent setting(Icon icon, String words, boolean on, java.util.function.Consumer<Boolean> set) {
        JPanel row = new JPanel(new BorderLayout(6, 0));   // the words wrap if they are long
        row.setOpaque(false);
        JCheckBox box = new JCheckBox((String) null, on);
        box.setOpaque(false);
        box.addActionListener(e -> {
            set.accept(box.isSelected());
            renderChoose();
        });
        JLabel l = new JLabel("<html><div style='width:470px'>" + esc(words) + "</div></html>", icon, SwingConstants.LEFT);
        l.setIconTextGap(8);
        l.setFont(l.getFont().deriveFont(20f));
        l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        Clicks.onClick(l, () -> box.doClick());
        JPanel boxAtTop = new JPanel(new BorderLayout());
        boxAtTop.setOpaque(false);
        boxAtTop.add(box, BorderLayout.NORTH);
        row.add(boxAtTop, BorderLayout.WEST);
        row.add(l, BorderLayout.CENTER);
        return row;
    }

    /** A checklist entry: a numbered gold circle, the step, a live state, an explanation and an illustration. */
    private static JComponent item(int n, String what, JLabel state, String why, JComponent picture) {
        JPanel row = new JPanel(new BorderLayout(14, 0));
        row.setOpaque(false);
        row.add(new Number(n), BorderLayout.WEST);
        JPanel words = column();
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        top.setOpaque(false);
        JLabel l = new JLabel(what);
        l.setFont(l.getFont().deriveFont(18f));
        top.add(l);
        if (state != null) {
            state.setFont(state.getFont().deriveFont(15f));
            top.add(Box.createHorizontalStrut(14));
            top.add(state);
        }
        words.add(left(top));
        words.add(left(text(why, 17f, Palette.MUTED, 380)));
        row.add(words, BorderLayout.CENTER);
        if (picture != null) row.add(picture, BorderLayout.EAST);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        return row;
    }

    /** A gold circle with the step's number. */
    private static final class Number extends JComponent {
        private final int n;

        Number(int n) {
            this.n = n;
            setPreferredSize(new Dimension(34, 34));
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(gold());
            g.fill(new Ellipse2D.Float(1, 1, 31, 31));
            g.setFont(getFont().deriveFont(Font.BOLD, 17f));
            FontMetrics fm = g.getFontMetrics();
            String s = String.valueOf(n);
            g.setColor(new Color(0x2A1E0A));
            g.drawString(s, 17 - fm.stringWidth(s) / 2f, 17 + (fm.getAscent() - fm.getDescent()) / 2f);
            g.dispose();
        }
    }

    /** A game-like switch, shown OFF: what to set when creating the character. */
    private static final class Toggle extends JComponent {
        private final String label;
        private final boolean on;

        Toggle(String label, boolean on) {
            this.label = label;
            this.on = on;
            setPreferredSize(new Dimension(190, 46));
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int h = getHeight();
            g.setColor(new Color(0x1E1A16));
            g.fill(new RoundRectangle2D.Float(0, 2, getWidth() - 1, h - 5, 10, 10));
            g.setColor(new Color(0x5A5048));
            g.draw(new RoundRectangle2D.Float(0, 2, getWidth() - 1, h - 5, 10, 10));
            g.setFont(getFont().deriveFont(15f));
            FontMetrics fm = g.getFontMetrics();
            g.setColor(new Color(0xE8E2D6));
            g.drawString(label, 12, h / 2f + (fm.getAscent() - fm.getDescent()) / 2f);
            // the switch, right
            int sw = 58, sh = 26, sx = getWidth() - sw - 12, sy = (h - sh) / 2;
            g.setColor(on ? Palette.GREEN : new Color(0x6B3530));
            g.fill(new RoundRectangle2D.Float(sx, sy, sw, sh, sh, sh));
            g.setColor(on ? Palette.GREEN.brighter() : new Color(0xC2524A));
            g.draw(new RoundRectangle2D.Float(sx, sy, sw, sh, sh, sh));
            int kx = on ? sx + sw - sh + 2 : sx + 2;
            g.setColor(Color.WHITE);
            g.fill(new Ellipse2D.Float(kx, sy + 2, sh - 4, sh - 4));
            g.setFont(getFont().deriveFont(Font.BOLD, 11f));
            fm = g.getFontMetrics();
            String t = on ? "ON" : "OFF";
            g.setColor(Color.WHITE);
            float tx = on ? sx + 8 : sx + sw - 8 - fm.stringWidth(t);
            g.drawString(t, tx, sy + sh / 2f + (fm.getAscent() - fm.getDescent()) / 2f);
            g.dispose();
        }
    }

    /** A mode's badge as on the loadout card, scaled. */
    static final class BadgeView extends JComponent {
        private final String mode;
        private final float scale;

        private final boolean noPet;

        BadgeView(String mode, float scale) {
            this(mode, false, scale);
        }

        BadgeView(String mode, boolean noPet, float scale) {
            this.mode = mode;
            this.noPet = noPet;
            this.scale = scale;
            int pad = 8;   // room for glows, sparkles and rays
            setPreferredSize(new Dimension(Math.round((ModeBadge.width(mode, noPet) + 2 * pad) * scale), Math.round((ModeBadge.H + 2 * pad) * scale)));
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.scale(scale, scale);
            ModeBadge.draw(g, 8, 8, mode, noPet);
            g.dispose();
        }
    }

    private static JLabel stepLine(int n) {
        JLabel l = new JLabel("STEP " + n + " OF 3");
        l.setFont(l.getFont().deriveFont(13f));
        l.setForeground(Palette.MUTED);
        return l;
    }

    private static JLabel title(String s) {
        JLabel l = new JLabel(s);
        l.setFont(l.getFont().deriveFont(26f));
        l.setForeground(gold());
        return l;
    }

    private static JLabel heading(String s) {
        JLabel l = new JLabel(s.toUpperCase(Locale.ROOT));
        l.setFont(l.getFont().deriveFont(15f));
        l.setForeground(gold());
        return l;
    }

    private static JComponent text(String s, float size, Color color) {
        return text(s, size, color, 560);
    }

    private static JComponent text(String s, float size, Color color, int width) {
        JLabel l = new JLabel("<html><div style='width:" + width + "px'>" + esc(s) + "</div></html>");
        l.setFont(l.getFont().deriveFont(size));
        if (color != null) l.setForeground(color);
        l.setBorder(new EmptyBorder(2, 0, 2, 0));
        return l;
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;");
    }

    private static JComponent buttons(JButton... bs) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        p.setOpaque(false);
        for (JButton b : bs) p.add(b);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
        return p;
    }

    private static JButton button(String label, String style, Runnable action) {
        JButton b = new JButton(label.toUpperCase(Locale.ROOT));
        if (style != null) b.putClientProperty("FlatLaf.styleClass", style);
        b.addActionListener(e -> action.run());
        return b;
    }

    private static JPanel column() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);
        return p;
    }

    private static Color gold() {
        return Section.color("LootGobbler.gold", Palette.GOLD);
    }

    /** Left-aligned, and a panel no taller than it needs (BoxLayout would stretch it into gaps). */
    private static <T extends JComponent> T left(T c) {
        c.setAlignmentX(LEFT_ALIGNMENT);
        if (c instanceof JPanel) c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
        return c;
    }

    /** The small green plus in a box that opens the wizard. */
    static JButton plusButton(Runnable open) {
        JButton b = new JButton() {
            @Override
            protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int s = Math.min(getWidth(), getHeight()) - 2, x = (getWidth() - s) / 2, y = (getHeight() - s) / 2;
                Color green = getModel().isRollover() ? Palette.GREEN.brighter() : Palette.GREEN;
                g.setColor(green);
                g.fill(new RoundRectangle2D.Float(x, y, s, s, 6, 6));
                g.setColor(green.darker());
                g.draw(new RoundRectangle2D.Float(x, y, s, s, 6, 6));
                g.setColor(Color.WHITE);
                g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                int m = s / 4;
                g.drawLine(x + s / 2, y + m, x + s / 2, y + s - m);
                g.drawLine(x + m, y + s / 2, x + s - m, y + s / 2);
                g.dispose();
            }
        };
        b.setPreferredSize(new Dimension(26, 26));
        b.setContentAreaFilled(false);
        b.setBorderPainted(false);
        b.setFocusable(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setToolTipText("Start a new game mode run: pick a mode, then create its character");
        b.addActionListener(e -> open.run());
        return b;
    }
}
