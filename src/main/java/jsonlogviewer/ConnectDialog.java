package jsonlogviewer;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * Extras → Connect: holt das Log eines Pods oder aller Pods eines Workloads mit kubectl/oc in eine Datei unter
 * {@code ~/.beswinged/kube/} und öffnet sie. Mit „Folgen" läuft {@code logs -f} weiter und Tail ist an.
 */
final class ConnectDialog extends JDialog {

    private static final String[] SINCE = {"", "5m", "15m", "1h", "6h", "24h", "7d"};

    private final JsonLogViewer viewer;
    private final JComboBox<Settings.Cluster> cluster = new JComboBox<>();
    private final JComboBox<String> namespace = new JComboBox<>();
    private final JComboBox<String> kind = new JComboBox<>(Kube.KINDS.toArray(new String[0]));
    private final JComboBox<String> name = new JComboBox<>();
    private final JLabel targetStatus = new JLabel(" ");
    private final JComboBox<String> container = new JComboBox<>();
    private final JComboBox<String> since = new JComboBox<>(SINCE);
    private final JSpinner tail = new JSpinner(new SpinnerNumberModel(0, 0, 10_000_000, 1000));
    private final JCheckBox previous = new JCheckBox("Vorheriger Container (nach Neustart)");
    private final JCheckBox follow = new JCheckBox("Folgen (logs -f, Tail einschalten)");
    private final JTextArea command = new JTextArea(3, 60);
    private final JLabel status = new JLabel(" ");
    private final JButton connect = new JButton("Verbinden");
    private final JButton cancel = new JButton("Abbrechen");
    private final Map<String, Kube.Target> targets = new HashMap<>();
    private boolean busy;
    private volatile Process running;
    private volatile Path runningOut;

    ConnectDialog(JsonLogViewer viewer) {
        super(viewer, "Connect – Kubernetes/OpenShift-Log", ModalityType.APPLICATION_MODAL);
        this.viewer = viewer;
        namespace.setEditable(true);
        name.setEditable(true);
        container.setEditable(true);
        since.setEditable(true);
        since.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> l, Object v, int i, boolean s, boolean f) {
                return super.getListCellRendererComponent(l, v == null || v.toString().isEmpty() ? "alles" : v, i, s, f);
            }
        });
        since.setToolTipText("leer: alles; sonst z.B. 30s, 15m, 2h, 3d");
        ((JSpinner.DefaultEditor) tail.getEditor()).getTextField().setColumns(8);
        targetStatus.setForeground(Color.GRAY);
        command.setEditable(false);
        command.setLineWrap(true);
        command.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        command.setBackground(new Color(0xF4F4F4));
        command.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));

        JPanel form = new JPanel(new GridBagLayout());
        int y = 0;
        row(form, y++, "Cluster", cluster, button("Connect Settings…", this::openSettings));
        row(form, y++, "Namespace", namespace, button("Laden", this::loadNamespaces));
        row(form, y++, "Art", kind, null);
        row(form, y++, "Name", name, button("Laden", this::loadTargets));
        row(form, y++, "", targetStatus, null);
        row(form, y++, "Container", container, null);
        hint(form, y++, "leer: alle Container");
        JPanel opts = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        opts.add(new JLabel("Seit"));
        opts.add(since);
        opts.add(new JLabel("   Letzte Zeilen"));
        opts.add(tail);
        opts.add(new JLabel("(0 = alle)"));
        row(form, y++, "Umfang", opts, null);
        row(form, y++, "", previous, null);
        row(form, y++, "", follow, null);
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0; g.gridy = y++; g.gridwidth = 3; g.anchor = GridBagConstraints.WEST; g.insets = new Insets(10, 4, 2, 4);
        form.add(new JLabel("Befehl"), g);
        g = new GridBagConstraints();
        g.gridx = 0; g.gridy = y++; g.gridwidth = 3; g.fill = GridBagConstraints.BOTH; g.weightx = 1; g.insets = new Insets(0, 4, 4, 4);
        form.add(command, g);
        g = new GridBagConstraints();
        g.gridx = 0; g.gridy = y; g.gridwidth = 3; g.fill = GridBagConstraints.HORIZONTAL; g.insets = new Insets(4, 4, 4, 4);
        form.add(status, g);
        form.setBorder(BorderFactory.createEmptyBorder(10, 10, 4, 10));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        connect.addActionListener(e -> connect());
        cancel.addActionListener(e -> cancel());
        buttons.add(connect);
        buttons.add(cancel);
        getRootPane().setDefaultButton(connect);
        add(form, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);

        fillClusters(null);
        restore(Settings.get().connectPrefs());
        Runnable upd = this::updateCommand;
        for (JComboBox<?> c : List.of(namespace, name, container, since)) {
            c.addActionListener(e -> upd.run());
            ((JTextField) c.getEditor().getEditorComponent()).getDocument().addDocumentListener(docListener(upd));
        }
        cluster.addActionListener(e -> {
            Settings.Cluster c = selectedCluster();
            if (c != null && !c.namespace().isBlank() && text(namespace).isEmpty()) namespace.setSelectedItem(c.namespace());
            targets.clear();
            upd.run();
        });
        kind.addActionListener(e -> { targets.clear(); name.removeAllItems(); targetStatus.setText(" "); upd.run(); });
        name.addActionListener(e -> showTarget());
        tail.addChangeListener(e -> upd.run());
        previous.addActionListener(e -> upd.run());
        follow.addActionListener(e -> upd.run());
        updateCommand();
        pack();
        setLocationRelativeTo(viewer);
    }

    // ------------------------------------------------------------------ Eingaben

    private void fillClusters(String select) {
        cluster.removeAllItems();
        for (Settings.Cluster c : Settings.get().clusters()) {
            cluster.addItem(c);
            if (c.name().equals(select)) cluster.setSelectedItem(c);
        }
        boolean none = cluster.getItemCount() == 0;
        connect.setEnabled(!none);
        if (none) setStatus("Noch kein Cluster eingerichtet: „Connect Settings…“ öffnen.", false);
    }

    private void restore(Settings.ConnectPrefs p) {
        for (int i = 0; i < cluster.getItemCount(); i++) if (cluster.getItemAt(i).name().equals(p.cluster())) cluster.setSelectedIndex(i);
        Settings.Cluster c = selectedCluster();
        namespace.setSelectedItem(!p.namespace().isEmpty() ? p.namespace() : c != null ? c.namespace() : "");
        kind.setSelectedItem(Kube.KINDS.contains(p.kind()) ? p.kind() : "Pod");
        name.setSelectedItem(p.name());
        container.setSelectedItem(p.container());
        since.setSelectedItem(p.since());
        tail.setValue(p.tailLines());
        previous.setSelected(p.previous());
        follow.setSelected(p.follow());
    }

    private Settings.Cluster selectedCluster() { return (Settings.Cluster) cluster.getSelectedItem(); }

    private static String text(JComboBox<?> c) {
        Object o = c.isEditable() ? c.getEditor().getItem() : c.getSelectedItem();
        return o == null ? "" : o.toString().trim();
    }

    private void openSettings() {
        Settings.Cluster sel = selectedCluster();
        ConnectSettingsDialog d = new ConnectSettingsDialog(this);
        d.setVisible(true);
        if (d.saved()) fillClusters(sel != null ? sel.name() : null);
        updateCommand();
    }

    private void loadNamespaces() {
        Settings.Cluster c = selectedCluster();
        if (c == null) return;
        String keep = text(namespace);
        background("Lade Namespaces …", () -> {
            try { return new Kube(c).namespaces(); } catch (IOException ex) { throw new RuntimeException(ex.getMessage(), ex); }
        }, l -> {
            namespace.removeAllItems();
            for (String s : l) namespace.addItem(s);
            namespace.setSelectedItem(keep.isEmpty() && !c.namespace().isEmpty() ? c.namespace() : keep);
            setStatus(l.size() + " Namespaces.", false);
            if (keep.isEmpty()) namespace.showPopup();
        });
    }

    private void loadTargets() {
        Settings.Cluster c = selectedCluster();
        String ns = text(namespace), k = (String) kind.getSelectedItem();
        if (c == null) return;
        if (ns.isEmpty()) { setStatus("Bitte zuerst den Namespace angeben.", true); return; }
        String keep = text(name);
        background("Lade " + k + " in " + ns + " …", () -> {
            try { return new Kube(c).targets(k, ns); } catch (IOException ex) { throw new RuntimeException(ex.getMessage(), ex); }
        }, l -> {
            targets.clear();
            name.removeAllItems();
            for (Kube.Target t : l) { targets.put(t.name(), t); name.addItem(t.name()); }
            name.setSelectedItem(keep);
            setStatus(l.size() + " gefunden.", false);
            showTarget();
            if (keep.isEmpty() || !targets.containsKey(keep)) name.showPopup();
        });
    }

    /** Status und Container des gewählten Pods/Workloads anzeigen. */
    private void showTarget() {
        Kube.Target t = targets.get(text(name));
        if (t == null) {
            targetStatus.setText(" ");
        } else {
            String sel = t.selector().isEmpty() ? "" : "  Selector: " + t.selector();
            targetStatus.setText((t.status().isEmpty() ? "" : t.status()) + sel);
            String keep = text(container);
            container.removeAllItems();
            container.addItem("");
            for (String s : t.containers()) container.addItem(s);
            container.setSelectedItem(t.containers().contains(keep) ? keep : t.containers().size() == 1 ? t.containers().get(0) : "");
        }
        updateCommand();
    }

    private void updateCommand() {
        Settings.Cluster c = selectedCluster();
        if (c == null) { command.setText(""); return; }
        String k = (String) kind.getSelectedItem();
        Kube.Target t = targets.get(text(name));
        Map<String, String> selector = t != null ? t.selector() : null;
        List<String> cmd = new Kube(c).logsCommand(or(text(namespace), "<namespace>"), k, or(text(name), "<name>"),
                selector, text(container), text(since), (Integer) tail.getValue(), previous.isSelected(), follow.isSelected());
        String s = Kube.display(cmd);
        if (!k.equals("Pod") && t == null) s += "\n(Selector wird beim Verbinden ermittelt: alle Pods von " + k + " " + or(text(name), "<name>") + ")";
        command.setText(s);
    }

    private static String or(String s, String d) { return s.isEmpty() ? d : s; }

    // ------------------------------------------------------------------ Verbinden

    private void connect() {
        if (busy) return;
        Settings.Cluster c = selectedCluster();
        String ns = text(namespace), k = (String) kind.getSelectedItem(), n = text(name), ct = text(container), sn = text(since);
        int tl = (Integer) tail.getValue();
        boolean prev = previous.isSelected(), fol = follow.isSelected();
        if (c == null) return;
        if (ns.isEmpty() || n.isEmpty()) { setStatus("Bitte Namespace und Name angeben.", true); return; }
        Settings.get().setConnectPrefs(new Settings.ConnectPrefs(c.name(), ns, k, n, ct, sn, tl, prev, fol));
        Path out = outFile(c, ns, k, n, ct);
        Kube kube = new Kube(c);
        busy = true;
        connect.setEnabled(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        setStatus("Verbinde …", false);
        Timer progress = new Timer(500, e -> {
            if (running != null) setStatus("Lade Log … " + human(size(out)) + (fol ? "" : "  (Abbrechen beendet den Download)"), false);
        });
        progress.start();
        new SwingWorker<Integer, Void>() {
            @Override protected Integer doInBackground() throws Exception {
                Map<String, String> selector = null;
                if (!k.equals("Pod")) {
                    Kube.Target t = targets.get(n);
                    if (t == null) {
                        for (Kube.Target x : kube.targets(k, ns)) if (x.name().equals(n)) t = x;
                        if (t == null) throw new IOException(k + " „" + n + "“ gibt es in " + ns + " nicht.");
                    }
                    selector = t.selector();
                }
                List<String> cmd = kube.logsCommand(ns, k, n, selector, ct, sn, tl, prev, fol);
                Process p = Kube.startLogs(cmd, out);
                running = p;
                runningOut = out;
                if (fol) {
                    // kurz warten: Fehler (Pod nicht gefunden, keine Rechte) kommen sofort
                    long until = System.currentTimeMillis() + 2500;
                    while (p.isAlive() && System.currentTimeMillis() < until && size(out) == 0) Thread.sleep(100);
                    return p.isAlive() ? null : p.exitValue();
                }
                return p.waitFor();
            }

            @Override protected void done() {
                progress.stop();
                busy = false;
                running = null;
                setCursor(Cursor.getDefaultCursor());
                connect.setEnabled(true);
                Integer exit;
                try {
                    exit = get();
                } catch (java.util.concurrent.CancellationException ex) {
                    return;
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    setStatus(String.valueOf(cause.getMessage()), true);
                    return;
                }
                if (runningOut == null) { setStatus("Abgebrochen.", true); return; } // Abbrechen
                runningOut = null;
                if (exit != null && exit != 0 && size(out) == 0) {
                    String err = Kube.errText(out);
                    setStatus(err.isEmpty() ? "Fehler, Exit-Code " + exit : err, true);
                    return;
                }
                dispose();
                viewer.openLog(out, LineIndex.Mode.RAM, true);
                if (fol && exit == null) viewer.setTail(true);
            }
        }.execute();
    }

    private void cancel() {
        if (running != null) {
            Path o = runningOut;
            runningOut = null;
            Kube.stop(o);
            running = null;
            return;
        }
        dispose();
    }

    /** {@code ~/.beswinged/kube/<cluster>_<namespace>_<name>[_<container>].jsonl} */
    static Path outFile(Settings.Cluster c, String ns, String kind, String n, String container) {
        String base = c.name() + "_" + ns + "_" + (kind.equals("Pod") ? "" : kind.toLowerCase(java.util.Locale.ROOT) + "-") + n
                + (container.isEmpty() ? "" : "_" + container);
        return Settings.get().dir().resolve("kube").resolve(base.replaceAll("[^\\w.-]", "_") + ".jsonl");
    }

    // ------------------------------------------------------------------ Hilfsfunktionen

    private void setStatus(String s, boolean error) {
        status.setForeground(error ? new Color(0xB00020) : Color.DARK_GRAY);
        status.setText("<html><div style='width:560px'>" + s.replace("&", "&amp;").replace("<", "&lt;").replace("\n", "<br>") + "</div></html>");
        if (isShowing() && status.getPreferredSize().height > status.getHeight() + 2) pack(); // mehrzeilige Fehlermeldung
    }

    private static long size(Path p) {
        try { return Files.size(p); } catch (IOException ex) { return 0; }
    }

    private static String human(long b) {
        return b < 1024 ? b + " B" : b < (1 << 20) ? (b >> 10) + " KB" : String.format("%.1f MB", b / 1048576.0);
    }

    private <T> void background(String msg, Supplier<T> work, java.util.function.Consumer<T> done) {
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        setStatus(msg, false);
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() { return work.get(); }
            @Override protected void done() {
                setCursor(Cursor.getDefaultCursor());
                try {
                    done.accept(get());
                } catch (Exception ex) {
                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                    setStatus(String.valueOf(c.getMessage()), true);
                }
            }
        }.execute();
    }

    private static DocumentListener docListener(Runnable r) {
        return new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { r.run(); }
            @Override public void removeUpdate(DocumentEvent e) { r.run(); }
            @Override public void changedUpdate(DocumentEvent e) { }
        };
    }

    private static JButton button(String text, Runnable r) {
        JButton b = new JButton(text);
        b.addActionListener(e -> r.run());
        return b;
    }

    private static void row(JPanel p, int y, String label, JComponent field, JComponent extra) {
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0; g.gridy = y; g.anchor = GridBagConstraints.WEST; g.insets = new Insets(4, 4, 0, 8);
        JLabel l = new JLabel(label);
        l.setLabelFor(field);
        p.add(l, g);
        g = new GridBagConstraints();
        g.gridx = 1; g.gridy = y; g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1; g.insets = new Insets(4, 0, 0, 4);
        p.add(field, g);
        if (extra != null) {
            g = new GridBagConstraints();
            g.gridx = 2; g.gridy = y; g.fill = GridBagConstraints.HORIZONTAL; g.insets = new Insets(4, 0, 0, 4);
            p.add(extra, g);
        }
    }

    private static void hint(JPanel p, int y, String text) {
        JLabel l = new JLabel(text);
        l.setForeground(Color.GRAY);
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 1; g.gridy = y; g.anchor = GridBagConstraints.WEST; g.insets = new Insets(0, 2, 0, 4);
        p.add(l, g);
    }
}
