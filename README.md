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

Noch nicht enthalten: Releases, Jira und Confluence, DDL-Abgleich, SSO. Die Anwender stehen bisher in der
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

Die Vorlage `ci/artifact-repo-ci.yml` prüft veröffentlichte Contracts mit der Data Contract CLI und Datenprodukte gegen
das ODPS-Schema.

## Erweitern

- `ArtifactGenerator`: weitere Dateien je Mapping erzeugen, zum Beispiel dbt
- `CatalogProvider`: weitere Metadatenquellen anbinden

Beans dieser Typen werden automatisch eingebunden.

## Tests

```sh
cd backend && mvn verify
cd frontend && npm test && npm run build
```

`PostgresMigrationTest` startet ein eingebettetes PostgreSQL und braucht kein Docker.
