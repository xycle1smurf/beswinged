package jsonlogviewer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Gespeicherter Filter in {@code ~/.beswinged/filters/<name>.yaml}: Textfilter, Auswahl- und Level-Filter
 * (Spalten über ihren Namen) und Zeitraum.
 */
record SavedFilter(String name, String text, String scope, boolean regex, boolean invert,
                   Map<String, List<String>> choices, Map<String, String> minLevels,
                   String from, String to, int linesBefore, int linesAfter) {

    static Path dir() { return Settings.get().dir().resolve("filters"); }

    /** Alle gespeicherten Filter, nach Namen sortiert (Dateiname ohne .yaml). */
    static List<Path> list() {
        Path d = dir();
        if (!Files.isDirectory(d)) return List.of();
        try (Stream<Path> s = Files.list(d)) {
            return s.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".yaml"))
                    .sorted((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.getFileName().toString(), b.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    static String displayName(Path p) {
        String n = p.getFileName().toString();
        return n.substring(0, n.length() - ".yaml".length());
    }

    /** Dateiname aus dem Anzeigenamen (unerlaubte Zeichen ersetzt). */
    static Path fileFor(String name) {
        return dir().resolve(name.trim().replaceAll("[\\\\/:*?\"<>|]", "_") + ".yaml");
    }

    void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, toYaml(), StandardCharsets.UTF_8);
    }

    String toYaml() {
        StringBuilder sb = new StringBuilder("# beswinged – gespeicherter Filter\n");
        sb.append("name: ").append(q(name)).append('\n');
        sb.append("text: ").append(q(text)).append('\n');
        sb.append("scope: ").append(q(scope)).append("   # Spaltenname, leer = ganze Zeile\n");
        sb.append("regex: ").append(regex).append('\n');
        sb.append("invert: ").append(invert).append('\n');
        sb.append(choices.isEmpty() ? "choices: []\n" : "choices:\n");
        for (Map.Entry<String, List<String>> e : choices.entrySet()) {
            sb.append("  - column: ").append(q(e.getKey())).append('\n');
            sb.append("    values:\n");
            for (String v : e.getValue()) sb.append("      - ").append(q(v)).append('\n');
        }
        sb.append(minLevels.isEmpty() ? "levels: []\n" : "levels:\n");
        for (Map.Entry<String, String> e : minLevels.entrySet())
            sb.append("  - column: ").append(q(e.getKey())).append("\n    min: ").append(e.getValue()).append('\n');
        sb.append("timeRange:\n");
        sb.append("  from: ").append(q(from)).append('\n');
        sb.append("  to: ").append(q(to)).append('\n');
        sb.append("  before: ").append(linesBefore).append('\n');
        sb.append("  after: ").append(linesAfter).append('\n');
        return sb.toString();
    }

    static SavedFilter load(Path file) throws IOException {
        Object root = MiniYaml.parse(Files.readString(file, StandardCharsets.UTF_8));
        if (!(root instanceof Map<?, ?> m)) throw new IOException("Kein gültiger Filter: " + file);
        Map<String, List<String>> choices = new LinkedHashMap<>();
        if (m.get("choices") instanceof List<?> l) {
            for (Object o : l) {
                if (!(o instanceof Map<?, ?> e)) continue;
                List<String> vals = new ArrayList<>();
                if (e.get("values") instanceof List<?> vl) for (Object v : vl) vals.add(v == null ? "" : v.toString());
                choices.put(str(e.get("column")), vals);
            }
        }
        Map<String, String> levels = new LinkedHashMap<>();
        if (m.get("levels") instanceof List<?> l) {
            for (Object o : l) if (o instanceof Map<?, ?> e) levels.put(str(e.get("column")), str(e.get("min")));
        }
        String from = "", to = "";
        int before = 0, after = 0;
        if (m.get("timeRange") instanceof Map<?, ?> t) {
            from = str(t.get("from"));
            to = str(t.get("to"));
            before = num(t.get("before"));
            after = num(t.get("after"));
        }
        String name = str(m.get("name"));
        return new SavedFilter(name.isBlank() ? displayName(file) : name, str(m.get("text")), str(m.get("scope")),
                bool(m.get("regex")), bool(m.get("invert")), choices, levels, from, to, before, after);
    }

    private static String q(String s) {
        return '"' + (s == null ? "" : s).replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t") + '"';
    }
    private static String str(Object o) { return o == null ? "" : o.toString(); }
    private static boolean bool(Object o) { return o != null && Boolean.parseBoolean(o.toString().trim()); }
    private static int num(Object o) {
        try { return o == null ? 0 : Math.max(0, Integer.parseInt(o.toString().trim())); } catch (NumberFormatException e) { return 0; }
    }
}
