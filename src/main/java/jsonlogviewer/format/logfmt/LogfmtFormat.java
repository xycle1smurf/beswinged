package jsonlogviewer.format.logfmt;

import java.util.LinkedHashMap;
import java.util.Map;
import jsonlogviewer.format.AbstractLogFormat;
import jsonlogviewer.format.Fields;

/**
 * logfmt (Go {@code log/slog} TextHandler, logrus, Prometheus, Grafana, …):
 * {@code time=2026-11-21T10:15:30Z level=INFO msg="Bestellung angelegt" order=42}. Jeder Schlüssel wird eine Spalte;
 * {@code msg} heißt {@code message}, {@code time}/{@code ts} heißt {@code @timestamp}.
 */
public class LogfmtFormat extends AbstractLogFormat {

    public LogfmtFormat() {
        super("logfmt", "logfmt (key=value)");
    }

    @Override public String description() { return "time=… level=INFO msg=\"…\" key=value"; }

    @Override public boolean isEntryStart(String line) {
        Map<String, Object> m = pairs(line);
        return m != null && m.size() >= 2;
    }

    @Override protected Map<String, Object> parseLine(String line) {
        Map<String, Object> raw = pairs(line);
        if (raw == null || raw.size() < 2) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();
            switch (k) {
                case "time", "ts", "t", "timestamp" -> m.putIfAbsent(Fields.TIMESTAMP, isoTimestamp(v.toString(), null, null));
                case "msg" -> m.putIfAbsent(Fields.MESSAGE, v);
                case "level", "lvl" -> m.putIfAbsent(Fields.LEVEL, v.toString().toUpperCase(java.util.Locale.ROOT));
                default -> m.putIfAbsent(k, v);
            }
        }
        return m;
    }

    /** Zerlegt {@code key=value key2="mit Leerzeichen"}; null wenn die Zeile nicht so aussieht. */
    static Map<String, Object> pairs(String line) {
        Map<String, Object> m = new LinkedHashMap<>();
        int i = 0, n = line.length();
        while (i < n) {
            while (i < n && line.charAt(i) == ' ') i++;
            if (i >= n) break;
            int ks = i;
            while (i < n && line.charAt(i) != '=' && line.charAt(i) != ' ' && line.charAt(i) != '"') i++;
            if (i >= n || line.charAt(i) != '=' || i == ks) return m.isEmpty() ? null : m.size() >= 2 ? tail(m, line, ks) : null;
            String key = line.substring(ks, i);
            i++;
            String val;
            if (i < n && line.charAt(i) == '"') {
                StringBuilder sb = new StringBuilder();
                i++;
                while (i < n && line.charAt(i) != '"') {
                    char c = line.charAt(i);
                    if (c == '\\' && i + 1 < n) {
                        char e = line.charAt(++i);
                        sb.append(switch (e) { case 'n' -> '\n'; case 't' -> '\t'; default -> e; });
                    } else sb.append(c);
                    i++;
                }
                if (i >= n) return null; // nicht geschlossen
                i++;
                val = sb.toString();
            } else {
                int vs = i;
                while (i < n && line.charAt(i) != ' ') i++;
                val = line.substring(vs, i);
            }
            m.put(key, val);
        }
        return m;
    }

    /** Text ohne key=value am Ende (kommt vor) als Nachricht anhängen. */
    private static Map<String, Object> tail(Map<String, Object> m, String line, int from) {
        m.merge("msg", line.substring(from).trim(), (a, b) -> a + " " + b);
        return m;
    }
}
