package main

import (
	"bytes"
	"encoding/binary"
	"encoding/hex"
	"fmt"
	"image/jpeg"
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

// Image hash index for the Android scanner.
//
// The scanner reads name and collector number off a card. That fails for
// cards printed before collector numbers when the name is foreign (the
// catalog only knows English names) or unreadable, and for any card whose
// number OCR cannot make out. Those cards are recognised by their artwork: the
// phone hashes the camera picture (see dhash.go) and looks for the nearest
// hash in an index built here, nightly, from Scryfall's "small" scans.
//
// HASH_SCOPE picks the printings:
//   - old (default): everything released before the modern frame (July 2003)
//     plus every printing in the collection, ~27,000 scans, ~350 MB fetched
//     once and then only what changed;
//   - collection: only the collection;
//   - all: the whole catalog, ~110,000 scans.
//
// Scans already in the image mirror are read from disk; the rest is fetched
// and discarded after hashing. The job is resumable: each hash remembers the
// cache buster of the scan it came from and is only recomputed when Scryfall
// replaces the picture.

const hashAlgo = "dhash1" // bump when dhash.go changes; the index is rebuilt

var (
	hashSyncMu      sync.Mutex
	hashSyncRunning bool
	hashClient      = &http.Client{Timeout: time.Minute}
)

func hashIndexEnabled() bool {
	v := strings.ToLower(strings.TrimSpace(os.Getenv("HASH_INDEX")))
	return v == "true" || v == "1" || v == "yes"
}

func hashScope() string {
	switch s := strings.ToLower(strings.TrimSpace(os.Getenv("HASH_SCOPE"))); s {
	case "all", "collection":
		return s
	}
	return "old"
}

func hashSyncHour() int {
	if v := os.Getenv("HASH_SYNC_HOUR"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n >= 0 && n < 24 {
			return n
		}
	}
	return 4
}

func hashRatePerSecond() float64 {
	if v := os.Getenv("HASH_RATE_PER_SECOND"); v != "" {
		if f, err := strconv.ParseFloat(v, 64); err == nil && f > 0 {
			return f
		}
	}
	return 8
}

// startHashScheduler runs the index once a day at HASH_SYNC_HOUR, an hour
// after the image mirror by default so that most owned cards are already on
// disk. Like the mirror, no run at startup.
func startHashScheduler() {
	if !hashIndexEnabled() {
		log.Printf("[hashes] index disabled (HASH_INDEX)")
		return
	}
	hour := hashSyncHour()
	log.Printf("[hashes] index enabled, scope %s, daily at %02d:00 UTC", hashScope(), hour)
	go func() {
		for {
			time.Sleep(time.Until(nextRunAt(time.Now().UTC(), hour)))
			runHashSync()
		}
	}()
}

type hashTarget struct {
	set, number, id, url, ts string
}

type hashRow struct {
	t    hashTarget
	h, v uint64
}

func runHashSync() {
	hashSyncMu.Lock()
	if hashSyncRunning {
		hashSyncMu.Unlock()
		return
	}
	hashSyncRunning = true
	hashSyncMu.Unlock()
	defer func() {
		hashSyncMu.Lock()
		hashSyncRunning = false
		hashSyncMu.Unlock()
	}()

	started := time.Now()
	if metaGet(db, "hash_algo") != hashAlgo {
		_, _ = db.Exec(`DELETE FROM card_hashes`)
		metaSet(db, "hash_algo", hashAlgo)
	}
	targets, err := hashTargets(hashScope())
	if err != nil {
		hashSyncFailed(err)
		return
	}
	known := map[string]string{}
	if rows, err := db.Query(`SELECT set_code, collector_number, ts FROM card_hashes`); err == nil {
		for rows.Next() {
			var s, n, ts string
			if rows.Scan(&s, &n, &ts) == nil {
				known[s+"|"+n] = ts
			}
		}
		rows.Close()
	}

	interval := time.Duration(float64(time.Second) / hashRatePerSecond())
	var batch []hashRow
	var computed, unchanged, failed, fromMirror int
	for _, t := range targets {
		if ts, ok := known[t.set+"|"+t.number]; ok && ts == t.ts {
			unchanged++
			continue
		}
		// One job at a time on Scryfall's CDN.
		for imageSyncInProgress() {
			time.Sleep(10 * time.Second)
		}
		data, local, err := hashSource(t)
		if err != nil {
			failed++
			metricHashScans.inc(labels("result", "error"))
			if failed <= 5 {
				log.Printf("[hashes] %s/%s: %v", t.set, t.number, err)
			}
			if !local {
				time.Sleep(interval)
			}
			continue
		}
		decoded, err := jpeg.Decode(bytes.NewReader(data))
		if err != nil {
			failed++
			metricHashScans.inc(labels("result", "error"))
			continue
		}
		h, v := toGray(decoded).dhash()
		batch = append(batch, hashRow{t, h, v})
		computed++
		if local {
			fromMirror++
			metricHashScans.inc(labels("result", "mirror"))
		} else {
			metricHashScans.inc(labels("result", "download"))
			time.Sleep(interval)
		}
		if len(batch) >= 200 {
			if err := storeHashes(batch); err != nil {
				hashSyncFailed(err)
				return
			}
			batch = batch[:0]
		}
	}
	if err := storeHashes(batch); err != nil {
		hashSyncFailed(err)
		return
	}

	if computed > 0 {
		metaSet(db, "hash_index_version", time.Now().UTC().Format(time.RFC3339Nano))
	}
	metaSet(db, "hash_sync_last_at", time.Now().UTC().Format(time.RFC3339))
	metaSet(db, "hash_sync_last_error", "")
	log.Printf("[hashes] done: %d computed (%d from the mirror), %d unchanged, %d errors in %s",
		computed, fromMirror, unchanged, failed, time.Since(started).Round(time.Second))
	if computed > 0 || failed > 0 {
		logActivity("info", "Hashes", fmt.Sprintf("Image hash index: %d computed, %d unchanged, %d errors",
			computed, unchanged, failed))
	}
}

func hashSyncFailed(err error) {
	log.Printf("[hashes] failed: %v", err)
	metaSet(db, "hash_sync_last_error", err.Error())
	logActivityDedup("error", "Hashes", "Image hash index failed: "+err.Error())
}

// hashTargets lists the printings in scope with their small scan. Digital-only
// sets are left out: nobody scans an MTGO card.
func hashTargets(scope string) ([]hashTarget, error) {
	where := `1 = 1`
	owned := `EXISTS (SELECT 1 FROM collection c WHERE c.set_code = sc.set_code AND c.collector_number = sc.collector_number)`
	switch scope {
	case "old":
		where = `(st.released_at < '2003-07-28' OR ` + owned + `)`
	case "collection":
		where = owned
	}
	rows, err := db.Query(`
		SELECT sc.set_code, sc.collector_number, sc.scryfall_id, sc.image_small
		FROM scryfall_cards sc
		LEFT JOIN sets st ON st.code = sc.set_code
		WHERE COALESCE(sc.image_small, '') <> '' AND sc.scryfall_id <> ''
		  AND COALESCE(st.digital, 0) = 0
		  AND ` + where + `
		ORDER BY st.released_at, sc.set_code, sc.collector_number`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []hashTarget
	for rows.Next() {
		var t hashTarget
		if err := rows.Scan(&t.set, &t.number, &t.id, &t.url); err != nil {
			return nil, err
		}
		t.ts = cacheBuster(t.url)
		out = append(out, t)
	}
	return out, rows.Err()
}

// hashSource returns the small scan: from the image mirror when it holds the
// current version, otherwise from Scryfall. local reports which.
func hashSource(t hashTarget) (data []byte, local bool, err error) {
	if imageMirrorEnabled() {
		rel := cardRelPath("small", "front", t.id)
		var ts string
		_ = db.QueryRow(`SELECT ts FROM image_files WHERE path = ?`, rel).Scan(&ts)
		if ts == t.ts {
			if data, err := os.ReadFile(filepath.Join(imageDir(), rel)); err == nil {
				return data, true, nil
			}
		}
	}
	req, err := http.NewRequest("GET", t.url, nil)
	if err != nil {
		return nil, false, err
	}
	req.Header.Set("User-Agent", userAgent)
	resp, err := hashClient.Do(req)
	if err != nil {
		return nil, false, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, false, fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	data, err = io.ReadAll(io.LimitReader(resp.Body, 2<<20))
	return data, false, err
}

// storeHashes writes one batch in one transaction. Nothing inside may touch
// db directly (single connection: it would wait for itself).
func storeHashes(batch []hashRow) error {
	if len(batch) == 0 {
		return nil
	}
	tx, err := db.Begin()
	if err != nil {
		return err
	}
	stmt, err := tx.Prepare(`INSERT INTO card_hashes(set_code, collector_number, scryfall_id, ts, hash_h, hash_v, computed_at)
		VALUES(?, ?, ?, ?, ?, ?, ?)
		ON CONFLICT(set_code, collector_number) DO UPDATE SET scryfall_id = excluded.scryfall_id, ts = excluded.ts,
			hash_h = excluded.hash_h, hash_v = excluded.hash_v, computed_at = excluded.computed_at`)
	if err != nil {
		_ = tx.Rollback()
		return err
	}
	now := time.Now().UTC().Format(time.RFC3339)
	for _, r := range batch {
		if _, err := stmt.Exec(r.t.set, r.t.number, r.t.id, r.t.ts, int64(r.h), int64(r.v), now); err != nil {
			stmt.Close()
			_ = tx.Rollback()
			return err
		}
	}
	stmt.Close()
	return tx.Commit()
}

func imageSyncInProgress() bool {
	imageSyncMu.Lock()
	defer imageSyncMu.Unlock()
	return imageSyncRunning
}

func hashSyncInProgress() bool {
	hashSyncMu.Lock()
	defer hashSyncMu.Unlock()
	return hashSyncRunning
}

// ---- HTTP ----

// handleCatalogHashes serves the index in a compact binary form, since the
// phone loads it whole:
//
//	"MPH1", uint32 count, then per printing: 16-byte Scryfall ID, uint64 h,
//	uint64 v - all big-endian, 32 bytes a record, ~870 KB for 27,000 cards.
func handleCatalogHashes(w http.ResponseWriter, r *http.Request) {
	count := countRows(db, "card_hashes")
	etag := weakETag("hh", fmt.Sprintf("%s|%s|%d", hashAlgo, metaGet(db, "hash_index_version"), count))
	if notModified(w, r, etag) {
		return
	}
	rows, err := db.Query(`SELECT scryfall_id, hash_h, hash_v FROM card_hashes ORDER BY scryfall_id`)
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	defer rows.Close()
	var body bytes.Buffer
	body.Grow(8 + 32*count)
	body.WriteString("MPH1")
	_ = binary.Write(&body, binary.BigEndian, uint32(0)) // count, patched below
	n := 0
	for rows.Next() {
		var id string
		var h, v int64
		if rows.Scan(&id, &h, &v) != nil {
			continue
		}
		raw, err := hex.DecodeString(strings.ReplaceAll(id, "-", ""))
		if err != nil || len(raw) != 16 {
			continue
		}
		body.Write(raw)
		_ = binary.Write(&body, binary.BigEndian, uint64(h))
		_ = binary.Write(&body, binary.BigEndian, uint64(v))
		n++
	}
	out := body.Bytes()
	binary.BigEndian.PutUint32(out[4:8], uint32(n))
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Length", strconv.Itoa(len(out)))
	_, _ = w.Write(out)
}

// handleHashSyncNow starts an index run in the background (password protected).
func handleHashSyncNow(w http.ResponseWriter, r *http.Request) {
	if !authorized(r) {
		http.Error(w, "Wrong or missing password", 403)
		return
	}
	if !hashIndexEnabled() {
		http.Error(w, "The hash index is not enabled", 400)
		return
	}
	go runHashSync()
	writeJSON(w, map[string]bool{"started": true})
}

// hashStatus is embedded into /api/status.
func hashStatus() map[string]interface{} {
	st := map[string]interface{}{"enabled": hashIndexEnabled()}
	if !hashIndexEnabled() {
		return st
	}
	st["running"] = hashSyncInProgress()
	st["scope"] = hashScope()
	st["hashes"] = countRows(db, "card_hashes")
	st["lastAt"] = metaGet(db, "hash_sync_last_at")
	st["lastError"] = metaGet(db, "hash_sync_last_error")
	return st
}
