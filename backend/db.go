package main

import (
	"database/sql"
	"encoding/json"
	"time"

	_ "modernc.org/sqlite"
)

const schema = `
CREATE TABLE IF NOT EXISTS sets (
  code         TEXT PRIMARY KEY,
  name         TEXT,
  card_count   INTEGER,
  set_type     TEXT,
  digital      INTEGER,
  released_at  TEXT,
  icon_svg_uri TEXT,
  updated_at   TEXT
);

CREATE TABLE IF NOT EXISTS scryfall_cards (
  set_code         TEXT,
  collector_number TEXT,
  scryfall_id      TEXT,
  name             TEXT,
  rarity           TEXT,
  type_line        TEXT,
  colors           TEXT,
  mana_cost        TEXT,
  oracle_text      TEXT,
  image_normal     TEXT,
  image_small      TEXT,
  price_eur        REAL,
  price_eur_foil   REAL,
  updated_at       TEXT,
  PRIMARY KEY (set_code, collector_number)
);

CREATE TABLE IF NOT EXISTS meta (
  key   TEXT PRIMARY KEY,
  value TEXT
);
`

// collectionSchema is kept separate (not part of `schema`) so it is only ever
// run against a fresh collection table – its binder index references a column
// that an old (pre-binder) table doesn't have. Creation/migration happens in
// migrate() after tableExists/columnExists checks.
const collectionSchema = `
CREATE TABLE IF NOT EXISTS collection (
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
CREATE INDEX IF NOT EXISTS idx_collection_set ON collection(set_code);
CREATE INDEX IF NOT EXISTS idx_collection_binder ON collection(binder_name);
`

func openDB(path string) (*sql.DB, error) {
	db, err := sql.Open("sqlite", path+"?_pragma=busy_timeout(5000)&_pragma=journal_mode(WAL)&_pragma=foreign_keys(1)")
	if err != nil {
		return nil, err
	}
	// Serialize access: simplest way to avoid write-lock contention between the
	// HTTP handlers and the background sync jobs.
	db.SetMaxOpenConns(1)
	if _, err := db.Exec(schema); err != nil {
		return nil, err
	}
	migrate(db)
	return db, nil
}

// migrate adds columns introduced after the initial schema to existing DBs.
// When a column is actually added (i.e. the DB predates it), the bulk-data
// marker is cleared so the next sync re-downloads and fills the new fields.
func migrate(db *sql.DB) {
	for _, stmt := range []string{
		`ALTER TABLE scryfall_cards ADD COLUMN mana_cost TEXT`,
		`ALTER TABLE scryfall_cards ADD COLUMN oracle_text TEXT`,
	} {
		if _, err := db.Exec(stmt); err == nil {
			_, _ = db.Exec(`DELETE FROM meta WHERE key = 'bulk_remote_updated_at'`)
		}
	}

	// Collection table: create fresh, or migrate an old one. The UNIQUE key
	// gained binder_name; SQLite can't ALTER a constraint, so we recreate the
	// table (preserving existing rows) when the column is missing.
	if !tableExists(db, "collection") {
		_, _ = db.Exec(collectionSchema)
	} else if !columnExists(db, "collection", "binder_name") {
		_, _ = db.Exec(`ALTER TABLE collection RENAME TO collection_old`)
		if _, err := db.Exec(collectionSchema); err == nil {
			_, _ = db.Exec(`INSERT INTO collection
				(binder_name, binder_type, scryfall_id, set_code, set_name, collector_number, name,
				 foil, rarity, language, quantity, purchase_price, currency, condition, added, updated_at)
				SELECT '', '', scryfall_id, set_code, set_name, collector_number, name,
				 foil, rarity, language, quantity, purchase_price, currency, condition, added, updated_at
				FROM collection_old`)
		}
		_, _ = db.Exec(`DROP TABLE IF EXISTS collection_old`)
	}
}

func tableExists(db *sql.DB, name string) bool {
	var n int
	_ = db.QueryRow(`SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?`, name).Scan(&n)
	return n > 0
}

func columnExists(db *sql.DB, table, col string) bool {
	rows, err := db.Query(`SELECT 1 FROM pragma_table_info(?) WHERE name = ?`, table, col)
	if err != nil {
		return false
	}
	defer rows.Close()
	return rows.Next()
}

func metaGet(db *sql.DB, key string) string {
	var v string
	_ = db.QueryRow(`SELECT value FROM meta WHERE key = ?`, key).Scan(&v)
	return v
}

func metaSet(db *sql.DB, key, value string) {
	_, _ = db.Exec(`INSERT INTO meta(key, value) VALUES(?, ?)
		ON CONFLICT(key) DO UPDATE SET value = excluded.value`, key, value)
}

func metaTime(db *sql.DB, key string) (time.Time, bool) {
	v := metaGet(db, key)
	if v == "" {
		return time.Time{}, false
	}
	t, err := time.Parse(time.RFC3339, v)
	if err != nil {
		return time.Time{}, false
	}
	return t, true
}

func countRows(db *sql.DB, table string) int {
	var n int
	_ = db.QueryRow(`SELECT COUNT(*) FROM ` + table).Scan(&n)
	return n
}

func colorsToJSON(colors []string) string {
	if len(colors) == 0 {
		return "[]"
	}
	b, _ := json.Marshal(colors)
	return string(b)
}

func colorsFromJSON(s string) []string {
	if s == "" {
		return []string{}
	}
	var out []string
	if err := json.Unmarshal([]byte(s), &out); err != nil {
		return []string{}
	}
	return out
}
