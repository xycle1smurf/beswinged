package jsonlogviewer;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * Datei → Log einfügen: Textfeld für einen Log-Ausschnitt aus der Zwischenablage (z.B. aus Teams). „Anzeigen" schreibt
 * den Text nach {@code ~/.beswinged/paste/} und öffnet ihn wie eine Datei. Nicht modal; mehrere Fenster möglich.
 */
final class PasteWindow extends JFrame {

    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss");
    private static final int KEEP_DAYS = 14;

    private final JsonLogViewer viewer;
    private final JTextArea text = new JTextArea(24, 100);
    private final JCheckBox repair = new JCheckBox("Reparieren", true);
    private final JLabel info = new JLabel(" ");
    private Path file;

    PasteWindow(JsonLogViewer viewer) {
        super("Log einfügen");
        this.viewer = viewer;
        setIconImages(HelpMenu.icons());
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        text.setTabSize(4);
        text.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { updateInfo(); }
            @Override public void removeUpdate(DocumentEvent e) { updateInfo(); }
            @Override public void changedUpdate(DocumentEvent e) { updateInfo(); }
        });
        repair.setToolTipText("<html>Über mehrere Zeilen verteilte JSON-Objekte zusammenfügen,<br>"
                + "geschützte Leerzeichen, unsichtbare Zeichen und typografische Anführungszeichen korrigieren<br>"
                + "(nur in Zeilen, die sonst kein gültiges JSON wären).</html>");
        repair.addActionListener(e -> updateInfo());

        JLabel hint = new JLabel("Log-Zeilen hier einfügen (Strg+V), z.B. aus Teams, einer Mail oder einem Ticket. Ein JSON-Objekt pro Zeile.");
        hint.setBorder(BorderFactory.createEmptyBorder(8, 8, 6, 8));
        JButton paste = new JButton("Aus Zwischenablage");
        paste.addActionListener(e -> { text.setText(clipboard()); text.setCaretPosition(0); });
        JButton clear = new JButton("Leeren");
        clear.addActionListener(e -> { text.setText(""); text.requestFocusInWindow(); });
        JButton show = new JButton("Anzeigen");
        show.setToolTipText("Text als Log im Hauptfenster öffnen (Strg+Enter)");
        show.addActionListener(e -> display());
        JButton close = new JButton("Schließen");
        close.addActionListener(e -> dispose());

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        left.add(paste);
        left.add(clear);
        left.add(repair);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.add(show);
        right.add(close);
        JPanel south = new JPanel(new BorderLayout());
        south.setBorder(BorderFactory.createEmptyBorder(6, 4, 8, 4));
        south.add(left, BorderLayout.CENTER);
        south.add(right, BorderLayout.EAST);
        info.setBorder(BorderFactory.createEmptyBorder(0, 6, 6, 6));
        south.add(info, BorderLayout.NORTH);
        info.setForeground(Color.DARK_GRAY);

        add(hint, BorderLayout.NORTH);
        add(new JScrollPane(text), BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(null);
        getRootPane().registerKeyboardAction(e -> display(),
                KeyStroke.getKeyStroke("ctrl ENTER"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        getRootPane().registerKeyboardAction(e -> dispose(),
                KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);

        String clip = clipboard();
        if (looksLikeLog(clip)) { text.setText(clip); text.setCaretPosition(0); }
        pack();
        setLocationRelativeTo(viewer);
        updateInfo();
    }

    private void display() {
        String raw = text.getText();
        if (raw.isBlank()) { info.setText("Kein Text."); return; }
        Result r = repair.isSelected() ? normalize(raw) : new Result(List.of(raw.split("\\R", -1)), 0, 0);
        try {
            if (file == null) {
                Path dir = Settings.get().dir().resolve("paste");
                Files.createDirectories(dir);
                cleanup(dir);
                file = dir.resolve("einfuegen-" + LocalDateTime.now().format(FILE_TS) + ".jsonl");
                for (int n = 2; Files.exists(file); n++)
                    file = dir.resolve("einfuegen-" + LocalDateTime.now().format(FILE_TS) + "-" + n + ".jsonl");
            }
            Files.writeString(file, String.join("\n", r.lines()) + "\n", StandardCharsets.UTF_8);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Konnte nicht speichern: " + ex.getMessage(), "Log einfügen", JOptionPane.ERROR_MESSAGE);
            return;
        }
        viewer.openLog(file, LineIndex.Mode.RAM, true);
        viewer.toFront();
        setTitle("Log einfügen – " + file.getFileName());
    }

    private void updateInfo() {
        String raw = text.getText();
        if (raw.isBlank()) { info.setText(" "); return; }
        Result r = repair.isSelected() ? normalize(raw) : new Result(List.of(raw.split("\\R")), 0, 0);
        int valid = 0, total = 0;
        for (String l : r.lines()) {
            if (l.isBlank()) continue;
            total++;
            if (isJson(l)) valid++;
        }
        StringBuilder s = new StringBuilder(total + " Zeilen, " + valid + " gültiges JSON");
        if (r.joined() > 0) s.append(", ").append(r.joined()).append(" zusammengefügt");
        if (r.fixed() > 0) s.append(", ").append(r.fixed()).append(" korrigiert");
        info.setText(s.toString());
    }

    // ------------------------------------------------------------------ Reparieren

    record Result(List<String> lines, int joined, int fixed) { }

    /**
     * Fügt mehrzeilige JSON-Objekte zu einer Zeile zusammen und korrigiert Zeichen, die beim Kopieren aus Chat-Programmen
     * entstehen. Zeilen, die schon gültiges JSON sind, bleiben unverändert.
     */
    static Result normalize(String raw) {
        String[] in = raw.replace("﻿", "").split("\\R");
        List<String> out = new ArrayList<>();
        int joined = 0, fixed = 0;
        for (int i = 0; i < in.length; i++) {
            String line = in[i];
            String t = clean(line).trim();
            if (t.startsWith("{") && depth(t) > 0) {
                // über mehrere Zeilen verteiltes Objekt (formatiertes JSON oder Zeilenumbruch beim Einfügen)
                StringBuilder sb = new StringBuilder(t);
                int j = i + 1, d = depth(t);
                while (j < in.length && d > 0 && j - i < 5000) {
                    String next = clean(in[j]).trim();
                    sb.append(next.startsWith("\"") || next.startsWith("}") || next.startsWith("]") ? "" : " ").append(next);
                    d = depth(sb.toString());
                    j++;
                }
                String cand = sb.toString();
                if (d == 0 && isJson(cand)) {
                    out.add(Json.stringify(Json.parse(cand), false));
                    joined++;
                    i = j - 1;
                    continue;
                }
                if (d == 0 && isJson(fixQuotes(cand))) {
                    out.add(Json.stringify(Json.parse(fixQuotes(cand)), false));
                    joined++;
                    fixed++;
                    i = j - 1;
                    continue;
                }
            }
            if (isJson(line.trim())) { out.add(line); continue; }
            String c = clean(line);
            if (isJson(c.trim())) { out.add(c.trim()); fixed++; continue; }
            String q = fixQuotes(c);
            if (isJson(q.trim())) { out.add(q.trim()); fixed++; continue; }
            out.add(line);
        }
        return new Result(out, joined, fixed);
    }

    /** Geschützte und schmale Leerzeichen zu normalen, unsichtbare Zeichen entfernen. */
    static String clean(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case ' ', ' ', ' ', ' ', ' ', ' ' -> sb.append(' ');
                case '​', '‌', '‍', '⁠', '﻿', '­' -> { }
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Typografische Anführungszeichen (von Autokorrektur eingesetzt) zu geraden. */
    static String fixQuotes(String s) {
        return s.replace('“', '"').replace('”', '"').replace('„', '"').replace('«', '"').replace('»', '"')
                .replace('‘', '\'').replace('’', '\'');
    }

    /** Offene Klammern außerhalb von Strings. */
    private static int depth(String s) {
        int d = 0;
        boolean str = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (str) {
                if (c == '\\') i++;
                else if (c == '"') str = false;
            } else if (c == '"') str = true;
            else if (c == '{' || c == '[') d++;
            else if (c == '}' || c == ']') d--;
        }
        return d;
    }

    private static boolean isJson(String s) {
        if (!s.startsWith("{")) return false;
        try {
            return Json.parse(s) instanceof java.util.Map;
        } catch (Json.ParseException ex) {
            return false;
        }
    }

    private static boolean looksLikeLog(String s) {
        return s != null && !s.isBlank() && s.length() < 200_000_000 && (s.contains("{") || s.lines().count() > 1);
    }

    private static String clipboard() {
        try {
            Object o = Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
            return o == null ? "" : o.toString();
        } catch (Exception ex) {
            return "";
        }
    }

    /** Alte eingefügte Logs löschen, die nicht mehr geöffnet sind. */
    private void cleanup(Path dir) {
        long limit = System.currentTimeMillis() - KEEP_DAYS * 24L * 3600_000L;
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().startsWith("einfuegen-"))
             .filter(p -> { try { return Files.getLastModifiedTime(p).toMillis() < limit; } catch (IOException e) { return false; } })
             .filter(p -> !viewer.isOpen(p))
             .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) { } });
        } catch (IOException ignored) {
            // nicht wichtig
        }
    }
}
