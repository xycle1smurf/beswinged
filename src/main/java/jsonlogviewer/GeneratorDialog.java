package jsonlogviewer;

import java.awt.BorderLayout;
import java.awt.EventQueue;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.filechooser.FileNameExtensionFilter;

/** Dialog zu {@link LogGenerator}: Anzahl Zeilen, Abstand, Schema und Zieldatei wählen. */
final class GeneratorDialog extends JDialog {

    /** Was nach dem Erzeugen geöffnet werden soll. */
    interface Result {
        /**
         * @param log        erzeugte Datei
         * @param schema     verwendetes Schema (null = Logstash-Standard)
         * @param schemaFile Schema-Datei, falls eine gewählt wurde, sonst null
         */
        void open(Path log, Schema schema, Path schemaFile);
    }

    private static final String STANDARD = "Logstash-Format (Standard)";
    private static final String CHOOSE = "Schema-Datei wählen…";

    private final JSpinner lines = new JSpinner(new SpinnerNumberModel(1000, 1, 50_000_000, 100));
    private final JSpinner interval = new JSpinner(new SpinnerNumberModel(15, 1, 86_400, 1));
    private final JComboBox<String> schemaBox = new JComboBox<>();
    private final JTextField target = new JTextField(32);
    private final JCheckBox openAfter = new JCheckBox("Nach dem Erzeugen öffnen", true);
    private final JProgressBar progress = new JProgressBar(0, 1000);
    private final JButton generate = new JButton("Erzeugen");
    private final JButton cancel = new JButton("Schließen");

    private final Schema currentSchema;
    private final String currentLabel;
    private Path chosenFile;
    private AtomicBoolean running;

    GeneratorDialog(JFrame owner, File dir, Schema currentSchema, String currentName, Result result) {
        super(owner, "Log-Generator", true);
        this.currentSchema = currentSchema;
        this.currentLabel = currentSchema == null ? null : "Aktuelles Schema (" + currentName + ")";

        schemaBox.addItem(STANDARD);
        if (currentLabel != null) schemaBox.addItem(currentLabel);
        schemaBox.addItem(CHOOSE);
        schemaBox.setToolTipText("Mit Schema werden genau dessen Spaltenpfade befüllt, passend zum Spaltennamen");
        schemaBox.addActionListener(e -> {
            if (CHOOSE.equals(schemaBox.getSelectedItem())) chooseSchemaFile(dir);
        });

        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        target.setText(new File(dir, "generated-" + stamp + ".jsonl").getAbsolutePath());
        JButton browse = new JButton("…");
        browse.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(dir);
            fc.setSelectedFile(new File(target.getText()));
            fc.setFileFilter(new FileNameExtensionFilter("JSON Lines (*.jsonl, *.json, *.log)", "jsonl", "json", "log"));
            if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) target.setText(fc.getSelectedFile().getAbsolutePath());
        });

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(10, 10, 4, 10));
        row(form, 0, "Anzahl Zeilen:", lines);
        row(form, 1, "Abstand (Sekunden):", interval);
        row(form, 2, "Schema:", schemaBox);
        JPanel file = new JPanel(new BorderLayout(4, 0));
        file.add(target, BorderLayout.CENTER);
        file.add(browse, BorderLayout.EAST);
        row(form, 3, "Zieldatei:", file);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 1; c.gridy = 4; c.anchor = GridBagConstraints.WEST; c.insets = new Insets(4, 4, 4, 4);
        form.add(openAfter, c);
        c.gridy = 5; c.fill = GridBagConstraints.HORIZONTAL;
        progress.setVisible(false);
        form.add(progress, c);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(generate);
        buttons.add(cancel);
        generate.addActionListener(e -> start(result));
        cancel.addActionListener(e -> {
            if (running != null) running.set(true);
            dispose();
        });
        getRootPane().setDefaultButton(generate);

        add(form, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
        pack();
        setLocationRelativeTo(owner);
    }

    private static void row(JPanel p, int y, String label, JComponent field) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.gridy = y; c.anchor = GridBagConstraints.WEST; c.insets = new Insets(4, 4, 4, 4);
        p.add(new JLabel(label), c);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1;
        p.add(field, c);
    }

    private void chooseSchemaFile(File dir) {
        JFileChooser fc = new JFileChooser(dir);
        fc.setFileFilter(new FileNameExtensionFilter("Schema (YAML, Properties)", "yaml", "yml", "properties"));
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            chosenFile = fc.getSelectedFile().toPath();
            String label = "Datei: " + chosenFile.getFileName();
            schemaBox.insertItemAt(label, schemaBox.getItemCount() - 1);
            schemaBox.setSelectedItem(label);
        } else {
            schemaBox.setSelectedIndex(0);
        }
    }

    private void start(Result result) {
        int count = (Integer) lines.getValue();
        int step = (Integer) interval.getValue();
        Path out = Path.of(target.getText().trim());
        Object sel = schemaBox.getSelectedItem();
        Schema schema;
        Path schemaFile = null;
        try {
            if (STANDARD.equals(sel)) schema = null;
            else if (sel != null && sel.equals(currentLabel)) schema = currentSchema;
            else if (chosenFile != null) { schemaFile = chosenFile; schema = Schema.load(chosenFile); }
            else schema = null;
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Schema konnte nicht geladen werden:\n" + ex.getMessage(), "Fehler", JOptionPane.ERROR_MESSAGE);
            return;
        }

        generate.setEnabled(false);
        progress.setVisible(true);
        progress.setValue(0);
        pack();
        AtomicBoolean stop = new AtomicBoolean();
        running = stop;
        // letzte Zeile liegt ungefähr bei "jetzt"
        Instant begin = Instant.now().minusSeconds((long) step * count).truncatedTo(ChronoUnit.SECONDS);
        Path fSchemaFile = schemaFile;
        Thread t = new Thread(() -> {
            try {
                new LogGenerator(begin, step, System.nanoTime()).write(out, count, schema,
                        n -> EventQueue.invokeLater(() -> progress.setValue((int) ((long) n * 1000 / count))), stop::get);
                if (stop.get()) return;
                EventQueue.invokeLater(() -> {
                    dispose();
                    if (openAfter.isSelected()) result.open(out, schema, fSchemaFile);
                });
            } catch (Exception ex) {
                EventQueue.invokeLater(() -> {
                    generate.setEnabled(true);
                    JOptionPane.showMessageDialog(this, "Erzeugen fehlgeschlagen:\n" + ex.getMessage(), "Fehler", JOptionPane.ERROR_MESSAGE);
                });
            }
        }, "log-generator");
        t.setDaemon(true);
        t.start();
    }
}
