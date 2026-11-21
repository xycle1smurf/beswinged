package jsonlogviewer.format;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Basisklasse für Konverter: Unterklassen zerlegen nur die <b>erste Zeile</b> eines Eintrags ({@link #parseLine});
 * Folgezeilen hängt {@link #parse} an – als {@link Fields#STACK_TRACE}, wenn sie wie ein Stacktrace aussehen, sonst an
 * die Nachricht. {@link #detect} zählt, wie viele Probezeilen {@link #isEntryStart} erfüllen.
 */
public abstract class AbstractLogFormat implements LogFormat {

    private static final Pattern EXCEPTION_LINE = Pattern.compile("^([a-zA-Z_$][\\w$]*\\.)+[A-Z][\\w$]*(Exception|Error|Throwable)(:.*)?$");
    private static final Pattern MORE = Pattern.compile("^\\s*\\.\\.\\. \\d+ (more|common frames omitted)\\s*$");

    /** Ausgabeformat für Zeitstempel. */
    public static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSXXX");

    private final String id;
    private final String name;

    protected AbstractLogFormat(String id, String name) {
        this.id = id;
        this.name = name;
    }

    @Override public String id() { return id; }

    @Override public String name() { return name; }

    /**
     * Felder der ersten Zeile eines Eintrags.
     * @return Felder (z.B. {@link java.util.LinkedHashMap}), oder null wenn die Zeile kein Eintragsanfang ist
     */
    protected abstract Map<String, Object> parseLine(String line);

    /** Standard: die Zeile lässt sich zerlegen. Unterklassen können das billiger prüfen. */
    @Override public boolean isEntryStart(String line) {
        return parseLine(line) != null;
    }

    @Override public Map<String, Object> parse(String entry) {
        int nl = entry.indexOf('\n');
        Map<String, Object> m = parseLine(nl < 0 ? entry : stripCr(entry.substring(0, nl)));
        if (m == null || nl < 0) return m;
        addContinuation(m, entry.substring(nl + 1));
        return m;
    }

    /** Folgezeilen anhängen: Stacktrace in eigenes Feld, sonst an die Nachricht (mehrzeilige Meldung). */
    protected void addContinuation(Map<String, Object> m, String rest) {
        rest = rest.replace("\r", "");
        if (rest.isBlank()) return;
        if (looksLikeStackTrace(rest)) {
            Object old = m.get(Fields.STACK_TRACE);
            m.put(Fields.STACK_TRACE, old == null ? rest : old + "\n" + rest);
        } else {
            Object msg = m.get(Fields.MESSAGE);
            m.put(Fields.MESSAGE, msg == null ? rest : msg + "\n" + rest);
        }
    }

    /**
     * Standard: Anteil der Zeilen, die einen Eintrag beginnen, an allen Zeilen außer offensichtlichen Folgezeilen
     * (eingerückt, „Caused by:", „... 5 more", Exception-Zeilen).
     */
    @Override public double detect(List<String> sample) {
        int starts = 0, relevant = 0;
        for (String line : sample) {
            if (line.isBlank()) continue;
            boolean start;
            try {
                start = isEntryStart(line);
            } catch (RuntimeException ex) {
                start = false;
            }
            if (start) { starts++; relevant++; }
            else if (!isContinuation(line)) relevant++;
        }
        return starts == 0 ? 0 : (double) starts / relevant;
    }

    // ------------------------------------------------------------------ Hilfen für Unterklassen

    /** Typische Folgezeile eines Stacktraces oder einer eingerückten Fortsetzung. */
    public static boolean isContinuation(String line) {
        if (line.isEmpty()) return true;
        char c = line.charAt(0);
        if (c == ' ' || c == '\t') return true;
        return line.startsWith("Caused by:") || line.startsWith("Suppressed:") || MORE.matcher(line).matches()
                || EXCEPTION_LINE.matcher(line.length() > 400 ? line.substring(0, 400) : line).matches();
    }

    /** Sieht der Text wie ein Java-Stacktrace aus? */
    public static boolean looksLikeStackTrace(String text) {
        return text.contains("\tat ") || text.contains("\n    at ") || text.startsWith("    at ") || text.contains("Caused by:")
                || EXCEPTION_LINE.matcher(firstLine(text)).matches();
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        String f = nl < 0 ? s : s.substring(0, nl);
        return f.length() > 400 ? f.substring(0, 400) : f;
    }

    private static String stripCr(String s) {
        return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
    }

    /**
     * Zeitstempel als ISO-8601 mit Offset ({@code 2026-11-21T10:15:30.123+01:00}), damit Zeitzonen und Zeitraum-Filter
     * funktionieren. Ohne Zone im Text gilt {@code zone}. Ohne Datum (nur Uhrzeit) bleibt der Text unverändert.
     * @param format Format des Textes, oder null für gängige ISO-Varianten ({@code 2026-11-21 10:15:30,123})
     */
    public static String isoTimestamp(String text, DateTimeFormatter format, ZoneId zone) {
        if (text == null) return null;
        String t = text.trim();
        if (t.isEmpty()) return t;
        ZoneId z = zone == null ? ZoneId.systemDefault() : zone;
        if (format != null) {
            try {
                TemporalAccessor ta = format.parse(t);
                return iso(ta, z, t);
            } catch (DateTimeParseException | ArithmeticException ignored) {
                // unten lenient versuchen
            }
        }
        if (t.matches("\\d{10}(\\.\\d+)?")) return Instant.ofEpochMilli((long) (Double.parseDouble(t) * 1000)).atZone(z).toOffsetDateTime().format(ISO);
        if (t.matches("\\d{13}")) return Instant.ofEpochMilli(Long.parseLong(t)).atZone(z).toOffsetDateTime().format(ISO);
        if (t.length() >= 19 && Character.isDigit(t.charAt(0)) && t.charAt(4) == '-' && t.charAt(7) == '-') {
            String n = t.substring(0, 10) + 'T' + t.substring(11).replace(',', '.');
            n = n.replaceAll("([+-]\\d{2})(\\d{2})$", "$1:$2").replace(" ", "");
            try {
                return OffsetDateTime.parse(n).format(ISO);
            } catch (DateTimeParseException ignored) { }
            try {
                return LocalDateTime.parse(n).atZone(z).toOffsetDateTime().format(ISO);
            } catch (DateTimeParseException ignored) { }
        }
        return t;
    }

    private static String iso(TemporalAccessor ta, ZoneId z, String raw) {
        if (!ta.isSupported(ChronoField.YEAR) && !ta.isSupported(ChronoField.YEAR_OF_ERA)) {
            if (ta.isSupported(ChronoField.INSTANT_SECONDS)) return Instant.from(ta).atZone(z).toOffsetDateTime().format(ISO);
            return raw;
        }
        if (!ta.isSupported(ChronoField.HOUR_OF_DAY) && !ta.isSupported(ChronoField.HOUR_OF_AMPM)) return raw;
        if (ta.isSupported(ChronoField.OFFSET_SECONDS)) return OffsetDateTime.from(ta).format(ISO);
        try {
            return ZonedDateTime.from(ta).toOffsetDateTime().format(ISO);
        } catch (RuntimeException ignored) { }
        return LocalDateTime.from(ta).atZone(z).toOffsetDateTime().format(ISO);
    }

    @Override public String toString() { return name(); }
}
