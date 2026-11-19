package jsonlogviewer;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.TextStyle;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.UIManager;

/**
 * Eingabefeld für Datum und Uhrzeit mit Kalender- und Uhrzeitauswahl (Button rechts).
 * Der Text bleibt frei editierbar; {@link #toInstant} wertet ihn in einer Zeitzone aus.
 */
final class DateTimeField extends JPanel {

    static final DateTimeFormatter OUT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    static final DateTimeFormatter OUT_MS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static final DateTimeFormatter IN_TIME = new DateTimeFormatterBuilder()
            .appendPattern("H:mm").optionalStart().appendPattern(":ss")
            .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true).optionalEnd().optionalEnd()
            .toFormatter();
    private static final DateTimeFormatter IN_ISO = withTime("uuuu-MM-dd");
    private static final DateTimeFormatter IN_DE = withTime("d.M.uuuu");
    private static final Pattern OFFSET_SUFFIX = Pattern.compile("(?:Z|[+-]\\d{2}(?::?\\d{2})?)$");

    private static DateTimeFormatter withTime(String datePattern) {
        return new DateTimeFormatterBuilder().appendPattern(datePattern)
                .optionalStart().appendLiteral(' ').append(IN_TIME).optionalEnd().toFormatter();
    }

    private final JTextField text = new JTextField(17);
    private final JButton pick = new JButton(new CalendarIcon());
    private Supplier<ZoneId> zone = ZoneId::systemDefault;
    private Supplier<LocalDateTime> reference = LocalDateTime::now; // Vorschlag, wenn das Feld leer ist

    DateTimeField() {
        super(new BorderLayout(2, 0));
        setOpaque(false);
        add(text, BorderLayout.CENTER);
        pick.setMargin(new Insets(1, 3, 1, 3));
        pick.setFocusable(false);
        pick.setToolTipText("Kalender und Uhrzeit");
        pick.addActionListener(e -> showPicker());
        add(pick, BorderLayout.EAST);
        setMaximumSize(new Dimension(220, 28));
        text.setToolTipText("<html>z.B. 2026-11-19 08:15:30, 19.11.2026 08:15, 08:15 (Datum der ausgewählten Zeile)<br>"
                + "oder mit Offset: 2026-11-19T07:15:00Z. Leer = offen.</html>");
    }

    JTextField textField() { return text; }
    String getText() { return text.getText(); }
    void setText(String s) { text.setText(s); }
    void setZoneSupplier(Supplier<ZoneId> z) { zone = z; }
    void setReferenceSupplier(Supplier<LocalDateTime> r) { reference = r; }

    void setDateTime(LocalDateTime t) {
        text.setText(t == null ? "" : format(t));
    }

    static String format(LocalDateTime t) {
        return (t.getNano() / 1_000_000 != 0 ? OUT_MS : OUT).format(t);
    }

    @Override public void setEnabled(boolean b) {
        super.setEnabled(b);
        text.setEnabled(b);
        pick.setEnabled(b);
    }

    /** Leeres Feld = null. Ohne Datum gilt das Datum von {@code defaultDate}. */
    static Instant toInstant(String input, ZoneId zone, LocalDate defaultDate) {
        String s = input.trim().replace('T', ' ').replace(',', '.');
        if (s.isEmpty()) return null;
        if (s.matches("\\d{4}-.*") && OFFSET_SUFFIX.matcher(s).find()) {
            Instant i = CellFormatter.parseInstant(s.replace(' ', 'T'));
            if (i == null) throw new DateTimeException("ungültig: " + input);
            return i;
        }
        return parseLocal(s, defaultDate).atZone(zone).toInstant();
    }

    /** Text ohne Offset als lokale Zeit. */
    static LocalDateTime parseLocal(String input, LocalDate defaultDate) {
        String s = input.trim().replace('T', ' ').replace(',', '.');
        if (s.matches("\\d{1,2}:\\d{2}.*")) return defaultDate.atTime(LocalTime.parse(s, IN_TIME));
        DateTimeFormatter f = s.matches("\\d{4}-.*") ? IN_ISO : IN_DE;
        TemporalAccessor ta = f.parseBest(s, LocalDateTime::from, LocalDate::from);
        return ta instanceof LocalDateTime l ? l : ((LocalDate) ta).atStartOfDay();
    }

    /** Aktueller Wert als lokale Zeit in der Feld-Zeitzone, oder null. */
    LocalDateTime value() {
        try {
            LocalDate def = reference.get().toLocalDate();
            Instant i = toInstant(text.getText(), zone.get(), def);
            return i == null ? null : LocalDateTime.ofInstant(i, zone.get());
        } catch (DateTimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ Popup

    private void showPicker() {
        LocalDateTime initial = value();
        Picker p = new Picker(initial != null ? initial : reference.get().withNano(0), t -> {
            setDateTime(t);
            text.requestFocusInWindow();
            text.postActionEvent(); // wie Enter
        });
        p.show(this, 0, getHeight());
    }

    /** Monatskalender + Uhrzeit. */
    static final class Picker extends JPopupMenu {
        private final java.util.function.Consumer<LocalDateTime> onOk;
        private final LocalDateTime original;
        private YearMonth month;
        private LocalDate day;
        private final JLabel title = new JLabel("", SwingConstants.CENTER);
        private final JPanel grid = new JPanel(new GridLayout(0, 7, 1, 1));
        private final JSpinner h, m, s;

        Picker(LocalDateTime initial, java.util.function.Consumer<LocalDateTime> onOk) {
            this.onOk = onOk;
            this.original = initial;
            this.day = initial.toLocalDate();
            this.month = YearMonth.from(day);
            setLayout(new BorderLayout(0, 4));
            setBorder(BorderFactory.createCompoundBorder(getBorder(), BorderFactory.createEmptyBorder(6, 6, 6, 6)));

            JPanel head = new JPanel(new BorderLayout());
            JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
            left.add(nav("«", "Jahr zurück", () -> month = month.minusYears(1)));
            left.add(nav("‹", "Monat zurück", () -> month = month.minusMonths(1)));
            right.add(nav("›", "Monat vor", () -> month = month.plusMonths(1)));
            right.add(nav("»", "Jahr vor", () -> month = month.plusYears(1)));
            title.setFont(title.getFont().deriveFont(Font.BOLD));
            head.add(left, BorderLayout.WEST);
            head.add(title, BorderLayout.CENTER);
            head.add(right, BorderLayout.EAST);
            add(head, BorderLayout.NORTH);
            add(grid, BorderLayout.CENTER);

            h = spinner(initial.getHour(), 23);
            m = spinner(initial.getMinute(), 59);
            s = spinner(initial.getSecond(), 59);
            JPanel time = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
            time.add(new JLabel("Uhrzeit:"));
            time.add(h); time.add(new JLabel(":"));
            time.add(m); time.add(new JLabel(":"));
            time.add(s);
            JButton now = new JButton("Jetzt");
            now.setMargin(new Insets(1, 6, 1, 6));
            now.addActionListener(e -> {
                LocalDateTime n = LocalDateTime.now();
                day = n.toLocalDate(); month = YearMonth.from(day);
                h.setValue(n.getHour()); m.setValue(n.getMinute()); s.setValue(n.getSecond());
                rebuild();
            });
            JButton ok = new JButton("OK");
            ok.addActionListener(e -> commit());
            JPanel south = new JPanel(new BorderLayout());
            south.add(time, BorderLayout.WEST);
            JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 0));
            btns.add(now);
            btns.add(ok);
            south.add(btns, BorderLayout.EAST);
            add(south, BorderLayout.SOUTH);
            rebuild();
        }

        private static JSpinner spinner(int v, int max) {
            JSpinner sp = new JSpinner(new SpinnerNumberModel(v, 0, max, 1));
            JSpinner.NumberEditor ed = new JSpinner.NumberEditor(sp, "00");
            sp.setEditor(ed);
            ed.getTextField().setColumns(2);
            return sp;
        }

        private JButton nav(String label, String tip, Runnable r) {
            JButton b = new JButton(label);
            b.setMargin(new Insets(0, 6, 0, 6));
            b.setToolTipText(tip);
            b.setFocusable(false);
            b.addActionListener(e -> { r.run(); rebuild(); });
            return b;
        }

        private void rebuild() {
            title.setText(month.getMonth().getDisplayName(TextStyle.FULL, Locale.GERMAN) + " " + month.getYear());
            grid.removeAll();
            for (DayOfWeek d : DayOfWeek.values()) {
                JLabel l = new JLabel(d.getDisplayName(TextStyle.SHORT_STANDALONE, Locale.GERMAN).substring(0, 2), SwingConstants.CENTER);
                l.setForeground(Color.GRAY);
                grid.add(l);
            }
            LocalDate first = month.atDay(1);
            int lead = first.getDayOfWeek().getValue() - 1;
            for (int i = 0; i < lead; i++) grid.add(new JLabel());
            LocalDate today = LocalDate.now();
            for (int d = 1; d <= month.lengthOfMonth(); d++) {
                LocalDate date = month.atDay(d);
                JButton b = new JButton(String.valueOf(d));
                b.setMargin(new Insets(2, 2, 2, 2));
                b.setFocusable(false);
                if (date.equals(day)) {
                    b.setBackground(UIManager.getColor("List.selectionBackground"));
                    b.setForeground(UIManager.getColor("List.selectionForeground"));
                    b.setOpaque(true);
                    b.setBorderPainted(false);
                }
                if (date.equals(today)) b.setFont(b.getFont().deriveFont(Font.BOLD));
                if (date.getDayOfWeek().getValue() >= 6 && !date.equals(day)) b.setForeground(new Color(0xB03030));
                b.addActionListener(e -> { day = date; rebuild(); });
                b.addMouseListener(new MouseAdapter() {
                    @Override public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2) commit(); }
                });
                grid.add(b);
            }
            int cells = 7 + lead + month.lengthOfMonth();
            for (int i = cells; i < 7 * 7; i++) grid.add(new JLabel(" ")); // feste Höhe (6 Wochen)
            grid.revalidate();
            grid.repaint();
            pack();
        }

        private void commit() {
            commitSpinner(h); commitSpinner(m); commitSpinner(s);
            LocalTime t = LocalTime.of((Integer) h.getValue(), (Integer) m.getValue(), (Integer) s.getValue());
            // Millisekunden des Ausgangswerts behalten, solange die Uhrzeit nicht geändert wurde
            if (t.equals(original.toLocalTime().withNano(0))) t = original.toLocalTime();
            setVisible(false);
            onOk.accept(day.atTime(t));
        }

        private static void commitSpinner(JSpinner sp) {
            try { sp.commitEdit(); } catch (java.text.ParseException ignored) { }
        }

        @Override public void show(Component invoker, int x, int y) {
            super.show(invoker, x, y);
        }
    }

    /** Kleines gezeichnetes Kalender-Symbol (Emoji rendert Swing unter Windows nicht zuverlässig). */
    private static final class CalendarIcon implements Icon {
        @Override public int getIconWidth() { return 14; }
        @Override public int getIconHeight() { return 14; }
        @Override public void paintIcon(Component c, Graphics g0, int x, int y) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color fg = c.isEnabled() ? new Color(0x404040) : Color.LIGHT_GRAY;
            g.setColor(c.isEnabled() ? new Color(0xC62828) : Color.LIGHT_GRAY);
            g.fillRect(x + 1, y + 2, 12, 3);
            g.setColor(fg);
            g.setStroke(new BasicStroke(1f));
            g.drawRect(x + 1, y + 2, 12, 11);
            g.fillRect(x + 4, y, 1, 3);
            g.fillRect(x + 10, y, 1, 3);
            for (int r = 0; r < 2; r++)
                for (int k = 0; k < 3; k++) g.fillRect(x + 3 + k * 3, y + 7 + r * 3, 2, 2);
            g.dispose();
        }
    }
}
