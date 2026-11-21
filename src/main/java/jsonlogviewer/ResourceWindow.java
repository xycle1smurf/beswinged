package jsonlogviewer;

import static jsonlogviewer.Kube.path;
import static jsonlogviewer.Kube.str;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.KeyStroke;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import jsonlogviewer.KubeResources.Res;

/**
 * Manifest (YAML), Zusammenfassung und Events eines Objekts aus Show Namespace. Eigenes, nicht modales Fenster;
 * es können beliebig viele gleichzeitig offen sein. Werte von Secrets sind verdeckt, bis „Werte anzeigen" gewählt wird.
 */
final class ResourceWindow extends JFrame {

    private static final String MASK = "++++++++";
    private static final Color HIT = new Color(0xFFE94D);
    private static final Color HIT_CURRENT = new Color(0xFF9F1C);

    private final JsonLogViewer viewer;
    private final Settings.Cluster cluster;
    private final String namespace;
    private Res res;
    private final JTextPane yaml = new JTextPane() {
        @Override public boolean getScrollableTracksViewportWidth() { return getUI().getPreferredSize(this).width <= getParent().getSize().width; }
    };
    private final DefaultTableModel summaryModel = new DefaultTableModel(new Object[]{"Feld", "Wert"}, 0) {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };
    private final DefaultTableModel eventsModel = new DefaultTableModel(new Object[]{"Typ", "Grund", "Zuletzt", "Anzahl", "Meldung"}, 0) {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };
    private final JLabel header = new JLabel();
    private final JLabel status = new JLabel(" ");
    private final JTextField find = new JTextField(18);
    private final JLabel findInfo = new JLabel(" ");
    private final JCheckBox reveal = new JCheckBox("Werte anzeigen");
    private final JTabbedPane tabs = new JTabbedPane();
    private final List<int[]> hits = new ArrayList<>();
    private int hit = -1;
    private boolean eventsLoaded;
    private Instant fetched;

    ResourceWindow(JsonLogViewer viewer, Settings.Cluster cluster, String namespace, Res res) {
        this.viewer = viewer;
        this.cluster = cluster;
        this.namespace = namespace;
        this.res = res;
        setIconImages(HelpMenu.icons());
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setTitle(res.kind + " " + res.name + " – " + namespace + " – " + cluster.name());

        header.setFont(header.getFont().deriveFont(Font.BOLD, 14f));
        header.setBorder(BorderFactory.createEmptyBorder(8, 10, 2, 10));
        yaml.setEditable(false);
        yaml.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        yaml.setBackground(new Color(0xFBFBFB));
        yaml.setMargin(new java.awt.Insets(6, 8, 6, 8));

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        JButton reload = new JButton("Aktualisieren");
        reload.setToolTipText("Objekt neu vom Cluster holen (F5)");
        reload.addActionListener(e -> reload());
        JButton copy = new JButton("Kopieren");
        copy.setToolTipText("Manifest in die Zwischenablage");
        copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(yaml.getText()), null));
        JButton save = new JButton("Speichern unter…");
        save.addActionListener(e -> save());
        bar.add(reload);
        bar.add(copy);
        bar.add(save);
        if (res.kind.equals("Secret")) {
            reveal.setToolTipText("Werte Base64-dekodiert anzeigen");
            reveal.addActionListener(e -> render());
            bar.add(reveal);
        }
        if (res.hasLogs()) {
            JButton logs = new JButton("Logs…");
            logs.addActionListener(e -> {
                ConnectDialog d = new ConnectDialog(viewer);
                d.preset(cluster.name(), namespace, res.kind, res.name);
                d.setLocationRelativeTo(this);
                d.setVisible(true);
            });
            bar.add(logs);
        }
        bar.add(new JLabel("   Suchen"));
        bar.add(find);
        bar.add(findInfo);
        find.setToolTipText("Regex; Enter: nächster Treffer, Umschalt+Enter: vorheriger, Esc: leeren");
        find.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { highlight(true); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { highlight(true); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { highlight(true); }
        });
        find.addActionListener(e -> step((e.getModifiers() & java.awt.event.ActionEvent.SHIFT_MASK) != 0 ? -1 : 1));
        find.registerKeyboardAction(e -> find.setText(""), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_FOCUSED);

        JTable summary = new JTable(summaryModel);
        summary.setRowHeight(summary.getRowHeight() + 4);
        summary.getColumnModel().getColumn(0).setPreferredWidth(150);
        summary.getColumnModel().getColumn(0).setMaxWidth(260);
        summary.getColumnModel().getColumn(1).setPreferredWidth(700);
        summary.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        JTable events = new JTable(eventsModel);
        events.getColumnModel().getColumn(0).setPreferredWidth(70);
        events.getColumnModel().getColumn(1).setPreferredWidth(140);
        events.getColumnModel().getColumn(2).setPreferredWidth(80);
        events.getColumnModel().getColumn(3).setPreferredWidth(60);
        events.getColumnModel().getColumn(4).setPreferredWidth(700);
        events.setDefaultRenderer(Object.class, new javax.swing.table.DefaultTableCellRenderer() {
            @Override public java.awt.Component getTableCellRendererComponent(JTable t, Object v, boolean s, boolean f, int r, int c) {
                java.awt.Component comp = super.getTableCellRendererComponent(t, v, s, f, r, c);
                if (!s) comp.setForeground("Warning".equals(t.getValueAt(r, 0)) ? new Color(0xB00020) : Color.DARK_GRAY);
                return comp;
            }
        });

        JScrollPane yamlScroll = new JScrollPane(yaml);
        yamlScroll.getVerticalScrollBar().setUnitIncrement(18);
        tabs.addTab("Manifest", yamlScroll);
        tabs.addTab("Zusammenfassung", new JScrollPane(summary));
        tabs.addTab("Events", new JScrollPane(events));
        tabs.addChangeListener(e -> { if (tabs.getSelectedIndex() == 2 && !eventsLoaded) loadEvents(); });

        JPanel north = new JPanel(new BorderLayout());
        north.add(header, BorderLayout.NORTH);
        north.add(bar, BorderLayout.CENTER);
        add(north, BorderLayout.NORTH);
        add(tabs, BorderLayout.CENTER);
        status.setBorder(BorderFactory.createEmptyBorder(3, 8, 4, 8));
        status.setForeground(Color.DARK_GRAY);
        add(status, BorderLayout.SOUTH);

        getRootPane().registerKeyboardAction(e -> reload(), KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        getRootPane().registerKeyboardAction(e -> { tabs.setSelectedIndex(0); find.requestFocusInWindow(); find.selectAll(); },
                KeyStroke.getKeyStroke(KeyEvent.VK_F, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), JComponent.WHEN_IN_FOCUSED_WINDOW);

        fetched = Instant.now();
        render();
        setSize(new Dimension(900, 760));
        setLocationByPlatform(true);
    }

    // ------------------------------------------------------------------ Anzeige

    private void render() {
        header.setIcon(res.health == null ? null : new HealthIcon(res.health));
        header.setText("<html>" + ResourceGraph.esc(res.kind) + " <font color='#1B3F73'>" + ResourceGraph.esc(res.name) + "</font>"
                + (res.health == null ? "" : " &nbsp;<font color='#" + String.format("%06X", res.health.rgb) + "'>" + res.health.label + "</font>")
                + (res.message.isEmpty() ? "" : "<br><font size='-1' color='#555555'>" + ResourceGraph.esc(res.message) + "</font>") + "</html>");
        Map<String, Object> shown = shown();
        int caret = yaml.getCaretPosition();
        String text = (res.kind.equals("Secret") && reveal.isSelected() ? "# Werte von data: Base64-dekodiert\n" : "") + Yaml.dump(shown);
        setYaml(text);
        yaml.setCaretPosition(Math.min(caret, yaml.getDocument().getLength()));
        fillSummary(shown);
        highlight(false);
        status.setText("Stand " + java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss").withZone(java.time.ZoneId.systemDefault()).format(fetched)
                + "  ·  " + text.lines().count() + " Zeilen");
    }

    /** Objekt für die Anzeige: managedFields entfernt, bei Secrets Werte verdeckt bzw. dekodiert. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> shown() {
        Map<String, Object> o = (Map<String, Object>) deepCopy(res.obj);
        if (o.get("metadata") instanceof Map<?, ?> md) ((Map<String, Object>) md).remove("managedFields");
        if (!res.kind.equals("Secret")) return o;
        boolean show = reveal.isSelected();
        for (String k : new String[]{"data", "stringData"}) {
            if (!(o.get(k) instanceof Map<?, ?> d)) continue;
            Map<String, Object> m = (Map<String, Object>) d;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (!show) e.setValue(MASK);
                else if (k.equals("data")) e.setValue(decode(str(e.getValue())));
            }
        }
        // kubectl apply legt das ganze Secret (mit Werten) in dieser Annotation ab
        if (!show && path(o, "metadata", "annotations") instanceof Map<?, ?> an) {
            Map<String, Object> a = (Map<String, Object>) an;
            if (a.containsKey("kubectl.kubernetes.io/last-applied-configuration")) a.put("kubectl.kubernetes.io/last-applied-configuration", MASK);
        }
        return o;
    }

    private static String decode(String b64) {
        try {
            byte[] b = Base64.getDecoder().decode(b64.trim());
            String s = new String(b, StandardCharsets.UTF_8);
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '�' || (c < 0x20 && c != '\n' && c != '\r' && c != '\t')) return "(binär, " + b.length + " Bytes)";
            }
            return s;
        } catch (IllegalArgumentException ex) {
            return b64;
        }
    }

    private static Object deepCopy(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> c = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) c.put(String.valueOf(e.getKey()), deepCopy(e.getValue()));
            return c;
        }
        if (v instanceof List<?> l) {
            List<Object> c = new ArrayList<>(l.size());
            for (Object o : l) c.add(deepCopy(o));
            return c;
        }
        return v;
    }

    // ------------------------------------------------------------------ YAML mit Farben

    private static final Pattern KEY = Pattern.compile("^(\\s*)(- )?((?:'[^']*'|\"[^\"]*\"|[^\\s'\"#][^:#]*?)):(?=\\s|$)");

    private void setYaml(String text) {
        StyledDocument doc = yaml.getStyledDocument();
        SimpleAttributeSet plain = new SimpleAttributeSet(), key = new SimpleAttributeSet(), str = new SimpleAttributeSet(),
                lit = new SimpleAttributeSet(), comment = new SimpleAttributeSet(), dash = new SimpleAttributeSet();
        StyleConstants.setForeground(plain, new Color(0x1F2328));
        StyleConstants.setForeground(key, new Color(0x0451A5));
        StyleConstants.setForeground(str, new Color(0xA31515));
        StyleConstants.setForeground(lit, new Color(0x098658));
        StyleConstants.setForeground(comment, new Color(0x008000));
        StyleConstants.setItalic(comment, true);
        StyleConstants.setForeground(dash, new Color(0x808080));
        try {
            doc.remove(0, doc.getLength());
            doc.insertString(0, text, plain);
            int off = 0;
            boolean block = false;
            int blockIndent = 0;
            for (String line : text.split("\n", -1)) {
                int indent = 0;
                while (indent < line.length() && line.charAt(indent) == ' ') indent++;
                if (block && (line.isBlank() || indent >= blockIndent)) {
                    doc.setCharacterAttributes(off, line.length(), str, true);
                    off += line.length() + 1;
                    continue;
                }
                block = false;
                if (line.trim().startsWith("#")) {
                    doc.setCharacterAttributes(off, line.length(), comment, true);
                } else {
                    Matcher m = KEY.matcher(line);
                    int valueStart;
                    if (m.find()) {
                        if (m.group(2) != null) doc.setCharacterAttributes(off + m.start(2), 1, dash, true);
                        doc.setCharacterAttributes(off + m.start(3), m.end(3) - m.start(3) + 1, key, true); // mit Doppelpunkt
                        valueStart = m.end() + 1;
                    } else {
                        int d = line.indexOf("- ");
                        if (d >= 0 && line.substring(0, d).isBlank()) { doc.setCharacterAttributes(off + d, 1, dash, true); valueStart = d + 2; }
                        else valueStart = indent;
                    }
                    if (valueStart < line.length()) {
                        String v = line.substring(valueStart).trim();
                        if (v.equals("|") || v.equals("|-")) { block = true; blockIndent = indent + 1; }
                        else if (v.matches("true|false|null|-?[0-9][0-9.eE+-]*|\\{\\}|\\[\\]"))
                            doc.setCharacterAttributes(off + valueStart, line.length() - valueStart, lit, true);
                        else doc.setCharacterAttributes(off + valueStart, line.length() - valueStart, str, true);
                    }
                }
                off += line.length() + 1;
            }
        } catch (BadLocationException ex) {
            yaml.setText(text);
        }
    }

    // ------------------------------------------------------------------ Suchen

    private void highlight(boolean jump) {
        javax.swing.text.Highlighter hl = yaml.getHighlighter();
        hl.removeAllHighlights();
        hits.clear();
        hit = -1;
        String q = find.getText();
        find.setBackground(javax.swing.UIManager.getColor("TextField.background"));
        if (q.isEmpty()) { findInfo.setText(" "); return; }
        Pattern p;
        try {
            p = Pattern.compile(q, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        } catch (java.util.regex.PatternSyntaxException ex) {
            find.setBackground(new Color(0xFFD6D6));
            findInfo.setText("ungültige Regex");
            return;
        }
        String text = yaml.getText().replace("\r", "");
        Matcher m = p.matcher(text);
        while (m.find() && hits.size() < 10_000) {
            if (m.end() == m.start()) continue;
            hits.add(new int[]{m.start(), m.end()});
        }
        DefaultHighlighter.DefaultHighlightPainter painter = new DefaultHighlighter.DefaultHighlightPainter(HIT);
        for (int[] h : hits) {
            try { hl.addHighlight(h[0], h[1], painter); } catch (BadLocationException ignored) { }
        }
        findInfo.setText(hits.size() + " Treffer");
        if (jump && !hits.isEmpty()) step(1);
    }

    private void step(int dir) {
        if (hits.isEmpty()) return;
        hit = hit < 0 ? (dir > 0 ? 0 : hits.size() - 1) : Math.floorMod(hit + dir, hits.size());
        int[] h = hits.get(hit);
        javax.swing.text.Highlighter hl = yaml.getHighlighter();
        for (javax.swing.text.Highlighter.Highlight x : hl.getHighlights()) {
            if (x.getPainter() instanceof DefaultHighlighter.DefaultHighlightPainter dp && HIT_CURRENT.equals(dp.getColor())) hl.removeHighlight(x);
        }
        try {
            hl.addHighlight(h[0], h[1], new DefaultHighlighter.DefaultHighlightPainter(HIT_CURRENT));
            java.awt.geom.Rectangle2D r = yaml.modelToView2D(h[0]);
            if (r != null) yaml.scrollRectToVisible(new java.awt.Rectangle((int) r.getX(), (int) r.getY() - 60, 10, (int) r.getHeight() + 120));
        } catch (BadLocationException ignored) { }
        findInfo.setText((hit + 1) + " / " + hits.size());
    }

    // ------------------------------------------------------------------ Zusammenfassung

    private void fillSummary(Map<String, Object> o) {
        summaryModel.setRowCount(0);
        add("Art", res.kind + "  (" + str(o.get("apiVersion")) + ")");
        add("Name", res.name);
        add("Namespace", namespace);
        add("Cluster", cluster.name());
        if (res.health != null) add("Zustand", res.health.label + (res.message.isEmpty() ? "" : " – " + res.message));
        if (!res.info.isEmpty()) add("Status", res.info);
        if (res.created != null) add("Erstellt", res.created + "  (vor " + KubeResources.age(res.created) + ")");
        add("UID", str(path(o, "metadata", "uid")));
        if (path(o, "metadata", "ownerReferences") instanceof List<?> owners)
            for (Object w : owners) add("Besitzer", str(path(w, "kind")) + " " + str(path(w, "name")));
        Object spec = o.get("spec");
        switch (res.kind) {
            case "Pod" -> {
                add("Node", str(path(spec, "nodeName")));
                add("Pod-IP", str(path(o, "status", "podIP")));
                add("Neustarts", String.valueOf(res.restarts));
                images(spec);
            }
            case "Deployment", "StatefulSet", "DaemonSet", "ReplicaSet", "Job", "DeploymentConfig", "ReplicationController" -> {
                Object sel = path(spec, "selector", "matchLabels");
                if (sel == null && res.kind.equals("DeploymentConfig")) sel = path(spec, "selector");
                if (sel != null) add("Selector", flat(sel));
                images(path(spec, "template", "spec"));
            }
            case "CronJob" -> {
                add("Zeitplan", str(path(spec, "schedule")));
                add("Zuletzt ausgeführt", str(path(o, "status", "lastScheduleTime")));
                images(path(spec, "jobTemplate", "spec", "template", "spec"));
            }
            case "Service" -> {
                add("Typ", str(path(spec, "type")));
                add("Cluster-IP", str(path(spec, "clusterIP")));
                if (path(spec, "selector") != null) add("Selector", flat(path(spec, "selector")));
                if (path(spec, "ports") instanceof List<?> ports) for (Object p : ports)
                    add("Port", str(path(p, "name")) + " " + str(path(p, "port")) + " → " + str(path(p, "targetPort")) + "/" + str(path(p, "protocol")));
            }
            case "Ingress" -> {
                if (path(spec, "rules") instanceof List<?> rules) for (Object r : rules) add("Host", str(path(r, "host")));
            }
            case "Route" -> {
                add("Host", str(path(spec, "host")));
                add("Ziel", str(path(spec, "to", "name")));
            }
            case "ConfigMap", "Secret" -> {
                if (o.get("data") instanceof Map<?, ?> d) for (Object k : d.keySet()) add("Schlüssel", String.valueOf(k));
            }
            default -> { }
        }
        if (path(o, "metadata", "labels") instanceof Map<?, ?> l) for (Map.Entry<?, ?> e : l.entrySet()) add("Label", e.getKey() + "=" + e.getValue());
        if (path(o, "metadata", "annotations") instanceof Map<?, ?> a) for (Map.Entry<?, ?> e : a.entrySet()) {
            String v = String.valueOf(e.getValue());
            add("Annotation", e.getKey() + "=" + (v.length() > 200 ? v.substring(0, 200) + "…" : v));
        }
        if (path(o, "status", "conditions") instanceof List<?> cs) for (Object c : cs)
            add("Condition", str(path(c, "type")) + "=" + str(path(c, "status"))
                    + (path(c, "reason") == null ? "" : " (" + str(path(c, "reason")) + ")")
                    + (path(c, "message") == null ? "" : ": " + str(path(c, "message"))));
    }

    private void images(Object podSpec) {
        for (String k : new String[]{"initContainers", "containers"})
            if (path(podSpec, k) instanceof List<?> cs) for (Object c : cs)
                add(k.equals("containers") ? "Container" : "Init-Container", str(path(c, "name")) + ": " + str(path(c, "image")));
    }

    private static String flat(Object m) {
        if (!(m instanceof Map<?, ?> mm)) return str(m);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<?, ?> e : mm.entrySet()) sb.append(sb.length() == 0 ? "" : ", ").append(e.getKey()).append('=').append(e.getValue());
        return sb.toString();
    }

    private void add(String k, String v) {
        if (v != null && !v.isBlank()) summaryModel.addRow(new Object[]{k, v});
    }

    // ------------------------------------------------------------------ Laden

    private void reload() {
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        status.setText("Lade …");
        new SwingWorker<Map<String, Object>, Void>() {
            @Override protected Map<String, Object> doInBackground() throws Exception {
                List<Map<String, Object>> l = KubeResources.items(new Kube(cluster).getJson(res.kind, res.name, namespace));
                if (l.isEmpty()) throw new IOException("Leere Antwort");
                return l.get(0);
            }
            @Override protected void done() {
                setCursor(null);
                try {
                    Res fresh = new Res(res.kind, res.name, res.uid, get());
                    fresh.created = res.created;
                    KubeResources.assess(fresh);
                    fresh.parent = res.parent;
                    res = fresh;
                    fetched = Instant.now();
                    render();
                    if (eventsLoaded) loadEvents();
                } catch (Exception ex) {
                    Throwable t = ex.getCause() != null ? ex.getCause() : ex;
                    status.setText("<html><font color='#B00020'>" + ResourceGraph.esc(String.valueOf(t.getMessage())) + "</font></html>");
                }
            }
        }.execute();
    }

    private void loadEvents() {
        eventsLoaded = true;
        eventsModel.setRowCount(0);
        status.setText("Lade Events …");
        new SwingWorker<List<Object[]>, Void>() {
            @Override protected List<Object[]> doInBackground() throws Exception {
                List<Object[]> rows = new ArrayList<>();
                List<Map<String, Object>> items = KubeResources.items(new Kube(cluster).eventsJson(namespace, res.uid));
                items.sort((a, b) -> time(b).compareTo(time(a)));
                for (Map<String, Object> e : items) {
                    Object n = e.get("count");
                    if (n == null) n = path(e, "series", "count");
                    String t = time(e);
                    Instant when = null;
                    try { when = Instant.parse(t); } catch (Exception ignored) { }
                    rows.add(new Object[]{str(e.get("type")), str(e.get("reason")), when == null ? t : "vor " + KubeResources.age(when),
                            n == null ? "1" : str(n), str(e.get("message")).trim()});
                }
                return rows;
            }
            @Override protected void done() {
                try {
                    List<Object[]> rows = get();
                    for (Object[] r : rows) eventsModel.addRow(r);
                    status.setText(rows.isEmpty() ? "Keine Events (Kubernetes hält sie meist nur eine Stunde)." : rows.size() + " Events");
                } catch (Exception ex) {
                    Throwable t = ex.getCause() != null ? ex.getCause() : ex;
                    status.setText("<html><font color='#B00020'>Events: " + ResourceGraph.esc(String.valueOf(t.getMessage())) + "</font></html>");
                }
            }
        }.execute();
    }

    private static String time(Map<String, Object> e) {
        for (String k : new String[]{"lastTimestamp", "eventTime", "firstTimestamp"}) {
            String s = str(e.get(k));
            if (!s.isEmpty() && !s.equals("null")) return s;
        }
        return str(path(e, "metadata", "creationTimestamp"));
    }

    private void save() {
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new java.io.File(res.kind.toLowerCase(java.util.Locale.ROOT) + "-" + res.name + ".yaml"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            Files.writeString(fc.getSelectedFile().toPath(), yaml.getText(), StandardCharsets.UTF_8);
            status.setText("Gespeichert: " + fc.getSelectedFile());
        } catch (IOException ex) {
            status.setText("<html><font color='#B00020'>" + ResourceGraph.esc(ex.getMessage()) + "</font></html>");
        }
    }

    String yamlText() { return yaml.getText(); }

    /** Zustandssymbol für die Überschrift. */
    private record HealthIcon(KubeResources.Health h) implements Icon {
        @Override public void paintIcon(java.awt.Component c, Graphics g, int x, int y) {
            ResourceGraph.paintHealth((Graphics2D) g, h, x + 8, y + 8, 7);
        }
        @Override public int getIconWidth() { return 18; }
        @Override public int getIconHeight() { return 16; }
    }
}
