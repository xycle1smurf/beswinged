package jsonlogviewer.format;

import java.util.List;
import java.util.Map;

/**
 * Konverter für ein Text-Logformat (Log4j, Logback, JUL, …). Zerlegt einen Log-Eintrag in Felder, die beswinged
 * anzeigt, als kämen sie aus einer JSON-Zeile: Jeder Schlüssel der Map wird eine Spalte.
 *
 * <p>Ein Eintrag besteht aus einer Startzeile und optional Folgezeilen (z.B. Stacktrace). Welche Zeile einen neuen
 * Eintrag beginnt, entscheidet {@link #isEntryStart}; alle anderen Zeilen werden an den vorigen Eintrag angehängt.
 *
 * <h2>Eigene Konverter</h2>
 * Klasse mit öffentlichem Konstruktor ohne Parameter schreiben (am einfachsten als Unterklasse von
 * {@link AbstractLogFormat} oder {@link jsonlogviewer.format.pattern.PatternLogFormat}), in einer Datei
 * {@code META-INF/services/jsonlogviewer.format.LogFormat} eintragen und das JAR
 * <ul>
 *   <li>in den Ordner {@code ~/.beswinged/formats/} legen, oder</li>
 *   <li>beim Start mit auf den Klassenpfad nehmen ({@code java -cp beswinged.jar:meinformat.jar jsonlogviewer.JsonLogViewer}).</li>
 * </ul>
 * Vorlage: {@link jsonlogviewer.format.example.ExampleFormat}.
 *
 * <p>Empfohlene Feldnamen stehen in {@link Fields}; mit ihnen passt auch das mitgelieferte Logstash-Schema.
 * Werte sollten Strings sein (Zahlen, Boolean, Listen und Maps gehen auch). Zeitstempel am besten als ISO-8601 mit
 * Offset ({@link AbstractLogFormat#isoTimestamp}), dann funktionieren Zeitraum-Filter und Zeitzonen.
 *
 * <p>Implementierungen müssen thread-sicher sein: beswinged ruft {@link #parse} und {@link #isEntryStart} aus
 * mehreren Threads gleichzeitig auf.
 */
public interface LogFormat {

    /** Eindeutige Kennung, z.B. {@code "logback"}; wird in den Einstellungen gespeichert. */
    String id();

    /** Anzeigename im Menü <i>Schema → Logformat</i>. */
    String name();

    /** Kurzbeschreibung für den Tooltip, z.B. die erkannten Muster. */
    default String description() { return ""; }

    /**
     * Wie gut passen die Probezeilen vom Anfang der Datei (ohne Leerzeilen) zu diesem Format?
     * @return 0 = gar nicht … 1 = jede Zeile. Ab 0,6 kommt das Format bei der automatischen Erkennung in Frage.
     */
    double detect(List<String> sample);

    /**
     * Variante für eine bestimmte Datei, z.B. das Muster, das am besten passt. Wird nach der Erkennung bzw. Auswahl
     * einmal je Datei aufgerufen. Standard: dieses Objekt.
     */
    default LogFormat forSample(List<String> sample) { return this; }

    /** Beginnt mit dieser Zeile ein neuer Eintrag? {@code false}: Folgezeile, gehört zum vorigen Eintrag. */
    boolean isEntryStart(String line);

    /**
     * Zerlegt einen Eintrag in Felder (Reihenfolge = Spaltenreihenfolge).
     * @param entry Startzeile und Folgezeilen, getrennt durch {@code \n}
     * @return Felder, oder {@code null} wenn der Eintrag nicht erkannt wird (Zeile wird als ungültig markiert)
     */
    Map<String, Object> parse(String entry);
}
