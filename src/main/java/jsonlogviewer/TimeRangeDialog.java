package jsonlogviewer;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.prefs.Preferences;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFormattedTextField;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

/**
 * "Umliegender Zeitraum": aus einem Zeitpunkt (Zeitstempel der ausgewählten Zeile), einer Spanne und der Art
 * (umliegend / nur davor / nur danach) Von/Bis berechnen, dazu feste Zusatzzeilen davor und danach.
 */
final class TimeRangeDialog extends JDialog {

    /** Ergebnis: Zeiten in der Zeitzone der Zeitstempel-Spalte. */
    record Range(LocalDateTime from, LocalDateTime to, int linesBefore, int linesAfter) {}

    static final String[] SPAN_LABELS = {"1 Min", "5 Min", "15 Min", "1 h", "2 h", "4 h", "8 h", "12 h"};
    static final Duration[] SPANS = {Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15),
            Duration.ofHours(1), Duration.ofHours(2), Duration.ofHours(4), Duration.ofHours(8), Duration.ofHours(12)};

    private final DateTimeField center = new DateTimeField();
    private final JComboBox<String> span = new JComboBox<>(SPAN_LABELS);
    private final JRadioButton around = new JRadioButton("Umliegend (davor und danach)");
    private final JRadioButton before = new JRadioButton("Nur davor");
    private final JRadioButton after = new JRadioButton("Nur danach");
    private final JSpinner linesBefore = new JSpinner(new SpinnerNumberModel(10, 0, 1_000_000, 1));
    private final JSpinner linesAfter = new JSpinner(new SpinnerNumberModel(10, 0, 1_000_000, 1));
    private final ZoneId zone;
    private Range result;

    private TimeRangeDialog(JFrame owner, LocalDateTime time, ZoneId zone, String zoneLabel) {
        super(owner, "Umliegender Zeitraum", true);
        this.zone = zone;
        Preferences prefs = Preferences.userNodeForPackage(TimeRangeDialog.class);
        center.setZoneSupplier(() -> zone);
        center.setReferenceSupplier(() -> time);
        center.setDateTime(time);
        span.setSelectedIndex(Math.max(0, Math.min(SPANS.length - 1, prefs.getInt("rangeSpan", 1))));
        ButtonGroup g = new ButtonGroup();
        g.add(around); g.add(before); g.add(after);
        switch (prefs.get("rangeKind", "around")) {
            case "before" -> before.setSelected(true);
            case "after" -> after.setSelected(true);
            default -> around.setSelected(true);
        }
        for (JSpinner sp : new JSpinner[]{linesBefore, linesAfter}) {
            JFormattedTextField f = ((JSpinner.DefaultEditor) sp.getEditor()).getTextField();
            f.setColumns(6);
            f.setFocusLostBehavior(JFormattedTextField.COMMIT_OR_REVERT);
        }

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(10, 10, 4, 10));
        row(form, 0, "Zeitpunkt (" + zoneLabel + "):", center);
        row(form, 1, "Zeitspanne:", span);
        JPanel kinds = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        kinds.add(around); kinds.add(before); kinds.add(after);
        row(form, 2, "Art:", kinds);
        row(form, 3, "Zusätzlich davor:", withUnit(linesBefore));
        row(form, 4, "Zusätzlich danach:", withUnit(linesAfter));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 1; c.gridy = 5; c.anchor = GridBagConstraints.WEST; c.insets = new Insets(2, 4, 4, 4);
        JLabel hint = new JLabel("<html><small>Zusatzzeilen werden unabhängig von ihrer Zeit vor der ersten<br>"
                + "bzw. nach der letzten Zeile des Zeitraums angezeigt.</small></html>");
        form.add(hint, c);

        JButton ok = new JButton("Übernehmen");
        JButton cancel = new JButton("Abbrechen");
        ok.addActionListener(e -> {
            if (compute()) {
                prefs.putInt("rangeSpan", span.getSelectedIndex());
                prefs.put("rangeKind", before.isSelected() ? "before" : after.isSelected() ? "after" : "around");
                dispose();
            }
        });
        cancel.addActionListener(e -> dispose());
        center.textField().addActionListener(e -> { /* Enter im Feld: nichts Besonderes */ });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(ok);
        buttons.add(cancel);
        getRootPane().setDefaultButton(ok);
        getRootPane().registerKeyboardAction(e -> dispose(), javax.swing.KeyStroke.getKeyStroke("ESCAPE"),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        add(form, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
        pack();
        setLocationRelativeTo(owner);
    }

    private static JPanel withUnit(JSpinner sp) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        p.add(sp);
        p.add(new JLabel("Zeilen"));
        return p;
    }

    private static void row(JPanel p, int y, String label, JComponent field) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.gridy = y; c.anchor = GridBagConstraints.WEST; c.insets = new Insets(4, 4, 4, 4);
        p.add(new JLabel(label), c);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1;
        p.add(field, c);
    }

    private boolean compute() {
        LocalDateTime t = center.value(); // null = leer oder ungültig
        if (t == null) {
            JOptionPane.showMessageDialog(this, "Zeitpunkt nicht erkannt: " + center.getText(), "Umliegender Zeitraum",
                    JOptionPane.ERROR_MESSAGE);
            return false;
        }
        try {
            linesBefore.commitEdit();
            linesAfter.commitEdit();
        } catch (java.text.ParseException ex) {
            JOptionPane.showMessageDialog(this, "Bitte eine Zeilenanzahl von 0 bis 1.000.000 angeben.",
                    "Umliegender Zeitraum", JOptionPane.ERROR_MESSAGE);
            return false;
        }
        Duration d = SPANS[span.getSelectedIndex()];
        LocalDateTime from = after.isSelected() ? t : t.minus(d);
        LocalDateTime to = before.isSelected() ? t : t.plus(d);
        result = new Range(from, to, (Integer) linesBefore.getValue(), (Integer) linesAfter.getValue());
        return true;
    }

    /** Zeigt den Dialog; null bei Abbruch. */
    static Range show(JFrame owner, LocalDateTime time, ZoneId zone, String zoneLabel) {
        TimeRangeDialog d = new TimeRangeDialog(owner, time, zone, zoneLabel);
        d.setVisible(true);
        return d.result;
    }
}
