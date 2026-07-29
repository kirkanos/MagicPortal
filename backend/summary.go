package main

import (
	"database/sql"
	"math"
	"net/http"
)

// handleSummary is a small, public, read-only endpoint returning the number of
// cards and the collection's value – handy for embedding on another site
// (e.g. a homepage widget). CORS is open since it exposes only aggregates.
func handleSummary(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Access-Control-Allow-Origin", "*")

	var market, purchase sql.NullFloat64
	var cards, entries, distinct sql.NullInt64
	err := db.QueryRow(`
		SELECT
			COALESCE(SUM(c.quantity * (`+foilAwareMarket+`)), 0),
			COALESCE(SUM(c.quantity * c.purchase_price), 0),
			COALESCE(SUM(c.quantity), 0),
			COUNT(*),
			COUNT(DISTINCT c.set_code || '|' || c.collector_number)
		FROM collection c
		LEFT JOIN scryfall_cards s
			ON s.set_code = c.set_code AND s.collector_number = c.collector_number`).
		Scan(&market, &purchase, &cards, &entries, &distinct)
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}

	writeJSON(w, map[string]interface{}{
		"cards":         cards.Int64,    // total physical cards (sum of quantities)
		"distinctCards": distinct.Int64, // distinct printings (languages/foil merged)
		"entries":       entries.Int64,  // individual collection rows
		"marketValue":   round2(market.Float64),
		"purchaseValue": round2(purchase.Float64),
		"currency":      "EUR",
		"updatedAt":     metaGet(db, "bulk_synced_at"),
	})
}

func round2(f float64) float64 {
	return math.Round(f*100) / 100
}
