package main

import (
	"database/sql"
	"encoding/json"
	"log"
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
  image_back_normal TEXT,
  image_back_small  TEXT,
  cardmarket_uri   TEXT,
  reserved         INTEGER,
  price_eur        REAL,
  price_eur_foil   REAL,
  updated_at       TEXT,
  PRIMARY KEY (set_code, collector_number)
);
-- The catalog is keyed by printing, but half the app asks by name: all
-- printings of a card (/api/prints), the Reserved List, the wishlist. Without
-- this index each of those is a full scan over ~110k rows.
CREATE INDEX IF NOT EXISTS idx_scryfall_cards_name ON scryfall_cards(name);

CREATE TABLE IF NOT EXISTS meta (
  key   TEXT PRIMARY KEY,
  value TEXT
);

-- Daily aggregate value snapshots (one row per day, upserted).
CREATE TABLE IF NOT EXISTS value_snapshots (
  date           TEXT PRIMARY KEY,   -- YYYY-MM-DD (UTC)
  market_eur     REAL,
  purchase_eur   REAL,
  card_count     INTEGER,
  distinct_count INTEGER,
  created_at     TEXT
);

-- Per-collection-card daily price snapshots (enables movers + attribution).
CREATE TABLE IF NOT EXISTS price_history (
  date           TEXT,
  scryfall_key   TEXT,               -- set_code|collector_number
  name           TEXT,
  price_eur      REAL,
  price_eur_foil REAL,
  PRIMARY KEY (date, scryfall_key)
);
CREATE INDEX IF NOT EXISTS idx_price_history_key ON price_history(scryfall_key);

-- Index of the local image mirror: which file has already been fetched, with
-- which cache buster from the source. Without it every run would have to
-- re-download each file or check it with a HEAD request.
CREATE TABLE IF NOT EXISTS image_files (
  path       TEXT PRIMARY KEY,
  ts         TEXT,
  bytes      INTEGER,
  fetched_at TEXT
);

-- Image hashes of card scans for the Android scanner (hashes.go). ts is the
-- cache buster of the scan a hash came from: a new picture, a new hash.
CREATE TABLE IF NOT EXISTS card_hashes (
  set_code         TEXT,
  collector_number TEXT,
  scryfall_id      TEXT,
  ts               TEXT,
  hash_h           INTEGER,
  hash_v           INTEGER,
  computed_at      TEXT,
  PRIMARY KEY (set_code, collector_number)
);

-- Answers to writes sent with an Idempotency-Key (collection_write.go): a
-- retried request gets the first answer instead of adding the cards twice.
CREATE TABLE IF NOT EXISTS idempotency_keys (
  key        TEXT PRIMARY KEY,
  response   TEXT,
  created_at TEXT
);

-- Wishlist: cards you are missing, keyed by name (all printings of a name are
-- the same want). set_code/collector_number pin a specific printing when it
-- actually matters; min_condition is what you would accept when buying.
CREATE TABLE IF NOT EXISTS wishlist (
  name             TEXT PRIMARY KEY,
  set_code         TEXT,
  collector_number TEXT,
  target_eur       REAL,
  min_condition    TEXT,
  note             TEXT,
  priority         INTEGER DEFAULT 0,
  added            TEXT
);

-- Persistent activity log (tool actions + errors), shown in the UI.
CREATE TABLE IF NOT EXISTS activity_log (
  id      INTEGER PRIMARY KEY AUTOINCREMENT,
  ts      TEXT,
  level   TEXT,
  source  TEXT,
  message TEXT
);
`

// collectionSchema is kept separate (not part of `schema`) so it is only ever
// run against a fresh collection table. Creation/migration happens in
// migrate() after tableExists/columnExists checks.
//
// origin says where a row comes from: "csv" for the ManaBox import, "app" for
// cards the Android app added. It is part of the key, so the same card can
// exist once from each source; the import replaces only its own rows and app
// rows survive it. Aggregates sum quantities anyway, so two rows count right.
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
  origin           TEXT NOT NULL DEFAULT 'csv',
  UNIQUE (binder_name, set_code, collector_number, foil, language, condition, origin)
);
`

// collectionIndexes run after every creation or rebuild of the collection
// table. Separate from the table on purpose: renaming a table takes its
// indexes along, so "CREATE INDEX IF NOT EXISTS" right after a rebuild finds
// the old table's index, skips, and dropping the old table then leaves the
// new one without any.
const collectionIndexes = `
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
		`ALTER TABLE scryfall_cards ADD COLUMN image_back_normal TEXT`,
		`ALTER TABLE scryfall_cards ADD COLUMN image_back_small TEXT`,
		`ALTER TABLE scryfall_cards ADD COLUMN cardmarket_uri TEXT`,
		`ALTER TABLE scryfall_cards ADD COLUMN reserved INTEGER`,
	} {
		if _, err := db.Exec(stmt); err == nil {
			_, _ = db.Exec(`DELETE FROM meta WHERE key = 'bulk_remote_updated_at'`)
		}
	}

	// Collection table: create fresh, or migrate an old one. The UNIQUE key
	// gained binder_name and later origin; SQLite can't ALTER a constraint, so
	// the table is recreated (preserving existing rows) when a column is missing.
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
	} else if !columnExists(db, "collection", "origin") {
		if err := addCollectionOrigin(db); err != nil {
			log.Printf("[db] adding the origin column failed, collection unchanged: %v", err)
		}
	}
	_, _ = db.Exec(collectionIndexes)
}

// addCollectionOrigin rebuilds the collection with the origin column, in one
// transaction: either every row arrives with origin 'csv' and its id, or
// nothing changed.
func addCollectionOrigin(db *sql.DB) error {
	tx, err := db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	cols := `id, binder_name, binder_type, scryfall_id, set_code, set_name, collector_number, name,
		foil, rarity, language, quantity, purchase_price, currency, condition, added, updated_at`
	for _, stmt := range []string{
		`ALTER TABLE collection RENAME TO collection_old`,
		collectionSchema,
		`INSERT INTO collection (` + cols + `, origin) SELECT ` + cols + `, 'csv' FROM collection_old`,
		`DROP TABLE collection_old`,
	} {
		if _, err := tx.Exec(stmt); err != nil {
			return err
		}
	}
	return tx.Commit()
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
