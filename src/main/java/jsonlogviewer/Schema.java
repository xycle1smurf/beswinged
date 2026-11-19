package jsonlogviewer;

import java.awt.Color;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Tabellen-Schema: welche Spalten aus welchen JSON-Pfaden kommen, in welcher Reihenfolge,
 * sichtbar oder nicht, mit welchem Formatter. Ladbar und speicherbar als YAML oder Properties.
 * Unveränderlich; Änderungen erzeugen über {@link #withColumns} ein neues Schema.
 */
public final class Schema {

    /** Sonderpfad: ganze Rohzeile. */
    public static final String RAW = "$raw";

    /**
     * @param logLevel     Spaltentyp "loglevel": Filter nach Mindest-Level
     * @param choiceFilter Auswahlfilter (Dropdown mit allen vorkommenden Werten) in der Filterleiste
     */
    public record Column(String id, String name, String path, int width, boolean visible,
                         CellFormatter formatter, boolean logLevel, boolean choiceFilter, String[] segments) {
        public Column(String id, String name, String path, int width, boolean visible, CellFormatter formatter,
                      boolean logLevel, boolean choiceFilter) {
            this(id, name, path, width, visible, formatter, logLevel, choiceFilter, Json.compilePath(path));
        }
        public Column(String id, String name, String path, int width) {
            this(id, name, path, width, true, CellFormatter.NONE, false, false);
        }
        public Column withWidth(int w) { return new Column(id, name, path, w, visible, formatter, logLevel, choiceFilter, segments); }
        public Column withVisible(boolean v) { return new Column(id, name, path, width, v, formatter, logLevel, choiceFilter, segments); }
        public Column withFormatter(CellFormatter f) { return new Column(id, name, path, width, visible, f, logLevel, choiceFilter, segments); }
        public Column withLogLevel(boolean b) { return new Column(id, name, path, width, visible, formatter, b, choiceFilter, segments); }
        public Column withChoiceFilter(boolean b) { return new Column(id, name, path, width, visible, formatter, logLevel, b, segments); }
        public boolean isRaw() { return RAW.equals(path); }
    }

    private static boolean isLogLevelType(String type) {
        if (type == null || type.isBlank() || type.equalsIgnoreCase("text")) return false;
        if (type.equalsIgnoreCase("loglevel")) return true;
        throw new IllegalArgumentException("Unbekannter Spaltentyp '" + type + "' (erlaubt: loglevel, text)");
    }

    public final String title;
    public final List<Column> columns;
    /** Optional: Zeilen einfärben anhand des Werts unter diesem Pfad. */
    private final String colorPathText;
    private final String[] colorPath;
    private final Map<String, Color> colors;      // Original-Schreibweise der Werte
    private final Map<String, Color> colorLookup; // Kleinbuchstaben
    public final boolean auto;

    private Schema(String title, List<Column> columns, String colorPath, Map<String, Color> colors, boolean auto) {
        this.title = title;
        this.columns = List.copyOf(columns);
        this.colorPathText = colorPath == null || colorPath.isBlank() ? null : colorPath;
        this.colorPath = colorPathText == null ? null : Json.compilePath(colorPathText);
        this.colors = colors;
        this.colorLookup = new LinkedHashMap<>();
        colors.forEach((k, v) -> colorLookup.put(k.toLowerCase(Locale.ROOT), v));
        this.auto = auto;
    }

    public Schema withColumns(List<Column> cols) {
        return new Schema(title, cols, colorPathText, colors, auto);
    }

    public Color colorFor(Object root) {
        if (colorPath == null || colorLookup.isEmpty()) return null;
        Object v = Json.get(root, colorPath);
        return v == null ? null : colorLookup.get(Json.toDisplay(v).toLowerCase(Locale.ROOT));
    }

    // ------------------------------------------------------------------ Laden

    public static Schema load(Path file) throws IOException {
        if (isProperties(file)) return fromProperties(file);
        return fromYaml(Files.readString(file, StandardCharsets.UTF_8));
    }

    private static boolean isProperties(Path file) {
        return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".properties");
    }

    static Schema fromYaml(String text) {
        Object root = MiniYaml.parse(text);
        if (!(root instanceof Map<?, ?> m)) throw new IllegalArgumentException("YAML-Wurzel muss ein Mapping sein");
        String title = str(m.get("title"), "JSON Log Viewer");
        Object cols = m.get("columns");
        if (!(cols instanceof List<?> colList) || colList.isEmpty())
            throw new IllegalArgumentException("'columns' muss eine nicht-leere Liste sein");
        List<Column> columns = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Object o : colList) {
            if (o instanceof String path) { // Kurzform: "- level"
                columns.add(new Column(uniqueId(ids, path), path, path, 150));
            } else if (o instanceof Map<?, ?> cm) {
                String path = str(cm.get("path"), null);
                if (path == null) throw new IllegalArgumentException("Spalte ohne 'path': " + cm);
                columns.add(new Column(uniqueId(ids, str(cm.get("id"), path)), str(cm.get("name"), path), path,
                        intVal(cm.get("width"), 150), boolVal(cm.get("visible"), true),
                        CellFormatter.fromKey(str(cm.get("formatter"), null)),
                        isLogLevelType(str(cm.get("type"), null)),
                        boolVal(cm.get("choiceFilter"), false)));
            } else throw new IllegalArgumentException("Ungültiger Spalteneintrag: " + o);
        }
        String colorPath = null;
        Map<String, Color> colors = new LinkedHashMap<>();
        if (m.get("rowColors") instanceof Map<?, ?> rc) {
            colorPath = str(rc.get("path"), null);
            if (rc.get("values") instanceof Map<?, ?> vals) {
                for (Map.Entry<?, ?> e : vals.entrySet())
                    colors.put(String.valueOf(e.getKey()), color(String.valueOf(e.getValue())));
            }
        }
        return new Schema(title, columns, colorPath, colors, false);
    }

    static Schema fromProperties(Path file) throws IOException {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        String title = p.getProperty("title", "JSON Log Viewer");
        String idList = p.getProperty("columns");
        if (idList == null || idList.isBlank()) throw new IllegalArgumentException("Property 'columns' fehlt");
        List<Column> columns = new ArrayList<>();
        for (String id : idList.split(",")) {
            id = id.trim();
            if (id.isEmpty()) continue;
            String pre = "column." + id + ".";
            String path = p.getProperty(pre + "path", id);
            columns.add(new Column(id, p.getProperty(pre + "name", id), path,
                    intVal(p.getProperty(pre + "width"), 150),
                    boolVal(p.getProperty(pre + "visible"), true),
                    CellFormatter.fromKey(p.getProperty(pre + "formatter")),
                    isLogLevelType(p.getProperty(pre + "type")),
                    boolVal(p.getProperty(pre + "choiceFilter"), false)));
        }
        Map<String, Color> colors = new LinkedHashMap<>();
        String prefix = "rowColors.values.";
        for (String key : p.stringPropertyNames()) {
            if (key.startsWith(prefix)) colors.put(key.substring(prefix.length()), color(p.getProperty(key)));
        }
        return new Schema(title, columns, p.getProperty("rowColors.path"), colors, false);
    }

    /** Schema automatisch aus den Top-Level-Schlüsseln der ersten Zeilen ableiten. */
    public static Schema auto(List<String> sampleLines) {
        Set<String> keys = new LinkedHashSet<>();
        for (String line : sampleLines) {
            try {
                if (Json.parse(line) instanceof Map<?, ?> m)
                    for (Object k : m.keySet()) keys.add(String.valueOf(k));
            } catch (RuntimeException ignored) {
                // ungültige Zeile -> überspringen
            }
        }
        String levelKey = null;
        for (String k : keys) {
            String lk = k.toLowerCase(Locale.ROOT);
            if (lk.equals("level") || lk.equals("log.level") || lk.equals("severity") || lk.equals("loglevel")) { levelKey = k; break; }
        }
        List<Column> columns = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (String k : keys) {
            Column c = new Column(uniqueId(ids, k), k, k, 150);
            columns.add(k.equals(levelKey) ? c.withLogLevel(true) : c);
        }
        if (columns.isEmpty()) columns.add(new Column("raw", "Zeile", RAW, 1000));
        Map<String, Color> colors = new LinkedHashMap<>();
        if (levelKey != null) {
            colors.put("ERROR", new Color(0xFFD6D6));
            colors.put("FATAL", new Color(0xFFB0B0));
            colors.put("WARN", new Color(0xFFF2C2));
            colors.put("WARNING", new Color(0xFFF2C2));
            colors.put("DEBUG", new Color(0xEEEEEE));
            colors.put("TRACE", new Color(0xF5F5F5));
        }
        return new Schema("JSON Log Viewer (automatisches Schema)", columns, levelKey, colors, true);
    }

    // --------------------------------------------------------------- Speichern

    /**
     * Speichert im Format passend zur Dateiendung. Ein Kommentarblock am Anfang einer bestehenden
     * Datei bleibt erhalten, Kommentare weiter unten nicht.
     */
    public void save(Path file) throws IOException {
        boolean props = isProperties(file);
        String header = Files.exists(file) ? leadingComments(Files.readString(file, StandardCharsets.UTF_8)) : "";
        if (header.isEmpty()) header = "# JSON Log Viewer Schema\n\n";
        Files.writeString(file, header + (props ? toProperties() : toYaml()), StandardCharsets.UTF_8);
    }

    private static String leadingComments(String text) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\r?\n", -1)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("!")) sb.append(line).append('\n');
            else break;
        }
        String s = sb.toString().stripTrailing();
        return s.isEmpty() ? "" : s + "\n\n";
    }

    /** Schema als YAML (ohne Kopfkommentar). */
    public String toYaml() {
        StringBuilder sb = new StringBuilder();
        sb.append("title: ").append(q(auto ? "JSON Log Viewer" : title)).append("\n\n");
        sb.append("columns:\n");
        for (Column c : columns) {
            sb.append("  - name: ").append(q(c.name())).append('\n');
            sb.append("    path: ").append(q(c.path())).append('\n');
            sb.append("    width: ").append(c.width()).append('\n');
            if (!c.visible()) sb.append("    visible: false\n");
            if (c.formatter() != CellFormatter.NONE) sb.append("    formatter: ").append(c.formatter().key).append('\n');
            if (c.logLevel()) sb.append("    type: loglevel\n");
            if (c.choiceFilter()) sb.append("    choiceFilter: true\n");
        }
        if (colorPathText != null && !colors.isEmpty()) {
            sb.append("\nrowColors:\n");
            sb.append("  path: ").append(q(colorPathText)).append('\n');
            sb.append("  values:\n");
            for (Map.Entry<String, Color> e : colors.entrySet())
                sb.append("    ").append(q(e.getKey())).append(": ").append(q(hex(e.getValue()))).append('\n');
        }
        return sb.toString();
    }

    /** Schema als Properties (ohne Kopfkommentar), Reihenfolge wie in der Tabelle. */
    public String toProperties() {
        StringBuilder sb = new StringBuilder();
        sb.append("title=").append(pv(auto ? "JSON Log Viewer" : title)).append('\n');
        List<String> ids = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (Column c : columns) ids.add(uniqueId(used, c.id()));
        sb.append("columns=").append(String.join(",", ids)).append("\n");
        for (int i = 0; i < columns.size(); i++) {
            Column c = columns.get(i);
            String pre = "column." + ids.get(i) + ".";
            sb.append('\n');
            sb.append(pre).append("name=").append(pv(c.name())).append('\n');
            sb.append(pre).append("path=").append(pv(c.path())).append('\n');
            sb.append(pre).append("width=").append(c.width()).append('\n');
            if (!c.visible()) sb.append(pre).append("visible=false\n");
            if (c.formatter() != CellFormatter.NONE) sb.append(pre).append("formatter=").append(c.formatter().key).append('\n');
            if (c.logLevel()) sb.append(pre).append("type=loglevel\n");
            if (c.choiceFilter()) sb.append(pre).append("choiceFilter=true\n");
        }
        if (colorPathText != null && !colors.isEmpty()) {
            sb.append("\nrowColors.path=").append(pv(colorPathText)).append('\n');
            for (Map.Entry<String, Color> e : colors.entrySet())
                sb.append("rowColors.values.").append(pk(e.getKey())).append('=').append(hex(e.getValue())).append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------- Helfer

    private static String uniqueId(Set<String> used, String raw) {
        String base = raw.replaceAll("[^A-Za-z0-9_-]+", "_").replaceAll("^_+|_+$", "");
        if (base.isEmpty()) base = "col";
        String id = base;
        for (int n = 2; !used.add(id); n++) id = base + "_" + n;
        return id;
    }

    private static String hex(Color c) {
        return String.format("#%06X", c.getRGB() & 0xFFFFFF);
    }

    private static String q(String s) {
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    /** Properties-Wert escapen. */
    private static String pv(String s) {
        String e = s.replace("\\", "\\\\").replace("\n", "\\n");
        return e.startsWith(" ") ? "\\" + e : e;
    }

    /** Properties-Schlüssel escapen. */
    private static String pk(String s) {
        return s.replace("\\", "\\\\").replace("\n", "\\n").replace(" ", "\\ ").replace("=", "\\=").replace(":", "\\:").replace("#", "\\#").replace("!", "\\!");
    }

    private static String str(Object o, String def) {
        return o == null ? def : o.toString();
    }

    private static int intVal(Object o, int def) {
        if (o == null) return def;
        try { return Integer.parseInt(o.toString().trim()); } catch (NumberFormatException e) { return def; }
    }

    private static boolean boolVal(Object o, boolean def) {
        if (o == null) return def;
        String s = o.toString().trim().toLowerCase(Locale.ROOT);
        if (s.equals("false") || s.equals("no") || s.equals("off")) return false;
        if (s.equals("true") || s.equals("yes") || s.equals("on")) return true;
        return def;
    }

    private static Color color(String s) {
        s = s.trim();
        if (s.startsWith("#")) s = s.substring(1);
        try {
            return new Color(Integer.parseInt(s, 16));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Ungültige Farbe: " + s + " (erwartet #RRGGBB)");
        }
    }
}
