package main

import (
	"compress/gzip"
	"database/sql"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"os"
	"strings"
	"time"
)

// Card names in other languages. Scryfall's default_cards dump (scryfall.go)
// knows only the English name; the printed names of German, French, Japanese
// ... printings are in the "all_cards" dump, one object per printing and
// language, ~400 MB gzipped. It is read as a stream, only the names are kept
// (card_names), and the card search matches them next to the English name.
//
// Names hardly change - a new set brings new ones - so the dump is fetched
// at most once a week. FOREIGN_NAMES=false turns it off.

const namesMaxAge = 7 * 24 * time.Hour

func foreignNamesEnabled() bool {
	return !strings.EqualFold(strings.TrimSpace(os.Getenv("FOREIGN_NAMES")), "false")
}

// namesDue: never fetched, older than a week, or the table is empty (a
// restored backup brings the meta table back, but not the names).
func namesDue() bool {
	return metaStale("names_synced_at", namesMaxAge) || countRows(db, "card_names") == 0
}

func syncNames(db *sql.DB) error {
	resp, err := scryfallGet("https://api.scryfall.com/bulk-data")
	if err != nil {
		return err
	}
	var list bulkList
	err = json.NewDecoder(resp.Body).Decode(&list)
	resp.Body.Close()
	if err != nil {
		return err
	}
	var uri string
	for _, e := range list.Data {
		if e.Type == "all_cards" {
			uri = e.JsonlDownloadURI
			break
		}
	}
	if uri == "" {
		return fmt.Errorf("all_cards bulk entry not found")
	}
	log.Printf("[names] downloading %s", uri)
	dl, err := scryfallGet(uri)
	if err != nil {
		return err
	}
	defer dl.Body.Close()
	if dl.StatusCode != 200 {
		return fmt.Errorf("all_cards: HTTP %d", dl.StatusCode)
	}
	gz, err := gzip.NewReader(dl.Body)
	if err != nil {
		return err
	}
	defer gz.Close()
	n, err := importNames(db, gz)
	if err != nil {
		return err
	}
	metaSet(db, "names_synced_at", time.Now().UTC().Format(time.RFC3339))
	log.Printf("[names] %d names in other languages stored", n)
	logActivity("info", "Sync", fmt.Sprintf("Card names in other languages updated (%d names)", n))
	return nil
}

// nameCard is the part of an all_cards object the names need.
type nameCard struct {
	Name        string `json:"name"`
	Lang        string `json:"lang"`
	PrintedName string `json:"printed_name"`
	CardFaces   []struct {
		PrintedName string `json:"printed_name"`
	} `json:"card_faces"`
}

type nameKey struct{ name, lang, printed string }

// importNames reads a JSONL stream of card objects and replaces card_names
// with the printed names in it that differ from the English name. Reading
// happens before the one short transaction, so the single DB connection is
// not held during the download.
func importNames(db *sql.DB, r io.Reader) (int, error) {
	seen := map[nameKey]struct{}{}
	add := func(name, lang, printed string) {
		printed = strings.TrimSpace(printed)
		if printed == "" || strings.EqualFold(printed, name) {
			return
		}
		seen[nameKey{name, lang, printed}] = struct{}{}
	}
	dec := json.NewDecoder(r)
	for dec.More() {
		var c nameCard
		if err := dec.Decode(&c); err != nil {
			return 0, err
		}
		if c.Lang == "" || c.Lang == "en" || c.Name == "" {
			continue
		}
		// Multi-face cards carry the names on the faces: each face is
		// searchable, and the whole name as printed ("A // B") as well.
		faces := make([]string, 0, len(c.CardFaces))
		for _, f := range c.CardFaces {
			if p := strings.TrimSpace(f.PrintedName); p != "" {
				faces = append(faces, p)
			}
		}
		if c.PrintedName != "" {
			add(c.Name, c.Lang, c.PrintedName)
		} else if len(faces) > 1 {
			add(c.Name, c.Lang, strings.Join(faces, " // "))
		} else if len(faces) == 1 {
			add(c.Name, c.Lang, faces[0])
		}
	}
	if len(seen) == 0 {
		// An empty or broken dump must not wipe the names we have.
		return 0, fmt.Errorf("no names in the dump")
	}

	tx, err := db.Begin()
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()
	if _, err := tx.Exec(`DELETE FROM card_names`); err != nil {
		return 0, err
	}
	stmt, err := tx.Prepare(`INSERT INTO card_names(name, lang, printed) VALUES (?, ?, ?)`)
	if err != nil {
		return 0, err
	}
	defer stmt.Close()
	for k := range seen {
		if _, err := stmt.Exec(k.name, k.lang, k.printed); err != nil {
			return 0, err
		}
	}
	return len(seen), tx.Commit()
}

// foreignNamesOf returns, per English name in the collection, its printed
// names in other languages joined by spaces, for the search haystack.
func foreignNamesOf(db *sql.DB) map[string]string {
	out := map[string]string{}
	rows, err := db.Query(`SELECT name, group_concat(printed, ' ') FROM card_names
		WHERE name IN (SELECT DISTINCT name FROM collection) GROUP BY name`)
	if err != nil {
		return out
	}
	defer rows.Close()
	for rows.Next() {
		var name, printed string
		if rows.Scan(&name, &printed) == nil {
			out[name] = printed
		}
	}
	return out
}
