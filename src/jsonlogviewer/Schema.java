package jsonlogviewer;

import java.awt.Color;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Tabellen-Schema: welche Spalten aus welchen JSON-Pfaden kommen.
 * Ladbar aus YAML (.yaml/.yml) oder Properties (.properties).
 */
public final class Schema {

    /** Sonderpfad: ganze Rohzeile. */
    public static final String RAW = "$raw";

    public record Column(String name, String path, int width, String[] segments) {
        public Column(String name, String path, int width) {
            this(name, path, width, Json.compilePath(path));
        }
    }

    public final String title;
    public final List<Column> columns;
    /** Optional: Zeilen einfärben anhand des Werts unter diesem Pfad. */
    public final String[] colorPath;
    public final Map<String, Color> colors; // Schlüssel in Kleinbuchstaben
    public final boolean auto;

    private Schema(String title, List<Column> columns, String colorPath, Map<String, Color> colors, boolean auto) {
        this.title = title;
        this.columns = List.copyOf(columns);
        this.colorPath = colorPath == null || colorPath.isBlank() ? null : Json.compilePath(colorPath);
        this.colors = colors;
        this.auto = auto;
    }

    public Color colorFor(Object root) {
        if (colorPath == null || colors.isEmpty()) return null;
        Object v = Json.get(root, colorPath);
        return v == null ? null : colors.get(Json.toDisplay(v).toLowerCase(Locale.ROOT));
    }

    // ------------------------------------------------------------------ Laden

    public static Schema load(Path file) throws IOException {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".properties")) return fromProperties(file);
        return fromYaml(Files.readString(file, StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    static Schema fromYaml(String text) {
        Object root = MiniYaml.parse(text);
        if (!(root instanceof Map<?, ?> m)) throw new IllegalArgumentException("YAML-Wurzel muss ein Mapping sein");
        String title = str(m.get("title"), "JSON Log Viewer");
        Object cols = m.get("columns");
        if (!(cols instanceof List<?> colList) || colList.isEmpty())
            throw new IllegalArgumentException("'columns' muss eine nicht-leere Liste sein");
        List<Column> columns = new ArrayList<>();
        for (Object o : colList) {
            if (o instanceof String path) { // Kurzform: "- level"
                columns.add(new Column(path, path, 150));
            } else if (o instanceof Map<?, ?> cm) {
                String path = str(cm.get("path"), null);
                if (path == null) throw new IllegalArgumentException("Spalte ohne 'path': " + cm);
                columns.add(new Column(str(cm.get("name"), path), path, intVal(cm.get("width"), 150)));
            } else throw new IllegalArgumentException("Ungültiger Spalteneintrag: " + o);
        }
        String colorPath = null;
        Map<String, Color> colors = new LinkedHashMap<>();
        if (m.get("rowColors") instanceof Map<?, ?> rc) {
            colorPath = str(rc.get("path"), null);
            if (rc.get("values") instanceof Map<?, ?> vals) {
                for (Map.Entry<?, ?> e : ((Map<Object, Object>) vals).entrySet())
                    colors.put(String.valueOf(e.getKey()).toLowerCase(Locale.ROOT), color(String.valueOf(e.getValue())));
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
        String ids = p.getProperty("columns");
        if (ids == null || ids.isBlank()) throw new IllegalArgumentException("Property 'columns' fehlt");
        List<Column> columns = new ArrayList<>();
        for (String id : ids.split(",")) {
            id = id.trim();
            if (id.isEmpty()) continue;
            String path = p.getProperty("column." + id + ".path", id);
            String name = p.getProperty("column." + id + ".name", id);
            int width = intVal(p.getProperty("column." + id + ".width"), 150);
            columns.add(new Column(name, path, width));
        }
        Map<String, Color> colors = new LinkedHashMap<>();
        String prefix = "rowColors.values.";
        for (String key : p.stringPropertyNames()) {
            if (key.startsWith(prefix))
                colors.put(key.substring(prefix.length()).toLowerCase(Locale.ROOT), color(p.getProperty(key)));
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
        List<Column> columns = new ArrayList<>();
        for (String k : keys) {
            // Pfad mit Punkt im Schlüssel funktioniert dank Longest-Match in Json.get
            columns.add(new Column(k, k, 150));
        }
        if (columns.isEmpty()) columns.add(new Column("Zeile", RAW, 1000));
        Map<String, Color> colors = new LinkedHashMap<>();
        String levelKey = null;
        for (String k : keys) {
            String lk = k.toLowerCase(Locale.ROOT);
            if (lk.equals("level") || lk.equals("log.level") || lk.equals("severity") || lk.equals("loglevel")) { levelKey = k; break; }
        }
        if (levelKey != null) defaultLevelColors(colors);
        return new Schema("JSON Log Viewer (automatisches Schema)", columns, levelKey, colors, true);
    }

    private static void defaultLevelColors(Map<String, Color> colors) {
        colors.put("error", new Color(0xFFD6D6));
        colors.put("fatal", new Color(0xFFB0B0));
        colors.put("warn", new Color(0xFFF2C2));
        colors.put("warning", new Color(0xFFF2C2));
        colors.put("debug", new Color(0xEEEEEE));
        colors.put("trace", new Color(0xF5F5F5));
    }

    /** Schema als YAML (z.B. um ein automatisch erkanntes Schema als Vorlage zu speichern). */
    public String toYaml() {
        StringBuilder sb = new StringBuilder();
        sb.append("# JSON Log Viewer Schema\n");
        sb.append("title: ").append(q(auto ? "JSON Log Viewer" : title)).append("\n\n");
        sb.append("columns:\n");
        for (Column c : columns) {
            sb.append("  - name: ").append(q(c.name())).append('\n');
            sb.append("    path: ").append(q(c.path())).append('\n');
            sb.append("    width: ").append(c.width()).append('\n');
        }
        if (colorPath != null && !colors.isEmpty()) {
            sb.append("\nrowColors:\n");
            sb.append("  path: ").append(q(String.join(".", colorPath))).append('\n');
            sb.append("  values:\n");
            for (Map.Entry<String, Color> e : colors.entrySet())
                sb.append("    ").append(q(e.getKey())).append(": ")
                  .append(q(String.format("#%06X", e.getValue().getRGB() & 0xFFFFFF))).append('\n');
        }
        return sb.toString();
    }

    private static String q(String s) {
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    // ------------------------------------------------------------- Helfer

    private static String str(Object o, String def) {
        return o == null ? def : o.toString();
    }

    private static int intVal(Object o, int def) {
        if (o == null) return def;
        try { return Integer.parseInt(o.toString().trim()); } catch (NumberFormatException e) { return def; }
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
