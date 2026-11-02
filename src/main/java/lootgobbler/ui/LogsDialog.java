package lootgobbler.ui;

import lootgobbler.AppLog;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Info -> Logs: the log files, one per day (newest first), with export. When logging is off it says where to turn
 * it on instead.
 */
public class LogsDialog extends JDialog {
    private static final long MAX_SHOWN = 2_000_000;   // bytes; bigger files show their end

    private final JComboBox<Path> day = new JComboBox<>();
    private final JTextArea text = new JTextArea();

    private LogsDialog(Window owner) {
        super(owner, "Logs", ModalityType.MODELESS);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        day.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                Object name = value instanceof Path p ? p.getFileName().toString() : value;
                return super.getListCellRendererComponent(list, name, index, selected, focus);
            }
        });
        day.addActionListener(e -> show((Path) day.getSelectedItem()));

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        top.add(new JLabel("Day"));
        top.add(day);
        top.add(button("Refresh", this::reload));
        top.add(button("Export...", this::export));
        top.add(button("Open folder", this::openFolder));

        text.setEditable(false);
        text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        JScrollPane scroll = new JScrollPane(text);

        JLabel where = new JLabel("Saved in " + AppLog.dir() + "  (change it in Settings > Logging)");
        where.setForeground(Palette.MUTED);

        JPanel box = new JPanel(new BorderLayout(0, 8));
        box.setBorder(new EmptyBorder(10, 10, 10, 10));
        box.add(top, BorderLayout.NORTH);
        box.add(scroll, BorderLayout.CENTER);
        box.add(where, BorderLayout.SOUTH);
        setContentPane(box);
        setSize(960, 640);
        setLocationRelativeTo(owner);
        reload();
    }

    public static void open(Window owner) {
        if (!AppLog.enabled()) {
            JOptionPane.showMessageDialog(owner, "Logging is turned off.\nTurn it on in Settings > Logging; "
                    + "from then on, everything the app reports is written to log files you can view here.",
                    "Logs", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        new LogsDialog(owner).setVisible(true);
    }

    private void reload() {
        Object was = day.getSelectedItem();
        List<Path> files = AppLog.files();
        day.removeAllItems();
        files.forEach(day::addItem);
        if (files.isEmpty()) {
            text.setText("No logs yet. They are written as the app runs.");
            return;
        }
        day.setSelectedItem(was != null && files.contains(was) ? was : files.get(0));
        show((Path) day.getSelectedItem());
    }

    private void show(Path file) {
        if (file == null) return;
        try {
            byte[] all = Files.readAllBytes(file);
            int from = (int) Math.max(0, all.length - MAX_SHOWN);
            String s = new String(all, from, all.length - from, StandardCharsets.UTF_8);
            text.setText(from > 0 ? "... (start cut off, export the file to see all of it)\n" + s : s);
            text.setCaretPosition(text.getDocument().getLength());   // newest at the bottom, in view
        } catch (IOException e) {
            text.setText("Could not read " + file + ": " + e.getMessage());
        }
    }

    private void export() {
        Path file = (Path) day.getSelectedItem();
        if (file == null) return;
        AppLog.flush();
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(file.getFileName().toFile());
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            Files.copy(file, chooser.getSelectedFile().toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Could not export: " + e.getMessage(), "Logs", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void openFolder() {
        try {
            Files.createDirectories(AppLog.dir());
            Desktop.getDesktop().open(AppLog.dir().toFile());
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Could not open " + AppLog.dir() + ": " + e.getMessage(), "Logs", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static JButton button(String label, Runnable action) {
        JButton b = new JButton(label);
        b.addActionListener(e -> action.run());
        return b;
    }
}
