# Roadmap

Drei geplante Arbeiten, in dieser Reihenfolge. Die Zahlen stammen aus Messungen
an der echten Sammlung (Stand 12.10.2026: 15.674 Einträge, 13.749 verschiedene
Drucke, 31.721 Karten).

Was bereits erledigt ist und warum, steht in der Git-Historie – kurz:
`/api/dashboard` aggregiert serverseitig (2,4 MB → 1,8 KB), `/api/collection`
und `/api/sets` beantworten bedingte Anfragen mit 304, und die Bild-URLs werden
im Browser aus der Scryfall-ID gebaut statt übertragen (14,8 → 10,0 MB).

---

## 1. Suche ins Backend — erledigt

> Umgesetzt in `backend/cards.go` und `app/js/grid.js`. Das Kartenraster lädt
> die Sammlung nicht mehr; der Erstaufbau fiel von 2,36 MB auf rund 46 KB.
> `oracleText` ist aus `/api/collection` verschwunden (2,36 → 1,32 MB gzip),
> die Seite nutzen nur noch `editions.js` und `deck-checker.js`.
>
> Offen geblieben: `/api/prints` (die „weiteren Editionen" im Detaildialog)
> liefert weiterhin fertige Bild-URLs und läuft damit am Bild-Spiegel vorbei.

### Warum

Der Oracle-Text ist mit 2,24 MB der letzte große Posten in `/api/collection`.
Streichen lässt er sich nicht, weil `grid.js` ihn durchsucht:

```js
const hay = (c.name + ' ' + (sc.oracleText || '') + ' ' +
             String(sc.manaCost || '').replace(/[{}\/]/g, '')).toLowerCase();
```

Der eigentliche Gewinn ist größer als die 2,24 MB: Läuft die Suche im Backend,
lädt `index.html` die Sammlung gar nicht mehr, sondern eine Seite Treffer.

### Endpunkt

`GET /api/cards` mit genau den Möglichkeiten, die die Oberfläche heute hat:

| Parameter | entspricht |
| --- | --- |
| `q` | Suchfeld: Name + Oracle-Text + Manakosten ohne `{}` und `/`, mehrere Begriffe UND-verknüpft |
| `set`, `rarity`, `color`, `foil`, `binder`, `type`, `subtype` | die sieben Filter-Auswahlen |
| `sort` | Name, Preis, Anzahl, Hinzugefügt, Edition |
| `cursor`, `limit` | Keyset-Paging, passend zum inkrementellen Rendern aus `f5f3632` |

Die Antwort enthält bereits **gruppierte** Zeilen – ein Eintrag je Druck mit
Sprachen, Gesamtanzahl, Preis und Repräsentant, also dieselbe Gruppierung, die
`grid.js` heute im Browser bildet – dazu `total` und `totalGroups` für die
Zähler über dem Raster.

### Schritte

1. Gruppierung und Filter als SQL, zunächst mit `LIKE`. Gegen 110.000
   Katalogzeilen messen; erst wenn das nicht reicht, FTS5 (in
   `modernc.org/sqlite` enthalten) über Name, Oracle-Text und Typzeile.
2. Normalisierte Spalte für die Manakosten (`{1}{R}` → `1r`), sonst läuft bei
   jeder Abfrage ein `replace()` über alle Zeilen.
3. `grid.js` auf den Endpunkt umstellen: Eingabe entprellen (250 ms),
   Filterwechsel = eine Abfrage, Scrollen = nächster Cursor.
4. Erst danach `oracleText` aus `/api/collection` entfernen und
   `payloadVersion` in `httpcache.go` hochzählen.

### Absicherung

Wie beim Dashboard: die heutige JS-Filterlogik als Referenz über den echten
Datenbestand laufen lassen und die Trefferlisten von rund 30
Parameterkombinationen gegen die SQL-Variante diffen. Dort fallen die
Feinheiten auf – Groß-/Kleinschreibung, Umlaute, leere Felder, `//`-Namen bei
doppelseitigen Karten.

### Preis

Filtern ist heute instant, weil alles lokal liegt. Danach kostet jeder
Filterwechsel einen Roundtrip, gemessen **~60 ms** bei offener Verbindung.
Dafür fällt der Erstaufbau von mehreren Sekunden auf Millisekunden, und auf dem
Handy verschwindet der Speicherbedarf für 15.000 Kartenobjekte.

Aufwand: Backend ~300 Zeilen, `grid.js` ~200 geänderte Zeilen, plus
Vergleichslauf.

---

## 2. Bilder und Symbole spiegeln — erledigt

> Umgesetzt in `backend/images.go`, `nginx/default.conf` und `shared.js`.
> Standard ist `IMAGE_MIRROR=false`; eingeschaltet laeuft der Abgleich taeglich
> um `IMAGE_SYNC_HOUR`. `/api/sets/{code}/cards` liefert seitdem ebenfalls IDs
> statt Bild-URLs, und das Editionen-Overlay nutzt denselben Flip wie der
> Kartendialog.

### Ablage

Eigenes Volume `mtg-images:/images`, **nicht** im DB-Volume – sonst wandern
1,6 GB in jedes Backup. Struktur wie bei Scryfall:

```
/images/cards/<size>/<face>/<1>/<2>/<id>.jpg
/images/sets/<code>.svg
/images/symbols/<code>.svg
```

Größe, gerechnet auf die aktuelle Sammlung: 13.749 Drucke × (~100 KB `normal` +
~20 KB `small`) ≈ **1,6 GB**. Dazu 1.056 Editionssymbole und ~90 Mana-Symbole
als SVG, zusammen unter 10 MB. Die 80 Rückseiten fallen nicht ins Gewicht.

### Ausliefern: lokal zuerst, sonst Scryfall

Eine Existenzprüfung je Bild im Browser hieße ein zusätzlicher Request pro
Karte – das kostet mehr, als der Spiegel spart. Die Entscheidung fällt deshalb
pro Datei in nginx, ohne dass die Oberfläche etwas davon merkt:

```nginx
location /img/ {
    root /srv/images;
    try_files $uri @scryfall;   # lokal vorhanden -> direkt ausliefern
    expires 30d;
}
location @scryfall {            # sonst transparent von Scryfall
    proxy_pass https://cards.scryfall.io;
    proxy_cache images;
}
```

Die Oberfläche fragt immer `/img/...`. Der **Schalter** liegt in `/api/config`
(`imageMirror: true|false`) und steuert nur, welche Basis-URL
`scryfallImageURL()` in `shared.js` baut – bei `false` geht alles wie heute
direkt an Scryfall. Damit ist der Spiegel jederzeit abschaltbar, ohne
Datenverlust und ohne Deploy.

### Hintergrund-Sync

Ein eigener Scheduler neben Sync, Import und Backup.

| Variable | Standard | Zweck |
| --- | --- | --- |
| `IMAGE_MIRROR` | `false` | Schalter; aus = alles wie bisher |
| `IMAGE_SYNC_HOUR` | `3` | Startzeit, einmal täglich |
| `IMAGE_SIZES` | `small,normal` | welche Größen gespiegelt werden |
| `IMAGE_RATE_PER_SECOND` | `10` | Schonung für Scryfall |
| `IMAGE_MAX_BYTES` | `3221225472` | Notbremse gegen eine volle Platte |

Ablauf: alle besessenen Drucke ermitteln, je Größe und Seite prüfen, was fehlt,
den Rest mit Ratenbegrenzung laden – erst nach `.tmp`, dann umbenennen, damit
ein Abbruch keine halben Dateien hinterlässt. Eine Tabelle
`image_files (id, face, size, ts, bytes, fetched_at)` merkt sich den
Cache-Buster aus der Katalog-URL; ändert Scryfall ein Artwork, wird genau diese
Datei neu geholt. Erster Lauf: ~27.500 Dateien, bei 10/s rund 45 Minuten –
nachts unkritisch, danach nur noch Neuzugänge.

### Damit es nicht still kaputtgeht

Die Lehre aus dem Import-Vorfall im September:

- Metriken über den vorhandenen Exporter: `mtg_images_mirrored`,
  `mtg_images_bytes`, `mtg_image_sync_last_timestamp_seconds`,
  `mtg_image_downloads_total{result}` – plus Panel und Alert im Grafana-Repo.
- Einträge im Aktivitätsprotokoll wie bei Sync und Backup.
- Manueller Anstoß über `POST /api/images/sync` (passwortgeschützt).
- Aufräumen nicht mehr besessener Bilder standardmäßig **aus**: Ein Import
  ersetzt die Sammlung komplett, ein Fehlimport würde sonst den halben Spiegel
  löschen.

### Reihenfolge

Schalter, nginx-Fallback und Ausliefern zuerst – damit kann man bei leerem
Volume schon live gehen, es passiert schlicht nichts. Danach der Sync-Job,
zuletzt Symbole und Metriken.

Scryfall erlaubt das Zwischenspeichern der Bilder ausdrücklich, bittet aber um
moderate Abrufraten (daher die Ratenbegrenzung) und um Namensnennung, die im
Footer bereits steht.

---

## 3. Doppelseitige Karten umdrehen — erledigt

> Umgesetzt in `shared.js` (`cardFaceHTML`, `initCardFlip`) und `style.css`.
> Das Editionen-Overlay zeigt die Rückseite weiterhin als zweites Bild; es
> wird mit Punkt 2 nachgezogen, wenn `/api/sets/{code}/cards` ohnehin auf
> abgeleitete URLs umgestellt wird.

### Vorher

`cardGroupModalHTML()` in `shared.js` rendert bei einer doppelseitigen Karte
beide Bilder untereinander in dieselbe Spalte. Das ist unübersichtlich, kostet
viel Höhe und zeigt die Karte nicht so, wie man sie in der Hand hält. Betroffen
sind aktuell 80 Einträge – Transformer-Karten, Modal-DFCs, Sagas mit Rückseite.

### Ziel

Ein Kartenbild, das sich umdrehen lässt: Klick auf das Bild oder auf eine
kleine Schaltfläche („↻ Umdrehen" / „↻ Flip") dreht es mit einer kurzen
3D-Animation auf die Rückseite und zurück.

### Umsetzung

Rein im Frontend, kein Backend-Anteil – das Flag `hasBack` und die ableitbare
Rückseiten-URL gibt es seit dem Umbau der Nutzlast schon.

```html
<div class="card-flip" data-flipped="false">
  <img class="face front" src="…/front/…">
  <img class="face back"  src="…/back/…">
</div>
```

```css
.card-flip { perspective: 1200px; }
.card-flip .face { backface-visibility: hidden; transition: transform .5s; }
.card-flip .back { transform: rotateY(180deg); position: absolute; inset: 0; }
.card-flip[data-flipped="true"] .front { transform: rotateY(180deg); }
.card-flip[data-flipped="true"] .back  { transform: rotateY(360deg); }
```

Details, die den Unterschied machen:

- **Nur wo es eine Rückseite gibt.** Ohne `hasBack` bleibt es beim heutigen
  einfachen `<img>`, ohne Schaltfläche und ohne Klickverhalten.
- **Rückseite erst bei Bedarf laden** (`loading="lazy"` reicht nicht, weil das
  Bild im DOM sichtbar ist): `src` erst beim ersten Umdrehen setzen. Spart bei
  jedem Öffnen einer solchen Karte ~100 KB.
- **Tastatur und Vorlesen:** Die Schaltfläche ist ein echter `<button>` mit
  `aria-pressed`, das Bild bekommt ein sprechendes `alt` je Seite.
- **Bewegung respektieren:** bei `prefers-reduced-motion: reduce` ohne
  Animation umschalten.
- **Zweisprachig** über das vorhandene `t()`, wie der Rest der Oberfläche.
- **Auch im Editionen-Overlay** (`editions.js` zeigt über
  `/api/sets/{code}/cards` ebenfalls Rückseiten) dieselbe Komponente nutzen,
  damit es nur eine Stelle gibt.

Aufwand: überschaubar, ~60 Zeilen JS und ~20 Zeilen CSS. Gut geeignet als
Zwischenstück zwischen den beiden größeren Punkten.
