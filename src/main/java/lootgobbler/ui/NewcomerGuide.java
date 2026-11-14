package lootgobbler.ui;

import lootgobbler.CharacterStore;
import lootgobbler.GobblerServer;
import lootgobbler.Holdings;
import lootgobbler.ItemDb;
import lootgobbler.Themes;
import com.formdev.flatlaf.util.UIScale;
import util.PropertiesManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.AWTEventListener;
import java.awt.event.MouseEvent;
import java.awt.geom.Area;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The Newcomer's Guide: a tour through the app over the window. The part a step talks about has a gold frame and
 * the rest of the page is greyed out (still readable), and a card next to it says what it is (Back, Next, Skip). A part to click glows and
 * says CLICK ME; clicking it does what it always does and goes on. Steps that wait for the game (you seen in-game,
 * your characters) or for you (server connected, registered) only open Next once that happened.
 * <p>
 * It starts by itself on every start until it was finished or skipped once (after the first start's greeting), and
 * goes on where it was if the app was closed during it.
 * Info > Newcomer's Guide starts it again. Only the quick tour exists so far.
 */
public final class NewcomerGuide {
    /** How the guide opens views: switches tabs and shows what a step needs. */
    public interface Nav {
        void tab(String title);

        void character(int charId);

        void caravan();

        void dungeons();
    }

    private enum Step {WELCOME, CHOOSE, PROFILE, CH_STATS, CH_LOADOUT, CH_PROGRESS, CH_ITEM, INVENTORY, BATTLES, DUNGEON,
        GUILD_RUN, CONNECT, ACCOUNT, GUILD, SETTINGS, DONE}

    private static final String KEY_ACTIVE = "guideActive", KEY_STEP = "guideStep", KEY_CHOSEN = "guideQuickChosen",
            KEY_DONE = "guideDoneVersion";   // the guide's version finished or skipped: it no longer starts by itself
    /**
     * The guide's version: raise it when the guide changed enough that everyone should see it again. A version
     * finished or skipped earlier counts as unseen. (1 was v0.7.28, kept under "guideDone".)
     */
    private static final String VERSION = "2";
    private static final Step[] STEPS = Step.values();
    private static final int CARD_W = 390, RING = 6;
    private static NewcomerGuide instance;
    private static volatile boolean registered;   // an account was registered while the app runs

    private final JFrame frame;
    private final Nav nav;
    private final Layer layer = new Layer();
    private final JPanel card = new JPanel();
    private final Timer timer;
    private final AWTEventListener clicks;
    private Component oldGlass;
    private int index = -1;
    private String shownSig = "";
    private View view;
    private List<Rectangle> spots = List.of();
    private List<Rectangle> glows = List.of();
    private final Set<String> scrolled = new HashSet<>();
    private long lastCharacterNav;
    private boolean running;
    private boolean menuWasOpen;   // the "quick or extensive?" step: Info > Newcomer's Guide was open
    private boolean tourChosen;   // started from the menu's Quick tour: no "quick or extensive?" step

    private NewcomerGuide(JFrame frame, Nav nav) {
        this.frame = frame;
        this.nav = nav;
        layer.setLayout(null);
        layer.setOpaque(false);
        card.setOpaque(false);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(new EmptyBorder(14, 18, 14, 18));
        layer.add(card);
        timer = new Timer(40, e -> tick());
        clicks = e -> {
            if (!(e instanceof MouseEvent m) || m.getID() != MouseEvent.MOUSE_PRESSED || view == null || !view.advanceOnClick) return;
            for (Component c : view.clicks) {
                if (c != null && (c == m.getComponent() || SwingUtilities.isDescendingFrom(m.getComponent(), c))) {
                    Step was = step();
                    SwingUtilities.invokeLater(() -> {   // after the click did its own thing
                        if (step() == was) go(index + 1, true);
                    });
                    return;
                }
            }
        };
        ItemLinks.onOpen(id -> SwingUtilities.invokeLater(() -> {
            if (running && step() == Step.CH_ITEM) go(Step.INVENTORY.ordinal(), true);
        }));
    }

    /** Set once by the main window. */
    public static void install(JFrame frame, Nav nav) {
        instance = new NewcomerGuide(frame, nav);
    }

    /** Info > Newcomer's Guide > Quick tour: from the start. */
    public static void startQuick() {
        if (instance == null) return;
        Step s = instance.running ? instance.step() : null;
        if (s == Step.CHOOSE) {   // picked in the menu the guide opened: go on, do not start over
            MenuSelectionManager.defaultManager().clearSelectedPath();
            instance.go(Step.PROFILE.ordinal(), false);
        } else {
            instance.choose(true);   // from Info: straight into the quick tour after the welcome
            if (s != Step.WELCOME) instance.begin(0);   // at the welcome already: just skip the question after it
        }
    }

    /**
     * After the window is up: on the very first start (the greeting was just shown) the guide starts; if the app was
     * closed during the guide, it goes on where it was.
     */
    public static void afterStart(boolean firstStart) {
        if (instance == null) return;
        if ("true".equals(PropertiesManager.getProperty(KEY_ACTIVE))) {
            int at = 0;
            try {
                at = Integer.parseInt(String.valueOf(PropertiesManager.getProperty(KEY_STEP)).trim());
            } catch (NumberFormatException ignored) {
                // from the start
            }
            instance.tourChosen = "true".equals(PropertiesManager.getProperty(KEY_CHOSEN));   // as it was started
            instance.begin(at);
        } else if (firstStart || !VERSION.equals(PropertiesManager.getProperty(KEY_DONE))) {
            // the first time, and for everyone who has not done it yet (also players from before it existed):
            // it asks "quick or extensive?"
            instance.choose(false);
            instance.begin(0);
        }
    }

    /** Whether the tour was already picked (started from Info), kept for going on after a restart. */
    private void choose(boolean chosen) {
        tourChosen = chosen;
        PropertiesManager.setProperties(KEY_CHOSEN, String.valueOf(chosen));
    }

    /** The Server tab registered an account. */
    public static void registeredAccount() {
        registered = true;
    }

    // ---- running ----------------------------------------------------------------------------------------------

    private void begin(int at) {
        if (!running) {
            oldGlass = frame.getGlassPane();
            frame.setGlassPane(layer);
            layer.setVisible(true);
            Toolkit.getDefaultToolkit().addAWTEventListener(clicks, AWTEvent.MOUSE_EVENT_MASK);
            timer.start();
            running = true;
        }
        PropertiesManager.setProperties(KEY_ACTIVE, "true");
        index = -1;
        go(Math.max(0, Math.min(STEPS.length - 1, at)), false);
    }

    private void end() {
        PropertiesManager.setProperties(KEY_ACTIVE, "false");
        PropertiesManager.setProperties(KEY_DONE, VERSION);
        if (!running) return;
        running = false;
        clickThroughMenu(false);
        previewDungeon(false);
        timer.stop();
        Toolkit.getDefaultToolkit().removeAWTEventListener(clicks);
        layer.setVisible(false);
        if (oldGlass != null) frame.setGlassPane(oldGlass);
        frame.repaint();
    }

    private Step step() {
        return index < 0 ? null : STEPS[index];
    }

    /** To step i; byClick: a click already opened what the step shows. */
    private void go(int i, boolean byClick) {
        if (i >= STEPS.length) {
            end();
            return;
        }
        if (tourChosen && STEPS[Math.max(0, i)] == Step.CHOOSE) {   // already chosen: past the question
            i = i > index ? Step.PROFILE.ordinal() : Step.WELCOME.ordinal();
        }
        index = Math.max(0, i);
        clickThroughMenu(step() == Step.CHOOSE);
        previewDungeon(step() == Step.DUNGEON);   // a sample dungeon in the title bar, only during that step
        PropertiesManager.setProperties(KEY_STEP, String.valueOf(index));
        scrolled.clear();
        menuWasOpen = false;
        shownSig = "";
        if (!byClick) open(step());
        tick();
    }

    /** What a step shows when it starts. */
    private void open(Step s) {
        switch (s) {
            case WELCOME, PROFILE, DONE -> nav.tab("Profile");
            case CHOOSE -> SwingUtilities.invokeLater(this::openGuideMenu);
            case CH_STATS, CH_LOADOUT, CH_PROGRESS, CH_ITEM -> {
                nav.tab("Characters");
                showACharacter();
            }
            case INVENTORY -> nav.tab("Inventory");
            case BATTLES -> {
                nav.tab("Battles");
                nav.dungeons();
            }
            case DUNGEON, GUILD_RUN -> nav.tab("Battles");
            case CONNECT, ACCOUNT -> nav.tab("Server");
            case GUILD -> {
                nav.tab("Guild");
                nav.caravan();
            }
            case SETTINGS -> nav.tab("Settings");
        }
    }

    /** The title bar's dungeon box with a sample dungeon (nothing counted), while you are in none. */
    private static void previewDungeon(boolean on) {
        if (GuideTargets.any("title.dungeon") instanceof ActiveDungeonBadge b) b.preview(on);
    }

    private void showACharacter() {
        CharacterStore store = CharacterStore.get();
        CharacterStore.Char c = store.mostPlayed();
        if (c == null) c = store.lastPlayedChar();
        if (c != null) {
            nav.character(c.charId);
            lastCharacterNav = System.currentTimeMillis();
        }
    }

    /** Opens Info > Newcomer's Guide, so you see where it is. */
    private void openGuideMenu() {
        Component info = GuideTargets.any("menu.info"), guide = GuideTargets.any("menu.guide");
        if (!(info instanceof JMenu m) || !(guide instanceof JMenu g) || !(m.getParent() instanceof JMenuBar bar)) return;
        if (!m.isShowing()) return;
        MenuSelectionManager.defaultManager().setSelectedPath(new MenuElement[]{bar, m, m.getPopupMenu(), g, g.getPopupMenu()});
    }

    private static final String CONSUME_ON_CLOSE = "PopupMenu.consumeEventOnClose";
    private Object consumeOnClose;   // the look and feel's setting while the guide changed it
    private boolean consumeChanged;

    /**
     * The "quick or extensive?" step keeps Info > Newcomer's Guide open. A click outside an open menu normally only
     * closes it (the click is swallowed), so QUICK took two clicks: during that step the click goes through.
     */
    private void clickThroughMenu(boolean on) {
        if (on && !consumeChanged) {
            consumeOnClose = UIManager.get(CONSUME_ON_CLOSE);
            UIManager.put(CONSUME_ON_CLOSE, Boolean.FALSE);
            consumeChanged = true;
        } else if (!on && consumeChanged) {
            UIManager.put(CONSUME_ON_CLOSE, consumeOnClose);
            consumeChanged = false;
        }
    }

    /** Whether the Info menu the guide opened is still open. */
    private static boolean menuOpen() {
        return GuideTargets.any("menu.info") instanceof JMenu m && m.getPopupMenu().isShowing();
    }

    private static boolean recognized() {
        String n = CharacterStore.get().playerName();
        return n != null && !n.isBlank();
    }

    private static boolean charactersLoaded() {
        for (CharacterStore.Char c : CharacterStore.get().all()) if (c.alive && c.classType > 0) return true;
        return false;
    }

    // ---- what each step says --------------------------------------------------------------------------------

    /** A step as it is now: its text, what it frames, what glows, whether Next is open. */
    private record View(String head, String title, String body, String waiting, List<Component> spots,
                        List<Component> clicks, boolean advanceOnClick, boolean open, String next, boolean choose,
                        boolean merged) {
        String sig() {
            return head + "|" + title + "|" + body + "|" + waiting + "|" + open + "|" + next + "|" + choose;
        }
    }

    private View describe(Step s) {
        int quickNo = s.ordinal() - 1, quickOf = STEPS.length - 2;
        String head = s == Step.WELCOME || s == Step.CHOOSE ? "NEWCOMER'S GUIDE" : "QUICK TOUR · " + quickNo + " / " + quickOf;
        List<Component> spot = new ArrayList<>(), click = new ArrayList<>();
        String title, body, waiting = null, next = "NEXT";
        boolean open = true, onClick = false, merged = false;   // merged: the parts framed as one
        switch (s) {
            case WELCOME -> {
                if (recognized()) {
                    title = "Welcome, " + CharacterStore.get().playerName() + "!";
                    body = "The Gobbler found you. It keeps track of your loot, your characters, your dungeon runs and"
                            + " your guild. Let's have a look around.";
                    next = "CONTINUE";
                } else if (!StatusBar.snifferRunning()) {
                    title = "Welcome to Loot Gobbler";
                    body = "First the Gobbler has to find you.<br>1. Click Start Sniffer.<br>2. Then open the game.";
                    add(spot, "sniffer");
                    add(click, "sniffer");
                    open = false;
                    waiting = "Next opens once the Gobbler sees you in the game.";
                } else {
                    title = "Now go into the game";
                    body = "Open the game and log in. Already playing? Change realm, or go to the Nexus or your vault,"
                            + " so the Gobbler sees you.";
                    open = false;
                    waiting = "Waiting for the game...";
                }
            }
            case CHOOSE -> {
                title = "Quick or extensive?";
                body = "The quick tour shows the essentials in about two minutes. The extensive one, going through every"
                        + " tab, is coming later.<br>You can start the guide again any time: Info > Newcomer's Guide.";
                add(spot, "menu.info");
                Component info = GuideTargets.any("menu.info"), guide = GuideTargets.any("menu.guide");
                if (info instanceof JMenu m && m.getPopupMenu().isShowing()) spot.add(m.getPopupMenu());
                if (guide instanceof JMenu g && g.getPopupMenu().isShowing()) spot.add(g.getPopupMenu());
            }
            case PROFILE -> {
                title = "Your overview";
                body = "Your profile sums up your fame, your characters and your latest runs. Click your character to"
                        + " look closer.";
                add(spot, "profile.overview");   // the whole card, the character glowing in it
                if (spot.isEmpty()) add(spot, "profile.character");
                add(click, "profile.character");
                onClick = true;
            }
            case CH_STATS -> {
                if (!charactersLoaded()) {
                    title = "Your characters are not loaded yet";
                    body = "Go into the game and enter the Nexus, or change realm: the Gobbler then reads all your"
                            + " characters.";
                    open = false;
                    waiting = "Waiting for your characters...";
                } else {
                    title = "All your characters";
                    body = "Every character with its gear, how many stats are maxed, its fame and what it gained lately."
                            + " Click your character to see it on the right.";
                    add(spot, "characters.list");
                    add(click, "characters.card");   // the one picked: clicking it goes on
                    onClick = true;
                }
            }
            case CH_LOADOUT -> {
                title = "Craft your loadout";
                body = "The character you picked: Loadout is what it wears now. Suggested Loadout puts together the best"
                        + " gear from everything you own, enchantments included: click the stats you prefer.";
                add(spot, "characters.loadout");
                add(spot, "characters.suggested");
                add(spot, "characters.inventory");
                merged = true;
            }
            case CH_PROGRESS -> {
                title = "Its progress";
                body = "Fame gained, fame per hour and time played, for each character on its own.";
                add(spot, "characters.fame");
            }
            case CH_ITEM -> {
                List<Component> boxes = new ArrayList<>();   // the items worn and carried
                add(boxes, "characters.loadout");
                add(boxes, "characters.inventory");
                for (Component box : boxes) collectBestItems(box, click);
                add(spot, "characters.loadout");
                add(spot, "characters.suggested");
                add(spot, "characters.inventory");
                merged = true;
                if (click.isEmpty()) {
                    title = "Your best items";
                    body = "Untiered and set items (UT and ST) on a character can be clicked anywhere: they open in"
                            + " the inventory. This one has none yet.";
                } else {
                    title = "Your best items";
                    body = "Your untiered and set items glow. Click one to see where you keep it and how many you have.";
                }
            }
            case INVENTORY -> {
                add(spot, "inventory.grid");
                if (Holdings.get().gaps().vault()) {
                    title = "Open your vault once";
                    body = "The inventory shows everything you own. Your characters are in already, your vault is not:"
                            + " walk into your vault in the game and it fills in.";
                } else {
                    title = "Everything you own";
                    body = "Characters, vault and gift chest in one place. An item you click anywhere is picked out here.";
                }
            }
            case BATTLES -> {
                title = "Every dungeon run";
                body = "Each dungeon you enter is logged: how long it took, your damage and what dropped.";
                add(spot, "battles.detail");
            }
            case DUNGEON -> {
                title = "The dungeon you are in";
                body = "While you play, the title bar shows your dungeon, how hard it is and how long you have been in."
                        + " Click it to open the run." + (lootgobbler.DungeonVisits.get().current() == null
                        ? "<br>Here, an example: it shows up by itself in your next dungeon." : "");
                add(spot, "title.dungeon");
            }
            case GUILD_RUN -> {
                title = "Did you notice your guild?";
                body = "Below each run the Gobbler compares you with the guildmates who were there. A Loot Gobbler"
                        + " server makes that work: every Gobbler shares what it saw, so they all know who played with"
                        + " whom. That is the heart of the Gobbler.<br>You do not need it to use the app, but it opens"
                        + " up the multiplayer side: challenges, the guild leaderboard, loot collections and more.";
                add(spot, "battles.guild");
            }
            case CONNECT -> {
                add(spot, "server.connection");
                if (GobblerServer.get().state() != GobblerServer.State.ONLINE) {
                    title = "Connect to your server";
                    body = "Ask your guild's admin for the server address, or enter it if you know it. Then click Connect.";
                    add(click, "server.connect");
                    open = false;
                    waiting = "Next opens once the connection works.";
                } else {
                    title = "Connected";
                    body = "The server answers. Next: your account.";
                }
            }
            case ACCOUNT -> {
                add(spot, "server.account");
                if (!registered && GobblerServer.get().user() == null) {
                    title = "Create your account";
                    body = "Your name comes from the game, so it is filled in already. Choose a password and click"
                            + " Register.";
                    add(click, "server.register");
                    open = false;
                    waiting = GobblerServer.get().state() == GobblerServer.State.ONLINE
                            ? "Next opens once you are registered." : "The server is not connected: go back a step.";
                } else {
                    title = "That is all for now";
                    body = "An admin or moderator has to accept you. Until then everything that does not need the"
                            + " server works as usual.";
                }
            }
            case GUILD -> {
                title = "Your guild";
                body = "Once you are accepted, your guild shows up here. The Loot Caravan, for example, collects what"
                        + " everyone in the guild finds.";
                add(spot, "guild.caravan");
            }
            case SETTINGS -> {
                title = "Make it yours";
                body = "Sounds for items and key pops, the overlays, fonts and the loot filter.";
                add(spot, "settings.itemSounds");
            }
            default -> {
                title = "You are all set";
                body = "Happy looting! Start the guide again any time from Info > Newcomer's Guide.";
                next = "FINISH";
            }
        }
        return new View(head, title, body, waiting, spot, click, onClick, open, next, s == Step.CHOOSE, merged);
    }

    private static void add(List<Component> to, String name) {
        Component c = GuideTargets.showing(name);
        if (c != null) to.add(c);
    }

    /** The UT and ST item icons in box (marked by ItemLinks), at most six. */
    private static void collectBestItems(Component box, List<Component> into) {
        if (into.size() >= 6) return;
        if (box instanceof JComponent j && j.getClientProperty(ItemLinks.ITEM) instanceof Integer id && j.isShowing()) {
            ItemDb.Item it = ItemDb.get().item(id);
            if (it != null && (it.isUT() || it.isST())) into.add(j);
        }
        if (box instanceof Container k) for (Component c : k.getComponents()) collectBestItems(c, into);
    }

    // ---- every 40 ms: what to frame, the card ----------------------------------------------------------------

    private void tick() {
        if (!running || index < 0) return;
        Step s = step();
        if (s.ordinal() >= Step.CH_STATS.ordinal() && s.ordinal() <= Step.CH_ITEM.ordinal() && charactersLoaded()
                && GuideTargets.showing("characters.loadout") == null
                && System.currentTimeMillis() - lastCharacterNav > 1500) {
            showACharacter();   // the characters came in while waiting: show one
        }
        view = describe(s);
        for (Component c : view.merged && !view.spots.isEmpty() ? view.spots.subList(0, 1) : view.spots) {   // into sight once per step (merged: from its top)
            String key = System.identityHashCode(c) + "";
            if (c instanceof JComponent j && scrolled.add(key)) j.scrollRectToVisible(new Rectangle(0, 0, j.getWidth(), j.getHeight()));
        }
        spots = rects(view.spots);
        if (view.merged && spots.size() > 1) {   // one frame around all of them
            Rectangle u = new Rectangle(spots.get(0));
            for (Rectangle r : spots) u = u.union(r);
            spots = List.of(u);
        }
        glows = rects(view.clicks);
        if (!view.sig().equals(shownSig)) {
            shownSig = view.sig();
            buildCard(view);
        }
        placeCard();
        layer.repaint();
    }

    private List<Rectangle> rects(List<Component> cs) {
        List<Rectangle> out = new ArrayList<>();
        for (Component c : cs) {
            if (!c.isShowing()) continue;
            Rectangle r = c instanceof JComponent j ? j.getVisibleRect() : new Rectangle(0, 0, c.getWidth(), c.getHeight());
            if (c instanceof JComponent j && j.getClientProperty(ItemLinks.ITEM) != null) {
                r = r.intersection(ItemLinks.iconArea(j));   // an item: just its icon, which is what takes the click
            }
            if (r.isEmpty()) continue;
            if (SwingUtilities.getWindowAncestor(c) != frame) {   // a menu in a window of its own
                Point p = c.getLocationOnScreen();
                SwingUtilities.convertPointFromScreen(p, layer);
                out.add(new Rectangle(p.x + r.x, p.y + r.y, r.width, r.height));
            } else {
                out.add(SwingUtilities.convertRectangle(c, r, layer));
            }
        }
        return out;
    }

    private void buildCard(View v) {
        card.removeAll();
        Color gold = Section.color("LootGobbler.gold", Palette.GOLD);
        JPanel top = row();
        JLabel head = new JLabel(v.head);
        head.putClientProperty("FlatLaf.styleClass", "small");
        head.setForeground(Palette.MUTED);
        top.add(head, BorderLayout.WEST);
        if (step() != Step.DONE) {
            JButton skip = new JButton("Skip");
            skip.putClientProperty("JButton.buttonType", "borderless");
            skip.setFocusable(false);
            skip.setToolTipText("Ends the guide. Info > Newcomer's Guide starts it again.");
            skip.addActionListener(e -> end());
            top.add(skip, BorderLayout.EAST);
        }
        card.add(top);
        JLabel title = new JLabel(v.title);
        title.setFont(new Font(Themes.HEAD_FONT, Font.PLAIN, 30));
        title.setForeground(gold);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(title);
        card.add(Box.createVerticalStrut(6));
        JLabel body = new JLabel("<html><body style='width:" + UIScale.unscale(UIScale.scale(CARD_W) - 2 * UIScale.scale(18) - 8) + "px'>"
                + v.body + "</body></html>");
        body.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(body);
        if (v.waiting != null) {
            card.add(Box.createVerticalStrut(8));
            JLabel w = new JLabel(v.waiting);
            w.setForeground(gold);
            w.setAlignmentX(Component.LEFT_ALIGNMENT);
            card.add(w);
        }
        card.add(Box.createVerticalStrut(10));
        JPanel buttons = new JPanel() {   // right under the text, from its left edge, 8 px apart
            @Override
            public Component add(Component c) {
                if (getComponentCount() > 0) super.add(Box.createHorizontalStrut(UIScale.scale(8)));
                return super.add(c);
            }
        };
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.setOpaque(false);
        buttons.setAlignmentX(Component.LEFT_ALIGNMENT);
        if (index > 0) buttons.add(button("BACK", false, true, () -> go(index - 1, false)));
        if (v.choose) {   // Back, Quick (the default), Extensive
            buttons.add(button("QUICK", true, true, () -> {
                MenuSelectionManager.defaultManager().clearSelectedPath();
                go(index + 1, false);
            }));
            JButton extensive = button("EXTENSIVE", false, false, () -> {
            });
            extensive.setToolTipText("Coming soon");
            buttons.add(extensive);
        } else {
            buttons.add(button(v.next, true, v.open, () -> go(index + 1, false)));
        }
        card.add(buttons);
        card.revalidate();
    }

    private static JPanel row() {
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    private static JButton button(String text, boolean primary, boolean enabled, Runnable action) {
        JButton b = new JButton(text);
        if (primary) b.putClientProperty("FlatLaf.styleClass", "primary");
        b.setFocusable(false);
        b.setEnabled(enabled);
        b.addActionListener(e -> action.run());
        return b;
    }

    /** Below what it frames if there is room, else above, right or left; in the middle when it frames nothing. */
    private void placeCard() {
        if (step() == Step.CHOOSE) {   // stays where it was once the menu closed, so it does not jump from under the mouse
            if (menuOpen()) menuWasOpen = true;
            else if (menuWasOpen) return;
        }
        Dimension d = card.getPreferredSize();
        int w = d.width, h = d.height, W = layer.getWidth(), H = layer.getHeight(), gap = 18;
        int x, y;
        if (spots.isEmpty()) {
            x = (W - w) / 2;
            y = (H - h) / 2;
        } else {
            Rectangle u = new Rectangle(spots.get(0));
            for (Rectangle r : spots) u = u.union(r);
            u.grow(RING, RING);
            if (u.y + u.height + gap + h + 30 <= H) {          // below (room for CLICK ME)
                x = u.x;
                y = u.y + u.height + gap + (glows.isEmpty() ? 0 : 30);
            } else if (u.y - gap - h >= 8) {                   // above
                x = u.x;
                y = u.y - gap - h;
            } else if (u.x + u.width + gap + w <= W) {         // right
                x = u.x + u.width + gap;
                y = u.y;
            } else if (u.x - gap - w >= 8) {                   // left
                x = u.x - gap - w;
                y = u.y;
            } else if (!glows.isEmpty()) {                     // a big frame: under what to click, inside it
                Rectangle t = glows.get(0);
                x = t.x;
                y = t.y + t.height + 16 + 34;
            } else {
                x = W - w - 24;
                y = H - h - 24;
            }
        }
        x = Math.max(8, Math.min(x, W - w - 8));
        y = Math.max(8, Math.min(y, H - h - 8));
        Rectangle now = new Rectangle(x, y, w, h);
        if (!now.equals(card.getBounds())) {
            card.setBounds(now);
            card.validate();
        }
    }

    // ---- the layer over the window ----------------------------------------------------------------------------

    private final class Layer extends JComponent {
        /** Only the card takes the mouse: everything else goes to the window below. */
        @Override
        public boolean contains(int x, int y) {
            return card.isVisible() && card.getBounds().contains(x, y);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Color gold = Section.color("LootGobbler.gold", Palette.GOLD);
            // what the step is about stays as it is in a gold frame with a soft glow; the rest is greyed out, still readable
            {   // a step that frames nothing (the welcome, the end, waiting): all of it greyed, just the card stands out
                Area grey = new Area(new Rectangle(0, 0, getWidth(), getHeight()));
                for (Rectangle r : spots) grey.subtract(new Area(round(r)));
                g2.setColor(new Color(24, 22, 20, 140));
                g2.fill(grey);
            }
            for (Rectangle r : spots) {
                RoundRectangle2D frame = round(r);
                for (int i = 6; i >= 1; i--) {
                    double falloff = 1 - (i - 1) / 6.0;
                    g2.setColor(new Color(gold.getRed(), gold.getGreen(), gold.getBlue(), (int) Math.round(110 * falloff * falloff)));
                    g2.setStroke(new BasicStroke(2f));
                    g2.draw(new RoundRectangle2D.Double(frame.getX() - i, frame.getY() - i, frame.getWidth() + 2 * i,
                            frame.getHeight() + 2 * i, 12 + i, 12 + i));
                }
                g2.setStroke(new BasicStroke(3f));
                g2.setColor(gold);
                g2.draw(frame);
            }

            double pulse = 0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 1100.0 * 2 * Math.PI);
            for (Rectangle r : glows) glow(g2, r, pulse);
            if (!glows.isEmpty()) clickMe(g2, glows.get(0), pulse);

            // the card: paper with a gold edge and a soft shadow
            Rectangle c = card.getBounds();
            g2.setColor(new Color(0, 0, 0, 90));
            g2.fillRoundRect(c.x + 4, c.y + 6, c.width, c.height, 14, 14);
            g2.setColor(paper());
            g2.fillRoundRect(c.x, c.y, c.width, c.height, 14, 14);
            g2.setColor(gold);
            g2.setStroke(new BasicStroke(2f));
            g2.drawRoundRect(c.x + 1, c.y + 1, c.width - 2, c.height - 2, 14, 14);
            g2.dispose();
        }

        private RoundRectangle2D round(Rectangle r) {
            return new RoundRectangle2D.Float(r.x - RING, r.y - RING, r.width + 2 * RING, r.height + 2 * RING, 12, 12);
        }

        /** A gold glow around r, growing and shrinking with the pulse. */
        private void glow(Graphics2D g2, Rectangle r, double pulse) {
            int size = 4 + (int) Math.round(10 * pulse);
            for (int i = size; i >= 1; i--) {
                double falloff = 1 - (i - 1) / (double) size;
                int a = (int) Math.round(255 * (0.25 + 0.4 * pulse) * falloff * falloff);
                g2.setColor(new Color(255, 194, 14, Math.min(255, a)));
                g2.setStroke(new BasicStroke(2f));
                g2.drawRoundRect(r.x - i, r.y - i, r.width + 2 * i, r.height + 2 * i, 8 + i, 8 + i);
            }
            g2.setColor(new Color(255, 194, 14));
            g2.setStroke(new BasicStroke(2f));
            g2.drawRoundRect(r.x - 1, r.y - 1, r.width + 2, r.height + 2, 8, 8);
        }

        /** "CLICK ME" under r (above it at the bottom edge), pulsing with the glow. */
        private void clickMe(Graphics2D g2, Rectangle r, double pulse) {
            Font f = new Font(Themes.HEAD_FONT, Font.PLAIN, (int) Math.round(20 + 3 * pulse));
            g2.setFont(f);
            FontMetrics fm = g2.getFontMetrics();
            String text = "CLICK ME";
            int w = fm.stringWidth(text) + 16, h = fm.getHeight() + 2;
            int x = r.x + r.width / 2 - w / 2;
            int y = r.y + r.height + 16;
            if (y + h > getHeight() - 4) y = r.y - 16 - h;
            x = Math.max(4, Math.min(x, getWidth() - w - 4));
            g2.setColor(new Color(255, 194, 14));
            g2.fillRoundRect(x, y, w, h, 8, 8);
            g2.setColor(new Color(0x1F1A14));
            g2.drawString(text, x + 8, y + 1 + fm.getAscent());
        }

        private Color paper() {
            Color c = UIManager.getColor("LootGobbler.paper");
            return c != null ? c : UIManager.getColor("Panel.background");
        }
    }
}
