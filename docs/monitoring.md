# Monitoring

Das Backend exponiert Prometheus-Metriken auf einem **eigenen Port** (Standard
`9090`, `METRICS_PORT`), der bewusst nicht über nginx/Traefik nach außen geht –
die Werte geben Sammlungswert, Ordnernamen und Fehlerzustände preis. Prometheus
erreicht den Endpunkt über das `traefik-network`, in dem beide Stacks liegen.

Kein zusätzliches Go-Modul: das Textformat wird in `backend/metrics.go` von Hand
geschrieben.

## Einrichtung

1. Der Service `backend` hängt in `docker-compose.yml` zusätzlich im
   `traefik-network` – in diesem Netz läuft auch der Monitoring-Stack, und
   Prometheus erreicht Container dort über ihren Namen. Traefik-Labels hat das
   Backend keine, es wird also nicht nach außen geroutet.
2. `docs/grafana/prometheus-scrape.yml` in die `prometheus.yml` übernehmen
   (Ziel: `magic-portal-backend:9090`).
3. `docs/grafana/alerts.yml` als Regeldatei einbinden.
4. `docs/grafana/mtg-portal-dashboard.json` in Grafana importieren und beim
   Import die Prometheus-Datenquelle wählen (Dashboard-UID `mtg-portal`).

Lokal testen: `docker compose -f docker-compose.local.yml up -d --build`, danach
`curl http://localhost:9091/metrics`.

## Konfiguration

| Variable | Standard | Bedeutung |
| --- | --- | --- |
| `METRICS_PORT` | `9090` | Port des Endpunkts; `off` schaltet ihn ab |
| `METRICS_CACHE_SECONDS` | `30` | Zwischenspeicher für die DB-Kennzahlen |

Der Cache ist kein Detail: der SQLite-Pool ist auf **eine** Verbindung begrenzt
(`openDB`), ein ungecachter Scrape würde sich die Verbindung mit dem
Hintergrund-Sync teilen. Scrape-Intervalle unter 30 s bringen entsprechend keine
neueren Werte.

## Metriken

### Sammlung
| Metrik | Typ | Bedeutung |
| --- | --- | --- |
| `mtg_collection_entries` | gauge | Zeilen in der Sammlung |
| `mtg_collection_cards` | gauge | Physische Karten (Summe Stückzahlen) |
| `mtg_collection_distinct_printings` | gauge | Verschiedene Druckausgaben |
| `mtg_collection_market_value_eur` | gauge | Marktwert, foil-bewusst wie im Dashboard |
| `mtg_collection_purchase_value_eur` | gauge | Einkaufswert |
| `mtg_collection_cards_without_price` | gauge | Karten ohne Scryfall-Treffer |
| `mtg_binder_market_value_eur{binder,type}` | gauge | Wert je Ordner/Liste |
| `mtg_binder_cards{binder,type}` | gauge | Karten je Ordner/Liste |

Ordnernamen sind Nutzerdaten: eine Umbenennung erzeugt eine neue Zeitreihe. Bei
sehr vielen Ordnern/Listen lohnt ein Blick auf die Serienzahl.

### Import, Sync, Backup
| Metrik | Typ | Bedeutung |
| --- | --- | --- |
| `mtg_imports_total{source,result}` | counter | Importläufe (`nextcloud`, `gdrive`, `upload`) |
| `mtg_import_rows_total{source}` | counter | Importierte CSV-Zeilen |
| `mtg_import_source_deleted_total{source}` | counter | Nach Import gelöschte Quelldateien |
| `mtg_remote_import_checks_total{source,result}` | counter | Prüfungen auf eine neue Quelldatei |
| `mtg_syncs_total{result}` | counter | Scryfall-Syncs |
| `mtg_backups_total{target,result}` | counter | Backupläufe |
| `mtg_last_remote_import_timestamp_seconds` | gauge | Zeitpunkt des letzten Imports (Unix) |
| `mtg_last_card_sync_timestamp_seconds` | gauge | Letzter Bulk-Sync |
| `mtg_last_sets_sync_timestamp_seconds` | gauge | Letzter Editions-Sync |
| `mtg_last_backup_timestamp_seconds` | gauge | Letztes Backup |
| `mtg_last_restore_timestamp_seconds` | gauge | Letzte Wiederherstellung |
| `mtg_last_value_snapshot_timestamp_seconds` | gauge | Letzter Wert-Snapshot |

Zeitstempel sind Unix-Sekunden; das Alter ist `time() - <metrik>`. Ein Wert von
`0` heißt „noch nie passiert" – in Alerts entsprechend abfangen.

`mtg_import_source_deleted_total` ist die Metrik für den Fall, der den Anlass
gab: Die Quelldatei wird **ausschließlich nach erfolgreichem Import** gelöscht.
Verschwindet die CSV in der Nextcloud, ohne dass dieser Zähler (und
`mtg_imports_total`) steigt, hat eine andere Instanz sie konsumiert.

### Zustand
| Metrik | Typ | Bedeutung |
| --- | --- | --- |
| `mtg_sync_in_progress` | gauge | 1 während ein Sync läuft |
| `mtg_sync_last_failed` | gauge | 1 nach fehlgeschlagenem Sync |
| `mtg_remote_import_last_failed` | gauge | 1 nach fehlgeschlagenem Import |
| `mtg_backup_last_failed` | gauge | 1 nach fehlgeschlagenem Backup |
| `mtg_remote_import_enabled` | gauge | 1 wenn eine Quelle konfiguriert ist |
| `mtg_backup_enabled` | gauge | 1 wenn ein Ziel konfiguriert ist |
| `mtg_activity_errors_24h` | gauge | Fehler im Aktivitätsprotokoll (24 h) |
| `mtg_catalog_cards`, `mtg_catalog_sets` | gauge | Umfang des Scryfall-Katalogs |
| `mtg_db_size_bytes` | gauge | Größe der SQLite-Datei |
| `mtg_backup_last_size_bytes` | gauge | Größe des letzten Backups |

### HTTP und Laufzeit
| Metrik | Typ | Bedeutung |
| --- | --- | --- |
| `mtg_http_requests_total{route,method,status}` | counter | API-Requests |
| `mtg_http_request_duration_seconds{route}` | histogram | Antwortzeiten |
| `go_goroutines`, `go_memstats_*`, `go_gc_cycles_total` | – | Go-Laufzeit |
| `process_start_time_seconds` | gauge | Startzeit, für die Uptime |

Das Label `route` ist auf die bekannten API-Pfade begrenzt, Set-Codes werden zu
`/api/sets/{code}/cards` zusammengefasst und alles Unbekannte zu `other` – ein
Scanner auf zufälligen Pfaden kann die Kardinalität also nicht aufblähen.

## Dashboard

`docs/grafana/mtg-portal-dashboard.json`, vier Bereiche:

- **Sammlung** – Markt-/Einkaufswert, Differenz, Kartenzahl, Wertverlauf, Top-10
  der Ordner nach Wert.
- **Import, Sync & Backup** – Alter der letzten Läufe, Importe gegen gelöschte
  Quelldateien, importierte Zeilen, Prüfraten, Fehlerzustände.
- **API** – Requests/s je Route, p95-Antwortzeit, Fehlerantworten.
- **System** – Laufzeit, Katalogumfang, DB-/Backup-Größe, Heap und Goroutinen.

Die Wertreihen stammen aus dem Scrape, nicht aus `value_snapshots`; die Historie
beginnt also mit dem ersten Scrape. Die langfristige Wertentwicklung bleibt im
Portal selbst (Dashboard-Seite), das aus den täglichen Snapshots zeichnet.
