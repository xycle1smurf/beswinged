# beswinged
Java Swing based JSON Log Viewer for the poor without OpenSearch

Swing-Viewer für große JSON-Lines-Logdateien (ein JSON-Objekt pro Zeile).
**Nur JDK 21, keine Fremdbibliotheken.** JSON-Parser und YAML-Parser (Teilmenge) sind selbst geschrieben.

## Bauen & Starten

Mit Maven (Standard-Layout, Quellen unter `src/main/java`):

```bat
mvn package
java -jar target/beswinged.jar app.log --schema schema.example.yaml
```

Weitere Optionen: `--ram` bzw. `--seek` (Lademodus, sonst ab 1 GB Seek), `--tail` (Tail einschalten).

Zum Ausprobieren liegt ein Beispiel im Format von logstash-logback-encoder bei (240 Zeilen, alle 15 s, TRACE bis ERROR):

```bat
java -jar target/beswinged.jar examples/logstash-sample.jsonl --schema examples/logstash.schema.yaml
```

Ohne Maven geht es auch nur mit dem JDK: `build.cmd` (Windows) bzw. `./build.sh` (Linux/macOS)
erzeugen dasselbe `target/beswinged.jar`. Alle Argumente sind optional. Log- und Schema-Dateien lassen sich auch
per Menü oder Drag & Drop ins Fenster öffnen (`.yaml`/`.yml`/`.properties` werden als Schema erkannt).

Ohne Schema werden die Spalten automatisch aus den Top-Level-Feldern der ersten 200 Zeilen erkannt.
Über *Datei → Schema als YAML speichern…* lässt sich das als Vorlage speichern.

## Wie es mit großen Dateien umgeht

- Beim Öffnen fragt der Dateidialog, wie geladen wird (ab 1 GB ist *Datei-Index (Seek)* vorausgewählt):
  - **In den RAM laden**: Die Datei liegt komplett im Speicher. Scrollen und Filtern sind am schnellsten,
    braucht aber etwa so viel Heap wie die Datei groß ist. Passt sie nicht, bietet der Viewer Seek an.
  - **Datei-Index (Seek)**: Die Datei wird einmal durchgelesen. Im RAM bleiben nur die Zeilenanfänge
    (8 Byte pro Zeile) und der Zeitstempel jeder 20. Zeile. Angezeigte Zeilen werden gezielt aus der Datei gelesen
    (3 Mio. Zeilen / 1,1 GB: 134 MB Heap statt 1,4 GB).
- Nachträglich umschalten: *Datei → Datei-Index (Seek)* lädt die geöffnete Datei im anderen Modus neu.
  Filter, Auswahlfilter, Zeitraum und ausgewählte Zeile bleiben erhalten.
- Die Tabelle ist sofort bedienbar und wächst während des Indizierens mit.
- Gelesen und geparst werden nur die **gerade sichtbaren Zeilen** (LRU-Cache mit 20.000 Zeilen).
- Messung (1,15 GB, 5 Mio. Zeilen, `-Xmx512m`): Index 1,3 s, Volltextfilter 1,7 s,
  Spaltenfilter mit vollständigem JSON-Parse 2,7 s.
- Für mehr als ca. 50 Mio. Zeilen ggf. mehr Heap geben (`java -Xmx2g -jar …`).

## Bedienung

| | |
|---|---|
| Filter | Teilstring (Groß-/Kleinschreibung egal) oder Regex, wahlweise auf die ganze Zeile oder eine Spalte; *Nicht* invertiert |
| Auswahlfilter | Zweite Filterzeile: Dropdown mit allen vorkommenden Werten einer Spalte. Tippen sucht, Enter/Leertaste/Klick wählt an oder ab, mehrere Werte möglich. Wird beim Schließen angewendet. Bei Paket-/Klassennamen (z.B. Logger) erscheinen zusätzlich die gemeinsamen Pakete hierarchisch eingerückt, z.B. `org.example.*  (12)`: Auswahl trifft alle Werte darunter. Gespeicherte Filter merken sich solche Paket-Einträge ebenfalls |
| Loglevel-Filter | Bei Spalten vom Typ Loglevel: „≥ WARN“ zeigt WARN, ERROR und FATAL. Kennt Log4j, Logback und JUL (FINEST … SEVERE) |
| Detailansicht | Klick auf `#`: ganze Zeile als formatiertes JSON. Klick auf eine andere Zelle: nur deren Wert, `\n` als echter Zeilenumbruch, `\uXXXX` als Zeichen |
| Ansicht | Spalten ein-/ausblenden, *Zeitzone* für alle Zeitstempel-Spalten |
| Spaltenköpfe | Per Drag & Drop umsortieren. Rechtsklick: *Ausblenden*, *Zeitzone* (bei Zeitstempeln), *Package original / compact / Class only* (bei Klassennamen), *Auswahlfilter*, *Spaltentyp Loglevel* |
| Extras → Log-Generator | Erzeugt ein Beispiel-Log mit wählbarer Zeilenzahl und wählbarem Zeitabstand. Entweder im Logstash-Format oder passend zu einem Schema: Jeder Spaltenpfad wird anhand seines Namens befüllt |
| Strg+O / Strg+L / F5 | Log öffnen / Schema laden / Neu laden (Dateiauswahl filtert wahlweise `*.json`/`*.jsonl` oder `*.log`) |
| Strg+S | Schema speichern unter… |
| Strg+G | Gehe zu Zeile |
| Zeitraum (dritte Filterzeile) | *Von* und *Bis* mit Kalender und Uhrzeit (Symbol rechts im Feld) oder getippt, z.B. `2026-11-19 08:15:30`, `19.11.2026 08:15`, `08:15` (Datum der ausgewÃ¤hlten Zeile) oder `2026-11-19T07:15:00Z`. Ein leeres Feld lÃ¤sst die Seite offen. *ZusÃ¤tzlich davor/danach* zeigt n Zeilen vor bzw. nach dem Zeitraum unabhÃ¤ngig von ihrer Zeit. Wirkt zusammen (UND) mit den anderen Filtern. Ohne Offset gilt die Zeitzone, in der die Zeitstempel-Spalte angezeigt wird (rechts angezeigt). *Gehe zu Von* springt ohne zu filtern. Ãber den Index werden je Grenze nur ca. 40 Zeilen gelesen, auch im Seek-Modus |
| Rechtsklick â Umliegender Zeitraumâ¦ | Ãbernimmt den Zeitstempel der Zeile. WÃ¤hlbar: Zeitspanne (1 Min bis 12 h), Art (umliegend, nur davor, nur danach) und Zusatzzeilen davor/danach (Standard 10). FÃ¼llt Von/Bis und filtert; die Zeile bleibt ausgewÃ¤hlt |
| Datei → Tail / Schreiben folgen (Strg+T) | Liest angehängte Zeilen alle 0,5 s nach. Aktive Filter und Auswahlwerte werden fortgeschrieben. Steht die Ansicht am Ende, scrollt sie mit |
| Rechtsklick in der Tabelle | *Kopiere Zelle*: der Wert wie in der Detailansicht (formatiert, mit echten ZeilenumbrÃ¼chen und Unicode). *Kopiere als Markdown*: die ausgewÃ¤hlten Zeilen fÃ¼r Dokumentationen, je Zeile eine Ãberschrift, kurze Werte als Liste, mehrzeilige Werte (Nachricht, Stacktrace, JSON) als Codeblock. AuÃerdem Rohzeile(n) oder Zellen (Tab-getrennt) kopieren |
| MenÃ¼ Schema | Laden, automatisch erkennen, speichern unter und die **20 zuletzt geÃ¶ffneten Schemas**. Jedes geÃ¶ffnete Schema wird nach `~/.beswinged/schemas/` kopiert; fehlt das Original, Ã¶ffnet der Eintrag die Kopie |
| MenÃ¼ Filter | *Speichern alsâ¦* legt Textfilter, Auswahl- und Level-Filter sowie Zeitraum unter einem Namen in `~/.beswinged/filters/<Name>.yaml` ab. Darunter die gespeicherten Filter zum Anwenden, unter *LÃ¶schen* zum Entfernen. Spalten werden Ã¼ber ihren Namen zugeordnet |
| Ansicht â Einstellungenâ¦ | Darstellung ganzer Zeilen je Loglevel: fett, kursiv, Schriftfarbe. Standard: WARN, ERROR und FATAL fett, TRACE dunkelgrau |

Alle Filter wirken gemeinsam (UND). *Zurücksetzen* in der Filterleiste leert alle.

Reihenfolge, Breite, Sichtbarkeit, Formatter, Spaltentyp und Auswahlfilter der Spalten werden automatisch in die geladene
Schema-Datei (YAML oder Properties) zurückgeschrieben. Der Kommentarblock am Anfang der Datei bleibt
erhalten, Kommentare weiter unten nicht. Beim automatisch erkannten Schema wird nichts gespeichert,
bis es einmal über *Schema speichern unter…* abgelegt wurde.

Zeitzone *Lokal* rechnet jeden Zeitstempel mit dem für sein Datum gültigen Offset um, also mit
Sommer- bzw. Winterzeit. Zeitstempel ohne Offset gelten als UTC.

Zeilen, die kein gültiges JSON sind, werden lila hinterlegt, mit dem Rohtext in der ersten Spalte.
Leere Zeilen werden übersprungen. Die Zeilennummer `#` zählt nur nicht-leere Zeilen.

## Schema

Siehe [schema.example.yaml](schema.example.yaml) bzw. [schema.example.properties](schema.example.properties).

```yaml
title: "Meine App-Logs"
columns:
  - name: Zeit
    path: "@timestamp"
    width: 190
  - name: User
    path: context.user.id     # verschachtelt; Arrays: tags[0]; ganze Zeile: $raw
  - name: Logger
    path: logger_name
    formatter: package-compact  # timestamp-local | timestamp-utc | package-compact | class-only
    choiceFilter: true          # Auswahl-Dropdown in der Filterleiste
  - name: Level
    path: level
    type: loglevel              # Filter "ab Level"
  - name: Thread
    path: thread_name
    visible: false
rowColors:
  path: level
  values:
    ERROR: "#FFD6D6"
```

Unterstütztes YAML: Mappings, Listen, Skalare (auch in Anführungszeichen), `[a, b]`, Kommentare.
Nicht unterstützt: Anker/Aliase, mehrzeilige Strings (`|`, `>`), `{…}`-Flow-Mappings.

## Einstellungen

Alles Benutzerbezogene liegt in `~/.beswinged/` (unter Windows `%USERPROFILE%.beswinged`):

- `settings.yaml`: letztes Verzeichnis, Loglevel-Darstellung, zuletzt geÃ¶ffnete Schemas, Vorgaben fÃ¼r *Umliegender Zeitraum*
- `schemas/`: Kopien der zuletzt geÃ¶ffneten Schemas (Cache)
- `filters/`: gespeicherte Filter, je Datei ein Filter

Mit `-Dbeswinged.home=<Ordner>` lÃ¤sst sich ein anderes Verzeichnis verwenden.

## Grenzen

- Kein Sortieren (bei Millionen Zeilen wäre das teuer). Die Reihenfolge entspricht der Datei.
- Zeitraum und Zeitsuche setzen zeitlich sortierte Zeilen voraus (Binärsuche über den Zeitstempel-Index).
- Tail verfolgt die geöffnete Datei. Wird sie gekürzt, wird sie neu geladen. Nach einer Rotation
  (Umbenennen und neue Datei anlegen) bitte die neue Datei öffnen.
- Einzelne Zeilen werden ab 64 MB abgeschnitten, Zelltexte ab 2.000 Zeichen.

## Log-Generator auf der Kommandozeile

```bat
java -cp target/beswinged.jar jsonlogviewer.LogGenerator ziel.jsonl 1000 schema.example.yaml
```

Ohne Schema-Argument wird das Logstash-Format erzeugt. Die letzte Zeile liegt ungefähr bei „jetzt“,
der Abstand beträgt 15 s.
