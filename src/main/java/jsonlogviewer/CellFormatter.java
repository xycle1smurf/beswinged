package jsonlogviewer;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAccessor;
import java.util.Locale;
import java.util.regex.Pattern;

/** Anzeige-Formatierung einer Spalte. Wird im Schema als {@code formatter} gespeichert. */
public enum CellFormatter {
    NONE("original"),
    TIMESTAMP_UTC("timestamp-utc"),
    TIMESTAMP_LOCAL("timestamp-local"),
    PACKAGE_COMPACT("package-compact"),
    CLASS_ONLY("class-only");

    public final String key;

    CellFormatter(String key) { this.key = key; }

    public boolean isTimestamp() { return this == TIMESTAMP_UTC || this == TIMESTAMP_LOCAL; }
    public boolean isPackage() { return this == PACKAGE_COMPACT || this == CLASS_ONLY; }

    public static CellFormatter fromKey(String key) {
        if (key == null || key.isBlank()) return NONE;
        String k = key.trim().toLowerCase(Locale.ROOT);
        for (CellFormatter f : values()) if (f.key.equals(k)) return f;
        if (k.equals("none") || k.equals("package-original") || k.equals("timestamp-original")) return NONE;
        throw new IllegalArgumentException("Unbekannter formatter '" + key
                + "' (erlaubt: timestamp-utc, timestamp-local, package-compact, class-only, original)");
    }

    public String apply(String s) {
        if (s == null || s.isEmpty() || this == NONE) return s;
        return switch (this) {
            case TIMESTAMP_UTC -> formatInstant(s, ZoneOffset.UTC);
            case TIMESTAMP_LOCAL -> formatInstant(s, ZoneId.systemDefault());
            case PACKAGE_COMPACT -> compactPackage(s);
            case CLASS_ONLY -> classOnly(s);
            default -> s;
        };
    }

    // ------------------------------------------------------------ Zeitstempel

    private static final DateTimeFormatter OUT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS xxx");

    private static final DateTimeFormatter IN = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .append(DateTimeFormatter.ISO_LOCAL_DATE)
            .appendLiteral('T')
            .append(DateTimeFormatter.ISO_LOCAL_TIME)
            .optionalStart().appendPattern("XXX").optionalEnd()
            .optionalStart().appendPattern("XX").optionalEnd()
            .toFormatter(Locale.ROOT);

    /**
     * Liest ISO-artige Zeitstempel: {@code 2026-11-19T08:00:00.000+01:00}, {@code ...Z},
     * {@code ...+0100}, {@code 2026-11-19 08:00:00,123}. Ohne Offset gilt UTC.
     * @return null, wenn kein Zeitstempel
     */
    public static Instant parseInstant(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.length() < 16 || s.length() > 40 || s.charAt(4) != '-' || s.charAt(7) != '-'
                || !Character.isDigit(s.charAt(0))) return null;
        char sep = s.charAt(10);
        if (sep != 'T' && sep != 't' && sep != ' ') return null;
        String norm = s.substring(0, 10) + 'T' + s.substring(11).replace(',', '.');
        try {
            TemporalAccessor ta = IN.parseBest(norm, OffsetDateTime::from, LocalDateTime::from);
            if (ta instanceof OffsetDateTime odt) return odt.toInstant();
            return ((LocalDateTime) ta).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Rechnet in die Zone um; der Offset (Sommer-/Winterzeit) ergibt sich aus dem jeweiligen Datum. */
    private static String formatInstant(String s, ZoneId zone) {
        Instant i = parseInstant(s);
        return i == null ? s : OUT.format(i.atZone(zone));
    }

    // ------------------------------------------------------------ Java-Klassen

    private static final Pattern CLASS_NAME =
            Pattern.compile("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)+");

    public static boolean isClassName(String s) {
        return s != null && s.length() < 300 && CLASS_NAME.matcher(s).matches();
    }

    /** {@code de.example.shop.OrderService} -> {@code d.e.s.OrderService} */
    private static String compactPackage(String s) {
        if (!isClassName(s)) return s;
        String[] parts = s.split("\\.");
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < parts.length - 1; i++) sb.append(parts[i].charAt(0)).append('.');
        return sb.append(parts[parts.length - 1]).toString();
    }

    /** {@code de.example.shop.OrderService} -> {@code OrderService} */
    private static String classOnly(String s) {
        if (!isClassName(s)) return s;
        return s.substring(s.lastIndexOf('.') + 1);
    }
}
