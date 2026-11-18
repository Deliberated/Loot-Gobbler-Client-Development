package lootgobbler.ui;

import com.formdev.flatlaf.util.UIScale;
import lootgobbler.Themes;
import util.PropertiesManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.AWTEventListener;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * What's New tours: after an update, once the release notes are closed, a short tour of a release's new features,
 * in the Newcomer's Guide's look (greyed page, gold frames, CLICK ME). Each starts with the same greeting, then its
 * own steps. A tour is shown once (finished or skipped); Info > What's New Tour shows the latest again.
 * <p>
 * To present a release's features, add a {@link Tour} to {@link #tours()}: a new id, and its steps.
 */
public final class FeatureTour {
    /** How a tour opens views. */
    public interface Nav {
        void tab(String title);

        void character(int charId);
    }

    /**
     * One step: its title and text (worked out when shown, so they can depend on your characters), what it opens
     * when it starts and undoes when left, what it frames, and what glows (clicking that goes on).
     */
    private record Step(Supplier<String> title, Supplier<String> body, Runnable open, Runnable leave,
                        Supplier<List<String>> spots, List<String> clicks, boolean merged) {
    }

    private record Tour(String id, List<Step> steps) {
    }

    private static final String KEY_SEEN = "featureToursSeen";   // the ids of the tours finished or skipped
    private static final String GREETING_TITLE = "Hey, welcome!", GREETING_BODY = "I have some new features for you.";
    private static final int CARD_W = 390;
    private static FeatureTour instance;

    private final JFrame frame;
    private final Nav nav;
    private final JPanel card = new JPanel();
    private final GuideLayer layer = new GuideLayer(card);
    private final Timer timer;
    private final AWTEventListener clicks;
    private Component oldGlass;
    private Tour tour;
    private int index = -1;   // -1: the greeting
    private String shownSig = "";
    private List<Component> clickable = List.of();
    private final Set<Component> scrolled = new java.util.HashSet<>();

    private FeatureTour(JFrame frame, Nav nav) {
        this.frame = frame;
        this.nav = nav;
        card.setOpaque(false);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(new EmptyBorder(14, 18, 14, 18));
        timer = new Timer(40, e -> tick());
        clicks = e -> {
            if (!(e instanceof MouseEvent m) || m.getID() != MouseEvent.MOUSE_PRESSED || tour == null) return;
            for (Component c : clickable) {
                if (c != null && (c == m.getComponent() || SwingUtilities.isDescendingFrom(m.getComponent(), c))) {
                    int was = index;
                    SwingUtilities.invokeLater(() -> {   // after the click did its own thing
                        if (index == was) go(index + 1);
                    });
                    return;
                }
            }
        };
    }

    /** Set once by the main window. */
    public static void install(JFrame frame, Nav nav) {
        instance = new FeatureTour(frame, nav);
    }

    // ---- the tours: one per release that has features worth showing, the latest last -------------------------

    private static List<Tour> tours() {
        return List.of(new Tour("game-modes", List.of(
                step("New: Game Modes",
                        "Player experiences got a tab of their own. Click it to have a look.",
                        null, spots("tab.Game Modes"), List.of("tab.Game Modes")),
                new Step(() -> "Your player experiences, tracked", () ->
                        "PPE, UPE and its variants (Pure UPE, STPE, Shiny UPE, Divine PE) are tracked now. From the moment a"
                                + " character is created, the Gobbler watches its drops, vault, trades, the shop and rewards, and"
                                + " tells you right away when a rule is broken.<br>Pick a mode to see its rules: what it may wear"
                                + " and where things may come from.",
                        () -> nav().tab("Game Modes"), null, spots("gamemodes.list", "gamemodes.detail"), List.of(), true),
                step("Start a run: the wizard",
                        "The green plus walks you through it: pick a mode, then create a new character with upgrades"
                                + " turned off (and without a pet for a no-pet run). The wizard waits for it and starts the"
                                + " run by itself.",
                        () -> nav().tab("Game Modes"), spots("gamemodes.plus"), List.of()),
                new Step(() -> "Or: click an eligible character", FeatureTour::eligibleText, FeatureTour::showEligible, null,
                        () -> GuideTargets.showing("characters.upeBanner") != null ? List.of("characters.upeBanner")
                                : List.of("gamemodes.could"), List.of(), false),
                new Step(() -> "Every run has its own inventory", () ->
                        "Inventory > Items shows what one run found on its own: pick it in the column on the left."
                                + " Here, an example character that just started: nothing found yet. Its drops show up here"
                                + " as it plays, apart from your whole collection.",
                        () -> {
                            nav().tab("Inventory");
                            inventoryExample(true);
                        },
                        () -> inventoryExample(false), spots("inventory.upeColumn", "inventory.grid"), List.of(), true),
                step("That's it", "Have fun with your runs! Info > What's New Tour shows this again.", null, List::of, List.of())
        )));
    }

    private static Step step(String title, String body, Runnable open, Supplier<List<String>> spots, List<String> clicks) {
        return new Step(() -> title, () -> body, open, null, spots, clicks, false);
    }

    private static Supplier<List<String>> spots(String... names) {
        List<String> l = Arrays.asList(names);
        return () -> l;
    }

    private static Nav nav() {
        return instance.nav;
    }

    /** A character that could start a run, newest first; 0 if none. */
    private static int eligible() {
        List<Integer> ids = CharactersView.potentialUpes();
        return ids.isEmpty() ? 0 : ids.get(0);
    }

    private static void showEligible() {
        int id = eligible();
        if (id != 0) {
            nav().tab("Characters");
            nav().character(id);
        } else {
            nav().tab("Game Modes");
        }
    }

    private static String eligibleText() {
        String how = "A character the Gobbler saw from its creation, with upgrades off, can start a run until it breaks a rule.";
        if (eligible() != 0) {
            return how + " It shows its mode faded, like this one: click it to start the run.<br>It is also listed in"
                    + " Game Modes under Could start, with a Start button for every mode.";
        }
        return how + " Such characters are listed here under Could start: click Start, in the mode you picked. In the"
                + " Characters tab its mode shows faded, a click on it starts the run.";
    }

    /** Inventory > Items with an example run (nothing stored), during that step. */
    private static void inventoryExample(boolean on) {
        if (GuideTargets.any("inventory.view") instanceof CollectionPanel p) p.showExample(on);
    }

    // ---- starting -------------------------------------------------------------------------------------------

    /** After the release notes and the Newcomer's Guide: the tours of this version not seen yet. */
    public static void afterStart() {
        if (instance == null || NewcomerGuide.running()) return;
        Tour next = tours().stream().filter(t -> !seen().contains(t.id())).reduce((a, b) -> b).orElse(null);
        if (next != null) instance.begin(next);
    }

    /** The first start ever: the Newcomer's Guide shows the app as it is, the tours so far count as seen. */
    public static void firstStart() {
        Set<String> s = seen();
        tours().forEach(t -> s.add(t.id()));
        PropertiesManager.setProperties(KEY_SEEN, String.join(",", s));
    }

    /** Info > What's New Tour: the latest tour again. */
    public static void showLatest() {
        if (instance == null || NewcomerGuide.running()) return;
        List<Tour> all = tours();
        if (!all.isEmpty()) instance.begin(all.get(all.size() - 1));
    }

    private static Set<String> seen() {
        String s = PropertiesManager.getProperty(KEY_SEEN);
        Set<String> out = new LinkedHashSet<>();
        if (s != null) for (String id : s.split(",")) if (!id.isBlank()) out.add(id.trim());
        return out;
    }

    // ---- running --------------------------------------------------------------------------------------------

    private void begin(Tour t) {
        if (tour == null) {
            oldGlass = frame.getGlassPane();
            frame.setGlassPane(layer);
            layer.setVisible(true);
            Toolkit.getDefaultToolkit().addAWTEventListener(clicks, AWTEvent.MOUSE_EVENT_MASK);
            timer.start();
        } else {
            leave();
        }
        tour = t;
        index = -1;
        go(-1);
    }

    private void end() {
        if (tour == null) return;
        leave();
        Set<String> s = seen();
        s.add(tour.id());
        PropertiesManager.setProperties(KEY_SEEN, String.join(",", s));
        tour = null;
        timer.stop();
        Toolkit.getDefaultToolkit().removeAWTEventListener(clicks);
        layer.setVisible(false);
        if (oldGlass != null) frame.setGlassPane(oldGlass);
        frame.repaint();
    }

    private Step current() {
        return index < 0 ? null : tour.steps().get(index);
    }

    private void leave() {
        Step s = current();
        if (s != null && s.leave() != null) s.leave().run();
    }

    /** To step i (-1: the greeting). */
    private void go(int i) {
        leave();
        if (i >= tour.steps().size()) {
            end();
            return;
        }
        index = Math.max(-1, i);
        shownSig = "";
        scrolled.clear();
        Step s = current();
        if (s != null && s.open() != null) s.open().run();
        tick();
    }

    private void tick() {
        if (tour == null) return;
        Step s = current();
        List<Component> spot = new ArrayList<>(), click = new ArrayList<>();
        if (s != null) {
            for (String n : s.spots().get()) add(spot, n);
            for (String n : s.clicks()) add(click, n);
        }
        for (Component c : s != null && s.merged() && !spot.isEmpty() ? spot.subList(0, 1) : spot) {   // into sight once per step
            if (c instanceof JComponent j && scrolled.add(c)) j.scrollRectToVisible(new Rectangle(0, 0, j.getWidth(), j.getHeight()));
        }
        clickable = click;
        List<Rectangle> spots = layer.rects(spot);
        if (s != null && s.merged() && spots.size() > 1) {   // one frame around all of them
            Rectangle u = new Rectangle(spots.get(0));
            for (Rectangle r : spots) u = u.union(r);
            spots = List.of(u);
        }
        layer.spots = spots;
        layer.glows = layer.rects(click);
        String title = s == null ? GREETING_TITLE : s.title().get(), body = s == null ? GREETING_BODY : s.body().get();
        String sig = index + "|" + title + "|" + body;
        if (!sig.equals(shownSig)) {
            shownSig = sig;
            buildCard(title, body);
        }
        layer.placeCard();
        layer.repaint();
    }

    private static void add(List<Component> to, String name) {
        Component c = GuideTargets.showing(name);
        if (c != null) to.add(c);
    }

    private void buildCard(String titleText, String bodyText) {
        card.removeAll();
        Color gold = Section.color("LootGobbler.gold", Palette.GOLD);
        int of = tour.steps().size();
        boolean last = index == of - 1;
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel head = new JLabel("WHAT'S NEW" + (index < 0 ? "" : " · " + (index + 1) + " / " + of));
        head.putClientProperty("FlatLaf.styleClass", "small");
        head.setForeground(Palette.MUTED);
        top.add(head, BorderLayout.WEST);
        if (!last) {
            JButton skip = new JButton("Skip");
            skip.putClientProperty("JButton.buttonType", "borderless");
            skip.setFocusable(false);
            skip.setToolTipText("Ends the tour. Info > What's New Tour shows it again.");
            skip.addActionListener(e -> end());
            top.add(skip, BorderLayout.EAST);
        }
        card.add(top);
        JLabel title = new JLabel(titleText);
        title.setFont(new Font(Themes.HEAD_FONT, Font.PLAIN, 30));
        title.setForeground(gold);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(title);
        card.add(Box.createVerticalStrut(6));
        JLabel body = new JLabel("<html><body style='width:" + UIScale.unscale(UIScale.scale(CARD_W) - 2 * UIScale.scale(18) - 8) + "px'>"
                + bodyText + "</body></html>");
        body.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(body);
        card.add(Box.createVerticalStrut(10));
        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.setOpaque(false);
        buttons.setAlignmentX(Component.LEFT_ALIGNMENT);
        if (index > 0) {
            buttons.add(button("BACK", false, () -> go(index - 1)));
            buttons.add(Box.createHorizontalStrut(UIScale.scale(8)));
        }
        buttons.add(button(last ? "FINISH" : "NEXT", true, () -> go(index + 1)));
        card.add(buttons);
        card.revalidate();
    }

    private static JButton button(String text, boolean primary, Runnable action) {
        JButton b = new JButton(text);
        if (primary) b.putClientProperty("FlatLaf.styleClass", "primary");
        b.setFocusable(false);
        b.addActionListener(e -> action.run());
        return b;
    }
}
