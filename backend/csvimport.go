package main

import (
	"database/sql"
	"encoding/csv"
	"io"
	"strconv"
	"strings"
	"time"
)

type importResult struct {
	Added   int `json:"added"`
	Updated int `json:"updated"`
	Total   int `json:"total"`
}

// importCSV parses a ManaBox-style CSV and upserts rows into the collection.
// Matching key: (set_code, collector_number, foil, language, condition).
func importCSV(db *sql.DB, r io.Reader) (importResult, error) {
	var res importResult

	cr := csv.NewReader(r)
	cr.FieldsPerRecord = -1
	cr.LazyQuotes = true

	header, err := cr.Read()
	if err != nil {
		return res, err
	}
	idx := map[string]int{}
	for i, h := range header {
		idx[strings.ToLower(strings.TrimSpace(h))] = i
	}
	get := func(rec []string, key string) string {
		if i, ok := idx[key]; ok && i < len(rec) {
			return strings.TrimSpace(rec[i])
		}
		return ""
	}

	tx, err := db.Begin()
	if err != nil {
		return res, err
	}
	defer tx.Rollback()

	updateStmt, err := tx.Prepare(`
		UPDATE collection SET
			scryfall_id = ?, set_name = ?, name = ?, rarity = ?,
			quantity = ?, purchase_price = ?, currency = ?, added = ?, updated_at = ?
		WHERE set_code = ? AND collector_number = ? AND foil = ? AND language = ? AND condition = ?`)
	if err != nil {
		return res, err
	}
	defer updateStmt.Close()

	insertStmt, err := tx.Prepare(`
		INSERT INTO collection
			(scryfall_id, set_code, set_name, collector_number, name, foil, rarity,
			 language, quantity, purchase_price, currency, condition, added, updated_at)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`)
	if err != nil {
		return res, err
	}
	defer insertStmt.Close()

	now := time.Now().UTC().Format(time.RFC3339)

	for {
		rec, err := cr.Read()
		if err == io.EOF {
			break
		}
		if err != nil {
			return res, err
		}

		name := get(rec, "name")
		if name == "" {
			continue
		}
		setCode := strings.ToLower(get(rec, "set code"))
		setName := get(rec, "set name")
		collector := get(rec, "collector number")
		foil := get(rec, "foil")
		if foil == "" {
			foil = "normal"
		}
		rarity := get(rec, "rarity")
		language := strings.ToLower(get(rec, "language"))
		condition := get(rec, "condition")
		scryfallID := get(rec, "scryfall id")
		currency := get(rec, "purchase price currency")
		if currency == "" {
			currency = "EUR"
		}
		qty, _ := strconv.Atoi(get(rec, "quantity"))
		if qty <= 0 {
			qty = 1
		}
		price, _ := strconv.ParseFloat(get(rec, "purchase price"), 64)
		added := get(rec, "added")

		up, err := updateStmt.Exec(scryfallID, setName, name, rarity, qty, price, currency, added, now,
			setCode, collector, foil, language, condition)
		if err != nil {
			return res, err
		}
		if n, _ := up.RowsAffected(); n > 0 {
			res.Updated++
		} else {
			if _, err := insertStmt.Exec(scryfallID, setCode, setName, collector, name, foil, rarity,
				language, qty, price, currency, condition, added, now); err != nil {
				return res, err
			}
			res.Added++
		}
	}

	if err := tx.Commit(); err != nil {
		return res, err
	}
	res.Total = res.Added + res.Updated
	return res, nil
}

func clearCollection(db *sql.DB) error {
	_, err := db.Exec(`DELETE FROM collection`)
	return err
}
