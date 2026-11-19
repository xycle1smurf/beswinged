package jsonlogviewer;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.prefs.Preferences;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JCheckBoxMenuItem;
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
import javax.swing.JRadioButtonMenuItem;
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
import javax.swing.event.ChangeEvent;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.event.TableColumnModelEvent;
import javax.swing.event.TableColumnModelListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;

/**
 * Swing-Viewer für große JSON-Lines-Logdateien. Nur JDK, keine Fremdbibliotheken.
 *
 * Aufruf: java -jar beswinged.jar [logdatei] [--schema schema.yaml|schema.properties]
 */
public final class JsonLogViewer extends JFrame {

    private static final int AUTO_SAMPLE = 200;
    private static final int KIND_SAMPLE = 50;

    /** Art der Werte einer Spalte, bestimmt welche Formatter im Kontextmenü angeboten werden. */
    private enum Kind { TIMESTAMP, CLASS, OTHER }

    private final Preferences prefs = Preferences.userNodeForPackage(JsonLogViewer.class);

    private final JTable table = new JTable();
    private final JTextArea detail = new JTextArea();
    private final JLabel detailHeader = new JLabel(" ");
    private final JTextField filterField = new JTextField(30);
    private final JComboBox<String> scopeBox = new JComboBox<>();
    private final List<Integer> scopeColumns = new ArrayList<>(); // Combo-Index-1 -> Schema-Spalte
    private final JCheckBox regexBox = new JCheckBox("Regex");
    private final JCheckBox invertBox = new JCheckBox("Nicht");
    private final JButton filterButton = new JButton("Filtern");
    private final JButton clearButton = new JButton("Zurücksetzen");
    private final JLabel status = new JLabel(" ");
    private final JLabel filterStatus = new JLabel(" ");
    private final JProgressBar progress = new JProgressBar(0, 1000);

    // Auswahlfilter / Loglevel-Filter (Schema-Spalte -> Widget)
    private static final int DISTINCT_LIMIT = 10_000;
    private final JPanel choiceBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
    private Map<Integer, ChoiceFilterButton> choiceButtons = new LinkedHashMap<>();
    private Map<Integer, JComboBox<String>> levelBoxes = new LinkedHashMap<>();
    private AtomicBoolean distinctCancel = new AtomicBoolean();

    private LineIndex index;
    private Schema configuredSchema;     // null = automatisch erkennen
    private Path schemaFile;             // Ziel für automatisches Speichern
    private boolean autoSchemaPending;
    private LogTableModel model = new LogTableModel(null, Schema.auto(List.of()));

    // Spaltenzustand der View
    private TableColumn[] tableColumns = new TableColumn[0]; // Index = Model-Spalte
    private List<Integer> order = new ArrayList<>();          // Schema-Spalten in Anzeigereihenfolge (inkl. ausgeblendeter)
    private boolean rebuildingColumns;
    private final Map<Integer, Kind> kindCache = new HashMap<>();
    private final Timer saveTimer = new Timer(800, e -> saveSchemaNow());
    private String lastSavedSnapshot;
    private String lastDetailKey;

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
        saveTimer.setRepeats(false);

        buildMenu();
        JPanel north = new JPanel(new BorderLayout());
        north.add(buildToolbar(), BorderLayout.NORTH);
        choiceBar.setBorder(BorderFactory.createEmptyBorder(0, 4, 4, 4));
        choiceBar.setVisible(false);
        north.add(choiceBar, BorderLayout.SOUTH);
        add(north, BorderLayout.NORTH);

        table.setAutoCreateColumnsFromModel(false); // Spalten verwaltet der Viewer selbst
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
            if (!e.getValueIsAdjusting()) showDetail(false);
        });
        table.getColumnModel().addColumnModelListener(new ColumnListener());
        table.setComponentPopupMenu(buildPopup());
        // Eigenes Menü für die Spaltenköpfe (sonst erbt der Header das Kontextmenü der Tabelle);
        // der Inhalt wird beim Öffnen passend zur Spalte unter der Maus aufgebaut.
        JPopupMenu headerPopup = new JPopupMenu();
        headerPopup.addPopupMenuListener(new PopupMenuListener() {
            @Override public void popupMenuWillBecomeVisible(PopupMenuEvent e) { fillHeaderPopup(headerPopup); }
            @Override public void popupMenuWillBecomeInvisible(PopupMenuEvent e) { }
            @Override public void popupMenuCanceled(PopupMenuEvent e) { }
        });
        table.getTableHeader().setComponentPopupMenu(headerPopup);
        table.getTableHeader().addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { headerPopupX = e.getX(); }
        });
        buildColumns();

        detail.setEditable(false);
        detail.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        detail.setTabSize(4);
        JPanel detailPanel = new JPanel(new BorderLayout());
        detailHeader.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
        detailPanel.add(detailHeader, BorderLayout.NORTH);
        detailPanel.add(new JScrollPane(detail), BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(table), detailPanel);
        split.setResizeWeight(0.7);
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
            @Override public void windowClosed(WindowEvent e) { flushSave(); closeIndex(); System.exit(0); }
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
        file.add(item("Schema automatisch erkennen", 0, e -> {
            flushSave();
            configuredSchema = null;
            schemaFile = null;
            applySchema();
        }));
        file.add(item("Schema speichern unter…", KeyEvent.VK_S, e -> saveSchemaAs()));
        file.addSeparator();
        file.add(item("Beenden", 0, e -> dispose()));

        JMenu view = new JMenu("Ansicht");
        view.addMenuListener(new MenuListener() {
            @Override public void menuSelected(MenuEvent e) { fillViewMenu(view); }
            @Override public void menuDeselected(MenuEvent e) { }
            @Override public void menuCanceled(MenuEvent e) { }
        });

        JMenu nav = new JMenu("Navigation");
        nav.add(item("Gehe zu Zeile…", KeyEvent.VK_G, e -> gotoLine()));
        nav.add(item("Filter fokussieren", KeyEvent.VK_F, e -> filterField.requestFocusInWindow()));
        nav.add(item("Zum Anfang", KeyEvent.VK_HOME, e -> selectRow(0)));
        nav.add(item("Zum Ende", KeyEvent.VK_END, e -> selectRow(model.getRowCount() - 1)));
        JMenu extras = new JMenu("Extras");
        extras.add(item("Log-Generator…", 0, e -> showGenerator()));
        bar.add(file);
        bar.add(view);
        bar.add(nav);
        bar.add(extras);
        setJMenuBar(bar);
    }

    private void showGenerator() {
        flushSave();
        String current = schemaFile != null ? schemaFile.getFileName().toString()
                : model.schema().auto ? "automatisch" : model.schema().title;
        Schema currentSchema = index == null && configuredSchema == null ? null : snapshot();
        new GeneratorDialog(this, new File(prefs.get("lastDir", System.getProperty("user.dir"))),
                currentSchema, current, this::openGenerated).setVisible(true);
    }

    /** Öffnet ein erzeugtes Log mit dem dafür verwendeten Schema. */
    private void openGenerated(Path log, Schema schema, Path file) {
        if (file != null) {
            loadSchema(file);
        } else if (schema == null) {
            try (var in = JsonLogViewer.class.getResourceAsStream("logstash.schema.yaml")) {
                configuredSchema = Schema.fromYaml(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                schemaFile = null;
            } catch (Exception ex) {
                configuredSchema = null;
                schemaFile = null;
            }
        } else {
            configuredSchema = schema; // aktuelles Schema beibehalten (schemaFile bleibt)
        }
        openLog(log);
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
        clearButton.addActionListener(e -> resetAllFilters());
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
        int keep = scopeBox.getSelectedIndex() > 0 ? scopeColumns.get(scopeBox.getSelectedIndex() - 1) : -1;
        scopeBox.removeAllItems();
        scopeColumns.clear();
        scopeBox.addItem("ganze Zeile");
        int select = 0;
        for (int k : order) {
            scopeColumns.add(k);
            scopeBox.addItem(model.column(k).name());
            if (k == keep) select = scopeColumns.size();
        }
        scopeBox.setSelectedIndex(select);
    }

    // ================================================================== Spalten

    /** Erzeugt die TableColumns für das aktuelle Modell. */
    private void buildColumns() {
        int n = model.schemaColumnCount();
        tableColumns = new TableColumn[n + 1];
        tableColumns[0] = new TableColumn(0, 80);
        tableColumns[0].setHeaderValue("#");
        order = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            Schema.Column c = model.column(k);
            TableColumn tc = new TableColumn(k + 1, c.width());
            tc.setHeaderValue(c.name());
            tableColumns[k + 1] = tc;
            order.add(k);
        }
        kindCache.clear();
        rebuildColumnModel();
        table.setRowHeight(table.getFontMetrics(table.getFont()).getHeight() + 4);
    }

    /** Baut das ColumnModel aus {@link #order} und der Sichtbarkeit neu auf. */
    private void rebuildColumnModel() {
        rebuildingColumns = true;
        try {
            TableColumnModel cm = table.getColumnModel();
            while (cm.getColumnCount() > 0) cm.removeColumn(cm.getColumn(0));
            cm.addColumn(tableColumns[0]);
            for (int k : order) if (model.column(k).visible()) cm.addColumn(tableColumns[k + 1]);
        } finally {
            rebuildingColumns = false;
        }
        table.getTableHeader().repaint();
    }

    /** Übernimmt die per Drag & Drop geänderte Reihenfolge; ausgeblendete Spalten behalten ihren Platz. */
    private void syncOrderFromView() {
        TableColumnModel cm = table.getColumnModel();
        List<Integer> visibleInView = new ArrayList<>();
        for (int v = 0; v < cm.getColumnCount(); v++) {
            int mi = cm.getColumn(v).getModelIndex();
            if (mi > 0) visibleInView.add(mi - 1);
        }
        Iterator<Integer> it = visibleInView.iterator();
        List<Integer> newOrder = new ArrayList<>(order.size());
        for (int k : order) newOrder.add(model.column(k).visible() && it.hasNext() ? it.next() : k);
        order = newOrder;
    }

    private int visibleCount() {
        int n = 0;
        for (int k = 0; k < model.schemaColumnCount(); k++) if (model.column(k).visible()) n++;
        return n;
    }

    private void setColumnVisible(int k, boolean visible) {
        if (model.column(k).visible() == visible) return;
        if (!visible && visibleCount() <= 1) { Toolkit.getDefaultToolkit().beep(); return; }
        model.setColumn(k, model.column(k).withVisible(visible));
        rebuildColumnModel();
        scheduleSave();
    }

    private void showAllColumns() {
        for (int k = 0; k < model.schemaColumnCount(); k++)
            if (!model.column(k).visible()) model.setColumn(k, model.column(k).withVisible(true));
        rebuildColumnModel();
        scheduleSave();
    }

    private void setFormatter(int k, CellFormatter f) {
        if (model.column(k).formatter() == f) return;
        model.setColumn(k, model.column(k).withFormatter(f));
        scheduleSave();
        showDetail(true);
        ChoiceFilterButton b = choiceButtons.get(k);
        if (b != null) { // Werte hängen vom Formatter ab
            b.clearSelection();
            b.setLoading();
            startDistinctScan();
        }
    }

    private void setChoiceFilter(int k, boolean on) {
        model.setColumn(k, model.column(k).withChoiceFilter(on));
        boolean hadSelection = !on && choiceButtons.containsKey(k) && !choiceButtons.get(k).selection().isEmpty();
        rebuildChoiceBar();
        scheduleSave();
        if (hadSelection) applyFilter();
    }

    private void setLogLevelType(int k, boolean on) {
        model.setColumn(k, model.column(k).withLogLevel(on));
        boolean hadLevel = !on && levelBoxes.containsKey(k) && levelBoxes.get(k).getSelectedIndex() > 0;
        rebuildChoiceBar();
        scheduleSave();
        if (hadLevel) applyFilter();
    }

    // ---------------------------------------------------- Auswahl- und Level-Filter

    /** Baut die zweite Filterzeile; bestehende Auswahlen bleiben erhalten. */
    private void rebuildChoiceBar() {
        Map<Integer, ChoiceFilterButton> oldChoices = choiceButtons;
        Map<Integer, JComboBox<String>> oldLevels = levelBoxes;
        choiceButtons = new LinkedHashMap<>();
        levelBoxes = new LinkedHashMap<>();
        choiceBar.removeAll();
        for (int k : order) {
            Schema.Column c = model.column(k);
            if (c.logLevel()) {
                JComboBox<String> box = oldLevels.get(k);
                if (box == null) {
                    box = new JComboBox<>();
                    box.addItem("alle");
                    for (int r = 1; r <= LogLevels.count(); r++) box.addItem("≥ " + LogLevels.label(r));
                    box.setPrototypeDisplayValue("≥ FATAL / CRITICAL   ");
                    box.setToolTipText("Zeigt das gewählte Level und alle schwerwiegenderen");
                    box.addActionListener(e -> applyFilter());
                }
                choiceBar.add(new JLabel(c.name() + ":"));
                choiceBar.add(box);
                levelBoxes.put(k, box);
            }
            if (c.choiceFilter()) {
                ChoiceFilterButton b = oldChoices.get(k);
                if (b == null) b = new ChoiceFilterButton(c.name(), this::applyFilter);
                choiceBar.add(b);
                choiceButtons.put(k, b);
            }
        }
        boolean show = !choiceButtons.isEmpty() || !levelBoxes.isEmpty();
        if (show) {
            JButton reset = new JButton("Auswahl zurücksetzen");
            reset.addActionListener(e -> {
                resetChoiceWidgets();
                applyFilter();
            });
            choiceBar.add(reset);
        }
        choiceBar.setVisible(show);
        choiceBar.revalidate();
        choiceBar.repaint();
        updateFilterControls();
        startDistinctScan();
    }

    private void resetChoiceWidgets() {
        for (ChoiceFilterButton b : choiceButtons.values()) b.clearSelection();
        for (JComboBox<String> box : levelBoxes.values()) {
            ActionListener[] ls = box.getActionListeners();
            for (ActionListener l : ls) box.removeActionListener(l);
            box.setSelectedIndex(0);
            for (ActionListener l : ls) box.addActionListener(l);
        }
    }

    /** Ermittelt im Hintergrund die DISTINCT-Werte aller Auswahlfilter, die noch keine Werte haben. */
    private void startDistinctScan() {
        if (index == null || !index.isComplete()) return; // wird nach dem Indizieren erneut aufgerufen
        List<Integer> ks = new ArrayList<>();
        for (Map.Entry<Integer, ChoiceFilterButton> e : choiceButtons.entrySet())
            if (e.getValue().isLoading()) ks.add(e.getKey());
        if (ks.isEmpty()) return;
        distinctCancel.set(true);
        AtomicBoolean cancel = new AtomicBoolean();
        distinctCancel = cancel;
        LineIndex idx = index;
        LogTableModel m = model;
        Schema.Column[] cols = new Schema.Column[ks.size()];
        for (int i = 0; i < cols.length; i++) cols[i] = m.column(ks.get(i));

        Thread t = new Thread(() -> {
            List<Set<String>> sets = new ArrayList<>();
            for (int i = 0; i < cols.length; i++) sets.add(new HashSet<>());
            boolean[] overflow = new boolean[cols.length];
            try {
                idx.scan(0, idx.lineCount(), (line, s) -> {
                    if ((line & 0xFFF) == 0 && cancel.get()) return false;
                    Object root;
                    try { root = Json.parse(s); } catch (RuntimeException ex) { return true; }
                    for (int i = 0; i < cols.length; i++) {
                        String v = LogTableModel.displayValue(cols[i], root, s);
                        if (sets.get(i).size() < DISTINCT_LIMIT) sets.get(i).add(v);
                        else if (!sets.get(i).contains(v)) overflow[i] = true;
                    }
                    return true;
                });
            } catch (IOException ex) {
                return;
            }
            if (cancel.get()) return;
            EventQueue.invokeLater(() -> {
                if (cancel.get() || model != m) return;
                for (int i = 0; i < cols.length; i++) {
                    ChoiceFilterButton b = choiceButtons.get(ks.get(i));
                    if (b == null) continue;
                    List<String> vals = new ArrayList<>(sets.get(i));
                    Comparator<String> cmp = String.CASE_INSENSITIVE_ORDER;
                    if (cols[i].logLevel()) cmp = Comparator.comparingInt(LogLevels::rank).thenComparing(cmp);
                    vals.sort(cmp);
                    b.setValues(vals, overflow[i]);
                }
            });
        }, "distinct");
        t.setDaemon(true);
        t.start();
    }

    private final class ColumnListener implements TableColumnModelListener {
        @Override public void columnMoved(TableColumnModelEvent e) {
            if (!rebuildingColumns && e.getFromIndex() != e.getToIndex()) {
                syncOrderFromView();
                scheduleSave();
            }
        }
        @Override public void columnMarginChanged(ChangeEvent e) { if (!rebuildingColumns) scheduleSave(); }
        @Override public void columnSelectionChanged(ListSelectionEvent e) {
            if (!e.getValueIsAdjusting()) showDetail(false);
        }
        @Override public void columnAdded(TableColumnModelEvent e) { }
        @Override public void columnRemoved(TableColumnModelEvent e) { }
    }

    /** Ermittelt anhand der ersten Zeilen, ob eine Spalte Zeitstempel oder Klassennamen enthält. */
    private Kind kind(int k) {
        CellFormatter f = model.column(k).formatter();
        if (f.isTimestamp()) return Kind.TIMESTAMP;
        if (f.isPackage()) return Kind.CLASS;
        Kind cached = kindCache.get(k);
        if (cached != null) return cached;
        if (index == null || index.lineCount() == 0 || model.column(k).isRaw()) return Kind.OTHER;
        int n = Math.min(KIND_SAMPLE, index.lineCount());
        int nonEmpty = 0, ts = 0, cls = 0;
        for (int i = 0; i < n; i++) {
            try {
                Object v = Json.get(Json.parse(index.readLine(i)), model.column(k).segments());
                if (v == null) continue;
                nonEmpty++;
                if (v instanceof String s) {
                    if (CellFormatter.parseInstant(s) != null) ts++;
                    else if (CellFormatter.isClassName(s)) cls++;
                }
            } catch (IOException | RuntimeException ignored) {
                // Zeile überspringen
            }
        }
        Kind kind = nonEmpty == 0 ? Kind.OTHER
                : ts == nonEmpty ? Kind.TIMESTAMP
                : cls * 10 >= nonEmpty * 8 ? Kind.CLASS
                : Kind.OTHER;
        if (n == KIND_SAMPLE || index.isComplete()) kindCache.put(k, kind);
        return kind;
    }

    private List<Integer> columnsOfKind(Kind kind) {
        List<Integer> l = new ArrayList<>();
        for (int k : order) if (kind(k) == kind) l.add(k);
        return l;
    }

    // ---------------------------------------------------------------- Menüs

    private void fillViewMenu(JMenu view) {
        view.removeAll();
        for (JMenuItem mi : columnToggles()) view.add(mi);
        view.addSeparator();
        JMenuItem all = new JMenuItem("Alle Spalten einblenden");
        all.addActionListener(e -> showAllColumns());
        view.add(all);
        view.addSeparator();
        List<Integer> ts = columnsOfKind(Kind.TIMESTAMP);
        JMenu tz = timezoneMenu(ts);
        if (ts.isEmpty()) {
            tz.setEnabled(false);
            tz.setToolTipText("Keine Spalte mit Zeitstempeln erkannt");
        }
        view.add(tz);
    }

    private List<JMenuItem> columnToggles() {
        List<JMenuItem> items = new ArrayList<>();
        for (int k : order) {
            Schema.Column c = model.column(k);
            JCheckBoxMenuItem cb = new JCheckBoxMenuItem(c.name(), c.visible());
            cb.addActionListener(e -> setColumnVisible(k, cb.isSelected()));
            items.add(cb);
        }
        return items;
    }

    /** Untermenü "Zeitzone" für die angegebenen Spalten. */
    private JMenu timezoneMenu(List<Integer> ks) {
        JMenu tz = new JMenu("Zeitzone");
        CellFormatter current = ks.isEmpty() ? CellFormatter.NONE : model.column(ks.get(0)).formatter();
        ButtonGroup g = new ButtonGroup();
        tz.add(radio(g, "Original (wie in der Datei)", current == CellFormatter.NONE, () -> ks.forEach(k -> setFormatter(k, CellFormatter.NONE))));
        tz.add(radio(g, "UTC", current == CellFormatter.TIMESTAMP_UTC, () -> ks.forEach(k -> setFormatter(k, CellFormatter.TIMESTAMP_UTC))));
        tz.add(radio(g, "Lokale Zeitzone (" + ZoneId.systemDefault().getId() + ")", current == CellFormatter.TIMESTAMP_LOCAL,
                () -> ks.forEach(k -> setFormatter(k, CellFormatter.TIMESTAMP_LOCAL))));
        return tz;
    }

    private static JRadioButtonMenuItem radio(ButtonGroup g, String text, boolean selected, Runnable action) {
        JRadioButtonMenuItem r = new JRadioButtonMenuItem(text, selected);
        g.add(r);
        r.addActionListener(e -> action.run());
        return r;
    }

    private int headerPopupX = -1;

    private void fillHeaderPopup(JPopupMenu pm) {
        pm.removeAll();
        Point mouse = table.getTableHeader().getMousePosition();
        int x = mouse != null ? mouse.x : headerPopupX;
        int v = x < 0 ? -1 : table.getTableHeader().columnAtPoint(new Point(x, 1));
        int mi = v < 0 ? 0 : table.convertColumnIndexToModel(v);
        if (mi > 0) {
            int k = mi - 1;
            JMenuItem hide = new JMenuItem("Ausblenden");
            hide.setEnabled(visibleCount() > 1);
            hide.addActionListener(ev -> setColumnVisible(k, false));
            pm.add(hide);
            Kind kind = kind(k);
            if (kind == Kind.TIMESTAMP) {
                pm.addSeparator();
                pm.add(timezoneMenu(List.of(k)));
            } else if (kind == Kind.CLASS) {
                pm.addSeparator();
                CellFormatter f = model.column(k).formatter();
                ButtonGroup g = new ButtonGroup();
                pm.add(radio(g, "Package original", f == CellFormatter.NONE, () -> setFormatter(k, CellFormatter.NONE)));
                pm.add(radio(g, "Package compact", f == CellFormatter.PACKAGE_COMPACT, () -> setFormatter(k, CellFormatter.PACKAGE_COMPACT)));
                pm.add(radio(g, "Class only", f == CellFormatter.CLASS_ONLY, () -> setFormatter(k, CellFormatter.CLASS_ONLY)));
            }
            pm.addSeparator();
            Schema.Column c = model.column(k);
            JCheckBoxMenuItem choice = new JCheckBoxMenuItem("Auswahlfilter", c.choiceFilter());
            choice.setToolTipText("Dropdown mit allen vorkommenden Werten in der Filterleiste");
            choice.addActionListener(ev -> setChoiceFilter(k, choice.isSelected()));
            pm.add(choice);
            JCheckBoxMenuItem level = new JCheckBoxMenuItem("Spaltentyp Loglevel", c.logLevel());
            level.setToolTipText("Filter nach Mindest-Level (Log4j, Logback, JUL)");
            level.addActionListener(ev -> setLogLevelType(k, level.isSelected()));
            pm.add(level);
            pm.addSeparator();
        }
        JMenu cols = new JMenu("Spalten");
        for (JMenuItem m : columnToggles()) cols.add(m);
        cols.addSeparator();
        JMenuItem all = new JMenuItem("Alle einblenden");
        all.addActionListener(ev -> showAllColumns());
        cols.add(all);
        pm.add(cols);
    }

    // ================================================================== Schema speichern

    /** Aktueller Zustand (Reihenfolge, Breite, Sichtbarkeit, Formatter) als Schema. */
    private Schema snapshot() {
        List<Schema.Column> cols = new ArrayList<>();
        for (int k : order) {
            Schema.Column c = model.column(k);
            int w = tableColumns[k + 1].getWidth();
            cols.add(w > 0 ? c.withWidth(w) : c);
        }
        return model.schema().withColumns(cols);
    }

    private void scheduleSave() {
        saveTimer.restart();
    }

    private void flushSave() {
        if (saveTimer.isRunning()) {
            saveTimer.stop();
            saveSchemaNow();
        }
    }

    private void saveSchemaNow() {
        Schema s = snapshot();
        String text = s.toYaml();
        if (text.equals(lastSavedSnapshot)) return;
        rebuildScopeBox();
        if (schemaFile == null) return; // automatisches Schema: nur im Speicher
        try {
            s.save(schemaFile);
            lastSavedSnapshot = text;
            configuredSchema = s;
        } catch (IOException ex) {
            error("Schema konnte nicht gespeichert werden: " + schemaFile, ex);
        }
    }

    private void saveSchemaAs() {
        JFileChooser fc = chooser();
        fc.setFileFilter(new FileNameExtensionFilter("Schema (YAML, Properties)", "yaml", "yml", "properties"));
        fc.setSelectedFile(schemaFile != null ? schemaFile.toFile() : new File(fc.getCurrentDirectory(), "schema.yaml"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path target = fc.getSelectedFile().toPath();
        if (!isSchemaFile(target)) target = target.resolveSibling(target.getFileName() + ".yaml");
        try {
            Schema s = snapshot();
            s.save(target);
            schemaFile = target;
            configuredSchema = Schema.load(target);
            lastSavedSnapshot = s.toYaml();
            tick();
        } catch (Exception ex) {
            error("Speichern fehlgeschlagen", ex);
        }
    }

    // ================================================================== Datei / Schema laden

    private void chooseLog() {
        JFileChooser fc = chooser();
        fc.addChoosableFileFilter(new FileNameExtensionFilter("JSON (*.json, *.jsonl)", "json", "jsonl"));
        fc.addChoosableFileFilter(new FileNameExtensionFilter("Log (*.log)", "log"));
        fc.setFileFilter(fc.getAcceptAllFileFilter());
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

    private JFileChooser chooser() {
        return new JFileChooser(prefs.get("lastDir", System.getProperty("user.dir")));
    }

    private static boolean isSchemaFile(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".yaml") || n.endsWith(".yml") || n.endsWith(".properties");
    }

    void loadSchema(Path file) {
        flushSave();
        try {
            configuredSchema = Schema.load(file);
            schemaFile = file;
            applySchema();
        } catch (Exception ex) {
            error("Schema konnte nicht geladen werden: " + file, ex);
        }
    }

    private void reload() {
        flushSave();
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
        flushSave();
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
                        startDistinctScan();
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
        buildColumns();
        lastSavedSnapshot = snapshot().toYaml();
        rebuildScopeBox();
        distinctCancel.set(true);
        choiceButtons = new LinkedHashMap<>(); // neues Modell: Auswahlen verwerfen
        levelBoxes = new LinkedHashMap<>();
        rebuildChoiceBar();
        filterStatus.setText(" ");
        lastDetailKey = null;
        detail.setText("");
        detailHeader.setText(" ");
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
        else if (configuredSchema != null) sb.append("  —  Schema: ").append(configuredSchema.title).append(" (nicht gespeichert)");
        else sb.append("  —  Schema: automatisch (Änderungen werden nicht gespeichert)");
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
        for (JComboBox<String> box : levelBoxes.values()) box.setEnabled(ok);
        for (ChoiceFilterButton b : choiceButtons.values()) b.setEnabled(ok);
    }

    /** Spaltenbedingung aus Auswahlfilter bzw. Loglevel-Filter. */
    private record ColumnCondition(Schema.Column col, Set<String> allowed, int minRank) {
        boolean test(Object root, String line) {
            String v = LogTableModel.displayValue(col, root, line);
            if (allowed != null && !allowed.contains(v)) return false;
            return minRank <= 0 || LogLevels.rank(v) >= minRank;
        }
    }

    private List<ColumnCondition> columnConditions() {
        List<ColumnCondition> conds = new ArrayList<>();
        for (Map.Entry<Integer, ChoiceFilterButton> e : choiceButtons.entrySet()) {
            Set<String> sel = e.getValue().selection();
            if (!sel.isEmpty()) conds.add(new ColumnCondition(model.column(e.getKey()), sel, 0));
        }
        for (Map.Entry<Integer, JComboBox<String>> e : levelBoxes.entrySet()) {
            int rank = e.getValue().getSelectedIndex(); // 0 = alle, sonst Rang
            if (rank > 0) conds.add(new ColumnCondition(model.column(e.getKey()), null, rank));
        }
        return conds;
    }

    /** "Zurücksetzen" in der Filterleiste: Text-, Auswahl- und Level-Filter leeren. */
    private void resetAllFilters() {
        filterField.setText("");
        resetChoiceWidgets();
        clearFilter();
    }

    /** Wendet Textfilter, Auswahlfilter und Loglevel-Filter gemeinsam (UND) an. */
    private void applyFilter() {
        if (index == null || !index.isComplete()) return;
        String text = filterField.getText();
        List<ColumnCondition> conds = columnConditions();
        if (text.isEmpty() && conds.isEmpty()) { clearFilter(); return; }

        Predicate<String> matcher = null;
        if (text.isEmpty()) {
            // nur Spaltenbedingungen
        } else if (regexBox.isSelected()) {
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
        int sel = scopeBox.getSelectedIndex();
        // Filter auf eine Spalte vergleicht mit dem angezeigten (formatierten) Wert
        Schema.Column col = sel > 0 ? model.column(scopeColumns.get(sel - 1)) : null;
        Predicate<String> textMatcher = matcher;
        boolean needJson = !conds.isEmpty() || (textMatcher != null && col != null && !col.isRaw());

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
                    Object root = null;
                    boolean parsed = false;
                    if (needJson) {
                        try { root = Json.parse(s); parsed = true; } catch (RuntimeException ex) { /* kein JSON */ }
                    }
                    boolean ok = true;
                    if (textMatcher != null) {
                        String target;
                        if (col == null || col.isRaw()) target = s;
                        else target = parsed ? LogTableModel.displayValue(col, root, s) : null;
                        boolean m = target != null && textMatcher.test(target);
                        ok = m != invert;
                    }
                    if (ok && !conds.isEmpty()) {
                        if (!parsed) ok = false;
                        else for (ColumnCondition c : conds) if (!c.test(root, s)) { ok = false; break; }
                    }
                    if (ok) {
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

    /**
     * Klick auf "#": komplette Zeile als formatiertes JSON.
     * Klick auf eine andere Zelle: nur deren Wert, mit echten Zeilenumbrüchen und Unicode.
     */
    private void showDetail(boolean force) {
        int r = table.getSelectedRow();
        if (r < 0 || index == null) {
            lastDetailKey = null;
            detail.setText("");
            detailHeader.setText(" ");
            return;
        }
        int lead = table.getColumnModel().getSelectionModel().getLeadSelectionIndex();
        int mi = lead >= 0 && lead < table.getColumnCount() ? table.convertColumnIndexToModel(lead) : 0;
        int line = model.lineAt(r);
        String key = line + ":" + mi;
        if (!force && key.equals(lastDetailKey)) return;
        lastDetailKey = key;

        String text;
        try {
            text = index.readLine(line);
        } catch (IOException ex) {
            detail.setText("Lesefehler: " + ex.getMessage());
            return;
        }
        String body;
        boolean wrap;
        if (mi == 0) {
            detailHeader.setText("Zeile " + (line + 1) + "  —  komplette Zeile");
            wrap = false;
            try {
                body = Json.stringify(Json.parse(text), true);
            } catch (RuntimeException ex) {
                body = "⚠ Kein gültiges JSON: " + ex.getMessage() + "\n\n" + text;
                wrap = true;
            }
        } else {
            Schema.Column c = model.column(mi - 1);
            detailHeader.setText("Zeile " + (line + 1) + "  —  " + c.name()
                    + (c.name().equals(c.path()) ? "" : "  (" + c.path() + ")"));
            wrap = true;
            if (c.isRaw()) body = text;
            else {
                try {
                    Object v = Json.get(Json.parse(text), c.segments());
                    if (v == null) body = "(kein Wert)";
                    else if (v instanceof Map || v instanceof List) {
                        body = Json.stringify(v, true);
                        wrap = false;
                    } else {
                        String raw = v.toString(); // JSON-Escapes (\n, \t, \\uXXXX) sind bereits aufgelöst
                        String formatted = c.formatter().apply(raw);
                        body = formatted.equals(raw) ? raw : formatted + "\n\nOriginal: " + raw;
                    }
                } catch (RuntimeException ex) {
                    body = "⚠ Kein gültiges JSON: " + ex.getMessage() + "\n\n" + text;
                }
            }
        }
        detail.setLineWrap(wrap);
        detail.setWrapStyleWord(true);
        detail.setText(body);
        detail.setCaretPosition(0);
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
                System.out.println("Aufruf: java -jar beswinged.jar [logdatei] [--schema schema.yaml|schema.properties]");
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
