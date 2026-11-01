package lootgobbler.ui;

import lootgobbler.CharacterStore;
import lootgobbler.CharacterStore.Char;
import lootgobbler.Themes;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.text.NumberFormat;
import java.util.Objects;
import java.util.function.IntConsumer;

/**
 * Profile tab: your player name and your recently most played character, plus the pages passed in (My Info,
 * Dungeon Stats).
 */
public class ProfileView extends SubTabs {
    private static final NumberFormat NUM = NumberFormat.getIntegerInstance();

    private final JPanel body = new JPanel();
    private String shown;   // what is on screen, so the frequent store updates only rebuild on a real change

    private final IntConsumer openCharacter;   // shows a character in the Characters tab

    public ProfileView(JComponent myInfo, JComponent dungeonStats, IntConsumer openCharacter) {
        this.openCharacter = openCharacter;
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setOpaque(false);
        body.setBorder(new EmptyBorder(16, 20, 20, 20));
        JPanel holder = new JPanel(new BorderLayout());   // keeps the content at its natural height
        holder.setOpaque(false);
        holder.add(body, BorderLayout.NORTH);
        page("Overview", new Section("Profile", Palette.GOLD, FitWidthPanel.scroll(holder, 24)));
        page("My Info", myInfo);
        page("Dungeon Stats", dungeonStats);
        done();

        CharacterStore.get().onChange(() -> SwingUtilities.invokeLater(this::refresh));
        refresh();
    }

    private void refresh() {
        CharacterStore store = CharacterStore.get();
        String name = store.playerName();
        Char c = store.mostPlayed();
        long gain = c == null ? 0 : store.gainedToday(c);
        String state = Objects.hash(name, c == null ? 0 : c.charId, c == null ? 0 : c.skin, c == null ? 0 : c.level,
                c == null ? 0 : c.fame, gain, c == null ? 0 : CharactersView.ago(c.lastPlayed)) + "";
        if (state.equals(shown)) return;
        shown = state;

        body.removeAll();
        JLabel player = new JLabel(name != null ? name : "Unknown player");
        player.setFont(new Font(Themes.HEAD_FONT, Font.PLAIN, 46));
        player.setForeground(Section.color("LootGobbler.gold", Palette.GOLD));
        body.add(left(player));
        if (name == null) body.add(left(muted("Start the sniffer and enter the game once to show your name.")));

        body.add(Box.createVerticalStrut(24));
        JLabel caption = new JLabel("MOST PLAYED CHARACTER");
        caption.putClientProperty("FlatLaf.styleClass", "small");
        body.add(left(caption));
        body.add(Box.createVerticalStrut(6));
        body.add(left(c != null ? character(c, gain) : muted("Log in once so your character list is loaded.")));
        body.revalidate();
        body.repaint();
    }

    /**
     * The character's skin, class and progress, and why it was picked: fame gained today, or played most recently.
     */
    private JComponent character(Char c, long gain) {
        JLabel sprite = new JLabel(CharactersView.skin(c, 96));

        JLabel cls = new JLabel(c.className);
        cls.setFont(cls.getFont().deriveFont(30f));
        JLabel progress = new JLabel(c.level < 20 ? "Level " + c.level : NUM.format(c.fame) + " Fame");
        progress.setFont(progress.getFont().deriveFont(20f));
        progress.setForeground(Section.color("LootGobbler.gold", Palette.GOLD));
        JLabel why = gain > 0 ? new JLabel("+" + NUM.format(gain) + " fame in the last 24h")
                : muted(c.lastPlayed == 0 ? "not played yet" : "last played " + CharactersView.ago(c.lastPlayed));
        why.setFont(why.getFont().deriveFont(gain > 0 ? Font.BOLD : Font.PLAIN, 15f));
        if (gain > 0) why.setForeground(Palette.GREEN);

        JPanel text = new JPanel(new GridLayout(3, 1));
        text.setOpaque(false);
        text.add(cls);
        text.add(progress);
        text.add(why);

        JPanel card = new JPanel(new BorderLayout(16, 0));
        card.setOpaque(false);
        card.add(sprite, BorderLayout.WEST);
        card.add(text, BorderLayout.CENTER);
        card.setToolTipText("Open in Characters");
        card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        int id = c.charId;
        card.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                openCharacter.accept(id);
            }
        });
        return card;
    }

    private static JComponent left(JComponent c) {
        c.setAlignmentX(LEFT_ALIGNMENT);
        return c;
    }

    private static JLabel muted(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(Palette.MUTED);
        return l;
    }
}
