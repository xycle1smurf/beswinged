package jsonlogviewer;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.prefs.Preferences;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;

/**
 * Swing-Viewer für große JSON-Lines-Logdateien. Nur JDK, keine Fremdbibliotheken.
 *
 * Aufruf: java -jar jsonlogviewer.jar [logdatei] [--schema schema.yaml|schema.properties]
 */
public final class JsonLogViewer extends JFrame {

    private static final int AUTO_SAMPLE = 200;

    private final Preferences prefs = Preferences.userNodeForPackage(JsonLogViewer.class);

    private final JTable table = new JTable();
    private final JTextArea detail = new JTextArea();
    private final JTextField filterField = new JTextField(30);
    private final JComboBox<String> scopeBox = new JComboBox<>();
    private final JCheckBox regexBox = new JCheckBox("Regex");
    private final JCheckBox invertBox = new JCheckBox("Nicht");
    private final JButton filterButton = new JButton("Filtern");
    private final JButton clearButton = new JButton("Zurücksetzen");
    private final JLabel status = new JLabel(" ");
    private final JLabel filterStatus = new JLabel(" ");
    private final JProgressBar progress = new JProgressBar(0, 1000);

    private LineIndex index;
    private Schema configuredSchema;     // null = automatisch erkennen
    private Path schemaFile;
    private boolean autoSchemaPending;
    private LogTableModel model = new LogTableModel(null, Schema.auto(List.of()));

    private AtomicBoolean indexCancel = new AtomicBoolean();
    private AtomicBoolean filterCancel = new AtomicBoolean();
    private volatile int filterProgress = -1; // Promille, -1 = kein Filter aktiv
    private long indexStartNanos;
    private long indexMillis = -1;

    public JsonLogViewer() {
        super("JSON Log Viewer");
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(1400, 850);
        setLocationByPlatform(true);

        buildMenu();
        add(buildToolbar(), BorderLayout.NORTH);

        table.setModel(model);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setFillsViewportHeight(true);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setShowVerticalLines(false);
        table.setGridColor(new Color(0xE6E6E6));
        table.getTableHeader().setReorderingAllowed(true);
        RowRenderer renderer = new RowRenderer();
        table.setDefaultRenderer(Object.class, renderer);
        table.setDefaultRenderer(String.class, renderer);
        table.setDefaultRenderer(Integer.class, renderer);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) showDetail();
        });
        table.setComponentPopupMenu(buildPopup());

        detail.setEditable(false);
        detail.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        detail.setTabSize(2);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(table), new JScrollPane(detail));
        split.setResizeWeight(0.75);
        add(split, BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout(8, 0));
        south.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
        progress.setPreferredSize(new Dimension(200, 16));
        progress.setVisible(false);
        south.add(status, BorderLayout.CENTER);
        JPanel right = new JPanel(new BorderLayout(8, 0));
        right.add(filterStatus, BorderLayout.CENTER);
        right.add(progress, BorderLayout.EAST);
        south.add(right, BorderLayout.EAST);
        add(south, BorderLayout.SOUTH);

        setDropTarget(new DropTarget() {
            @Override public synchronized void drop(DropTargetDropEvent evt) {
                try {
                    evt.acceptDrop(DnDConstants.ACTION_COPY);
                    @SuppressWarnings("unchecked")
                    List<File> files = (List<File>) evt.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    for (File f : files) {
                        if (isSchemaFile(f.toPath())) loadSchema(f.toPath()); else openLog(f.toPath());
                    }
                } catch (Exception ex) {
                    error("Drop fehlgeschlagen", ex);
                }
            }
        });

        new Timer(200, e -> tick()).start();
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) { closeIndex(); System.exit(0); }
        });
        updateFilterControls();
        rebuildScopeBox();
    }

    // ================================================================== UI-Aufbau

    private void buildMenu() {
        JMenuBar bar = new JMenuBar();
        JMenu file = new JMenu("Datei");
        file.add(item("Log öffnen…", KeyEvent.VK_O, e -> chooseLog()));
        file.add(item("Neu laden", KeyEvent.VK_F5, e -> reload()));
        file.addSeparator();
        file.add(item("Schema laden…", KeyEvent.VK_L, e -> chooseSchema()));
        file.add(item("Schema automatisch erkennen", 0, e -> { configuredSchema = null; schemaFile = null; applySchema(); }));
        file.add(item("Schema als YAML speichern…", 0, e -> saveSchema()));
        file.addSeparator();
        file.add(item("Beenden", 0, e -> dispose()));
        JMenu nav = new JMenu("Navigation");
        nav.add(item("Gehe zu Zeile…", KeyEvent.VK_G, e -> gotoLine()));
        nav.add(item("Filter fokussieren", KeyEvent.VK_F, e -> filterField.requestFocusInWindow()));
        nav.add(item("Zum Anfang", KeyEvent.VK_HOME, e -> selectRow(0)));
        nav.add(item("Zum Ende", KeyEvent.VK_END, e -> selectRow(model.getRowCount() - 1)));
        bar.add(file);
        bar.add(nav);
        setJMenuBar(bar);
    }

    private JMenuItem item(String text, int key, java.awt.event.ActionListener l) {
        JMenuItem mi = new JMenuItem(text);
        if (key == KeyEvent.VK_F5) mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0));
        else if (key != 0) mi.setAccelerator(KeyStroke.getKeyStroke(key, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        mi.addActionListener(l);
        return mi;
    }

    private JToolBar buildToolbar() {
        JToolBar tb = new JToolBar();
        tb.setFloatable(false);
        tb.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        tb.add(new JLabel("Filter: "));
        filterField.setMaximumSize(new Dimension(500, 28));
        filterField.addActionListener(e -> applyFilter());
        filterField.setToolTipText("Enter = filtern. Ohne Regex: Teilstring, Groß-/Kleinschreibung egal");
        tb.add(filterField);
        tb.addSeparator();
        tb.add(new JLabel("in "));
        scopeBox.setMaximumSize(new Dimension(220, 28));
        tb.add(scopeBox);
        tb.addSeparator();
        tb.add(regexBox);
        invertBox.setToolTipText("Treffer ausschließen statt anzeigen");
        tb.add(invertBox);
        tb.addSeparator();
        filterButton.addActionListener(e -> applyFilter());
        clearButton.addActionListener(e -> clearFilter());
        tb.add(filterButton);
        tb.add(clearButton);
        tb.add(Box.createHorizontalGlue());
        return tb;
    }

    private JPopupMenu buildPopup() {
        JPopupMenu pm = new JPopupMenu();
        pm.add(new JMenuItem(new AbstractAction("Rohzeile(n) kopieren") {
            @Override public void actionPerformed(ActionEvent e) { copyRawLines(); }
        }));
        pm.add(new JMenuItem(new AbstractAction("Zellen kopieren (Tab-getrennt)") {
            @Override public void actionPerformed(ActionEvent e) {
                table.getTransferHandler().exportToClipboard(table, Toolkit.getDefaultToolkit().getSystemClipboard(),
                        javax.swing.TransferHandler.COPY);
            }
        }));
        return pm;
    }

    private void rebuildScopeBox() {
        scopeBox.removeAllItems();
        scopeBox.addItem("ganze Zeile");
        for (Schema.Column c : model.schema().columns) scopeBox.addItem(c.name());
    }

    private void applyColumnWidths() {
        TableColumn first = table.getColumnModel().getColumn(0);
        first.setPreferredWidth(80);
        List<Schema.Column> cols = model.schema().columns;
        for (int k = 0; k < cols.size(); k++)
            table.getColumnModel().getColumn(k + 1).setPreferredWidth(cols.get(k).width());
        int h = table.getFontMetrics(table.getFont()).getHeight() + 4;
        table.setRowHeight(h);
    }

    // ================================================================== Datei / Schema

    private void chooseLog() {
        JFileChooser fc = chooser();
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            prefs.put("lastDir", fc.getCurrentDirectory().getAbsolutePath());
            openLog(fc.getSelectedFile().toPath());
        }
    }

    private void chooseSchema() {
        JFileChooser fc = chooser();
        fc.setFileFilter(new FileNameExtensionFilter("Schema (YAML, Properties)", "yaml", "yml", "properties"));
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            prefs.put("lastDir", fc.getCurrentDirectory().getAbsolutePath());
            loadSchema(fc.getSelectedFile().toPath());
        }
    }

    private void saveSchema() {
        JFileChooser fc = chooser();
        fc.setSelectedFile(new File(fc.getCurrentDirectory(), "schema.yaml"));
        if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                Files.writeString(fc.getSelectedFile().toPath(), model.schema().toYaml(), StandardCharsets.UTF_8);
            } catch (IOException ex) {
                error("Speichern fehlgeschlagen", ex);
            }
        }
    }

    private JFileChooser chooser() {
        return new JFileChooser(prefs.get("lastDir", System.getProperty("user.dir")));
    }

    private static boolean isSchemaFile(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".yaml") || n.endsWith(".yml") || n.endsWith(".properties");
    }

    void loadSchema(Path file) {
        try {
            configuredSchema = Schema.load(file);
            schemaFile = file;
            applySchema();
        } catch (Exception ex) {
            error("Schema konnte nicht geladen werden: " + file, ex);
        }
    }

    private void reload() {
        if (schemaFile != null) {
            try {
                configuredSchema = Schema.load(schemaFile);
            } catch (Exception ex) {
                error("Schema konnte nicht geladen werden: " + schemaFile, ex);
            }
        }
        if (index != null) openLog(index.path());
        else applySchema();
    }

    void openLog(Path file) {
        closeIndex();
        LineIndex idx;
        try {
            idx = new LineIndex(file);
        } catch (IOException ex) {
            error("Datei konnte nicht geöffnet werden: " + file, ex);
            return;
        }
        index = idx;
        indexMillis = -1;
        indexStartNanos = System.nanoTime();
        AtomicBoolean cancel = new AtomicBoolean();
        indexCancel = cancel;
        applySchema();
        progress.setVisible(true);

        Thread t = new Thread(() -> {
            try {
                idx.build(cancel::get);
                EventQueue.invokeLater(() -> {
                    if (index == idx) {
                        indexMillis = (System.nanoTime() - indexStartNanos) / 1_000_000;
                        tick();
                        updateFilterControls();
                    }
                });
            } catch (IOException ex) {
                if (!cancel.get()) EventQueue.invokeLater(() -> error("Fehler beim Indizieren", ex));
            }
        }, "indexer");
        t.setDaemon(true);
        t.start();
    }

    private void closeIndex() {
        indexCancel.set(true);
        filterCancel.set(true);
        filterProgress = -1;
        if (index != null) {
            try { index.close(); } catch (IOException ignored) { }
            index = null;
        }
    }

    /** Erstellt ein neues Modell mit dem aktuellen Schema (Filter wird zurückgesetzt). */
    private void applySchema() {
        filterCancel.set(true);
        filterProgress = -1;
        Schema s = configuredSchema;
        autoSchemaPending = false;
        if (s == null) {
            if (index != null && (index.lineCount() >= AUTO_SAMPLE || index.isComplete())) {
                s = Schema.auto(sampleLines());
            } else {
                s = Schema.auto(List.of());
                autoSchemaPending = index != null;
            }
        }
        model = new LogTableModel(index, s);
        table.setModel(model);
        applyColumnWidths();
        rebuildScopeBox();
        filterStatus.setText(" ");
        detail.setText("");
        String name = index == null ? "" : " — " + index.path().getFileName();
        setTitle(s.title + name);
        updateFilterControls();
    }

    private List<String> sampleLines() {
        List<String> lines = new ArrayList<>();
        int n = Math.min(AUTO_SAMPLE, index.lineCount());
        for (int i = 0; i < n; i++) {
            try { lines.add(index.readLine(i)); } catch (IOException ignored) { }
        }
        return lines;
    }

    // ================================================================== Periodisches Update

    private void tick() {
        if (index == null) {
            status.setText("Keine Datei geöffnet — Datei per Drag & Drop oder Strg+O öffnen");
            return;
        }
        if (autoSchemaPending && (index.lineCount() >= AUTO_SAMPLE || index.isComplete())) applySchema();
        model.refreshRowCount();

        long size = index.fileSize();
        StringBuilder sb = new StringBuilder();
        sb.append(index.path().getFileName()).append("  —  ")
          .append(String.format("%,d", index.lineCount())).append(" Zeilen  —  ")
          .append(human(size));
        if (!index.isComplete()) {
            int permille = size == 0 ? 0 : (int) (index.bytesScanned() * 1000 / size);
            sb.append("  —  indiziere… ").append(permille / 10).append('%');
            progress.setValue(permille);
        } else if (indexMillis >= 0) {
            sb.append("  —  indiziert in ").append(String.format("%.1f s", indexMillis / 1000.0));
        }
        if (schemaFile != null) sb.append("  —  Schema: ").append(schemaFile.getFileName());
        else sb.append("  —  Schema: automatisch");
        status.setText(sb.toString());

        int fp = filterProgress;
        if (fp >= 0) progress.setValue(fp);
        progress.setVisible(!index.isComplete() || fp >= 0);
    }

    private static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] u = {"KB", "MB", "GB", "TB"};
        double v = bytes;
        int k = -1;
        while (v >= 1024 && k < u.length - 1) { v /= 1024; k++; }
        return String.format("%.1f %s", v, u[k]);
    }

    // ================================================================== Filter

    private void updateFilterControls() {
        boolean ok = index != null && index.isComplete();
        filterButton.setEnabled(ok);
        filterField.setEnabled(ok);
        filterField.setToolTipText(ok ? "Enter = filtern. Ohne Regex: Teilstring, Groß-/Kleinschreibung egal"
                                      : "Filter ist nach dem Indizieren verfügbar");
    }

    private void applyFilter() {
        if (index == null || !index.isComplete()) return;
        String text = filterField.getText();
        if (text.isEmpty()) { clearFilter(); return; }

        Predicate<String> matcher;
        if (regexBox.isSelected()) {
            try {
                Pattern p = Pattern.compile(text, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
                matcher = s -> p.matcher(s).find();
            } catch (PatternSyntaxException ex) {
                JOptionPane.showMessageDialog(this, ex.getMessage(), "Ungültiger Regex", JOptionPane.ERROR_MESSAGE);
                return;
            }
        } else {
            String needle = text.toLowerCase(Locale.ROOT);
            matcher = s -> containsIgnoreCase(s, needle);
        }
        boolean invert = invertBox.isSelected();
        int scope = scopeBox.getSelectedIndex() - 1; // -1 = ganze Zeile
        Schema schema = model.schema();
        String[] segs = scope >= 0 ? schema.columns.get(scope).segments() : null;
        boolean rawColumn = scope >= 0 && Schema.RAW.equals(schema.columns.get(scope).path());

        filterCancel.set(true);
        AtomicBoolean cancel = new AtomicBoolean();
        filterCancel = cancel;
        LineIndex idx = index;
        LogTableModel targetModel = model;
        int total = idx.lineCount();
        int selectedLine = table.getSelectedRow() >= 0 ? model.lineAt(table.getSelectedRow()) : -1;
        filterProgress = 0;
        filterStatus.setText("filtere…");
        long t0 = System.nanoTime();

        Thread t = new Thread(() -> {
            int[][] hits = {new int[1024]};
            int[] count = {0};
            try {
                idx.scan(0, total, (line, s) -> {
                    if ((line & 0xFFF) == 0) {
                        if (cancel.get()) return false;
                        filterProgress = total == 0 ? 0 : (int) ((long) line * 1000 / total);
                    }
                    String target;
                    if (segs == null || rawColumn) target = s;
                    else {
                        try {
                            target = Json.toDisplay(Json.get(Json.parse(s), segs));
                        } catch (RuntimeException ex) {
                            target = null;
                        }
                    }
                    boolean m = target != null && matcher.test(target);
                    if (m != invert) {
                        if (count[0] == hits[0].length) hits[0] = Arrays.copyOf(hits[0], hits[0].length * 2);
                        hits[0][count[0]++] = line;
                    }
                    return true;
                });
            } catch (IOException ex) {
                if (!cancel.get()) EventQueue.invokeLater(() -> error("Fehler beim Filtern", ex));
                return;
            }
            if (cancel.get()) return;
            int[] result = hits[0];
            long ms = (System.nanoTime() - t0) / 1_000_000;
            EventQueue.invokeLater(() -> {
                if (cancel.get() || model != targetModel) return;
                filterProgress = -1;
                model.setFilter(result, count[0]);
                filterStatus.setText(String.format("Filter: %,d Treffer (%.1f s)", count[0], ms / 1000.0));
                if (selectedLine >= 0 && count[0] > 0) selectRow(model.nearestViewRow(selectedLine));
                tick();
            });
        }, "filter");
        t.setDaemon(true);
        t.start();
    }

    private void clearFilter() {
        filterCancel.set(true);
        filterProgress = -1;
        int selectedLine = table.getSelectedRow() >= 0 ? model.lineAt(table.getSelectedRow()) : -1;
        model.setFilter(null, 0);
        filterStatus.setText(" ");
        if (selectedLine >= 0) selectRow(selectedLine);
        tick();
    }

    /** Teilstring-Suche ohne Groß-/Kleinschreibung, ohne pro Zeile einen String zu kopieren. */
    static boolean containsIgnoreCase(String hay, String needleLower) {
        int n = needleLower.length();
        if (n == 0) return true;
        char first = needleLower.charAt(0);
        char firstUpper = Character.toUpperCase(first);
        int max = hay.length() - n;
        for (int i = 0; i <= max; i++) {
            char c = hay.charAt(i);
            if ((c == first || c == firstUpper || Character.toLowerCase(c) == first)
                    && hay.regionMatches(true, i, needleLower, 0, n)) return true;
        }
        return false;
    }

    // ================================================================== Navigation / Detail

    private void gotoLine() {
        if (model.getRowCount() == 0) return;
        String s = JOptionPane.showInputDialog(this, "Zeilennummer:", "Gehe zu Zeile", JOptionPane.QUESTION_MESSAGE);
        if (s == null) return;
        try {
            int line = Integer.parseInt(s.trim().replace(".", "").replace(",", "")) - 1;
            selectRow(model.nearestViewRow(Math.max(0, line)));
        } catch (NumberFormatException ex) {
            Toolkit.getDefaultToolkit().beep();
        }
    }

    private void selectRow(int row) {
        if (row < 0 || row >= model.getRowCount()) return;
        table.getSelectionModel().setSelectionInterval(row, row);
        table.scrollRectToVisible(table.getCellRect(row, 0, true));
        table.requestFocusInWindow();
    }

    private void showDetail() {
        int r = table.getSelectedRow();
        if (r < 0 || index == null) { detail.setText(""); return; }
        int line = model.lineAt(r);
        try {
            String text = index.readLine(line);
            String body;
            try {
                body = Json.stringify(Json.parse(text), true);
            } catch (RuntimeException ex) {
                body = "⚠ Kein gültiges JSON: " + ex.getMessage() + "\n\n" + text;
            }
            detail.setText("// Zeile " + (line + 1) + "\n" + body);
            detail.setCaretPosition(0);
        } catch (IOException ex) {
            detail.setText("Lesefehler: " + ex.getMessage());
        }
    }

    private void copyRawLines() {
        int[] rows = table.getSelectedRows();
        if (rows.length == 0 || index == null) return;
        if (rows.length > 100_000) {
            JOptionPane.showMessageDialog(this, "Bitte höchstens 100.000 Zeilen auf einmal kopieren.");
            return;
        }
        StringBuilder sb = new StringBuilder();
        try {
            for (int r : rows) sb.append(index.readLine(model.lineAt(r))).append('\n');
        } catch (IOException ex) {
            error("Lesefehler", ex);
            return;
        }
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(sb.toString()), null);
    }

    private void error(String msg, Exception ex) {
        JOptionPane.showMessageDialog(this, msg + "\n\n" + ex.getMessage(), "Fehler", JOptionPane.ERROR_MESSAGE);
    }

    // ================================================================== Renderer

    private final class RowRenderer extends DefaultTableCellRenderer {
        private final Color invalidBg = new Color(0xF3E5F5);
        private final Color lineNoFg = new Color(0x888888);

        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus, int row, int col) {
            super.getTableCellRendererComponent(t, value, selected, focus, row, col);
            int modelCol = t.convertColumnIndexToModel(col);
            setHorizontalAlignment(modelCol == 0 ? SwingConstants.RIGHT : SwingConstants.LEFT);
            if (!selected) {
                LogTableModel.Row r = model.row(row);
                Color bg = r.invalid() ? invalidBg : r.background();
                setBackground(bg != null ? bg : t.getBackground());
                setForeground(modelCol == 0 ? lineNoFg : t.getForeground());
            }
            if (modelCol != 0 && value != null) {
                String s = value.toString();
                setToolTipText(s.length() > 60 ? (s.length() > 500 ? s.substring(0, 500) + "…" : s) : null);
            } else setToolTipText(null);
            return this;
        }
    }

    // ================================================================== main

    public static void main(String[] args) {
        Path log = null;
        Path schema = null;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ((a.equals("--schema") || a.equals("-s")) && i + 1 < args.length) schema = Path.of(args[++i]);
            else if (a.equals("-h") || a.equals("--help")) {
                System.out.println("Aufruf: java -jar jsonlogviewer.jar [logdatei] [--schema schema.yaml|schema.properties]");
                return;
            } else log = Path.of(a);
        }
        Path fLog = log, fSchema = schema;
        EventQueue.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) { }
            JsonLogViewer v = new JsonLogViewer();
            if (fSchema != null) v.loadSchema(fSchema);
            if (fLog != null) v.openLog(fLog);
            v.tick();
            v.setVisible(true);
        });
    }
}
