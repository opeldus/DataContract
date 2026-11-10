# DataContract

Webanwendung für Mapping-Dokumente und Datenprodukte mit Review. Fachanwender pflegen beides im Browser, die Anwendung
prüft es und veröffentlicht freigegebene Stände als ODCS-Contract und ODPS-Datenprodukt in Git. Anwender brauchen dafür
keinen Git-Zugang.

## Stand

- Mapping-Grid mit Autovervollständigung, Einfügen aus Excel und Excel-Import
- Prüfung gegen den lokalen Katalog-Index (Quelle, Spalte, Typen, Dubletten, ODCS-Schema v3.2.0)
- Review mit Vier-Augen-Prinzip, Kommentaren je Regel und Verlauf
- Commit nach Git mit der Person als Author und dem Bot als Committer
- Katalog-Anbindung an DataHub über GraphQL
- Datenprodukte (ODPS v1.1.0) mit Input- und Output-Ports, die auf veröffentlichte Contract-Versionen zeigen
- Prüfung der Abhängigkeiten: fehlende oder nicht veröffentlichte Contracts, neuere Versionen, mehrere Lieferanten
  eines Contracts und Abhängigkeitskreise
- Abhängigkeitsgraph mit Auswirkungen je Element und Anzeige der Abnehmer am Mapping
- Fachlicher Vergleich mit der letzten veröffentlichten Version: Einstufung als inkompatibel, inhaltlich oder
  Dokumentation, passende Versionserhöhung und betroffene Datenprodukte
- Vergleich des Ziels mit DDL-Dateien aus Git, ohne Datenbankzugriff
- Jira Cloud: Autovervollständigung und Prüfung der Anforderungen, Links vom Vorgang zum Contract
- Confluence Cloud: generierte Seiten für Contracts und Datenprodukte
- Rückverfolgung von der Anforderung über Regel und Contract bis zum Datenprodukt
- Releases, die aufeinander aufbauen, mit automatischem Nachführen, Konfliktklärung und geplanten Spalten

Noch nicht enthalten: SSO. Die Anwender stehen bisher in der
Konfiguration.

## Lokal starten

Voraussetzungen: JDK 17 oder neuer, Maven, Node.js 24.

```sh
cd backend
git init --bare --initial-branch=main data/artifacts-remote.git
SPRING_PROFILES_ACTIVE=dev mvn spring-boot:run
```

```sh
cd frontend
npm install
npm run dev
```

Die Anwendung läuft dann auf <http://localhost:5173>. Das Profil `dev` nutzt H2, einen Demo-Katalog und diese Anwender:

| Anwender | Passwort | Rolle |
|---|---|---|
| `anna` | `anna` | Bearbeiten |
| `rene` | `rene` | Prüfen und veröffentlichen |

## Betrieb

Ohne Profil nutzt die Anwendung PostgreSQL (`docker-compose.yml`) und legt keine Anwender an. Lege sie unter
`app.security.users` mit BCrypt-Hashes an, siehe `application-dev.yml` für das Format.

| Variable | Bedeutung |
|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | PostgreSQL |
| `DATAHUB_URL`, `DATAHUB_TOKEN` | DataHub GraphQL; ohne URL bleibt der Katalog leer |
| `GIT_REMOTE_URL`, `GIT_BRANCH` | Artefakt-Repo; ohne URL bleiben Commits lokal |
| `GIT_USERNAME`, `GIT_TOKEN` | Zugang des technischen Git-Users über HTTPS |
| `GIT_WORKDIR`, `GIT_BOT_NAME`, `GIT_BOT_EMAIL` | Arbeitsverzeichnis und Committer |
| `UPLOAD_MAX_SIZE` | Größte Datei beim Import, Standard `500MB` |
| `DDL_REMOTE_URL`, `DDL_BRANCH`, `DDL_PATH` | Repo mit den DDL-Dateien (`*.sql`) und optional ein Ordner; ohne URL entfällt der Vergleich |
| `DDL_USERNAME`, `DDL_TOKEN`, `DDL_WORKDIR` | Lesezugang über HTTPS und Arbeitsverzeichnis |

Der DDL-Vergleich liest `CREATE`, `ALTER`, `DROP` und `RENAME TABLE` aus DB2 und Snowflake in der Reihenfolge der
Dateipfade, sodass auch Flyway-Migrationen funktionieren (`V2` vor `V10`). Was nicht verstanden wird, erscheint als
Warnung am DDL-Stand im Bereich „Prüfung“. Abweichungen zu einer vorhandenen Tabelle sperren das Einreichen, eine Tabelle
ohne DDL erzeugt nur einen Hinweis.

Die Vorlage `ci/artifact-repo-ci.yml` prüft veröffentlichte Contracts mit der Data Contract CLI und Datenprodukte gegen
das ODPS-Schema.

### Releases

Ein Release baut auf `main` oder einem anderen offenen Release auf und bekommt den Branch `release/<name>`. Es enthält nur
das, was es ändert, alles andere erbt es. Wird ein Stand veröffentlicht, führt die Anwendung ihn in abhängige Releases
nach: Ohne eigene Kopie schreibt der Bot die Dateien auf deren Branch, mit eigener Kopie wird je Feld zusammengeführt.
Widersprüche erscheinen als Konflikt in der Oberfläche und sperren das Einreichen, Git-Konflikte entstehen nicht.

Abschließen lässt sich ein Release, wenn alles darin veröffentlicht und sein Basisrelease abgeschlossen ist. Die Dateien
landen dann auf `main`, es entsteht der Tag `release/<name>`, und abhängige Releases bauen danach auf `main` auf. Der
DDL-Vergleich liest einen festen Branch, deshalb erzeugen Abweichungen in einem Release nur Hinweise.

### Jira und Confluence Cloud

| Variable | Bedeutung |
|---|---|
| `ATLASSIAN_URL` | Atlassian-Site, zum Beispiel `https://firma.atlassian.net`; ohne URL bleibt beides aus |
| `ATLASSIAN_EMAIL`, `ATLASSIAN_API_TOKEN` | Technischer Benutzer mit klassischem API-Token ohne Scopes |
| `CONFLUENCE_SPACE_KEY`, `CONFLUENCE_PARENT_PAGE_ID` | Zielbereich und optional die übergeordnete Seite; ohne Bereich entfällt Confluence |
| `APP_PUBLIC_URL` | Adresse dieser Anwendung; ohne sie entstehen keine Links in Jira |

Der technische Benutzer braucht in Jira die Rechte *Browse projects* und *Link issues*, in Confluence Schreibrechte im
Zielbereich. Jira bleibt für Vorgänge führend: Die Anwendung liest sie nur und aktualisiert ihre Kopie alle 15 Minuten.

Die Anwendung ruft Atlassian nur auf und braucht deshalb keine Verbindung von außen. Links und Seiten werden zusammen
mit der Veröffentlichung als Aufgabe gespeichert und danach mit Wiederholungen übertragen, ein Ausfall stört das
Veröffentlichen also nicht. Generierte Seiten tragen eine Kennung, eine fremde Seite mit gleichem Titel wird nie
überschrieben. Betrieben wird das bisher für genau eine laufende Instanz.

## Erweitern

- `ArtifactGenerator`: weitere Dateien je Mapping erzeugen, zum Beispiel dbt
- `CatalogProvider`: weitere Metadatenquellen anbinden
- `DdlSource`: DDL aus anderen Quellen lesen
- `IssueTracker`, `DocumentationPublisher`: andere Systeme für Anforderungen und Dokumentation anbinden

Beans dieser Typen werden automatisch eingebunden.

## Tests

```sh
cd backend && mvn verify
cd frontend && npm test && npm run build
```

`PostgresMigrationTest` startet ein eingebettetes PostgreSQL und braucht kein Docker.

## Lizenz

Apache License 2.0, siehe [LICENSE](LICENSE). Die Schemas in `backend/src/main/resources/schema` stammen aus den
Standards ODCS und ODPS des Bitol-Projekts (Linux Foundation) und stehen ebenfalls unter Apache 2.0.

## DataStage-Jobs analysieren

Der Bereich **DataStage** zeigt bestehende Jobs von IBM InfoSphere DataStage als Ablauf, als Sequenzdiagramm und als Verarbeitungskette über mehrere Jobs. Er liest nur und schreibt nichts nach DataStage. Gespeichert wird nur das Analyseergebnis mit Stages, Tabellen und SQL, nicht der Export selbst.

- **DSX** wird gelesen. Ein Export entsteht zum Beispiel mit `dsexport`, ein selbst geschriebenes Beispiel steht in `SampleDsx` unter den Tests. Exporte gehören nicht ins Repository, `.gitignore` schließt `*.dsx` und `*.isx` aus.
- **ISX** wird entpackt. Das innere XML ist nicht dokumentiert und wird deshalb noch nicht ausgewertet: Der Import listet solche Dateien mit ihrem Wurzelelement auf.
- **Grenzen:** Shared Container werden nicht aufgelöst. Tabellennamen müssen über Jobs hinweg gleich geschrieben sein, damit die Kette sie verbindet; Job-Parameter werden nicht eingesetzt. Dynamisch gebautes SQL wird nicht erkannt.
