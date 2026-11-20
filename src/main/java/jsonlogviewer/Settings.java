package jsonlogviewer;

import java.awt.Color;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Benutzereinstellungen in {@code ~/.beswinged/settings.yaml}; zuletzt geöffnete Schemas werden zusätzlich
 * als Kopie in {@code ~/.beswinged/schemas/} abgelegt. Das Verzeichnis lässt sich mit
 * {@code -Dbeswinged.home=...} umstellen.
 */
final class Settings {

    static final int MAX_RECENT = 20;

    /** Zuletzt geöffnetes Schema: Originalpfad und Kopie im Cache. */
    record RecentSchema(Path source, Path cache, String title, String lastUsed) {}

    /** Darstellung der Zeilen eines Loglevels (Rang aus {@link LogLevels}). */
    record LevelStyle(boolean bold, boolean italic, Color foreground) {
        static final LevelStyle NONE = new LevelStyle(false, false, null);
        boolean isDefault() { return !bold && !italic && foreground == null; }
    }

    private static Settings instance;

    private final Path dir;
    private final Path file;
    private String lastDir;
    private final List<RecentSchema> recent = new ArrayList<>();
    private final Map<Integer, LevelStyle> levelStyles = new LinkedHashMap<>(); // Rang -> Stil
    /** Geöffnete Datei im Menü Fenster (Reihenfolge = Menü). */
    record OpenEntry(Path path, LineIndex.Mode mode, Path schema) {}

    private final List<OpenEntry> openFiles = new ArrayList<>();
    private Path activeFile;
    private final List<Path> recentFiles = new ArrayList<>(); // neueste zuerst
    private static final int KEEP_RECENT_FILES = 50;          // gemerkt; angezeigt werden 20 vorhandene
    private int rangeSpan = 1;
    private String rangeKind = "around";
    private String loadError;

    /**
     * Cluster für Extras → Connect. Zugangsdaten liegen nur in der kubeconfig; hier steht, welche Datei und welcher
     * Kontext verwendet wird. Leere Felder: Standard des Werkzeugs (PATH, KUBECONFIG bzw. ~/.kube/config, aktueller Kontext).
     */
    record Cluster(String name, String tool, String toolPath, String kubeconfig, String context, String namespace) {
        boolean isOc() { return "oc".equals(tool); }
        @Override public String toString() { return name; }
    }

    /** Letzte Eingaben im Connect-Dialog. */
    record ConnectPrefs(String cluster, String namespace, String kind, String name, String container,
                        String since, int tailLines, boolean previous, boolean follow) {
        static final ConnectPrefs EMPTY = new ConnectPrefs("", "", "Pod", "", "", "", 0, false, true);
    }

    private final List<Cluster> clusters = new ArrayList<>();
    private ConnectPrefs connectPrefs = ConnectPrefs.EMPTY;

    static synchronized Settings get() {
        if (instance == null) {
            String home = System.getProperty("beswinged.home");
            Path dir = home != null ? Path.of(home) : Path.of(System.getProperty("user.home"), ".beswinged");
            instance = new Settings(dir);
        }
        return instance;
    }

    private Settings(Path dir) {
        this.dir = dir;
        this.file = dir.resolve("settings.yaml");
        defaults();
        if (Files.isRegularFile(file)) {
            try {
                load(Files.readString(file, StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException e) {
                loadError = e.getMessage();
            }
        }
    }

    /** Standard: ERROR/FATAL und WARN fett, TRACE dunkelgrau. */
    private void defaults() {
        levelStyles.clear();
        levelStyles.put(LogLevels.rank("TRACE"), new LevelStyle(false, false, new Color(0x606060)));
        levelStyles.put(LogLevels.rank("WARN"), new LevelStyle(true, false, null));
        levelStyles.put(LogLevels.rank("ERROR"), new LevelStyle(true, false, null));
        levelStyles.put(LogLevels.rank("FATAL"), new LevelStyle(true, false, null));
    }

    Path dir() { return dir; }
    Path file() { return file; }
    String loadError() { return loadError; }

    String lastDir() { return lastDir; }
    void setLastDir(String d) {
        if (d != null && !d.equals(lastDir)) { lastDir = d; save(); }
    }

    int rangeSpan() { return rangeSpan; }
    String rangeKind() { return rangeKind; }
    void setRange(int span, String kind) { rangeSpan = span; rangeKind = kind; save(); }

    // ------------------------------------------------------------------ Kubernetes / OpenShift

    List<Cluster> clusters() { return List.copyOf(clusters); }

    void setClusters(List<Cluster> list) {
        clusters.clear();
        clusters.addAll(list);
        save();
    }

    ConnectPrefs connectPrefs() { return connectPrefs; }

    void setConnectPrefs(ConnectPrefs p) {
        if (p.equals(connectPrefs)) return;
        connectPrefs = p;
        save();
    }

    // ------------------------------------------------------------------ Loglevel-Darstellung

    LevelStyle levelStyle(int rank) { return levelStyles.getOrDefault(rank, LevelStyle.NONE); }

    Map<Integer, LevelStyle> levelStyles() { return new LinkedHashMap<>(levelStyles); }

    void setLevelStyles(Map<Integer, LevelStyle> styles) {
        levelStyles.clear();
        for (Map.Entry<Integer, LevelStyle> e : styles.entrySet())
            if (!e.getValue().isDefault()) levelStyles.put(e.getKey(), e.getValue());
        save();
    }

    void resetLevelStyles() {
        defaults();
        save();
    }

    // ------------------------------------------------------------------ offene und zuletzt geöffnete Dateien

    List<OpenEntry> openFiles() { return List.copyOf(openFiles); }
    Path activeFile() { return activeFile; }

    void setOpenFiles(List<OpenEntry> entries, Path active) {
        if (entries.equals(openFiles) && java.util.Objects.equals(active, activeFile)) return;
        openFiles.clear();
        openFiles.addAll(entries);
        activeFile = active;
        save();
    }

    /** Die neuesten zuerst; nur noch vorhandene Dateien, höchstens {@code max}. */
    List<Path> recentFiles(int max) {
        List<Path> l = new ArrayList<>();
        for (Path p : recentFiles) {
            if (l.size() >= max) break;
            if (Files.isRegularFile(p)) l.add(p);
        }
        return l;
    }

    void rememberFile(Path file) {
        Path abs = file.toAbsolutePath().normalize();
        if (!recentFiles.isEmpty() && recentFiles.get(0).equals(abs)) return;
        recentFiles.remove(abs);
        recentFiles.add(0, abs);
        while (recentFiles.size() > KEEP_RECENT_FILES) recentFiles.remove(recentFiles.size() - 1);
        save();
    }

    void clearRecentFiles() {
        recentFiles.clear();
        save();
    }

    // ------------------------------------------------------------------ zuletzt geöffnete Schemas

    List<RecentSchema> recentSchemas() { return List.copyOf(recent); }

    /** Merkt ein geöffnetes/gespeichertes Schema (an erster Stelle) und legt eine Kopie im Cache ab. */
    void rememberSchema(Path source, String title) {
        Path abs = source.toAbsolutePath().normalize();
        Path cache = null;
        try {
            cache = cacheCopy(abs);
        } catch (IOException ignored) {
            // ohne Kopie trotzdem merken
        }
        recent.removeIf(r -> r.source().equals(abs));
        recent.add(0, new RecentSchema(abs, cache, title, Instant.now().toString()));
        while (recent.size() > MAX_RECENT) recent.remove(recent.size() - 1);
        save();
    }

    /** Aktualisiert nur die Cache-Kopie (nach automatischem Speichern). */
    void refreshCache(Path source) {
        Path abs = source.toAbsolutePath().normalize();
        for (RecentSchema r : recent) {
            if (r.source().equals(abs)) {
                try { cacheCopy(abs); } catch (IOException ignored) { }
                return;
            }
        }
    }

    void forgetSchema(Path source) {
        if (recent.removeIf(r -> r.source().equals(source))) save();
    }

    void clearRecent() {
        recent.clear();
        save();
    }

    /** Kopie im Cache: {@code schemas/<name>-<hash>.<endung>}, eindeutig pro Originalpfad. */
    private Path cacheCopy(Path source) throws IOException {
        String name = source.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : ".yaml";
        String hash = String.format("%08x", source.toString().toLowerCase(Locale.ROOT).hashCode());
        Path target = dir.resolve("schemas").resolve(base.replaceAll("[^\\w.-]", "_") + "-" + hash + ext);
        if (source.equals(target)) return target; // aus dem Cache geöffnet
        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    // ------------------------------------------------------------------ Laden / Speichern

    private void load(String yaml) {
        Object root = MiniYaml.parse(yaml);
        if (!(root instanceof Map<?, ?> m)) return;
        if (m.get("lastDir") instanceof String s && !s.isBlank()) lastDir = s;
        if (m.get("recentSchemas") instanceof List<?> l) {
            for (Object o : l) {
                if (!(o instanceof Map<?, ?> e) || !(e.get("source") instanceof String src)) continue;
                Path cache = e.get("cache") instanceof String c && !c.isBlank() ? dir.resolve(c) : null;
                recent.add(new RecentSchema(Path.of(src), cache, str(e.get("title")), str(e.get("lastUsed"))));
                if (recent.size() >= MAX_RECENT) break;
            }
        }
        if (m.get("openFiles") instanceof List<?> l) {
            for (Object o : l) {
                if (!(o instanceof Map<?, ?> e) || !(e.get("path") instanceof String p) || p.isBlank()) continue;
                LineIndex.Mode mode = "seek".equalsIgnoreCase(str(e.get("mode"))) ? LineIndex.Mode.SEEK : LineIndex.Mode.RAM;
                String sch = str(e.get("schema"));
                openFiles.add(new OpenEntry(Path.of(p), mode, sch.isBlank() ? null : Path.of(sch)));
            }
        }
        if (m.get("activeFile") instanceof String s && !s.isBlank()) activeFile = Path.of(s);
        if (m.get("recentFiles") instanceof List<?> l) {
            for (Object o : l) if (o instanceof String s && !s.isBlank() && recentFiles.size() < KEEP_RECENT_FILES) recentFiles.add(Path.of(s));
        }
        if (m.get("levelStyles") instanceof List<?> l) {
            levelStyles.clear();
            for (Object o : l) {
                if (!(o instanceof Map<?, ?> e)) continue;
                int rank = LogLevels.rank(str(e.get("level")));
                if (rank == 0) continue;
                Color fg = null;
                String c = str(e.get("color"));
                if (!c.isBlank()) {
                    try { fg = Color.decode(c.trim()); } catch (NumberFormatException ignored) { }
                }
                LevelStyle st = new LevelStyle(bool(e.get("bold")), bool(e.get("italic")), fg);
                if (!st.isDefault()) levelStyles.put(rank, st);
            }
        }
        if (m.get("clusters") instanceof List<?> l) {
            for (Object o : l) {
                if (!(o instanceof Map<?, ?> e) || str(e.get("name")).isBlank()) continue;
                String tool = "oc".equalsIgnoreCase(str(e.get("tool")).trim()) ? "oc" : "kubectl";
                clusters.add(new Cluster(str(e.get("name")), tool, str(e.get("toolPath")), str(e.get("kubeconfig")),
                        str(e.get("context")), str(e.get("namespace"))));
            }
        }
        if (m.get("connect") instanceof Map<?, ?> c) {
            int tail = 0;
            try { tail = Integer.parseInt(str(c.get("tailLines")).trim()); } catch (NumberFormatException ignored) { }
            String kind = str(c.get("kind"));
            connectPrefs = new ConnectPrefs(str(c.get("cluster")), str(c.get("namespace")), kind.isBlank() ? "Pod" : kind,
                    str(c.get("name")), str(c.get("container")), str(c.get("since")), tail,
                    bool(c.get("previous")), c.get("follow") == null || bool(c.get("follow")));
        }
        if (m.get("timeRange") instanceof Map<?, ?> tr) {
            try { rangeSpan = Integer.parseInt(str(tr.get("span"))); } catch (NumberFormatException ignored) { }
            String k = str(tr.get("kind"));
            if (!k.isBlank()) rangeKind = k;
        }
    }

    String toYaml() {
        StringBuilder sb = new StringBuilder();
        sb.append("# beswinged – Einstellungen (wird vom Programm geschrieben)\n");
        sb.append("# levelStyles: Darstellung ganzer Zeilen je Loglevel (bold, italic, color als #RRGGBB)\n\n");
        if (lastDir != null) sb.append("lastDir: ").append(q(lastDir)).append('\n');
        sb.append("\ntimeRange:\n  span: ").append(rangeSpan).append("\n  kind: ").append(rangeKind).append('\n');
        sb.append(levelStyles.isEmpty() ? "\nlevelStyles: []\n" : "\nlevelStyles:\n");
        for (Map.Entry<Integer, LevelStyle> e : levelStyles.entrySet()) {
            LevelStyle s = e.getValue();
            sb.append("  - level: ").append(LogLevels.GROUPS[e.getKey() - 1][0]).append('\n');
            if (s.bold()) sb.append("    bold: true\n");
            if (s.italic()) sb.append("    italic: true\n");
            if (s.foreground() != null) sb.append("    color: ").append(q(hex(s.foreground()))).append('\n');
        }
        sb.append(openFiles.isEmpty() ? "\nopenFiles: []\n" : "\n# Menü Fenster, in dieser Reihenfolge\nopenFiles:\n");
        for (OpenEntry e : openFiles) {
            sb.append("  - path: ").append(q(e.path().toString())).append('\n');
            sb.append("    mode: ").append(e.mode() == LineIndex.Mode.SEEK ? "seek" : "ram").append('\n');
            if (e.schema() != null) sb.append("    schema: ").append(q(e.schema().toString())).append('\n');
        }
        if (activeFile != null) sb.append("activeFile: ").append(q(activeFile.toString())).append('\n');
        sb.append(recentFiles.isEmpty() ? "\nrecentFiles: []\n" : "\nrecentFiles:\n");
        for (Path p : recentFiles) sb.append("  - ").append(q(p.toString())).append('\n');
        sb.append(clusters.isEmpty() ? "\nclusters: []\n"
                : "\n# Extras → Connect Settings (Zugangsdaten nur in der kubeconfig)\nclusters:\n");
        for (Cluster c : clusters) {
            sb.append("  - name: ").append(q(c.name())).append('\n');
            sb.append("    tool: ").append(c.tool()).append('\n');
            if (!c.toolPath().isBlank()) sb.append("    toolPath: ").append(q(c.toolPath())).append('\n');
            if (!c.kubeconfig().isBlank()) sb.append("    kubeconfig: ").append(q(c.kubeconfig())).append('\n');
            if (!c.context().isBlank()) sb.append("    context: ").append(q(c.context())).append('\n');
            if (!c.namespace().isBlank()) sb.append("    namespace: ").append(q(c.namespace())).append('\n');
        }
        ConnectPrefs cp = connectPrefs;
        sb.append("\nconnect:\n");
        sb.append("  cluster: ").append(q(cp.cluster())).append('\n');
        sb.append("  namespace: ").append(q(cp.namespace())).append('\n');
        sb.append("  kind: ").append(q(cp.kind())).append('\n');
        sb.append("  name: ").append(q(cp.name())).append('\n');
        sb.append("  container: ").append(q(cp.container())).append('\n');
        sb.append("  since: ").append(q(cp.since())).append('\n');
        sb.append("  tailLines: ").append(cp.tailLines()).append('\n');
        sb.append("  previous: ").append(cp.previous()).append('\n');
        sb.append("  follow: ").append(cp.follow()).append('\n');
        sb.append(recent.isEmpty() ? "\nrecentSchemas: []\n" : "\nrecentSchemas:\n");
        for (RecentSchema r : recent) {
            sb.append("  - source: ").append(q(r.source().toString())).append('\n');
            if (r.cache() != null) sb.append("    cache: ").append(q(dir.relativize(r.cache()).toString().replace('\\', '/'))).append('\n');
            if (r.title() != null && !r.title().isBlank()) sb.append("    title: ").append(q(r.title())).append('\n');
            if (r.lastUsed() != null && !r.lastUsed().isBlank()) sb.append("    lastUsed: ").append(q(r.lastUsed())).append('\n');
        }
        return sb.toString();
    }

    void save() {
        try {
            Files.createDirectories(dir);
            Path tmp = file.resolveSibling("settings.yaml.tmp");
            Files.writeString(tmp, toYaml(), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            System.err.println("Einstellungen konnten nicht gespeichert werden: " + e.getMessage());
        }
    }

    static String hex(Color c) { return String.format("#%06X", c.getRGB() & 0xFFFFFF); }

    private static String q(String s) {
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private static String str(Object o) { return o == null ? "" : o.toString(); }

    private static boolean bool(Object o) { return o != null && Boolean.parseBoolean(o.toString().trim()); }
}
