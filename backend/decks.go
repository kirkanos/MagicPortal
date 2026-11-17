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

// renameDeckCover keeps the cover, format and commander with a renamed
// binder. Into an existing binder with settings of its own, those stay.
func renameDeckCover(from, to string) {
	if from == to {
		return
	}
	deckCoversMu.Lock()
	defer deckCoversMu.Unlock()
	covers := loadDeckCovers()
	if c, ok := covers[from]; ok {
		delete(covers, from)
		if _, taken := covers[to]; !taken {
			covers[to] = c
		}
		saveDeckCovers(covers)
	}
	settings := loadDeckSettings()
	if d, ok := settings[from]; ok {
		delete(settings, from)
		if _, taken := settings[to]; !taken {
			settings[to] = d
		}
		saveDeckSettings(settings)
	}
}

// Deck formats; "" is a casual deck without a format.
var deckFormats = map[string]bool{
	"": true, "standard": true, "pioneer": true, "modern": true, "legacy": true,
	"vintage": true, "pauper": true, "commander": true, "brawl": true, "oathbreaker": true,
}

// commanderFormats have a commander; its art is the cover unless one is picked.
var commanderFormats = map[string]bool{"commander": true, "brawl": true, "oathbreaker": true}

// deckSettings: format and commander of a deck (meta deck_settings, binder
// name -> settings).
type deckSettings struct {
	Format    string     `json:"format,omitempty"`
	Commander *deckCover `json:"commander,omitempty"`
}

func loadDeckSettings() map[string]deckSettings {
	out := map[string]deckSettings{}
	if raw := metaGet(db, "deck_settings"); raw != "" {
		_ = json.Unmarshal([]byte(raw), &out)
	}
	return out
}

func saveDeckSettings(s map[string]deckSettings) {
	b, _ := json.Marshal(s)
	metaSet(db, "deck_settings", string(b))
}

// catalogCard looks a printing up for a cover or commander.
func catalogCard(setCode, number string) (deckCover, error) {
	c := deckCover{SetCode: strings.ToLower(strings.TrimSpace(setCode)), CollectorNumber: strings.TrimSpace(number)}
	err := db.QueryRow(`SELECT name, COALESCE(scryfall_id, ''), COALESCE(scryfall_id, '')
		FROM scryfall_cards WHERE set_code = ? AND collector_number = ?`, c.SetCode, c.CollectorNumber).Scan(&c.Name, &c.ScryfallID, &c.ImageID)
	if errors.Is(err, sql.ErrNoRows) {
		return c, &writeError{http.StatusNotFound, -1, fmt.Sprintf("card %s #%s is not in the catalog", c.SetCode, c.CollectorNumber)}
	}
	return c, err
}

func binderExists(w http.ResponseWriter, name string) bool {
	var n int
	if err := db.QueryRow(`SELECT COUNT(*) FROM collection WHERE binder_name = ?`, name).Scan(&n); err != nil {
		http.Error(w, err.Error(), 500)
		return false
	}
	if n == 0 {
		writeWriteError(w, &writeError{http.StatusNotFound, -1, "no such binder"})
		return false
	}
	return true
}

// PUT /api/binders/{name}/deck {format, commander: {setCode, collectorNumber} | null}.
// The commander must be a card of the deck and the format one with a commander.
func handleDeckSettings(w http.ResponseWriter, r *http.Request) {
	if !authorized(r) {
		http.Error(w, "Wrong or missing password", 403)
		return
	}
	name := r.PathValue("name")
	var in struct {
		Format    string `json:"format"`
		Commander *struct {
			SetCode         string `json:"setCode"`
			CollectorNumber string `json:"collectorNumber"`
		} `json:"commander"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<12)).Decode(&in); err != nil {
		http.Error(w, "invalid JSON: "+err.Error(), 400)
		return
	}
	in.Format = strings.ToLower(strings.TrimSpace(in.Format))
	if !deckFormats[in.Format] {
		http.Error(w, "unknown format "+in.Format, 400)
		return
	}
	if !binderExists(w, name) {
		return
	}
	out := deckSettings{Format: in.Format}
	if in.Commander != nil && strings.TrimSpace(in.Commander.SetCode) != "" {
		if !commanderFormats[in.Format] {
			http.Error(w, "format "+in.Format+" has no commander", 400)
			return
		}
		c, err := catalogCard(in.Commander.SetCode, in.Commander.CollectorNumber)
		if err != nil {
			writeLookupError(w, err)
			return
		}
		var n int
		if err := db.QueryRow(`SELECT COUNT(*) FROM collection WHERE binder_name = ? AND set_code = ? AND collector_number = ?`,
			name, c.SetCode, c.CollectorNumber).Scan(&n); err != nil {
			http.Error(w, err.Error(), 500)
			return
		}
		if n == 0 {
			writeWriteError(w, &writeError{http.StatusNotFound, -1, c.Name + " is not in this deck"})
			return
		}
		out.Commander = &c
	}

	deckCoversMu.Lock()
	defer deckCoversMu.Unlock()
	settings := loadDeckSettings()
	if out.Format == "" && out.Commander == nil {
		delete(settings, name)
	} else {
		settings[name] = out
	}
	saveDeckSettings(settings)
	msg := fmt.Sprintf("Deck %q: format %q", name, out.Format)
	if out.Commander != nil {
		msg += ", commander " + out.Commander.Name
	}
	logActivity("info", "App", msg)
	writeJSON(w, out)
}

func writeLookupError(w http.ResponseWriter, err error) {
	var we *writeError
	if errors.As(err, &we) {
		writeWriteError(w, we)
		return
	}
	http.Error(w, err.Error(), 500)
}

// binderColors: the colours of the cards in each deck, in WUBRG order, and
// the printings ("set|number") each deck holds.
func binderColors() (map[string][]string, map[string]map[string]bool) {
	sets := map[string]map[string]bool{}
	held := map[string]map[string]bool{}
	rows, _, err := cardRows()
	if err != nil {
		return nil, nil
	}
	for _, c := range rows {
		if c.BinderType != "deck" {
			continue
		}
		h := held[c.BinderName]
		if h == nil {
			h = map[string]bool{}
			held[c.BinderName] = h
		}
		h[strings.ToLower(c.SetCode)+"|"+c.CollectorNumber] = true
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
	return out, held
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
	if !binderExists(w, name) {
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
	c, err := catalogCard(in.SetCode, in.CollectorNumber)
	if err != nil {
		writeLookupError(w, err)
		return
	}
	covers[name] = c
	saveDeckCovers(covers)
	logActivity("info", "App", fmt.Sprintf("Cover of %q: %s", name, c.Name))
	writeJSON(w, c)
}
