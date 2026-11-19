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

- Beim Öffnen wird im Hintergrund nur ein **Index der Zeilenanfänge** aufgebaut (8 Byte pro Zeile).
  Die Tabelle ist sofort bedienbar und wächst mit.
- Gelesen und geparst werden nur die **gerade sichtbaren Zeilen** (LRU-Cache mit 20.000 Zeilen).
- Messung (1,15 GB, 5 Mio. Zeilen, `-Xmx512m`): Index 1,3 s, Volltextfilter 1,7 s,
  Spaltenfilter mit vollständigem JSON-Parse 2,7 s.
- Für mehr als ca. 50 Mio. Zeilen ggf. mehr Heap geben (`java -Xmx2g -jar …`).

## Bedienung

| | |
|---|---|
| Filter | Teilstring (Groß-/Kleinschreibung egal) oder Regex, wahlweise auf die ganze Zeile oder eine Spalte; *Nicht* invertiert |
| Auswahlfilter | Zweite Filterzeile: Dropdown mit allen vorkommenden Werten einer Spalte. Tippen sucht, Enter/Leertaste/Klick wählt an oder ab, mehrere Werte möglich. Wird beim Schließen angewendet |
| Loglevel-Filter | Bei Spalten vom Typ Loglevel: „≥ WARN“ zeigt WARN, ERROR und FATAL. Kennt Log4j, Logback und JUL (FINEST … SEVERE) |
| Detailansicht | Klick auf `#`: ganze Zeile als formatiertes JSON. Klick auf eine andere Zelle: nur deren Wert, `\n` als echter Zeilenumbruch, `\uXXXX` als Zeichen |
| Ansicht | Spalten ein-/ausblenden, *Zeitzone* für alle Zeitstempel-Spalten |
| Spaltenköpfe | Per Drag & Drop umsortieren. Rechtsklick: *Ausblenden*, *Zeitzone* (bei Zeitstempeln), *Package original / compact / Class only* (bei Klassennamen), *Auswahlfilter*, *Spaltentyp Loglevel* |
| Extras → Log-Generator | Erzeugt ein Beispiel-Log mit wählbarer Zeilenzahl und wählbarem Zeitabstand. Entweder im Logstash-Format oder passend zu einem Schema: Jeder Spaltenpfad wird anhand seines Namens befüllt |
| Strg+O / Strg+L / F5 | Log öffnen / Schema laden / Neu laden (Dateiauswahl filtert wahlweise `*.json`/`*.jsonl` oder `*.log`) |
| Strg+S | Schema speichern unter… |
| Strg+G | Gehe zu Zeile |
| Rechtsklick in der Tabelle | Rohzeile(n) oder Zellen kopieren |

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

## Grenzen

- Kein Sortieren (bei Millionen Zeilen wäre das teuer). Die Reihenfolge entspricht der Datei.
- Kein Live-Tail. *Neu laden* (F5) liest die Datei neu ein.
- Einzelne Zeilen werden ab 64 MB abgeschnitten, Zelltexte ab 2.000 Zeichen.

## Log-Generator auf der Kommandozeile

```bat
java -cp target/beswinged.jar jsonlogviewer.LogGenerator ziel.jsonl 1000 schema.example.yaml
```

Ohne Schema-Argument wird das Logstash-Format erzeugt. Die letzte Zeile liegt ungefähr bei „jetzt“,
der Abstand beträgt 15 s.
