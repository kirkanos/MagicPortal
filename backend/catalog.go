package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"sync"
)

// The Scryfall catalog for the Android card scanner.
//
// The scanner reads a card's set code and collector number (or its name) with
// the phone's camera and has to find the printing. It does that against a copy
// of the catalog on the phone: at a card shop or flea market there may be no
// network, and a round trip per analysed camera frame over a home DSL line
// would be far too slow - and would queue behind the sync jobs on the single
// database connection.
//
//   - GET /api/catalog/index: the whole catalog in compact form for that copy.
//     Arrays, not objects, and an ETag over the content, so the phone only
//     downloads it again when printings were added - not after every daily
//     price update, which leaves the index unchanged.
//   - GET /api/catalog/card: one printing by set and number, with prices.
//   - GET /api/catalog/search: name search that forgives OCR errors, for a
//     first run before the index is on the phone.
//
// Everything is built from the database once per catalog sync and kept in
// memory, like the card grid's index (cardRows in cards.go).

type catalogEntry struct {
	SetCode         string // lowercase
	CollectorNumber string
	Name            string
	ScryfallID      string
	HasImage        bool
	HasBack         bool
	Rarity          string
	ReleasedAt      string
}

type catalogData struct {
	key       string
	printings []catalogEntry
	// Normalised name (and each face of a double-faced card) -> printings,
	// newest first.
	byName map[string][]int
	names  []string // keys of byName, for the fuzzy search
	index  []byte   // serialised /api/catalog/index
	etag   string
}

var (
	catalogMu    sync.Mutex
	catalogCache *catalogData
)

// normalizeName folds a card name to what OCR can be compared against:
// lowercase, diacritics folded with the grid's collation table, everything but
// letters, digits and spaces dropped. The Android app has a twin; both are
// tested against the same cases.
func normalizeName(s string) string {
	var b strings.Builder
	b.Grow(len(s))
	space := false
	for _, r := range strings.ToLower(s) {
		if folded, ok := collateFold[r]; ok {
			b.WriteString(folded)
			space = false
			continue
		}
		switch {
		case r >= 'a' && r <= 'z', r >= '0' && r <= '9':
			b.WriteRune(r)
			space = false
		case r == ' ' || r == '-' || r == '/':
			if !space && b.Len() > 0 {
				b.WriteByte(' ')
				space = true
			}
		}
	}
	return strings.TrimSpace(b.String())
}

// catalog returns the cached catalog, rebuilding it after a sync.
func catalog() (*catalogData, error) {
	key := metaGet(db, "bulk_synced_at") + "|" + metaGet(db, "sets_synced_at")
	catalogMu.Lock()
	defer catalogMu.Unlock()
	if catalogCache != nil && catalogCache.key == key {
		return catalogCache, nil
	}

	type setRow struct {
		name, released, setType string
		cardCount               int
	}
	sets := map[string]setRow{}
	setRows, err := db.Query(`SELECT code, COALESCE(name, ''), COALESCE(released_at, ''), COALESCE(card_count, 0),
		COALESCE(set_type, '') FROM sets WHERE COALESCE(digital, 0) = 0`)
	if err != nil {
		return nil, err
	}
	for setRows.Next() {
		var code string
		var s setRow
		if err := setRows.Scan(&code, &s.name, &s.released, &s.cardCount, &s.setType); err != nil {
			setRows.Close()
			return nil, err
		}
		sets[strings.ToLower(code)] = s
	}
	setRows.Close()

	rows, err := db.Query(`SELECT set_code, collector_number, name, COALESCE(scryfall_id, ''),
		COALESCE(image_normal, '') <> '', COALESCE(image_back_normal, '') <> '', COALESCE(rarity, '')
		FROM scryfall_cards`)
	if err != nil {
		return nil, err
	}
	var printings []catalogEntry
	for rows.Next() {
		var p catalogEntry
		if err := rows.Scan(&p.SetCode, &p.CollectorNumber, &p.Name, &p.ScryfallID, &p.HasImage, &p.HasBack, &p.Rarity); err != nil {
			rows.Close()
			return nil, err
		}
		p.SetCode = strings.ToLower(p.SetCode)
		p.ReleasedAt = sets[p.SetCode].released
		printings = append(printings, p)
	}
	rows.Close()

	// Stable order: set, then collector number - the index's ETag must not
	// change just because SQLite returned rows in another order.
	sort.Slice(printings, func(i, j int) bool {
		if printings[i].SetCode != printings[j].SetCode {
			return printings[i].SetCode < printings[j].SetCode
		}
		return printings[i].CollectorNumber < printings[j].CollectorNumber
	})

	byName := map[string][]int{}
	add := func(name string, i int) {
		if n := normalizeName(name); n != "" {
			byName[n] = append(byName[n], i)
		}
	}
	for i, p := range printings {
		add(p.Name, i)
		// The scanner reads one face of a double-faced card; each face
		// leads to the full card.
		if strings.Contains(p.Name, "//") {
			for _, face := range strings.Split(p.Name, "//") {
				add(face, i)
			}
		}
	}
	names := make([]string, 0, len(byName))
	for n, idx := range byName {
		sort.SliceStable(idx, func(a, b int) bool { return printings[idx[a]].ReleasedAt > printings[idx[b]].ReleasedAt })
		names = append(names, n)
	}
	sort.Strings(names)

	// The index for the phone. Arrays keep 110k cards at a few MB.
	type indexOut struct {
		Sets  [][]interface{} `json:"sets"`
		Cards [][]interface{} `json:"cards"`
	}
	out := indexOut{Sets: [][]interface{}{}, Cards: make([][]interface{}, 0, len(printings))}
	codes := make([]string, 0, len(sets))
	for c := range sets {
		codes = append(codes, c)
	}
	sort.Strings(codes)
	for _, c := range codes {
		s := sets[c]
		out.Sets = append(out.Sets, []interface{}{c, s.name, s.released, s.cardCount, s.setType})
	}
	for _, p := range printings {
		flags := 0
		if p.HasImage {
			flags |= 1
		}
		if p.HasBack {
			flags |= 2
		}
		out.Cards = append(out.Cards, []interface{}{p.SetCode, p.CollectorNumber, p.Name, p.ScryfallID, flags})
	}
	var buf bytes.Buffer
	if err := json.NewEncoder(&buf).Encode(out); err != nil {
		return nil, err
	}

	catalogCache = &catalogData{
		key:       key,
		printings: printings,
		byName:    byName,
		names:     names,
		index:     buf.Bytes(),
		etag:      weakETag("ci", sha256hex(buf.Bytes())[:16]),
	}
	return catalogCache, nil
}

func handleCatalogIndex(w http.ResponseWriter, r *http.Request) {
	c, err := catalog()
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	if notModified(w, r, c.etag) {
		return
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	_, _ = w.Write(c.index)
}

type catalogCardOut struct {
	SetCode         string   `json:"setCode"`
	SetName         string   `json:"setName"`
	CollectorNumber string   `json:"collectorNumber"`
	Name            string   `json:"name"`
	ScryfallID      string   `json:"scryfallId"`
	Rarity          string   `json:"rarity"`
	TypeLine        string   `json:"typeLine"`
	ImageID         string   `json:"imageId"`
	HasBack         bool     `json:"hasBack,omitempty"`
	PriceEur        *float64 `json:"priceEur"`
	PriceEurFoil    *float64 `json:"priceEurFoil"`
}

func handleCatalogCard(w http.ResponseWriter, r *http.Request) {
	set := strings.ToLower(strings.TrimSpace(r.URL.Query().Get("set")))
	number := strings.TrimSpace(r.URL.Query().Get("number"))
	if set == "" || number == "" {
		http.Error(w, "set and number are required", 400)
		return
	}
	var c catalogCardOut
	var image bool
	err := db.QueryRow(`
		SELECT s.set_code, COALESCE(t.name, ''), s.collector_number, s.name, COALESCE(s.scryfall_id, ''),
		       COALESCE(s.rarity, ''), COALESCE(s.type_line, ''), COALESCE(s.image_normal, '') <> '',
		       COALESCE(s.image_back_normal, '') <> '', s.price_eur, s.price_eur_foil
		FROM scryfall_cards s LEFT JOIN sets t ON t.code = s.set_code
		WHERE s.set_code = ? AND s.collector_number = ?`, set, number).
		Scan(&c.SetCode, &c.SetName, &c.CollectorNumber, &c.Name, &c.ScryfallID, &c.Rarity, &c.TypeLine,
			&image, &c.HasBack, &c.PriceEur, &c.PriceEurFoil)
	if err != nil {
		http.Error(w, "No such printing", 404)
		return
	}
	c.SetCode = strings.ToUpper(c.SetCode)
	if image {
		c.ImageID = c.ScryfallID
	}
	writeJSON(w, c)
}

// levenshtein with two reused rows; the inputs are short card names.
func levenshtein(a, b string) int {
	ra, rb := []rune(a), []rune(b)
	prev := make([]int, len(rb)+1)
	cur := make([]int, len(rb)+1)
	for j := range prev {
		prev[j] = j
	}
	for i := 1; i <= len(ra); i++ {
		cur[0] = i
		for j := 1; j <= len(rb); j++ {
			cost := 1
			if ra[i-1] == rb[j-1] {
				cost = 0
			}
			cur[j] = min(prev[j]+1, cur[j-1]+1, prev[j-1]+cost)
		}
		prev, cur = cur, prev
	}
	return prev[len(rb)]
}

type catalogHit struct {
	Name      string            `json:"name"`
	Score     float64           `json:"score"`
	Printings []catalogHitPrint `json:"printings"`
}

type catalogHitPrint struct {
	SetCode         string `json:"setCode"`
	CollectorNumber string `json:"collectorNumber"`
	ScryfallID      string `json:"scryfallId"`
	ReleasedAt      string `json:"releasedAt"`
	Rarity          string `json:"rarity"`
	ImageID         string `json:"imageId"`
}

// searchNames ranks catalog names by similarity to an OCR reading. Only names
// of similar length are compared - a few thousand edit distances on short
// strings, well under a millisecond each batch.
func searchNames(c *catalogData, query string, limit int) []catalogHit {
	q := normalizeName(query)
	if len([]rune(q)) < 3 {
		return nil
	}
	type scored struct {
		name  string
		score float64
	}
	var hits []scored
	ql := len([]rune(q))
	for _, n := range c.names {
		nl := len([]rune(n))
		if nl < ql-3 || nl > ql+3 {
			continue
		}
		d := levenshtein(q, n)
		longest := max(ql, nl)
		score := 1 - float64(d)/float64(longest)
		if score >= 0.6 {
			hits = append(hits, scored{n, score})
		}
	}
	sort.Slice(hits, func(i, j int) bool {
		if hits[i].score != hits[j].score {
			return hits[i].score > hits[j].score
		}
		return hits[i].name < hits[j].name
	})

	// Several normalised names (a face and the full card) can point at the
	// same card; report each card once, under its real name.
	out := []catalogHit{}
	seen := map[string]bool{}
	for _, h := range hits {
		idx := c.byName[h.name]
		real := c.printings[idx[0]].Name
		if seen[real] {
			continue
		}
		seen[real] = true
		hit := catalogHit{Name: real, Score: float64(int(h.score*1000)) / 1000}
		for _, i := range idx {
			p := c.printings[i]
			img := ""
			if p.HasImage {
				img = p.ScryfallID
			}
			hit.Printings = append(hit.Printings, catalogHitPrint{
				SetCode: strings.ToUpper(p.SetCode), CollectorNumber: p.CollectorNumber, ScryfallID: p.ScryfallID,
				ReleasedAt: p.ReleasedAt, Rarity: p.Rarity, ImageID: img,
			})
		}
		out = append(out, hit)
		if len(out) == limit {
			break
		}
	}
	return out
}

func handleCatalogSearch(w http.ResponseWriter, r *http.Request) {
	name := r.URL.Query().Get("name")
	if len([]rune(normalizeName(name))) < 3 {
		http.Error(w, "name needs at least 3 letters", 400)
		return
	}
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	if limit <= 0 || limit > 50 {
		limit = 10
	}
	c, err := catalog()
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	writeJSON(w, searchNames(c, name, limit))
}
