# Monitoring

The backend exposes Prometheus metrics on a **separate port** (default `9090`,
`METRICS_PORT`) that deliberately does not go out through nginx/Traefik – the
values reveal collection value, binder names and error states. Prometheus
reaches the endpoint over the `traefik-network` both stacks share.

No extra Go module: the text format is written by hand in `backend/metrics.go`.

## Setup

1. The `backend` service additionally joins the `traefik-network` in
   `docker-compose.yml` – the monitoring stack lives in that network too, and
   Prometheus reaches containers there by name. The backend carries no Traefik
   labels, so it is not routed to the outside.
2. Copy `docs/grafana/prometheus-scrape.yml` into your `prometheus.yml`
   (target: `magic-portal-backend:9090`).
3. Wire up `docs/grafana/alerts.yml` as a rule file.
4. Import `docs/grafana/mtg-portal-dashboard.json` into Grafana and pick the
   Prometheus data source during the import (dashboard UID `mtg-portal`).

Testing locally: `docker compose -f docker-compose.local.yml up -d --build`,
then `curl http://localhost:9091/metrics`.

## Configuration

| Variable | Default | Meaning |
| --- | --- | --- |
| `METRICS_PORT` | `9090` | Port of the endpoint; `off` disables it |
| `METRICS_CACHE_SECONDS` | `30` | Cache for the database figures |

That cache is not a detail: the SQLite pool is limited to **one** connection
(`openDB`), so an uncached scrape would share that connection with the
background sync. Scrape intervals below 30 s therefore yield no fresher values.

## Metrics

### Collection
| Metric | Type | Meaning |
| --- | --- | --- |
| `mtg_collection_entries` | gauge | Rows in the collection |
| `mtg_collection_cards` | gauge | Physical cards (sum of quantities) |
| `mtg_collection_distinct_printings` | gauge | Distinct printings |
| `mtg_collection_market_value_eur` | gauge | Market value, foil-aware as in the dashboard |
| `mtg_collection_purchase_value_eur` | gauge | Purchase value |
| `mtg_collection_cards_without_price` | gauge | Cards with no Scryfall match |
| `mtg_binder_market_value_eur{binder,type}` | gauge | Value per binder/list |
| `mtg_binder_cards{binder,type}` | gauge | Cards per binder/list |

Binder names are user data: renaming one creates a new time series. With a lot
of binders/lists it is worth keeping an eye on the series count.

### Wishlist
| Metric | Type | Meaning |
| --- | --- | --- |
| `mtg_wishlist_items` | gauge | Entries on the wishlist |
| `mtg_wishlist_value_eur` | gauge | What the list would cost at the cheapest printing |
| `mtg_wishlist_targets_met` | gauge | Entries at or below their target price |

`mtg_wishlist_targets_met` is the notification channel for a reached target
price: alert on it going up rather than building a second mechanism into the
tool. A rule along the lines of

```promql
increase(mtg_wishlist_targets_met[1h]) > 0
```

fires once when a card falls below the price you set. The figures come from the
same aggregation as `/api/wishlist` (deliberately, so the alert and the page
can never disagree), which also means they exclude digital and memorabilia
sets - see `cheapestByName` in `backend/wishlist.go`.

### Import, sync, backup
| Metric | Type | Meaning |
| --- | --- | --- |
| `mtg_imports_total{source,result}` | counter | Import runs (`nextcloud`, `gdrive`, `upload`) |
| `mtg_import_rows_total{source}` | counter | Imported CSV rows |
| `mtg_import_source_deleted_total{source}` | counter | Source files deleted after an import |
| `mtg_remote_import_checks_total{source,result}` | counter | Checks for a new source file |
| `mtg_syncs_total{result}` | counter | Scryfall syncs |
| `mtg_backups_total{target,result}` | counter | Backup runs |
| `mtg_last_remote_import_timestamp_seconds` | gauge | Time of the last import (Unix) |
| `mtg_last_card_sync_timestamp_seconds` | gauge | Last bulk sync |
| `mtg_last_sets_sync_timestamp_seconds` | gauge | Last set sync |
| `mtg_last_backup_timestamp_seconds` | gauge | Last backup |
| `mtg_last_restore_timestamp_seconds` | gauge | Last restore |
| `mtg_last_value_snapshot_timestamp_seconds` | gauge | Last value snapshot |

Timestamps are Unix seconds; the age is `time() - <metric>`. A value of `0`
means "never happened" – catch that in alerts accordingly.

`mtg_import_source_deleted_total` is the metric for the incident that prompted
all this: the source file is deleted **only after a successful import**. If the
CSV disappears from Nextcloud without this counter (and `mtg_imports_total`)
going up, another instance has consumed it.

### State
| Metric | Type | Meaning |
| --- | --- | --- |
| `mtg_sync_in_progress` | gauge | 1 while a sync is running |
| `mtg_sync_last_failed` | gauge | 1 after a failed sync |
| `mtg_remote_import_last_failed` | gauge | 1 after a failed import |
| `mtg_backup_last_failed` | gauge | 1 after a failed backup |
| `mtg_remote_import_enabled` | gauge | 1 when a source is configured |
| `mtg_backup_enabled` | gauge | 1 when a target is configured |
| `mtg_activity_errors_24h` | gauge | Errors in the activity log (24 h) |
| `mtg_catalog_cards`, `mtg_catalog_sets` | gauge | Size of the Scryfall catalog |
| `mtg_db_size_bytes` | gauge | Size of the SQLite file |
| `mtg_image_mirror_enabled` | gauge | 1 when the local image mirror is active |
| `mtg_images_mirrored` | gauge | Number of locally mirrored files |
| `mtg_images_bytes` | gauge | Disk space the mirror occupies |
| `mtg_last_image_sync_timestamp_seconds` | gauge | Last mirror run |
| `mtg_image_downloads_total{result}` | counter | Downloaded images by result |
| `mtg_hash_index_enabled` | gauge | 1 when the scanner's image hash index is active |
| `mtg_card_hashes` | gauge | Printings in the hash index |
| `mtg_hash_sync_in_progress` | gauge | 1 while the hash index is being built |
| `mtg_last_hash_sync_timestamp_seconds` | gauge | Last hash index run |
| `mtg_hash_scans_total{result}` | counter | Hashed scans: `mirror`, `download` or `error` |
| `mtg_app_writes_total{op}` | counter | Collection writes from the Android app: `add`, `update`, `move`, `merge`, `delete`, `export`, `confirm` |
| `mtg_backup_last_size_bytes` | gauge | Size of the last backup |

### HTTP and runtime
| Metric | Type | Meaning |
| --- | --- | --- |
| `mtg_http_requests_total{route,method,status}` | counter | API requests |
| `mtg_http_request_duration_seconds{route}` | histogram | Response times |
| `go_goroutines`, `go_memstats_*`, `go_gc_cycles_total` | – | Go runtime |
| `process_start_time_seconds` | gauge | Start time, for uptime |

The `route` label is restricted to the known API paths, set codes are folded
into `/api/sets/{code}/cards` and anything unknown becomes `other` – so a
scanner hitting random paths cannot inflate the cardinality.

## Dashboard

`docs/grafana/mtg-portal-dashboard.json`, four sections:

- **Collection** – market/purchase value, difference, card count, value over
  time, top 10 binders by value.
- **Import, sync & backup** – age of the last runs, imports against deleted
  source files, imported rows, check rates, error states.
- **API** – requests/s per route, p95 response time, error responses.
- **System** – uptime, catalog size, database/backup size, heap and goroutines.

The value series come from the scrape, not from `value_snapshots`, so their
history starts with the first scrape. Long-term value development stays in the
portal itself (the statistics page), which draws from the daily snapshots.
