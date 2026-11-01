package lootgobbler.ui;

import lootgobbler.Discord;
import lootgobbler.DiscordAlerts;
import lootgobbler.Webhooks;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Discord tab: the list of webhooks loot cards are posted to, with add / edit / copy / remove and a test button.
 */
public class DiscordPanel extends JPanel {
    private final JPanel rows = new JPanel();
    private final List<Webhooks.Hook> drafts = new ArrayList<>();
    private final Set<Webhooks.Hook> editing = new HashSet<>();
    private final JLabel feedback = new JLabel(" ");

    public DiscordPanel() {
        super(new BorderLayout());
        setBorder(new EmptyBorder(8, 8, 8, 8));

        JButton test = button("Send test loot", null, this::sendTest);
        JButton add = button("+ Add webhook", "primary", this::addDraft);

        JLabel intro = new JLabel("<html>Each UT or ST item that drops for you is posted as a "
                + "rendered loot card to every webhook below.<br>In Discord: Edit Channel -&gt; Integrations -&gt; Webhooks "
                + "-&gt; New Webhook -&gt; Copy Webhook URL, then paste it into a row and Save. Add an optional name to "
                + "identify the Discord.</html>");
        intro.setAlignmentX(LEFT_ALIGNMENT);

        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        rows.setOpaque(false);
        rows.setAlignmentX(LEFT_ALIGNMENT);

        JButton addAnother = button("+ Add another webhook", null, this::addDraft);
        addAnother.setAlignmentX(LEFT_ALIGNMENT);
        addAnother.setMaximumSize(new Dimension(Integer.MAX_VALUE, addAnother.getPreferredSize().height + 4));
        feedback.setAlignmentX(LEFT_ALIGNMENT);

        JPanel col = new JPanel();
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.setOpaque(false);
        col.setBorder(new EmptyBorder(10, 12, 12, 12));
        col.add(intro);
        col.add(Box.createVerticalStrut(10));
        col.add(rows);
        col.add(addAnother);
        col.add(Box.createVerticalStrut(8));
        col.add(feedback);

        JPanel body = new JPanel(new BorderLayout());
        body.add(col, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(body);
        sp.setBorder(null);
        sp.getViewport().setOpaque(false);
        add(new Section("Webhooks", Palette.PURPLE, wrap(sp)).actions(test, add));

        Webhooks.get().onChange(() -> SwingUtilities.invokeLater(this::rebuild));
        rebuild();
    }

    private void rebuild() {
        rows.removeAll();
        for (Webhooks.Hook h : Webhooks.get().all()) {
            rows.add(editing.contains(h) ? editRow(h, false) : savedRow(h));
            rows.add(Box.createVerticalStrut(8));
        }
        for (Webhooks.Hook h : drafts) {
            rows.add(editRow(h, true));
            rows.add(Box.createVerticalStrut(8));
        }
        rows.revalidate();
        rows.repaint();
    }

    private JPanel savedRow(Webhooks.Hook h) {
        Color dot;
        String status;
        Color statusColor;
        switch (h.status()) {
            case CONNECTED -> {
                dot = Palette.GREEN;
                status = "Connected · " + h.detail();
                statusColor = Palette.GREEN;
            }
            case FAILED -> {
                dot = Palette.RED;
                status = h.detail();
                statusColor = Palette.RED;
            }
            case CHECKING -> {
                dot = Palette.MUTED;
                status = "Checking...";
                statusColor = Palette.MUTED;
            }
            default -> {
                dot = Palette.MUTED;
                status = "Not checked";
                statusColor = Palette.MUTED;
            }
        }
        JLabel title = new JLabel(h.title(), new Section.Square(dot, 10), SwingConstants.LEFT);
        title.setIconTextGap(12);
        title.setFont(title.getFont().deriveFont(18f));
        JLabel sub = new JLabel(status);
        sub.setForeground(statusColor);
        sub.setBorder(new EmptyBorder(0, 22, 0, 0));
        JPanel text = new JPanel(new GridLayout(2, 1));
        text.setOpaque(false);
        text.add(title);
        text.add(sub);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.setOpaque(false);
        buttons.add(button("Edit", null, () -> {
            editing.add(h);
            rebuild();
        }));
        buttons.add(button("Copy URL", null, () -> {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(h.url), null);
            feedback.setText("Webhook URL copied.");
        }));
        buttons.add(button("Remove", "danger", () -> {
            int ok = JOptionPane.showConfirmDialog(this, "Remove \"" + h.title() + "\"?", "Remove webhook",
                    JOptionPane.YES_NO_OPTION);
            if (ok == JOptionPane.YES_OPTION) Webhooks.get().remove(h);
        }));

        JPanel row = row(true);
        row.add(text, BorderLayout.CENTER);
        row.add(buttons, BorderLayout.EAST);
        return finish(row);
    }

    private JPanel editRow(Webhooks.Hook h, boolean draft) {
        JTextField url = new JTextField(h.url);
        url.putClientProperty("JTextField.placeholderText", "Paste Discord webhook URL");
        JTextField name = new JTextField(h.name, 14);
        name.putClientProperty("JTextField.placeholderText", "Discord name (optional)");

        JPanel fields = new JPanel(new BorderLayout(6, 0));
        fields.setOpaque(false);
        fields.add(url, BorderLayout.CENTER);
        fields.add(name, BorderLayout.EAST);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.setOpaque(false);
        buttons.add(button("Save", "primary", () -> {
            String u = url.getText().trim();
            if (!Discord.isWebhookUrl(u)) {
                feedback.setText("That is not a Discord webhook URL (https://discord.com/api/webhooks/...).");
                return;
            }
            drafts.remove(h);
            editing.remove(h);
            feedback.setText(" ");
            Webhooks.get().put(h, u, name.getText());
        }));
        buttons.add(button("Remove", "danger", () -> {
            drafts.remove(h);
            editing.remove(h);
            if (!draft) Webhooks.get().remove(h);
            rebuild();
        }));

        JPanel row = row(false);
        row.add(fields, BorderLayout.CENTER);
        row.add(buttons, BorderLayout.EAST);
        return finish(row);
    }

    private void addDraft() {
        drafts.add(new Webhooks.Hook());
        rebuild();
    }

    private void sendTest() {
        feedback.setText("Sending test loot...");
        DiscordAlerts.sendTest(err -> SwingUtilities.invokeLater(() ->
                feedback.setText(err == null ? "Test loot sent." : "Test failed: " + err)));
    }

    /**
     * Bordered row; saved rows use the header color, edit rows the page color.
     */
    private static JPanel row(boolean filled) {
        JPanel row = new JPanel(new BorderLayout(10, 0)) {
            @Override
            public void updateUI() {
                super.updateUI();
                setBackground(Section.color(filled ? "TableHeader.background" : "TextArea.background", getBackground()));
                setBorder(BorderFactory.createCompoundBorder(
                        new LineBorder(Section.color("Component.borderColor", Color.GRAY)),
                        new EmptyBorder(6, 10, 6, 8)));
            }
        };
        row.setAlignmentX(LEFT_ALIGNMENT);
        return row;
    }

    private static JPanel finish(JPanel row) {
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        return row;
    }

    private static JPanel wrap(JComponent c) {
        JPanel p = new JPanel(new BorderLayout());
        p.add(c);
        return p;
    }

    private static JButton button(String text, String style, Runnable action) {
        JButton b = new JButton(text.toUpperCase(Locale.ROOT));
        if (style != null) b.putClientProperty("FlatLaf.styleClass", style);
        b.setFocusable(false);
        b.addActionListener(e -> action.run());
        return b;
    }
}
