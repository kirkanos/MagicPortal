# MTG Portal

Ein Webportal zur Anzeige deiner Magic: The Gathering Sammlung aus einem ManaBox-CSV-Export.
Inspiriert von [mtg-collection-viewer](https://github.com/pnz1990/mtg-collection-viewer), lauffähig als Docker-Stack mit eigenem Backend und Datenbank.

## Features

- **Karten-Ansicht** – alle Karten als durchsuchbares, filterbares Raster (Set, Rarität, Farbe, Foil, Sortierung). Gleiche Karten in verschiedenen Sprachen werden zu einer Kachel zusammengefasst (Gesamtanzahl + Sprachflaggen).
- **Editionen** – Überblick pro Set: besessen / fehlt / mehrfach, inkl. Editionen, aus denen du noch keine Karte hast. Klick öffnet ein Overlay mit allen Karten (fehlende ausgegraut) und dem Wert der fehlenden Karten.
- **Statistik-Dashboard** – Charts zu Rarität, Farbverteilung, Sets, Foil-Anteil, Kaufwert über Zeit, wertvollste Karten und Editionen nach Marktwert.
- **Deck-Checker** – Deckliste einfügen und sehen, welche Karten du schon besitzt.
- **CSV-Upload** – neue ManaBox-Exporte direkt im Menü hochladen (passwortgeschützt); neue Karten werden angelegt, bestehende aktualisiert (Upsert).

## Architektur

```text
┌── web (nginx) ───────┐   statische Oberfläche + Reverse-Proxy /api → backend
└─────────┬────────────┘
          │ /api
┌─────────▼────────────┐   Go-Backend: REST-API + Background-Jobs
│  backend (Go)        │   (SQLite-Datei unter ./data-db)
└──────────────────────┘
```

- Kartenmetadaten, Bilder und **Preise** kommen aus **Scryfall Bulk Data** – ein täglich aktualisierter Komplettdump, den das Backend im Hintergrund herunterlädt und in SQLite ablegt (statt tausender Einzel-API-Calls im Browser).
- Background-Jobs beim Start und danach: Set-Index (wöchentlich) und Karten/Preise (täglich) werden automatisch aktualisiert.
- Die Oberfläche lädt alles fertig angereichert aus der API – schnelle, für alle geteilte Ladezeiten.

## Starten

```bash
docker compose up -d --build
```

Portal danach unter [http://localhost:8080](http://localhost:8080). Der Stack startet mit **leerem Datenbestand** – lade deine ManaBox-CSV über das Menü hoch (siehe unten). Die Scryfall-Kartendaten/Preise werden beim Start im Hintergrund geladen.

## Sammlung befüllen & aktualisieren

Der Stack startet mit **leerem Datenbestand**. Sammlung über die Oberfläche befüllen: oben rechts „🔒 Upload freischalten" → Passwort → „⬆ CSV hochladen". Neue Karten werden hinzugefügt, bestehende aktualisiert (Upsert). „Leeren" entfernt die gesamte Sammlung wieder.

Erwartete Spalten (Standard-ManaBox-Export): `Name, Set code, Set name, Collector number, Foil, Rarity, Quantity, Scryfall ID, Purchase price, Purchase price currency, Condition, Language, Added`.

## Konfiguration

- **Passwort:** in `.env` setzen (`UPLOAD_PASSWORD=…`); wird von `docker-compose.yml` eingelesen. Leer = kein Passwortschutz. Schützt Upload/Reset/Sync. Übertragung via `X-Upload-Password`-Header (kein Browser-Login-Dialog). Vorlage: `.env.example`.
- **Datenbank:** benanntes Docker-Volume `mtg-db` (persistent, schreibbar für den Non-Root-Backend-User).

## Aktualisieren & Status

- Der **Datenstand** (letzte Aktualisierung der Kartendaten/Preise) wird oben rechts im Menü angezeigt.
- Button **„🔄 Aktualisieren"** (nach Passwort-Freischaltung) stößt einen sofortigen Scryfall-Sync an; während des Laufs zeigt die Statusanzeige einen Spinner, danach lädt die Seite mit frischen Preisen neu.
- Automatisch: Set-Index wöchentlich, Karten/Preise täglich (im Hintergrund).

## Hardening

Beide Container laufen mit `read_only`-Rootfs, `cap_drop: ALL` (web nur mit den nötigen nginx-Caps), `no-new-privileges` und Speicher-/PID-Limits. Das Backend läuft als Non-Root-User (uid 10001). Der Backend-Port ist nicht nach außen gemappt (nur über den nginx-Proxy erreichbar).

## API-Endpunkte

| Methode | Pfad | Zweck |
|--------|------|-------|
| GET | `/api/collection` | Sammlung, angereichert mit Scryfall-Daten |
| GET | `/api/sets` | Set-Index |
| GET | `/api/sets/{code}/cards` | Alle Karten eines Sets |
| GET | `/api/status` | Sync-Status & Datenstand |
| GET | `/api/auth-check` | Passwortprüfung (204/403) |
| POST | `/api/upload` | CSV hochladen (Upsert) |
| POST | `/api/reset` | Sammlung zurücksetzen |
| POST | `/api/sync` | Scryfall-Sync jetzt anstoßen |

## Projektstruktur

```text
mtg-portal/
├── app/                  # nginx-Docroot (statische Oberfläche)
│   ├── index.html        # Karten-Ansicht
│   ├── editions.html · dashboard.html · deck-checker.html
│   ├── css/style.css
│   └── js/shared.js · grid.js · editions.js · dashboard.js · deck-checker.js
├── backend/              # Go-Backend (API + Sync-Jobs)
│   ├── main.go · db.go · csvimport.go · scryfall.go
│   └── Dockerfile
├── nginx/default.conf    # statisch + Proxy /api → backend
├── Dockerfile            # web-Image
├── .env                  # UPLOAD_PASSWORD (nicht eingecheckt)
└── docker-compose.yml    # Volume "mtg-db" = SQLite-Datenbank
```
