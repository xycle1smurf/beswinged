package jsonlogviewer.format.pattern;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import jsonlogviewer.format.AbstractLogFormat;

/**
 * Konverter auf Basis eines oder mehrerer Layout-Muster ({@link LayoutPattern}). Bei der Erkennung zählt das Muster,
 * das am besten passt; für die Datei wird dann nur noch dieses verwendet ({@link #forSample}).
 *
 * <p>Eigene Formate mit Muster brauchen nur einen Konstruktor:
 * <pre>{@code
 * public class MyFormat extends PatternLogFormat {
 *     public MyFormat() { super("my-app", "Meine Anwendung", "%d{yyyy-MM-dd HH:mm:ss} %-5level [%thread] %logger - %msg%n"); }
 * }
 * }</pre>
 */
public class PatternLogFormat extends AbstractLogFormat {

    private final List<LayoutPattern> patterns;

    public PatternLogFormat(String id, String name, String... patterns) {
        super(id, name);
        List<LayoutPattern> l = new ArrayList<>();
        for (String p : patterns) l.add(LayoutPattern.compile(p));
        this.patterns = List.copyOf(l);
    }

    public PatternLogFormat(String id, String name, List<LayoutPattern> patterns) {
        super(id, name);
        if (patterns.isEmpty()) throw new IllegalArgumentException("Kein Muster");
        this.patterns = List.copyOf(patterns);
    }

    public List<LayoutPattern> patterns() { return patterns; }

    @Override public String description() {
        StringBuilder sb = new StringBuilder();
        for (LayoutPattern p : patterns) sb.append(sb.length() == 0 ? "" : "\n").append(p.pattern());
        return sb.toString();
    }

    @Override public double detect(List<String> sample) {
        if (patterns.size() == 1) return super.detect(sample);
        double best = 0;
        for (LayoutPattern p : patterns) best = Math.max(best, single(p).detect(sample));
        return best;
    }

    @Override public PatternLogFormat forSample(List<String> sample) {
        if (patterns.size() == 1) return this;
        LayoutPattern best = patterns.get(0);
        double score = -1;
        for (LayoutPattern p : patterns) {
            double s = single(p).detect(sample);
            if (s > score) { score = s; best = p; }
        }
        return single(best);
    }

    private PatternLogFormat single(LayoutPattern p) {
        return new PatternLogFormat(id(), name(), List.of(p));
    }

    @Override public boolean isEntryStart(String line) {
        for (LayoutPattern p : patterns) if (p.matchesFirstLine(line)) return true;
        return false;
    }

    @Override protected Map<String, Object> parseLine(String line) {
        for (LayoutPattern p : patterns) {
            if (p.extraLines() > 0) continue;
            Map<String, Object> m = p.parse(line);
            if (m != null) return m;
        }
        return null;
    }

    @Override public Map<String, Object> parse(String entry) {
        for (LayoutPattern p : patterns) {
            if (p.extraLines() == 0) continue;
            // Muster über mehrere Zeilen: so viele Zeilen wie das Muster hat, Rest sind Folgezeilen
            int cut = -1;
            for (int k = 0; k <= p.extraLines(); k++) {
                cut = entry.indexOf('\n', cut + 1);
                if (cut < 0) break;
            }
            String head = cut < 0 ? entry : entry.substring(0, cut);
            Map<String, Object> m = p.parse(head.replace("\r", ""));
            if (m != null) {
                if (cut >= 0) addContinuation(m, entry.substring(cut + 1));
                return m;
            }
        }
        return super.parse(entry);
    }
}
