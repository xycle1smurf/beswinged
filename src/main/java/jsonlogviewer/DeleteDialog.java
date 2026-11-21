package jsonlogviewer;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import jsonlogviewer.KubeResources.Res;

/**
 * Löschen eines Objekts mit den Optionen von {@code kubectl delete}. Wie bei ArgoCD muss zur Bestätigung
 * der Name des Objekts eingegeben werden.
 */
final class DeleteDialog extends JDialog {

    private final Kube kube;
    private final String namespace;
    private final Res res;
    private final String type;
    private final Runnable onDeleted;

    private final JRadioButton foreground = new JRadioButton("Vordergrund: erst abhängige Objekte löschen, dann dieses");
    private final JRadioButton background = new JRadioButton("Hintergrund: dieses sofort, abhängige danach (Standard)", true);
    private final JRadioButton orphan = new JRadioButton("Nicht kaskadierend: abhängige Objekte bleiben bestehen");
    private final JRadioButton graceDefault = new JRadioButton("Standard-Wartezeit des Objekts", true);
    private final JRadioButton graceCustom = new JRadioButton("Wartezeit");
    private final JSpinner graceSeconds = new JSpinner(new SpinnerNumberModel(30, 0, 86_400, 5));
    private final JRadioButton now = new JRadioButton("Sofort (--now)");
    private final JCheckBox force = new JCheckBox("Erzwingen (--force)");
    private final JLabel command = new JLabel();
    private final JTextField confirm = new JTextField(28);
    private final JButton delete = new JButton("Löschen");
    private final JLabel status = new JLabel(" ");
    private boolean running;

    DeleteDialog(Window owner, Settings.Cluster cluster, String namespace, Res res, Runnable onDeleted) {
        super(owner, res.kind + " löschen", ModalityType.APPLICATION_MODAL);
        this.kube = new Kube(cluster);
        this.namespace = namespace;
        this.res = res;
        this.type = Kube.type(res.kind, res.obj);
        this.onDeleted = onDeleted;

        group(foreground, background, orphan);
        group(graceDefault, graceCustom, now);
        foreground.setToolTipText("--cascade=foreground");
        background.setToolTipText("--cascade=background");
        orphan.setToolTipText("--cascade=orphan: z.B. Pods eines Deployments laufen weiter");
        graceCustom.setToolTipText("--grace-period=<Sekunden>: Zeit zum sauberen Beenden (SIGTERM bis SIGKILL)");
        now.setToolTipText("--now: Signal sofort senden, minimale Wartezeit (1 s)");
        force.setToolTipText("<html>--force --grace-period=0: Objekt sofort aus der API entfernen, ohne auf das Beenden zu warten.<br>"
                + "Bei Pods kann der Prozess auf dem Knoten weiterlaufen. Nur im Notfall verwenden.</html>");
        force.setForeground(new Color(0xB00020));

        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createEmptyBorder(12, 14, 6, 14));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.insets = new Insets(2, 0, 2, 0);
        p.add(new JLabel("<html>" + ResourceGraph.esc(res.kind) + " <b>" + ResourceGraph.esc(res.name) + "</b> in Namespace <b>"
                + ResourceGraph.esc(namespace) + "</b> auf Cluster <b>" + ResourceGraph.esc(cluster.name()) + "</b> löschen?</html>"), c);

        c.gridy++;
        c.insets = new Insets(12, 0, 2, 0);
        p.add(bold("Abhängige Objekte (--cascade)"), c);
        c.insets = new Insets(0, 12, 0, 0);
        for (JRadioButton b : List.of(foreground, background, orphan)) { c.gridy++; p.add(b, c); }

        c.gridy++;
        c.insets = new Insets(12, 0, 2, 0);
        p.add(bold("Beenden"), c);
        c.insets = new Insets(0, 12, 0, 0);
        c.gridy++;
        p.add(graceDefault, c);
        c.gridy++;
        JPanel custom = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        custom.add(graceCustom);
        custom.add(graceSeconds);
        custom.add(new JLabel("  Sekunden (--grace-period)"));
        p.add(custom, c);
        c.gridy++;
        p.add(now, c);
        c.gridy++;
        c.insets = new Insets(6, 12, 0, 0);
        p.add(force, c);

        c.gridy++;
        c.insets = new Insets(12, 0, 2, 0);
        p.add(bold("Befehl"), c);
        c.gridy++;
        c.insets = new Insets(0, 12, 0, 0);
        command.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        p.add(command, c);

        c.gridy++;
        c.insets = new Insets(14, 0, 4, 0);
        p.add(new JLabel("<html>Zur Bestätigung den Namen eingeben: <b>" + ResourceGraph.esc(res.name) + "</b></html>"), c);
        c.gridy++;
        c.insets = new Insets(0, 0, 0, 0);
        p.add(confirm, c);
        c.gridy++;
        c.insets = new Insets(8, 0, 0, 0);
        p.add(status, c);

        delete.setEnabled(false);
        delete.setForeground(new Color(0xB00020));
        delete.addActionListener(e -> run());
        JButton cancel = new JButton("Abbrechen");
        cancel.addActionListener(e -> { if (!running) dispose(); });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(delete);
        buttons.add(cancel);

        JPanel root = new JPanel(new BorderLayout());
        root.add(p, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        setContentPane(root);
        getRootPane().registerKeyboardAction(e -> { if (!running) dispose(); },
                KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        getRootPane().setDefaultButton(delete);

        for (JRadioButton b : List.of(foreground, background, orphan, graceDefault, graceCustom, now)) b.addActionListener(e -> update());
        force.addActionListener(e -> update());
        graceSeconds.addChangeListener(e -> { graceCustom.setSelected(true); update(); });
        confirm.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { update(); }
            @Override public void removeUpdate(DocumentEvent e) { update(); }
            @Override public void changedUpdate(DocumentEvent e) { update(); }
        });
        update();
        pack();
        setResizable(false);
        setLocationRelativeTo(owner);
    }

    private static void group(JRadioButton... bs) {
        ButtonGroup g = new ButtonGroup();
        for (JRadioButton b : bs) g.add(b);
    }

    private static JLabel bold(String s) {
        JLabel l = new JLabel(s);
        l.setFont(l.getFont().deriveFont(Font.BOLD));
        return l;
    }

    List<String> buildCommand() {
        String cascade = foreground.isSelected() ? "foreground" : orphan.isSelected() ? "orphan" : "background";
        int grace = graceCustom.isSelected() ? (Integer) graceSeconds.getValue() : -1;
        return kube.deleteCommand(type, res.name, namespace, cascade, grace, now.isSelected(), force.isSelected());
    }

    private void update() {
        boolean f = force.isSelected();
        graceDefault.setEnabled(!f && !running);
        graceCustom.setEnabled(!f && !running);
        graceSeconds.setEnabled(!f && !running);
        now.setEnabled(!f && !running);
        List<String> cmd = buildCommand();
        command.setText("<html><div style='width:430px'>" + ResourceGraph.esc(Kube.display(cmd)) + "</div></html>");
        delete.setEnabled(!running && confirm.getText().equals(res.name));
    }

    private void run() {
        if (running || !confirm.getText().equals(res.name)) return;
        running = true;
        update();
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        setStatus("Lösche …", false);
        List<String> cmd = buildCommand();
        new SwingWorker<String, Void>() {
            @Override protected String doInBackground() throws Exception { return Kube.run(cmd); }
            @Override protected void done() {
                running = false;
                setCursor(null);
                try {
                    get();
                    dispose();
                    if (onDeleted != null) onDeleted.run();
                } catch (Exception ex) {
                    Throwable t = ex.getCause() != null ? ex.getCause() : ex;
                    setStatus("Löschen fehlgeschlagen: " + t.getMessage(), true);
                    update();
                    pack();
                }
            }
        }.execute();
    }

    private void setStatus(String s, boolean error) {
        status.setForeground(error ? new Color(0xB00020) : Color.DARK_GRAY);
        status.setText("<html><div style='width:430px'>" + ResourceGraph.esc(s).replace("\n", "<br>") + "</div></html>");
    }

    /** Für Tests. */
    JTextField confirmField() { return confirm; }
    JButton deleteButton() { return delete; }
    JCheckBox forceBox() { return force; }
    JRadioButton orphanButton() { return orphan; }
    JRadioButton nowButton() { return now; }
}
