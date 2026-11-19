package jsonlogviewer;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JColorChooser;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;

/** Ansicht → Einstellungen…: Darstellung der Zeilen je Loglevel (fett, kursiv, Schriftfarbe). */
final class SettingsDialog extends JDialog {

    private final JCheckBox[] bold = new JCheckBox[LogLevels.count() + 1];
    private final JCheckBox[] italic = new JCheckBox[LogLevels.count() + 1];
    private final Color[] colors = new Color[LogLevels.count() + 1];
    private final JButton[] colorButtons = new JButton[LogLevels.count() + 1];
    private final JLabel[] previews = new JLabel[LogLevels.count() + 1];
    private boolean changed;

    private SettingsDialog(JFrame owner) {
        super(owner, "Einstellungen", true);
        Settings s = Settings.get();

        JPanel grid = new JPanel(new GridBagLayout());
        grid.setBorder(BorderFactory.createTitledBorder("Darstellung der Zeilen je Loglevel"));
        String[] heads = {"Level", "Fett", "Kursiv", "Schriftfarbe", "", "Vorschau"};
        for (int i = 0; i < heads.length; i++) grid.add(bold(new JLabel(heads[i])), gbc(i, 0));
        for (int r = LogLevels.count(); r >= 1; r--) { // schwerste oben
            int rank = r, y = LogLevels.count() - r + 1;
            Settings.LevelStyle st = s.levelStyle(rank);
            bold[rank] = new JCheckBox("", st.bold());
            italic[rank] = new JCheckBox("", st.italic());
            colors[rank] = st.foreground();
            colorButtons[rank] = new JButton();
            colorButtons[rank].setToolTipText("Schriftfarbe wählen");
            colorButtons[rank].addActionListener(e -> {
                Color c = JColorChooser.showDialog(this, "Schriftfarbe " + LogLevels.label(rank),
                        colors[rank] != null ? colors[rank] : Color.BLACK);
                if (c != null) { colors[rank] = c; update(rank); }
            });
            JButton reset = new JButton("Farbe entfernen");
            reset.setMargin(new Insets(1, 4, 1, 4));
            reset.setToolTipText("Normale Schriftfarbe");
            reset.addActionListener(e -> { colors[rank] = null; update(rank); });
            previews[rank] = new JLabel("  12:00:00  " + LogLevels.GROUPS[rank - 1][0] + "  Beispielnachricht  ");
            previews[rank].setOpaque(true);
            previews[rank].setBackground(Color.WHITE);
            previews[rank].setBorder(BorderFactory.createLineBorder(new Color(0xDDDDDD)));
            bold[rank].addActionListener(e -> update(rank));
            italic[rank].addActionListener(e -> update(rank));
            grid.add(new JLabel(LogLevels.label(rank)), gbc(0, y));
            grid.add(bold[rank], gbc(1, y));
            grid.add(italic[rank], gbc(2, y));
            grid.add(colorButtons[rank], gbc(3, y));
            grid.add(reset, gbc(4, y));
            grid.add(previews[rank], gbc(5, y));
            update(rank);
        }

        JLabel where = new JLabel("<html><small>Gespeichert in " + s.file() + "</small></html>");
        where.setForeground(Color.GRAY);
        where.setBorder(BorderFactory.createEmptyBorder(4, 8, 0, 8));

        JButton defaults = new JButton("Standard wiederherstellen");
        defaults.addActionListener(e -> {
            for (int r = 1; r <= LogLevels.count(); r++) { bold[r].setSelected(false); italic[r].setSelected(false); colors[r] = null; }
            bold[LogLevels.rank("WARN")].setSelected(true);
            bold[LogLevels.rank("ERROR")].setSelected(true);
            bold[LogLevels.rank("FATAL")].setSelected(true);
            colors[LogLevels.rank("TRACE")] = new Color(0x606060);
            for (int r = 1; r <= LogLevels.count(); r++) update(r);
        });
        JButton ok = new JButton("OK");
        ok.addActionListener(e -> {
            Map<Integer, Settings.LevelStyle> m = new LinkedHashMap<>();
            for (int r = 1; r <= LogLevels.count(); r++)
                m.put(r, new Settings.LevelStyle(bold[r].isSelected(), italic[r].isSelected(), colors[r]));
            s.setLevelStyles(m);
            changed = true;
            dispose();
        });
        JButton cancel = new JButton("Abbrechen");
        cancel.addActionListener(e -> dispose());
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT));
        left.add(defaults);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        right.add(ok);
        right.add(cancel);
        JPanel south = new JPanel(new BorderLayout());
        south.add(where, BorderLayout.NORTH);
        south.add(left, BorderLayout.WEST);
        south.add(right, BorderLayout.EAST);

        JPanel content = new JPanel(new BorderLayout());
        content.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
        content.add(grid, BorderLayout.CENTER);
        content.add(south, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().setDefaultButton(ok);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        pack();
        setLocationRelativeTo(owner);
    }

    private void update(int rank) {
        Color c = colors[rank];
        colorButtons[rank].setIcon(new Swatch(c));
        colorButtons[rank].setText(c == null ? "normal" : Settings.hex(c));
        JLabel p = previews[rank];
        Font f = p.getFont().deriveFont((bold[rank].isSelected() ? Font.BOLD : 0) | (italic[rank].isSelected() ? Font.ITALIC : 0));
        p.setFont(f);
        p.setForeground(c != null ? c : Color.BLACK);
    }

    private static JLabel bold(JLabel l) {
        l.setFont(l.getFont().deriveFont(Font.BOLD));
        return l;
    }

    private static GridBagConstraints gbc(int x, int y) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = x; c.gridy = y;
        c.insets = new Insets(2, 6, 2, 6);
        c.anchor = x == 0 || x == 5 ? GridBagConstraints.WEST : GridBagConstraints.CENTER;
        c.fill = x == 3 ? GridBagConstraints.HORIZONTAL : GridBagConstraints.NONE;
        return c;
    }

    /** Farbfeld im Button. */
    private record Swatch(Color c) implements Icon {
        @Override public int getIconWidth() { return 14; }
        @Override public int getIconHeight() { return 14; }
        @Override public void paintIcon(Component comp, Graphics g, int x, int y) {
            g.setColor(c != null ? c : Color.BLACK);
            g.fillRect(x, y, 14, 14);
            g.setColor(Color.GRAY);
            g.drawRect(x, y, 13, 13);
        }
    }

    /** @return true wenn gespeichert wurde */
    static boolean show(JFrame owner) {
        SettingsDialog d = new SettingsDialog(owner);
        d.setVisible(true);
        return d.changed;
    }
}
