package lootgobbler.ui;

import assets.ImageBuffer;
import lootgobbler.StartupSound;
import lootgobbler.Themes;
import tomato.realmshark.enums.LootBags;
import tomato.gui.TomatoGUI;
import tomato.gui.maingui.PingSoundGUI;
import tomato.gui.stats.LootGUI;
import tomato.realmshark.Sound;
import util.PropertiesManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.function.Consumer;

/**
 * Settings tab: sound/ping toggles, loot bag filter and theme. Uses the same settings keys and
 * static flags as Tomato's original menus, so saved settings carry over.
 */
public class SettingsPanel extends JPanel {
    public SettingsPanel() {
        super(new BorderLayout());

        JPanel sections = new JPanel();
        sections.setLayout(new BoxLayout(sections, BoxLayout.Y_AXIS));
        sections.setBorder(new EmptyBorder(8, 8, 8, 8));
        sections.add(new Section("Sound & Pings", Palette.GOLD, soundBody()));
        sections.add(Box.createVerticalStrut(8));
        sections.add(new Section("Startup Sound", Palette.GOLD, startupBody()));
        sections.add(Box.createVerticalStrut(8));
        sections.add(GuideTargets.mark("settings.itemSounds", new Section("Item Sounds", Palette.GOLD, itemSoundsBody())));
        sections.add(Box.createVerticalStrut(8));
        sections.add(new Section("Ping Lists", Palette.GOLD, pingListsBody()));
        sections.add(Box.createVerticalStrut(8));
        sections.add(new Section("Loot Filter", Palette.GOLD, filterBody()));
        sections.add(Box.createVerticalStrut(8));
        sections.add(new Section("Theme", Palette.GOLD, themeBody()));
        sections.add(Box.createVerticalStrut(8));
        sections.add(new Section("Logging", Palette.GOLD, loggingBody()));
        sections.add(Box.createVerticalStrut(8));
        sections.add(new Section("Newcomer's Guide", Palette.GOLD, guideBody()));

        JPanel holder = new JPanel(new BorderLayout());   // keeps sections at their natural height
        holder.add(sections, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(holder);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll);
    }

    private JPanel soundBody() {
        JPanel body = body();

        JSlider volume = new JSlider(0, 100, intProperty("soundVolume", Sound.DEFAULT_VOLUME));
        volume.setOpaque(false);
        volume.addChangeListener(e -> {
            if (volume.getValueIsAdjusting()) return;
            Sound.setVolume(volume.getValue());
            PropertiesManager.setProperties("soundVolume", String.valueOf(volume.getValue()));
            Sound.pm.play();
        });
        JPanel volumeRow = row();
        volumeRow.add(new JLabel("Volume"));
        volumeRow.add(Box.createHorizontalStrut(24));
        volumeRow.add(volume);
        body.add(volumeRow);

        JPanel grid = grid();
        grid.add(toggle("PM ping", "chatPing", false, v -> Sound.playPmSound = v, Sound.pm));
        grid.add(toggle("Party ping", "chatPingParty", false, v -> Sound.playPartySound = v, Sound.party));
        grid.add(toggle("Guild ping", "chatPingGuild", false, v -> Sound.playGuildSound = v, Sound.guild));
        grid.add(toggle("White bag", "whiteBagSound", true, v -> Sound.playWhiteBagSound = v, Sound.whitebag));
        grid.add(toggle("Orange bag", "orangeBagSound", true, v -> Sound.playOrangeBagSound = v, Sound.orangebag));
        grid.add(toggle("Red bag", "redBagSound", false, v -> Sound.playRedBagSound = v, Sound.redbag));
        grid.add(toggle("Gold bag", "goldBagSound", false, v -> Sound.playGoldBagSound = v, Sound.goldbag));
        grid.add(toggle("Egg bag", "eggBagSound", false, v -> Sound.playEggBagSound = v, Sound.eggbag));
        grid.add(toggle("Blue bag", "blueBagSound", false, v -> Sound.playBlueBagSound = v, Sound.bluebag));
        grid.add(toggle("Trade", "tradePing", false, v -> Sound.playTradeSound = v, Sound.trade));
        body.add(grid);
        return body;
    }

    /**
     * Sounds for what items do, each in its own part: the Amulet of Restoration, and the cooldowns of the quickslot
     * items and the Protective Matrix armors.
     */
    private JPanel itemSoundsBody() {
        JPanel body = body();
        int amulet = lootgobbler.Amulets.restorationId();
        int[] matrices = {0x0483, 0x0484, 0x0485};   // Heavy (heavy armor), Magic (robe), Fitted (light armor)
        itemSound(body, lootgobbler.ItemSound.AMULET, "Played when your Amulet of Restoration breaks (it saved you).",
                Arrow.BROKEN, amulet);
        body.add(Box.createVerticalStrut(14));
        itemSound(body, lootgobbler.ItemSound.AMULET_READY,
                "Played when your Amulet of Restoration is charged again (1800 seconds after it saved you).",
                Arrow.RECHARGED, amulet);
        body.add(Box.createVerticalStrut(14));
        itemSound(body, lootgobbler.ItemSound.REFINERY,
                "Played when your Coral Juice Refinery can be used again (15 seconds after using it).",
                null, lootgobbler.ItemCooldowns.REFINERY);
        body.add(Box.createVerticalStrut(14));
        itemSound(body, lootgobbler.ItemSound.ALIEN,
                "Played when your Miniature Alien Generator can be used again (45 seconds after using it).",
                null, lootgobbler.ItemCooldowns.ALIEN_GENERATOR);
        body.add(Box.createVerticalStrut(14));
        itemSound(body, lootgobbler.ItemSound.MATRIX_BREAK,
                "Played when your Protective Matrix (Heavy, Magic or Fitted) breaks (it shielded you).",
                Arrow.BROKEN, matrices);
        body.add(Box.createVerticalStrut(14));
        itemSound(body, lootgobbler.ItemSound.MATRIX,
                "Played when your Protective Matrix is charged again (180 seconds after it shielded you).",
                Arrow.RECHARGED, matrices);
        return body;
    }

    /**
     * One item's part: its name with the items behind it (and an arrow: down when it broke, up when it is charged
     * again), when the sound plays, and the sound (a WAV file of your choice, or none).
     */
    private void itemSound(JPanel body, lootgobbler.ItemSound sound, String when, Arrow arrow, int... items) {
        JPanel title = row();
        JLabel name = new JLabel(sound.item);
        name.setFont(name.getFont().deriveFont(Font.BOLD, name.getFont().getSize2D() + 2));
        name.setForeground(Palette.GOLD);
        title.add(name);
        JPanel icons = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        icons.setOpaque(false);
        for (int id : items) {
            if (id < 0) continue;
            JLabel it = new JLabel(ActiveDungeonBadge.fit(CharactersView.sprite(id, 40), 24));
            String n = assets.IdToAsset.objectName(id);
            if (n != null) it.setToolTipText(n);
            icons.add(it);
        }
        if (arrow != null) {
            JLabel a = new JLabel(arrow);
            a.setToolTipText(arrow == Arrow.BROKEN ? "Broken" : "Recharged");
            icons.add(Box.createHorizontalStrut(2));
            icons.add(a);
        }
        title.add(icons);
        // its own volume, at the right: heard when let go
        JLabel level = new JLabel();
        JSlider volume = new JSlider(0, 100, sound.volume());
        volume.setFocusable(false);
        volume.setOpaque(false);
        volume.setPreferredSize(new Dimension(150, volume.getPreferredSize().height));
        volume.setToolTipText("Volume of this sound");
        Runnable showLevel = () -> level.setText(volume.getValue() + "%");
        showLevel.run();
        level.setPreferredSize(new Dimension(level.getFontMetrics(level.getFont()).stringWidth("100%") + 4, level.getPreferredSize().height));
        volume.addChangeListener(e -> {
            showLevel.run();
            if (volume.getValueIsAdjusting()) return;
            sound.setVolume(volume.getValue());
            sound.play();
        });
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        right.setOpaque(false);
        right.add(new JLabel("Volume"));
        right.add(volume);
        right.add(level);
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.setAlignmentX(LEFT_ALIGNMENT);
        head.add(title, BorderLayout.CENTER);
        head.add(right, BorderLayout.EAST);
        head.setMaximumSize(new Dimension(Integer.MAX_VALUE, head.getPreferredSize().height));
        body.add(head);
        JPanel hint = row();
        hint.add(new JLabel(when));
        body.add(hint);
        JLabel current = new JLabel();
        // the bundled sounds to pick from (only when there is more than one)
        JComboBox<String> builtins = new JComboBox<>(sound.builtins.stream().map(lootgobbler.ItemSound.Builtin::name).toArray(String[]::new));
        builtins.setFocusable(false);
        boolean[] showing = {false};
        Runnable show = () -> {
            showing[0] = true;
            String f = sound.file();
            int b = sound.builtin();
            current.setText(sound.isOff() ? "Off" : !f.isEmpty() ? new java.io.File(f).getName()
                    : sound.builtins.size() > 1 ? "" : "Default (" + sound.builtins.get(0).name() + ")");
            current.setToolTipText(f.isEmpty() ? null : f);
            builtins.setSelectedIndex(b >= 0 && !sound.isOff() ? b : -1);
            showing[0] = false;
        };
        builtins.addActionListener(e -> {
            if (showing[0] || builtins.getSelectedIndex() < 0) return;
            sound.useBuiltin(builtins.getSelectedIndex());
            sound.play();   // hear the choice
            show.run();
        });
        show.run();
        JPanel pickRow = row();
        if (sound.builtins.size() > 1) {
            pickRow.add(builtins);
            pickRow.add(Box.createHorizontalStrut(8));
        }
        pickRow.add(current);
        pickRow.add(Box.createHorizontalStrut(12));
        pickRow.add(button("Play", sound::play));
        pickRow.add(button("Choose...", () -> {
            String f = sound.file();
            JFileChooser chooser = new JFileChooser(f.isEmpty() ? null : new java.io.File(f).getParentFile());
            chooser.setDialogTitle("Sound for the " + sound.item);
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("WAV sound (*.wav)", "wav"));
            if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
            if (sound.set(chooser.getSelectedFile().getAbsolutePath())) {
                sound.play();
            } else {
                JOptionPane.showMessageDialog(this, "This file cannot be played. Please choose a WAV file.",
                        "Item Sounds", JOptionPane.WARNING_MESSAGE);
            }
            show.run();
        }));
        pickRow.add(button("Default", () -> {
            sound.useBuiltin(0);
            sound.play();
            show.run();
        }));
        pickRow.add(button("Off", () -> {
            sound.turnOff();
            show.run();
        }));
        body.add(pickRow);
    }

    /**
     * Sound played once when Loot Gobbler opens. Picking one plays it, so you can hear the choice.
     */
    private JPanel startupBody() {
        JPanel body = body();
        JPanel hint = row();
        hint.add(new JLabel("Played once when Loot Gobbler opens."));
        body.add(hint);
        int selected = java.util.Arrays.asList(StartupSound.IDS).indexOf(StartupSound.saved());
        Segmented picker = new Segmented(true, selected, i -> {
            PropertiesManager.setProperties(StartupSound.KEY, StartupSound.IDS[i]);
            StartupSound.play(StartupSound.IDS[i]);
        }, StartupSound.LABELS);
        JPanel pickRow = row();
        pickRow.add(picker);
        pickRow.add(Box.createHorizontalStrut(8));
        pickRow.add(button("Play", StartupSound::playSaved));
        body.add(pickRow);
        return body;
    }

    private JPanel pingListsBody() {
        JPanel body = body();
        JPanel hint = row();
        hint.add(new JLabel("Play a sound when chat, items, enchants, entities or dungeons match your lists."));
        body.add(hint);
        JPanel buttons = new JPanel(new GridLayout(0, 3, 8, 6));
        buttons.setOpaque(false);
        buttons.setAlignmentX(LEFT_ALIGNMENT);
        buttons.setBorder(new EmptyBorder(4, 6, 4, 6));
        buttons.add(button("Chat pings...", TomatoGUI::openChatPingMessage));
        buttons.add(button("Item pings...", TomatoGUI::openItemPing));
        buttons.add(button("Enchant pings...", TomatoGUI::openEnchantPing));
        buttons.add(button("Entity pings...", TomatoGUI::openEntityIdPing));
        buttons.add(button("Dungeon pings...", TomatoGUI::openDungeonPing));
        buttons.add(button("Ping sounds...", PingSoundGUI::open));
        body.add(buttons);
        return body;
    }

    private JPanel filterBody() {
        JPanel body = body();
        JPanel hint = row();
        hint.add(new JLabel("Which loot-bag colours appear in the logger."));
        body.add(hint);

        JPanel grid = grid();
        grid.add(filter("White bags", LootBags.WHITE, "filterWhiteBag", true, v -> LootGUI.filterWhiteBag = v));
        grid.add(filter("Orange bags", LootBags.ORANGE, "filterOrangeBag", true, v -> LootGUI.filterOrangeBag = v));
        grid.add(filter("Red bags", LootBags.RED, "filterRedBag", true, v -> LootGUI.filterRedBag = v));
        grid.add(filter("Gold bags", LootBags.GOLD, "filterGoldBag", true, v -> LootGUI.filterGoldBag = v));
        grid.add(filter("Egg bags", LootBags.EGG, "filterEggBag", false, v -> LootGUI.filterEggBag = v));
        grid.add(filter("Blue bags", LootBags.BLUE, "filterBlueBag", false, v -> LootGUI.filterBlueBag = v));
        grid.add(filter("Teal bags", LootBags.TEAL, "filterTealBag", true, v -> LootGUI.filterTealBag = v));
        grid.add(filter("Purple bags", LootBags.PURPLE, "filterPurpleBag", false, v -> LootGUI.filterPurpleBag = v));
        grid.add(filter("Pink bags", LootBags.PINK, "filterPinkBag", false, v -> LootGUI.filterPinkBag = v));
        grid.add(filter("Brown bags", LootBags.BROWN, "filterBrownBag", false, v -> LootGUI.filterBrownBag = v));
        body.add(grid);
        return body;
    }

    private JPanel themeBody() {
        JPanel body = body();
        String current = Themes.normalize(PropertiesManager.getProperty("theme"));
        int selected = java.util.Arrays.asList(Themes.KEYS).indexOf(current);
        Segmented picker = new Segmented(true, selected, i -> {
            Themes.install(Themes.KEYS[i]);
            PropertiesManager.setProperties("theme", Themes.KEYS[i]);
        }, Themes.LABELS);
        picker.setAlignmentX(LEFT_ALIGNMENT);
        body.add(picker);
        return body;
    }

    /**
     * Log files (off by default) and the folder they go to. Viewed and exported under Info > Logs.
     */
    /** Run the Newcomer's Guide again on the next start, as if never seen; switches off once it ran through. */
    private JPanel guideBody() {
        JPanel body = body();
        JPanel hint = row();
        hint.add(new JLabel("The tour through Loot Gobbler. Info > Newcomer's Guide starts the quick tour at any time."));
        body.add(hint);
        JCheckBox next = checkBox("Show the whole guide on the next start, as if never seen", NewcomerGuide.onNextStart());
        next.setToolTipText("Switches off by itself once the guide was finished or skipped");
        next.addActionListener(e -> NewcomerGuide.setOnNextStart(next.isSelected()));
        NewcomerGuide.onDone(() -> SwingUtilities.invokeLater(() -> next.setSelected(NewcomerGuide.onNextStart())));
        JPanel nextRow = row();
        nextRow.add(next);
        body.add(nextRow);
        return body;
    }

    private JPanel loggingBody() {
        JPanel body = body();
        JPanel hint = row();
        hint.add(new JLabel("Writes what the app reports, errors included, to one file per day. View and export them under Info > Logs."));
        body.add(hint);

        JCheckBox on = checkBox("Write log files", lootgobbler.AppLog.enabled());
        on.addActionListener(e -> lootgobbler.AppLog.setEnabled(on.isSelected()));
        JPanel onRow = row();
        onRow.add(on);
        body.add(onRow);

        JLabel folder = new JLabel(lootgobbler.AppLog.dir().toString());
        JPanel dirRow = row();
        dirRow.add(new JLabel("Folder"));
        dirRow.add(Box.createHorizontalStrut(12));
        dirRow.add(folder);
        dirRow.add(Box.createHorizontalStrut(8));
        dirRow.add(button("Change...", () -> {
            JFileChooser chooser = new JFileChooser(lootgobbler.AppLog.dir().toFile());
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setDialogTitle("Folder for log files");
            if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
            lootgobbler.AppLog.setDir(chooser.getSelectedFile().toPath());
            folder.setText(lootgobbler.AppLog.dir().toString());
        }));
        dirRow.add(button("Default", () -> {
            lootgobbler.AppLog.setDir(lootgobbler.AppLog.defaultDir());
            folder.setText(lootgobbler.AppLog.dir().toString());
        }));
        body.add(dirRow);

        JSpinner keep = new JSpinner(new SpinnerNumberModel(lootgobbler.AppLog.keepDays(), 0, 3650, 1));
        keep.addChangeListener(e -> lootgobbler.AppLog.setKeepDays((Integer) keep.getValue()));
        JLabel keepHint = new JLabel("days   (0 = keep all logs)");
        keepHint.setForeground(Palette.MUTED);
        JPanel keepRow = row();
        keepRow.add(new JLabel("Keep logs for"));
        keepRow.add(Box.createHorizontalStrut(12));
        keepRow.add(keep);
        keepRow.add(keepHint);
        keepRow.setToolTipText("Older log files are deleted when the app starts and at midnight. Default: "
                + lootgobbler.AppLog.DEFAULT_KEEP_DAYS + " days.");
        body.add(keepRow);
        return body;
    }

    private JCheckBox toggle(String text, String key, boolean byDefault, Consumer<Boolean> apply, Sound preview) {
        JCheckBox box = checkBox(text, boolProperty(key, byDefault));
        box.addActionListener(e -> {
            boolean on = box.isSelected();
            PropertiesManager.setProperties(key, Boolean.toString(on));
            apply.accept(on);
            preview.play();   // every click plays the sound, so you can hear it before deciding
        });
        return box;
    }

    /**
     * Check box followed by the bag's sprite and name; clicking the sprite or name toggles it too.
     */
    private JComponent filter(String text, LootBags bag, String key, boolean byDefault, Consumer<Boolean> apply) {
        JCheckBox box = checkBox("", boolProperty(key, byDefault));
        box.addActionListener(e -> {
            PropertiesManager.setProperties(key, Boolean.toString(box.isSelected()));
            apply.accept(box.isSelected());
            LootGUI.applyFilters();
        });
        JLabel label = new JLabel(text, bagIcon(bag), SwingConstants.LEFT);
        label.setIconTextGap(6);
        label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        Clicks.onClick(label, () -> box.doClick());
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        row.setOpaque(false);
        row.add(box);
        row.add(label);
        return row;
    }

    private static Icon bagIcon(LootBags bag) {
        try {
            return ImageBuffer.getOutlinedIcon(bag.getId(), 22);
        } catch (Exception e) {
            return null;   // assets not extracted yet
        }
    }

    private static JCheckBox checkBox(String text, boolean selected) {
        JCheckBox box = new JCheckBox(text, selected);
        box.setOpaque(false);
        box.setFont(UIManager.getFont("Label.font"));
        return box;
    }

    private static JButton button(String text, Runnable action) {
        JButton b = new JButton(text.toUpperCase(java.util.Locale.ROOT));
        b.addActionListener(e -> action.run());
        return b;
    }

    private static JPanel body() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(new EmptyBorder(10, 12, 12, 12));
        return p;
    }

    /** Behind an item sound's name: down (red) when the item broke, up (green) when it is charged again. */
    private enum Arrow implements Icon {
        BROKEN(new Color(0xE0533D), false), RECHARGED(new Color(0x4CAF50), true);

        private final Color color;
        private final boolean up;

        Arrow(Color color, boolean up) {
            this.color = color;
            this.up = up;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.translate(x, y);
            if (!up) g2.rotate(Math.PI, 7, 9);
            java.awt.geom.Path2D p = new java.awt.geom.Path2D.Double();
            p.moveTo(7, 1);
            p.lineTo(13, 8);
            p.lineTo(9.5, 8);
            p.lineTo(9.5, 17);
            p.lineTo(4.5, 17);
            p.lineTo(4.5, 8);
            p.lineTo(1, 8);
            p.closePath();
            g2.setColor(color);
            g2.fill(p);
            g2.setColor(color.darker().darker());
            g2.setStroke(new BasicStroke(1f));
            g2.draw(p);
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return 14;
        }

        @Override
        public int getIconHeight() {
            return 18;
        }
    }

    private static JPanel row() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        p.setOpaque(false);
        p.setAlignmentX(LEFT_ALIGNMENT);
        return p;
    }

    private static JPanel grid() {
        JPanel p = new JPanel(new GridLayout(0, 3, 24, 4));
        p.setOpaque(false);
        p.setAlignmentX(LEFT_ALIGNMENT);
        p.setBorder(new EmptyBorder(4, 6, 8, 6));
        return p;
    }

    private static boolean boolProperty(String key, boolean byDefault) {
        String v = PropertiesManager.getProperty(key);
        return v == null ? byDefault : v.equals("true");
    }

    private static int intProperty(String key, int byDefault) {
        try {
            return Integer.parseInt(PropertiesManager.getProperty(key));
        } catch (Exception e) {
            return byDefault;
        }
    }
}
