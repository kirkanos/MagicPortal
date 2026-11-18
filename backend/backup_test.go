package main

import (
	"bytes"
	"compress/gzip"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
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

func TestBackupNamesAndPruning(t *testing.T) {
	now := time.Date(2026, 11, 18, 15, 30, 12, 0, time.UTC)
	if n := backupName(backupAuto, now); n != "mtg-portal-backup-auto-2026-11-18.db.gz" {
		t.Error(n)
	}
	manual := backupName(backupManual, now)
	if manual != "mtg-portal-backup-manual-2026-11-18-153012.db.gz" || !isManualBackup(manual) || !isBackupName(manual) {
		t.Error(manual)
	}
	files := []backupFile{
		{Name: "mtg-portal-backup-2026-11-14.db.gz"}, // from before the split: automatic
		{Name: "mtg-portal-backup-auto-2026-11-15.db.gz"},
		{Name: "mtg-portal-backup-manual-2026-11-01-080000.db.gz"},
		{Name: "mtg-portal-backup-auto-2026-11-17.db.gz"},
		{Name: "mtg-portal-backup-auto-2026-11-16.db.gz"},
	}
	var pruned []string
	for _, f := range backupsToPrune(files, 2) {
		pruned = append(pruned, f.Name)
	}
	if strings.Join(pruned, ",") != "mtg-portal-backup-auto-2026-11-15.db.gz,mtg-portal-backup-2026-11-14.db.gz" {
		t.Errorf("pruned %v", pruned)
	}
	if len(backupsToPrune(files, 0)) != 0 {
		t.Error("keep 0 means keep all")
	}

	// The newest by modification time, not by name.
	files = []backupFile{
		{Name: "mtg-portal-backup-manual-2026-11-18-080000.db.gz", Mod: now.Add(-8 * time.Hour)},
		{Name: "mtg-portal-backup-auto-2026-11-18.db.gz", Mod: now},
	}
	if b, _ := newestBackup(files); b.Name != files[1].Name {
		t.Errorf("newest %s", b.Name)
	}
	files[0].Mod, files[1].Mod = time.Time{}, time.Time{}
	if b, _ := newestBackup(files); b.Name != files[0].Name {
		t.Errorf("newest by name %s", b.Name)
	}
}

func TestRestoreRefusesAGzipBomb(t *testing.T) {
	newTestDB(t)
	old := maxBackupUnpacked
	maxBackupUnpacked = 1 << 20
	defer func() { maxBackupUnpacked = old }()
	var buf bytes.Buffer
	gz := gzip.NewWriter(&buf)
	gz.Write(make([]byte, 4<<20))
	gz.Close()
	err := restoreFromGz(buf.Bytes())
	if err == nil || !strings.Contains(err.Error(), "refused") {
		t.Errorf("err %v", err)
	}
}
