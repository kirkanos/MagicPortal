package main

import (
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"sort"
	"strings"
	"sync"
)

// Decks are binders of type "deck". Besides the cards they have a cover: the
// art of one card, picked on the deck page. Covers live in meta (key
// deck_covers, a JSON object binder name -> cover), so the backup carries them
// and no table is needed for a handful of entries.

type deckCover struct {
	SetCode         string `json:"setCode"`
	CollectorNumber string `json:"collectorNumber"`
	Name            string `json:"name"`
	ScryfallID      string `json:"scryfallId"`
	// ImageID is what the image URL is built from (imageId of a card).
	ImageID string `json:"imageId"`
}

var deckCoversMu sync.Mutex

func loadDeckCovers() map[string]deckCover {
	covers := map[string]deckCover{}
	if raw := metaGet(db, "deck_covers"); raw != "" {
		_ = json.Unmarshal([]byte(raw), &covers)
	}
	return covers
}

func saveDeckCovers(covers map[string]deckCover) {
	b, _ := json.Marshal(covers)
	metaSet(db, "deck_covers", string(b))
}

// renameDeckCover keeps the cover with a renamed binder. Into an existing
// binder with a cover of its own, that one stays.
func renameDeckCover(from, to string) {
	if from == to {
		return
	}
	deckCoversMu.Lock()
	defer deckCoversMu.Unlock()
	covers := loadDeckCovers()
	c, ok := covers[from]
	if !ok {
		return
	}
	delete(covers, from)
	if _, taken := covers[to]; !taken {
		covers[to] = c
	}
	saveDeckCovers(covers)
}

// binderColors: the colours of the cards in each binder, in WUBRG order.
func binderColors() map[string][]string {
	sets := map[string]map[string]bool{}
	rows, _, err := cardRows()
	if err != nil {
		return nil
	}
	for _, c := range rows {
		if c.BinderType != "deck" {
			continue
		}
		s := sets[c.BinderName]
		if s == nil {
			s = map[string]bool{}
			sets[c.BinderName] = s
		}
		for _, col := range c.Colors {
			s[strings.ToUpper(col)] = true
		}
	}
	order := map[string]int{"W": 0, "U": 1, "B": 2, "R": 3, "G": 4}
	out := map[string][]string{}
	for name, s := range sets {
		cols := []string{}
		for col := range s {
			if _, ok := order[col]; ok {
				cols = append(cols, col)
			}
		}
		sort.Slice(cols, func(i, j int) bool { return order[cols[i]] < order[cols[j]] })
		out[name] = cols
	}
	return out
}

// POST /api/binders/{name}/cover {setCode, collectorNumber}; an empty setCode
// removes the cover. Any printing of the catalog will do.
func handleDeckCover(w http.ResponseWriter, r *http.Request) {
	if !authorized(r) {
		http.Error(w, "Wrong or missing password", 403)
		return
	}
	name := r.PathValue("name")
	var in struct {
		SetCode         string `json:"setCode"`
		CollectorNumber string `json:"collectorNumber"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<12)).Decode(&in); err != nil {
		http.Error(w, "invalid JSON: "+err.Error(), 400)
		return
	}
	var n int
	if err := db.QueryRow(`SELECT COUNT(*) FROM collection WHERE binder_name = ?`, name).Scan(&n); err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	if n == 0 {
		writeWriteError(w, &writeError{http.StatusNotFound, -1, "no such binder"})
		return
	}

	deckCoversMu.Lock()
	defer deckCoversMu.Unlock()
	covers := loadDeckCovers()
	if strings.TrimSpace(in.SetCode) == "" {
		delete(covers, name)
		saveDeckCovers(covers)
		w.WriteHeader(http.StatusNoContent)
		return
	}
	c := deckCover{SetCode: strings.ToLower(strings.TrimSpace(in.SetCode)), CollectorNumber: strings.TrimSpace(in.CollectorNumber)}
	err := db.QueryRow(`SELECT name, COALESCE(scryfall_id, ''), COALESCE(scryfall_id, '')
		FROM scryfall_cards WHERE set_code = ? AND collector_number = ?`, c.SetCode, c.CollectorNumber).Scan(&c.Name, &c.ScryfallID, &c.ImageID)
	if errors.Is(err, sql.ErrNoRows) {
		writeWriteError(w, &writeError{http.StatusNotFound, -1, fmt.Sprintf("card %s #%s is not in the catalog", c.SetCode, c.CollectorNumber)})
		return
	}
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	covers[name] = c
	saveDeckCovers(covers)
	logActivity("info", "App", fmt.Sprintf("Cover of %q: %s", name, c.Name))
	writeJSON(w, c)
}
