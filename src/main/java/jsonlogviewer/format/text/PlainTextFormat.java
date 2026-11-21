package jsonlogviewer.format.text;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jsonlogviewer.format.AbstractLogFormat;
import jsonlogviewer.format.Fields;

/**
 * Rückfall für unbekannte Textformate: Zeitstempel am Zeilenanfang (ISO, nur Uhrzeit oder syslog-Stil) und ein
 * Loglevel-Wort werden erkannt, der Rest ist die Nachricht. Beginnen die meisten Zeilen mit einem Zeitstempel, gelten
 * Zeilen ohne Zeitstempel als Fortsetzung (Stacktrace); sonst ist jede Zeile ein Eintrag.
 */
public class PlainTextFormat extends AbstractLogFormat {

    private static final Pattern TS = Pattern.compile("^\\[?("
            + "\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d{1,9})?(?:Z|[+-]\\d{2}:?\\d{2})?"
            + "|\\d{4}/\\d{2}/\\d{2} \\d{2}:\\d{2}:\\d{2}(?:[.,]\\d{1,9})?"
            + "|[A-Z][a-z]{2} [ \\d]\\d \\d{2}:\\d{2}:\\d{2}"
            + "|\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d{1,9})?"
            + ")\\]?[ \\t]*");
    private static final Pattern LEVEL = Pattern.compile(
            "^[\\[(<]?(TRACE|DEBUG|INFO|NOTICE|WARN|WARNING|ERROR|ERR|SEVERE|CRITICAL|CRIT|FATAL|ALERT|EMERG|FINE|FINER|FINEST|CONFIG)[\\])>:]?(?=\\s|$)[ \\t]*",
            Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter SYSLOG = new DateTimeFormatterBuilder().appendPattern("MMM ppd HH:mm:ss")
            .parseDefaulting(ChronoField.YEAR, LocalDate.now().getYear()).toFormatter(Locale.ENGLISH);

    private final boolean timestamped;

    public PlainTextFormat() {
        this(false);
    }

    private PlainTextFormat(boolean timestamped) {
        super("text", "Text (allgemein)");
        this.timestamped = timestamped;
    }

    @Override public String description() {
        return "Zeitstempel und Loglevel werden erkannt, der Rest ist die Nachricht"
                + (timestamped ? "; Zeilen ohne Zeitstempel gehören zum vorigen Eintrag" : "");
    }

    /** Wird nie automatisch gewählt, nur als Rückfall. */
    @Override public double detect(List<String> sample) {
        return 0;
    }

    @Override public PlainTextFormat forSample(List<String> sample) {
        int ts = 0, total = 0;
        for (String l : sample) {
            if (l.isBlank()) continue;
            total++;
            if (TS.matcher(l).lookingAt()) ts++;
        }
        return new PlainTextFormat(total > 0 && ts * 2 >= total);
    }

    @Override public boolean isEntryStart(String line) {
        return !timestamped || TS.matcher(line).lookingAt();
    }

    @Override protected Map<String, Object> parseLine(String line) {
        Map<String, Object> m = new LinkedHashMap<>();
        String rest = line;
        Matcher t = TS.matcher(rest);
        if (t.lookingAt()) {
            String raw = t.group(1);
            String iso = raw.length() == 15 && Character.isLetter(raw.charAt(0)) ? isoTimestamp(raw, SYSLOG, null)
                    : isoTimestamp(raw.replace('/', '-'), null, null);
            m.put(Fields.TIMESTAMP, iso);
            rest = rest.substring(t.end());
        } else if (timestamped) {
            return null;
        }
        // Level am Anfang oder als einzelnes Wort in den ersten 60 Zeichen
        Matcher l = LEVEL.matcher(rest);
        if (l.lookingAt()) {
            m.put(Fields.LEVEL, l.group(1).toUpperCase(Locale.ROOT));
            rest = rest.substring(l.end());
        } else {
            Matcher w = Pattern.compile("(?<=^|[\\s\\[(.])(TRACE|DEBUG|INFO|WARN|WARNING|ERROR|SEVERE|FATAL)(?=[\\s\\]):]|$)").matcher(rest);
            if (w.find() && w.start() < 60) m.put(Fields.LEVEL, w.group(1));
        }
        m.put(Fields.MESSAGE, rest);
        return m;
    }

    @Override public Map<String, Object> parse(String entry) {
        Map<String, Object> m = super.parse(entry);
        if (m == null) { // ohne Zeitstempel (Rückfall): ganze Zeile als Nachricht
            m = new LinkedHashMap<>();
            m.put(Fields.MESSAGE, entry);
        }
        return m;
    }
}
