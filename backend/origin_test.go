package main

import (
	"database/sql"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"testing"
)

// The collection as it was before the origin column, with its indexes.
const collectionSchemaBeforeOrigin = `
CREATE TABLE collection (
  id               INTEGER PRIMARY KEY AUTOINCREMENT,
  binder_name      TEXT,
  binder_type      TEXT,
  scryfall_id      TEXT,
  set_code         TEXT,
  set_name         TEXT,
  collector_number TEXT,
  name             TEXT,
  foil             TEXT,
  rarity           TEXT,
  language         TEXT,
  quantity         INTEGER,
  purchase_price   REAL,
  currency         TEXT,
  condition        TEXT,
  added            TEXT,
  updated_at       TEXT,
  UNIQUE (binder_name, set_code, collector_number, foil, language, condition)
);
CREATE INDEX idx_collection_set ON collection(set_code);
CREATE INDEX idx_collection_binder ON collection(binder_name);
`

func collectionIndexNames(t *testing.T, d *sql.DB) []string {
	t.Helper()
	rows, err := d.Query(`SELECT name FROM pragma_index_list('collection') WHERE origin = 'c'`)
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	var out []string
	for rows.Next() {
		var n string
		rows.Scan(&n)
		out = append(out, n)
	}
	sort.Strings(out)
	return out
}

func TestMigrateAddsOriginAndIndexes(t *testing.T) {
	path := filepath.Join(t.TempDir(), "old.db")
	old, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	for _, q := range []string{
		collectionSchemaBeforeOrigin,
		`INSERT INTO collection (id, binder_name, set_code, collector_number, name, foil, language, condition, quantity)
			VALUES (7, 'Box', 'snc', '47', 'Ledger Shredder', 'normal', 'en', 'near_mint', 2),
			       (9, 'Box', 'ice', '61', 'Brainstorm', 'foil', 'de', 'good', 1)`,
	} {
		if _, err := old.Exec(q); err != nil {
			t.Fatal(err)
		}
	}
	old.Close()

	d, err := openDB(path)
	if err != nil {
		t.Fatal(err)
	}
	defer d.Close()
	rows, err := d.Query(`SELECT id, name, quantity, origin FROM collection ORDER BY id`)
	if err != nil {
		t.Fatal(err)
	}
	var got []string
	for rows.Next() {
		var id, qty int
		var name, origin string
		rows.Scan(&id, &name, &qty, &origin)
		got = append(got, strings.Join([]string{strconv.Itoa(id), name, strconv.Itoa(qty), origin}, "|"))
	}
	rows.Close()
	if want := "7|Ledger Shredder|2|csv,9|Brainstorm|1|csv"; strings.Join(got, ",") != want {
		t.Errorf("rows after migration: %v", got)
	}
	if idx := collectionIndexNames(t, d); strings.Join(idx, ",") != "idx_collection_binder,idx_collection_set" {
		t.Errorf("indexes after migration: %v", idx)
	}
	// The same card may now exist once per origin.
	if _, err := d.Exec(`INSERT INTO collection (binder_name, set_code, collector_number, name, foil, language, condition, quantity, origin)
		VALUES ('Box', 'snc', '47', 'Ledger Shredder', 'normal', 'en', 'near_mint', 1, 'app')`); err != nil {
		t.Errorf("app twin of a csv row: %v", err)
	}
	// Opening again changes nothing.
	d.Close()
	d2, err := openDB(path)
	if err != nil {
		t.Fatal(err)
	}
	defer d2.Close()
	if n := countRows(d2, "collection"); n != 3 {
		t.Errorf("%d rows after reopening", n)
	}
}

const testCSV = `Binder Name,Binder Type,Name,Set code,Set name,Collector number,Foil,Rarity,Quantity,ManaBox ID,Scryfall ID,Purchase price,Misprint,Altered,Condition,Language,Purchase price currency
Box,binder,Ledger Shredder,SNC,Streets of New Capenna,47,normal,rare,2,1,id-1,1.5,false,false,near_mint,en,EUR
Box,binder,Brainstorm,ICE,Ice Age,61,normal,common,1,2,id-2,0.2,false,false,near_mint,en,EUR
`

func TestImportCSVKeepsAppRows(t *testing.T) {
	newTestDB(t)
	if _, err := db.Exec(`INSERT INTO collection (binder_name, binder_type, set_code, collector_number, name, foil, language, condition, quantity, origin)
		VALUES ('Scans', 'binder', 'snc', '47', 'Ledger Shredder', 'normal', 'en', 'near_mint', 3, 'app'),
		       ('Box', 'binder', 'snc', '47', 'Ledger Shredder', 'normal', 'en', 'near_mint', 1, 'app')`); err != nil {
		t.Fatal(err)
	}
	for round := 0; round < 2; round++ {
		res, err := importCSV(db, strings.NewReader(testCSV))
		if err != nil {
			t.Fatal(err)
		}
		if res.Total != 2 {
			t.Errorf("round %d: imported %d", round, res.Total)
		}
	}
	var csvRows, appRows, total int
	db.QueryRow(`SELECT COUNT(*) FROM collection WHERE origin = 'csv'`).Scan(&csvRows)
	db.QueryRow(`SELECT COUNT(*) FROM collection WHERE origin = 'app'`).Scan(&appRows)
	db.QueryRow(`SELECT SUM(quantity) FROM collection WHERE set_code = 'snc'`).Scan(&total)
	if csvRows != 2 || appRows != 2 {
		t.Errorf("csv %d, app %d rows", csvRows, appRows)
	}
	// 2 from the CSV + 3 + 1 from the app, the same key twice included.
	if total != 6 {
		t.Errorf("Ledger Shredder total %d, want 6", total)
	}
}

func TestClearCollectionByOrigin(t *testing.T) {
	newTestDB(t)
	if _, err := importCSV(db, strings.NewReader(testCSV)); err != nil {
		t.Fatal(err)
	}
	db.Exec(`INSERT INTO collection (binder_name, set_code, collector_number, name, foil, language, condition, quantity, origin)
		VALUES ('Scans', 'snc', '47', 'Ledger Shredder', 'normal', 'en', 'near_mint', 1, 'app')`)
	if n, err := clearCollection(db, "csv"); err != nil || n != 2 {
		t.Fatalf("cleared %d csv rows (%v)", n, err)
	}
	if n := countRows(db, "collection"); n != 1 {
		t.Errorf("%d rows left, want the app row", n)
	}
	if n, _ := clearCollection(db, ""); n != 1 {
		t.Errorf("clearing all removed %d", n)
	}
}
