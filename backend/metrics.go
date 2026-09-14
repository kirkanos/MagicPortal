package main

import (
	"fmt"
	"log"
	"net/http"
	"os"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// Prometheus metrics in the text exposition format, written by hand – the
// backend stays dependency-free (see go.mod) and the handful of series here
// doesn't justify pulling in client_golang.
//
// Served on a SEPARATE listener (METRICS_PORT, default 9090) that is not routed
// through nginx/Traefik: the numbers expose collection value and error states,
// so Prometheus reaches them over the Docker network only.
//
// Two kinds of series:
//   - process counters/histograms, updated inline where things happen (imports,
//     backups, syncs, HTTP requests) and reset on restart;
//   - gauges derived from the database, collected on scrape behind a short TTL
//     cache. The DB pool is limited to a single connection (openDB), so an
//     uncached scrape would compete with the background sync for it.

const metricsNamespace = "mtg"

// dbFilePath is remembered at startup so the DB file size can be reported.
var dbFilePath string

// ---- counters ----

// counterVec is a set of label-keyed counters. Label combinations are created
// on first use; all call sites use fixed, low-cardinality label values.
type counterVec struct {
	mu   sync.Mutex
	vals map[string]float64
}

func newCounterVec() *counterVec { return &counterVec{vals: map[string]float64{}} }

func (c *counterVec) add(labels string, v float64) {
	c.mu.Lock()
	c.vals[labels] += v
	c.mu.Unlock()
}

func (c *counterVec) inc(labels string) { c.add(labels, 1) }

// snapshot returns the current values, sorted for stable scrape output.
func (c *counterVec) snapshot() []labelledValue {
	c.mu.Lock()
	out := make([]labelledValue, 0, len(c.vals))
	for l, v := range c.vals {
		out = append(out, labelledValue{l, v})
	}
	c.mu.Unlock()
	sort.Slice(out, func(i, j int) bool { return out[i].labels < out[j].labels })
	return out
}

type labelledValue struct {
	labels string
	value  float64
}

// histogram is a fixed-bucket latency histogram (seconds), per label set.
type histogram struct {
	mu      sync.Mutex
	buckets []float64
	series  map[string]*histSeries
}

type histSeries struct {
	counts []uint64
	sum    float64
	count  uint64
}

func newHistogram(buckets []float64) *histogram {
	return &histogram{buckets: buckets, series: map[string]*histSeries{}}
}

func (h *histogram) observe(labels string, seconds float64) {
	h.mu.Lock()
	defer h.mu.Unlock()
	s := h.series[labels]
	if s == nil {
		s = &histSeries{counts: make([]uint64, len(h.buckets))}
		h.series[labels] = s
	}
	for i, ub := range h.buckets {
		if seconds <= ub {
			s.counts[i]++
		}
	}
	s.sum += seconds
	s.count++
}

var (
	// Imports: source is "nextcloud", "gdrive" or "upload"; result "success"/"error".
	metricImports    = newCounterVec()
	metricImportRows = newCounterVec()
	// Deletions of the source file after a successful remote import. Makes the
	// "CSV vanished but nothing was imported" case visible from the outside:
	// a deletion without a matching successful import cannot happen here, so a
	// vanishing file with a flat counter means another instance consumed it.
	metricImportSourceDeleted = newCounterVec()
	metricRemoteChecks        = newCounterVec()

	metricBackups = newCounterVec()
	metricSyncs   = newCounterVec()

	metricHTTPRequests = newCounterVec()
	metricHTTPDuration = newHistogram([]float64{0.005, 0.025, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30})

	// Last backup payload size, set on every successful backup.
	metricBackupBytes atomic.Int64

	processStart = time.Now()
)

// ---- HTTP instrumentation ----

// apiRoutes is the allowlist of route labels. Anything unknown collapses into
// "other" so a scanner hitting random paths can't blow up label cardinality.
var apiRoutes = map[string]string{
	"collection": "/api/collection", "sets": "/api/sets", "prints": "/api/prints",
	"binders": "/api/binders", "subtypes": "/api/subtypes", "status": "/api/status",
	"summary": "/api/summary", "config": "/api/config", "reserved": "/api/reserved",
	"value-history": "/api/value-history", "value-movers": "/api/value-movers",
	"activity": "/api/activity", "backup": "/api/backup", "auth-check": "/api/auth-check",
	"upload": "/api/upload", "reset": "/api/reset", "sync": "/api/sync", "health": "/api/health",
}

// routeLabel maps a request path to a bounded label value.
func routeLabel(path string) string {
	rest := strings.TrimPrefix(path, "/api/")
	if rest == path { // not an /api/ path at all
		return "other"
	}
	head, tail, _ := strings.Cut(rest, "/")
	switch head {
	case "sets":
		if tail != "" {
			return "/api/sets/{code}/cards" // collapse the set code
		}
	case "activity":
		if tail == "clear" {
			return "/api/activity/clear"
		}
	case "backup":
		switch tail {
		case "restore-latest", "restore-upload":
			return "/api/backup/" + tail
		}
	}
	if r, ok := apiRoutes[head]; ok && tail == "" {
		return r
	}
	return "other"
}

// statusRecorder captures the status code for the request counter.
type statusRecorder struct {
	http.ResponseWriter
	status int
}

func (s *statusRecorder) WriteHeader(code int) {
	s.status = code
	s.ResponseWriter.WriteHeader(code)
}

func (s *statusRecorder) Write(b []byte) (int, error) {
	if s.status == 0 {
		s.status = http.StatusOK
	}
	return s.ResponseWriter.Write(b)
}

// instrument wraps the API mux: one counter per route/method/status and a
// latency histogram per route.
func instrument(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		rec := &statusRecorder{ResponseWriter: w}
		next.ServeHTTP(rec, r)
		if rec.status == 0 {
			rec.status = http.StatusOK
		}
		route := routeLabel(r.URL.Path)
		metricHTTPRequests.inc(labels("route", route, "method", r.Method, "status", strconv.Itoa(rec.status)))
		metricHTTPDuration.observe(labels("route", route), time.Since(start).Seconds())
	})
}

// ---- database-derived gauges ----

type dbGauges struct {
	entries, cards, distinct       int64
	unpriced                       int64
	marketEUR, purchaseEUR         float64
	catalogCards, catalogSets      int64
	activityErrors24h              int64
	binders                        []binderGauge
	lastImport, lastSync, lastSets float64
	lastBackup, lastRestore        float64
	lastSnapshot                   float64
	importErr, syncErr, backupErr  float64
	dbBytes                        float64
}

type binderGauge struct {
	name, binderType string
	cards, entries   int64
	marketEUR        float64
}

var (
	gaugeMu       sync.Mutex
	gaugeCache    *dbGauges
	gaugeCachedAt time.Time
)

// metricsCacheTTL bounds how often the scrape hits the database.
func metricsCacheTTL() time.Duration {
	if v := os.Getenv("METRICS_CACHE_SECONDS"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n >= 0 {
			return time.Duration(n) * time.Second
		}
	}
	return 30 * time.Second
}

func collectGauges() *dbGauges {
	gaugeMu.Lock()
	defer gaugeMu.Unlock()
	if gaugeCache != nil && time.Since(gaugeCachedAt) < metricsCacheTTL() {
		return gaugeCache
	}

	g := &dbGauges{}

	// Collection aggregates – same valuation as the dashboard/snapshots.
	_ = db.QueryRow(`
		SELECT COUNT(*),
		       COALESCE(SUM(c.quantity), 0),
		       COUNT(DISTINCT c.set_code || '|' || c.collector_number),
		       COALESCE(SUM(c.quantity * (`+foilAwareMarket+`)), 0),
		       COALESCE(SUM(c.quantity * c.purchase_price), 0),
		       COALESCE(SUM(CASE WHEN s.set_code IS NULL THEN c.quantity ELSE 0 END), 0)
		FROM collection c
		LEFT JOIN scryfall_cards s
		       ON s.set_code = c.set_code AND s.collector_number = c.collector_number`).
		Scan(&g.entries, &g.cards, &g.distinct, &g.marketEUR, &g.purchaseEUR, &g.unpriced)

	g.catalogCards = int64(countRows(db, "scryfall_cards"))
	g.catalogSets = int64(countRows(db, "sets"))

	// Errors logged in the last 24h – the activity log is the user-visible
	// record, so alerting on it matches what the UI shows.
	cutoff := time.Now().UTC().Add(-24 * time.Hour).Format(time.RFC3339)
	_ = db.QueryRow(`SELECT COUNT(*) FROM activity_log WHERE level = 'error' AND ts >= ?`, cutoff).
		Scan(&g.activityErrors24h)

	// Per-binder values. Binder count is small (folders + lists the user keeps),
	// but note that renaming a binder creates a new series.
	if rows, err := db.Query(`
		SELECT c.binder_name, c.binder_type, COUNT(*), COALESCE(SUM(c.quantity), 0),
		       COALESCE(SUM(c.quantity * (` + foilAwareMarket + `)), 0)
		FROM collection c
		LEFT JOIN scryfall_cards s
		       ON s.set_code = c.set_code AND s.collector_number = c.collector_number
		GROUP BY c.binder_name, c.binder_type`); err == nil {
		for rows.Next() {
			var b binderGauge
			if err := rows.Scan(&b.name, &b.binderType, &b.entries, &b.cards, &b.marketEUR); err == nil {
				g.binders = append(g.binders, b)
			}
		}
		rows.Close()
	}

	g.lastImport = metaUnix("remote_import_last_at")
	g.lastSync = metaUnix("bulk_synced_at")
	g.lastSets = metaUnix("sets_synced_at")
	g.lastBackup = metaUnix("backup_last_at")
	g.lastRestore = metaUnix("backup_last_restore_at")

	var snapshotCreated string
	_ = db.QueryRow(`SELECT COALESCE(MAX(created_at), '') FROM value_snapshots`).Scan(&snapshotCreated)
	if t, err := time.Parse(time.RFC3339, snapshotCreated); err == nil {
		g.lastSnapshot = float64(t.Unix())
	}

	g.importErr = boolGauge(metaGet(db, "remote_import_last_error") != "")
	g.backupErr = boolGauge(metaGet(db, "backup_last_error") != "")
	g.syncErr = boolGauge(syncError() != "")

	if dbFilePath != "" {
		if fi, err := os.Stat(dbFilePath); err == nil {
			g.dbBytes = float64(fi.Size())
		}
	}

	gaugeCache, gaugeCachedAt = g, time.Now()
	return g
}

func metaUnix(key string) float64 {
	t, ok := metaTime(db, key)
	if !ok {
		return 0
	}
	return float64(t.Unix())
}

func boolGauge(b bool) float64 {
	if b {
		return 1
	}
	return 0
}

// ---- exposition ----

func handleMetrics(w http.ResponseWriter, r *http.Request) {
	g := collectGauges()
	var b strings.Builder

	metric := func(name, help, typ string, write func()) {
		fmt.Fprintf(&b, "# HELP %s %s\n# TYPE %s %s\n", name, help, name, typ)
		write()
	}
	simple := func(name, help, typ string, v float64) {
		metric(name, help, typ, func() { fmt.Fprintf(&b, "%s %s\n", name, formatFloat(v)) })
	}
	vec := func(name, help, typ string, c *counterVec) {
		metric(name, help, typ, func() {
			for _, lv := range c.snapshot() {
				fmt.Fprintf(&b, "%s{%s} %s\n", name, lv.labels, formatFloat(lv.value))
			}
		})
	}

	n := func(s string) string { return metricsNamespace + "_" + s }

	// --- collection ---
	simple(n("collection_entries"), "Zeilen in der Sammlung (Karteneinträge)", "gauge", float64(g.entries))
	simple(n("collection_cards"), "Physische Karten (Summe der Stückzahlen)", "gauge", float64(g.cards))
	simple(n("collection_distinct_printings"), "Verschiedene Druckausgaben in der Sammlung", "gauge", float64(g.distinct))
	simple(n("collection_market_value_eur"), "Marktwert der Sammlung in EUR (foil-bewusst)", "gauge", g.marketEUR)
	simple(n("collection_purchase_value_eur"), "Einkaufswert der Sammlung in EUR", "gauge", g.purchaseEUR)
	simple(n("collection_cards_without_price"), "Karten ohne Scryfall-Treffer (ohne Preis/Bild)", "gauge", float64(g.unpriced))

	metric(n("binder_market_value_eur"), "Marktwert je Ordner/Liste in EUR", "gauge", func() {
		for _, bd := range g.binders {
			fmt.Fprintf(&b, "%s{%s} %s\n", n("binder_market_value_eur"),
				labels("binder", bd.name, "type", bd.binderType), formatFloat(bd.marketEUR))
		}
	})
	metric(n("binder_cards"), "Karten je Ordner/Liste", "gauge", func() {
		for _, bd := range g.binders {
			fmt.Fprintf(&b, "%s{%s} %s\n", n("binder_cards"),
				labels("binder", bd.name, "type", bd.binderType), formatFloat(float64(bd.cards)))
		}
	})

	// --- catalog ---
	simple(n("catalog_cards"), "Karten im lokalen Scryfall-Katalog", "gauge", float64(g.catalogCards))
	simple(n("catalog_sets"), "Editionen im lokalen Scryfall-Katalog", "gauge", float64(g.catalogSets))

	// --- freshness: unix timestamps, alert via time() - metric ---
	simple(n("last_remote_import_timestamp_seconds"), "Zeitpunkt des letzten erfolgreichen Remote-Imports", "gauge", g.lastImport)
	simple(n("last_card_sync_timestamp_seconds"), "Zeitpunkt des letzten Scryfall-Bulk-Syncs", "gauge", g.lastSync)
	simple(n("last_sets_sync_timestamp_seconds"), "Zeitpunkt des letzten Editions-Syncs", "gauge", g.lastSets)
	simple(n("last_backup_timestamp_seconds"), "Zeitpunkt des letzten erfolgreichen Backups", "gauge", g.lastBackup)
	simple(n("last_restore_timestamp_seconds"), "Zeitpunkt der letzten Wiederherstellung", "gauge", g.lastRestore)
	simple(n("last_value_snapshot_timestamp_seconds"), "Zeitpunkt des letzten Wert-Snapshots", "gauge", g.lastSnapshot)

	// --- health ---
	simple(n("sync_in_progress"), "1 während ein Sync läuft", "gauge", boolGauge(syncInProgress()))
	simple(n("sync_last_failed"), "1 wenn der letzte Sync einen Fehler hinterlassen hat", "gauge", g.syncErr)
	simple(n("remote_import_last_failed"), "1 wenn der letzte Remote-Import-Versuch fehlschlug", "gauge", g.importErr)
	simple(n("backup_last_failed"), "1 wenn das letzte Backup fehlschlug", "gauge", g.backupErr)
	simple(n("remote_import_enabled"), "1 wenn eine Import-Quelle konfiguriert ist", "gauge", boolGauge(webdavConfigured() || gdriveConfigured()))
	simple(n("backup_enabled"), "1 wenn ein Backup-Ziel konfiguriert ist", "gauge", boolGauge(backupConfigured()))
	simple(n("activity_errors_24h"), "Fehlereinträge im Aktivitätsprotokoll der letzten 24h", "gauge", float64(g.activityErrors24h))
	simple(n("db_size_bytes"), "Größe der SQLite-Datei", "gauge", g.dbBytes)
	simple(n("backup_last_size_bytes"), "Größe des zuletzt erzeugten Backups", "gauge", float64(metricBackupBytes.Load()))

	// --- process counters ---
	vec(n("imports_total"), "Importläufe nach Quelle und Ergebnis", "counter", metricImports)
	vec(n("import_rows_total"), "Importierte CSV-Zeilen nach Quelle", "counter", metricImportRows)
	vec(n("import_source_deleted_total"), "Nach dem Import gelöschte Quelldateien", "counter", metricImportSourceDeleted)
	vec(n("remote_import_checks_total"), "Prüfungen auf eine neue Quelldatei nach Quelle und Ergebnis", "counter", metricRemoteChecks)
	vec(n("backups_total"), "Backupläufe nach Ziel und Ergebnis", "counter", metricBackups)
	vec(n("syncs_total"), "Scryfall-Syncs nach Ergebnis", "counter", metricSyncs)

	// --- HTTP ---
	vec(n("http_requests_total"), "API-Requests nach Route, Methode und Status", "counter", metricHTTPRequests)
	writeHistogram(&b, n("http_request_duration_seconds"), "Dauer der API-Requests", metricHTTPDuration)

	// --- runtime (standard names so generic Grafana panels work) ---
	var ms runtime.MemStats
	runtime.ReadMemStats(&ms)
	simple("go_goroutines", "Laufende Goroutinen", "gauge", float64(runtime.NumGoroutine()))
	simple("go_memstats_heap_alloc_bytes", "Belegter Heap", "gauge", float64(ms.HeapAlloc))
	simple("go_memstats_sys_bytes", "Vom Betriebssystem geholter Speicher", "gauge", float64(ms.Sys))
	simple("go_gc_cycles_total", "Abgeschlossene GC-Zyklen", "counter", float64(ms.NumGC))
	simple("process_start_time_seconds", "Startzeit des Prozesses (Unix)", "gauge", float64(processStart.Unix()))

	w.Header().Set("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
	_, _ = w.Write([]byte(b.String()))
}

func writeHistogram(b *strings.Builder, name, help string, h *histogram) {
	fmt.Fprintf(b, "# HELP %s %s\n# TYPE %s histogram\n", name, help, name)
	h.mu.Lock()
	defer h.mu.Unlock()
	keys := make([]string, 0, len(h.series))
	for k := range h.series {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	for _, k := range keys {
		s := h.series[k]
		for i, ub := range h.buckets {
			fmt.Fprintf(b, "%s_bucket{%s,le=%q} %d\n", name, k, formatFloat(ub), s.counts[i])
		}
		fmt.Fprintf(b, "%s_bucket{%s,le=\"+Inf\"} %d\n", name, k, s.count)
		fmt.Fprintf(b, "%s_sum{%s} %s\n", name, k, formatFloat(s.sum))
		fmt.Fprintf(b, "%s_count{%s} %d\n", name, k, s.count)
	}
}

// labels builds a label string from key/value pairs, escaping per the exposition
// format (binder names are user data and may contain quotes or backslashes).
func labels(kv ...string) string {
	var b strings.Builder
	for i := 0; i+1 < len(kv); i += 2 {
		if i > 0 {
			b.WriteByte(',')
		}
		b.WriteString(kv[i])
		b.WriteString(`="`)
		b.WriteString(escapeLabel(kv[i+1]))
		b.WriteString(`"`)
	}
	return b.String()
}

func escapeLabel(s string) string {
	r := strings.NewReplacer(`\`, `\\`, `"`, `\"`, "\n", `\n`)
	return r.Replace(s)
}

func formatFloat(f float64) string {
	return strconv.FormatFloat(f, 'g', -1, 64)
}

// ---- listener ----

// startMetricsServer exposes /metrics on its own port. It is deliberately not
// part of the API mux: the API is published through nginx/Traefik, this isn't.
// METRICS_PORT="" disables the endpoint entirely.
func startMetricsServer() {
	port := envOr("METRICS_PORT", "9090")
	if port == "off" {
		log.Printf("[metrics] deaktiviert")
		return
	}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /metrics", handleMetrics)
	mux.HandleFunc("GET /", func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, "/metrics", http.StatusFound)
	})
	srv := &http.Server{
		Addr:              ":" + port,
		Handler:           mux,
		ReadHeaderTimeout: 10 * time.Second,
	}
	log.Printf("[metrics] Prometheus-Endpunkt auf :%s/metrics", port)
	go func() {
		if err := srv.ListenAndServe(); err != nil {
			log.Printf("[metrics] Listener beendet: %v", err)
		}
	}()
}
