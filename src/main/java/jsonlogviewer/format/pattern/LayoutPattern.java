package jsonlogviewer.format.pattern;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jsonlogviewer.format.AbstractLogFormat;
import jsonlogviewer.format.Fields;

/**
 * Übersetzt ein Layout-Muster von Logback ({@code PatternLayout}) oder Log4j/Log4j2 ({@code PatternLayout}),
 * z.B. {@code %d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n}, in einen regulären Ausdruck und
 * zerlegt damit Log-Zeilen in Felder.
 *
 * <p>Unterstützt: Datum mit Format und Zeitzone (auch ISO8601, ABSOLUTE, DATE, …), Level, Thread, Logger, Klasse,
 * Methode, Datei, Zeile, Nachricht, MDC ({@code %X{key}}), PID, Breiten ({@code %-5level}, {@code %15.15t}),
 * Hüllen wie {@code %highlight(...)}, {@code %clr(...){cyan}} und {@code %style{...}{bold}},
 * Variablen ({@code ${NAME:-default}}). Ausnahmen ({@code %ex}, {@code %wEx}, …) stehen in Folgezeilen und werden
 * nicht im Muster gesucht. Unbekannte Konvertierungswörter werden zu einem Feld mit ihrem Namen.
 */
public final class LayoutPattern {

    private static final Set<String> WRAPPERS = Set.of("highlight", "style", "clr", "replace", "notEmpty", "equals",
            "equalsIgnoreCase", "maxLen", "maxLength", "encode", "black", "red", "green", "yellow", "blue", "magenta", "cyan",
            "white", "gray", "boldRed", "boldGreen", "boldYellow", "boldBlue", "boldMagenta", "boldCyan", "boldWhite", "faint",
            "bold", "underline", "trim");
    private static final Set<String> EXCEPTIONS = Set.of("ex", "exception", "throwable", "xEx", "xException", "xThrowable",
            "rEx", "rException", "rThrowable", "rootException", "wEx", "wex", "nopex", "nopexception", "e", "xwEx", "xwex");
    private static final Map<String, String> NAMED_DATES = Map.ofEntries(
            Map.entry("ISO8601", "yyyy-MM-dd HH:mm:ss,SSS|T"), Map.entry("DEFAULT", "yyyy-MM-dd HH:mm:ss,SSS"),
            Map.entry("ABSOLUTE", "HH:mm:ss,SSS"), Map.entry("DATE", "dd MMM yyyy HH:mm:ss,SSS"),
            Map.entry("COMPACT", "yyyyMMddHHmmssSSS"), Map.entry("ISO8601_BASIC", "yyyyMMdd'T'HHmmss,SSS"),
            Map.entry("ISO8601_OFFSET_DATE_TIME_HH", "yyyy-MM-dd'T'HH:mm:ss,SSSX"),
            Map.entry("ISO8601_OFFSET_DATE_TIME_HHMM", "yyyy-MM-dd'T'HH:mm:ss,SSSXX"),
            Map.entry("ISO8601_OFFSET_DATE_TIME_HHCMM", "yyyy-MM-dd'T'HH:mm:ss,SSSXXX"),
            Map.entry("ABSOLUTE_MICROS", "HH:mm:ss,nnnnnn"), Map.entry("ABSOLUTE_NANOS", "HH:mm:ss,nnnnnnnnn"),
            Map.entry("ABSOLUTE_PERIOD", "HH:mm:ss.SSS"), Map.entry("DEFAULT_PERIOD", "yyyy-MM-dd HH:mm:ss.SSS"),
            Map.entry("ISO8601_PERIOD", "yyyy-MM-dd'T'HH:mm:ss.SSS"), Map.entry("US_MONTH_DAY_YEAR4_TIME", "dd/MM/yyyy hh:mm:ss.SSS a"),
            Map.entry("UNIX", "#epoch"), Map.entry("UNIX_MILLIS", "#epochMillis"));
    /** Spring-Boot-Variablen, die im Log als eigene Felder erscheinen. */
    private static final Map<String, String> SPRING_VARS = Map.of(
            "PID", "%pid", "LOGGED_APPLICATION_NAME", "%esb{application}", "APPLICATION_NAME", "%esb{application}",
            "LOG_CORRELATION_PATTERN", "%correlationId", "LOG_LEVEL_PATTERN", "%5p",
            "LOG_DATEFORMAT_PATTERN", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "LOG_EXCEPTION_CONVERSION_WORD", "%wEx");

    private final String pattern;
    private final Pattern firstLine;
    private final Pattern full;          // null = einzeilig
    private final int extraLines;        // Zeilen nach der ersten, die zum Muster gehören
    private final List<String> fields = new ArrayList<>();
    private final Map<Integer, DateInfo> dates = new HashMap<>(); // Gruppe -> Datumsformat

    private record DateInfo(DateTimeFormatter format, ZoneId zone) { }

    // ------------------------------------------------------------------ Knoten

    private sealed interface Node permits Lit, Conv, Newline { }

    private record Lit(String text) implements Node { }

    private record Newline() implements Node { }

    private record Conv(String word, int min, boolean left, List<String> options, List<Node> children) implements Node { }

    private LayoutPattern(String pattern, Map<String, String> properties) {
        this.pattern = pattern;
        String resolved = resolve(pattern, properties == null ? Map.of() : properties, 0);
        List<Node> nodes = new Parser(resolved).sequence(false);
        // Zeilen nach dem letzten %n enthalten nur Ausnahmen; Zeilen dazwischen gehören zum Muster
        List<List<Node>> lines = new ArrayList<>();
        lines.add(new ArrayList<>());
        for (Node n : flatten(nodes)) {
            if (n instanceof Newline) lines.add(new ArrayList<>());
            else lines.get(lines.size() - 1).add(n);
        }
        while (lines.size() > 1 && isEmpty(lines.get(lines.size() - 1))) lines.remove(lines.size() - 1);
        extraLines = lines.size() - 1;
        StringBuilder first = new StringBuilder();
        int[] group = {0};
        build(lines.get(0), first, group, true);
        if (fields.isEmpty()) throw new IllegalArgumentException("Das Muster enthält keine Felder: " + pattern);
        firstLine = Pattern.compile("^" + first + "\\s*$");
        if (extraLines > 0) {
            StringBuilder all = new StringBuilder(first);
            for (int i = 1; i < lines.size(); i++) {
                all.append("\\s*\\n");
                build(lines.get(i), all, group, true);
            }
            full = Pattern.compile("^" + all + "\\s*$");
        } else {
            full = null;
        }
    }

    /** @throws IllegalArgumentException wenn das Muster nicht verwendbar ist */
    public static LayoutPattern compile(String pattern) {
        return new LayoutPattern(pattern, Map.of());
    }

    /** @param properties Werte für {@code ${NAME}} (z.B. aus {@code <property>} in logback.xml) */
    public static LayoutPattern compile(String pattern, Map<String, String> properties) {
        return new LayoutPattern(pattern, properties);
    }

    public String pattern() { return pattern; }

    /** Feldnamen in Reihenfolge des Musters. */
    public List<String> fields() { return List.copyOf(fields); }

    /** Regulärer Ausdruck für die erste Zeile (zur Kontrolle). */
    public String regex() { return firstLine.pattern(); }

    /** Zeilen nach der ersten, die laut Muster noch zum Eintrag gehören (meist 0). */
    public int extraLines() { return extraLines; }

    public boolean matchesFirstLine(String line) {
        return firstLine.matcher(line).matches();
    }

    /**
     * Zerlegt den Anfang eines Eintrags.
     * @param lines erste Zeile plus ggf. {@link #extraLines()} weitere, mit {@code \n} getrennt
     * @return Felder oder null
     */
    public Map<String, Object> parse(String lines) {
        Matcher m = (full != null ? full : firstLine).matcher(lines);
        if (!m.matches()) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        for (int g = 1; g <= m.groupCount() && g <= fields.size(); g++) {
            String v = m.group(g);
            if (v == null) continue;
            v = v.strip();
            String f = fields.get(g - 1);
            if (v.isEmpty() && !f.equals(Fields.MESSAGE)) continue;
            DateInfo d = dates.get(g);
            if (d != null) v = AbstractLogFormat.isoTimestamp(v, d.format(), d.zone());
            out.putIfAbsent(f, v);
        }
        return out;
    }

    // ------------------------------------------------------------------ Variablen

    /**
     * Ersetzt {@code ${NAME}} und {@code ${NAME:-default}} durch Werte aus {@code properties}, Systemeigenschaften oder
     * Spring-Boot-Standards (PID, Anwendungsname, … werden zu eigenen Feldern).
     */
    public static String resolveVariables(String pattern, Map<String, String> properties) {
        return resolve(pattern, properties == null ? Map.of() : properties, 0);
    }

    private static String resolve(String s, Map<String, String> props, int depth) {
        if (depth > 10 || !s.contains("${")) return s;
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            int start = s.indexOf("${", i);
            if (start < 0) { out.append(s, i, s.length()); break; }
            out.append(s, i, start);
            int d = 0, j = start + 2;
            for (; j < s.length(); j++) {
                char c = s.charAt(j);
                if (c == '{') d++;
                else if (c == '}') { if (d == 0) break; d--; }
            }
            if (j >= s.length()) { out.append(s, start, s.length()); break; }
            String body = s.substring(start + 2, j);
            int sep = body.indexOf(":-");
            String name = sep < 0 ? body : body.substring(0, sep);
            String def = sep < 0 ? null : body.substring(sep + 2);
            if (name.matches("(sys|env|ctx|main|spring|bundle|marker|map|lower|upper):.+")) name = name.substring(name.indexOf(':') + 1);
            String v = props.get(name);
            if (v == null) v = System.getProperty(name);
            if (v == null && SPRING_VARS.containsKey(name)) v = SPRING_VARS.get(name);
            if (v == null) v = def != null ? def : "";
            out.append(resolve(v, props, depth + 1));
            i = j + 1;
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ Parser

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) { this.s = s; }

        /** @param inParens bis zur schließenden Klammer lesen */
        List<Node> sequence(boolean inParens) {
            List<Node> out = new ArrayList<>();
            StringBuilder lit = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i);
                if (inParens && c == ')') { i++; break; }
                if (c == '\\' && i + 1 < s.length()) {
                    char n = s.charAt(i + 1);
                    if (n == 'n') { flush(out, lit); out.add(new Newline()); i += 2; continue; }
                    if (n == 't') { lit.append('\t'); i += 2; continue; }
                    lit.append(n);
                    i += 2;
                    continue;
                }
                if (c != '%') { lit.append(c); i++; continue; }
                i++;
                if (i >= s.length()) { lit.append('%'); break; }
                if (s.charAt(i) == '%') { lit.append('%'); i++; continue; }
                flush(out, lit);
                out.add(conversion());
            }
            flush(out, lit);
            return out;
        }

        private static void flush(List<Node> out, StringBuilder lit) {
            if (lit.length() > 0) { out.add(new Lit(lit.toString())); lit.setLength(0); }
        }

        private Node conversion() {
            boolean left = false;
            int min = 0;
            if (i < s.length() && s.charAt(i) == '-') { left = true; i++; }
            int st = i;
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            if (i > st) min = Integer.parseInt(s.substring(st, i));
            if (i < s.length() && s.charAt(i) == '.') {
                i++;
                if (i < s.length() && s.charAt(i) == '-') i++;
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            }
            String word;
            List<Node> children = List.of();
            if (i < s.length() && s.charAt(i) == '(') { // anonyme Gruppe %-30(...)
                i++;
                word = "";
                children = sequence(true);
            } else {
                st = i;
                while (i < s.length() && Character.isLetter(s.charAt(i))) i++;
                word = s.substring(st, i);
                if (word.equals("n")) return new Newline();
                if (i < s.length() && s.charAt(i) == '(') { // Logback-Hülle %highlight(...)
                    i++;
                    children = sequence(true);
                }
            }
            List<String> options = new ArrayList<>();
            while (i < s.length() && s.charAt(i) == '{') {
                int d = 0, j = i + 1;
                for (; j < s.length(); j++) {
                    char c = s.charAt(j);
                    if (c == '{') d++;
                    else if (c == '}') { if (d == 0) break; d--; }
                }
                options.add(s.substring(i + 1, Math.min(j, s.length())));
                i = Math.min(j + 1, s.length());
            }
            // Log4j2-Hülle %highlight{%-5level}{...}: erste Option ist ein Muster
            if (children.isEmpty() && WRAPPERS.contains(word) && !options.isEmpty() && options.get(0).contains("%")) {
                children = new Parser(options.get(0)).sequence(false);
            }
            return new Conv(word, min, left, options, children);
        }
    }

    /** Hüllen durch ihren Inhalt ersetzen. */
    private static List<Node> flatten(List<Node> nodes) {
        List<Node> out = new ArrayList<>();
        for (Node n : nodes) {
            if (n instanceof Conv c && (c.word().isEmpty() || WRAPPERS.contains(c.word()))) out.addAll(flatten(c.children()));
            else out.add(n);
        }
        return out;
    }

    private static boolean isEmpty(List<Node> line) {
        for (Node n : line) {
            if (n instanceof Lit l && !l.text().isBlank()) return false;
            if (n instanceof Conv c && !EXCEPTIONS.contains(c.word())) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ Regex bauen

    private void build(List<Node> line, StringBuilder re, int[] group, boolean lastLine) {
        int lastField = -1;
        for (int k = 0; k < line.size(); k++) if (line.get(k) instanceof Conv c && !EXCEPTIONS.contains(c.word())) lastField = k;
        for (int k = 0; k < line.size(); k++) {
            Node n = line.get(k);
            if (n instanceof Lit l) {
                re.append(literal(l.text()));
                continue;
            }
            if (!(n instanceof Conv c) || EXCEPTIONS.contains(c.word())) continue;
            boolean last = k == lastField && onlyBlankAfter(line, k);
            String[] spec = spec(c, last);
            if (spec == null) continue;
            String field = spec[0], value = spec[1];
            if (field.equals("#optional-bracket")) { // %esb{name}, %correlationId: „[wert] " oder nichts
                group[0]++;
                fields.add(spec[2]);
                re.append("(?:\\[([^\\]]*)\\] ?)?");
                continue;
            }
            group[0]++;
            fields.add(unique(field));
            String g = "(" + value + ")";
            if (c.min() > 0) g = c.left() ? g + " *" : " *" + g;
            re.append(g);
            if (field.equals(Fields.TIMESTAMP)) dates.put(group[0], dateInfo(c));
        }
    }

    private String unique(String f) {
        if (!fields.contains(f)) return f;
        for (int n = 2; ; n++) if (!fields.contains(f + "_" + n)) return f + "_" + n;
    }

    private static boolean onlyBlankAfter(List<Node> line, int k) {
        for (int j = k + 1; j < line.size(); j++) {
            Node n = line.get(j);
            if (n instanceof Lit l && !l.text().isBlank()) return false;
            if (n instanceof Conv c && !EXCEPTIONS.contains(c.word())) return false;
        }
        return true;
    }

    private static String literal(String text) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == ' ' || c == '\t') {
                while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) i++;
                sb.append("[ \\t]+");
                continue;
            }
            if ("\\.[]{}()*+?^$|".indexOf(c) >= 0) sb.append('\\');
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    /** @return {Feldname, Regex} oder null (keine Ausgabe) */
    private static String[] spec(Conv c, boolean last) {
        String opt = c.options().isEmpty() ? "" : c.options().get(0).trim();
        String lazy = last ? ".*" : ".*?";
        return switch (c.word()) {
            case "d", "date" -> new String[]{Fields.TIMESTAMP, dateRegex(dateFormat(c))};
            case "p", "le", "level" -> new String[]{Fields.LEVEL, "[A-Za-z]+"};
            case "t", "thread", "tn", "threadName" -> new String[]{Fields.THREAD, last ? ".+" : ".+?"};
            case "T", "tid", "threadId" -> new String[]{Fields.THREAD_ID, "\\d+"};
            case "c", "lo", "logger" -> new String[]{Fields.LOGGER, "\\S+"};
            case "C", "class" -> new String[]{Fields.CLASS, "\\S+"};
            case "M", "method" -> new String[]{Fields.METHOD, "\\S+"};
            case "L", "line" -> new String[]{Fields.LINE, "\\d+|\\?"};
            case "F", "file" -> new String[]{Fields.FILE, "\\S+"};
            case "l", "location", "caller" -> new String[]{"caller", "\\S+"};
            case "m", "msg", "message", "s" -> new String[]{Fields.MESSAGE, lazy};
            case "r", "relative" -> new String[]{"relative", "\\d+"};
            case "sn", "sequenceNumber" -> new String[]{"sequence", "\\d+"};
            case "pid", "processId" -> new String[]{Fields.PID, "\\d+"};
            case "N", "nano" -> new String[]{"nano", "\\d+"};
            case "u", "uuid" -> new String[]{"uuid", "[0-9a-fA-F-]+"};
            case "cn", "contextName" -> new String[]{"context", "\\S*"};
            case "marker", "markerSimpleName" -> new String[]{"marker", lazy};
            case "kvp" -> new String[]{"kvp", lazy};
            case "x", "NDC", "ndc" -> new String[]{"ndc", lazy};
            case "X", "mdc", "MDC", "K", "property" -> {
                String key = opt.contains(":-") ? opt.substring(0, opt.indexOf(":-")) : opt;
                yield new String[]{key.isEmpty() ? "mdc" : key, lazy};
            }
            case "esb" -> new String[]{"#optional-bracket", "", opt.isEmpty() ? "application" : opt.toLowerCase(Locale.ROOT)};
            case "correlationId" -> new String[]{"#optional-bracket", "", "correlation_id"};
            case "" -> null;
            default -> new String[]{c.word(), lazy};
        };
    }

    // ------------------------------------------------------------------ Datum

    /** Java-Datumsformat aus den Optionen; Präfix „#" für Epoch. */
    private static String dateFormat(Conv c) {
        String opt = c.options().isEmpty() ? "ISO8601" : c.options().get(0).trim();
        if (opt.isEmpty()) opt = "ISO8601";
        int comma = opt.lastIndexOf(',');
        if (comma > 0 && zone(opt.substring(comma + 1).trim()) != null) opt = opt.substring(0, comma).trim();
        if (opt.length() >= 2 && opt.startsWith("\"") && opt.endsWith("\"")) opt = opt.substring(1, opt.length() - 1);
        return NAMED_DATES.getOrDefault(opt, opt);
    }

    private static DateInfo dateInfo(Conv c) {
        String opt = c.options().isEmpty() ? "" : c.options().get(0).trim();
        ZoneId z = null;
        int comma = opt.lastIndexOf(',');
        if (comma > 0) z = zone(opt.substring(comma + 1).trim());
        if (c.options().size() > 1) z = zone(c.options().get(1).trim());
        String f = dateFormat(c);
        DateTimeFormatter fmt = null;
        if (!f.startsWith("#")) {
            String javaFmt = f.endsWith("|T") ? f.substring(0, f.length() - 2) : f;
            try {
                fmt = new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(javaFmt.replace("nnnnnnnnn", "SSSSSSSSS").replace("nnnnnn", "SSSSSS"))
                        .toFormatter(Locale.ENGLISH);
            } catch (IllegalArgumentException ex) {
                fmt = null; // lenient
            }
            if (f.endsWith("|T")) fmt = null; // ' ' oder 'T' erlaubt: lenient
        }
        return new DateInfo(fmt, z);
    }

    private static ZoneId zone(String s) {
        if (s.isEmpty()) return null;
        try {
            return ZoneId.of(s);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** Regex für ein SimpleDateFormat-/DateTimeFormatter-Muster. */
    static String dateRegex(String f) {
        if (f.equals("#epoch")) return "\\d{10}(?:\\.\\d+)?";
        if (f.equals("#epochMillis")) return "\\d{13}";
        boolean anySep = f.endsWith("|T");
        if (anySep) f = f.substring(0, f.length() - 2);
        StringBuilder re = new StringBuilder();
        int i = 0;
        while (i < f.length()) {
            char c = f.charAt(i);
            if (c == '\'') {
                int j = f.indexOf('\'', i + 1);
                if (j < 0) j = f.length();
                re.append(Pattern.quote(f.substring(i + 1, j).isEmpty() ? "'" : f.substring(i + 1, j)));
                i = j + 1;
                continue;
            }
            if (!Character.isLetter(c)) {
                if (anySep && c == ' ') re.append("[ T]");
                else if (c == ',' || c == '.') re.append("[,.]");
                else re.append(Pattern.quote(String.valueOf(c)));
                i++;
                continue;
            }
            int j = i;
            while (j < f.length() && f.charAt(j) == c) j++;
            int n = j - i;
            re.append(switch (c) {
                case 'y', 'u' -> n == 2 ? "\\d{2}" : "\\d{4}";
                case 'M', 'L' -> n >= 4 ? "[A-Za-z]+\\.?" : n == 3 ? "[A-Za-z]{3,4}\\.?" : "\\d{1,2}";
                case 'd', 'H', 'h', 'k', 'K', 'm', 's' -> n >= 2 ? "\\d{2}" : "\\d{1,2}";
                case 'S', 'n' -> "\\d{" + n + "}";
                case 'D' -> "\\d{1,3}";
                case 'E' -> "[A-Za-z]+\\.?";
                case 'a' -> "[AaPp]\\.?[Mm]\\.?";
                case 'X', 'x' -> "(?:Z|[+-]\\d{2}(?::?\\d{2})?)";
                case 'Z' -> "(?:Z|[+-]\\d{2}:?\\d{2})";
                case 'z', 'V' -> "[A-Za-z][\\w/+-]*";
                default -> "\\S+?";
            });
            i = j;
        }
        return re.toString();
    }

    @Override public String toString() { return pattern; }
}
