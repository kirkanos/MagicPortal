package main

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"
)

const userAgent = "mtg-portal/1.0 (+https://github.com/local)"

var httpClient = &http.Client{Timeout: 15 * time.Minute}

func scryfallGet(url string) (*http.Response, error) {
	req, err := http.NewRequest("GET", url, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", userAgent)
	req.Header.Set("Accept", "application/json")
	return httpClient.Do(req)
}

// ---- Sets ----

type scrySet struct {
	Code       string `json:"code"`
	Name       string `json:"name"`
	CardCount  int    `json:"card_count"`
	SetType    string `json:"set_type"`
	Digital    bool   `json:"digital"`
	ReleasedAt string `json:"released_at"`
	IconSvgURI string `json:"icon_svg_uri"`
}

type setsPage struct {
	Data     []scrySet `json:"data"`
	HasMore  bool      `json:"has_more"`
	NextPage string    `json:"next_page"`
}

func syncSets(db *sql.DB) error {
	url := "https://api.scryfall.com/sets"
	total := 0
	for url != "" {
		resp, err := scryfallGet(url)
		if err != nil {
			return err
		}
		var page setsPage
		err = json.NewDecoder(resp.Body).Decode(&page)
		resp.Body.Close()
		if err != nil {
			return err
		}

		tx, err := db.Begin()
		if err != nil {
			return err
		}
		stmt, err := tx.Prepare(`
			INSERT INTO sets (code, name, card_count, set_type, digital, released_at, icon_svg_uri, updated_at)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?)
			ON CONFLICT(code) DO UPDATE SET
				name=excluded.name, card_count=excluded.card_count, set_type=excluded.set_type,
				digital=excluded.digital, released_at=excluded.released_at,
				icon_svg_uri=excluded.icon_svg_uri, updated_at=excluded.updated_at`)
		if err != nil {
			tx.Rollback()
			return err
		}
		now := time.Now().UTC().Format(time.RFC3339)
		for _, s := range page.Data {
			dig := 0
			if s.Digital {
				dig = 1
			}
			if _, err := stmt.Exec(s.Code, s.Name, s.CardCount, s.SetType, dig, s.ReleasedAt, s.IconSvgURI, now); err != nil {
				stmt.Close()
				tx.Rollback()
				return err
			}
			total++
		}
		stmt.Close()
		if err := tx.Commit(); err != nil {
			return err
		}

		if page.HasMore {
			url = page.NextPage
			time.Sleep(120 * time.Millisecond)
		} else {
			url = ""
		}
	}
	metaSet(db, "sets_synced_at", time.Now().UTC().Format(time.RFC3339))
	log.Printf("[sync] sets: %d aktualisiert", total)
	return nil
}

// ---- Subtype catalogs (official subtypes per Scryfall) ----

var subtypeCatalogs = []string{
	"creature-types", "planeswalker-types", "land-types",
	"artifact-types", "enchantment-types", "spell-types", "battle-types",
}

// syncSubtypes fetches Scryfall's official subtype catalogs and stores their
// union (JSON array) in meta, so the UI can filter out joke/Un-set subtypes.
func syncSubtypes(db *sql.DB) error {
	union := map[string]bool{}
	for _, cat := range subtypeCatalogs {
		resp, err := scryfallGet("https://api.scryfall.com/catalog/" + cat)
		if err != nil {
			return err
		}
		var c struct {
			Data []string `json:"data"`
		}
		err = json.NewDecoder(resp.Body).Decode(&c)
		resp.Body.Close()
		if err != nil {
			return err
		}
		for _, s := range c.Data {
			union[s] = true
		}
		time.Sleep(120 * time.Millisecond)
	}
	arr := make([]string, 0, len(union))
	for s := range union {
		arr = append(arr, s)
	}
	b, _ := json.Marshal(arr)
	metaSet(db, "subtypes", string(b))
	metaSet(db, "subtypes_synced_at", time.Now().UTC().Format(time.RFC3339))
	log.Printf("[sync] subtypes: %d offizielle Untertypen", len(arr))
	return nil
}

// ---- Bulk cards ----

type bulkEntry struct {
	Type        string `json:"type"`
	DownloadURI string `json:"download_uri"`
	UpdatedAt   string `json:"updated_at"`
}
type bulkList struct {
	Data []bulkEntry `json:"data"`
}

type bulkCard struct {
	ID              string            `json:"id"`
	Name            string            `json:"name"`
	Set             string            `json:"set"`
	CollectorNumber string            `json:"collector_number"`
	Rarity          string            `json:"rarity"`
	TypeLine        string            `json:"type_line"`
	Colors          []string          `json:"colors"`
	ManaCost        string            `json:"mana_cost"`
	OracleText      string            `json:"oracle_text"`
	Digital         bool              `json:"digital"`
	Reserved        bool              `json:"reserved"`
	ImageUris       map[string]string `json:"image_uris"`
	CardFaces       []struct {
		ImageUris  map[string]string `json:"image_uris"`
		TypeLine   string            `json:"type_line"`
		Colors     []string          `json:"colors"`
		ManaCost   string            `json:"mana_cost"`
		OracleText string            `json:"oracle_text"`
	} `json:"card_faces"`
	Prices struct {
		Eur     string `json:"eur"`
		EurFoil string `json:"eur_foil"`
	} `json:"prices"`
	PurchaseUris map[string]string `json:"purchase_uris"`
}

func parsePrice(s string) interface{} {
	if s == "" {
		return nil
	}
	f, err := strconv.ParseFloat(s, 64)
	if err != nil {
		return nil
	}
	return f
}

// syncBulk downloads Scryfall's "default_cards" bulk file (one object per
// printing, incl. prices) and upserts it into scryfall_cards. This replaces
// per-card API calls entirely and also refreshes prices.
//
// The big (~140 MB) download only happens when Scryfall actually published a
// new dump: we first read the small /bulk-data listing and compare its
// updated_at against the last one we imported. force=true always downloads.
func syncBulk(db *sql.DB, force bool) error {
	resp, err := scryfallGet("https://api.scryfall.com/bulk-data")
	if err != nil {
		return err
	}
	var list bulkList
	err = json.NewDecoder(resp.Body).Decode(&list)
	resp.Body.Close()
	if err != nil {
		return err
	}
	var dlURI, remoteUpdated string
	for _, e := range list.Data {
		if e.Type == "default_cards" {
			dlURI = e.DownloadURI
			remoteUpdated = e.UpdatedAt
			break
		}
	}
	if dlURI == "" {
		return fmt.Errorf("default_cards bulk entry nicht gefunden")
	}

	// Skip the heavy download if we already have this exact dump.
	if !force && remoteUpdated != "" && remoteUpdated == metaGet(db, "bulk_remote_updated_at") && countRows(db, "scryfall_cards") > 0 {
		return nil
	}

	log.Printf("[sync] lade Bulk-Data: %s", dlURI)
	dl, err := scryfallGet(dlURI)
	if err != nil {
		return err
	}
	defer dl.Body.Close()

	dec := json.NewDecoder(dl.Body)
	// Expect a top-level JSON array.
	if _, err := dec.Token(); err != nil {
		return err
	}

	upsert := `
		INSERT INTO scryfall_cards
			(set_code, collector_number, scryfall_id, name, rarity, type_line, colors,
			 mana_cost, oracle_text, image_normal, image_small, image_back_normal, image_back_small,
			 cardmarket_uri, reserved, price_eur, price_eur_foil, updated_at)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
		ON CONFLICT(set_code, collector_number) DO UPDATE SET
			scryfall_id=excluded.scryfall_id, name=excluded.name, rarity=excluded.rarity,
			type_line=excluded.type_line, colors=excluded.colors,
			mana_cost=excluded.mana_cost, oracle_text=excluded.oracle_text,
			image_normal=excluded.image_normal, image_small=excluded.image_small,
			image_back_normal=excluded.image_back_normal, image_back_small=excluded.image_back_small,
			cardmarket_uri=excluded.cardmarket_uri, reserved=excluded.reserved,
			price_eur=excluded.price_eur, price_eur_foil=excluded.price_eur_foil,
			updated_at=excluded.updated_at`

	now := time.Now().UTC().Format(time.RFC3339)
	count := 0
	const batchSize = 5000

	var tx *sql.Tx
	var stmt *sql.Stmt
	beginBatch := func() error {
		var e error
		if tx, e = db.Begin(); e != nil {
			return e
		}
		if stmt, e = tx.Prepare(upsert); e != nil {
			tx.Rollback()
			return e
		}
		return nil
	}
	if err := beginBatch(); err != nil {
		return err
	}

	for dec.More() {
		var c bulkCard
		if err := dec.Decode(&c); err != nil {
			stmt.Close()
			tx.Rollback()
			return err
		}
		if c.Digital {
			continue
		}

		imgN, imgS := c.ImageUris["normal"], c.ImageUris["small"]
		typeLine, colors := c.TypeLine, c.Colors
		if imgN == "" && imgS == "" && len(c.CardFaces) > 0 {
			imgN = c.CardFaces[0].ImageUris["normal"]
			imgS = c.CardFaces[0].ImageUris["small"]
		}
		if typeLine == "" && len(c.CardFaces) > 0 {
			typeLine = c.CardFaces[0].TypeLine
		}
		if len(colors) == 0 && len(c.CardFaces) > 0 {
			colors = c.CardFaces[0].Colors
		}
		if imgN == "" {
			imgN = imgS
		}
		if imgS == "" {
			imgS = imgN
		}

		var imgBackN, imgBackS string
		if len(c.CardFaces) >= 2 {
			imgBackN = c.CardFaces[1].ImageUris["normal"]
			imgBackS = c.CardFaces[1].ImageUris["small"]
		}

		manaCost, oracleText := c.ManaCost, c.OracleText
		if len(c.CardFaces) > 0 {
			if manaCost == "" {
				manaCost = c.CardFaces[0].ManaCost
			}
			if oracleText == "" {
				parts := make([]string, 0, len(c.CardFaces))
				for _, f := range c.CardFaces {
					if f.OracleText != "" {
						parts = append(parts, f.OracleText)
					}
				}
				oracleText = strings.Join(parts, "\n//\n")
			}
		}

		cardmarketURI := c.PurchaseUris["cardmarket"]
		reserved := 0
		if c.Reserved {
			reserved = 1
		}

		if _, err := stmt.Exec(c.Set, c.CollectorNumber, c.ID, c.Name, c.Rarity, typeLine,
			colorsToJSON(colors), manaCost, oracleText, imgN, imgS, imgBackN, imgBackS,
			cardmarketURI, reserved, parsePrice(c.Prices.Eur), parsePrice(c.Prices.EurFoil), now); err != nil {
			stmt.Close()
			tx.Rollback()
			return err
		}
		count++
		if count%batchSize == 0 {
			stmt.Close()
			if err := tx.Commit(); err != nil {
				return err
			}
			if err := beginBatch(); err != nil {
				return err
			}
		}
	}

	stmt.Close()
	if err := tx.Commit(); err != nil {
		return err
	}
	metaSet(db, "bulk_synced_at", time.Now().UTC().Format(time.RFC3339))
	if remoteUpdated != "" {
		metaSet(db, "bulk_remote_updated_at", remoteUpdated)
	}
	log.Printf("[sync] bulk cards: %d gespeichert", count)
	return nil
}

// ---- Sync manager (only one sync at a time) ----

var (
	syncMu      sync.Mutex
	syncRunning bool
	syncLastErr string
)

func syncInProgress() bool {
	syncMu.Lock()
	defer syncMu.Unlock()
	return syncRunning
}

func syncError() string {
	syncMu.Lock()
	defer syncMu.Unlock()
	return syncLastErr
}

// startSync launches a sync in the background unless one is already running.
// force=true refreshes everything regardless of staleness. Returns false if a
// sync was already in progress.
func startSync(force bool) bool {
	syncMu.Lock()
	if syncRunning {
		syncMu.Unlock()
		return false
	}
	syncRunning = true
	syncMu.Unlock()

	go func() {
		err := doSync(force)
		syncMu.Lock()
		syncRunning = false
		if err != nil {
			syncLastErr = err.Error()
		} else {
			syncLastErr = ""
		}
		syncMu.Unlock()
	}()
	return true
}

func metaStale(key string, maxAge time.Duration) bool {
	t, ok := metaTime(db, key)
	if !ok {
		return true
	}
	return time.Since(t) > maxAge
}

func doSync(force bool) error {
	var firstErr error
	// Sets change rarely and the listing is small; refresh weekly (or if empty).
	if force || metaStale("sets_synced_at", 7*24*time.Hour) || countRows(db, "sets") == 0 {
		if err := syncSets(db); err != nil {
			log.Printf("[sync] sets fehlgeschlagen: %v", err)
			firstErr = err
		}
	}
	// Official subtype catalogs; change rarely (monthly, or if never fetched).
	if force || metaStale("subtypes_synced_at", 30*24*time.Hour) || metaGet(db, "subtypes") == "" {
		if err := syncSubtypes(db); err != nil {
			log.Printf("[sync] subtypes fehlgeschlagen: %v", err)
			if firstErr == nil {
				firstErr = err
			}
		}
	}
	// Cards + prices: syncBulk does a cheap remote check and only downloads the
	// full dump when Scryfall published a newer one (or when forced / empty).
	if err := syncBulk(db, force); err != nil {
		log.Printf("[sync] bulk fehlgeschlagen: %v", err)
		if firstErr == nil {
			firstErr = err
		}
	}
	// Record a value snapshot once the (possibly refreshed) prices are in place.
	// Only when there is a collection to value, so history starts at first import.
	if countRows(db, "collection") > 0 {
		if err := snapshotValue(db); err != nil {
			log.Printf("[value] Snapshot fehlgeschlagen: %v", err)
			if firstErr == nil {
				firstErr = err
			}
		}
	}
	return firstErr
}

// startScheduler runs an initial sync and then a periodic background refresh so
// new Scryfall data (and prices) is picked up automatically. The interval
// defaults to 5 minutes and is configurable via SYNC_INTERVAL_MINUTES; the
// heavy bulk download still only happens when new data is actually available.
func startScheduler() {
	interval := 5 * time.Minute
	if v := os.Getenv("SYNC_INTERVAL_MINUTES"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			interval = time.Duration(n) * time.Minute
		}
	}
	log.Printf("[sync] Hintergrund-Intervall: %s", interval)
	go func() {
		startSync(false)
		ticker := time.NewTicker(interval)
		defer ticker.Stop()
		for range ticker.C {
			startSync(false)
		}
	}()
}
