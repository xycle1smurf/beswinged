# beswinged
Java Swing based JSON Log Viewer for the poor without OpenSearch

Swing-Viewer für große JSON-Lines-Logdateien (ein JSON-Objekt pro Zeile).
**Nur JDK 21, keine Fremdbibliotheken.** JSON-Parser und YAML-Parser (Teilmenge) sind selbst geschrieben.

## Bauen & Starten

```bat
build.cmd
java -jar jsonlogviewer.jar app.log --schema schema.example.yaml
```

Unter Linux/macOS `./build.sh`. Alle Argumente sind optional. Log- und Schema-Dateien lassen sich auch
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
| Detailansicht | Ausgewählte Zeile als formatiertes JSON |
| Strg+O / Strg+L / F5 | Log öffnen / Schema laden / Neu laden |
| Strg+G | Gehe zu Zeile |
| Rechtsklick | Rohzeile(n) oder Zellen kopieren |

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
