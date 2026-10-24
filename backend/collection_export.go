package main

import (
	"database/sql"
	"encoding/csv"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// Handing the app's cards over to ManaBox.
//
// ManaBox stays the master of the collection: its CSV export replaces the
// csv rows here on every import. Cards the Android app added (origin 'app')
// exist only here until they are taken into ManaBox:
//
//  1. POST /api/collection/export answers with the app rows as a CSV in
//     exactly the format of a ManaBox export, and remembers what it exported
//     (row and quantity) under an export ID (header X-Export-Id).
//  2. The user imports that file into ManaBox.
//  3. POST /api/collection/export/{id}/confirm moves exactly the exported
//     quantities from the app rows into csv rows. From then on the cards are
//     ManaBox's: the next ManaBox export brings them back as csv rows, and
//     nothing is counted twice.
//
// The snapshot is what makes step 3 exact: a quantity changed or a card added
// between export and confirmation stays an app row, because ManaBox has not
// seen it.

// manaboxHeader is the header of a ManaBox collection export, column for column.
var manaboxHeader = []string{
	"Binder Name", "Binder Type", "Name", "Set code", "Set name", "Collector number", "Foil", "Rarity",
	"Quantity", "ManaBox ID", "Scryfall ID", "Purchase price", "Misprint", "Altered", "Condition",
	"Language", "Purchase price currency", "Added",
}

// exportedRow is one line of a snapshot: the app row and what of it went out.
type exportedRow struct {
	ID              int64   `json:"id"`
	Quantity        int     `json:"quantity"`
	BinderName      string  `json:"binderName"`
	BinderType      string  `json:"binderType"`
	SetCode         string  `json:"setCode"`
	SetName         string  `json:"setName"`
	CollectorNumber string  `json:"collectorNumber"`
	Name            string  `json:"name"`
	ScryfallID      string  `json:"scryfallId"`
	Rarity          string  `json:"rarity"`
	Foil            string  `json:"foil"`
	Language        string  `json:"language"`
	Condition       string  `json:"condition"`
	PurchasePrice   float64 `json:"purchasePrice"`
	Currency        string  `json:"currency"`
	Added           string  `json:"added"`
}

// ---- POST /api/collection/export ----

func handleCollectionExport(w http.ResponseWriter, r *http.Request) {
	if !authorized(r) {
		http.Error(w, "Wrong or missing password", 403)
		return
	}
	id, rows, err := createExport(time.Now())
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	cards := 0
	for _, x := range rows {
		cards += x.Quantity
	}
	if len(rows) > 0 {
		metricAppWrites.inc(labels("op", "export"))
		logActivity("info", "App", fmt.Sprintf("Export %d for ManaBox: %d cards in %d rows", id, cards, len(rows)))
	}
	w.Header().Set("Content-Type", "text/csv; charset=utf-8")
	w.Header().Set("Content-Disposition", fmt.Sprintf(`attachment; filename="magic-portal-app-%s.csv"`, time.Now().UTC().Format("2006-01-02")))
	w.Header().Set("X-Export-Id", strconv.FormatInt(id, 10))
	w.Header().Set("X-Export-Cards", strconv.Itoa(cards))
	w.Header().Set("Cache-Control", "no-store")
	_ = writeManaboxCSV(w, rows)
}

// createExport snapshots the app rows. An export with no rows is still
// recorded (and confirmable): it keeps the client's flow simple.
func createExport(now time.Time) (int64, []exportedRow, error) {
	tx, err := db.Begin()
	if err != nil {
		return 0, nil, err
	}
	defer tx.Rollback()
	q, err := tx.Query(`SELECT id, quantity, COALESCE(binder_name, ''), COALESCE(binder_type, ''), set_code,
		COALESCE(set_name, ''), collector_number, name, COALESCE(scryfall_id, ''), COALESCE(rarity, ''), foil, language,
		condition, COALESCE(purchase_price, 0), COALESCE(currency, ''), COALESCE(added, '')
		FROM collection WHERE origin = 'app' AND quantity > 0 ORDER BY binder_name, name, set_code, collector_number`)
	if err != nil {
		return 0, nil, err
	}
	var rows []exportedRow
	for q.Next() {
		var x exportedRow
		if err := q.Scan(&x.ID, &x.Quantity, &x.BinderName, &x.BinderType, &x.SetCode, &x.SetName, &x.CollectorNumber,
			&x.Name, &x.ScryfallID, &x.Rarity, &x.Foil, &x.Language, &x.Condition, &x.PurchasePrice, &x.Currency, &x.Added); err != nil {
			q.Close()
			return 0, nil, err
		}
		rows = append(rows, x)
	}
	q.Close()
	snap, _ := json.Marshal(rows)
	res, err := tx.Exec(`INSERT INTO collection_exports(created_at, rows) VALUES(?, ?)`, now.UTC().Format(time.RFC3339), string(snap))
	if err != nil {
		return 0, nil, err
	}
	id, _ := res.LastInsertId()
	// Old unconfirmed exports are of no use after a month.
	_, _ = tx.Exec(`DELETE FROM collection_exports WHERE created_at < ?`, now.Add(-30*24*time.Hour).UTC().Format(time.RFC3339))
	return id, rows, tx.Commit()
}

func writeManaboxCSV(w http.ResponseWriter, rows []exportedRow) error {
	cw := csv.NewWriter(w)
	if err := cw.Write(manaboxHeader); err != nil {
		return err
	}
	for _, x := range rows {
		currency := x.Currency
		if currency == "" {
			currency = "EUR"
		}
		if err := cw.Write([]string{
			x.BinderName, x.BinderType, x.Name, strings.ToUpper(x.SetCode), x.SetName, x.CollectorNumber, x.Foil,
			x.Rarity, strconv.Itoa(x.Quantity), "", x.ScryfallID, strconv.FormatFloat(x.PurchasePrice, 'f', -1, 64),
			"false", "false", x.Condition, x.Language, currency, x.Added,
		}); err != nil {
			return err
		}
	}
	cw.Flush()
	return cw.Error()
}

// ---- POST /api/collection/export/{id}/confirm ----

type confirmResult struct {
	// Cards moved from app rows to csv rows.
	Moved int `json:"moved"`
	// Exported cards no longer in their app row (removed after the export).
	Missing int `json:"missing"`
}

func handleCollectionExportConfirm(w http.ResponseWriter, r *http.Request) {
	if !authorized(r) {
		http.Error(w, "Wrong or missing password", 403)
		return
	}
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		http.Error(w, "invalid id", 400)
		return
	}
	res, err := confirmExport(id, time.Now())
	if err != nil {
		writeWriteError(w, err)
		return
	}
	metricAppWrites.inc(labels("op", "confirm"))
	logActivity("info", "App", fmt.Sprintf("Export %d taken into ManaBox: %d cards are csv rows now", id, res.Moved))
	writeJSON(w, res)
}

func confirmExport(id int64, now time.Time) (confirmResult, error) {
	var res confirmResult
	tx, err := db.Begin()
	if err != nil {
		return res, err
	}
	defer tx.Rollback()
	var snap string
	var confirmed sql.NullString
	err = tx.QueryRow(`SELECT rows, confirmed_at FROM collection_exports WHERE id = ?`, id).Scan(&snap, &confirmed)
	if errors.Is(err, sql.ErrNoRows) {
		return res, &writeError{http.StatusNotFound, -1, "no such export"}
	}
	if err != nil {
		return res, err
	}
	if confirmed.Valid && confirmed.String != "" {
		return res, &writeError{http.StatusConflict, -1, "export already confirmed at " + confirmed.String}
	}
	var rows []exportedRow
	if err := json.Unmarshal([]byte(snap), &rows); err != nil {
		return res, err
	}
	ts := now.UTC().Format(time.RFC3339)
	for _, x := range rows {
		// What is left of the exported quantity in the app row.
		var have int
		err := tx.QueryRow(`SELECT quantity FROM collection WHERE id = ? AND origin = 'app'`, x.ID).Scan(&have)
		if err != nil && !errors.Is(err, sql.ErrNoRows) {
			return res, err
		}
		n := min(have, x.Quantity)
		res.Missing += x.Quantity - n
		if n <= 0 {
			continue
		}
		if have == n {
			if _, err := tx.Exec(`DELETE FROM collection WHERE id = ?`, x.ID); err != nil {
				return res, err
			}
		} else if _, err := tx.Exec(`UPDATE collection SET quantity = quantity - ?, updated_at = ? WHERE id = ?`, n, ts, x.ID); err != nil {
			return res, err
		}
		// Into the csv row ManaBox will report for this card, as exported.
		if _, err := tx.Exec(`
			INSERT INTO collection (binder_name, binder_type, scryfall_id, set_code, set_name, collector_number, name,
				foil, rarity, language, quantity, purchase_price, currency, condition, added, updated_at, origin)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'csv')
			ON CONFLICT (binder_name, set_code, collector_number, foil, language, condition, origin)
			DO UPDATE SET quantity = quantity + excluded.quantity, updated_at = excluded.updated_at`,
			x.BinderName, x.BinderType, x.ScryfallID, x.SetCode, x.SetName, x.CollectorNumber, x.Name,
			x.Foil, x.Rarity, x.Language, n, x.PurchasePrice, x.Currency, x.Condition, x.Added, ts); err != nil {
			return res, err
		}
		res.Moved += n
	}
	if _, err := tx.Exec(`UPDATE collection_exports SET confirmed_at = ? WHERE id = ?`, ts, id); err != nil {
		return res, err
	}
	if err := bumpCollectionRev(tx); err != nil {
		return res, err
	}
	return res, tx.Commit()
}
