package jsonlogviewer;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
import jsonlogviewer.format.pattern.ConfigImport;
import jsonlogviewer.format.pattern.LayoutPattern;
import jsonlogviewer.format.pattern.PatternLogFormat;

/**
 * Schema → Import → logback.xml / log4j2.xml / Muster eingeben: Appender auswählen, Muster prüfen (Felder,
 * Trefferquote und Vorschau mit dem geöffneten Log) und als eigenes Logformat speichern.
 */
final class PatternImportDialog extends JDialog {

    enum Source { LOGBACK, LOG4J2, MANUAL }

    private final Source source;
    private final List<String> sample;
    private final DefaultListModel<ConfigImport.Candidate> candidates = new DefaultListModel<>();
    private final JList<ConfigImport.Candidate> list = new JList<>(candidates);
    private final JLabel fileLabel = new JLabel(" ");
    private final JTextArea pattern = new JTextArea(3, 80);
    private final JTextField name = new JTextField(30);
    private final JLabel fields = new JLabel(" ");
    private final JLabel score = new JLabel(" ");
    private final DefaultTableModel preview = new DefaultTableModel() {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };
    private final JButton ok = new JButton("Übernehmen");
    private Path file;
    private Settings.UserFormat result;

    /** @param sample Probezeilen des geöffneten Logs (für Trefferquote und Vorschau), leer wenn keins offen */
    PatternImportDialog(java.awt.Window owner, Source source, List<String> sample) {
        super(owner, switch (source) {
            case LOGBACK -> "Import – logback.xml";
            case LOG4J2 -> "Import – log4j2.xml";
            case MANUAL -> "Eigenes Layout-Muster";
        }, ModalityType.APPLICATION_MODAL);
        this.source = source;
        this.sample = sample;
        pattern.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        pattern.setLineWrap(true);
        pattern.setToolTipText("Logback-/Log4j-PatternLayout, z.B. %d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n");
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setVisibleRowCount(6);
        list.setCellRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override public java.awt.Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                ConfigImport.Candidate c = (ConfigImport.Candidate) v;
                String dim = s ? "" : " color='#666666'";
                String text = "<html><b>" + esc(c.name()) + "</b> <font" + dim + ">(" + esc(c.type()) + ")</font>"
                        + (c.usable() ? "<br><code>" + esc(abbrev(c.pattern())) + "</code>" : "<br><i>" + esc(c.note()) + "</i>")
                        + (c.usable() && !c.note().isEmpty() ? " <i><font" + dim + ">" + esc(c.note()) + "</font></i>" : "") + "</html>";
                java.awt.Component comp = super.getListCellRendererComponent(l, text, i, s, f);
                if (!c.usable() && !s) comp.setForeground(Color.GRAY);
                return comp;
            }
        });
        list.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            ConfigImport.Candidate c = list.getSelectedValue();
            if (c == null) return;
            pattern.setText(c.pattern());
            pattern.setCaretPosition(0);
            if (c.usable()) name.setText((file == null ? "" : file.getFileName() + ": ") + c.name());
        });
        pattern.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { check(); }
            @Override public void removeUpdate(DocumentEvent e) { check(); }
            @Override public void changedUpdate(DocumentEvent e) { check(); }
        });
        fields.setForeground(Color.DARK_GRAY);

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(10, 10, 4, 10));
        int y = 0;
        if (source != Source.MANUAL) {
            JButton other = new JButton("Datei wählen…");
            other.addActionListener(e -> chooseFile());
            JPanel fp = new JPanel(new BorderLayout(8, 0));
            fp.add(fileLabel, BorderLayout.CENTER);
            fp.add(other, BorderLayout.EAST);
            add(form, y++, "Datei", fp, false);
            add(form, y++, "Appender", new JScrollPane(list), true);
        }
        add(form, y++, "Muster", new JScrollPane(pattern), false);
        add(form, y++, "Felder", fields, false);
        add(form, y++, "Test", score, false);
        JTable table = new JTable(preview);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        JScrollPane ps = new JScrollPane(table);
        ps.setPreferredSize(new Dimension(760, 170));
        add(form, y++, "Vorschau", ps, true);
        add(form, y, "Name", name, false);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton cancel = new JButton("Abbrechen");
        ok.addActionListener(e -> accept());
        cancel.addActionListener(e -> dispose());
        buttons.add(ok);
        buttons.add(cancel);
        getRootPane().setDefaultButton(ok);
        add(form, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
        check();
        pack();
        setLocationRelativeTo(owner);
    }

    private static void add(JPanel p, int y, String label, java.awt.Component c, boolean grow) {
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0; g.gridy = y; g.anchor = GridBagConstraints.NORTHWEST; g.insets = new Insets(6, 4, 4, 8);
        p.add(new JLabel(label), g);
        g = new GridBagConstraints();
        g.gridx = 1; g.gridy = y; g.fill = grow ? GridBagConstraints.BOTH : GridBagConstraints.HORIZONTAL;
        g.weightx = 1; g.weighty = grow ? 1 : 0; g.insets = new Insets(4, 4, 4, 4);
        p.add(c, g);
    }

    /** Dateiauswahl; false wenn abgebrochen. */
    boolean chooseFile() {
        JFileChooser fc = new JFileChooser(Settings.get().lastDir());
        fc.setDialogTitle(getTitle());
        fc.setFileFilter(new FileNameExtensionFilter(source == Source.LOGBACK ? "Logback-Konfiguration (*.xml)" : "Log4j2-Konfiguration (*.xml)", "xml"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return false;
        Settings.get().setLastDir(fc.getSelectedFile().getParent());
        return load(fc.getSelectedFile().toPath());
    }

    boolean load(Path xml) {
        ConfigImport.Result r;
        try {
            r = source == Source.LOG4J2 ? ConfigImport.log4j2(xml) : ConfigImport.logback(xml);
        } catch (IOException ex) {
            javax.swing.JOptionPane.showMessageDialog(this, ex.getMessage(), getTitle(), javax.swing.JOptionPane.ERROR_MESSAGE);
            return false;
        }
        file = xml;
        fileLabel.setText(xml.toString());
        candidates.clear();
        for (ConfigImport.Candidate c : r.candidates()) candidates.addElement(c);
        if (candidates.isEmpty()) {
            score.setText("Keine Appender gefunden.");
        } else {
            // bestes Muster vorauswählen: das mit der höchsten Trefferquote im geöffneten Log, sonst das erste brauchbare
            int best = -1;
            double bestScore = -1;
            for (int i = 0; i < candidates.size(); i++) {
                ConfigImport.Candidate c = candidates.get(i);
                if (!c.usable()) continue;
                double s = 0;
                try {
                    if (!sample.isEmpty()) s = new PatternLogFormat("t", "t", c.pattern()).detect(sample);
                } catch (IllegalArgumentException ignored) { }
                if (s > bestScore) { bestScore = s; best = i; }
            }
            list.setSelectedIndex(Math.max(0, best));
        }
        return true;
    }

    void setPattern(String p) { pattern.setText(p); }

    /** Muster prüfen und Vorschau füllen. */
    private void check() {
        preview.setRowCount(0);
        preview.setColumnCount(0);
        String p = pattern.getText().trim();
        if (p.isEmpty()) {
            fields.setText(" ");
            score.setText(source == Source.MANUAL ? "Muster eingeben." : " ");
            ok.setEnabled(false);
            return;
        }
        PatternLogFormat f;
        try {
            f = new PatternLogFormat("t", "t", p);
        } catch (RuntimeException ex) {
            fields.setText(" ");
            score.setText("<html><font color='#B00020'>" + esc(String.valueOf(ex.getMessage())) + "</font></html>");
            ok.setEnabled(false);
            return;
        }
        LayoutPattern lp = f.patterns().get(0);
        fields.setText("<html>" + esc(String.join(", ", lp.fields())) + "</html>");
        fields.setToolTipText("<html>Regex der ersten Zeile:<br><code>" + esc(lp.regex()) + "</code></html>");
        ok.setEnabled(true);
        if (sample.isEmpty()) {
            score.setText("Kein Log geöffnet – Trefferquote und Vorschau erscheinen, wenn vorher ein Log geöffnet ist.");
            return;
        }
        double s = f.detect(sample);
        score.setText("<html>Passt auf <b>" + Math.round(s * 100) + " %</b> der Einträge am Anfang des geöffneten Logs"
                + (s < 0.6 ? " – <font color='#B00020'>passt das Muster zur Datei?</font>" : "") + "</html>");
        // Vorschau: erste Einträge
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> entries = new ArrayList<>();
        for (String line : sample) {
            if (entries.size() > 30) break;
            if (entries.isEmpty() || f.isEntryStart(line)) entries.add(line);
            else entries.set(entries.size() - 1, entries.get(entries.size() - 1) + "\n" + line);
        }
        Set<String> cols = new LinkedHashSet<>();
        for (String e : entries) {
            Map<String, Object> m = f.parse(e);
            if (m == null) { Map<String, Object> bad = new java.util.LinkedHashMap<>(); bad.put("⚠ nicht erkannt", e); m = bad; }
            rows.add(m);
            cols.addAll(m.keySet());
            if (rows.size() >= 20) break;
        }
        for (String c : cols) preview.addColumn(c);
        for (Map<String, Object> m : rows) {
            Object[] r = new Object[cols.size()];
            int i = 0;
            for (String c : cols) {
                Object v = m.get(c);
                r[i++] = v == null ? "" : v.toString().replace('\n', '⏎');
            }
            preview.addRow(r);
        }
    }

    private void accept() {
        String p = pattern.getText().trim();
        String n = name.getText().trim();
        if (n.isEmpty()) n = "Eigenes Muster";
        try {
            LayoutPattern.compile(p);
        } catch (RuntimeException ex) {
            return;
        }
        result = new Settings.UserFormat(n, p, file == null ? "" : file.toString());
        dispose();
    }

    /** Gewähltes Format, null wenn abgebrochen. */
    Settings.UserFormat result() { return result; }

    private static String abbrev(String s) {
        return s.length() > 110 ? s.substring(0, 110) + "…" : s;
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static PatternLogFormat toFormat(Settings.UserFormat u) {
        return new PatternLogFormat("user:" + u.name(), u.name(), u.pattern());
    }
}
