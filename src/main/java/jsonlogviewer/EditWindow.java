package jsonlogviewer;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Map;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import jsonlogviewer.KubeResources.Res;

/**
 * Bearbeitet ein Objekt des Clusters. Gespeichert wird immer YAML ({@code kubectl replace}); die XML-Ansicht dient nur
 * dazu, die Struktur ohne Einrückungsfehler zu bearbeiten – beim Wechsel und beim Speichern wird zurück in YAML gewandelt.
 */
final class EditWindow extends JFrame {

    private enum Mode { YAML, XML }

    private final Settings.Cluster cluster;
    private final String namespace;
    private final Res res;
    private final String type;
    private final Runnable onSaved;

    private final JTextArea text = new JTextArea();
    private final JToggleButton yamlButton = new JToggleButton("YAML", true);
    private final JToggleButton xmlButton = new JToggleButton("XML");
    private final JButton indent = new JButton("Einrücken");
    private final JButton reload = new JButton("Neu laden");
    private final JButton save = new JButton("Speichern");
    private final JLabel status = new JLabel(" ");
    private Mode mode = Mode.YAML;
    private boolean dirty, quiet, loaded;
    private SwingWorker<?, ?> worker;

    EditWindow(Settings.Cluster cluster, String namespace, Res res, Runnable onSaved) {
        super("Bearbeiten: " + res.kind + " " + res.name + " – " + namespace + " – beswinged");
        this.cluster = cluster;
        this.namespace = namespace;
        this.res = res;
        this.type = Kube.type(res.kind, res.obj);
        this.onSaved = onSaved;
        setIconImages(HelpMenu.icons());
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { close(); }
        });

        text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        text.setTabSize(2);
        text.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { changed(); }
            @Override public void removeUpdate(DocumentEvent e) { changed(); }
            @Override public void changedUpdate(DocumentEvent e) { }
        });
        // Tab fügt zwei Leerzeichen ein: Tabulatoren sind in YAML-Einrückungen verboten
        text.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), "two-spaces");
        text.getActionMap().put("two-spaces", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { text.replaceSelection("  "); }
        });

        ButtonGroup g = new ButtonGroup();
        g.add(yamlButton);
        g.add(xmlButton);
        for (JToggleButton b : new JToggleButton[]{yamlButton, xmlButton}) {
            java.awt.Dimension d = b.getPreferredSize();
            d.width = Math.max(d.width, b.getFontMetrics(b.getFont().deriveFont(Font.BOLD)).stringWidth("YAML") + 36);
            b.setPreferredSize(d);
            b.setMinimumSize(d);
        }
        yamlButton.setToolTipText("YAML bearbeiten – so wird gespeichert");
        xmlButton.setToolTipText("<html>Als XML bearbeiten: Die Struktur steckt in den Elementen, nicht in der Einrückung.<br>"
                + "Schlüssel, die kein XML-Name sind, stehen als &lt;entry key=\"…\"&gt;; Listen haben type=\"list\" mit &lt;item&gt;.<br>"
                + "Texte, die sonst als Zahl oder true/false gelesen würden, tragen type=\"string\".<br>"
                + "Beim Speichern wird automatisch zurück in YAML gewandelt.</html>");
        yamlButton.addActionListener(e -> switchTo(Mode.YAML));
        xmlButton.addActionListener(e -> switchTo(Mode.XML));
        indent.setToolTipText("XML anhand der Elemente neu einrücken (Strg+Umschalt+F)");
        indent.addActionListener(e -> reindent());
        indent.setEnabled(false);
        reload.setToolTipText("Aktuellen Stand vom Cluster laden (verwirft Änderungen)");
        reload.addActionListener(e -> { if (confirmDiscard()) load(null); });
        save.setToolTipText("Mit kubectl replace speichern (Strg+S)");
        save.addActionListener(e -> save());

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        bar.add(new JLabel("Ansicht:"));
        bar.add(yamlButton);
        bar.add(xmlButton);
        bar.add(indent);
        bar.add(Box.createHorizontalStrut(12));
        bar.add(reload);
        if (res.kind.equals("Secret")) {
            JLabel hint = new JLabel("data: Werte Base64-kodiert (stringData: Klartext)");
            hint.setForeground(Color.GRAY);
            bar.add(hint);
        }

        JButton close = new JButton("Schließen");
        close.addActionListener(e -> close());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(save);
        buttons.add(close);
        JPanel south = new JPanel(new BorderLayout(8, 0));
        south.setBorder(BorderFactory.createEmptyBorder(4, 8, 6, 6));
        south.add(status, BorderLayout.CENTER);
        south.add(buttons, BorderLayout.EAST);

        JPanel root = new JPanel(new BorderLayout());
        root.add(bar, BorderLayout.NORTH);
        root.add(new JScrollPane(text), BorderLayout.CENTER);
        root.add(south, BorderLayout.SOUTH);
        setContentPane(root);
        key("save", KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK), this::save);
        key("indent", KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), this::reindent);
        key("close", KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), this::close);
        setSize(820, 760);
        setLocationByPlatform(true);
        load(null);
    }

    private void key(String name, KeyStroke ks, Runnable r) {
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(ks, name);
        getRootPane().getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { r.run(); }
        });
    }

    // ------------------------------------------------------------------ Laden

    private void load(String doneMessage) {
        busy(true, "Lade " + res.kind + " " + res.name + " …");
        Kube kube = new Kube(cluster);
        worker = new SwingWorker<String, Void>() {
            @Override protected String doInBackground() throws Exception {
                Object o = Json.parse(kube.getJson(type, res.name, namespace));
                if (o instanceof Map<?, ?> m && m.get("metadata") instanceof Map<?, ?> md) md.remove("managedFields");
                return Yaml.dump(o);
            }
            @Override protected void done() {
                busy(false, null);
                try {
                    setText(get(), Mode.YAML);
                    loaded = true;
                    busy(false, null);
                    dirty = false;
                    text.setCaretPosition(0);
                    setStatus(doneMessage != null ? doneMessage : "Geladen. Änderungen mit Speichern (Strg+S) übernehmen.", false);
                } catch (Exception ex) {
                    setStatus("Laden fehlgeschlagen: " + message(ex), true);
                }
            }
        };
        worker.execute();
    }

    // ------------------------------------------------------------------ Ansicht wechseln

    /** @return true, wenn die Ansicht jetzt {@code target} ist */
    private boolean switchTo(Mode target) {
        if (target == mode || !loaded) { syncButtons(); return target == mode; }
        try {
            String converted = target == Mode.XML
                    ? XmlView.toXml(YamlReader.parse(text.getText()))
                    : Yaml.dump(XmlView.fromXml(text.getText()));
            setText(converted, target);
            text.setCaretPosition(0);
            setStatus(target == Mode.XML ? "XML-Ansicht: Struktur über Elemente, Einrückung egal. Gespeichert wird als YAML."
                    : "YAML-Ansicht.", false);
            return true;
        } catch (YamlReader.YamlException ex) {
            gotoLine(ex.line);
            fail("YAML fehlerhaft, bitte korrigieren: " + ex.getMessage());
        } catch (RuntimeException ex) {
            gotoLine(lineOf(ex.getMessage()));
            fail((mode == Mode.XML ? "XML fehlerhaft, bitte korrigieren: " : "Umwandlung nicht möglich: ") + ex.getMessage());
        }
        syncButtons();
        return false;
    }

    private void reindent() {
        if (mode != Mode.XML) return;
        try {
            int line = currentLine();
            setText(XmlView.toXml(XmlView.fromXml(text.getText())), Mode.XML);
            gotoLine(line);
            setStatus("XML neu eingerückt.", false);
        } catch (RuntimeException ex) {
            gotoLine(lineOf(ex.getMessage()));
            fail("XML fehlerhaft, bitte korrigieren: " + ex.getMessage());
        }
    }

    private void setText(String s, Mode m) {
        quiet = true;
        try {
            text.setText(s);
        } finally {
            quiet = false;
        }
        mode = m;
        syncButtons();
    }

    private void syncButtons() {
        yamlButton.setSelected(mode == Mode.YAML);
        xmlButton.setSelected(mode == Mode.XML);
        indent.setEnabled(mode == Mode.XML);
    }

    // ------------------------------------------------------------------ Speichern

    private void save() {
        if (!loaded || (worker != null && !worker.isDone())) return;
        if (mode == Mode.XML && !switchTo(Mode.YAML)) return;
        if (!dirty) { setStatus("Keine Änderungen.", false); return; }
        String yaml = text.getText();
        try {
            YamlReader.parse(yaml); // frühe, verständliche Fehlermeldung; kubectl prüft danach noch einmal
        } catch (YamlReader.YamlException ex) {
            gotoLine(ex.line);
            fail("YAML fehlerhaft, bitte korrigieren: " + ex.getMessage());
            return;
        }
        busy(true, "Speichere mit kubectl replace …");
        Kube kube = new Kube(cluster);
        worker = new SwingWorker<String, Void>() {
            @Override protected String doInBackground() throws Exception { return kube.replace(yaml, namespace); }
            @Override protected void done() {
                busy(false, null);
                try {
                    String out = get().trim();
                    dirty = false;
                    if (onSaved != null) onSaved.run();
                    load("Gespeichert" + (out.isEmpty() ? "" : ": " + out) + " (neu geladen)");
                } catch (Exception ex) {
                    String msg = message(ex);
                    if (msg.contains("has been modified")) msg += "\nDas Objekt wurde inzwischen geändert: Neu laden und die Änderung wiederholen.";
                    fail("Speichern fehlgeschlagen: " + msg);
                }
            }
        };
        worker.execute();
    }

    // ------------------------------------------------------------------ Hilfsfunktionen

    private void changed() {
        if (!quiet) dirty = true;
    }

    private boolean confirmDiscard() {
        return !dirty || JOptionPane.showConfirmDialog(this, "Änderungen verwerfen?", getTitle(),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION;
    }

    private void close() {
        if (!confirmDiscard()) return;
        if (worker != null) worker.cancel(true);
        dispose();
    }

    private void busy(boolean on, String msg) {
        setCursor(on ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : null);
        // ohne geladenes Objekt nichts speichern (sonst würde ein leeres Manifest geschickt)
        save.setEnabled(!on && loaded);
        yamlButton.setEnabled(loaded);
        xmlButton.setEnabled(loaded);
        text.setEditable(loaded);
        reload.setEnabled(!on);
        if (msg != null) setStatus(msg, false);
    }

    private int currentLine() {
        try {
            return text.getLineOfOffset(text.getCaretPosition()) + 1;
        } catch (BadLocationException e) {
            return 0;
        }
    }

    private void gotoLine(int line) {
        if (line <= 0) return;
        try {
            int l = Math.min(line, text.getLineCount()) - 1;
            text.setCaretPosition(text.getLineStartOffset(l));
            text.moveCaretPosition(text.getLineEndOffset(l) - (l < text.getLineCount() - 1 ? 1 : 0));
            text.requestFocusInWindow();
        } catch (BadLocationException ignored) {
            // Zeile existiert nicht mehr
        }
    }

    private static int lineOf(String msg) {
        if (msg == null || !msg.startsWith("Zeile ")) return 0;
        int i = 6;
        while (i < msg.length() && Character.isDigit(msg.charAt(i))) i++;
        return i > 6 ? Integer.parseInt(msg.substring(6, i)) : 0;
    }

    private void fail(String msg) {
        setStatus(msg, true);
        java.awt.Toolkit.getDefaultToolkit().beep();
    }

    private void setStatus(String s, boolean error) {
        status.setForeground(error ? new Color(0xB00020) : Color.DARK_GRAY);
        status.setText("<html>" + ResourceGraph.esc(s).replace("\n", "<br>") + "</html>");
    }

    private static String message(Exception ex) {
        Throwable t = ex instanceof java.util.concurrent.ExecutionException && ex.getCause() != null ? ex.getCause() : ex;
        return t.getMessage() != null ? t.getMessage() : t.toString();
    }

    /** Für Tests. */
    String textForTest() { return text.getText(); }
    boolean xmlForTest() { return mode == Mode.XML; }
}
