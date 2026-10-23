package main

import (
	"encoding/json"
	"net/http"
	"sort"
	"strings"
)

// Aggregates for clients that should not download the whole collection.
//
// Two pages of the web UI load /api/collection - 7.8 MB of JSON for a real
// collection - only to boil it down in the browser: the editions page groups it
// by set, the deck checker turns it into a name -> quantity map. A phone on a
// mobile connection should not pay that, so both reductions live here as well.
// They run over cardRows(), the in-memory index the card grid already uses, and
// therefore cost no extra query on the single database connection.
//
// The logic mirrors buildEditions() and buildOwnedIndex() in the web UI one to
// one, including the "distinct card" rule: the same collector number in several
// languages or finishes counts once.

type editionOut struct {
	Code         string  `json:"code"`
	Name         string  `json:"name"`
	IconSvgURI   string  `json:"iconSvgUri"`
	SetType      string  `json:"setType"`
	ReleasedAt   string  `json:"releasedAt"`
	Owned        int     `json:"owned"`
	Total        *int    `json:"total"`   // null when the set is unknown to the catalog
	Missing      *int    `json:"missing"` // null for the same reason
	DupeCount    int     `json:"dupeCount"`
	DupeExtra    int     `json:"dupeExtra"`
	TotalCopies  int     `json:"totalCopies"`
	MarketValue  float64 `json:"marketValue"`
	InCollection bool    `json:"inCollection"`
}

// setCardOwned is what the set overlay shows for one owned card: how many
// copies, split by language and by binder.
type setCardOwned struct {
	Copies   int            `json:"copies"`
	ByLang   map[string]int `json:"byLang"`
	ByBinder map[string]int `json:"byBinder"`
}

// normNumber mirrors normNumber() in editions.js: the collector number without
// leading zeros, so "047" from one source and "47" from another meet.
func normNumber(n string) string {
	s := strings.ToLower(strings.TrimSpace(n))
	i := 0
	for i < len(s)-1 && s[i] == '0' && s[i+1] >= '0' && s[i+1] <= '9' {
		i++
	}
	return s[i:]
}

// distinctKey mirrors distinctKey() in editions.js. Rows without a collector
// number fall back to the name.
func distinctKey(c *cardRow) string {
	if c.CollectorNumber != "" {
		return "n:" + normNumber(c.CollectorNumber)
	}
	return "name:" + strings.ToLower(c.Name)
}

// ownedBySet groups the collection by lowercase set code and, within a set,
// by distinct card.
func ownedBySet(rows []*cardRow) map[string]map[string]*setCardOwned {
	out := map[string]map[string]*setCardOwned{}
	for _, c := range rows {
		lc := strings.ToLower(c.SetCode)
		set := out[lc]
		if set == nil {
			set = map[string]*setCardOwned{}
			out[lc] = set
		}
		key := distinctKey(c)
		d := set[key]
		if d == nil {
			d = &setCardOwned{ByLang: map[string]int{}, ByBinder: map[string]int{}}
			set[key] = d
		}
		d.Copies += c.Quantity
		lang := strings.ToLower(c.Language)
		if lang == "" {
			lang = "?"
		}
		d.ByLang[lang] += c.Quantity
		if c.BinderName != "" {
			d.ByBinder[c.BinderName] += c.Quantity
		}
	}
	return out
}

type setInfo struct {
	name, setType, releasedAt, icon string
	cardCount                       int
	digital                         bool
}

func handleEditions(w http.ResponseWriter, r *http.Request) {
	// Both inputs change independently: the collection on an import, the set
	// list on a sets sync. The validator covers both.
	if notModified(w, r, weakETag("e", collectionETag(db)+"|"+setsETag(db))) {
		return
	}
	rows, _, err := cardRows()
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}

	sets := map[string]setInfo{}
	setRows, err := db.Query(`SELECT code, COALESCE(name, ''), COALESCE(card_count, 0), COALESCE(set_type, ''),
		COALESCE(digital, 0), COALESCE(released_at, ''), COALESCE(icon_svg_uri, '') FROM sets`)
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	for setRows.Next() {
		var code string
		var s setInfo
		var dig int
		if err := setRows.Scan(&code, &s.name, &s.cardCount, &s.setType, &dig, &s.releasedAt, &s.icon); err != nil {
			setRows.Close()
			http.Error(w, err.Error(), 500)
			return
		}
		s.digital = dig != 0
		sets[strings.ToLower(code)] = s
	}
	setRows.Close()

	// Owned rows per set, plus the set code and name as the collection
	// spells them - used when the catalog does not know the set.
	type ownedSet struct {
		code, name string
		rows       []*cardRow
	}
	owned := map[string]*ownedSet{}
	for _, c := range rows {
		lc := strings.ToLower(c.SetCode)
		o := owned[lc]
		if o == nil {
			o = &ownedSet{code: c.SetCode, name: c.SetName}
			owned[lc] = o
		}
		o.rows = append(o.rows, c)
	}
	distinct := ownedBySet(rows)

	// Union: every owned set plus every non-digital set of the catalog. Which
	// of them are shown is the client's set-type filter.
	codes := map[string]bool{}
	for lc := range owned {
		codes[lc] = true
	}
	for lc, s := range sets {
		if !s.digital {
			codes[lc] = true
		}
	}

	out := make([]editionOut, 0, len(codes))
	for lc := range codes {
		info, known := sets[lc]
		o := owned[lc]
		e := editionOut{Code: strings.ToUpper(lc)}
		if o != nil {
			e.Code = o.code
		}
		switch {
		case known && info.name != "":
			e.Name = info.name
		case o != nil && o.name != "":
			e.Name = o.name
		default:
			e.Name = strings.ToUpper(lc)
		}
		if known {
			e.IconSvgURI, e.SetType, e.ReleasedAt = info.icon, info.setType, info.releasedAt
		}

		for _, d := range distinct[lc] {
			e.Owned++
			if d.Copies > 1 {
				e.DupeCount++
				e.DupeExtra += d.Copies - 1
			}
		}
		if o != nil {
			for _, c := range o.rows {
				e.TotalCopies += c.Quantity
				e.MarketValue += c.marketPrice() * float64(c.Quantity)
			}
		}
		if known {
			total := info.cardCount
			missing := total - e.Owned
			if missing < 0 {
				missing = 0
			}
			e.Total, e.Missing = &total, &missing
		}
		e.InCollection = e.Owned > 0
		out = append(out, e)
	}

	// The client sorts; a stable order here keeps the response cacheable and
	// diffable.
	sort.Slice(out, func(i, j int) bool {
		if out[i].ReleasedAt != out[j].ReleasedAt {
			return out[i].ReleasedAt > out[j].ReleasedAt
		}
		return out[i].Code < out[j].Code
	})
	writeJSONCacheable(w, out)
}

// handleOwnedByName answers the deck checker's question - how many copies of
// each of these names do I own - without shipping the collection. Names are
// matched case-insensitively and every asked name is in the answer, with 0 when
// it is not owned, so the client needs no fallback.
func handleOwnedByName(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Names []string `json:"names"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20)).Decode(&in); err != nil {
		http.Error(w, "Invalid JSON: "+err.Error(), 400)
		return
	}
	if len(in.Names) > 500 {
		http.Error(w, "At most 500 names per request", 400)
		return
	}
	rows, _, err := cardRows()
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}

	out := make(map[string]int, len(in.Names))
	for _, n := range in.Names {
		if k := strings.ToLower(strings.TrimSpace(n)); k != "" {
			out[k] = 0
		}
	}
	for _, c := range rows {
		k := strings.ToLower(c.Name)
		if _, asked := out[k]; asked {
			out[k] += c.Quantity
		}
	}
	writeJSON(w, out)
}
