package jsonlogviewer.format.example;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jsonlogviewer.format.AbstractLogFormat;
import jsonlogviewer.format.Fields;

/**
 * Beispiel und Vorlage für einen eigenen Konverter. Erkennt Zeilen mit senkrechten Strichen als Trenner:
 * <pre>
 * 2026-11-21 10:15:30.123 | INFO  | OrderService | Bestellung 42 angelegt
 * 2026-11-21 10:15:31.007 | ERROR | OrderService | Zahlung abgelehnt
 *     at com.example.Payment.charge(Payment.java:88)
 * </pre>
 *
 * <h2>So entsteht ein eigener Konverter</h2>
 * <ol>
 *   <li>Diese Klasse kopieren (eigenes Paket, z.B. {@code com.example.beswinged}) und {@link #parseLine} anpassen.
 *       Die Basisklasse {@link AbstractLogFormat} hängt Folgezeilen (Stacktraces) an und übernimmt die Erkennung.
 *       Für Formate, die sich als Logback-/Log4j-Muster beschreiben lassen, reicht eine Unterklasse von
 *       {@link jsonlogviewer.format.pattern.PatternLogFormat} mit dem Muster.</li>
 *   <li>Datei {@code META-INF/services/jsonlogviewer.format.LogFormat} mit dem vollständigen Klassennamen anlegen.</li>
 *   <li>Kompilieren und als JAR packen, mit {@code beswinged.jar} im Klassenpfad:
 *       <pre>javac -cp beswinged.jar -d out MyFormat.java
 *jar cf meinformat.jar -C out . -C res META-INF</pre></li>
 *   <li>Das JAR nach {@code ~/.beswinged/formats/} kopieren (oder beim Start in den Klassenpfad aufnehmen) und
 *       beswinged neu starten. Das Format steht dann unter <i>Schema → Logformat</i> und wird automatisch erkannt.</li>
 * </ol>
 * Diese Klasse ist selbst so eingetragen (siehe {@code META-INF/services} im beswinged-JAR).
 */
public class ExampleFormat extends AbstractLogFormat {

    private static final Pattern LINE = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}(?:\\.\\d{3})?)\\s*\\|\\s*([A-Z]+)\\s*\\|\\s*([^|]*?)\\s*\\|\\s?(.*)$");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSS]");

    /** Öffentlicher Konstruktor ohne Parameter (für ServiceLoader). */
    public ExampleFormat() {
        super("example-pipe", "Beispiel: Zeit | Level | Quelle | Nachricht");
    }

    @Override public String description() {
        return "2026-11-21 10:15:30.123 | INFO | OrderService | Nachricht";
    }

    /** Schnelle Prüfung ohne Map: wird beim Einlesen für jede Zeile aufgerufen. */
    @Override public boolean isEntryStart(String line) {
        return LINE.matcher(line).matches();
    }

    @Override protected Map<String, Object> parseLine(String line) {
        Matcher m = LINE.matcher(line);
        if (!m.matches()) return null;
        Map<String, Object> out = new LinkedHashMap<>(); // Reihenfolge = Spalten
        out.put(Fields.TIMESTAMP, isoTimestamp(m.group(1), TIME, null)); // Ortszeit -> ISO mit Offset
        out.put(Fields.LEVEL, m.group(2));
        out.put(Fields.LOGGER, m.group(3));
        out.put(Fields.MESSAGE, m.group(4));
        return out;
    }
}
