package main

import (
	"database/sql"
	"encoding/json"
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
	uploadPassword = os.Getenv("UPLOAD_PASSWORD")
	port := envOr("PORT", "8080")

	var err error
	db, err = openDB(dbPath)
	if err != nil {
		log.Fatalf("DB konnte nicht geöffnet werden: %v", err)
	}
	defer db.Close()

	startScheduler()

	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/collection", handleCollection)
	mux.HandleFunc("GET /api/sets", handleSets)
	mux.HandleFunc("GET /api/sets/{code}/cards", handleSetCards)
	mux.HandleFunc("GET /api/status", handleStatus)
	mux.HandleFunc("GET /api/auth-check", handleAuthCheck)
	mux.HandleFunc("POST /api/upload", handleUpload)
	mux.HandleFunc("POST /api/reset", handleReset)
	mux.HandleFunc("POST /api/sync", handleSync)
	mux.HandleFunc("GET /api/health", func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(204) })

	log.Printf("mtg-portal backend läuft auf :%s (Passwortschutz: %v)", port, uploadPassword != "")
	if err := http.ListenAndServe(":"+port, mux); err != nil {
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

type scryfallOut struct {
	Name       string   `json:"name"`
	Image      string   `json:"image"`
	ImageSmall string   `json:"imageSmall"`
	TypeLine   string   `json:"typeLine"`
	Colors     []string `json:"colors"`
	PriceEur   *float64 `json:"priceEur"`
	SetName    string   `json:"setName"`
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
	Scryfall        *scryfallOut `json:"scryfall"`
}

func handleCollection(w http.ResponseWriter, r *http.Request) {
	rows, err := db.Query(`
		SELECT c.id, c.scryfall_id, c.set_code, c.set_name, c.collector_number, c.name, c.foil, c.rarity,
		       c.language, c.quantity, c.purchase_price, c.currency, c.condition, c.added,
		       s.name, s.type_line, s.colors, s.image_normal, s.image_small, s.price_eur
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
			c                                       cardOut
			setCode                                 string
			sName, sType, sColors, sImgN, sImgS     sql.NullString
			sPrice                                  sql.NullFloat64
		)
		if err := rows.Scan(&c.Key, &c.ScryfallID, &setCode, &c.SetName, &c.CollectorNumber, &c.Name,
			&c.Foil, &c.Rarity, &c.Language, &c.Quantity, &c.PurchasePrice, &c.Currency, &c.Condition, &c.Added,
			&sName, &sType, &sColors, &sImgN, &sImgS, &sPrice); err != nil {
			http.Error(w, err.Error(), 500)
			return
		}
		c.SetCode = strings.ToUpper(setCode)
		if sName.Valid || sImgN.Valid {
			so := &scryfallOut{
				Name:       sName.String,
				Image:      sImgN.String,
				ImageSmall: sImgS.String,
				TypeLine:   sType.String,
				Colors:     colorsFromJSON(sColors.String),
				SetName:    c.SetName,
			}
			if sPrice.Valid {
				p := sPrice.Float64
				so.PriceEur = &p
			}
			c.Scryfall = so
		}
		out = append(out, c)
	}
	writeJSON(w, out)
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
	writeJSON(w, out)
}

type setCardOut struct {
	CollectorNumber string   `json:"collectorNumber"`
	Name            string   `json:"name"`
	Rarity          string   `json:"rarity"`
	TypeLine        string   `json:"typeLine"`
	PriceEur        *float64 `json:"priceEur"`
	PriceEurFoil    *float64 `json:"priceEurFoil"`
	Image           string   `json:"image"`
	ImageSmall      string   `json:"imageSmall"`
}

func handleSetCards(w http.ResponseWriter, r *http.Request) {
	code := strings.ToLower(r.PathValue("code"))
	rows, err := db.Query(`
		SELECT collector_number, name, rarity, type_line, price_eur, price_eur_foil, image_normal, image_small
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
		if err := rows.Scan(&c.CollectorNumber, &c.Name, &c.Rarity, &c.TypeLine, &price, &priceFoil, &c.Image, &c.ImageSmall); err != nil {
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
		out = append(out, c)
	}
	writeJSON(w, out)
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
		http.Error(w, "Falsches oder fehlendes Passwort", 403)
		return
	}
	defer r.Body.Close()
	res, err := importCSV(db, r.Body)
	if err != nil {
		http.Error(w, "Import fehlgeschlagen: "+err.Error(), 400)
		return
	}
	log.Printf("[upload] %d neu, %d aktualisiert", res.Added, res.Updated)
	// Pull any missing card metadata/prices in the background.
	go startSync(false)
	writeJSON(w, res)
}

func handleReset(w http.ResponseWriter, r *http.Request) {
	if !authorized(r) {
		http.Error(w, "Falsches oder fehlendes Passwort", 403)
		return
	}
	if err := clearCollection(db); err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
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
	})
}

func handleSync(w http.ResponseWriter, r *http.Request) {
	if !authorized(r) {
		http.Error(w, "Falsches oder fehlendes Passwort", 403)
		return
	}
	started := startSync(true)
	writeJSON(w, map[string]bool{"started": started, "running": true})
}
