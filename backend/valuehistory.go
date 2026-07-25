package main

import (
	"database/sql"
	"log"
	"net/http"
	"sort"
	"strconv"
	"time"
)

// Value history: forward-only tracking of the collection's worth over time.
// snapshotValue writes one aggregate row per day plus a per-collection-card price
// row, both keyed by date (upsert) so repeated syncs on the same day just refresh
// that day with the latest prices. Valuation is foil-aware.

const dateLayout = "2006-01-02"

// foilAwareMarket is the SQL expression for a collection row's per-copy market
// price: the foil price for non-normal finishes (when known), else the base price.
const foilAwareMarket = `CASE
	WHEN lower(c.foil) NOT IN ('normal','') AND s.price_eur_foil IS NOT NULL THEN s.price_eur_foil
	ELSE s.price_eur END`

func snapshotValue(db *sql.DB) error {
	date := time.Now().UTC().Format(dateLayout)
	now := time.Now().UTC().Format(time.RFC3339)

	var market, purchase sql.NullFloat64
	var cardCount, distinctCount sql.NullInt64
	err := db.QueryRow(`
		SELECT
			COALESCE(SUM(c.quantity * (`+foilAwareMarket+`)), 0),
			COALESCE(SUM(c.quantity * c.purchase_price), 0),
			COALESCE(SUM(c.quantity), 0),
			COUNT(DISTINCT c.set_code || '|' || c.collector_number)
		FROM collection c
		LEFT JOIN scryfall_cards s
			ON s.set_code = c.set_code AND s.collector_number = c.collector_number`).
		Scan(&market, &purchase, &cardCount, &distinctCount)
	if err != nil {
		return err
	}

	if _, err := db.Exec(`
		INSERT INTO value_snapshots(date, market_eur, purchase_eur, card_count, distinct_count, created_at)
		VALUES(?, ?, ?, ?, ?, ?)
		ON CONFLICT(date) DO UPDATE SET
			market_eur=excluded.market_eur, purchase_eur=excluded.purchase_eur,
			card_count=excluded.card_count, distinct_count=excluded.distinct_count,
			created_at=excluded.created_at`,
		date, market.Float64, purchase.Float64, cardCount.Int64, distinctCount.Int64, now); err != nil {
		return err
	}

	// Per-card price snapshot for the cards currently in the collection.
	if _, err := db.Exec(`
		INSERT INTO price_history(date, scryfall_key, name, price_eur, price_eur_foil)
		SELECT ?, c.set_code || '|' || c.collector_number, MAX(c.name), s.price_eur, s.price_eur_foil
		FROM collection c
		JOIN scryfall_cards s
			ON s.set_code = c.set_code AND s.collector_number = c.collector_number
		GROUP BY c.set_code, c.collector_number
		ON CONFLICT(date, scryfall_key) DO UPDATE SET
			price_eur=excluded.price_eur, price_eur_foil=excluded.price_eur_foil, name=excluded.name`,
		date); err != nil {
		return err
	}

	log.Printf("[value] Snapshot %s: Markt %.2f €, Kauf %.2f €, %d Karten", date, market.Float64, purchase.Float64, cardCount.Int64)
	return nil
}

// ---- API: value history ----

type valuePoint struct {
	Date          string  `json:"date"`
	MarketEur     float64 `json:"marketEur"`
	PurchaseEur   float64 `json:"purchaseEur"`
	CardCount     int     `json:"cardCount"`
	DistinctCount int     `json:"distinctCount"`
}

func handleValueHistory(w http.ResponseWriter, r *http.Request) {
	rows, err := db.Query(`
		SELECT date, market_eur, purchase_eur, card_count, distinct_count
		FROM value_snapshots ORDER BY date ASC`)
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	defer rows.Close()

	out := []valuePoint{}
	for rows.Next() {
		var p valuePoint
		if err := rows.Scan(&p.Date, &p.MarketEur, &p.PurchaseEur, &p.CardCount, &p.DistinctCount); err != nil {
			http.Error(w, err.Error(), 500)
			return
		}
		out = append(out, p)
	}
	writeJSON(w, out)
}

// ---- API: top movers ----

type mover struct {
	Name        string  `json:"name"`
	Quantity    int     `json:"quantity"`
	PriceThen   float64 `json:"priceThen"`
	PriceNow    float64 `json:"priceNow"`
	Delta       float64 `json:"delta"`
	Pct         float64 `json:"pct"`
	ValueImpact float64 `json:"valueImpact"`
}

// handleValueMovers reports the biggest per-card price gainers and losers between
// a base date (roughly `days` ago) and the latest snapshot, weighted by the
// current quantity held (value impact on the collection).
func handleValueMovers(w http.ResponseWriter, r *http.Request) {
	days := 30
	if v := r.URL.Query().Get("days"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			days = n
		}
	}

	var latest string
	if err := db.QueryRow(`SELECT MAX(date) FROM price_history`).Scan(&latest); err != nil || latest == "" {
		writeJSON(w, map[string]interface{}{"days": days, "gainers": []mover{}, "losers": []mover{}})
		return
	}

	// Base date: earliest snapshot on/after the cutoff, else the earliest overall.
	cutoff := time.Now().UTC().AddDate(0, 0, -days).Format(dateLayout)
	var base sql.NullString
	_ = db.QueryRow(`SELECT date FROM price_history WHERE date >= ? ORDER BY date ASC LIMIT 1`, cutoff).Scan(&base)
	if !base.Valid || base.String == "" {
		_ = db.QueryRow(`SELECT MIN(date) FROM price_history`).Scan(&base)
	}
	if !base.Valid || base.String == "" || base.String == latest {
		writeJSON(w, map[string]interface{}{
			"days": days, "baseDate": base.String, "latestDate": latest,
			"gainers": []mover{}, "losers": []mover{},
		})
		return
	}

	rows, err := db.Query(`
		SELECT now.name, now.price_eur, base.price_eur, COALESCE(q.qty, 0)
		FROM price_history now
		JOIN price_history base
			ON base.scryfall_key = now.scryfall_key AND base.date = ?
		LEFT JOIN (
			SELECT set_code || '|' || collector_number AS k, SUM(quantity) AS qty
			FROM collection GROUP BY k
		) q ON q.k = now.scryfall_key
		WHERE now.date = ?
			AND now.price_eur IS NOT NULL AND base.price_eur IS NOT NULL`,
		base.String, latest)
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	defer rows.Close()

	movers := []mover{}
	for rows.Next() {
		var m mover
		if err := rows.Scan(&m.Name, &m.PriceNow, &m.PriceThen, &m.Quantity); err != nil {
			http.Error(w, err.Error(), 500)
			return
		}
		m.Delta = m.PriceNow - m.PriceThen
		if m.PriceThen > 0 {
			m.Pct = (m.Delta / m.PriceThen) * 100
		}
		m.ValueImpact = m.Delta * float64(m.Quantity)
		if m.Delta != 0 {
			movers = append(movers, m)
		}
	}

	// Gainers: largest positive value impact; losers: largest negative.
	sort.Slice(movers, func(i, j int) bool { return movers[i].ValueImpact > movers[j].ValueImpact })
	gainers := topPositive(movers, 10)
	losers := topNegative(movers, 10)

	writeJSON(w, map[string]interface{}{
		"days": days, "baseDate": base.String, "latestDate": latest,
		"gainers": gainers, "losers": losers,
	})
}

func topPositive(sorted []mover, n int) []mover {
	out := []mover{}
	for _, m := range sorted {
		if m.ValueImpact <= 0 || len(out) >= n {
			break
		}
		out = append(out, m)
	}
	return out
}

func topNegative(sorted []mover, n int) []mover {
	out := []mover{}
	for i := len(sorted) - 1; i >= 0; i-- {
		if sorted[i].ValueImpact >= 0 || len(out) >= n {
			break
		}
		out = append(out, sorted[i])
	}
	return out
}
