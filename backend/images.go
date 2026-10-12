package main

import (
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Local mirror for card images and symbols.
//
// Everything the portal shows comes from the local database - except the
// pictures, which the browser pulls straight from Scryfall's CDN. This job
// copies the images of owned cards (plus edition and mana symbols) into a
// volume so the portal keeps its looks when Scryfall is unreachable, and so a
// large collection stops hammering their CDN on every page view.
//
// Whether a given file is served locally or from Scryfall is decided by nginx
// (try_files with a proxy fallback), not by the browser: an existence check per
// image would cost one extra request per card and eat the savings. The frontend
// only learns whether the mirror is on at all - /api/config exposes that - and
// builds its URLs accordingly (see scryfallImageURL in shared.js).
//
// The whole feature is off unless IMAGE_MIRROR says otherwise, and switching it
// off needs no cleanup: the frontend simply goes back to Scryfall.

const (
	scryfallCardsHost = "https://cards.scryfall.io"
	scryfallSvgHost   = "https://svgs.scryfall.io"
)

var (
	imageSyncMu      sync.Mutex
	imageSyncRunning bool
	imageClient      = &http.Client{Timeout: 2 * time.Minute}
)

// ---- config ----

func imageMirrorEnabled() bool {
	v := strings.ToLower(strings.TrimSpace(os.Getenv("IMAGE_MIRROR")))
	return v == "true" || v == "1" || v == "yes"
}

func imageDir() string { return envOr("IMAGE_DIR", "/images") }

// imageSizes reports which renderings to mirror. "small" feeds the card grid,
// "normal" the detail dialog.
func imageSizes() []string {
	out := []string{}
	for _, s := range strings.Split(envOr("IMAGE_SIZES", "small,normal"), ",") {
		if s = strings.TrimSpace(s); s != "" {
			out = append(out, s)
		}
	}
	return out
}

func imageRatePerSecond() float64 {
	if v := os.Getenv("IMAGE_RATE_PER_SECOND"); v != "" {
		if f, err := strconv.ParseFloat(v, 64); err == nil && f > 0 {
			return f
		}
	}
	return 10
}

func imageMaxBytes() int64 {
	if v := os.Getenv("IMAGE_MAX_BYTES"); v != "" {
		if n, err := strconv.ParseInt(v, 10, 64); err == nil && n > 0 {
			return n
		}
	}
	return 3 << 30 // 3 GiB
}

func imageSyncHour() int {
	if v := os.Getenv("IMAGE_SYNC_HOUR"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n >= 0 && n < 24 {
			return n
		}
	}
	return 3
}

// ---- scheduler ----

// startImageScheduler runs the mirror once a day at IMAGE_SYNC_HOUR. There is
// deliberately no run at startup: the first full pass fetches tens of thousands
// of files, which belongs into the night and not into a deploy.
func startImageScheduler() {
	if !imageMirrorEnabled() {
		log.Printf("[images] Spiegel deaktiviert (IMAGE_MIRROR)")
		return
	}
	if err := os.MkdirAll(imageDir(), 0o755); err != nil {
		log.Printf("[images] Verzeichnis %s nicht nutzbar: %v", imageDir(), err)
		return
	}
	hour := imageSyncHour()
	log.Printf("[images] Spiegel aktiv in %s, taeglich um %02d:00 UTC", imageDir(), hour)
	go func() {
		for {
			time.Sleep(time.Until(nextRunAt(time.Now().UTC(), hour)))
			runImageSync()
		}
	}()
}

// nextRunAt returns the next occurrence of the given full hour after now.
func nextRunAt(now time.Time, hour int) time.Time {
	next := time.Date(now.Year(), now.Month(), now.Day(), hour, 0, 0, 0, now.Location())
	if !next.After(now) {
		next = next.Add(24 * time.Hour)
	}
	return next
}

// ---- the job ----

type imageTarget struct {
	rel string // path inside the mirror, also the URL path below /img/
	url string // where to fetch it from
	ts  string // cache buster of the source URL, "" for symbols
}

// runImageSync fetches everything that is missing or outdated. Only one run at
// a time; a second call returns immediately.
func runImageSync() {
	imageSyncMu.Lock()
	if imageSyncRunning {
		imageSyncMu.Unlock()
		return
	}
	imageSyncRunning = true
	imageSyncMu.Unlock()
	defer func() {
		imageSyncMu.Lock()
		imageSyncRunning = false
		imageSyncMu.Unlock()
	}()

	started := time.Now()
	targets, err := imageTargets()
	if err != nil {
		log.Printf("[images] Zielliste fehlgeschlagen: %v", err)
		metaSet(db, "image_sync_last_error", err.Error())
		logActivityDedup("error", "Bilder", "Bild-Spiegel fehlgeschlagen: "+err.Error())
		return
	}

	known := loadImageIndex()
	interval := time.Duration(float64(time.Second) / imageRatePerSecond())
	var fetched, skipped, failed int
	var bytes int64
	total := currentMirrorBytes()

	for _, tgt := range targets {
		abs := filepath.Join(imageDir(), tgt.rel)
		if entry, ok := known[tgt.rel]; ok && entry == tgt.ts && fileExists(abs) {
			skipped++
			continue
		}
		if total+bytes > imageMaxBytes() {
			log.Printf("[images] Groessenlimit erreicht (%d Bytes), Abbruch", imageMaxBytes())
			metricImageDownloads.inc(labels("result", "limit"))
			break
		}
		n, err := fetchImage(tgt, abs)
		if err != nil {
			failed++
			metricImageDownloads.inc(labels("result", "error"))
			// A single missing picture must not abort the whole run.
			if failed <= 5 {
				log.Printf("[images] %s: %v", tgt.rel, err)
			}
			continue
		}
		recordImageFile(tgt.rel, tgt.ts, n)
		fetched++
		bytes += n
		metricImageDownloads.inc(labels("result", "success"))
		time.Sleep(interval)
	}

	metaSet(db, "image_sync_last_at", time.Now().UTC().Format(time.RFC3339))
	metaSet(db, "image_sync_last_error", "")
	log.Printf("[images] fertig: %d geladen, %d unveraendert, %d Fehler, %.1f MB in %s",
		fetched, skipped, failed, float64(bytes)/1e6, started.Format("15:04")+"–"+time.Now().Format("15:04"))
	if fetched > 0 || failed > 0 {
		logActivity("info", "Bilder", fmt.Sprintf("Bild-Spiegel: %d geladen, %d unveraendert, %d Fehler (%.0f MB)",
			fetched, skipped, failed, float64(bytes)/1e6))
	}
}

// imageTargets lists every file the mirror should hold: card faces of owned
// printings, the edition symbols of those sets, and the mana symbols that
// actually appear on those cards.
func imageTargets() ([]imageTarget, error) {
	var out []imageTarget
	sizes := imageSizes()

	rows, err := db.Query(`
		SELECT DISTINCT s.scryfall_id, COALESCE(s.image_normal, ''), COALESCE(s.image_small, ''),
		       COALESCE(s.image_back_normal, ''), COALESCE(s.image_back_small, '')
		FROM collection c
		JOIN scryfall_cards s
		  ON s.set_code = c.set_code AND s.collector_number = c.collector_number
		WHERE s.scryfall_id <> ''`)
	if err != nil {
		return nil, err
	}
	for rows.Next() {
		var id, normal, small, backNormal, backSmall string
		if err := rows.Scan(&id, &normal, &small, &backNormal, &backSmall); err != nil {
			rows.Close()
			return nil, err
		}
		for _, size := range sizes {
			src := normal
			back := backNormal
			if size == "small" {
				src = small
				back = backSmall
			}
			if src != "" {
				out = append(out, imageTarget{cardRelPath(size, "front", id), src, cacheBuster(src)})
			}
			if back != "" {
				out = append(out, imageTarget{cardRelPath(size, "back", id), back, cacheBuster(back)})
			}
		}
	}
	rows.Close()

	// Edition symbols of the sets actually owned.
	setRows, err := db.Query(`
		SELECT DISTINCT s.code, COALESCE(s.icon_svg_uri, '')
		FROM sets s
		WHERE COALESCE(s.icon_svg_uri, '') <> ''
		  AND s.code IN (SELECT DISTINCT set_code FROM collection)`)
	if err != nil {
		return nil, err
	}
	for setRows.Next() {
		var code, uri string
		if err := setRows.Scan(&code, &uri); err != nil {
			setRows.Close()
			return nil, err
		}
		out = append(out, imageTarget{filepath.Join("sets", code+".svg"), uri, cacheBuster(uri)})
	}
	setRows.Close()

	for _, sym := range manaSymbolsInCollection() {
		rel := filepath.Join("symbols", sym+".svg")
		out = append(out, imageTarget{rel, scryfallSvgHost + "/card-symbols/" + sym + ".svg", ""})
	}
	return out, nil
}

// manaSymbolsInCollection collects the distinct symbol tokens of the owned
// cards - the same tokens manaSymbolImg() builds URLs for in the frontend.
func manaSymbolsInCollection() []string {
	seen := map[string]bool{}
	rows, err := db.Query(`
		SELECT DISTINCT COALESCE(s.mana_cost, '')
		FROM collection c
		JOIN scryfall_cards s
		  ON s.set_code = c.set_code AND s.collector_number = c.collector_number
		WHERE COALESCE(s.mana_cost, '') <> ''`)
	if err != nil {
		return nil
	}
	defer rows.Close()
	out := []string{}
	for rows.Next() {
		var cost string
		if err := rows.Scan(&cost); err != nil {
			continue
		}
		for _, m := range manaTokenRe.FindAllStringSubmatch(cost, -1) {
			code := strings.ToUpper(strings.ReplaceAll(m[1], "/", ""))
			if code != "" && !seen[code] {
				seen[code] = true
				out = append(out, code)
			}
		}
	}
	return out
}

func cardRelPath(size, face, id string) string {
	if len(id) < 2 {
		return ""
	}
	return filepath.Join("cards", size, face, id[0:1], id[1:2], id+".jpg")
}

// cacheBuster returns the query part Scryfall appends to image URLs; it changes
// when the artwork is replaced, which is exactly when a refetch is needed.
func cacheBuster(u string) string {
	if i := strings.IndexByte(u, '?'); i >= 0 {
		return u[i+1:]
	}
	return ""
}

// fetchImage downloads one file, writing to a temporary name first so an
// interrupted run never leaves a half file behind.
func fetchImage(tgt imageTarget, abs string) (int64, error) {
	if tgt.rel == "" {
		return 0, fmt.Errorf("kein Zielpfad")
	}
	if err := os.MkdirAll(filepath.Dir(abs), 0o755); err != nil {
		return 0, err
	}
	resp, err := imageClient.Get(tgt.url)
	if err != nil {
		return 0, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return 0, fmt.Errorf("HTTP %d", resp.StatusCode)
	}

	tmp := abs + ".tmp"
	f, err := os.Create(tmp)
	if err != nil {
		return 0, err
	}
	n, err := io.Copy(f, resp.Body)
	closeErr := f.Close()
	if err == nil {
		err = closeErr
	}
	if err != nil {
		_ = os.Remove(tmp)
		return 0, err
	}
	if err := os.Rename(tmp, abs); err != nil {
		_ = os.Remove(tmp)
		return 0, err
	}
	return n, nil
}

func fileExists(p string) bool {
	st, err := os.Stat(p)
	return err == nil && !st.IsDir()
}

// ---- index table ----

func loadImageIndex() map[string]string {
	out := map[string]string{}
	rows, err := db.Query(`SELECT path, ts FROM image_files`)
	if err != nil {
		return out
	}
	defer rows.Close()
	for rows.Next() {
		var p, ts string
		if err := rows.Scan(&p, &ts); err == nil {
			out[p] = ts
		}
	}
	return out
}

func recordImageFile(rel, ts string, bytes int64) {
	_, _ = db.Exec(`INSERT INTO image_files(path, ts, bytes, fetched_at) VALUES(?, ?, ?, ?)
		ON CONFLICT(path) DO UPDATE SET ts = excluded.ts, bytes = excluded.bytes, fetched_at = excluded.fetched_at`,
		rel, ts, bytes, time.Now().UTC().Format(time.RFC3339))
}

func currentMirrorBytes() int64 {
	var n int64
	_ = db.QueryRow(`SELECT COALESCE(SUM(bytes), 0) FROM image_files`).Scan(&n)
	return n
}

func mirroredFileCount() int {
	var n int
	_ = db.QueryRow(`SELECT COUNT(*) FROM image_files`).Scan(&n)
	return n
}

// ---- HTTP ----

// handleImageSyncNow starts a mirror run in the background. Password protected
// like the other write endpoints.
func handleImageSyncNow(w http.ResponseWriter, r *http.Request) {
	if !authorized(r) {
		http.Error(w, "Falsches oder fehlendes Passwort", 403)
		return
	}
	if !imageMirrorEnabled() {
		http.Error(w, "Bild-Spiegel ist nicht aktiviert", 400)
		return
	}
	go runImageSync()
	writeJSON(w, map[string]bool{"started": true})
}

// imageStatus is embedded into /api/status.
func imageStatus() map[string]interface{} {
	st := map[string]interface{}{"enabled": imageMirrorEnabled()}
	if !imageMirrorEnabled() {
		return st
	}
	imageSyncMu.Lock()
	running := imageSyncRunning
	imageSyncMu.Unlock()
	st["running"] = running
	st["files"] = mirroredFileCount()
	st["bytes"] = currentMirrorBytes()
	st["lastAt"] = metaGet(db, "image_sync_last_at")
	st["lastError"] = metaGet(db, "image_sync_last_error")
	return st
}
