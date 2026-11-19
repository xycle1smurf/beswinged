package jsonlogviewer;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Gemeinsame Rangfolge der Loglevels von Log4j, Logback und java.util.logging (JUL).
 * Höherer Rang = schwerwiegender. Gleichwertige Levels teilen sich einen Rang.
 */
public final class LogLevels {

    /** Gruppen in aufsteigender Schwere. */
    static final String[][] GROUPS = {
        {"FINEST"},
        {"TRACE", "FINER"},
        {"DEBUG", "FINE"},
        {"CONFIG"},
        {"INFO"},
        {"WARN", "WARNING"},
        {"ERROR", "SEVERE"},
        {"FATAL", "CRITICAL"},
    };

    private static final Map<String, Integer> RANK = new HashMap<>();
    static {
        for (int i = 0; i < GROUPS.length; i++) for (String l : GROUPS[i]) RANK.put(l, i + 1);
    }

    private LogLevels() { }

    /** Rang des Levels (1..), 0 wenn unbekannt. */
    public static int rank(String level) {
        if (level == null) return 0;
        Integer r = RANK.get(level.trim().toUpperCase(Locale.ROOT));
        return r == null ? 0 : r;
    }

    /** Anzeigename einer Gruppe, z.B. "WARN / WARNING". */
    public static String label(int rank) {
        return String.join(" / ", GROUPS[rank - 1]);
    }

    public static int count() { return GROUPS.length; }
}
