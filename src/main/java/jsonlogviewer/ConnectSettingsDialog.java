package jsonlogviewer;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * Extras → Connect Settings: Liste der Cluster. Je Cluster Werkzeug (kubectl/oc), optional Programmpfad,
 * kubeconfig-Datei und Kontext sowie Standard-Namespace. Zugangsdaten bleiben in der kubeconfig.
 */
final class ConnectSettingsDialog extends JDialog {

    private final DefaultListModel<Settings.Cluster> listModel = new DefaultListModel<>();
    private final JList<Settings.Cluster> list = new JList<>(listModel);
    private final JTextField name = new JTextField(24);
    private final JComboBox<String> tool = new JComboBox<>(new String[]{"kubectl", "oc"});
    private final JTextField toolPath = new JTextField(24);
    private final JTextField kubeconfig = new JTextField(24);
    private final JComboBox<String> context = new JComboBox<>();
    private final JTextField namespace = new JTextField(24);
    private final JTextArea result = new JTextArea(3, 40);
    private boolean loading;
    private boolean saved;

    ConnectSettingsDialog(Window owner) {
        super(owner, "Connect Settings", ModalityType.APPLICATION_MODAL);
        for (Settings.Cluster c : Settings.get().clusters()) listModel.addElement(c);

        // links: Liste
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) showSelected(); });
        JPanel left = new JPanel(new BorderLayout(0, 4));
        left.add(new JLabel("Cluster"), BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(list);
        sp.setPreferredSize(new Dimension(200, 260));
        left.add(sp, BorderLayout.CENTER);
        JPanel lb = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        lb.add(button("Neu", this::addNew));
        lb.add(button("Kopieren", this::duplicate));
        lb.add(button("Entfernen", this::remove));
        JPanel lbs = new JPanel(new BorderLayout(0, 4));
        lbs.add(lb, BorderLayout.NORTH);
        lbs.add(button("Aus kubeconfig importieren…", this::importContexts), BorderLayout.SOUTH);
        left.add(lbs, BorderLayout.SOUTH);

        // rechts: Formular
        JPanel form = new JPanel(new GridBagLayout());
        context.setEditable(true);
        int y = 0;
        row(form, y++, "Name", name, null);
        row(form, y++, "Werkzeug", tool, null);
        row(form, y++, "Programm", toolPath, button("…", () -> choose(toolPath, false)));
        hint(form, y++, "leer: kubectl bzw. oc aus dem PATH");
        row(form, y++, "kubeconfig", kubeconfig, button("…", () -> choose(kubeconfig, true)));
        hint(form, y++, "leer: KUBECONFIG bzw. ~/.kube/config");
        row(form, y++, "Kontext", context, button("Laden", this::loadContexts));
        hint(form, y++, "leer: aktueller Kontext der kubeconfig");
        row(form, y++, "Standard-Namespace", namespace, null);
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 1; g.gridy = y++; g.anchor = GridBagConstraints.WEST; g.insets = new Insets(8, 4, 2, 4);
        form.add(button("Verbindung testen", this::test), g);
        result.setEditable(false);
        result.setLineWrap(true);
        result.setWrapStyleWord(true);
        result.setOpaque(false);
        result.setBorder(null);
        g = new GridBagConstraints();
        g.gridx = 0; g.gridy = y; g.gridwidth = 3; g.fill = GridBagConstraints.BOTH; g.weightx = 1; g.weighty = 1;
        g.insets = new Insets(4, 4, 4, 4);
        form.add(result, g);

        JLabel info = new JLabel("<html><div style='width:520px;color:#555555'>Passwörter und Tokens speichert beswinged nicht. "
                + "Angemeldet wird wie gewohnt, z.B. mit <code>oc login</code>, <code>az aks get-credentials</code>, "
                + "<code>gcloud container clusters get-credentials</code> oder <code>aws eks update-kubeconfig</code>; "
                + "das legt Cluster und Kontext in der kubeconfig an.</div></html>");

        JPanel center = new JPanel(new BorderLayout(12, 0));
        center.add(left, BorderLayout.WEST);
        center.add(form, BorderLayout.CENTER);
        center.add(info, BorderLayout.SOUTH);
        center.setBorder(BorderFactory.createEmptyBorder(10, 10, 4, 10));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton ok = button("OK", this::save);
        buttons.add(ok);
        buttons.add(button("Abbrechen", this::dispose));
        getRootPane().setDefaultButton(ok);

        add(center, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);

        // Formular -> ausgewählter Eintrag
        DocumentListener dl = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { store(); }
            @Override public void removeUpdate(DocumentEvent e) { store(); }
            @Override public void changedUpdate(DocumentEvent e) { store(); }
        };
        for (JTextField f : List.of(name, toolPath, kubeconfig, namespace)) f.getDocument().addDocumentListener(dl);
        ((JTextField) context.getEditor().getEditorComponent()).getDocument().addDocumentListener(dl);
        context.addActionListener(e -> store());
        tool.addActionListener(e -> store());

        if (!listModel.isEmpty()) list.setSelectedIndex(0);
        showSelected();
        pack();
        setLocationRelativeTo(owner);
    }

    /** true, wenn mit OK gespeichert wurde. */
    boolean saved() { return saved; }

    // ------------------------------------------------------------------ Liste

    private void addNew() {
        String n = unique("Cluster");
        listModel.addElement(new Settings.Cluster(n, "kubectl", "", "", "", ""));
        list.setSelectedIndex(listModel.size() - 1);
        name.requestFocusInWindow();
        name.selectAll();
    }

    private void duplicate() {
        Settings.Cluster c = list.getSelectedValue();
        if (c == null) return;
        listModel.addElement(new Settings.Cluster(unique(c.name()), c.tool(), c.toolPath(), c.kubeconfig(), c.context(), c.namespace()));
        list.setSelectedIndex(listModel.size() - 1);
    }

    private void remove() {
        int i = list.getSelectedIndex();
        if (i < 0) return;
        listModel.remove(i);
        if (!listModel.isEmpty()) list.setSelectedIndex(Math.min(i, listModel.size() - 1));
        showSelected();
    }

    /** Legt für jeden Kontext der kubeconfig einen Eintrag an (vorhandene Kontexte werden übersprungen). */
    private void importContexts() {
        // Werkzeug, Programm und kubeconfig vom ausgewählten Eintrag übernehmen
        Settings.Cluster cur = list.getSelectedValue() != null ? current() : null;
        Settings.Cluster base = cur == null ? new Settings.Cluster("", "kubectl", "", "", "", "")
                : new Settings.Cluster("", cur.tool(), cur.toolPath(), cur.kubeconfig(), "", "");
        background(() -> {
            try {
                return new Kube(base).contexts();
            } catch (Exception ex) {
                throw new RuntimeException(ex.getMessage(), ex);
            }
        }, ctxs -> {
            int added = 0;
            for (String ctx : ctxs) {
                boolean exists = false;
                for (int i = 0; i < listModel.size(); i++)
                    if (listModel.get(i).context().equals(ctx) && listModel.get(i).kubeconfig().equals(base.kubeconfig())) exists = true;
                if (exists) continue;
                listModel.addElement(new Settings.Cluster(unique(ctx), base.tool(), base.toolPath(), base.kubeconfig(), ctx, ""));
                added++;
            }
            if (added > 0) list.setSelectedIndex(listModel.size() - added); // leert die Meldung, daher vorher
            result.setForeground(Color.DARK_GRAY);
            result.setText(ctxs.size() + " Kontexte gefunden, " + added + " neu angelegt.");
        });
    }

    private String unique(String base) {
        String n = base;
        for (int k = 2; ; k++) {
            boolean taken = false;
            for (int i = 0; i < listModel.size(); i++) if (listModel.get(i).name().equalsIgnoreCase(n)) taken = true;
            if (!taken) return n;
            n = base + " " + k;
        }
    }

    // ------------------------------------------------------------------ Formular

    private void showSelected() {
        Settings.Cluster c = list.getSelectedValue();
        loading = true;
        try {
            boolean on = c != null;
            for (JComponent f : List.of(name, tool, toolPath, kubeconfig, context, namespace)) f.setEnabled(on);
            name.setText(on ? c.name() : "");
            tool.setSelectedItem(on ? c.tool() : "kubectl");
            toolPath.setText(on ? c.toolPath() : "");
            kubeconfig.setText(on ? c.kubeconfig() : "");
            context.removeAllItems();
            context.setSelectedItem(on ? c.context() : "");
            namespace.setText(on ? c.namespace() : "");
            result.setText("");
        } finally {
            loading = false;
        }
    }

    private void store() {
        int i = list.getSelectedIndex();
        if (loading || i < 0) return;
        Settings.Cluster c = current();
        if (!c.equals(listModel.get(i))) listModel.set(i, c);
    }

    private Settings.Cluster current() {
        Object ctx = context.isEditable() ? context.getEditor().getItem() : context.getSelectedItem();
        return new Settings.Cluster(name.getText().trim(), String.valueOf(tool.getSelectedItem()), toolPath.getText().trim(),
                kubeconfig.getText().trim(), ctx == null ? "" : ctx.toString().trim(), namespace.getText().trim());
    }

    private void loadContexts() {
        Settings.Cluster c = current();
        background(() -> {
            try {
                return new Kube(c).contexts();
            } catch (Exception ex) {
                throw new RuntimeException(ex.getMessage(), ex);
            }
        }, ctxs -> {
            String keep = c.context();
            loading = true;
            context.removeAllItems();
            for (String s : ctxs) context.addItem(s);
            context.setSelectedItem(keep);
            loading = false;
            result.setForeground(Color.DARK_GRAY);
            result.setText(ctxs.isEmpty() ? "Keine Kontexte in der kubeconfig." : ctxs.size() + " Kontexte geladen.");
            if (!keep.isEmpty() || ctxs.isEmpty()) return;
            context.showPopup();
        });
    }

    private void test() {
        Settings.Cluster c = current();
        result.setForeground(Color.DARK_GRAY);
        result.setText("Teste " + c.name() + " …");
        background(() -> {
            try {
                return new Kube(c).test(c.namespace());
            } catch (Exception ex) {
                throw new RuntimeException(ex.getMessage(), ex);
            }
        }, s -> {
            result.setForeground(s.contains("nicht erlaubt") ? new Color(0xB00020) : new Color(0x1B6E20));
            result.setText(s);
        });
    }

    private void save() {
        List<Settings.Cluster> l = new ArrayList<>();
        for (int i = 0; i < listModel.size(); i++) {
            Settings.Cluster c = listModel.get(i);
            if (c.name().isBlank()) {
                list.setSelectedIndex(i);
                JOptionPane.showMessageDialog(this, "Bitte jedem Cluster einen Namen geben.", "Connect Settings", JOptionPane.WARNING_MESSAGE);
                return;
            }
            l.add(c);
        }
        Settings.get().setClusters(l);
        saved = true;
        dispose();
    }

    // ------------------------------------------------------------------ Hilfsfunktionen

    private void choose(JTextField target, boolean config) {
        JFileChooser fc = new JFileChooser();
        String cur = target.getText().trim();
        File start = !cur.isEmpty() ? new File(cur)
                : config ? new File(System.getProperty("user.home"), ".kube") : null;
        if (start != null) fc.setSelectedFile(start);
        fc.setDialogTitle(config ? "kubeconfig wählen" : "kubectl bzw. oc wählen");
        fc.setFileHidingEnabled(false);
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) target.setText(fc.getSelectedFile().getAbsolutePath());
    }

    private <T> void background(Supplier<T> work, java.util.function.Consumer<T> done) {
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() { return work.get(); }
            @Override protected void done() {
                setCursor(Cursor.getDefaultCursor());
                try {
                    done.accept(get());
                } catch (Exception ex) {
                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                    result.setForeground(new Color(0xB00020));
                    result.setText(String.valueOf(c.getMessage()));
                }
            }
        }.execute();
    }

    private static JButton button(String text, Runnable r) {
        JButton b = new JButton(text);
        b.addActionListener(e -> r.run());
        return b;
    }

    private static void row(JPanel p, int y, String label, JComponent field, JComponent extra) {
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0; g.gridy = y; g.anchor = GridBagConstraints.WEST; g.insets = new Insets(4, 4, 0, 6);
        JLabel l = new JLabel(label);
        l.setLabelFor(field);
        p.add(l, g);
        g = new GridBagConstraints();
        g.gridx = 1; g.gridy = y; g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1; g.insets = new Insets(4, 0, 0, 4);
        p.add(field, g);
        if (extra != null) {
            g = new GridBagConstraints();
            g.gridx = 2; g.gridy = y; g.insets = new Insets(4, 0, 0, 4);
            p.add(extra, g);
        }
    }

    private static void hint(JPanel p, int y, String text) {
        JLabel l = new JLabel(text);
        l.setForeground(Color.GRAY);
        l.setFont(l.getFont().deriveFont(l.getFont().getSize2D() - 1f));
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 1; g.gridy = y; g.anchor = GridBagConstraints.WEST; g.insets = new Insets(0, 2, 0, 4);
        p.add(l, g);
    }
}
