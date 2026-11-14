package lootgobbler.ui;

import lootgobbler.Themes;
import util.PropertiesManager;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.event.KeyEvent;

/**
 * Greeting shown once, the first time Loot Gobbler starts: the app icon and a hello from the author.
 */
public class WelcomeDialog extends JDialog {
    private static final String KEY_SHOWN = "welcomeShown";

    public WelcomeDialog(Window owner) {
        super(owner, "Welcome to Loot Gobbler", ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        JPanel box = new JPanel(new BorderLayout(28, 18));
        box.setBackground(ui("paper"));
        box.setBorder(new CompoundBorder(new LineBorder(ui("line"), 2), new EmptyBorder(28, 32, 24, 32)));

        Image icon = AboutDialog.appIcon(128);   // 2x the 64px pixel art, so every pixel stays square
        if (icon != null) {
            JLabel goblin = new JLabel(new ImageIcon(icon));
            goblin.setVerticalAlignment(SwingConstants.TOP);
            box.add(goblin, BorderLayout.WEST);
        }

        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        JLabel hello = new JLabel("Hello fellow realmer!");
        hello.setFont(new Font(Themes.HEAD_FONT, Font.PLAIN, 46));
        hello.setForeground(ui("gold"));
        JLabel message = new JLabel("<html><body style='width:330px'>I am here to be by your side and help you better"
                + " understand your game. If you have questions, contact me (Gunshit) on Discord</body></html>");
        message.setFont(new Font(Themes.BODY_FONT, Font.PLAIN, 25));
        message.setForeground(ui("ink"));
        JLabel start = new JLabel("<html><body style='width:330px'>When you're ready to start, click \"Start Sniffer\""
                + " in the bottom right and start your game</body></html>");
        start.setFont(message.getFont());
        start.setForeground(ui("ink"));
        text.add(Box.createVerticalStrut(6));
        text.add(hello);
        text.add(Box.createVerticalStrut(8));
        text.add(message);
        text.add(Box.createVerticalStrut(18));
        text.add(start);
        box.add(text, BorderLayout.CENTER);

        JButton ok = new JButton("LET'S GO");
        ok.putClientProperty("FlatLaf.styleClass", "primary");
        ok.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        buttons.setOpaque(false);
        buttons.add(ok);
        box.add(buttons, BorderLayout.SOUTH);

        JPanel outer = new JPanel(new BorderLayout());
        outer.setBorder(new EmptyBorder(16, 16, 16, 16));
        outer.add(box);
        setContentPane(outer);
        getRootPane().setDefaultButton(ok);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        pack();
        setLocationRelativeTo(owner);
    }

    private static Color ui(String key) {
        Color c = UIManager.getColor("LootGobbler." + key);
        return c != null ? c : Color.GRAY;
    }

    /**
     * Shows the greeting if it has never been shown, and remembers that it was. Returns whether it was shown (the
     * very first start).
     */
    public static boolean showOnFirstStart(Window owner) {
        if ("true".equals(PropertiesManager.getProperty(KEY_SHOWN))) return false;
        PropertiesManager.setProperties(KEY_SHOWN, "true");
        new WelcomeDialog(owner).setVisible(true);
        return true;
    }
}
