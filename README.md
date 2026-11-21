# beswinged
Java Swing based JSON Log Viewer for the poor without OpenSearch

Swing-Viewer für große JSON-Lines-Logdateien (ein JSON-Objekt pro Zeile).
**Nur JDK 21, keine Fremdbibliotheken.** JSON-Parser und YAML-Parser (Teilmenge) sind selbst geschrieben.

## Download

Fertige Pakete gibt es unter [Releases](https://github.com/xycle1smurf/beswinged/releases):

| Datei | Inhalt | Start |
|---|---|---|
| `beswinged-<Version>-windows-x64-setup.exe` | Installer: Programme-Ordner, Startmenü, optional Desktop-Verknüpfung, Eintrag unter *Installierte Apps* | ausführen, dann Startmenü → beswinged |
| `beswinged-<Version>-windows-x64.zip` | Ordner mit eigener Java-Laufzeit, keine Installation nötig | entpacken, `beswinged\beswinged.exe` |
| `beswinged-<Version>-linux-x64.tar.gz` | dito | entpacken, `beswinged/bin/beswinged` |
| `beswinged-<Version>-macos-aarch64.tar.gz` | `beswinged.app` (Apple Silicon) | entpacken, öffnen |
| `beswinged.jar` | nur das Programm, benötigt Java 21+ | `java -jar beswinged.jar` |

Die Pakete sind nicht signiert. Windows zeigt beim ersten Start ggf. eine SmartScreen-Warnung
(*Weitere Informationen → Trotzdem ausführen*). Unter macOS hilft
`xattr -dr com.apple.quarantine beswinged.app`. Log- und Schema-Datei lassen sich wie bei der Jar
als Argumente übergeben oder per Drag & Drop öffnen. Die Laufzeit darf bis zu 50 % des Arbeitsspeichers nutzen.

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
| Highlight Filter | Feld rechts in der Leiste über der Detailansicht: Regex (Groß-/Kleinschreibung egal), alle Treffer im Text darunter werden gelb markiert, die Anzahl steht daneben. Enter / Umschalt+Enter springt zum nächsten / vorherigen Treffer (orange), Esc leert. Bleibt beim Wechsel der Zeile aktiv; ungültige Regex färbt das Feld rot |
| Ansicht | Spalten ein-/ausblenden, *Zeitzone* für alle Zeitstempel-Spalten |
| Spaltenköpfe | Per Drag & Drop umsortieren. Rechtsklick: *Ausblenden*, *Zeitzone* (bei Zeitstempeln), *Package original / compact / Class only* (bei Klassennamen), *Auswahlfilter*, *Spaltentyp Loglevel* |
| Extras → Log-Generator | Erzeugt ein Beispiel-Log mit wählbarer Zeilenzahl und wählbarem Zeitabstand. Entweder im Logstash-Format oder passend zu einem Schema: Jeder Spaltenpfad wird anhand seines Namens befüllt |
| Extras → Connect… (Strg+K) | Holt das Log aus Kubernetes oder OpenShift mit `kubectl` bzw. `oc`: Cluster, Namespace, Art (Pod, Deployment, ReplicaSet, StatefulSet, DaemonSet, DeploymentConfig), Name und optional Container. *Laden* listet Namespaces bzw. Pods/Workloads mit Status und Containern. Bei Workloads werden alle Pods über deren Label-Selector gelesen. Optional *Seit* (`--since`), *Letzte Zeilen* (`--tail`), *Vorheriger Container* (`--previous`). Der Befehl wird vorab angezeigt. Das Log landet in `~/.beswinged/kube/<Cluster>_<Namespace>_<Name>.jsonl` und wird geöffnet. Mit *Folgen* läuft `logs -f` weiter und Tail ist an; *Datei → Schließen* oder Programmende beendet es |
| Extras → Show Namespace… | Eigenes Fenster mit allen Objekten eines Namespaces als Kacheln, angelehnt an die Baumansicht von Argo CD: links der Namespace, daneben Deployments, StatefulSets, DaemonSets, CronJobs, Services, Ingresses/Routes, PVCs, ConfigMaps, Secrets usw., Kinder (ReplicaSet → Pod, CronJob → Job → Pod, Service → Endpoints) jeweils eine Spalte weiter. Jede Kachel zeigt Zustand (Healthy, Progressing, Degraded, Suspended – Regeln wie in Argo CD), Kurzinfo (z.B. `rev:3 1/2 bereit`, `crashloopbackoff 0/1 ↻ 7`) und Alter. Filter nach Name, Zustand und Art; Systemobjekte und alte ReplicaSets sind ausgeblendet. Strg+Mausrad zoomt, Ziehen verschiebt, *Auto* lädt alle 10 s neu. Doppelklick öffnet ein Fenster mit **Manifest (YAML)**, Zusammenfassung und Events – nicht modal, beliebig viele gleichzeitig. Werte von Secrets sind verdeckt, bis *Werte anzeigen* gewählt wird. Rechtsklick → *Logs…* öffnet Connect vorbelegt. Mehrere Namespace-Fenster sind möglich. Ohne Argo CD gibt es keinen Sync-Status (Abgleich mit Git) |
| Extras → Connect Settings… | Cluster verwalten: Name, Werkzeug (`kubectl`/`oc`), optional Programmpfad, kubeconfig-Datei, Kontext (*Laden* listet die Kontexte), Standard-Namespace. *Aus kubeconfig importieren* legt je Kontext einen Eintrag an, *Verbindung testen* zeigt Server-Version und ob Logs gelesen werden dürfen. **Zugangsdaten speichert beswinged nicht**; angemeldet wird wie gewohnt (`oc login`, `az aks get-credentials`, …), das füllt die kubeconfig |
| Datei → Log einfügen… (Strg+Umschalt+V) | Fenster mit Textfeld für einen Log-Ausschnitt aus der Zwischenablage (z.B. aus Teams, einer Mail oder einem Ticket); beim Öffnen ist der Inhalt der Zwischenablage schon eingefügt. *Anzeigen* (Strg+Enter) öffnet den Text wie eine Datei; erneutes *Anzeigen* aktualisiert sie. *Reparieren* fügt über mehrere Zeilen verteilte JSON-Objekte zusammen und korrigiert geschützte Leerzeichen, unsichtbare Zeichen und typografische Anführungszeichen. Der Text wird unter `~/.beswinged/paste/` abgelegt (nach 14 Tagen gelöscht, sofern nicht geöffnet) |
| Strg+O / Strg+L / F5 | Log öffnen / Schema laden / Neu laden (Dateiauswahl filtert wahlweise `*.json`/`*.jsonl` oder `*.log`). Neu laden behält Filter und Auswahl |
| Menü Fenster | Alle geöffneten Logdateien (RAM oder Seek) in Öffnungsreihenfolge, die zuletzt geöffnete unten. Strg+1 … Strg+9 wechselt. Jede Datei behält ihr Schema, ihre Filter, ihren Zeitraum und die ausgewählte Zeile. Eine bereits offene Datei erneut zu öffnen wechselt nur zu ihr |
| Datei → Schließen (Strg+W) | Schließt die angezeigte Datei und entfernt sie aus *Fenster*. Angezeigt wird dann die Datei davor, sonst die danach, sonst ein leeres Fenster |
| Datei → Zuletzt geöffnet | Die 20 zuletzt geöffneten Logdateien, soweit sie noch existieren. *Liste leeren* entfernt alle |
| Strg+S | Schema speichern unter… |
| Strg+G | Gehe zu Zeile |
| Zeitraum (dritte Filterzeile) | *Von* und *Bis* mit Kalender und Uhrzeit (Symbol rechts im Feld) oder getippt, z.B. `2026-11-19 08:15:30`, `19.11.2026 08:15`, `08:15` (Datum der ausgewählten Zeile) oder `2026-11-19T07:15:00Z`. Ein leeres Feld lässt die Seite offen. *Zusätzlich davor/danach* zeigt n Zeilen vor bzw. nach dem Zeitraum unabhängig von ihrer Zeit. Wirkt zusammen (UND) mit den anderen Filtern. Ohne Offset gilt die Zeitzone, in der die Zeitstempel-Spalte angezeigt wird (rechts angezeigt). *Gehe zu Von* springt ohne zu filtern. Über den Index werden je Grenze nur ca. 40 Zeilen gelesen, auch im Seek-Modus |
| Rechtsklick → Umliegender Zeitraum… | Übernimmt den Zeitstempel der Zeile. Wählbar: Zeitspanne (1 Min bis 12 h), Art (umliegend, nur davor, nur danach) und Zusatzzeilen davor/danach (Standard 10). Füllt Von/Bis und filtert; die Zeile bleibt ausgewählt |
| Datei → Tail / Schreiben folgen (Strg+T) | Liest angehängte Zeilen alle 0,5 s nach. Aktive Filter und Auswahlwerte werden fortgeschrieben. Steht die Ansicht am Ende, scrollt sie mit |
| Rechtsklick in der Tabelle | *Kopiere Zelle*: der Wert wie in der Detailansicht (formatiert, mit echten Zeilenumbrüchen und Unicode). *Kopiere als Markdown*: die ausgewählten Zeilen für Dokumentationen, je Zeile eine Überschrift, kurze Werte als Liste, mehrzeilige Werte (Nachricht, Stacktrace, JSON) als Codeblock. Außerdem Rohzeile(n) oder Zellen (Tab-getrennt) kopieren |
| Menü Schema | Laden, automatisch erkennen, speichern unter und die **20 zuletzt geöffneten Schemas**. Jedes geöffnete Schema wird nach `~/.beswinged/schemas/` kopiert; fehlt das Original, öffnet der Eintrag die Kopie |
| Menü Filter | *Speichern als…* legt Textfilter, Auswahl- und Level-Filter sowie Zeitraum unter einem Namen in `~/.beswinged/filters/<Name>.yaml` ab. Darunter die gespeicherten Filter zum Anwenden, unter *Löschen* zum Entfernen. Spalten werden über ihren Namen zugeordnet |
| Ansicht → Einstellungen… | Darstellung ganzer Zeilen je Loglevel: fett, kursiv, Schriftfarbe. Standard: WARN, ERROR und FATAL fett, TRACE dunkelgrau |
| Menü Hilfe | *Hilfe* (F1): Bedienungshilfe mit Tastenkürzeln. *Suche nach Updates…*: fragt bei GitHub das neueste Release ab und bietet bei einer neueren Version die Download-Seite an (es wird nur die Versionsnummer gesendet). *Über beswinged*: Version, Java-Laufzeit, Lizenzhinweis und der vollständige GPL-Text |

Alle Filter wirken gemeinsam (UND). *Zurücksetzen* in der Filterleiste leert alle.

Reihenfolge, Breite, Sichtbarkeit, Formatter, Spaltentyp und Auswahlfilter der Spalten werden automatisch in die geladene
Schema-Datei (YAML oder Properties) zurückgeschrieben. Der Kommentarblock am Anfang der Datei bleibt
erhalten, Kommentare weiter unten nicht. Beim automatisch erkannten Schema wird nichts gespeichert,
bis es einmal über *Schema speichern unter…* abgelegt wurde.

Zeitzone *Lokal* rechnet jeden Zeitstempel mit dem für sein Datum gültigen Offset um, also mit
Sommer- bzw. Winterzeit. Zeitstempel ohne Offset gelten als UTC.

Zeilen, die kein gültiges JSON sind, werden lila hinterlegt, mit dem Rohtext in der ersten Spalte.
Leere Zeilen werden übersprungen. Die Zeilennummer `#` zählt nur nicht-leere Zeilen.

## Text-Logs (kein JSON)

Beginnt die Mehrheit der Zeilen nicht mit `{`, wird das Format anhand der ersten Zeilen erkannt
und jeder Eintrag in Felder zerlegt, als wäre er JSON gewesen (Feldnamen wie beim
logstash-logback-encoder: `@timestamp`, `level`, `thread_name`, `logger_name`, `message`, `stack_trace`).
Stacktraces und andere Folgezeilen werden dem vorherigen Eintrag zugeordnet.

Eingebaut: Spring Boot 2/3, Logback, Log4j/Log4j2 (auch WildFly/JBoss), java.util.logging,
klog (Kubernetes), logfmt und als Rückfall *Text (allgemein)*. Das gilt für Dateien wie für
`kubectl logs`/`oc logs`.

*Schema → Logformat* zeigt das erkannte Format und erlaubt, es je Datei festzulegen.
*Schema → Import → logback.xml… / log4j2.xml…* liest eine Konfiguration ein, listet ihre Appender
mit Muster (auch `${…}`-Properties und die Spring-Boot-Standardmuster), testet das gewählte Muster
an der geöffneten Datei und legt es als eigenes Format ab. *Muster eingeben…* nimmt ein
PatternLayout direkt entgegen. Eigene Muster stehen in `settings.yaml` unter `logFormats`.

### Eigene Konverter

Ein Konverter implementiert `jsonlogviewer.format.LogFormat` (oder erbt von `AbstractLogFormat`,
die Stacktraces und Folgezeilen schon behandelt). Vorlage: `jsonlogviewer.format.example.ExampleFormat`.
Die Klasse wird in `META-INF/services/jsonlogviewer.format.LogFormat` eingetragen und als JAR nach
`~/.beswinged/formats/` gelegt oder auf den Classpath gesetzt
(`java -cp beswinged.jar:meinformat.jar jsonlogviewer.JsonLogViewer`, unter Windows `;` statt `:`).

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

Alles Benutzerbezogene liegt in `~/.beswinged/` (unter Windows `%USERPROFILE%\.beswinged`):

- `settings.yaml`: letztes Verzeichnis, Loglevel-Darstellung, zuletzt geöffnete Schemas, Vorgaben für *Umliegender Zeitraum*,
  offene Dateien mit Modus und Schema (`openFiles`, `activeFile`) und zuletzt geöffnete Dateien (`recentFiles`).
  Beim Start werden die offenen Dateien wiederhergestellt; eingelesen wird zunächst nur die aktive, die übrigen beim ersten Anzeigen
- `schemas/`: Kopien der zuletzt geöffneten Schemas (Cache)
- `filters/`: gespeicherte Filter, je Datei ein Filter
- `paste/`: mit *Datei → Log einfügen* angezeigte Texte.
- `kube/`: mit *Extras → Connect* geholte Logs (und je eine `.err.txt` mit der Fehlerausgabe von kubectl/oc).
  Die Cluster stehen in `settings.yaml` unter `clusters`, die letzten Eingaben des Connect-Dialogs unter `connect`

Mit `-Dbeswinged.home=<Ordner>` lässt sich ein anderes Verzeichnis verwenden.

## Pakete selbst bauen

`./dist.sh [Version]` (unter Windows in Git Bash) erzeugt mit `jlink` und `jpackage` aus dem JDK ein Paket
für das aktuelle Betriebssystem in `target/dist/`. Die Laufzeit enthält nur `java.base` und `java.desktop`
(ca. 50 MB entpackt, 33 MB als ZIP). GraalVM oder eine Oracle-Lizenz werden nicht gebraucht; ein OpenJDK-Build
wie Eclipse Temurin genügt.

Unter Windows entsteht zusätzlich der Installer `…-setup.exe`, wenn [NSIS](https://nsis.sourceforge.io/) installiert ist
(Skript: [packaging/windows/beswinged.nsi](packaging/windows/beswinged.nsi)). Er zeigt Liesmich und GPL auf einer
Seite zum Akzeptieren, installiert nach *Programme\beswinged*, legt den Startmenü-Eintrag, optional eine
Desktop-Verknüpfung, den Eintrag unter *Installierte Apps* und *App Paths* (Start mit Win+R → `beswinged`) an.
Ans Anheften in der Taskleiste kommt ein Installer unter Windows 10/11 nicht heran; das geht per Rechtsklick
auf das laufende Programm. Einstellungen in `~/.beswinged` bleiben bei der Deinstallation erhalten.

Die Version kommt aus `pom.xml` (`<revision>`) bzw. aus dem Tag; *Hilfe → Über beswinged* zeigt sie an.
Das Icon liegt als PNG in `src/main/resources/jsonlogviewer/icons/` und als `packaging/beswinged.ico`/`.png`.

Ein Tag `v*` startet den Workflow [release.yml](.github/workflows/release.yml), der auf Windows, Linux und macOS
baut und ein GitHub Release mit allen Paketen und der Jar anlegt:

```bat
git tag v1.0.0
git push origin v1.0.0
```

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
