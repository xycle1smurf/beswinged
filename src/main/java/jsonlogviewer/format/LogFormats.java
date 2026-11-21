package jsonlogviewer.format;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.stream.Stream;
import jsonlogviewer.format.jul.JulFormat;
import jsonlogviewer.format.klog.KlogFormat;
import jsonlogviewer.format.log4j.Log4jFormat;
import jsonlogviewer.format.logback.LogbackFormat;
import jsonlogviewer.format.logfmt.LogfmtFormat;
import jsonlogviewer.format.springboot.SpringBootFormat;
import jsonlogviewer.format.text.PlainTextFormat;

/**
 * Alle bekannten Logformate und die automatische Erkennung. Quellen, in dieser Reihenfolge:
 * mitgelieferte Formate, eigene Muster (Schema → Import), per {@link ServiceLoader} gefundene Klassen aus dem
 * Klassenpfad und aus den JARs in {@code ~/.beswinged/formats/}.
 */
public final class LogFormats {

    /** Kennung für JSON Lines (kein Konverter). */
    public static final String JSON = "json";
    /** Mindest-Trefferquote für die automatische Erkennung. */
    public static final double THRESHOLD = 0.6;
    private static final int SAMPLE_BYTES = 256 * 1024;
    private static final int SAMPLE_LINES = 300;

    /** Ergebnis der Erkennung; {@code format == null} heißt JSON. */
    public record Detection(LogFormat format, double score, boolean empty) {
        public String id() { return format == null ? JSON : format.id(); }
        public String name() { return format == null ? "JSON" : format.name(); }
    }

    private static Path pluginDir;
    private static List<LogFormat> discovered;           // Klassenpfad + Plugin-JARs
    private static final List<String> errors = new ArrayList<>();
    private static volatile List<LogFormat> userFormats = List.of();

    private LogFormats() { }

    /** Ordner für Plugin-JARs; vor dem ersten Zugriff setzen. */
    public static synchronized void setPluginDir(Path dir) {
        pluginDir = dir;
        discovered = null;
    }

    /** Eigene Formate (z.B. aus logback.xml importierte Muster). */
    public static void setUserFormats(List<LogFormat> formats) {
        userFormats = List.copyOf(formats);
    }

    public static List<LogFormat> userFormats() { return userFormats; }

    /** Mitgelieferte Formate, spezielle zuerst; {@link PlainTextFormat} ist der Rückfall und steht am Ende. */
    public static List<LogFormat> builtIns() {
        return List.of(new SpringBootFormat(), new LogbackFormat(), new Log4jFormat(), new JulFormat(),
                new KlogFormat(), new LogfmtFormat());
    }

    /** Alle Formate ohne JSON und ohne den Text-Rückfall. */
    public static synchronized List<LogFormat> all() {
        Map<String, LogFormat> m = new LinkedHashMap<>();
        for (LogFormat f : userFormats) m.putIfAbsent(f.id(), f);
        for (LogFormat f : builtIns()) m.putIfAbsent(f.id(), f);
        for (LogFormat f : discovered()) m.putIfAbsent(f.id(), f);
        return new ArrayList<>(m.values());
    }

    /** Text-Rückfall. */
    public static LogFormat fallback() { return new PlainTextFormat(); }

    /** Format nach Kennung; {@link #JSON} und Unbekanntes ergeben null. */
    public static LogFormat byId(String id) {
        if (id == null || id.isBlank() || id.equals(JSON)) return null;
        if (id.equals("text")) return fallback();
        for (LogFormat f : all()) if (f.id().equals(id)) return f;
        return null;
    }

    /** Fehler beim Laden von Plugins (für die Anzeige). */
    public static synchronized List<String> errors() {
        discovered();
        return List.copyOf(errors);
    }

    private static synchronized List<LogFormat> discovered() {
        if (discovered != null) return discovered;
        List<LogFormat> l = new ArrayList<>();
        errors.clear();
        ClassLoader app = LogFormats.class.getClassLoader();
        load(ServiceLoader.load(LogFormat.class, app), l, "Klassenpfad");
        List<URL> jars = new ArrayList<>();
        if (pluginDir != null && Files.isDirectory(pluginDir)) {
            try (Stream<Path> s = Files.list(pluginDir)) {
                s.filter(p -> p.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jar")).sorted().forEach(p -> {
                    try { jars.add(p.toUri().toURL()); } catch (IOException ex) { errors.add(p + ": " + ex.getMessage()); }
                });
            } catch (IOException ex) {
                errors.add(pluginDir + ": " + ex.getMessage());
            }
        }
        if (!jars.isEmpty()) {
            @SuppressWarnings("resource") // bleibt für die Laufzeit des Programms offen
            URLClassLoader cl = new URLClassLoader(jars.toArray(new URL[0]), app);
            ServiceLoader<LogFormat> sl = ServiceLoader.load(LogFormat.class, cl);
            List<LogFormat> plugins = new ArrayList<>();
            load(sl, plugins, pluginDir.toString());
            for (LogFormat f : plugins) if (l.stream().noneMatch(x -> x.getClass() == f.getClass())) l.add(f);
        }
        discovered = l;
        return l;
    }

    private static void load(ServiceLoader<LogFormat> sl, List<LogFormat> into, String where) {
        Iterator<LogFormat> it = sl.iterator();
        while (true) {
            try {
                if (!it.hasNext()) break;
                LogFormat f = it.next();
                if (f.id() == null || f.id().isBlank() || f.name() == null) {
                    errors.add(where + ": " + f.getClass().getName() + " hat keine Kennung oder keinen Namen");
                    continue;
                }
                into.add(f);
            } catch (ServiceConfigurationError | RuntimeException | LinkageError ex) {
                errors.add(where + ": " + ex.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ Erkennung

    /** Erste nicht-leere Zeilen der Datei (höchstens 256 KB). */
    public static List<String> sample(Path file) throws IOException {
        byte[] b;
        try (InputStream in = Files.newInputStream(file)) {
            b = in.readNBytes(SAMPLE_BYTES);
        }
        String s = new String(b, StandardCharsets.UTF_8);
        if (s.startsWith("﻿")) s = s.substring(1);
        List<String> lines = new ArrayList<>();
        String[] parts = s.split("\\r?\\n", -1);
        int n = b.length == SAMPLE_BYTES ? parts.length - 1 : parts.length; // letzte Zeile evtl. abgeschnitten
        for (int i = 0; i < n && lines.size() < SAMPLE_LINES; i++) if (!parts[i].isBlank()) lines.add(parts[i]);
        return lines;
    }

    /** Erkennt das Format einer Datei. Leere Dateien gelten vorerst als JSON. */
    public static Detection detect(Path file) throws IOException {
        return detect(sample(file));
    }

    public static Detection detect(List<String> sample) {
        if (sample.isEmpty()) return new Detection(null, 0, true);
        int json = 0;
        for (String l : sample) if (l.stripLeading().startsWith("{")) json++;
        if (json * 2 >= sample.size()) return new Detection(null, (double) json / sample.size(), false);
        LogFormat best = null;
        double score = 0;
        for (LogFormat f : all()) {
            double s;
            try {
                s = f.detect(sample);
            } catch (RuntimeException ex) {
                continue; // fehlerhaftes Plugin
            }
            if (s > score + 1e-9) { score = s; best = f; }
        }
        if (best == null || score < THRESHOLD) return new Detection(fallback().forSample(sample), score, false);
        return new Detection(best.forSample(sample), score, false);
    }

    /** Format für eine Datei: gewählte Kennung oder automatisch ({@code null}/leer). */
    public static Detection resolve(String id, List<String> sample) {
        if (id == null || id.isBlank()) return detect(sample);
        if (id.equals(JSON)) return new Detection(null, 1, sample.isEmpty());
        LogFormat f = byId(id);
        if (f == null) return detect(sample);
        return new Detection(f.forSample(sample), f.detect(sample), sample.isEmpty());
    }
}
