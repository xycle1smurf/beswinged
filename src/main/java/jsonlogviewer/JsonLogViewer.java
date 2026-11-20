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
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
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
import javax.swing.JRadioButton;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
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

    private String lastFilterName;

    private final JTable table = new JTable();
    private final JTextArea detail = new JTextArea();
    private final JLabel detailHeader = new JLabel(" ");
    /** Highlight-Filter rechts in der Trennleiste: markiert Regex-Treffer in der Detailansicht gelb. */
    private final JTextField highlightField = new JTextField(18);
    private final JLabel highlightCount = new JLabel();
    private final List<int[]> highlightHits = new ArrayList<>();
    private int highlightPos = -1;
    private static final javax.swing.text.Highlighter.HighlightPainter HIGHLIGHT =
            new javax.swing.text.DefaultHighlighter.DefaultHighlightPainter(new Color(0xFFEB3B));
    private static final javax.swing.text.Highlighter.HighlightPainter HIGHLIGHT_CURRENT =
            new javax.swing.text.DefaultHighlighter.DefaultHighlightPainter(new Color(0xFFA726));
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

    private AtomicBoolean filterCancel = new AtomicBoolean();
    private volatile int filterProgress = -1; // Promille, -1 = kein Filter aktiv

    // Tail / Zeitsuche / Datei-Modus
    static final long SEEK_THRESHOLD = 1L << 30; // ab 1 GB per Seek-Index
    private final JCheckBoxMenuItem tailItem = new JCheckBoxMenuItem("Tail / Schreiben folgen");
    private final JCheckBoxMenuItem seekItem = new JCheckBoxMenuItem("Datei-Index (Seek)");
    private final Timer tailTimer = new Timer(500, e -> tailPoll());
    private boolean tailBusy;
    // Zeitraum (Von/Bis + Zusatzzeilen)
    private final JPanel timeBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
    private final DateTimeField fromField = new DateTimeField();
    private final DateTimeField toField = new DateTimeField();
    private final JSpinner extraBefore = new JSpinner(new SpinnerNumberModel(0, 0, 1_000_000, 1));
    private final JSpinner extraAfter = new JSpinner(new SpinnerNumberModel(0, 0, 1_000_000, 1));
    private final JLabel zoneLabel = new JLabel();
    private String zoneSample;         // Zeitstempel der ersten Zeile (für den Offset der Datei)
    private boolean rangeReapplyOnTail; // Zeitraum reicht bis ans Dateiende -> bei Tail neu berechnen
    private Predicate<String> activeFilter; // Zeilentest des angewendeten Filters (null = keiner)
    private int filteredUpTo;               // Zeilen < filteredUpTo hat der Filter geprüft

    public JsonLogViewer() {
        super("JSON Log Viewer");
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(1400, 850);
        setLocationByPlatform(true);
        saveTimer.setRepeats(false);

        setIconImages(HelpMenu.icons());
        buildMenu();
        JPanel north = new JPanel(new BorderLayout());
        north.add(buildToolbar(), BorderLayout.NORTH);
        choiceBar.setBorder(BorderFactory.createEmptyBorder(0, 4, 4, 4));
        choiceBar.setVisible(false);
        JPanel bars = new JPanel(new BorderLayout());
        bars.add(choiceBar, BorderLayout.NORTH);
        bars.add(buildTimeBar(), BorderLayout.SOUTH);
        north.add(bars, BorderLayout.SOUTH);
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
        // Rechtsklick wählt die Zelle unter der Maus (bestehende Mehrfachauswahl bleibt erhalten)
        table.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (!javax.swing.SwingUtilities.isRightMouseButton(e)) return;
                int r = table.rowAtPoint(e.getPoint()), c = table.columnAtPoint(e.getPoint());
                if (r < 0 || c < 0) return;
                if (table.isRowSelected(r)) {
                    table.getColumnModel().getSelectionModel().setSelectionInterval(c, c);
                    showDetail(false);
                } else table.changeSelection(r, c, false, false);
            }
        });
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
        JPanel detailBar = new JPanel(new BorderLayout(8, 0));
        detailBar.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
        detailBar.add(detailHeader, BorderLayout.CENTER);
        detailBar.add(buildHighlightBar(), BorderLayout.EAST);
        detailPanel.add(detailBar, BorderLayout.NORTH);
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
            @Override public void windowClosed(WindowEvent e) { flushSave(); saveOpenFiles(); closeAll(); System.exit(0); }
        });
        updateFilterControls();
        rebuildScopeBox();
    }

    // ================================================================== UI-Aufbau

    private void buildMenu() {
        JMenuBar bar = new JMenuBar();
        JMenu file = new JMenu("Datei");
        file.add(item("Log öffnen…", KeyEvent.VK_O, e -> chooseLog()));
        JMenu recentFiles = new JMenu("Zuletzt geöffnet");
        recentFiles.addMenuListener(new MenuListener() {
            @Override public void menuSelected(MenuEvent e) { fillRecentFilesMenu(recentFiles); }
            @Override public void menuDeselected(MenuEvent e) { }
            @Override public void menuCanceled(MenuEvent e) { }
        });
        file.add(recentFiles);
        file.add(item("Schließen", KeyEvent.VK_W, e -> closeCurrent(true)));
        file.addSeparator();
        file.add(item("Neu laden", KeyEvent.VK_F5, e -> reload()));
        tailItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_T, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        tailItem.setToolTipText("Neu angehängte Zeilen laufend einlesen und ans Ende scrollen");
        tailItem.addActionListener(e -> setTail(tailItem.isSelected()));
        file.add(tailItem);
        seekItem.setToolTipText("<html>An: nur Zeilen-Offsets und jeder " + LineIndex.TS_STEP + ". Zeitstempel im RAM, Zeilen werden aus der Datei gelesen.<br>"
                + "Aus: ganze Datei im RAM. Umschalten lädt die Datei neu; Filter und Auswahl bleiben erhalten.</html>");
        seekItem.addActionListener(e -> switchMode(seekItem.isSelected()));
        file.add(seekItem);
        file.addSeparator();
        file.add(item("Beenden", 0, e -> dispose()));

        JMenu schemaMenu = new JMenu("Schema");
        fillSchemaMenu(schemaMenu); // Tastenkürzel sofort verfügbar; Inhalt wird beim Öffnen aktualisiert
        schemaMenu.addMenuListener(new MenuListener() {
            @Override public void menuSelected(MenuEvent e) { fillSchemaMenu(schemaMenu); }
            @Override public void menuDeselected(MenuEvent e) { }
            @Override public void menuCanceled(MenuEvent e) { }
        });
        JMenu filterMenu = new JMenu("Filter");
        filterMenu.addMenuListener(new MenuListener() {
            @Override public void menuSelected(MenuEvent e) { fillFilterMenu(filterMenu); }
            @Override public void menuDeselected(MenuEvent e) { }
            @Override public void menuCanceled(MenuEvent e) { }
        });

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
        extras.addSeparator();
        extras.add(item("Connect…", KeyEvent.VK_K, e -> new ConnectDialog(this).setVisible(true)));
        extras.add(item("Connect Settings…", 0, e -> new ConnectSettingsDialog(this).setVisible(true)));
        bar.add(file);
        bar.add(schemaMenu);
        bar.add(filterMenu);
        bar.add(view);
        bar.add(nav);
        bar.add(extras);
        fillWindowMenu();
        bar.add(windowMenu);
        bar.add(HelpMenu.create(this));
        setJMenuBar(bar);
    }

    private void showGenerator() {
        flushSave();
        String current = schemaFile != null ? schemaFile.getFileName().toString()
                : model.schema().auto ? "automatisch" : model.schema().title;
        Schema currentSchema = index == null && configuredSchema == null ? null : snapshot();
        new GeneratorDialog(this, new File(lastDir()),
                currentSchema, current, this::openGenerated).setVisible(true);
    }

    /** Öffnet ein erzeugtes Log mit dem dafür verwendeten Schema. */
    private void openGenerated(Path log, Schema schema, Path file) {
        // Schema gilt nur für die neue Datei; die angezeigte Datei behält ihres
        Schema s;
        Path sf = null;
        if (file != null) {
            try {
                s = Schema.load(file);
                sf = file;
                Settings.get().rememberSchema(file, s.title);
            } catch (Exception ex) {
                error("Schema konnte nicht geladen werden: " + file, ex);
                s = null;
            }
        } else if (schema == null) {
            try (var in = JsonLogViewer.class.getResourceAsStream("logstash.schema.yaml")) {
                s = Schema.fromYaml(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            } catch (Exception ex) {
                s = null;
            }
        } else {
            s = schema; // aktuelles Schema
            sf = schemaFile;
        }
        pendingSchema = s;
        pendingSchemaFile = sf;
        pendingSchemaSet = true;
        try {
            Path abs = log.toAbsolutePath().normalize();
            OpenFile existing = find(abs);
            openLog(abs, existing != null ? existing.mode : defaultMode(abs), true); // überschriebene Datei neu einlesen
        } finally {
            pendingSchemaSet = false;
            pendingSchema = null;
            pendingSchemaFile = null;
        }
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

    /** Dritte Filterzeile: Zeitraum Von/Bis mit Kalender, Zusatzzeilen davor/danach. */
    private JPanel buildTimeBar() {
        timeBar.setBorder(BorderFactory.createEmptyBorder(0, 4, 4, 4));
        for (DateTimeField f : new DateTimeField[]{fromField, toField}) {
            f.setZoneSupplier(this::displayZone);
            f.setReferenceSupplier(this::referenceTime);
            f.textField().addActionListener(e -> applyTimeRange());
        }
        for (JSpinner sp : new JSpinner[]{extraBefore, extraAfter}) {
            javax.swing.JFormattedTextField tf = ((JSpinner.DefaultEditor) sp.getEditor()).getTextField();
            tf.setColumns(5);
            tf.addActionListener(e -> applyTimeRange());
        }
        extraBefore.setToolTipText("Zeilen vor der ersten Zeile des Zeitraums, unabhängig von ihrer Zeit");
        extraAfter.setToolTipText("Zeilen nach der letzten Zeile des Zeitraums, unabhängig von ihrer Zeit");
        zoneLabel.setForeground(Color.GRAY);
        JButton apply = new JButton("Anwenden");
        apply.addActionListener(e -> applyTimeRange());
        JButton jump = new JButton("Gehe zu Von");
        jump.setToolTipText("Zur ersten Zeile ab „Von“ springen, ohne zu filtern");
        jump.addActionListener(e -> gotoFrom());
        JButton around = new JButton("Umliegend…");
        around.setToolTipText("Zeitraum um den Zeitstempel der ausgewählten Zeile (auch per Rechtsklick)");
        around.addActionListener(e -> showSurroundingRange());
        JButton clear = new JButton("Leeren");
        clear.addActionListener(e -> { clearTimeRange(); applyFilter(); });
        timeBar.add(new JLabel("Zeitraum von"));
        timeBar.add(fromField);
        timeBar.add(new JLabel("bis"));
        timeBar.add(toField);
        timeBar.add(zoneLabel);
        timeBar.add(Box.createHorizontalStrut(8));
        timeBar.add(new JLabel("Zusätzlich davor"));
        timeBar.add(extraBefore);
        timeBar.add(new JLabel("danach"));
        timeBar.add(extraAfter);
        timeBar.add(new JLabel("Zeilen"));
        timeBar.add(apply);
        timeBar.add(jump);
        timeBar.add(around);
        timeBar.add(clear);
        setTimeBarEnabled(false);
        return timeBar;
    }

    private void setTimeBarEnabled(boolean on) {
        if (timeBar.getComponentCount() > 0 && timeBar.getComponent(1).isEnabled() == on) return;
        for (Component c : timeBar.getComponents()) c.setEnabled(on);
    }

    private JPopupMenu buildPopup() {
        JPopupMenu pm = new JPopupMenu();
        pm.add(new JMenuItem(new AbstractAction("Kopiere Zelle") {
            @Override public void actionPerformed(ActionEvent e) { copyCell(); }
        }));
        pm.add(new JMenuItem(new AbstractAction("Kopiere als Markdown") {
            @Override public void actionPerformed(ActionEvent e) { copyAsMarkdown(); }
        }));
        pm.add(new JMenuItem(new AbstractAction("Umliegender Zeitraum…") {
            @Override public void actionPerformed(ActionEvent e) { showSurroundingRange(); }
        }));
        pm.addSeparator();
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
        view.addSeparator();
        view.add(item("Einstellungen…", 0, e -> showSettings())); // immer der letzte Eintrag
    }

    private void showSettings() {
        if (SettingsDialog.show(this)) table.repaint();
    }

    // ================================================================== Menü Schema

    private void fillSchemaMenu(JMenu menu) {
        menu.removeAll();
        menu.add(item("Schema laden…", KeyEvent.VK_L, e -> chooseSchema()));
        menu.add(item("Schema automatisch erkennen", 0, e -> {
            flushSave();
            configuredSchema = null;
            schemaFile = null;
            applySchema();
        }));
        menu.add(item("Schema speichern unter…", KeyEvent.VK_S, e -> saveSchemaAs()));
        menu.addSeparator();
        List<Settings.RecentSchema> recent = Settings.get().recentSchemas();
        if (recent.isEmpty()) {
            JMenuItem none = new JMenuItem("(noch keine Schemas geöffnet)");
            none.setEnabled(false);
            menu.add(none);
        }
        Path current = schemaFile == null ? null : schemaFile.toAbsolutePath().normalize();
        int n = 0;
        for (Settings.RecentSchema r : recent) {
            n++;
            boolean exists = java.nio.file.Files.isRegularFile(r.source());
            boolean cached = r.cache() != null && java.nio.file.Files.isRegularFile(r.cache());
            String title = r.title() == null || r.title().isBlank() ? "" : "  —  " + r.title();
            JMenuItem mi = new JCheckBoxMenuItem((n < 10 ? n + " " : n + "  ") + r.source().getFileName() + title
                    + (exists ? "" : cached ? "  (Kopie aus Cache)" : "  (fehlt)"));
            mi.setSelected(r.source().equals(current) || (r.cache() != null && r.cache().equals(current)));
            if (n < 10) mi.setMnemonic(KeyEvent.VK_0 + n);
            mi.setToolTipText("<html>" + r.source() + (cached ? "<br>Cache: " + r.cache() : "")
                    + (r.lastUsed() != null ? "<br>Zuletzt: " + r.lastUsed() : "") + "</html>");
            mi.setEnabled(exists || cached);
            mi.addActionListener(e -> openRecentSchema(r));
            menu.add(mi);
        }
        if (!recent.isEmpty()) {
            menu.addSeparator();
            menu.add(item("Liste leeren", 0, e -> Settings.get().clearRecent()));
        }
    }

    /** Öffnet ein zuletzt verwendetes Schema; fehlt das Original, die Kopie aus dem Cache. */
    private void openRecentSchema(Settings.RecentSchema r) {
        if (java.nio.file.Files.isRegularFile(r.source())) {
            loadSchema(r.source());
        } else if (r.cache() != null && java.nio.file.Files.isRegularFile(r.cache())) {
            JOptionPane.showMessageDialog(this, "Das Schema gibt es nicht mehr:\n" + r.source()
                    + "\n\nGeöffnet wird die Kopie aus dem Cache:\n" + r.cache()
                    + "\n(Änderungen werden in der Kopie gespeichert.)", "Schema", JOptionPane.INFORMATION_MESSAGE);
            loadSchema(r.cache(), false);
        }
    }

    // ================================================================== Menü Filter

    private void fillFilterMenu(JMenu menu) {
        menu.removeAll();
        JMenuItem saveAs = item("Speichern als…", 0, e -> saveFilterAs());
        saveAs.setEnabled(index != null);
        menu.add(saveAs);
        menu.addSeparator();
        List<Path> files = SavedFilter.list();
        if (files.isEmpty()) {
            JMenuItem none = new JMenuItem("(keine gespeicherten Filter)");
            none.setEnabled(false);
            menu.add(none);
        }
        for (Path p : files) {
            JMenuItem mi = new JMenuItem(SavedFilter.displayName(p));
            mi.setToolTipText(p.toString());
            mi.setEnabled(index != null);
            mi.addActionListener(e -> loadFilter(p));
            menu.add(mi);
        }
        if (!files.isEmpty()) {
            menu.addSeparator();
            JMenu del = new JMenu("Löschen");
            for (Path p : files) {
                JMenuItem mi = new JMenuItem(SavedFilter.displayName(p));
                mi.addActionListener(e -> {
                    if (JOptionPane.showConfirmDialog(this, "Filter „" + SavedFilter.displayName(p) + "“ löschen?",
                            "Filter löschen", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
                    try {
                        java.nio.file.Files.deleteIfExists(p);
                    } catch (IOException ex) {
                        error("Löschen fehlgeschlagen", ex);
                    }
                });
                del.add(mi);
            }
            menu.add(del);
        }
    }

    /** Aktuelle Filtereinstellungen (Text, Auswahl, Level, Zeitraum) unter einem Namen speichern. */
    private void saveFilterAs() {
        String name = (String) JOptionPane.showInputDialog(this, "Name des Filters:", "Filter speichern",
                JOptionPane.QUESTION_MESSAGE, null, null, lastFilterName != null ? lastFilterName : "");
        if (name == null || name.isBlank()) return;
        Path file = SavedFilter.fileFor(name);
        if (java.nio.file.Files.exists(file) && JOptionPane.showConfirmDialog(this,
                "Es gibt bereits einen Filter „" + name.trim() + "“. Überschreiben?", "Filter speichern",
                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        SavedFilter f = currentFilter(name.trim());
        try {
            f.save(file);
            lastFilterName = name.trim();
            filterStatus.setText("Filter gespeichert: " + file.getFileName());
        } catch (IOException ex) {
            error("Filter konnte nicht gespeichert werden: " + file, ex);
        }
    }

    /** Aktueller Zustand aller Filter-Bedienelemente. */
    private SavedFilter currentFilter(String name) {
        Map<String, List<String>> choices = new LinkedHashMap<>();
        for (Map.Entry<Integer, ChoiceFilterButton> e : choiceButtons.entrySet()) {
            Set<String> sel = e.getValue().selection();
            if (!sel.isEmpty()) choices.put(model.column(e.getKey()).name(), sel.stream().sorted().toList());
        }
        Map<String, String> levels = new LinkedHashMap<>();
        for (Map.Entry<Integer, JComboBox<String>> e : levelBoxes.entrySet()) {
            int rank = e.getValue().getSelectedIndex();
            if (rank > 0) levels.put(model.column(e.getKey()).name(), LogLevels.GROUPS[rank - 1][0]);
        }
        int sel = scopeBox.getSelectedIndex();
        String scope = sel > 0 ? model.column(scopeColumns.get(sel - 1)).name() : "";
        return new SavedFilter(name, filterField.getText(), scope, regexBox.isSelected(),
                invertBox.isSelected(), choices, levels, fromField.getText().trim(), toField.getText().trim(),
                spinnerInt(extraBefore), spinnerInt(extraAfter));
    }

    private static boolean isEmpty(SavedFilter f) {
        return f.text().isEmpty() && f.choices().isEmpty() && f.minLevels().isEmpty() && f.from().isBlank() && f.to().isBlank();
    }

    /** Gespeicherten Filter einsetzen und anwenden. Spalten werden über ihren Namen gefunden. */
    private void loadFilter(Path file) {
        SavedFilter f;
        try {
            f = SavedFilter.load(file);
        } catch (IOException | RuntimeException ex) {
            error("Filter konnte nicht geladen werden: " + file, ex);
            return;
        }
        applySavedFilter(f, -1);
        lastFilterName = f.name();
    }

    /**
     * Setzt die Filter-Bedienelemente auf {@code f} und wendet sie an.
     * @param focusLine danach auszuwählende Zeile (-1 = bisherige Auswahl)
     */
    private void applySavedFilter(SavedFilter f, int focusLine) {
        List<String> missing = new ArrayList<>();
        boolean schemaChanged = false;
        // Spalten, die einen Auswahl-/Level-Filter brauchen, entsprechend einschalten
        for (String col : f.choices().keySet()) {
            int k = columnByName(col);
            if (k < 0) missing.add(col);
            else if (!model.column(k).choiceFilter()) { model.setColumn(k, model.column(k).withChoiceFilter(true)); schemaChanged = true; }
        }
        for (String col : f.minLevels().keySet()) {
            int k = columnByName(col);
            if (k < 0) missing.add(col);
            else if (!model.column(k).logLevel()) { model.setColumn(k, model.column(k).withLogLevel(true)); schemaChanged = true; }
        }
        if (schemaChanged) {
            rebuildChoiceBar();
            scheduleSave();
        }
        resetChoiceWidgets();
        for (Map.Entry<String, List<String>> e : f.choices().entrySet()) {
            ChoiceFilterButton b = choiceButtons.get(columnByName(e.getKey()));
            if (b != null) b.setSelection(e.getValue());
        }
        for (Map.Entry<String, String> e : f.minLevels().entrySet()) {
            JComboBox<String> box = levelBoxes.get(columnByName(e.getKey()));
            if (box == null) continue;
            ActionListener[] ls = box.getActionListeners();
            for (ActionListener l : ls) box.removeActionListener(l);
            box.setSelectedIndex(Math.min(box.getItemCount() - 1, LogLevels.rank(e.getValue())));
            for (ActionListener l : ls) box.addActionListener(l);
        }
        filterField.setText(f.text());
        regexBox.setSelected(f.regex());
        invertBox.setSelected(f.invert());
        int scopeIndex = 0;
        if (!f.scope().isBlank()) {
            int k = columnByName(f.scope());
            int i = scopeColumns.indexOf(k);
            if (k < 0 || i < 0) missing.add(f.scope());
            else scopeIndex = i + 1;
        }
        scopeBox.setSelectedIndex(scopeIndex);
        fromField.setText(f.from());
        toField.setText(f.to());
        extraBefore.setValue(f.linesBefore());
        extraAfter.setValue(f.linesAfter());
        if (!missing.isEmpty())
            JOptionPane.showMessageDialog(this, "Diese Spalten gibt es im aktuellen Schema nicht, ihr Filter entfällt:\n"
                    + String.join(", ", missing), "Filter " + f.name(), JOptionPane.WARNING_MESSAGE);
        if (isEmpty(f)) {
            if (focusLine >= 0 && model.getRowCount() > 0) selectRow(model.nearestViewRow(focusLine));
        } else applyFilter(focusLine, false);
    }

    // ================================================================== RAM / Seek umschalten

    /** Datei → Datei-Index (Seek): geöffnete Datei im anderen Modus neu laden; Filter und Auswahl bleiben. */
    private void switchMode(boolean seek) {
        if (current == null) { seekItem.setSelected(false); return; }
        LineIndex.Mode target = seek ? LineIndex.Mode.SEEK : LineIndex.Mode.RAM;
        if (current.mode == target) return;
        openLog(current.path, target, true); // merkt und stellt Filter und Auswahl wieder her
        seekItem.setSelected(current != null && current.mode == LineIndex.Mode.SEEK); // Abbruch im Speicherdialog
    }

    private int columnByName(String name) {
        for (int k = 0; k < model.schemaColumnCount(); k++) if (model.column(k).name().equals(name)) return k;
        for (int k = 0; k < model.schemaColumnCount(); k++) if (model.column(k).path().equals(name)) return k;
        return -1;
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
            Settings.get().refreshCache(schemaFile);
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
            Settings.get().rememberSchema(target, configuredSchema.title);
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

        // Zusatz rechts im Dialog: Laden in den RAM oder per Seek-Index (ab 1 GB vorausgewählt)
        JRadioButton ramButton = new JRadioButton("In den RAM laden");
        JRadioButton seekButton = new JRadioButton("Datei-Index (Seek)");
        ramButton.setToolTipText("Ganze Datei im Speicher: schnelles Scrollen und Filtern, braucht so viel RAM wie die Datei groß ist");
        seekButton.setToolTipText("Nur Zeilen-Offsets und jeden " + LineIndex.TS_STEP
                + ". Zeitstempel im RAM, Zeilen werden bei Bedarf aus der Datei gelesen");
        ButtonGroup g = new ButtonGroup();
        g.add(ramButton);
        g.add(seekButton);
        ramButton.setSelected(true);
        JLabel sizeLabel = new JLabel(" ");
        JPanel opts = new JPanel(new java.awt.GridLayout(0, 1, 0, 2));
        opts.setBorder(BorderFactory.createTitledBorder("Laden"));
        opts.add(ramButton);
        opts.add(seekButton);
        opts.add(sizeLabel);
        JPanel acc = new JPanel(new BorderLayout());
        acc.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 0));
        acc.add(opts, BorderLayout.NORTH);
        fc.setAccessory(acc);
        fc.addPropertyChangeListener(JFileChooser.SELECTED_FILE_CHANGED_PROPERTY, ev -> {
            File f = fc.getSelectedFile();
            if (f == null || !f.isFile()) { sizeLabel.setText(" "); return; }
            sizeLabel.setText("Größe: " + human(f.length()));
            (defaultMode(f.toPath()) == LineIndex.Mode.SEEK ? seekButton : ramButton).setSelected(true);
        });

        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            Settings.get().setLastDir(fc.getCurrentDirectory().getAbsolutePath());
            openLog(fc.getSelectedFile().toPath(), seekButton.isSelected() ? LineIndex.Mode.SEEK : LineIndex.Mode.RAM);
        }
    }

    /** Ab 1 GB per Seek-Index, sonst in den RAM. */
    static LineIndex.Mode defaultMode(Path file) {
        try {
            return java.nio.file.Files.size(file) >= SEEK_THRESHOLD ? LineIndex.Mode.SEEK : LineIndex.Mode.RAM;
        } catch (IOException e) {
            return LineIndex.Mode.SEEK;
        }
    }

    private void chooseSchema() {
        JFileChooser fc = chooser();
        fc.setFileFilter(new FileNameExtensionFilter("Schema (YAML, Properties)", "yaml", "yml", "properties"));
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            Settings.get().setLastDir(fc.getCurrentDirectory().getAbsolutePath());
            loadSchema(fc.getSelectedFile().toPath());
        }
    }

    private JFileChooser chooser() {
        return new JFileChooser(lastDir());
    }

    private static boolean isSchemaFile(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".yaml") || n.endsWith(".yml") || n.endsWith(".properties");
    }

    private static String lastDir() {
        String d = Settings.get().lastDir();
        return d != null && new File(d).isDirectory() ? d : System.getProperty("user.dir");
    }

    void loadSchema(Path file) {
        loadSchema(file, true);
    }

    /** @param remember in "Schema → zuletzt geöffnet" aufnehmen (nicht beim Öffnen der Cache-Kopie) */
    void loadSchema(Path file, boolean remember) {
        flushSave();
        try {
            configuredSchema = Schema.load(file);
            schemaFile = file;
            if (remember) Settings.get().rememberSchema(file, configuredSchema.title);
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
        if (current != null) openLog(current.path, current.mode, true); // Filter und Auswahl bleiben
        else applySchema();
    }

    void openLog(Path file) {
        Path abs = file.toAbsolutePath().normalize();
        OpenFile doc = find(abs);
        openLog(abs, doc != null ? doc.mode : defaultMode(abs), false);
    }

    void openLog(Path file, LineIndex.Mode mode) {
        openLog(file, mode, false);
    }

    /**
     * Öffnet eine Datei (neuer Eintrag unten im Menü Fenster) oder zeigt sie an, wenn sie schon offen ist.
     * @param reindex auch eine bereits offene Datei neu einlesen (Neu laden, Moduswechsel, gekürzte Datei)
     */
    void openLog(Path file, LineIndex.Mode mode, boolean reindex) {
        Path abs = file.toAbsolutePath().normalize();
        OpenFile doc = find(abs);
        if (doc != null && !reindex && doc.mode == mode) {
            activate(doc);
            Settings.get().rememberFile(abs);
            return;
        }
        if (mode == LineIndex.Mode.RAM) {
            // Passt die Datei in den Heap? Sonst Seek anbieten.
            try {
                long fileBytes = java.nio.file.Files.size(abs);
                Runtime rt = Runtime.getRuntime();
                long free = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
                        + (doc != null && doc.index != null ? doc.index.ramBytes() : 0); // wird beim Neu-Einlesen frei
                if (fileBytes > free - (256L << 20)) {
                    int answer = JOptionPane.showConfirmDialog(this, String.format(
                            "Die Datei (%s) passt nicht sicher in den verfügbaren Speicher (%s).%n"
                            + "Stattdessen per Datei-Index (Seek) öffnen?%n%n"
                            + "(Mehr Speicher z.B. mit: java -Xmx8g -jar beswinged.jar)",
                            human(fileBytes), human(Math.max(0, free))),
                            "Zu wenig Speicher", JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
                    if (answer == JOptionPane.YES_OPTION) mode = LineIndex.Mode.SEEK;
                    else if (answer != JOptionPane.NO_OPTION) return;
                }
            } catch (IOException ignored) {
                // Fehler meldet das Öffnen
            }
        }
        flushSave();
        boolean isNew = doc == null;
        if (isNew) {
            doc = new OpenFile(abs, mode);
            doc.schema = configuredSchema; // neue Datei übernimmt das aktuelle Schema
            doc.schemaFile = schemaFile;
        } else if (doc == current) {
            storeState(doc); // Filter, Auswahl und Schema nach dem Neu-Einlesen wiederherstellen
        }
        if (pendingSchemaSet) { // Log-Generator: neues Schema, alter Filterzustand passt nicht mehr
            doc.schema = pendingSchema;
            doc.schemaFile = pendingSchemaFile;
            doc.filter = null;
            doc.selectedLine = -1;
        }
        LineIndex.Mode oldMode = doc.mode;
        doc.mode = mode;
        closeDocIndex(doc);
        if (!startIndex(doc)) {
            doc.mode = oldMode;
            if (!isNew && doc == current) closeCurrent(false);
            return;
        }
        if (isNew) openFiles.add(doc);
        if (doc == current) current = null; // erneut vollständig anzeigen (Zustand ist gemerkt)
        activate(doc);
        Settings.get().rememberFile(abs);
    }

    /** Legt den Index an und indiziert im Hintergrund. */
    private boolean startIndex(OpenFile doc) {
        LineIndex idx;
        try {
            idx = new LineIndex(doc.path, doc.mode, timestampPathFor(doc.schema));
        } catch (IOException ex) {
            error("Datei konnte nicht geöffnet werden: " + doc.path, ex);
            return false;
        }
        AtomicBoolean cancel = new AtomicBoolean();
        doc.index = idx;
        doc.cancel = cancel;
        doc.millis = -1;
        doc.startNanos = System.nanoTime();
        Thread t = new Thread(() -> {
            try {
                idx.build(cancel::get);
                EventQueue.invokeLater(() -> {
                    if (doc.index != idx) return;
                    doc.millis = (System.nanoTime() - doc.startNanos) / 1_000_000;
                    if (current == doc && index == idx) {
                        tick();
                        updateFilterControls();
                        startDistinctScan();
                        if (tailItem.isSelected()) scrollToEnd();
                        Runnable after = doc.afterIndexed;
                        doc.afterIndexed = null;
                        if (after != null) after.run();
                    }
                });
            } catch (IOException ex) {
                if (!cancel.get()) EventQueue.invokeLater(() -> error("Fehler beim Indizieren: " + doc.path, ex));
            }
        }, "indexer");
        t.setDaemon(true);
        t.start();
        return true;
    }

    // ================================================================== Offene Dateien (Menü Fenster)

    /** Eine geöffnete Log-Datei mit ihrem Anzeigezustand. */
    private static final class OpenFile {
        final Path path;
        LineIndex.Mode mode;
        LineIndex index;                 // null = noch nicht eingelesen (aus den Einstellungen wiederhergestellt)
        AtomicBoolean cancel = new AtomicBoolean();
        long startNanos;
        long millis = -1;
        Schema schema;                   // null = automatisch
        Path schemaFile;
        SavedFilter filter;              // Filterzustand beim Wegschalten
        int selectedLine = -1;
        Runnable afterIndexed;           // nach dem Indizieren, sobald die Datei angezeigt wird

        OpenFile(Path path, LineIndex.Mode mode) { this.path = path; this.mode = mode; }
    }

    private final List<OpenFile> openFiles = new ArrayList<>();
    private OpenFile current;
    private Schema pendingSchema;      // Schema für die nächste neu geöffnete Datei (Log-Generator)
    private Path pendingSchemaFile;
    private boolean pendingSchemaSet;
    private final JMenu windowMenu = new JMenu("Fenster");

    private OpenFile find(Path abs) {
        for (OpenFile f : openFiles) if (f.path.equals(abs)) return f;
        return null;
    }

    /** Merkt Schema, Filter und Auswahl der angezeigten Datei. */
    private void storeState(OpenFile doc) {
        doc.schema = configuredSchema;
        doc.schemaFile = schemaFile;
        if (doc.afterIndexed != null) return; // Wiederherstellung steht noch aus: Zustand nicht überschreiben
        doc.filter = currentFilter("");
        doc.selectedLine = table.getSelectedRow() >= 0 && index != null ? model.lineAt(table.getSelectedRow()) : -1;
    }

    /** Zeigt eine offene Datei an (liest sie bei Bedarf erst ein) und stellt ihren Zustand wieder her. */
    private void activate(OpenFile doc) {
        if (doc == current) return;
        flushSave();
        if (current != null) storeState(current);
        if (doc.index == null && !startIndex(doc)) {
            openFiles.remove(doc);
            saveOpenFiles();
            return;
        }
        current = doc;
        filterCancel.set(true);
        filterProgress = -1;
        index = doc.index;
        configuredSchema = doc.schema;
        schemaFile = doc.schemaFile;
        zoneSample = null;
        resetFilterUi();
        applySchema();
        SavedFilter f = doc.filter;
        int line = doc.selectedLine;
        Runnable restore = () -> {
            if (f != null) applySavedFilter(f, line);
            else if (line >= 0 && model.getRowCount() > 0) selectRow(model.nearestViewRow(line));
            if (tailItem.isSelected()) scrollToEnd();
        };
        if (doc.index.isComplete()) {
            doc.afterIndexed = null;
            updateFilterControls();
            startDistinctScan();
            restore.run();
        } else doc.afterIndexed = restore;
        tick();
        saveOpenFiles();
    }

    /** Leert Text-, Auswahl-, Level-Filter und Zeitraum in der Oberfläche (ohne anzuwenden). */
    private void resetFilterUi() {
        filterField.setText("");
        regexBox.setSelected(false);
        invertBox.setSelected(false);
        if (scopeBox.getItemCount() > 0) scopeBox.setSelectedIndex(0);
        clearTimeRange();
        resetChoiceWidgets();
    }

    private void closeDocIndex(OpenFile doc) {
        doc.cancel.set(true);
        doc.afterIndexed = null;
        if (doc.index != null) {
            if (doc.index == index) filterCancel.set(true);
            try { doc.index.close(); } catch (IOException ignored) { }
            doc.index = null;
        }
    }

    /**
     * Datei → Schließen: angezeigte Datei schließen und aus Fenster entfernen. Angezeigt wird die Datei davor,
     * sonst die danach, sonst ein leeres Fenster.
     */
    private void closeCurrent(boolean flush) {
        if (flush) flushSave();
        OpenFile doc = current;
        int i = doc == null ? -1 : openFiles.indexOf(doc);
        if (doc != null) {
            closeDocIndex(doc);
            Kube.stop(doc.path); // laufendes "logs -f" dieser Datei beenden
            openFiles.remove(doc);
            current = null; // Zustand der geschlossenen Datei nicht merken
        }
        if (!openFiles.isEmpty()) {
            activate(openFiles.get(i > 0 ? i - 1 : 0));
            return;
        }
        index = null;
        filterCancel.set(true);
        filterProgress = -1;
        resetFilterUi();
        applySchema();
        tick();
        saveOpenFiles();
    }

    private void closeAll() {
        for (OpenFile f : openFiles) closeDocIndex(f);
        Kube.stopAll();
        filterCancel.set(true);
        index = null;
    }

    /** Liste für das Menü Fenster und die Einstellungen aktualisieren. */
    private void saveOpenFiles() {
        List<Settings.OpenEntry> entries = new ArrayList<>();
        for (OpenFile f : openFiles) {
            Path sch = f == current ? schemaFile : f.schemaFile;
            entries.add(new Settings.OpenEntry(f.path, f.mode, sch));
        }
        Settings.get().setOpenFiles(entries, current == null ? null : current.path);
        fillWindowMenu();
    }

    /** Beim Start: Dateien aus den Einstellungen übernehmen (eingelesen wird erst beim Anzeigen). */
    void restoreOpenFiles() {
        for (Settings.OpenEntry e : Settings.get().openFiles()) {
            Path p = e.path().toAbsolutePath().normalize();
            if (!java.nio.file.Files.isRegularFile(p) || find(p) != null) continue;
            OpenFile doc = new OpenFile(p, e.mode());
            if (e.schema() != null && java.nio.file.Files.isRegularFile(e.schema())) {
                try {
                    doc.schema = Schema.load(e.schema());
                    doc.schemaFile = e.schema();
                } catch (Exception ignored) {
                    // automatisch erkennen
                }
            }
            openFiles.add(doc);
        }
        fillWindowMenu();
    }

    /** Beim Start ohne Log-Argument: zuletzt angezeigte Datei anzeigen. */
    void showRestoredActive() {
        if (openFiles.isEmpty()) return;
        Path active = Settings.get().activeFile();
        OpenFile doc = active == null ? null : find(active.toAbsolutePath().normalize());
        activate(doc != null ? doc : openFiles.get(openFiles.size() - 1));
    }

    private void fillWindowMenu() {
        windowMenu.removeAll();
        if (openFiles.isEmpty()) {
            JMenuItem none = new JMenuItem("(keine Datei geöffnet)");
            none.setEnabled(false);
            windowMenu.add(none);
            return;
        }
        ButtonGroup g = new ButtonGroup();
        int n = 0;
        for (OpenFile f : openFiles) {
            n++;
            String info = f.mode == LineIndex.Mode.SEEK ? "Seek" : "RAM";
            JRadioButtonMenuItem mi = new JRadioButtonMenuItem(n + "  " + f.path.getFileName() + "   (" + info + ")");
            mi.setToolTipText(f.path.toString());
            mi.setSelected(f == current);
            if (n <= 9) {
                mi.setMnemonic(KeyEvent.VK_0 + n);
                mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_0 + n, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
            }
            mi.addActionListener(e -> activate(f));
            g.add(mi);
            windowMenu.add(mi);
        }
    }

    private void fillRecentFilesMenu(JMenu menu) {
        menu.removeAll();
        List<Path> files = Settings.get().recentFiles(20);
        if (files.isEmpty()) {
            JMenuItem none = new JMenuItem("(keine)");
            none.setEnabled(false);
            menu.add(none);
            return;
        }
        int n = 0;
        for (Path p : files) {
            n++;
            JMenuItem mi = new JMenuItem(n + "  " + p.getFileName() + "   —   " + (p.getParent() == null ? "" : p.getParent()));
            if (n <= 9) mi.setMnemonic(KeyEvent.VK_0 + n);
            mi.setToolTipText(p.toString());
            mi.addActionListener(e -> openLog(p));
            menu.add(mi);
        }
        menu.addSeparator();
        menu.add(item("Liste leeren", 0, e -> Settings.get().clearRecentFiles()));
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
        activeFilter = null;
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
            setTimeBarEnabled(false);
            seekItem.setEnabled(false);
            return;
        }
        if (autoSchemaPending && (index.lineCount() >= AUTO_SAMPLE || index.isComplete())) applySchema();
        model.refreshRowCount();

        long size = index.fileSize();
        StringBuilder sb = new StringBuilder();
        sb.append(index.path().getFileName()).append("  —  ")
          .append(String.format("%,d", index.lineCount())).append(" Zeilen  —  ")
          .append(human(size))
          .append(index.mode() == LineIndex.Mode.RAM ? "  —  im RAM" : "  —  Seek-Index");
        if (tailItem.isSelected()) sb.append("  —  Tail");
        seekItem.setSelected(index.mode() == LineIndex.Mode.SEEK);
        seekItem.setEnabled(true);
        boolean ts = index.timestampSlots() > 0;
        setTimeBarEnabled(ts);
        zoneLabel.setText(ts ? "(" + zoneName(displayZone()) + ")" : "");
        if (!index.isComplete()) {
            int permille = size == 0 ? 0 : (int) (index.bytesScanned() * 1000 / size);
            sb.append("  —  indiziere… ").append(permille / 10).append('%');
            progress.setValue(permille);
        } else if (current != null && current.millis >= 0) {
            sb.append("  —  indiziert in ").append(String.format("%.1f s", current.millis / 1000.0));
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
            if (allowed != null && !ChoiceFilterButton.matches(allowed, v)) return false;
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
        clearTimeRange();
        resetChoiceWidgets();
        clearFilter();
    }

    private void applyFilter() {
        applyFilter(-1, false);
    }

    /**
     * Wendet Zeitraum, Textfilter, Auswahlfilter und Loglevel-Filter gemeinsam (UND) an.
     * @param focusLine danach auszuwählende Zeile (-1 = bisherige Auswahl)
     * @param fromTail  vom Tail ausgelöst: Auswahl ohne Scrollen wiederherstellen
     */
    private void applyFilter(int focusLine, boolean fromTail) {
        if (index == null || !index.isComplete()) return;
        String text = filterField.getText();
        List<ColumnCondition> conds = columnConditions();
        LineRange range;
        try {
            range = timeRange();
        } catch (DateTimeException ex) {
            if (!fromTail) JOptionPane.showMessageDialog(this, ex.getMessage(), "Zeitraum", JOptionPane.ERROR_MESSAGE);
            return;
        } catch (IOException ex) {
            error("Lesefehler", ex);
            return;
        }
        if (text.isEmpty() && conds.isEmpty() && range == null) { clearFilter(); return; }

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
        Predicate<String> lineTest = s -> {
            Object root = null;
            boolean parsed = false;
            if (needJson) {
                try { root = Json.parse(s); parsed = true; } catch (RuntimeException ex) { /* kein JSON */ }
            }
            if (textMatcher != null) {
                String target;
                if (col == null || col.isRaw()) target = s;
                else target = parsed ? LogTableModel.displayValue(col, root, s) : null;
                boolean m = target != null && textMatcher.test(target);
                if (m == invert) return false;
            }
            if (!conds.isEmpty()) {
                if (!parsed) return false;
                for (ColumnCondition c : conds) if (!c.test(root, s)) return false;
            }
            return true;
        };

        filterCancel.set(true);
        AtomicBoolean cancel = new AtomicBoolean();
        filterCancel = cancel;
        LineIndex idx = index;
        LogTableModel targetModel = model;
        int total = idx.lineCount();
        int scanFrom = range == null ? 0 : range.lo(), scanTo = range == null ? total : range.hi();
        boolean trivial = textMatcher == null && conds.isEmpty(); // nur Zeitraum: keine Zeile lesen
        int selectedLine = focusLine >= 0 ? focusLine : table.getSelectedRow() >= 0 ? model.lineAt(table.getSelectedRow()) : -1;
        // Tail: offener Zeitraum -> neue Zeilen normal prüfen; endet er vor dem Dateiende -> keine neuen Treffer;
        // reicht ein begrenzter Zeitraum bis ans Ende -> bei neuen Zeilen neu berechnen
        boolean reapply = range != null && !range.openEnd() && range.hi() >= total;
        Predicate<String> tailTest = range != null && !range.openEnd() && !reapply ? s -> false : lineTest;
        filterProgress = 0;
        filterStatus.setText("filtere…");
        long t0 = System.nanoTime();

        Thread t = new Thread(() -> {
            int[][] hits = {new int[1024]};
            int[] count = {0};
            try {
                if (trivial) {
                    hits[0] = new int[Math.max(0, scanTo - scanFrom)];
                    for (int i = scanFrom; i < scanTo; i++) hits[0][count[0]++] = i;
                } else idx.scan(scanFrom, scanTo, (line, s) -> {
                    if ((line & 0xFFF) == 0) {
                        if (cancel.get()) return false;
                        int span = Math.max(1, scanTo - scanFrom);
                        filterProgress = (int) ((long) (line - scanFrom) * 1000 / span);
                    }
                    if (lineTest.test(s)) {
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
                activeFilter = tailTest;
                rangeReapplyOnTail = reapply;
                filteredUpTo = total;
                filterStatus.setText(String.format("Filter: %,d Treffer (%.1f s)", count[0], ms / 1000.0)
                        + (range != null ? "  —  Zeitraum " + range.desc() : ""));
                if (selectedLine >= 0 && count[0] > 0) {
                    int row = model.nearestViewRow(selectedLine);
                    if (fromTail) table.getSelectionModel().setSelectionInterval(row, row);
                    else selectRow(row);
                }
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
        activeFilter = null;
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
            applyHighlight(false);
            return;
        }
        int lead = table.getColumnModel().getSelectionModel().getLeadSelectionIndex();
        int mi = lead >= 0 && lead < table.getColumnCount() ? table.convertColumnIndexToModel(lead) : 0;
        int line = model.lineAt(r);
        String key = line + ":" + mi;
        if (!force && key.equals(lastDetailKey)) return;
        lastDetailKey = key;

        DetailText d;
        try {
            d = detailText(line, mi);
        } catch (IOException ex) {
            detail.setText("Lesefehler: " + ex.getMessage());
            return;
        }
        detailHeader.setText(d.header());
        detail.setLineWrap(d.wrap());
        detail.setWrapStyleWord(true);
        detail.setText(d.body());
        detail.setCaretPosition(0);
        applyHighlight(true);
    }

    // ------------------------------------------------------------------ Highlight-Filter

    private JPanel buildHighlightBar() {
        JPanel p = new JPanel(new BorderLayout(6, 0));
        JLabel l = new JLabel("Highlight Filter:");
        l.setLabelFor(highlightField);
        highlightField.setToolTipText("<html>Regex, Groß-/Kleinschreibung egal. Alle Treffer werden in der Detailansicht gelb markiert.<br>"
                + "Enter / Umschalt+Enter: nächster / vorheriger Treffer, Esc: leeren</html>");
        highlightField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { applyHighlight(true); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { applyHighlight(true); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { }
        });
        highlightField.addActionListener(e -> stepHighlight(1));
        highlightField.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, java.awt.event.InputEvent.SHIFT_DOWN_MASK), "hlPrev");
        highlightField.getActionMap().put("hlPrev", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { stepHighlight(-1); }
        });
        highlightField.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "hlClear");
        highlightField.getActionMap().put("hlClear", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { highlightField.setText(""); }
        });
        highlightCount.setForeground(Color.GRAY);
        highlightCount.setPreferredSize(new Dimension(80, highlightCount.getPreferredSize().height));
        p.add(l, BorderLayout.WEST);
        p.add(highlightField, BorderLayout.CENTER);
        p.add(highlightCount, BorderLayout.EAST);
        return p;
    }

    /** Markiert alle Treffer des Highlight-Filters in der Detailansicht; {@code scroll}: zum ersten Treffer. */
    private void applyHighlight(boolean scroll) {
        javax.swing.text.Highlighter h = detail.getHighlighter();
        h.removeAllHighlights();
        highlightHits.clear();
        highlightPos = -1;
        highlightField.setBackground(UIManager.getColor("TextField.background"));
        String q = highlightField.getText();
        if (q.isEmpty()) {
            highlightCount.setText("");
            return;
        }
        Pattern pat;
        try {
            pat = Pattern.compile(q, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.MULTILINE);
        } catch (PatternSyntaxException ex) {
            highlightField.setBackground(new Color(0xFFD6D6));
            highlightCount.setText("ungültig");
            return;
        }
        String text = detail.getText();
        java.util.regex.Matcher m = pat.matcher(text);
        while (m.find() && highlightHits.size() < 10_000) {
            if (m.end() > m.start()) highlightHits.add(new int[]{m.start(), m.end()});
            else if (m.end() >= text.length()) break; // leere Treffer (z.B. "a*") nicht markieren
        }
        for (int[] hit : highlightHits) {
            try {
                h.addHighlight(hit[0], hit[1], HIGHLIGHT);
            } catch (javax.swing.text.BadLocationException ignored) {
                // Text hat sich geändert
            }
        }
        highlightCount.setText(highlightHits.isEmpty() ? "keine Treffer" : highlightHits.size() + " Treffer");
        if (scroll && !highlightHits.isEmpty()) scrollToHit(highlightHits.get(0));
    }

    /** Springt zum nächsten/vorherigen Treffer und hebt ihn orange hervor. */
    private void stepHighlight(int dir) {
        if (highlightHits.isEmpty()) return;
        int n = highlightHits.size();
        highlightPos = highlightPos < 0 ? (dir > 0 ? 0 : n - 1) : Math.floorMod(highlightPos + dir, n);
        javax.swing.text.Highlighter h = detail.getHighlighter();
        h.removeAllHighlights();
        for (int i = 0; i < n; i++) {
            int[] hit = highlightHits.get(i);
            try {
                h.addHighlight(hit[0], hit[1], i == highlightPos ? HIGHLIGHT_CURRENT : HIGHLIGHT);
            } catch (javax.swing.text.BadLocationException ignored) {
                // Text hat sich geändert
            }
        }
        highlightCount.setText((highlightPos + 1) + " / " + n);
        scrollToHit(highlightHits.get(highlightPos));
    }

    private void scrollToHit(int[] hit) {
        javax.swing.SwingUtilities.invokeLater(() -> {
            try {
                java.awt.geom.Rectangle2D a = detail.modelToView2D(hit[0]), b = detail.modelToView2D(hit[1]);
                if (a == null || b == null) return;
                java.awt.Rectangle r = a.getBounds().union(b.getBounds());
                r.grow(0, 2 * r.height); // etwas Kontext ober- und unterhalb
                detail.scrollRectToVisible(r);
            } catch (javax.swing.text.BadLocationException ignored) {
                // Text hat sich geändert
            }
        });
    }

    /** Inhalt der Detailansicht; {@code copy} ist der Wert für "Kopiere Zelle" (ohne Zusatz "Original"). */
    private record DetailText(String header, String body, boolean wrap, String copy) {}

    private DetailText detailText(int line, int mi) throws IOException {
        String text = index.readLine(line);
        if (mi == 0) {
            String header = "Zeile " + (line + 1) + "  —  komplette Zeile";
            try {
                String body = Json.stringify(Json.parse(text), true);
                return new DetailText(header, body, false, body);
            } catch (RuntimeException ex) {
                return new DetailText(header, "⚠ Kein gültiges JSON: " + ex.getMessage() + "\n\n" + text, true, text);
            }
        }
        Schema.Column c = model.column(mi - 1);
        String header = "Zeile " + (line + 1) + "  —  " + c.name()
                + (c.name().equals(c.path()) ? "" : "  (" + c.path() + ")");
        if (c.isRaw()) return new DetailText(header, text, true, text);
        try {
            Object v = Json.get(Json.parse(text), c.segments());
            if (v == null) return new DetailText(header, "(kein Wert)", true, "");
            if (v instanceof Map || v instanceof List) {
                String body = Json.stringify(v, true);
                return new DetailText(header, body, false, body);
            }
            String raw = v.toString(); // JSON-Escapes (\n, \t, \\uXXXX) sind bereits aufgelöst
            String formatted = c.formatter().apply(raw);
            String body = formatted.equals(raw) ? raw : formatted + "\n\nOriginal: " + raw;
            return new DetailText(header, body, true, formatted);
        } catch (RuntimeException ex) {
            return new DetailText(header, "⚠ Kein gültiges JSON: " + ex.getMessage() + "\n\n" + text, true, text);
        }
    }

    /** Kopiert den Wert der Zelle (Zeile + Spalte der Auswahl) so, wie ihn die Detailansicht zeigt. */
    private void copyCell() {
        int r = table.getSelectedRow();
        if (r < 0 || index == null) return;
        int lead = table.getColumnModel().getSelectionModel().getLeadSelectionIndex();
        int mi = lead >= 0 && lead < table.getColumnCount() ? table.convertColumnIndexToModel(lead) : 0;
        try {
            String s = detailText(model.lineAt(r), mi).copy();
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(s), null);
        } catch (IOException ex) {
            error("Lesefehler", ex);
        }
    }

    // ================================================================== Zeitraum

    /** Zeilenbereich [lo, hi) eines Zeitraums inkl. Zusatzzeilen; {@code first} = erste Zeile im Zeitraum. */
    private record LineRange(int lo, int hi, int first, boolean openEnd, String desc) {}

    /** Zeitzone, in der Zeiten eingegeben und angezeigt werden (wie die Zeitstempel-Spalte). */
    private ZoneId displayZone() {
        if (index == null) return ZoneId.systemDefault();
        if (zoneSample == null && index.lineCount() > 0) {
            try { zoneSample = index.timestampText(index.readLine(0)); } catch (IOException ignored) { }
        }
        return inputZone(zoneSample);
    }

    private static String zoneName(ZoneId z) {
        if (z.equals(ZoneOffset.UTC)) return "UTC";
        if (z.equals(ZoneId.systemDefault()) && !(z instanceof ZoneOffset)) return "lokal, " + z.getId();
        return "UTC" + z.getId();
    }

    /** Zeitstempel der ausgewählten Zeile (sonst der ersten) in der Anzeige-Zeitzone; Vorschlag für leere Felder. */
    private LocalDateTime referenceTime() {
        ZoneId z = displayZone();
        if (index != null && index.lineCount() > 0) {
            try {
                int line = table.getSelectedRow() >= 0 ? model.lineAt(table.getSelectedRow()) : 0;
                String ts = index.timestampText(index.readLine(line));
                Instant i = ts == null ? null : CellFormatter.parseInstant(ts);
                if (i != null) return LocalDateTime.ofInstant(i, z).withNano(0);
            } catch (IOException ignored) { }
        }
        return LocalDateTime.now(z).withNano(0);
    }

    private static int spinnerInt(JSpinner sp) {
        try { sp.commitEdit(); } catch (java.text.ParseException ignored) { /* alter Wert */ }
        return (Integer) sp.getValue();
    }

    private Instant parseField(DateTimeField f, String name, ZoneId z, LocalDate def) {
        try {
            return DateTimeField.toInstant(f.getText(), z, def);
        } catch (DateTimeException ex) {
            throw new DateTimeException("„" + name + "“ nicht erkannt: " + f.getText().trim()
                    + "\n\nBeispiele: 2026-11-19 08:15:30   19.11.2026 08:15   08:15   2026-11-19T07:15:00Z");
        }
    }

    /** Zeitraum aus den Feldern Von/Bis; null, wenn beide leer sind. */
    private LineRange timeRange() throws IOException {
        if (fromField.getText().isBlank() && toField.getText().isBlank()) return null;
        if (index.timestampSlots() == 0) throw new DateTimeException("In dieser Datei wurden keine Zeitstempel erkannt.");
        ZoneId z = displayZone();
        LocalDate def = referenceTime().toLocalDate();
        Instant from = parseField(fromField, "Von", z, def);
        Instant to = parseField(toField, "Bis", z, def);
        if (from != null && to != null && from.isAfter(to)) throw new DateTimeException("„Von“ liegt nach „Bis“.");
        int before = spinnerInt(extraBefore), after = spinnerInt(extraAfter);
        int lines = index.lineCount();
        int start = from == null ? 0 : index.findLineAtOrAfter(from.toEpochMilli());
        int end = to == null ? lines : index.findLineAtOrAfter(to.toEpochMilli() + 1); // erste Zeile nach "Bis"
        end = Math.max(end, start);
        String desc = (from == null ? "…" : DateTimeField.format(LocalDateTime.ofInstant(from, z))) + " – "
                + (to == null ? "…" : DateTimeField.format(LocalDateTime.ofInstant(to, z)))
                + (before + after > 0 ? " (+" + before + "/+" + after + " Zeilen)" : "");
        return new LineRange(Math.max(0, start - before), (int) Math.min(lines, (long) end + after), start, to == null, desc);
    }

    /** "Anwenden" / Enter in Von/Bis: Filter setzen und zur ersten Zeile im Zeitraum springen. */
    private void applyTimeRange() {
        if (index == null) return;
        try {
            LineRange r = timeRange();
            applyFilter(r == null ? -1 : Math.min(r.first(), Math.max(0, index.lineCount() - 1)), false);
        } catch (DateTimeException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Zeitraum", JOptionPane.ERROR_MESSAGE);
        } catch (IOException ex) {
            error("Lesefehler", ex);
        }
    }

    private void clearTimeRange() {
        fromField.setText("");
        toField.setText("");
        extraBefore.setValue(0);
        extraAfter.setValue(0);
    }

    /** Springt zur ersten Zeile ab "Von" (ohne zu filtern); nutzt den Zeitstempel-Index (jede 20. Zeile). */
    private void gotoFrom() {
        if (index == null || index.timestampSlots() == 0) return;
        try {
            ZoneId z = displayZone();
            Instant target = parseField(fromField, "Von", z, referenceTime().toLocalDate());
            if (target == null) return;
            int line = index.findLineAtOrAfter(target.toEpochMilli());
            if (line < 0) return;
            int row = model.nearestViewRow(Math.min(line, index.lineCount() - 1));
            if (row < 0) {
                filterStatus.setText("Zeit: keine Zeile im Filter");
                return;
            }
            selectRow(row);
            String found = index.timestampText(index.readLine(model.lineAt(row)));
            filterStatus.setText("Zeit " + DateTimeField.format(LocalDateTime.ofInstant(target, z))
                    + " → Zeile " + (model.lineAt(row) + 1) + (found != null ? " (" + found + ")" : ""));
        } catch (DateTimeException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Zeitraum", JOptionPane.ERROR_MESSAGE);
        } catch (IOException ex) {
            error("Lesefehler", ex);
        }
    }

    /** Rechtsklick → Umliegender Zeitraum…: Dialog mit dem Zeitstempel der ausgewählten Zeile. */
    private void showSurroundingRange() {
        int r = table.getSelectedRow();
        if (r < 0 || index == null) return;
        int line = model.lineAt(r);
        ZoneId z = displayZone();
        Instant i;
        try {
            String ts = index.timestampText(index.readLine(line));
            i = ts == null ? null : CellFormatter.parseInstant(ts);
        } catch (IOException ex) {
            error("Lesefehler", ex);
            return;
        }
        if (i == null) {
            JOptionPane.showMessageDialog(this, "Die ausgewählte Zeile hat keinen erkannten Zeitstempel.",
                    "Umliegender Zeitraum", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        TimeRangeDialog.Range res = TimeRangeDialog.show(this, LocalDateTime.ofInstant(i, z), z, zoneName(z));
        if (res == null) return;
        fromField.setDateTime(res.from());
        toField.setDateTime(res.to());
        extraBefore.setValue(res.linesBefore());
        extraAfter.setValue(res.linesAfter());
        applyFilter(line, false); // ausgewählte Zeile bleibt ausgewählt
    }

    /**
     * Zeitzone für Eingaben ohne Offset: wie die Zeitstempel-Spalte angezeigt wird
     * (lokal / UTC), sonst der Offset aus der Datei.
     */
    private ZoneId inputZone(String sampleTimestamp) {
        String[] tsPath = index.timestampPath();
        for (int k = 0; k < model.schemaColumnCount(); k++) {
            Schema.Column c = model.column(k);
            boolean isTs = c.formatter().isTimestamp() || (tsPath != null && Arrays.equals(c.segments(), tsPath));
            if (!isTs) continue;
            if (c.formatter() == CellFormatter.TIMESTAMP_LOCAL) return ZoneId.systemDefault();
            if (c.formatter() == CellFormatter.TIMESTAMP_UTC) return ZoneOffset.UTC;
            break;
        }
        if (sampleTimestamp != null) {
            java.util.regex.Matcher m = Pattern.compile("([+-])(\\d{2}):?(\\d{2})?$").matcher(sampleTimestamp.trim());
            if (m.find() && sampleTimestamp.contains(":")) {
                try {
                    int h = Integer.parseInt(m.group(2)), mm = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
                    int sign = m.group(1).equals("-") ? -1 : 1;
                    return ZoneOffset.ofHoursMinutes(sign * h, sign * mm);
                } catch (DateTimeException ignored) { }
            }
        }
        return ZoneOffset.UTC;
    }

    /** Zeitstempel-Pfad aus dem Schema (Spalte mit Zeitstempel-Formatter oder Namen), sonst null = automatisch. */
    static String[] timestampPathFor(Schema s) {
        if (s == null) return null;
        for (Schema.Column c : s.columns) if (!c.isRaw() && c.formatter().isTimestamp()) return c.segments();
        for (Schema.Column c : s.columns) {
            String p = c.path().toLowerCase(Locale.ROOT);
            if (!c.isRaw() && (p.contains("timestamp") || p.endsWith("time") || p.equals("@t"))) return c.segments();
        }
        return null;
    }

    // ================================================================== Tail

    void setTail(boolean on) {
        tailItem.setSelected(on);
        if (on) {
            tailTimer.start();
            scrollToEnd();
        } else tailTimer.stop();
        tick();
    }

    private boolean isAtEnd() {
        java.awt.Rectangle vis = table.getVisibleRect();
        return vis.y + vis.height >= table.getHeight() - 2 * table.getRowHeight();
    }

    private void scrollToEnd() {
        int n = model.getRowCount();
        if (n > 0) table.scrollRectToVisible(table.getCellRect(n - 1, 0, true));
    }

    /** Liest im Hintergrund angehängte Zeilen; aktiver Filter und Auswahlwerte werden fortgeschrieben. */
    private void tailPoll() {
        LineIndex idx = index;
        if (idx == null || !idx.isComplete() || tailBusy) return;
        tailBusy = true;
        LogTableModel m = model;
        Predicate<String> filter = activeFilter;
        int filterFrom = Math.max(0, filteredUpTo - 1); // letzte Zeile kann gewachsen sein
        boolean follow = isAtEnd();
        List<Integer> ks = new ArrayList<>();
        for (Map.Entry<Integer, ChoiceFilterButton> e : choiceButtons.entrySet())
            if (!e.getValue().isLoading()) ks.add(e.getKey());
        Schema.Column[] cols = new Schema.Column[ks.size()];
        for (int i = 0; i < cols.length; i++) cols[i] = m.column(ks.get(i));

        Thread t = new Thread(() -> {
            try {
                int oldLines = idx.lineCount();
                long oldSize = idx.fileSize();
                int added = idx.extend();
                if (added < 0) { // Datei wurde gekürzt/neu begonnen
                    EventQueue.invokeLater(() -> {
                        tailBusy = false;
                        if (index == idx) openLog(idx.path(), idx.mode(), true);
                    });
                    return;
                }
                if (idx.fileSize() == oldSize) {
                    EventQueue.invokeLater(() -> tailBusy = false);
                    return;
                }
                int newLines = idx.lineCount();
                int distinctFrom = Math.max(0, oldLines - 1);
                int from = filter != null ? Math.min(filterFrom, distinctFrom) : distinctFrom;
                int[][] hits = {new int[64]};
                int[] hc = {0};
                List<Set<String>> sets = new ArrayList<>();
                for (int i = 0; i < cols.length; i++) sets.add(new HashSet<>());
                if (filter != null || cols.length > 0) {
                    idx.scan(from, newLines, (line, s) -> {
                        if (filter != null && line >= filterFrom && filter.test(s)) {
                            if (hc[0] == hits[0].length) hits[0] = Arrays.copyOf(hits[0], hc[0] * 2);
                            hits[0][hc[0]++] = line;
                        }
                        if (cols.length > 0 && line >= distinctFrom) {
                            try {
                                Object root = Json.parse(s);
                                for (int i = 0; i < cols.length; i++) sets.get(i).add(LogTableModel.displayValue(cols[i], root, s));
                            } catch (RuntimeException ignored) { }
                        }
                        return true;
                    });
                }
                EventQueue.invokeLater(() -> {
                    tailBusy = false;
                    if (index != idx || model != m) return;
                    if (oldLines > 0) m.invalidateLine(oldLines - 1);
                    if (filter != null && activeFilter == filter && rangeReapplyOnTail) {
                        applyFilter(-1, true); // Zeitraum bis zum Dateiende: neu berechnen
                    } else if (filter != null && activeFilter == filter) {
                        m.replaceFilterTail(filterFrom, hits[0], hc[0]);
                        filteredUpTo = newLines;
                        filterStatus.setText(String.format("Filter: %,d Treffer (Tail)", m.getRowCount()));
                    }
                    for (int i = 0; i < cols.length; i++) {
                        ChoiceFilterButton b = choiceButtons.get(ks.get(i));
                        if (b == null) continue;
                        Comparator<String> cmp = String.CASE_INSENSITIVE_ORDER;
                        if (cols[i].logLevel()) cmp = Comparator.comparingInt(LogLevels::rank).thenComparing(cmp);
                        b.addValues(sets.get(i), cmp, DISTINCT_LIMIT);
                    }
                    tick();
                    if (follow) scrollToEnd();
                });
            } catch (IOException ex) {
                EventQueue.invokeLater(() -> {
                    tailBusy = false;
                    setTail(false);
                    error("Tail abgebrochen", ex);
                });
            }
        }, "tail");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Ausgewählte Zeilen als Markdown für Dokumentationen: je Zeile eine Überschrift, kurze Werte als Liste,
     * mehrzeilige oder lange Werte (Nachricht, Stacktrace, JSON) als Codeblock. Sichtbare Spalten in Anzeigereihenfolge.
     */
    private void copyAsMarkdown() {
        int[] rows = table.getSelectedRows();
        if (rows.length == 0 || index == null) return;
        if (rows.length > 10_000) {
            JOptionPane.showMessageDialog(this, "Bitte höchstens 10.000 Zeilen auf einmal als Markdown kopieren.");
            return;
        }
        StringBuilder sb = new StringBuilder();
        try {
            for (int r : rows) {
                int line = model.lineAt(r);
                if (!sb.isEmpty()) sb.append("\n---\n\n");
                sb.append(markdownRow(line, index.readLine(line)));
            }
        } catch (IOException ex) {
            error("Lesefehler", ex);
            return;
        }
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(sb.toString()), null);
        filterStatus.setText(rows.length == 1 ? "Zeile als Markdown kopiert" : rows.length + " Zeilen als Markdown kopiert");
    }

    private String markdownRow(int line, String text) {
        StringBuilder head = new StringBuilder("### Zeile ").append(line + 1).append("\n\n");
        Object root;
        try {
            root = Json.parse(text);
        } catch (RuntimeException ex) {
            return head.append(fence(text, "text")).toString();
        }
        StringBuilder list = new StringBuilder();
        StringBuilder blocks = new StringBuilder();
        for (int vc = 0; vc < table.getColumnCount(); vc++) {
            int mc = table.convertColumnIndexToModel(vc);
            if (mc == 0) continue;
            Schema.Column c = model.column(mc - 1);
            String value;
            String lang = "text";
            if (c.isRaw()) {
                value = Json.stringify(root, true);
                lang = "json";
            } else {
                Object o = Json.get(root, c.segments());
                if (o == null) continue;
                if (o instanceof Map || o instanceof List) {
                    value = Json.stringify(o, true);
                    lang = "json";
                } else value = c.formatter().apply(o.toString());
            }
            if (value.isBlank()) continue;
            String label = "**" + mdEscape(c.name()) + ":**";
            if (lang.equals("json") || value.contains("\n") || value.length() > 120) {
                blocks.append('\n').append(label).append("\n\n").append(fence(value.stripTrailing(), lang));
            } else {
                list.append("- ").append(label).append(' ')
                    .append(value.indexOf(' ') < 0 ? inlineCode(value) : mdEscape(value)).append('\n');
            }
        }
        return head.append(list).append(blocks).toString();
    }

    /** Codeblock; der Zaun ist länger als jede Backtick-Folge im Inhalt. */
    private static String fence(String s, String lang) {
        int longest = 0, run = 0;
        for (int i = 0; i < s.length(); i++) {
            run = s.charAt(i) == '`' ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        String f = "`".repeat(Math.max(3, longest + 1));
        return f + lang + "\n" + s + "\n" + f + "\n";
    }

    private static String inlineCode(String s) {
        String f = s.contains("`") ? "``" : "`";
        return f + (s.startsWith("`") || s.endsWith("`") ? " " + s + " " : s) + f;
    }

    private static String mdEscape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (char ch : s.toCharArray()) {
            if ("\\`*_[]<>#|".indexOf(ch) >= 0) sb.append('\\');
            sb.append(ch);
        }
        return sb.toString();
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
        private Font styledBase;
        private Font[] styledFonts = new Font[4]; // Index = Font.BOLD | Font.ITALIC

        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus, int row, int col) {
            super.getTableCellRendererComponent(t, value, selected, focus, row, col);
            int modelCol = t.convertColumnIndexToModel(col);
            setHorizontalAlignment(modelCol == 0 ? SwingConstants.RIGHT : SwingConstants.LEFT);
            LogTableModel.Row r = model.row(row);
            Settings.LevelStyle st = r.levelRank() > 0 ? Settings.get().levelStyle(r.levelRank()) : Settings.LevelStyle.NONE;
            int fontStyle = (st.bold() ? Font.BOLD : 0) | (st.italic() ? Font.ITALIC : 0);
            Font base = t.getFont();
            if (fontStyle != 0) {
                if (styledBase != base) { styledBase = base; styledFonts = new Font[4]; }
                if (styledFonts[fontStyle] == null) styledFonts[fontStyle] = base.deriveFont(fontStyle);
                setFont(styledFonts[fontStyle]);
            } else setFont(base);
            if (!selected) {
                Color bg = r.invalid() ? invalidBg : r.background();
                setBackground(bg != null ? bg : t.getBackground());
                setForeground(modelCol == 0 ? lineNoFg : st.foreground() != null ? st.foreground() : t.getForeground());
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
        LineIndex.Mode mode = null;
        boolean tail = false;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ((a.equals("--schema") || a.equals("-s")) && i + 1 < args.length) schema = Path.of(args[++i]);
            else if (a.equals("--ram")) mode = LineIndex.Mode.RAM;
            else if (a.equals("--seek")) mode = LineIndex.Mode.SEEK;
            else if (a.equals("--tail") || a.equals("-f")) tail = true;
            else if (a.equals("-h") || a.equals("--help")) {
                System.out.println("Aufruf: java -jar beswinged.jar [logdatei] [--schema schema.yaml|schema.properties]"
                        + " [--ram|--seek] [--tail]");
                return;
            } else log = Path.of(a);
        }
        Path fLog = log, fSchema = schema;
        LineIndex.Mode fMode = mode;
        boolean fTail = tail;
        EventQueue.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) { }
            JsonLogViewer v = new JsonLogViewer();
            v.restoreOpenFiles(); // Menü Fenster wie beim letzten Mal
            if (fSchema != null) v.loadSchema(fSchema);
            if (fTail) v.setTail(true);
            if (fLog != null) { if (fMode != null) v.openLog(fLog, fMode, true); else v.openLog(fLog); }
            else if (fSchema == null) v.showRestoredActive();
            v.tick();
            v.setVisible(true);
        });
    }
}
