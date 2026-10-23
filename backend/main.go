package main

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"os"
	"strings"
)

var (
	db             *sql.DB
	uploadPassword string
)

func main() {
	dbPath := envOr("DB_PATH", "/data/mtg.db")
	dbFilePath = dbPath
	uploadPassword = os.Getenv("UPLOAD_PASSWORD")
	port := envOr("PORT", "8080")

	var err error
	db, err = openDB(dbPath)
	if err != nil {
		log.Fatalf("could not open the database: %v", err)
	}
	defer db.Close()

	maybeAutoRestore()
	startScheduler()
	startRemoteImportScheduler()
	startBackupScheduler()
	startImageScheduler()
	startHashScheduler()
	startMetricsServer()

	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/collection", handleCollection)
	mux.HandleFunc("POST /api/collection/owned", handleOwnedByName)
	mux.HandleFunc("GET /api/editions", handleEditions)
	mux.HandleFunc("GET /api/catalog/index", handleCatalogIndex)
	mux.HandleFunc("GET /api/catalog/card", handleCatalogCard)
	mux.HandleFunc("GET /api/catalog/search", handleCatalogSearch)
	mux.HandleFunc("GET /api/catalog/hashes", handleCatalogHashes)
	mux.HandleFunc("GET /api/sets", handleSets)
	mux.HandleFunc("GET /api/sets/{code}/cards", handleSetCards)
	mux.HandleFunc("GET /api/prints", handlePrints)
	mux.HandleFunc("GET /api/binders", handleBinders)
	mux.HandleFunc("GET /api/subtypes", handleSubtypes)
	mux.HandleFunc("GET /api/status", handleStatus)
	mux.HandleFunc("GET /api/summary", handleSummary)
	mux.HandleFunc("GET /api/dashboard", handleDashboard)
	mux.HandleFunc("GET /api/cards", handleCards)
	mux.HandleFunc("GET /api/cards/facets", handleCardFacets)
	mux.HandleFunc("GET /api/cards/random", handleRandomCards)
	mux.HandleFunc("GET /api/config", handleConfigGet)
	mux.HandleFunc("POST /api/config", handleConfigSet)
	mux.HandleFunc("GET /api/reserved", handleReserved)
	mux.HandleFunc("GET /api/wishlist", handleWishlist)
	mux.HandleFunc("GET /api/wishlist/export", handleWishlistExport)
	mux.HandleFunc("POST /api/wishlist", handleWishlistAdd)
	mux.HandleFunc("DELETE /api/wishlist/{name}", handleWishlistDelete)
	mux.HandleFunc("GET /api/value-history", handleValueHistory)
	mux.HandleFunc("GET /api/value-movers", handleValueMovers)
	mux.HandleFunc("GET /api/activity", handleActivity)
	mux.HandleFunc("POST /api/activity/clear", handleActivityClear)
	mux.HandleFunc("POST /api/images/sync", handleImageSyncNow)
	mux.HandleFunc("POST /api/hashes/sync", handleHashSyncNow)
	mux.HandleFunc("POST /api/backup", handleBackupNow)
	mux.HandleFunc("POST /api/backup/restore-latest", handleRestoreLatest)
	mux.HandleFunc("POST /api/backup/restore-upload", handleRestoreUpload)
	mux.HandleFunc("GET /api/auth-check", handleAuthCheck)
	mux.HandleFunc("POST /api/upload", handleUpload)
	mux.HandleFunc("POST /api/reset", handleReset)
	mux.HandleFunc("POST /api/sync", handleSync)
	mux.HandleFunc("GET /api/health", func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(204) })

	log.Printf("mtg-portal backend listening on :%s (password protection: %v)", port, uploadPassword != "")
	if err := http.ListenAndServe(":"+port, instrument(mux)); err != nil {
		log.Fatal(err)
	}
}

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func authorized(r *http.Request) bool {
	if uploadPassword == "" {
		return true
	}
	return r.Header.Get("X-Upload-Password") == uploadPassword
}

func writeJSON(w http.ResponseWriter, v interface{}) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	_ = json.NewEncoder(w).Encode(v)
}

// ---- Handlers ----

// scryfallOut carries the catalog data for one collection row.
//
// Image URLs are deliberately NOT included: they follow the scheme
// cards.scryfall.io/<size>/<face>/<1st>/<2nd>/<id>.jpg and the frontend builds
// them from the id (see scryfallImage() in shared.js). For a real collection
// that is roughly 3.4 MB less JSON per request, without a single extra round
// trip. Fields that usually repeat something the row already carries are only
// sent when they actually differ.
type scryfallOut struct {
	// Only set when the catalog name differs from the name in the collection.
	Name string `json:"name,omitempty"`
	// Only set when the catalog row has a different Scryfall id than the
	// collection row (ManaBox occasionally exports the id of another printing).
	ImageID string `json:"imageId,omitempty"`
	// NoImage marks the few catalog entries that have no image at all (art
	// series and similar special printings). Without the flag the frontend
	// would derive a URL from the id that does not exist at Scryfall.
	NoImage  bool     `json:"noImage,omitempty"`
	HasBack  bool     `json:"hasBack,omitempty"`
	TypeLine string   `json:"typeLine"`
	Colors   []string `json:"colors"`
	ManaCost string   `json:"manaCost"`
	// The rules text is no longer included: the card grid searches it
	// server-side (/api/cards) and receives it with the page of results, and
	// the other pages do not show it at all. Saves roughly 2.2 MB per call.
	PriceEur     *float64 `json:"priceEur"`
	PriceEurFoil *float64 `json:"priceEurFoil"`
}

type cardOut struct {
	Key             int          `json:"key"`
	Name            string       `json:"name"`
	SetCode         string       `json:"setCode"`
	SetName         string       `json:"setName"`
	CollectorNumber string       `json:"collectorNumber"`
	Foil            string       `json:"foil"`
	Rarity          string       `json:"rarity"`
	Quantity        int          `json:"quantity"`
	ScryfallID      string       `json:"scryfallId"`
	PurchasePrice   float64      `json:"purchasePrice"`
	Currency        string       `json:"currency"`
	Condition       string       `json:"condition"`
	Language        string       `json:"language"`
	Added           string       `json:"added"`
	BinderName      string       `json:"binderName"`
	BinderType      string       `json:"binderType"`
	Scryfall        *scryfallOut `json:"scryfall"`
}

func handleCollection(w http.ResponseWriter, r *http.Request) {
	// Cheapest possible answer first: the browser already has this payload.
	if notModified(w, r, collectionETag(db)) {
		return
	}
	rows, err := db.Query(`
		SELECT c.id, c.scryfall_id, c.set_code, c.set_name, c.collector_number, c.name, c.foil, c.rarity,
		       c.language, c.quantity, c.purchase_price, c.currency, c.condition, c.added, c.binder_name, c.binder_type,
		       s.name, s.type_line, s.colors, s.mana_cost, s.scryfall_id,
		       COALESCE(s.image_normal, '') <> '',
		       COALESCE(s.image_back_normal, '') <> '', s.price_eur, s.price_eur_foil
		FROM collection c
		LEFT JOIN scryfall_cards s
		       ON s.set_code = c.set_code AND s.collector_number = c.collector_number
		ORDER BY c.name`)
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	defer rows.Close()

	out := []cardOut{}
	for rows.Next() {
		var (
			c                                 cardOut
			setCode                           string
			sName, sType, sColors, sMana, sID sql.NullString
			sHasImage, sHasBack               bool
			sPrice, sPriceFoil                sql.NullFloat64
		)
		if err := rows.Scan(&c.Key, &c.ScryfallID, &setCode, &c.SetName, &c.CollectorNumber, &c.Name,
			&c.Foil, &c.Rarity, &c.Language, &c.Quantity, &c.PurchasePrice, &c.Currency, &c.Condition, &c.Added,
			&c.BinderName, &c.BinderType,
			&sName, &sType, &sColors, &sMana, &sID, &sHasImage, &sHasBack, &sPrice, &sPriceFoil); err != nil {
			http.Error(w, err.Error(), 500)
			return
		}
		c.SetCode = strings.ToUpper(setCode)
		if sName.Valid || sID.Valid {
			so := &scryfallOut{
				TypeLine: sType.String,
				Colors:   colorsFromJSON(sColors.String),
				ManaCost: sMana.String,
				HasBack:  sHasBack,
				NoImage:  !sHasImage,
			}
			if sName.String != c.Name {
				so.Name = sName.String
			}
			if sHasImage && sID.String != c.ScryfallID {
				so.ImageID = sID.String
			}
			if sPrice.Valid {
				p := sPrice.Float64
				so.PriceEur = &p
			}
			if sPriceFoil.Valid {
				p := sPriceFoil.Float64
				so.PriceEurFoil = &p
			}
			c.Scryfall = so
		}
		out = append(out, c)
	}
	writeJSONCacheable(w, out)
}

type setOut struct {
	Name       string `json:"name"`
	CardCount  int    `json:"cardCount"`
	IconSvgURI string `json:"iconSvgUri"`
	ReleasedAt string `json:"releasedAt"`
	SetType    string `json:"setType"`
	Digital    bool   `json:"digital"`
}

func handleSets(w http.ResponseWriter, r *http.Request) {
	if notModified(w, r, setsETag(db)) {
		return
	}
	rows, err := db.Query(`SELECT code, name, card_count, set_type, digital, released_at, icon_svg_uri FROM sets`)
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	defer rows.Close()

	out := map[string]setOut{}
	for rows.Next() {
		var code string
		var s setOut
		var dig int
		if err := rows.Scan(&code, &s.Name, &s.CardCount, &s.SetType, &dig, &s.ReleasedAt, &s.IconSvgURI); err != nil {
			http.Error(w, err.Error(), 500)
			return
		}
		s.Digital = dig != 0
		out[strings.ToLower(code)] = s
	}
	writeJSONCacheable(w, out)
}

// setCardOut is one card of an edition. Like the collection it ships the
// Scryfall id instead of four image URLs - the frontend derives them, and with
// the local mirror on they point at the own domain.
type setCardOut struct {
	CollectorNumber string   `json:"collectorNumber"`
	Name            string   `json:"name"`
	Rarity          string   `json:"rarity"`
	TypeLine        string   `json:"typeLine"`
	PriceEur        *float64 `json:"priceEur"`
	PriceEurFoil    *float64 `json:"priceEurFoil"`
	ImageID         string   `json:"imageId"`
	HasBack         bool     `json:"hasBack,omitempty"`
	CardmarketURI   string   `json:"cardmarketUri"`
	// Owned copies of this card, absent when none. Lets a client draw the set
	// overlay without downloading the collection (see editions.go).
	Owned *setCardOwned `json:"owned,omitempty"`
}

func handleSetCards(w http.ResponseWriter, r *http.Request) {
	code := strings.ToLower(r.PathValue("code"))
	// Before the catalog query, not inside its loop: cardRows() needs the
	// single database connection, which an open rows cursor still holds.
	collection, _, err := cardRows()
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	owned := ownedBySet(collection)[code]

	rows, err := db.Query(`
		SELECT collector_number, name, rarity, type_line, price_eur, price_eur_foil,
		       CASE WHEN COALESCE(image_normal, '') = '' THEN '' ELSE scryfall_id END,
		       COALESCE(image_back_normal, '') <> '', cardmarket_uri
		FROM scryfall_cards WHERE set_code = ?
		ORDER BY CAST(collector_number AS INTEGER), collector_number`, code)
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	defer rows.Close()

	out := []setCardOut{}
	for rows.Next() {
		var c setCardOut
		var price, priceFoil sql.NullFloat64
		var cardmarket sql.NullString
		if err := rows.Scan(&c.CollectorNumber, &c.Name, &c.Rarity, &c.TypeLine, &price, &priceFoil,
			&c.ImageID, &c.HasBack, &cardmarket); err != nil {
			http.Error(w, err.Error(), 500)
			return
		}
		if price.Valid {
			p := price.Float64
			c.PriceEur = &p
		}
		if priceFoil.Valid {
			p := priceFoil.Float64
			c.PriceEurFoil = &p
		}
		c.CardmarketURI = cardmarket.String
		c.Owned = owned["n:"+normNumber(c.CollectorNumber)]
		out = append(out, c)
	}
	writeJSON(w, out)
}

type printOut struct {
	SetCode         string `json:"setCode"`
	SetName         string `json:"setName"`
	IconSvgURI      string `json:"iconSvgUri"`
	CollectorNumber string `json:"collectorNumber"`
	Rarity          string `json:"rarity"`
	// ImageID instead of ready-made URLs: the frontend derives the image
	// address from it and can therefore route it through the local mirror.
	ImageID      string   `json:"imageId"`
	PriceEur     *float64 `json:"priceEur"`
	PriceEurFoil *float64 `json:"priceEurFoil"`
}

// handlePrints returns every printing of a card (by exact Scryfall name) across
// all editions, for the "other printings" section in the card detail view.
func handlePrints(w http.ResponseWriter, r *http.Request) {
	name := r.URL.Query().Get("name")
	out := []printOut{}
	if name != "" {
		rows, err := db.Query(`
			SELECT sc.set_code, COALESCE(s.name, ''), COALESCE(s.icon_svg_uri, ''),
			       sc.collector_number, sc.rarity,
			       CASE WHEN COALESCE(sc.image_normal, '') = '' THEN '' ELSE sc.scryfall_id END,
			       sc.price_eur, sc.price_eur_foil
			FROM scryfall_cards sc
			LEFT JOIN sets s ON s.code = sc.set_code
			WHERE sc.name = ?
			ORDER BY COALESCE(s.released_at, '') DESC, sc.set_code, CAST(sc.collector_number AS INTEGER)`, name)
		if err != nil {
			http.Error(w, err.Error(), 500)
			return
		}
		defer rows.Close()
		for rows.Next() {
			var p printOut
			var setCode string
			var price, priceFoil sql.NullFloat64
			if err := rows.Scan(&setCode, &p.SetName, &p.IconSvgURI, &p.CollectorNumber, &p.Rarity,
				&p.ImageID, &price, &priceFoil); err != nil {
				http.Error(w, err.Error(), 500)
				return
			}
			p.SetCode = strings.ToUpper(setCode)
			if price.Valid {
				v := price.Float64
				p.PriceEur = &v
			}
			if priceFoil.Valid {
				v := priceFoil.Float64
				p.PriceEurFoil = &v
			}
			out = append(out, p)
		}
	}
	writeJSON(w, out)
}

type binderOut struct {
	Name        string  `json:"name"`
	Type        string  `json:"type"`
	Cards       int     `json:"cards"`
	Total       int     `json:"total"`
	MarketValue float64 `json:"marketValue"`
}

// handleBinders returns per-binder aggregates (folders and lists) for the
// Ordner/Listen overview pages.
func handleBinders(w http.ResponseWriter, r *http.Request) {
	rows, err := db.Query(`
		SELECT c.binder_name, c.binder_type, COUNT(*),
		       COALESCE(SUM(c.quantity), 0),
		       COALESCE(SUM(c.quantity * COALESCE(sc.price_eur, 0)), 0)
		FROM collection c
		LEFT JOIN scryfall_cards sc ON sc.set_code = c.set_code AND sc.collector_number = c.collector_number
		GROUP BY c.binder_name, c.binder_type
		ORDER BY c.binder_type, c.binder_name`)
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	defer rows.Close()
	out := []binderOut{}
	for rows.Next() {
		var b binderOut
		if err := rows.Scan(&b.Name, &b.Type, &b.Cards, &b.Total, &b.MarketValue); err != nil {
			http.Error(w, err.Error(), 500)
			return
		}
		out = append(out, b)
	}
	writeJSON(w, out)
}

// handleSubtypes returns the union of Scryfall's official subtype catalogs
// (stored as a JSON array in meta) so the UI can filter subtypes.
func handleSubtypes(w http.ResponseWriter, r *http.Request) {
	v := metaGet(db, "subtypes")
	if v == "" {
		v = "[]"
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	_, _ = w.Write([]byte(v))
}

func handleAuthCheck(w http.ResponseWriter, r *http.Request) {
	if authorized(r) {
		w.WriteHeader(204)
	} else {
		w.WriteHeader(403)
	}
}

func handleUpload(w http.ResponseWriter, r *http.Request) {
	if !authorized(r) {
		http.Error(w, "Wrong or missing password", 403)
		return
	}
	defer r.Body.Close()
	res, err := importCSV(db, r.Body)
	if err != nil {
		metricImports.inc(labels("source", "upload", "result", "error"))
		logActivity("error", "Upload", "CSV import failed: "+err.Error())
		http.Error(w, "Import failed: "+err.Error(), 400)
		return
	}
	metricImports.inc(labels("source", "upload", "result", "success"))
	metricImportRows.add(labels("source", "upload"), float64(res.Total))
	log.Printf("[upload] %d new, %d updated", res.Added, res.Updated)
	logActivity("info", "Upload", fmt.Sprintf("CSV uploaded: %d new, %d updated (%d total)", res.Added, res.Updated, res.Total))
	// Pull any missing card metadata/prices in the background.
	go startSync(false)
	writeJSON(w, res)
}

func handleReset(w http.ResponseWriter, r *http.Request) {
	if !authorized(r) {
		http.Error(w, "Wrong or missing password", 403)
		return
	}
	if err := clearCollection(db); err != nil {
		logActivity("error", "Reset", "Clearing failed: "+err.Error())
		http.Error(w, err.Error(), 500)
		return
	}
	logActivity("info", "Reset", "Collection cleared")
	writeJSON(w, map[string]string{"status": "reset"})
}

func handleStatus(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, map[string]interface{}{
		"running":         syncInProgress(),
		"lastError":       syncError(),
		"setsSyncedAt":    metaGet(db, "sets_synced_at"),
		"cardsSyncedAt":   metaGet(db, "bulk_synced_at"),
		"collectionCount": countRows(db, "collection"),
		"cardCount":       countRows(db, "scryfall_cards"),
		"setCount":        countRows(db, "sets"),
		"remoteImport": map[string]interface{}{
			"enabled":    webdavConfigured() || gdriveConfigured(),
			"lastAt":     metaGet(db, "remote_import_last_at"),
			"lastSource": metaGet(db, "remote_import_last_source"),
			"lastError":  metaGet(db, "remote_import_last_error"),
		},
		"backup":      backupStatus(),
		"imageMirror": imageStatus(),
		"hashIndex":   hashStatus(),
	})
}

func handleSync(w http.ResponseWriter, r *http.Request) {
	if !authorized(r) {
		http.Error(w, "Wrong or missing password", 403)
		return
	}
	// If a remote source is configured, also look for a new CSV to import first.
	// checkRemoteImports guards itself (TryLock), so a spammed button is safe.
	remote := webdavConfigured() || gdriveConfigured()
	if remote {
		go checkRemoteImports()
	}
	started := startSync(true)
	if started || remote {
		msg := "Manual refresh started"
		if remote {
			msg += " (including a check for a new CSV import)"
		}
		logActivity("info", "Sync", msg)
	}
	writeJSON(w, map[string]bool{"started": started, "running": true})
}
