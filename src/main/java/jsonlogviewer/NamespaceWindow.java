package jsonlogviewer;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import jsonlogviewer.KubeResources.Health;
import jsonlogviewer.KubeResources.Res;

/**
 * Extras → Show Namespace: eigenes Fenster mit allen Objekten eines Namespaces als Kacheln (ähnlich Argo CD).
 * Doppelklick öffnet das Manifest in einem eigenen Fenster; mehrere Fenster gleichzeitig sind möglich.
 */
final class NamespaceWindow extends JFrame {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final String[] STATES = {"Alle Zustände", "Nicht gesund", "Degraded", "Progressing", "Suspended"};
    private static final int AUTO_SECONDS = 10;

    private final JsonLogViewer viewer;
    private final JComboBox<Settings.Cluster> cluster = new JComboBox<>();
    private final JComboBox<String> namespace = new JComboBox<>();
    private final JButton refresh = new JButton("Aktualisieren");
    private final JCheckBox auto = new JCheckBox("Auto (" + AUTO_SECONDS + " s)");
    private final JTextField search = new JTextField(16);
    private final JComboBox<String> state = new JComboBox<>(STATES);
    private final JButton kindsButton = new JButton("Arten ▾");
    private final JCheckBox system = new JCheckBox("Systemobjekte");
    private final JCheckBox emptyRs = new JCheckBox("Alte ReplicaSets");
    private final JLabel zoomLabel = new JLabel("100 %");
    private final JLabel status = new JLabel(" ");
    private final ResourceGraph graph = new ResourceGraph();
    private final Set<String> hiddenKinds = new TreeSet<>();
    private final Set<String> knownKinds = new TreeSet<>();
    private final Timer timer = new Timer(AUTO_SECONDS * 1000, e -> load(false));
    private KubeResources resources = new KubeResources();
    private String loadedKey = "";
    private KubeResources.Snapshot snapshot;
    private SwingWorker<?, ?> worker;

    NamespaceWindow(JsonLogViewer viewer) {
        super("Show Namespace");
        this.viewer = viewer;
        setIconImages(HelpMenu.icons());
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        namespace.setEditable(true);
        namespace.setPrototypeDisplayValue("ein-recht-langer-namespace-name");
        cluster.setPrototypeDisplayValue(new Settings.Cluster("ein-langer-clustername", "", "", "", "", ""));
        state.setPrototypeDisplayValue("Alle Zustände  ");
        search.putClientProperty("JTextField.placeholderText", "Name");
        search.setToolTipText("Kacheln nach Name filtern (Teilstring, Groß-/Kleinschreibung egal)");
        system.setToolTipText("default-ServiceAccount, kube-root-ca.crt, Token-Secrets usw. anzeigen");
        emptyRs.setToolTipText("ReplicaSets ohne Pods (frühere Versionen eines Deployments) anzeigen");
        auto.setToolTipText("Alle " + AUTO_SECONDS + " Sekunden neu laden");
        refresh.setToolTipText("Neu laden (F5)");

        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        row1.add(new JLabel("Cluster"));
        row1.add(cluster);
        JButton settings = new JButton("…");
        settings.setToolTipText("Connect Settings");
        settings.addActionListener(e -> openSettings());
        row1.add(settings);
        row1.add(Box.createHorizontalStrut(8));
        row1.add(new JLabel("Namespace"));
        row1.add(namespace);
        JButton listNs = new JButton("Liste");
        listNs.setToolTipText("Namespaces des Clusters laden");
        listNs.addActionListener(e -> loadNamespaces());
        row1.add(listNs);
        row1.add(Box.createHorizontalStrut(8));
        row1.add(refresh);
        row1.add(auto);

        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        row2.add(new JLabel("Filter"));
        row2.add(search);
        row2.add(state);
        row2.add(kindsButton);
        row2.add(system);
        row2.add(emptyRs);
        row2.add(Box.createHorizontalStrut(12));
        JButton out = new JButton("−"), in = new JButton("+"), fit = new JButton("Einpassen"), one = new JButton("100 %");
        out.setToolTipText("Verkleinern (Strg+Mausrad)");
        in.setToolTipText("Vergrößern (Strg+Mausrad)");
        out.addActionListener(e -> graph.setZoom(graph.zoom() / 1.2, null));
        in.addActionListener(e -> graph.setZoom(graph.zoom() * 1.2, null));
        fit.addActionListener(e -> graph.fit());
        one.addActionListener(e -> graph.setZoom(1.0, null));
        row2.add(out);
        row2.add(zoomLabel);
        row2.add(in);
        row2.add(one);
        row2.add(fit);

        JPanel north = new JPanel();
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        north.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        row1.setAlignmentX(LEFT_ALIGNMENT);
        row2.setAlignmentX(LEFT_ALIGNMENT);
        north.add(row1);
        north.add(row2);

        JScrollPane scroll = new JScrollPane(graph);
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        scroll.getHorizontalScrollBar().setUnitIncrement(24);
        scroll.setBorder(BorderFactory.createMatteBorder(1, 0, 1, 0, new Color(0xD5DEE5)));
        scroll.getViewport().setBackground(new Color(0xEEF2F5));
        status.setBorder(BorderFactory.createEmptyBorder(3, 8, 4, 8));

        add(north, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);

        graph.setOnOpen(this::openResource);
        graph.setOnPopup(this::popup);
        graph.addPropertyChangeListener("zoom", e -> zoomLabel.setText(Math.round(graph.zoom() * 100) + " %"));
        refresh.addActionListener(e -> load(true));
        auto.addActionListener(e -> { if (auto.isSelected()) { timer.restart(); load(false); } else timer.stop(); });
        cluster.addActionListener(e -> {
            Settings.Cluster c = (Settings.Cluster) cluster.getSelectedItem();
            if (c != null && !c.namespace().isBlank()) namespace.setSelectedItem(c.namespace());
        });
        namespace.addActionListener(e -> { if ("comboBoxEdited".equals(e.getActionCommand()) || namespace.isPopupVisible()) load(true); });
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { applyFilter(); }
            @Override public void removeUpdate(DocumentEvent e) { applyFilter(); }
            @Override public void changedUpdate(DocumentEvent e) { applyFilter(); }
        });
        state.addActionListener(e -> applyFilter());
        system.addActionListener(e -> applyFilter());
        emptyRs.addActionListener(e -> applyFilter());
        kindsButton.addActionListener(e -> kindsMenu().show(kindsButton, 0, kindsButton.getHeight()));
        getRootPane().registerKeyboardAction(e -> load(true), KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        getRootPane().registerKeyboardAction(e -> search.requestFocusInWindow(),
                KeyStroke.getKeyStroke(KeyEvent.VK_F, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), JComponent.WHEN_IN_FOCUSED_WINDOW);
        getRootPane().registerKeyboardAction(e -> { Res r = graph.selected(); if (r != null) openResource(r); },
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        applyFilter();

        fillClusters();
        Settings.ConnectPrefs p = Settings.get().connectPrefs();
        for (int i = 0; i < cluster.getItemCount(); i++) if (cluster.getItemAt(i).name().equals(p.cluster())) cluster.setSelectedIndex(i);
        Settings.Cluster c = (Settings.Cluster) cluster.getSelectedItem();
        namespace.setSelectedItem(!p.namespace().isEmpty() ? p.namespace() : c != null ? c.namespace() : "");

        setSize(1280, 820);
        setLocationByPlatform(true);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowOpened(java.awt.event.WindowEvent e) { if (!text(namespace).isEmpty()) load(true); }
            @Override public void windowClosed(java.awt.event.WindowEvent e) { timer.stop(); if (worker != null) worker.cancel(true); }
        });
        if (cluster.getItemCount() == 0) setStatus("Noch kein Cluster eingerichtet: Schaltfläche „…“ (Connect Settings) öffnen.", true);
    }

    // ------------------------------------------------------------------ Laden

    private void fillClusters() {
        Object sel = cluster.getSelectedItem();
        cluster.removeAllItems();
        for (Settings.Cluster c : Settings.get().clusters()) {
            cluster.addItem(c);
            if (sel instanceof Settings.Cluster s && s.name().equals(c.name())) cluster.setSelectedItem(c);
        }
    }

    private void openSettings() {
        ConnectSettingsDialog d = new ConnectSettingsDialog(this);
        d.setVisible(true);
        if (d.saved()) { fillClusters(); resources = new KubeResources(); }
    }

    private void loadNamespaces() {
        Settings.Cluster c = (Settings.Cluster) cluster.getSelectedItem();
        if (c == null) return;
        setStatus("Lade Namespaces …", false);
        new SwingWorker<List<String>, Void>() {
            @Override protected List<String> doInBackground() throws Exception { return new Kube(c).namespaces(); }
            @Override protected void done() {
                try {
                    String keep = text(namespace);
                    List<String> l = get();
                    namespace.removeAllItems();
                    for (String s : l) namespace.addItem(s);
                    namespace.setSelectedItem(keep);
                    setStatus(l.size() + " Namespaces. Einen auswählen.", false);
                    namespace.showPopup();
                } catch (Exception ex) {
                    setStatus(message(ex), true);
                }
            }
        }.execute();
    }

    /** @param explicit vom Benutzer ausgelöst (sonst Auto-Aktualisierung: Fehler leise, keine Sanduhr) */
    private void load(boolean explicit) {
        Settings.Cluster c = (Settings.Cluster) cluster.getSelectedItem();
        String ns = text(namespace);
        if (c == null || ns.isEmpty()) {
            if (explicit) setStatus(c == null ? "Kein Cluster eingerichtet." : "Bitte einen Namespace angeben.", true);
            return;
        }
        if (worker != null && !worker.isDone()) {
            if (!explicit) return;
            worker.cancel(true);
        }
        String key = c.name() + "/" + ns;
        boolean switched = !key.equals(loadedKey);
        if (switched) resources = new KubeResources();
        KubeResources res = resources;
        Kube kube = new Kube(c);
        if (explicit) {
            setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
            setStatus("Lade " + ns + " von " + c.name() + " …", false);
        }
        refresh.setEnabled(false);
        worker = new SwingWorker<KubeResources.Snapshot, Void>() {
            @Override protected KubeResources.Snapshot doInBackground() throws Exception { return res.load(kube, ns); }
            @Override protected void done() {
                setCursor(null);
                refresh.setEnabled(true);
                if (isCancelled()) return;
                try {
                    showSnapshot(get(), c, ns, switched);
                    loadedKey = key;
                    Settings.ConnectPrefs p = Settings.get().connectPrefs();
                    if (!p.cluster().equals(c.name()) || !p.namespace().equals(ns))
                        Settings.get().setConnectPrefs(new Settings.ConnectPrefs(c.name(), ns, p.kind(), p.name(), p.container(),
                                p.since(), p.tailLines(), p.previous(), p.follow()));
                } catch (Exception ex) {
                    setStatus((explicit ? "" : "Auto-Aktualisierung: ") + message(ex), true);
                }
            }
        };
        worker.execute();
    }

    private void showSnapshot(KubeResources.Snapshot s, Settings.Cluster c, String ns, boolean switched) {
        snapshot = s;
        setTitle("Namespace " + ns + " – " + c.name() + " – beswinged");
        knownKinds.clear();
        for (Res r : s.all()) knownKinds.add(r.kind);
        graph.setRoot(s.root());
        if (switched) {
            graph.select(null);
            javax.swing.SwingUtilities.invokeLater(graph::initialView);
        }
        updateStatus();
    }

    private void updateStatus() {
        if (snapshot == null) return;
        int[] counts = new int[Health.values().length];
        for (Res r : snapshot.all()) if (r.health != null && !hiddenBySystem(r)) counts[r.health.ordinal()]++;
        StringBuilder sb = new StringBuilder();
        sb.append(graph.visibleCount()).append(" von ").append(snapshot.all().size()).append(" Objekten angezeigt");
        for (Health h : Health.values()) {
            if (counts[h.ordinal()] == 0) continue;
            sb.append("  ·  <font color='#").append(String.format("%06X", h.rgb)).append("'>●</font> ")
              .append(counts[h.ordinal()]).append(' ').append(h.label);
        }
        sb.append("  ·  Stand ").append(CLOCK.format(snapshot.time()));
        if (!snapshot.errors().isEmpty()) sb.append("  ·  <font color='#B00020'>Kein Zugriff auf: ")
                .append(ResourceGraph.esc(String.join(", ", snapshot.errors().keySet()))).append("</font>");
        status.setForeground(Color.DARK_GRAY);
        status.setText("<html>" + sb + "</html>");
    }

    // ------------------------------------------------------------------ Filter

    private void applyFilter() {
        String q = search.getText().trim().toLowerCase(Locale.ROOT);
        String st = (String) state.getSelectedItem();
        boolean sys = system.isSelected(), rs = emptyRs.isSelected();
        graph.setFilter(r -> {
            if (!sys && hiddenBySystem(r)) return false;
            if (!rs && isOldReplicaSet(r)) return false;
            if (hiddenKinds.contains(r.kind)) return false;
            if (!q.isEmpty() && !r.name.toLowerCase(Locale.ROOT).contains(q)) return false;
            return switch (st) {
                case "Nicht gesund" -> r.health != null && r.health != Health.HEALTHY;
                case "Degraded" -> r.health == Health.DEGRADED;
                case "Progressing" -> r.health == Health.PROGRESSING;
                case "Suspended" -> r.health == Health.SUSPENDED;
                default -> true;
            };
        });
        kindsButton.setText(hiddenKinds.isEmpty() ? "Arten ▾" : "Arten (" + hiddenKinds.size() + " aus) ▾");
        updateStatus();
    }

    private static boolean hiddenBySystem(Res r) {
        for (Res p = r; p != null; p = p.parent) if (p.system) return true;
        return false;
    }

    private static boolean isOldReplicaSet(Res r) {
        if (!r.kind.equals("ReplicaSet") && !r.kind.equals("ReplicationController")) return false;
        if (r.parent == null || r.parent.isRoot()) return false;
        Object want = Kube.path(r.obj, "spec", "replicas"), have = Kube.path(r.obj, "status", "replicas");
        return "0".equals(Kube.str(want)) && (have == null || "0".equals(Kube.str(have))) && r.children.isEmpty();
    }

    private JPopupMenu kindsMenu() {
        JPopupMenu m = new JPopupMenu();
        Set<String> kinds = new LinkedHashSet<>(knownKinds);
        kinds.addAll(hiddenKinds);
        if (kinds.isEmpty()) {
            JMenuItem none = new JMenuItem("(erst laden)");
            none.setEnabled(false);
            m.add(none);
        }
        for (String k : kinds) {
            JCheckBoxMenuItem it = new JCheckBoxMenuItem(k, !hiddenKinds.contains(k));
            it.addActionListener(e -> {
                if (it.isSelected()) hiddenKinds.remove(k); else hiddenKinds.add(k);
                applyFilter();
            });
            m.add(it);
        }
        if (!kinds.isEmpty()) {
            m.addSeparator();
            JMenuItem all = new JMenuItem("Alle anzeigen");
            all.addActionListener(e -> { hiddenKinds.clear(); applyFilter(); });
            m.add(all);
        }
        return m;
    }

    // ------------------------------------------------------------------ Aktionen

    private void openResource(Res r) {
        if (r == null || r.isRoot()) return;
        Settings.Cluster c = currentCluster();
        if (c == null || snapshot == null) return;
        new ResourceWindow(viewer, c, snapshot.root().name, r).setVisible(true);
    }

    private Settings.Cluster currentCluster() {
        String name = loadedKey.contains("/") ? loadedKey.substring(0, loadedKey.indexOf('/')) : "";
        for (Settings.Cluster c : Settings.get().clusters()) if (c.name().equals(name)) return c;
        return (Settings.Cluster) cluster.getSelectedItem();
    }

    private void popup(MouseEvent e) {
        Res r = graph.at(e.getPoint());
        JPopupMenu m = new JPopupMenu();
        if (r != null && !r.isRoot()) {
            JMenuItem yaml = new JMenuItem("Manifest anzeigen");
            yaml.addActionListener(a -> openResource(r));
            m.add(yaml);
            if (r.hasLogs()) {
                JMenuItem logs = new JMenuItem("Logs…");
                logs.addActionListener(a -> openLogs(r));
                m.add(logs);
            }
            JMenuItem copy = new JMenuItem("Name kopieren");
            copy.addActionListener(a -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(r.name), null));
            m.add(copy);
            m.addSeparator();
            JMenuItem only = new JMenuItem("Nur „" + r.name + "“ und Verwandte zeigen");
            only.addActionListener(a -> search.setText(r.name));
            m.add(only);
            m.addSeparator();
        }
        JMenuItem reload = new JMenuItem("Aktualisieren");
        reload.addActionListener(a -> load(true));
        m.add(reload);
        JMenuItem fit = new JMenuItem("Einpassen");
        fit.addActionListener(a -> graph.fit());
        m.add(fit);
        m.show(graph, e.getX(), e.getY());
    }

    private void openLogs(Res r) {
        Settings.Cluster c = currentCluster();
        if (c == null || snapshot == null) return;
        ConnectDialog d = new ConnectDialog(viewer);
        d.preset(c.name(), snapshot.root().name, r.kind, r.name);
        d.setLocationRelativeTo(this);
        d.setVisible(true);
    }

    // ------------------------------------------------------------------ Hilfsfunktionen

    private void setStatus(String s, boolean error) {
        status.setForeground(error ? new Color(0xB00020) : Color.DARK_GRAY);
        status.setText("<html>" + ResourceGraph.esc(s).replace("\n", "<br>") + "</html>");
    }

    private static String text(JComboBox<?> c) {
        Object o = c.isEditable() ? c.getEditor().getItem() : c.getSelectedItem();
        return o == null ? "" : o.toString().trim();
    }

    private static String message(Exception ex) {
        Throwable t = ex instanceof java.util.concurrent.ExecutionException && ex.getCause() != null ? ex.getCause() : ex;
        if (t instanceof Json.ParseException) return "Antwort ist kein JSON: " + t.getMessage();
        return t instanceof IOException || t.getMessage() != null ? String.valueOf(t.getMessage()) : t.toString();
    }

    List<Res> visibleForTest() {
        List<Res> l = new ArrayList<>();
        if (snapshot != null) for (Res r : snapshot.all()) if (graph.find(r.key()) != null) l.add(r);
        return l;
    }
}
