# MTG Portal

Ein Webportal zur Anzeige deiner Magic: The Gathering Sammlung aus einem ManaBox-CSV-Export.
Inspiriert von [mtg-collection-viewer](https://github.com/pnz1990/mtg-collection-viewer), aber als Docker-Container lauffähig.

## Features

- **Grid-Ansicht** – alle Karten als durchsuchbares, filterbares Raster (Set, Rarität, Farbe, Foil, Sortierung)
- **Binder-Ansicht** – Karten seitenweise wie in einem echten Ordner (9 pro Seite)
- **Karussell** – eine Karte nach der anderen, mit Pfeiltasten-Navigation
- **Statistik-Dashboard** – Charts zu Rarität, Farbverteilung, Sets, Foil-Anteil, Kaufwert über Zeit, wertvollste Karten
- **Deck-Checker** – Deckliste einfügen und sehen, welche Karten du schon besitzt

Kartenbilder, Preise, Typzeilen und Farben werden client-seitig über die [Scryfall API](https://scryfall.com/docs/api) nachgeladen und im `localStorage` des Browsers zwischengespeichert, damit sie nicht bei jedem Laden neu abgefragt werden.

## Starten

```bash
docker compose up -d --build
```

Danach ist das Portal unter [http://localhost:8080](http://localhost:8080) erreichbar.

## Sammlung aktualisieren

Einfach die Datei `app/data/ManaBox_Collection.csv` durch einen neuen ManaBox-Export ersetzen und die Seite neu laden – kein Rebuild nötig (die Datei ist per `docker-compose.yml` als Volume eingebunden).

Erwartete Spalten (Standard-ManaBox-Export): `Name, Set code, Set name, Collector number, Foil, Rarity, Quantity, Scryfall ID, Purchase price, Purchase price currency, Condition, Language, Added`.

## Projektstruktur

```
mtg-portal/
├── app/                  # nginx-Docroot (statische Site, kein Build-Schritt nötig)
│   ├── index.html        # Grid-Ansicht
│   ├── binder.html
│   ├── carousel.html
│   ├── dashboard.html
│   ├── deck-checker.html
│   ├── css/style.css
│   ├── js/
│   │   ├── shared.js     # CSV-Parsing, Scryfall-Anreicherung + Cache, Modal
│   │   ├── grid.js / binder.js / carousel.js / dashboard.js / deck-checker.js
│   └── data/ManaBox_Collection.csv
├── Dockerfile
└── docker-compose.yml
```

## Hinweis zum ersten Laden

Beim allerersten Laden müssen alle einzigartigen Karten einmal von Scryfall abgefragt werden (gebatcht, 75 pro Anfrage). Das kann bei größeren Sammlungen einen Moment dauern – ein Fortschrittsbalken zeigt den Status an. Danach greift der Browser-Cache und nachfolgende Ladevorgänge sind sofort da.
