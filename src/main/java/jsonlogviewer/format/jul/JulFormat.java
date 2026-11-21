package jsonlogviewer.format.jul;

import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jsonlogviewer.format.AbstractLogFormat;
import jsonlogviewer.format.Fields;

/**
 * java.util.logging mit dem Standard-{@code SimpleFormatter}: zwei Zeilen je Eintrag,
 * <pre>
 * Nov 21, 2026 10:15:30 AM com.example.Shop placeOrder
 * WARNING: Lager fast leer
 * </pre>
 * Englische und deutsche Ausgabe (WARNUNG, SCHWERWIEGEND, …).
 */
public class JulFormat extends AbstractLogFormat {

    private static final Pattern HEAD = Pattern.compile(
            "^([A-Za-zäöü]{3,5}\\.? \\d{1,2}, \\d{4},? \\d{1,2}:\\d{2}:\\d{2}(?:\\s?[APap]\\.?[Mm]\\.?|\\s?(?:VORM|NACHM)\\.)?) (\\S+)(?: (\\S+))?$");
    private static final Pattern LEVEL = Pattern.compile("^([A-ZÄÖÜ]{3,15}): (.*)$");
    private static final Map<String, String> GERMAN = Map.of("SCHWERWIEGEND", "SEVERE", "WARNUNG", "WARNING",
            "INFORMATION", "INFO", "KONFIGURATION", "CONFIG", "FEIN", "FINE", "FEINER", "FINER", "AM FEINSTEN", "FINEST");
    private static final DateTimeFormatter EN = new DateTimeFormatterBuilder().parseCaseInsensitive()
            .appendPattern("MMM d, yyyy h:mm:ss a").toFormatter(Locale.ENGLISH);

    public JulFormat() {
        super("jul", "java.util.logging (SimpleFormatter)");
    }

    @Override public String description() { return "Nov 21, 2026 10:15:30 AM com.example.Klasse methode\nINFO: Nachricht"; }

    @Override public boolean isEntryStart(String line) {
        return HEAD.matcher(line).matches();
    }

    @Override public double detect(List<String> sample) {
        int heads = 0, levels = 0, relevant = 0;
        boolean afterHead = false;
        for (String line : sample) {
            if (line.isBlank()) continue;
            if (HEAD.matcher(line).matches()) { heads++; relevant++; afterHead = true; continue; }
            if (afterHead && LEVEL.matcher(line).matches()) { levels++; afterHead = false; continue; }
            afterHead = false;
            if (!isContinuation(line)) relevant++;
        }
        return heads == 0 || levels == 0 ? 0 : (double) heads / relevant;
    }

    @Override protected Map<String, Object> parseLine(String line) {
        Matcher h = HEAD.matcher(line);
        if (!h.matches()) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        String ts = h.group(1).replace("VORM.", "AM").replace("NACHM.", "PM");
        m.put(Fields.TIMESTAMP, isoTimestamp(ts, EN, null));
        m.put(Fields.LEVEL, "");
        m.put(Fields.LOGGER, h.group(2));
        if (h.group(3) != null) m.put(Fields.METHOD, h.group(3));
        m.put(Fields.MESSAGE, "");
        return m;
    }

    @Override public Map<String, Object> parse(String entry) {
        String[] lines = entry.replace("\r", "").split("\n", 3);
        Map<String, Object> m = parseLine(lines[0]);
        if (m == null) return null;
        int rest = 1;
        if (lines.length > 1) {
            Matcher l = LEVEL.matcher(lines[1]);
            if (l.matches()) {
                m.put(Fields.LEVEL, GERMAN.getOrDefault(l.group(1), l.group(1)));
                m.put(Fields.MESSAGE, l.group(2));
                rest = 2;
            }
        }
        if (((String) m.get(Fields.LEVEL)).isEmpty()) m.remove(Fields.LEVEL);
        if (lines.length > rest) addContinuation(m, String.join("\n", java.util.Arrays.copyOfRange(lines, rest, lines.length)));
        return m;
    }
}
