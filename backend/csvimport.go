package main

import (
	"bufio"
	"bytes"
	"database/sql"
	"encoding/csv"
	"fmt"
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

	// Sniff BOM and delimiter before parsing: exports occasionally arrive as
	// semicolon- or tab-separated (Excel round-trip) or with a UTF-8 BOM, which
	// would otherwise silently yield a header without a usable "name" column.
	br := bufio.NewReader(r)
	if bom, _ := br.Peek(3); bytes.Equal(bom, []byte{0xEF, 0xBB, 0xBF}) {
		_, _ = br.Discard(3)
	}
	firstLine, _ := br.Peek(8192)
	if i := bytes.IndexByte(firstLine, '\n'); i >= 0 {
		firstLine = firstLine[:i]
	}

	cr := csv.NewReader(br)
	cr.FieldsPerRecord = -1
	cr.LazyQuotes = true
	cr.Comma = detectDelimiter(firstLine)

	header, err := cr.Read()
	if err != nil {
		return res, err
	}
	idx := map[string]int{}
	for i, h := range header {
		idx[strings.ToLower(strings.TrimSpace(h))] = i
	}
	// Without a name column every row would be skipped, which – because the import
	// is a full replace – would wipe the collection without any error. Refuse
	// instead and report what was actually received.
	if _, ok := idx["name"]; !ok {
		return res, fmt.Errorf("CSV ohne Spalte \"Name\" – erkannte Kopfzeile: %s", snippet(firstLine))
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

	// Full replace: an import mirrors the CSV exactly, so cards no longer in the
	// file are removed. Done inside the transaction → on any error the previous
	// collection stays intact (rollback).
	if _, err := tx.Exec(`DELETE FROM collection`); err != nil {
		return res, err
	}

	updateStmt, err := tx.Prepare(`
		UPDATE collection SET
			binder_type = ?, scryfall_id = ?, set_name = ?, name = ?, rarity = ?,
			quantity = ?, purchase_price = ?, currency = ?, added = ?, updated_at = ?
		WHERE binder_name = ? AND set_code = ? AND collector_number = ? AND foil = ? AND language = ? AND condition = ?`)
	if err != nil {
		return res, err
	}
	defer updateStmt.Close()

	insertStmt, err := tx.Prepare(`
		INSERT INTO collection
			(binder_name, binder_type, scryfall_id, set_code, set_name, collector_number, name, foil, rarity,
			 language, quantity, purchase_price, currency, condition, added, updated_at)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`)
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
		binderName := get(rec, "binder name")
		binderType := strings.ToLower(get(rec, "binder type"))
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

		up, err := updateStmt.Exec(binderType, scryfallID, setName, name, rarity, qty, price, currency, added, now,
			binderName, setCode, collector, foil, language, condition)
		if err != nil {
			return res, err
		}
		if n, _ := up.RowsAffected(); n > 0 {
			res.Updated++
		} else {
			if _, err := insertStmt.Exec(binderName, binderType, scryfallID, setCode, setName, collector, name, foil, rarity,
				language, qty, price, currency, condition, added, now); err != nil {
				return res, err
			}
			res.Added++
		}
	}

	// A file that parses but contains no card must not silently empty the
	// collection (and, for remote imports, get deleted at the source afterwards).
	// Clearing on purpose is what the reset endpoint is for.
	if res.Added+res.Updated == 0 {
		return res, fmt.Errorf("CSV enthält keine Karten – Sammlung unverändert (Kopfzeile: %s)", snippet(firstLine))
	}

	if err := tx.Commit(); err != nil {
		return res, err
	}
	res.Total = res.Added + res.Updated
	return res, nil
}

// detectDelimiter picks the separator that occurs most often in the header line.
func detectDelimiter(headerLine []byte) rune {
	best, bestCount := ',', bytes.Count(headerLine, []byte{','})
	for _, c := range []rune{';', '\t'} {
		if n := bytes.Count(headerLine, []byte(string(c))); n > bestCount {
			best, bestCount = c, n
		}
	}
	return best
}

// snippet renders the start of the received data for error messages – enough to
// tell a changed CSV header from an HTML error page.
func snippet(b []byte) string {
	s := strings.TrimSpace(strings.ReplaceAll(string(b), "\r", ""))
	if s == "" {
		return "(leer)"
	}
	if len(s) > 200 {
		s = strings.ToValidUTF8(s[:200], "") + "…"
	}
	return s
}

func clearCollection(db *sql.DB) error {
	_, err := db.Exec(`DELETE FROM collection`)
	return err
}
