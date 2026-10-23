package main

import (
	"bytes"
	"compress/gzip"
	"os"
	"path/filepath"
	"testing"
)

// newTestDB points the package-level db at a fresh database in a temp dir.
func newTestDB(t *testing.T) {
	t.Helper()
	d, err := openDB(filepath.Join(t.TempDir(), "test.db"))
	if err != nil {
		t.Fatal(err)
	}
	old := db
	db = d
	t.Cleanup(func() {
		d.Close()
		db = old
	})
}

// gzipBackup builds a gzipped SQLite file from the given statements, the same
// shape restoreFromGz receives from a remote.
func gzipBackup(t *testing.T, stmts ...string) []byte {
	t.Helper()
	path := filepath.Join(t.TempDir(), "backup.db")
	b, err := openDB(path)
	if err != nil {
		t.Fatal(err)
	}
	// openDB creates the current schema; drop what the statements redefine.
	for _, s := range stmts {
		if _, err := b.Exec(s); err != nil {
			t.Fatalf("%s: %v", s, err)
		}
	}
	// Fold the WAL into the main file before reading it from disk.
	if _, err := b.Exec(`PRAGMA wal_checkpoint(TRUNCATE)`); err != nil {
		t.Fatal(err)
	}
	b.Close()
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var buf bytes.Buffer
	gz := gzip.NewWriter(&buf)
	gz.Write(raw)
	gz.Close()
	return buf.Bytes()
}

func TestRestoreMatchesColumnsByName(t *testing.T) {
	cases := []struct {
		name  string
		table string // collection table as it existed in the backup
	}{
		{
			// A backup taken before a column was added: one column fewer.
			name: "older backup, column missing",
			table: `CREATE TABLE collection (
				id INTEGER PRIMARY KEY AUTOINCREMENT, binder_name TEXT, binder_type TEXT,
				scryfall_id TEXT, set_code TEXT, set_name TEXT, collector_number TEXT, name TEXT,
				foil TEXT, rarity TEXT, language TEXT, quantity INTEGER, purchase_price REAL,
				currency TEXT, condition TEXT, added TEXT)`,
		},
		{
			// A backup from a newer release restored into this binary.
			name: "newer backup, extra column",
			table: `CREATE TABLE collection (
				id INTEGER PRIMARY KEY AUTOINCREMENT, binder_name TEXT, binder_type TEXT,
				scryfall_id TEXT, set_code TEXT, set_name TEXT, collector_number TEXT, name TEXT,
				foil TEXT, rarity TEXT, language TEXT, quantity INTEGER, purchase_price REAL,
				currency TEXT, condition TEXT, added TEXT, updated_at TEXT, from_the_future TEXT)`,
		},
		{
			// Same columns in a different order: positional copy would scramble them.
			name: "same columns, different order",
			table: `CREATE TABLE collection (
				name TEXT, quantity INTEGER, id INTEGER PRIMARY KEY AUTOINCREMENT, binder_name TEXT,
				binder_type TEXT, scryfall_id TEXT, set_code TEXT, set_name TEXT, collector_number TEXT,
				foil TEXT, rarity TEXT, language TEXT, purchase_price REAL, currency TEXT,
				condition TEXT, added TEXT, updated_at TEXT)`,
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			newTestDB(t)
			if _, err := db.Exec(`INSERT INTO collection (name, set_code, collector_number, quantity)
				VALUES ('Old Row', 'lea', '1', 9)`); err != nil {
				t.Fatal(err)
			}

			data := gzipBackup(t,
				`DROP TABLE collection`,
				tc.table,
				`INSERT INTO collection (name, set_code, collector_number, foil, language, condition, quantity)
				 VALUES ('Lightning Bolt', 'lea', '161', 'normal', 'en', 'near_mint', 3)`,
			)
			if err := restoreFromGz(data); err != nil {
				t.Fatalf("restore: %v", err)
			}

			var n, qty int
			var name, set string
			if err := db.QueryRow(`SELECT COUNT(*) FROM collection`).Scan(&n); err != nil {
				t.Fatal(err)
			}
			if n != 1 {
				t.Fatalf("want exactly the restored row, got %d rows", n)
			}
			if err := db.QueryRow(`SELECT name, set_code, quantity FROM collection`).Scan(&name, &set, &qty); err != nil {
				t.Fatal(err)
			}
			if name != "Lightning Bolt" || set != "lea" || qty != 3 {
				t.Errorf("columns scrambled: name=%q set=%q qty=%d", name, set, qty)
			}
		})
	}
}
