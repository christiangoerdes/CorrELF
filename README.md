# CorrELF

CorrELF ist eine Spring-Boot-Anwendung zur Analyse und zum Vergleich von ELF-Dateien. Sie kombiniert mehrere 
Repräsentationen wie Hashes, Program-Header-Features und Code-Regionen, um Ähnlichkeiten zwischen Dateien zu bestimmen.

Das Projekt besteht aus zwei Hauptkomponenten: (1) die Implementierung der *Prototyp* in `/src` und (2) allen 
Skripten und Dateien, die für das *Parametertuning*, die *Erstellung des Evaluationsdatensatzes* und der *Validierung 
und Leistungsbewertung* genutzt wurden.

## Setup

### Voraussetzungen

* Java Development Kit (JDK) 21
* Apache Maven
* Laufende PostgreSQL-Instanz mit einer angelegten Datenbank `CorrELF`
* Gültige Datenbankzugangsdaten (Benutzername, Passwort)

Die Zugangsdaten werden in `src/main/resources/application.properties` konfiguriert. Alternativ
kann die dort kommentierte H2-In-Memory-Konfiguration aktivieren werden.

### JNI-basierte `coderec`-Integration

1. JNI-Library bauen:
   ```bash
   git clone https://github.com/christiangoerdes/coderec.git
   cd coderec
   curl --proto '=https' --tlsv1.2 -sSf https://valentinobst.de/a13f15d91f0f8846d748e42e7a881f783eb8f922861a63d9dfb74824d21337039dd8216f0373c3e5820c5e32de8f0a1880ec55456ff0da39f17d32f567d62b84/cpu_rec_corpus.tar.gz -o cpu_rec_corpus.tar.gz && tar xf cpu_rec_corpus.tar.gz && rm cpu_rec_corpus.tar.gz
   cargo build --release
   ```
2. Kompilierte JNI-Bibliothek unter `src/main/resources/coderec/` ablegen:
   ```
   coderec/coderec_jni.dll       # Windows
   coderec/coderec_jni.dylib     # macOS
   coderec/libcoderec_jni.so     # Linux
   ```

## Projektüberblick

| Pfad                                | Inhalt                                                                                                                                                                                                                                                                                                         |
|-------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `src/main/java/com/goerdes/correlf` | Anwendungscode. Enthält die Spring-Konfiguration (`CorrElfApplication`), HTTP-Einstiegspunkte unter `api`, Programmlogik in `services`, sowie Hilfskomponenten (`components`) für JNI-/MinHash-Anbindung, Datenmodelle (`model`), Datenbank-Entities und Repositories (`db`) und Utility-Funktionen (`utils`). |
| `src/main/resources`                | Konfigurationen der Anwendung, z.B. `application.properties` sowie Platz für die JNI-Libraries (`coderec/`).                                                                                                                                                                                                   |
| `src/test/java`                     | JUnit-Tests für Komponenten, Service-Layer und Evaluationtests, inklusive Test-Hilfsklassen unter `utils`.                                                                                                                                                                                                     |
| `data/`                             | Sämtliche Dateien und Skripte für die Erstellung des Evaluationsdatensatzes, der Evaluation und Visualisierung der Ergebnisse.                                                                                                                                                                                 |

## Datensatz

Der Datensatz besteht aus mehreren Teilen. 
Der finale Datensatz liegt in `data/dataset`.

### Buildroot-Datensatz und weitere Datensatz-Skripte (`data/scripts`)

Dieser Ordner enthält Skripte zur Erstellung des Buildroot-Datensatzes.
Zusätzlich befinden sich hier Skripte, um einen Datensatz nach Duplikaten zu durchsuchen und zu bereinigen.

| Skript                 | Zweck                                                                                                                                                      |
|------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `build.sh`             | Führt einen zufälligen Buildroot-Build inklusive BusyBox-Konfiguration aus und sammelt Rootfs, ELF-Binaries und relevante Konfigurationsdateien.           |
| `run_builds.sh`        | Wiederholt `build.sh` mehrfach, um mehrere zufällige Builds zu erzeugen.                                                                                   |
| `collect_elfs.py`      | Extrahiert ELF-Dateien aus den erzeugten Rootfs-Archiven in die jeweiligen Build-Verzeichnisse.                                                            |
| `collect_all_elfs.py`  | Aggregiert sämtliche extrahierten ELF-Dateien in einen gemeinsamen Datensatzordner (`dataset/Buildroot`) und ergänzt sie um ein Build-spezifisches Suffix. |
| `find_duplicates.py`   | Durchsucht einen Ordner nach identischen Dateien (Hash-basiert) und schreibt gefundene Duplikate auf die Konsole.                                          |
| `remove_duplicates.py` | Entfernt exakte Duplikate (Hash-basiert) in einem Ordner und protokolliert diese in `duplicate_cleaner_report.txt`.                                        |
| `split_dataset.py`     | Teilt einen Datensatz in Trainings- und Testmenge (70/30).                                                                                                 |
| `data_report.py`       | Erstellt eine Familienstatistik basierend auf Dateinamenpräfixen (vor `___`).                                                                              |

### Hersteller-Datensatz (`data/LFwC`)

In diesem Ordner befinden sich die Firmware-Images und ELF-Dateien der genutzten Herstellerfirmware. 
Zusätzlich liegt hier die Liste LFwC-korpus Liste zusammen mit Skripten um eine Auswahl von X Images zu wählen und 
Images aus dieser Auswahl zu ersetzen.

| Skript              | Zweck                                                                                                                                                                                            |
|---------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `select_samples.py` | Zieht aus dem LFwC-Korpus eine Stichprobe von 10 Herstellern mit jeweils 10 Firmware-Images, lädt deren Dateien herunter und speichert die Ergebnisse in aktualisierten CSV-Dateien              |
| `replace_sample.py` | Ersetzt in der bestehenden Stichprobe eine bestimmte Firmware-Datei durch eine neue, noch nicht verwendete Datei desselben Herstellers, lädt sie herunter und aktualisiert die CSV entsprechend. |
| `collect_elfs.py`   | Durchsucht alle extrahierten Firmware-Ordner nach ELF-Dateien und kopiert diese gesammelt in ein neues Unterverzeichnis                                                                          |

### OpenWRT-Datensatz (`data/openwrt`)

Dieser Ordner enthält den OpenWRT-Datensatz; zusammen mit den dafür verwendeten Skripten.

| Skript               | Zweck                                                                                                                                 |
|----------------------|---------------------------------------------------------------------------------------------------------------------------------------|
| `download_images.py` | Lädt zufällig ausgewählte OpenWrt-Firmwares herunter und speichert ihre Metadaten in einer CSV                                        |
| `extract.py`         | Entpackt alle `.bin`-Firmwaredateien aus dem Ordner `firmwares` mit binwalk in je ein eigenes Unterverzeichnis von `extracted_images` |
| `collect_efls.py`    | Durchsucht alle extrahierten Firmware-Ordner nach ELF-Dateien und kopiert diese gesammelt in ein neues Unterverzeichnis               |


## Evaluation und Visualisierung

Alle Skripte für das Parametertuning und die Visualisierungen befinden sich in Unterordnern von `/data/evaluation`.

### Training

| Skript                                    | Zweck                                                                                                                                                                                                                          |
|-------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `train/rep_threshold/preprocess.py`       | Wandelt Vergleichs-JSONs in ein partitioniertes Parquet-Dataset um.                                                                                                                                                            |
| `train/rep_threshold/find_thresholds.py`  | Berechnet für verschiedene Repräsentationen ROC-Kurven, AUC-Werte und optimale Schwellen, speichert die Ergebnisse in einer CSV und erzeugt eine ROC-Grafik                                                                    |
| `train/weight/preprocess.py`              | Liest das Parquet-Dataset ein, wendet für ausgewählte Repräsentationen feste Schwellenwerte an, wandelt die Scores in binäre Merkmale (0/1) um und speichert diese zusammen mit Label und Familie in ein neues Parquet-Dataset |
| `train/weight/find_weights.py`            | Trainiert aus binären Mustern Gewichte für drei Repräsentationen per Logistic Regression, normalisiert sie und speichert das Ergebnis als JSON-Modell.                                                                         |
| `train/final_threshold/find_threshold.py` | Kombiniert drei Repräsentationen mit festen Schwellenwerten und Gewichten zu einem Score, berechnet ROC-Kurve und optimalen Schwellenwert.                                                                                     |

### Test

| Skript                      | Zweck                                                                                                                                                                                                                     |
|-----------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `test/eval_all.py`          | Liest Vergleichs-JSONs mit `score_weighted`, bewertet sie bei einem festen Schwellwert, zählt TP/FP/TN/FN, berechnet Metriken wie TPR, FPR, etc. und schreibt das Gesamtergebnis in eine CSV                              |
| `test/eval_all_fp.py`       | Durchsucht Vergleichs-JSONs nach False Positives, zählt für jedes Quell-/Ziel-Paar die Treffer und speichert sie in eine CSV                                                                                              |
| `test/eval_by_family.py`    | Berechnet für ausgewählte Familien die Klassifikationsmetriken (TP, FP, TN, FN, TPR, FPR, etc.) bei einem festen Schwellenwert aus Vergleichs-JSONs und schreibt die Ergebnisse pro Familie plus Gesamtsumme in eine CSV. |
| `test/eval_by_family_fp.py` | Durchsucht Vergleichs-JSONs, zählt für jede gewählte Quell-Familie die FP und speichert die Ergebnisse als CSV-Tabelle                                                                                                    |
| `test/plot_roc.py`          | Zeichnet eine ROC-Kurve aus aggregierten CSV-Daten und legt die direkt aus dem Parquet-Datensatz berechnete ROC darüber, zeigt AUC- und Youden-J-Punkte und speichert den Vergleich als PNG                               |

Zusätzlich befinden sich unter `src/test/java/com/goerdes/correlf/evaluation` Java-Tests, die ebenfalls für die 
Evaluation genutzt wurden:

| Test                    | Zweck                                                                                                                                                                                                                                                                                                                                                                                                      |
|-------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `ExportTestDataTest`    | Exportiert für jede Eingabedatei Vergleichsdaten: Ähnlichkeiten der Repräsentationen, binäre Vektoren nach festen Schwellenwerten und einen gewichteten Gesamtscore. Ergebnisse werden als JSON gespeichert, dazu Laufzeiten (Vorbereitung, Vergleich, Serialisierung, Gesamt) gemessen und in eine CSV geschrieben. Bereits vorhandene valide JSONs werden übersprungen, die Verarbeitung läuft parallel. |
| `ExportTrainDataTest`   | Exportiert Trainingsdaten: Für jede Eingabedatei werden alle fünf Repräsentationen erzeugt, gegen die vorab geladenen DB-Dateien verglichen und die Ergebnisse als JSON gespeichert. Existierende Outputs werden übersprungen und fehlerhafte überschrieben. Die Verarbeitung läuft parallel, identische Dateinamen werden nicht verglichen.                                                               |
| `FamilyBatchExportTest` | Exportiert für eine ausgewählte Familie: Filtert Eingabedateien nach Namenspräfix, analysiert jede mit dem fileAnalysisService, schreibt die Ergebnisse als JSON in familien-spezifische Output-Verzeichnisse und protokolliert Erfolg/Fehlschlag pro Datei.                                                                                                                                               |
| `SimilarityTimingTest`  | Misst die Performance von Ähnlichkeitsvergleichen: Eine Referenzdatei wird eingelesen, ihre Repräsentationen (Strings, Code-Regionen, Program-Headers) extrahiert und gegen alle DB-Dateien verglichen. Für jeden Vergleich werden die Laufzeiten je Repräsentation erfasst, Durchschnittswerte berechnet und die langsamsten Vergleiche gelistet                                                          |

## API- & Service-Layer

### API-Nutzung
Die REST-API ist standardmäßig unter `http://localhost:8080/api` erreichbar.

#### `POST http://localhost:8080/api`
Lädt eine einzelne Binärdatei hoch, analysiert sie gegen den Datenbestand und liefert eine Liste von 
Vergleichsergebnissen.

```bash
curl -X POST http://localhost:8080/api \
  -F "file=@/path/to/binary"
```

#### `POST http://localhost:8080/api/compare`
Vergleicht zwei hochgeladene Dateien direkt miteinander und gibt das Ergebnis für dieses Paar zurück.

```bash
curl -X POST http://localhost:8080/api/compare \
  -F "file1=@/path/to/first-binary" \
  -F "file2=@/path/to/second-binary"
```

#### `POST http://localhost:8080/api/upload-zip`
Importiert ein ZIP-Archiv mit mehreren Binärdateien, extrahiert jede Datei und speichert sie in der Datenbank 
(verarbeitet mit Repräsentationen).

```bash
curl -X POST http://localhost:8080/api/upload-zip \
  -F "file=@/path/to/binaries.zip"
```