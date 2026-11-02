package lootgobbler.ui;

import tomato.gui.SmartScroller;
import util.PropertiesManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.util.EnumSet;
import java.util.Locale;

/**
 * Unified chat log: one striped list (time, channel, name: text) plus a Channels sidebar
 * with Clear Chat and per-channel visibility toggles (saved in settings).
 * add/clear/setLogFont are safe to call from any thread.
 */
public class ChatLogPanel extends JPanel {
    public enum Channel {
        GUILD("Guild", Palette.BLUE),
        PARTY("Party", Palette.GREEN),
        PM("PM", Palette.GOLD),
        PUBLIC("Public", Palette.STONE),
        SYSTEM("System", Palette.RED);

        final String label;
        final Color color;

        Channel(String label, Color color) {
            this.label = label;
            this.color = color;
        }
    }

    private record Line(Channel channel, String name, String text) {
        @Override
        public String toString() {
            return name.isEmpty() ? text : name + ": " + text;
        }
    }

    private static final int MAX_LINES = 5000;
    private static final int COL_TIME = 0, COL_CHANNEL = 1, COL_MESSAGE = 2;

    private final DefaultTableModel model = new DefaultTableModel(new Object[]{"Time", "Channel", "Message"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable table = new JTable(model);
    private final TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(model);
    private final EnumSet<Channel> visible = EnumSet.allOf(Channel.class);

    public ChatLogPanel() {
        super(new BorderLayout(8, 0));
        setBorder(new EmptyBorder(8, 8, 8, 8));

        table.setTableHeader(null);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setFillsViewportHeight(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        table.setDefaultRenderer(Object.class, new LineRenderer());
        fixWidth(COL_TIME, 80);
        fixWidth(COL_CHANNEL, 80);

        for (int i = 0; i < model.getColumnCount(); i++) sorter.setSortable(i, false);
        sorter.setRowFilter(new RowFilter<>() {
            @Override
            public boolean include(Entry<? extends DefaultTableModel, ? extends Integer> e) {
                return visible.contains(((Line) e.getValue(COL_CHANNEL)).channel);
            }
        });
        table.setRowSorter(sorter);

        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(new EmptyBorder(6, 6, 6, 6));
        new SmartScroller(scroll);

        add(new Section("Chat Log", Palette.BLUE, scroll), BorderLayout.CENTER);
        add(new Section("Channels", Palette.PURPLE, sidebar()), BorderLayout.EAST);
    }

    public void add(String time, Channel channel, String name, String text) {
        Line line = new Line(channel, name == null ? "" : name, text == null ? "" : text);
        SwingUtilities.invokeLater(() -> {
            model.addRow(new Object[]{time, line, line});
            if (model.getRowCount() > MAX_LINES) model.removeRow(0);
        });
    }

    public void clear() {
        SwingUtilities.invokeLater(() -> model.setRowCount(0));
    }

    public void setLogFont(Font font) {
        SwingUtilities.invokeLater(() -> {
            table.setFont(font);
            table.setRowHeight(table.getFontMetrics(font).getHeight() + 8);
        });
    }

    private JPanel sidebar() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(new EmptyBorder(10, 12, 12, 12));

        JLabel hint = new JLabel("Show channels:");
        hint.setAlignmentX(LEFT_ALIGNMENT);
        p.add(hint);
        p.add(Box.createVerticalStrut(8));

        JButton clear = new JButton("CLEAR CHAT");
        clear.putClientProperty("FlatLaf.styleClass", "danger");
        clear.setAlignmentX(LEFT_ALIGNMENT);
        clear.addActionListener(e -> clear());
        p.add(clear);
        p.add(Box.createVerticalStrut(12));

        for (Channel ch : new Channel[]{Channel.GUILD, Channel.PARTY, Channel.PM, Channel.PUBLIC}) {
            p.add(channelRow(ch));
            p.add(Box.createVerticalStrut(6));
        }
        return p;
    }

    private JPanel channelRow(Channel ch) {
        String key = "chatShow" + ch.label;
        boolean shown = !"false".equals(PropertiesManager.getProperty(key));
        if (!shown) visible.remove(ch);

        JCheckBox box = new JCheckBox();
        box.setOpaque(false);
        box.setSelected(shown);
        box.addActionListener(e -> {
            if (box.isSelected()) visible.add(ch);
            else visible.remove(ch);
            PropertiesManager.setProperties(key, Boolean.toString(box.isSelected()));
            sorter.allRowsChanged();
        });

        JLabel label = new JLabel(ch.label.toUpperCase(Locale.ROOT), new Section.Square(ch.color, 8), SwingConstants.LEFT);
        label.setIconTextGap(10);
        Font f = UIManager.getFont("TabbedPane.font");
        if (f != null) label.setFont(f);

        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6)) {
            @Override
            public void updateUI() {
                super.updateUI();
                setBackground(Section.color("TableHeader.background", getBackground()));
                setBorder(new LineBorder(Section.color("Component.borderColor", Color.GRAY)));
            }
        };
        row.add(box);
        row.add(label);
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        row.setPreferredSize(new Dimension(190, row.getPreferredSize().height));
        row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        Clicks.onClick(row, () -> box.doClick());
        return row;
    }

    private void fixWidth(int col, int width) {
        table.getColumnModel().getColumn(col).setMinWidth(width);
        table.getColumnModel().getColumn(col).setMaxWidth(width);
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static class LineRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                       boolean focus, int row, int column) {
            super.getTableCellRendererComponent(table, value, selected, false, row, column);
            setBorder(new EmptyBorder(0, 10, 0, 10));
            setToolTipText(null);
            if (!selected) {
                Color alt = UIManager.getColor("Table.alternateRowColor");
                setBackground(row % 2 == 1 && alt != null ? alt : table.getBackground());
                setForeground(table.getForeground());
            }

            if (column == COL_TIME) {
                setFont(table.getFont().deriveFont(table.getFont().getSize2D() - 2f));
                if (!selected) setForeground(Palette.MUTED);
            } else if (column == COL_CHANNEL) {
                Line line = (Line) value;
                Font f = UIManager.getFont("TabbedPane.font");
                setFont(f != null ? f : table.getFont());
                setText(line.channel.label.toUpperCase(Locale.ROOT));
                if (!selected) setForeground(line.channel.color);
            } else {
                Line line = (Line) value;
                setFont(table.getFont());
                String text = escape(line.text);
                setText(line.name.isEmpty() ? "<html>" + text + "</html>"
                        : "<html><b>" + escape(line.name) + "</b>: " + text + "</html>");
                setToolTipText(line.toString());
            }
            return this;
        }
    }
}
