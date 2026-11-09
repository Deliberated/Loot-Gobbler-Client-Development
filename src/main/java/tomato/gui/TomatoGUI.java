package tomato.gui;

import java.awt.*;
import javax.swing.*;
import lootgobbler.ui.CollectionPanel;
import lootgobbler.ui.DiscordPanel;
import lootgobbler.ui.DpsPanel;
import lootgobbler.ui.SettingsPanel;
import lootgobbler.ui.StatusBar;
import lootgobbler.ui.TabBar;
import javax.swing.border.EmptyBorder;
import packets.data.QuestData;
import tomato.Tomato;
import tomato.backend.data.TomatoData;
import tomato.gui.character.CharacterPanelGUI;
import tomato.gui.chat.ChatGUI;
import tomato.gui.chat.ChatPingGUI;
import tomato.gui.dps.DpsDisplayOptions;
import tomato.gui.dps.DpsGUI;
import tomato.gui.keypop.KeypopGUI;
import tomato.gui.maingui.*;
import tomato.gui.myinfo.MyInfoGUI;
import tomato.gui.quest.QuestGUI;
import tomato.gui.security.ParsePanelGUI;
import tomato.gui.security.SecurityGUI;
import tomato.gui.stats.DungeonStats;
import tomato.gui.stats.StatisticsGUI;
import tomato.version.Version;
import util.PropertiesManager;

/**
 * Example GUI for Tomato mod.
 */
public class TomatoGUI {

    private static final int windowWidth = 1799;
    private static final int windowHeight = 1062;
    private static int fontSize = 16;
    private static int fontStyle = 0;
    private static String fontName = lootgobbler.Themes.PIXEL_FONT;
    private static StatusBar statusBar;
    private static JFrame frame;
    private static ChatGUI chatPanel;
    private static SecurityGUI securityPanel;
    private static CharacterPanelGUI characterPanel;
    private static QuestGUI questPanel;
    private static MyInfoGUI myDmg;
    private static StatisticsGUI statistics;
    private JMenuBar jMenuBar;
    private JPanel mainPanel, dpsPanel;
    private TomatoMenuBar menuBar;
    private Point center;
    private Image icon;
    private static TomatoData data;

    public TomatoGUI(TomatoData data) {
        this.data = data;
    }

    /**
     * Create main panel and initializes the GUI for the example Tomato.
     */
    public void create() {
        TabBar tabbedPane = new TabBar();   // one scrollable row, tabs reorderable by dragging

        myDmg = new MyInfoGUI(data);
        lootgobbler.ui.CharactersView characters = new lootgobbler.ui.CharactersView();
        lootgobbler.ui.GuildPanel guild = new lootgobbler.ui.GuildPanel();
        lootgobbler.ui.DpsLoggerView dps = new lootgobbler.ui.DpsLoggerView(new DpsPanel());   // Dungeons | DPS
        tabbedPane.addTab("Profile", new lootgobbler.ui.ProfileView(new lootgobbler.ui.DungeonStatsPanel(), id -> {
            characters.showCharacter(id);   // clicking your character on the profile opens it in Characters
            tabbedPane.show("Characters");
        }, page -> {
            guild.show(page);   // the guild row on the profile opens the caravan or the quest map
            tabbedPane.show("Guild");
        }, run -> {
            dps.showRun(run);   // a recent run on the profile opens it in Battles
            tabbedPane.show("Battles");
        }, cls -> {
            characters.showClass(cls);   // the exaltations on the profile open the Classes page (or one class)
            tabbedPane.show("Characters");
        }));
        chatPanel = new ChatGUI(data);
        questPanel = new QuestGUI();
        JComponent discord = new DiscordPanel();
        if (!lootgobbler.Discord.ENABLED) {
            discord = lootgobbler.ui.UnderConstruction.wrap(discord,
                    "Discord webhooks are under construction - nothing is posted for now.");
        }
        tabbedPane.addTab("Social", new lootgobbler.ui.SubTabs()
                .page("Chat", chatPanel)
                .page("Daily Quests", lootgobbler.ui.UnderConstruction.wrap(questPanel,
                        "Daily quests are under construction - they will come from the Loot Gobbler server."))
                .page("Discord", discord).done());
        CollectionPanel inventory = new CollectionPanel();
        tabbedPane.addTab("Inventory", inventory);
        lootgobbler.ui.ItemLinks.setOpener(id -> {   // item icons in other tabs jump to the item in Inventory
            tabbedPane.show("Inventory");
            inventory.showItem(id);
        });
        tabbedPane.addTab("Characters", characters);

        dpsPanel = new DpsGUI(data);   // Tomato's original view: not shown any more, but its packet handlers still run
        tabbedPane.addTab("Battles", dps);   // Dungeons | DPS (was "DPS Logger")
        lootgobbler.ui.DpsLoggerView.setVisitOpener(v -> {   // e.g. a dungeon icon on a fame chart
            dps.showVisit(v);
            tabbedPane.show("Battles");
        });

        // Key-pops, Security, the old Characters view and Statistics have no tab any more, but are still created: packet
        // handlers, key-pop sounds, the loot filter, fame tracking and font changes call into them.
        new KeypopGUI();
        securityPanel = new SecurityGUI();
        characterPanel = new CharacterPanelGUI(data);
        statistics = new StatisticsGUI(data);

        center =
            GraphicsEnvironment.getLocalGraphicsEnvironment().getCenterPoint();
        menuBar = new TomatoMenuBar();

        statusBar = new StatusBar(TomatoMenuBar::toggleSniffer, () -> tabbedPane.show("Server"));

        mainPanel = new JPanel();
        mainPanel.setLayout(new BorderLayout());
        mainPanel.add(tabbedPane, BorderLayout.CENTER);
        mainPanel.add(statusBar, BorderLayout.SOUTH);

        icon = Toolkit.getDefaultToolkit().getImage(Tomato.imagePath);
        loadFontSizePreset();
        loadFontNamePreset();
        DpsGUI.loadFilterPreset();
        DpsDisplayOptions.loadProfileFilter();
        lootgobbler.PacketDebug.install();
        lootgobbler.ItemUse.install();   // forged and fed items leave the collection
        lootgobbler.CharacterStore.install();   // your death moves the character to the graveyard at once
        lootgobbler.ItemCooldowns.install();   // a sound when the Coral Juice Refinery or Alien Generator is ready again
        lootgobbler.DungeonDifficulty.preload();
        lootgobbler.SpriteMemory.start();   // drops RealmShark's decoded sprite sheets (300+ MB) when idle   // the title bar's dungeon box shows how hard a dungeon is
        jMenuBar = menuBar.make();
        // top-level menus sit in the title bar, so they use its text color (matters for dark title bars)
        for (Component c : jMenuBar.getComponents()) {
            if (c instanceof JMenu m) m.putClientProperty("FlatLaf.styleClass", "titleMenu");
        }
        // the dungeon you are in, in the title bar between the menus and the window title
        jMenuBar.add(Box.createHorizontalStrut(12));
        jMenuBar.add(new lootgobbler.ui.ActiveDungeonBadge(() -> {
            dps.showDungeons();   // the dungeon you are in, under Battles > Dungeons
            tabbedPane.show("Battles");
        }));
        // Built after the menu so the sound/filter flags are already loaded from settings.
        tabbedPane.addTab("Settings", new SettingsPanel());
        tabbedPane.addTab("Guild", guild);
        lootgobbler.ui.ServerPanel server = new lootgobbler.ui.ServerPanel();
        tabbedPane.addTab("Server", server);
        tabbedPane.addTrailing(new lootgobbler.ui.NotificationBell());
        // new players waiting for approval: a notification that opens the queue in the Server tab
        lootgobbler.ApprovalWatcher.get().start(() -> SwingUtilities.invokeLater(() -> {
            tabbedPane.show("Server");
            server.showModeration();
        }));
        // the DPS Logger tab is called Battles now: keep its place in a saved tab order
        String savedOrder = util.PropertiesManager.getProperty("tabOrder");
        if (savedOrder != null && savedOrder.contains("DPS Logger")) {
            util.PropertiesManager.setProperties("tabOrder", savedOrder.replace("DPS Logger", "Battles"));
        }
        tabbedPane.restoreOrder("Profile", "Inventory", "Characters", "Battles", "Social", "Guild", "Server", "Settings");
        makeFrame();

        frame.setVisible(true);
        lootgobbler.StartupSound.playSaved();
        lootgobbler.ui.WhatsNewDialog.showAfterUpdate(frame);   // after an update; reads its settings before the welcome
        SwingUtilities.invokeLater(() -> lootgobbler.ui.WelcomeDialog.showOnFirstStart(frame));   // once the window is up
        lootgobbler.Updater.start();   // first check a few seconds after start, then every hour
        lootgobbler.ServerSync.get().start();          // syncs player data whenever logged in to a server
        lootgobbler.GobblerServer.get().reconnect();   // to the saved Loot Gobbler server, if any
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {   // keep the dungeon you are in, and upload it
            lootgobbler.DungeonVisits.get().close();
            lootgobbler.ServerSync.get().flush(5_000);
        }, "save-on-close"));
    }

    /**
     * Method to create text areas.
     *
     * @param textArea Text area object.
     * @return Scroll pane object to add to a parent object.
     */
    public static JScrollPane createTextArea(
        JTextArea textArea,
        boolean stayAtTop
    ) {
        textArea.setEnabled(true);
        textArea.setEditable(false);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        JScrollPane scrollChat = new JScrollPane(textArea);
        scrollChat.setVerticalScrollBarPolicy(
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        );
        scrollChat.setAutoscrolls(true);
        if (stayAtTop) {
            new SmartScroller(scrollChat, 0);
        } else {
            new SmartScroller(scrollChat);
        }
        return scrollChat;
    }

    /**
     * Loads the theme preset chosen by the user.
     */
    public static void loadThemePreset() {
        lootgobbler.Themes.install(PropertiesManager.getProperty("theme"));
    }

    /**
     * Loads the font size preset chosen by the user.
     */
    private void loadFontSizePreset() {
        String fontSize = PropertiesManager.getProperty("fontSize");
        int fs = TomatoGUI.fontSize;
        if (fontSize != null) {
            try {
                fs = Integer.parseInt(fontSize);
            } catch (Exception ignored) {}
        }

        fontSizeTextAreas(fs);
    }

    /**
     * Loads the font size preset chosen by the user.
     */
    private void loadFontNamePreset() {
        String fontName = PropertiesManager.getProperty("fontName");
        if (fontName != null) fontName = lootgobbler.Themes.normalizeFont(fontName);
        if (fontName == null) {
            fontName = TomatoGUI.fontName;
        } else {
            TomatoGUI.fontName = fontName;
        }
        String fontStyle = PropertiesManager.getProperty("fontStyle");
        int fontStyleNum = TomatoGUI.fontStyle;
        if (fontStyle != null) {
            try {
                fontStyleNum = Integer.parseInt(fontStyle);
            } catch (Exception ignored) {}
        }
        fontNameTextAreas(fontName, fontStyleNum);
    }

    /**
     * Creates the frame with icon.
     */
    public void makeFrame() {
        frame = new JFrame("Loot Gobbler by Gunshit");
        frame.setIconImages(lootgobbler.AppIcon.images());
        frame.setMinimumSize(new Dimension(640, 420));
        lootgobbler.ui.WindowMemory.attach(frame, windowWidth, windowHeight);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.add(jMenuBar);
        frame.setJMenuBar(jMenuBar);
        menuBar.setFrame(frame);
        frame.setContentPane(mainPanel);
        frame.setVisible(true);
    }

    public static String getFontName() {
        return fontName;
    }

    public static int getDefaultFontSize() {
        return fontSize;
    }

    /**
     * Set font size of text area.
     */
    public static void fontSizeTextAreas(int size) {
        fontSize = size;
        Font font = new Font(fontName, fontStyle, size);
        ChatGUI.editFont(font);
        KeypopGUI.editFont(font);
        DpsGUI.editFont(font);
        ParsePanelGUI.editFont(font);
        DungeonStats.editFont(font);
    }

    /**
     * Set font size of text area.
     */
    public static void fontNameTextAreas(String name, int style) {
        fontName = name;
        fontStyle = style;
        Font font = new Font(name, style, fontSize);
        ChatGUI.editFont(font);
        KeypopGUI.editFont(font);
        DpsGUI.editFont(font);
        ParsePanelGUI.editFont(font);
        DungeonStats.editFont(font);
    }

    /**
     * Updates the questGUI with quest data.
     *
     * @param q Quest data received when visiting quest room.
     */
    public static void updateQuests(QuestData[] q) {
        questPanel.update(q);
    }

    /**
     * Updates the state of the sniffer at the bottom label to show if running or off.
     *
     * @param running Set the label to running or off.
     */
    public static void setStateOfSniffer(boolean running) {
        snifferRunning = running;
        if (statusBar != null) statusBar.setRunning(running);
    }

    private static volatile boolean snifferRunning;

    public static boolean isSnifferRunning() {
        return snifferRunning;
    }

    /**
     * Getter for the main object.
     *
     * @return The main tomato frame object.
     */
    public static JFrame getFrame() {
        return frame;
    }

    /**
     * Opens chat message ping window.
     */
    public static void openChatPingMessage() {
        new ChatPingGUI(data, chatPanel).open();
    }

    /**
     * Opens entity ID ping window.
     */
    public static void openEntityIdPing() {
        new EntityPingGUI(data).open();
    }

    /**
     * Opens entity ID ping window.
     */
    public static void openItemPing() {
        new ItemPingGUI(data).open();
    }

    /**
     * Opens enchantment ping window.
     */
    public static void openEnchantPing() {
        EnchantPingGUI.open();
    }

    /**
     * Opens dungeon ping window.
     */
    public static void openDungeonPing() {
        DungeonPingGUI.open();
    }
}
