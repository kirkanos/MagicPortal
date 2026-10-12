package main

import (
	"encoding/json"
	"math/rand"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"sync"
)

// Server-side search, filtering and grouping for the card grid.
//
// The grid used to download the whole collection and do this in the browser.
// That cost ~10 MB per visit and made the rules text undroppable from
// /api/collection, because the search box looks into it.
//
// The logic here mirrors applyFilters()/groupCards() in grid.js one-to-one,
// including the parts that look odd out of context: the grouping key of the
// tile, the representative variant (English with image first), the exact
// tie-breakers of every sort order, and "colorless" meaning "no colours at
// all" rather than a colour named C.
//
// Rows are held in memory and rebuilt when the collection validator changes.
// A query then costs a few milliseconds instead of a SQLite scan per keystroke;
// the whole index is a handful of MB for a 15.000 card collection.

const cardsPageSize = 60

type cardRow struct {
	ID              int
	Name            string
	SetCode         string
	SetName         string
	CollectorNumber string
	Rarity          string
	Foil            string
	Language        string
	Condition       string
	Quantity        int
	PurchasePrice   float64
	Currency        string
	Added           string
	BinderName      string
	BinderType      string

	HasCatalog   bool
	TypeLine     string
	ManaCost     string
	OracleText   string
	Colors       []string
	ImageID      string
	HasBack      bool
	PriceEur     *float64
	PriceEurFoil *float64

	// Precomputed at index build: the search haystack and the parsed type line.
	haystack string
	types    []string
	subtypes []string
}

// marketPrice is the foil-aware per-copy price (variantMarketPrice in shared.js).
func (c *cardRow) marketPrice() float64 {
	isFoil := c.Foil != "" && strings.ToLower(c.Foil) != "normal"
	if isFoil && c.PriceEurFoil != nil {
		return *c.PriceEurFoil
	}
	if c.PriceEur != nil {
		return *c.PriceEur
	}
	return 0
}

func (c *cardRow) hasImage() bool { return c.HasCatalog && c.ImageID != "" }

// ---- in-memory index ----

var (
	cardIndexMu   sync.Mutex
	cardIndexETag string
	cardIndexRows []*cardRow
	cardIndexSets map[string]string // set code (upper) -> release date
)

// cardRows returns the current index, rebuilding it when the collection or the
// catalog changed. The validator is the same one the HTTP caching uses.
func cardRows() ([]*cardRow, map[string]string, error) {
	etag := collectionETag(db)
	cardIndexMu.Lock()
	defer cardIndexMu.Unlock()
	if etag == cardIndexETag && cardIndexRows != nil {
		return cardIndexRows, cardIndexSets, nil
	}

	rows, err := db.Query(`
		SELECT c.id, c.name, c.set_code, c.set_name, c.collector_number, c.rarity, c.foil, c.language,
		       c.condition, c.quantity, c.purchase_price, c.currency, c.added, c.binder_name, c.binder_type,
		       s.scryfall_id, COALESCE(s.type_line, ''), COALESCE(s.mana_cost, ''), COALESCE(s.oracle_text, ''),
		       COALESCE(s.colors, ''), COALESCE(s.image_normal, '') <> '',
		       COALESCE(s.image_back_normal, '') <> '', s.price_eur, s.price_eur_foil
		FROM collection c
		LEFT JOIN scryfall_cards s
		       ON s.set_code = c.set_code AND s.collector_number = c.collector_number
		ORDER BY c.name`)
	if err != nil {
		return nil, nil, err
	}
	defer rows.Close()

	out := make([]*cardRow, 0, 16000)
	for rows.Next() {
		var c cardRow
		var sID, colorsJSON *string
		var hasImage bool
		var priceEur, priceFoil *float64
		if err := rows.Scan(&c.ID, &c.Name, &c.SetCode, &c.SetName, &c.CollectorNumber, &c.Rarity, &c.Foil,
			&c.Language, &c.Condition, &c.Quantity, &c.PurchasePrice, &c.Currency, &c.Added,
			&c.BinderName, &c.BinderType,
			&sID, &c.TypeLine, &c.ManaCost, &c.OracleText, &colorsJSON, &hasImage, &c.HasBack,
			&priceEur, &priceFoil); err != nil {
			return nil, nil, err
		}
		c.SetCode = strings.ToUpper(c.SetCode)
		c.PriceEur, c.PriceEurFoil = priceEur, priceFoil
		if sID != nil {
			c.HasCatalog = true
			// With no catalog image the id stays empty: a URL derived from
			// it would not exist at Scryfall.
			if hasImage {
				c.ImageID = *sID
			}
		}
		if colorsJSON != nil {
			c.Colors = colorsFromJSON(*colorsJSON)
		}
		// Same haystack as the browser: name, rules text and the mana cost
		// without braces and slashes.
		stripped := strings.NewReplacer("{", "", "}", "", "/", "").Replace(c.ManaCost)
		c.haystack = strings.ToLower(c.Name + " " + c.OracleText + " " + stripped)
		c.types = cardTypesOf(c.TypeLine)
		c.subtypes = cardSubtypesOf(c.TypeLine)
		out = append(out, &c)
	}

	sets := map[string]string{}
	setRows, err := db.Query(`SELECT code, COALESCE(released_at, '') FROM sets`)
	if err == nil {
		for setRows.Next() {
			var code, rel string
			if err := setRows.Scan(&code, &rel); err == nil {
				sets[strings.ToUpper(code)] = rel
			}
		}
		setRows.Close()
	}

	cardIndexRows, cardIndexSets, cardIndexETag = out, sets, etag
	return cardIndexRows, cardIndexSets, nil
}

// cardSubtypesOf mirrors cardSubtypes() in shared.js: everything behind the em
// dash of each face.
func cardSubtypesOf(typeLine string) []string {
	seen := map[string]bool{}
	out := []string{}
	for _, face := range strings.Split(typeLine, "//") {
		parts := strings.Split(face, "—")
		if len(parts) < 2 {
			continue
		}
		for _, s := range strings.Fields(strings.Join(parts[1:], "—")) {
			if s != "" && !seen[s] {
				seen[s] = true
				out = append(out, s)
			}
		}
	}
	return out
}

// ---- Sorting the way the browser did ----
//
// The interface sorted with localeCompare, i.e. by Unicode collation: case and
// accents only count as a tie-breaker, and punctuation has an order of its own
// in which the comma comes before the apostrophe - byte-wise it is exactly the
// other way round. Without this, around 4 % of the names end up in a different
// place than before.
//
// Rather than pulling in a collation library, the table below covers the
// handful of characters that actually occur. Against the 9,920 names in the
// collection it produces the same order as localeCompare.

var collateFold = map[rune]string{
	'á': "a", 'à': "a", 'â': "a", 'ä': "a", 'ã': "a", 'å': "a",
	'é': "e", 'è': "e", 'ê': "e", 'ë': "e",
	'í': "i", 'ì': "i", 'î': "i", 'ï': "i",
	'ó': "o", 'ò': "o", 'ô': "o", 'ö': "o", 'õ': "o", 'ø': "o",
	'ú': "u", 'ù': "u", 'û': "u", 'ü': "u",
	'ñ': "n", 'ç': "c", 'ý': "y", 'ÿ': "y", 'š': "s", 'ž': "z",
	'ß': "ss", 'æ': "ae", 'œ': "oe",
}

// collatePunct is the order of the punctuation characters per DUCET.
const collatePunct = " _-–—,;:!?.…'\"()[]{}@*/\\&#%+<=>~$€"

var collateWeight = func() map[rune]rune {
	m := map[rune]rune{}
	for i, ch := range collatePunct {
		m[ch] = rune(1 + i)
	}
	return m
}()

// collateKey builds the comparison key; equal keys fall back to the raw string.
func collateKey(s string) string {
	var b strings.Builder
	b.Grow(len(s))
	for _, r := range strings.ToLower(s) {
		if folded, ok := collateFold[r]; ok {
			for _, fr := range folded {
				writeCollated(&b, fr)
			}
			continue
		}
		writeCollated(&b, r)
	}
	return b.String()
}

func writeCollated(b *strings.Builder, r rune) {
	if w, ok := collateWeight[r]; ok {
		b.WriteRune(w)
		return
	}
	b.WriteRune(r)
}

// compareNames orders like String.prototype.localeCompare does in the browser.
func compareNames(a, b string) int {
	ka, kb := collateKey(a), collateKey(b)
	if ka != kb {
		return strings.Compare(ka, kb)
	}
	return strings.Compare(a, b)
}

// ---- query ----

type cardQuery struct {
	terms   []string
	set     string
	rarity  string
	color   string
	foil    string
	binder  string
	cType   string
	subtype string
	sort    string
	offset  int
	limit   int
}

func parseCardQuery(r *http.Request) cardQuery {
	q := r.URL.Query()
	cq := cardQuery{
		set:     q.Get("set"),
		rarity:  q.Get("rarity"),
		color:   q.Get("color"),
		foil:    q.Get("foil"),
		binder:  q.Get("binder"),
		cType:   q.Get("type"),
		subtype: q.Get("subtype"),
		sort:    q.Get("sort"),
		limit:   cardsPageSize,
	}
	for _, t := range strings.Fields(strings.ToLower(strings.TrimSpace(q.Get("q")))) {
		cq.terms = append(cq.terms, t)
	}
	if v, err := strconv.Atoi(q.Get("offset")); err == nil && v > 0 {
		cq.offset = v
	}
	if v, err := strconv.Atoi(q.Get("limit")); err == nil && v > 0 && v <= 500 {
		cq.limit = v
	}
	return cq
}

func (cq cardQuery) matches(c *cardRow) bool {
	for _, term := range cq.terms {
		if !strings.Contains(c.haystack, term) {
			return false
		}
	}
	if cq.set != "" && c.SetCode != cq.set {
		return false
	}
	if cq.rarity != "" && c.Rarity != cq.rarity {
		return false
	}
	if cq.foil != "" && c.Foil != cq.foil {
		return false
	}
	if cq.binder != "" && c.BinderName != cq.binder {
		return false
	}
	if cq.cType != "" && !contains(c.types, cq.cType) {
		return false
	}
	if cq.subtype != "" && !contains(c.subtypes, cq.subtype) {
		return false
	}
	if cq.color != "" {
		if cq.color == "C" {
			if len(c.Colors) != 0 {
				return false
			}
		} else if !contains(c.Colors, cq.color) {
			return false
		}
	}
	return true
}

func contains(list []string, want string) bool {
	for _, v := range list {
		if v == want {
			return true
		}
	}
	return false
}

// ---- grouping ----

type variantOut struct {
	Language      string   `json:"language"`
	Foil          string   `json:"foil"`
	Condition     string   `json:"condition"`
	Quantity      int      `json:"quantity"`
	PurchasePrice float64  `json:"purchasePrice"`
	Currency      string   `json:"currency"`
	Added         string   `json:"added"`
	BinderName    string   `json:"binderName"`
	BinderType    string   `json:"binderType"`
	MarketPrice   *float64 `json:"marketPrice"`
}

type groupOut struct {
	Key             string       `json:"key"`
	Name            string       `json:"name"`
	SetCode         string       `json:"setCode"`
	SetName         string       `json:"setName"`
	CollectorNumber string       `json:"collectorNumber"`
	Rarity          string       `json:"rarity"`
	TotalQty        int          `json:"totalQty"`
	AnyFoil         bool         `json:"anyFoil"`
	Langs           []string     `json:"langs"`
	MarketPrice     float64      `json:"marketPrice"`
	PurchasePrice   float64      `json:"purchasePrice"`
	Added           string       `json:"added"`
	Released        string       `json:"released"`
	ImageID         string       `json:"imageId"`
	HasBack         bool         `json:"hasBack"`
	TypeLine        string       `json:"typeLine"`
	ManaCost        string       `json:"manaCost"`
	OracleText      string       `json:"oracleText"`
	Colors          []string     `json:"colors"`
	Variants        []variantOut `json:"variants"`
}

func groupKeyOf(c *cardRow) string {
	if c.CollectorNumber != "" {
		return c.SetCode + "|n:" + c.CollectorNumber
	}
	return c.SetCode + "|name:" + strings.ToLower(c.Name)
}

// pickRep mirrors pickRep() in grid.js: an English printing with an image wins,
// then any printing with an image, then simply the first one.
func pickRep(variants []*cardRow) *cardRow {
	for _, v := range variants {
		if v.hasImage() && strings.EqualFold(v.Language, "en") {
			return v
		}
	}
	for _, v := range variants {
		if v.hasImage() {
			return v
		}
	}
	return variants[0]
}

func buildGroups(rows []*cardRow, sets map[string]string) []*groupOut {
	order := []string{}
	buckets := map[string][]*cardRow{}
	for _, c := range rows {
		k := groupKeyOf(c)
		if _, ok := buckets[k]; !ok {
			order = append(order, k)
		}
		buckets[k] = append(buckets[k], c)
	}

	out := make([]*groupOut, 0, len(order))
	for _, k := range order {
		vs := buckets[k]
		rep := pickRep(vs)
		g := &groupOut{
			Key: k, Name: vs[0].Name, SetCode: vs[0].SetCode, SetName: vs[0].SetName,
			CollectorNumber: vs[0].CollectorNumber,
			Rarity:          rep.Rarity, PurchasePrice: rep.PurchasePrice,
			MarketPrice: rep.marketPrice(), ImageID: rep.ImageID, HasBack: rep.HasBack,
			TypeLine: rep.TypeLine, ManaCost: rep.ManaCost, OracleText: rep.OracleText,
			Colors: rep.Colors, Released: sets[vs[0].SetCode],
			Langs: []string{}, Variants: make([]variantOut, 0, len(vs)),
		}
		if g.Colors == nil {
			g.Colors = []string{}
		}
		seen := map[string]bool{}
		for _, v := range vs {
			g.TotalQty += v.Quantity
			if v.Foil != "" && v.Foil != "normal" {
				g.AnyFoil = true
			}
			if l := strings.ToLower(v.Language); l != "" && !seen[l] {
				seen[l] = true
				g.Langs = append(g.Langs, l)
			}
			if v.Added > g.Added {
				g.Added = v.Added
			}
			mp := v.marketPrice()
			var mpp *float64
			if v.HasCatalog {
				mpp = &mp
			}
			g.Variants = append(g.Variants, variantOut{
				Language: v.Language, Foil: v.Foil, Condition: v.Condition, Quantity: v.Quantity,
				PurchasePrice: v.PurchasePrice, Currency: v.Currency, Added: v.Added,
				BinderName: v.BinderName, BinderType: v.BinderType, MarketPrice: mpp,
			})
		}
		out = append(out, g)
	}
	return out
}

// sortGroups applies the same orders and tie-breakers as the browser did.
func sortGroups(groups []*groupOut, mode string) {
	cmpName := func(a, b *groupOut) int { return compareNames(a.Name, b.Name) }
	less := func(i, j int) bool { return cmpName(groups[i], groups[j]) < 0 }
	switch mode {
	case "set":
		less = func(i, j int) bool {
			a, b := groups[i], groups[j]
			if c := compareNames(a.SetCode, b.SetCode); c != 0 {
				return c < 0
			}
			return cmpName(a, b) < 0
		}
	case "price-desc":
		less = func(i, j int) bool { return groups[i].PurchasePrice > groups[j].PurchasePrice }
	case "price-asc":
		less = func(i, j int) bool { return groups[i].PurchasePrice < groups[j].PurchasePrice }
	case "value-desc":
		less = func(i, j int) bool {
			a, b := groups[i], groups[j]
			if a.MarketPrice != b.MarketPrice {
				return a.MarketPrice > b.MarketPrice
			}
			return cmpName(a, b) < 0
		}
	case "value-asc":
		less = func(i, j int) bool {
			a, b := groups[i], groups[j]
			if a.MarketPrice != b.MarketPrice {
				return a.MarketPrice < b.MarketPrice
			}
			return cmpName(a, b) < 0
		}
	case "rarity":
		less = func(i, j int) bool { return compareNames(groups[i].Rarity, groups[j].Rarity) < 0 }
	case "added-desc":
		less = func(i, j int) bool { return strings.Compare(groups[j].Added, groups[i].Added) < 0 }
	case "released-desc":
		less = func(i, j int) bool {
			a, b := groups[i], groups[j]
			if c := strings.Compare(b.Released, a.Released); c != 0 {
				return c < 0
			}
			return cmpName(a, b) < 0
		}
	case "released-asc":
		less = func(i, j int) bool {
			a, b := groups[i], groups[j]
			ra, rb := a.Released, b.Released
			if ra == "" {
				ra = "9999"
			}
			if rb == "" {
				rb = "9999"
			}
			if c := strings.Compare(ra, rb); c != 0 {
				return c < 0
			}
			return cmpName(a, b) < 0
		}
	}
	// Stable, so equal entries keep the order they had in the browser too.
	sort.SliceStable(groups, less)
}

// ---- handlers ----

func handleCards(w http.ResponseWriter, r *http.Request) {
	cq := parseCardQuery(r)
	// The validator covers the data and the query: a different filter is a
	// different response.
	if notModified(w, r, weakETag("q", collectionETag(db)+"|"+r.URL.RawQuery)) {
		return
	}
	rows, sets, err := cardRows()
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}

	matched := make([]*cardRow, 0, 256)
	for _, c := range rows {
		if cq.matches(c) {
			matched = append(matched, c)
		}
	}
	groups := buildGroups(matched, sets)
	sortGroups(groups, cq.sort)

	total := len(groups)
	start := cq.offset
	if start > total {
		start = total
	}
	end := start + cq.limit
	if end > total {
		end = total
	}
	page := groups[start:end]

	writeJSONCacheable(w, map[string]interface{}{
		"groups":  page,
		"total":   total,
		"offset":  start,
		"hasMore": end < total,
	})
}

// handleCardFacets serves everything the filter controls need: which values
// actually occur in the collection, plus the counters above the grid. Computed
// over the whole index, so the lists stay stable while filtering.
func handleCardFacets(w http.ResponseWriter, r *http.Request) {
	if notModified(w, r, weakETag("f", collectionETag(db))) {
		return
	}
	rows, _, err := cardRows()
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}

	rarities := map[string]bool{}
	colors := map[string]bool{}
	colorless := false
	binders := map[string]bool{}
	foils := map[string]bool{}
	types := map[string]bool{}
	subtypesByType := map[string]map[string]bool{}
	setNames := map[string]string{}
	groupKeys := map[string]bool{}

	var totalQty int
	var purchase, market float64

	for _, c := range rows {
		if c.Rarity != "" {
			rarities[c.Rarity] = true
		}
		for _, col := range c.Colors {
			colors[col] = true
		}
		if c.HasCatalog && len(c.Colors) == 0 {
			colorless = true
		}
		if c.BinderName != "" {
			binders[c.BinderName] = true
		}
		if c.Foil != "" {
			foils[c.Foil] = true
		}
		for _, tp := range c.types {
			types[tp] = true
			if subtypesByType[tp] == nil {
				subtypesByType[tp] = map[string]bool{}
			}
			for _, st := range c.subtypes {
				subtypesByType[tp][st] = true
			}
		}
		if _, ok := setNames[c.SetCode]; !ok {
			setNames[c.SetCode] = c.SetName
		}
		groupKeys[groupKeyOf(c)] = true
		totalQty += c.Quantity
		purchase += c.PurchasePrice * float64(c.Quantity)
		market += c.marketPrice() * float64(c.Quantity)
	}

	setList := []map[string]string{}
	for code, name := range setNames {
		setList = append(setList, map[string]string{"code": code, "name": name})
	}
	sort.Slice(setList, func(i, j int) bool { return setList[i]["name"] < setList[j]["name"] })

	subs := map[string][]string{}
	for tp, set := range subtypesByType {
		subs[tp] = sortedKeys(set)
	}

	writeJSONCacheable(w, map[string]interface{}{
		"sets":           setList,
		"rarities":       sortedKeys(rarities),
		"colors":         sortedKeys(colors),
		"colorless":      colorless,
		"binders":        sortedKeys(binders),
		"foils":          sortedKeys(foils),
		"types":          sortedKeys(types),
		"subtypesByType": subs,
		"stats": map[string]interface{}{
			"entries":       len(rows),
			"distinct":      len(groupKeys),
			"totalQty":      totalQty,
			"purchaseValue": round2(purchase),
			"marketValue":   round2(market),
		},
	})
}

// handleRandomCards returns a few random cards for the strip on the cards page.
func handleRandomCards(w http.ResponseWriter, r *http.Request) {
	n := 6
	if v, err := strconv.Atoi(r.URL.Query().Get("count")); err == nil && v > 0 && v <= 30 {
		n = v
	}
	rows, sets, err := cardRows()
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	withImage := make([]*cardRow, 0, len(rows))
	for _, c := range rows {
		if c.hasImage() {
			withImage = append(withImage, c)
		}
	}
	if n > len(withImage) {
		n = len(withImage)
	}
	picked := make([]*cardRow, 0, n)
	for _, i := range rand.Perm(len(withImage))[:n] {
		picked = append(picked, withImage[i])
	}
	// Random by definition, so no caching headers here.
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	_ = json.NewEncoder(w).Encode(buildGroups(picked, sets))
}

func sortedKeys(m map[string]bool) []string {
	out := make([]string, 0, len(m))
	for k := range m {
		out = append(out, k)
	}
	sort.Strings(out)
	return out
}
