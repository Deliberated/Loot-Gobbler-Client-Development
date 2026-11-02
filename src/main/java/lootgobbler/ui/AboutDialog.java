package lootgobbler.ui;

import lootgobbler.Themes;
import lootgobbler.Updater;
import tomato.Tomato;
import tomato.version.Version;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;

/**
 * Info -> About: the app icon, name, author, a tagline and credits. The full license texts ship as files.
 */
public class AboutDialog extends JDialog {

    public AboutDialog(Window owner) {
        super(owner, "About Loot Gobbler", ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        JPanel box = new JPanel(new BorderLayout(0, 18));
        box.setBackground(ui("paper"));
        box.setBorder(new CompoundBorder(new LineBorder(ui("line"), 2), new EmptyBorder(28, 32, 24, 32)));

        // header: icon, then name and author
        JPanel head = new JPanel(new FlowLayout(FlowLayout.LEFT, 20, 0));
        head.setOpaque(false);
        Image icon = appIcon(128);   // 2x the 64px pixel art, so every pixel stays square
        if (icon != null) {
            JLabel goblin = new JLabel(new ImageIcon(icon));
            goblin.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            goblin.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mousePressed(java.awt.event.MouseEvent e) {
                    tomato.realmshark.Sound.goblinCackle.play();   // poke the goblin, it cackles
                }
            });
            head.add(goblin);
        }
        JPanel names = new JPanel();
        names.setOpaque(false);
        names.setLayout(new BoxLayout(names, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("Loot Gobbler");
        title.setFont(new Font(Themes.HEAD_FONT, Font.PLAIN, 54));
        title.setForeground(ui("gold"));
        JLabel by = new JLabel("by Gunshit");
        by.setFont(new Font(Themes.BODY_FONT, Font.PLAIN, 30));
        by.setForeground(ui("ink"));
        JLabel version = new JLabel(Version.VERSION);
        version.setFont(new Font(Themes.BODY_FONT, Font.PLAIN, 20));
        version.setForeground(ui("muted"));
        names.add(Box.createVerticalStrut(10));
        names.add(title);
        names.add(by);
        names.add(Box.createVerticalStrut(6));
        names.add(version);
        head.add(names);
        box.add(head, BorderLayout.NORTH);

        JPanel info = new JPanel();
        info.setOpaque(false);
        info.setLayout(new BoxLayout(info, BoxLayout.Y_AXIS));
        info.setBorder(new EmptyBorder(6, 4, 10, 4));
        JLabel tagline = new JLabel("Your right hand for your RotMG adventures!");
        tagline.setFont(new Font(Themes.BODY_FONT, Font.PLAIN, 27));
        tagline.setForeground(ui("ink"));
        info.add(tagline);
        JLabel author = new JLabel("designed and developed for the Pluribus Guild");
        author.setFont(new Font(Themes.BODY_FONT, Font.PLAIN, 21));
        author.setForeground(ui("ink"));
        info.add(author);
        info.add(Box.createVerticalStrut(14));
        for (String line : new String[]{"Built on RealmShark's packet API (X-com)", "MIT license"}) {
            JLabel l = new JLabel(line);
            l.setFont(new Font(Themes.BODY_FONT, Font.PLAIN, 21));
            l.setForeground(ui("muted"));
            info.add(l);
            info.add(Box.createVerticalStrut(2));
        }
        box.add(info, BorderLayout.CENTER);

        JButton update = new JButton("CHECK FOR UPDATES");
        update.addActionListener(e -> Updater.check(this));
        JButton close = new JButton("CLOSE");
        close.putClientProperty("FlatLaf.styleClass", "primary");
        close.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        buttons.setOpaque(false);
        buttons.add(update);
        buttons.add(close);
        box.add(buttons, BorderLayout.SOUTH);

        JPanel outer = new JPanel(new BorderLayout());
        outer.setBorder(new EmptyBorder(16, 16, 16, 16));
        outer.add(box);
        setContentPane(outer);
        getRootPane().setDefaultButton(close);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        pack();
        setLocationRelativeTo(owner);
    }

    /**
     * The app icon scaled with hard pixel edges, or null if missing.
     */
    static Image appIcon(int size) {
        try {
            Image src = lootgobbler.AppIcon.load(size <= 64 ? 64 : 256);
            if (src == null) return null;
            BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(src, 0, 0, size, size, null);
            g.dispose();
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private static Color ui(String key) {
        Color c = UIManager.getColor("LootGobbler." + key);
        return c != null ? c : Color.GRAY;
    }

    public static void show(Window owner) {
        new AboutDialog(owner).setVisible(true);
    }
}
