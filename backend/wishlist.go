package main

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"net/http"
	"sort"
	"strings"
	"time"
)

// Wishlist: cards you are missing, marked wherever you notice they are missing.
//
// Identity is the card NAME, not a printing – you want "Mishra's Workshop", not
// necessarily the Antiquities one. That matches the rest of the code
// (reserved.go, deck-checker.js both key by name). set_code/collector_number
// stay optional for the case where the printing really does matter; when they
// are set, that printing's price and image are shown instead of the cheapest.
//
// Condition is an annotation, not a filter: Scryfall carries exactly one price
// per printing (the Cardmarket trend), with no condition dimension anywhere. So
// min_condition says what you would accept when buying, it travels to Cardmarket
// with the export, and it is compared against what you already own – but it can
// never sharpen a price.

// conditionRank orders the condition vocabulary that csvimport.go stores
// verbatim from the CSV. Higher is better. Both spellings of "lightly played"
// occur in real exports, hence the duplicate entries.
//
// An unknown value deliberately has no rank: it means "no statement", not
// "worst", so it never triggers an upgrade suggestion.
var conditionRank = map[string]int{
	"mint":              7,
	"near_mint":         6,
	"excellent":         5,
	"good":              4,
	"light_played":      3,
	"lightly_played":    3,
	"played":            2,
	"moderately_played": 2,
	"heavily_played":    1,
	"poor":              0,
	"damaged":           0,
}

func conditionRankOf(c string) (int, bool) {
	r, ok := conditionRank[strings.ToLower(strings.TrimSpace(c))]
	return r, ok
}

// ---- shared catalog aggregation ----

// catalogPrinting is one printing of a card, as the catalog knows it.
type catalogPrinting struct {
	Price           *float64
	ImageID         string
	Rarity          string
	CardmarketURI   string
	SetCode         string
	CollectorNumber string
}

// cheapestByName reduces the catalog to one entry per card name: the cheapest
// printing, with its image, rarity and Cardmarket link. `where` is a SQL
// condition on scryfall_cards (aliased `c`).
//
// Used by both the Reserved List ("what would the rest cost?") and the wishlist
// ("what does this card cost right now?") – the two questions are the same
// aggregation, so it lives here rather than twice.
//
// Digital and memorabilia sets are excluded, because both answer the question
// wrongly: the cheapest Black Lotus in the raw catalog is the 30th Anniversary
// proxy at ~2,300 EUR and the cheapest Force of Will a gold-bordered World
// Championship card – neither is a card you can play, and neither is what
// "cheapest printing" is meant to tell you. The first real printing of a Lotus
// costs ~14,000 EUR.
func cheapestByName(where string, args ...interface{}) (map[string]*catalogPrinting, error) {
	rows, err := db.Query(`
		SELECT c.name, c.price_eur,
		       CASE WHEN COALESCE(c.image_normal, '') = '' THEN '' ELSE c.scryfall_id END,
		       c.rarity, COALESCE(c.cardmarket_uri, ''), c.set_code, c.collector_number
		FROM scryfall_cards c
		LEFT JOIN sets s ON s.code = c.set_code
		WHERE (`+where+`)
		  AND COALESCE(s.digital, 0) = 0
		  AND COALESCE(s.set_type, '') <> 'memorabilia'`, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := map[string]*catalogPrinting{}
	for rows.Next() {
		var name, img, rarity, cardmarket, setCode, number string
		var price *float64
		if err := rows.Scan(&name, &price, &img, &rarity, &cardmarket, &setCode, &number); err != nil {
			return nil, err
		}
		cur := out[name]
		if cur == nil {
			out[name] = &catalogPrinting{
				Price: price, ImageID: img, Rarity: rarity, CardmarketURI: cardmarket,
				SetCode: setCode, CollectorNumber: number,
			}
			continue
		}
		// A cheaper printing wins outright; otherwise only fill in what is still
		// missing, so an entry without an image/link gets one from a sibling.
		if price != nil && (cur.Price == nil || *price < *cur.Price) {
			cur.Price = price
			cur.SetCode, cur.CollectorNumber = setCode, number
			if img != "" {
				cur.ImageID = img
			}
			if cardmarket != "" {
				cur.CardmarketURI = cardmarket
			}
			continue
		}
		if cur.ImageID == "" && img != "" {
			cur.ImageID = img
		}
		if cur.CardmarketURI == "" && cardmarket != "" {
			cur.CardmarketURI = cardmarket
		}
	}
	return out, rows.Err()
}

// ---- API ----

type wishlistItem struct {
	Name            string   `json:"name"`
	SetCode         string   `json:"setCode,omitempty"`
	CollectorNumber string   `json:"collectorNumber,omitempty"`
	TargetEur       *float64 `json:"targetEur"`
	MinCondition    string   `json:"minCondition,omitempty"`
	Note            string   `json:"note,omitempty"`
	Priority        int      `json:"priority"`
	Added           string   `json:"added"`

	// From the catalog: the printing the price refers to – the pinned one when
	// set_code/collector_number are filled, else the cheapest.
	ImageID          string   `json:"imageId"`
	Rarity           string   `json:"rarity,omitempty"`
	CardmarketURI    string   `json:"cardmarketUri,omitempty"`
	Price            *float64 `json:"price"`
	PriceSetCode     string   `json:"priceSetCode,omitempty"`
	PriceSetName     string   `json:"priceSetName,omitempty"`
	PriceNumber      string   `json:"priceNumber,omitempty"`
	Pinned           bool     `json:"pinned,omitempty"`
	UnknownToCatalog bool     `json:"unknownToCatalog,omitempty"`

	// Price movement, from the daily snapshots snapshotValue() writes for
	// wished cards as well as owned ones.
	PriceThen *float64 `json:"priceThen"`
	ChangePct *float64 `json:"changePct"`

	TargetMet bool `json:"targetMet,omitempty"`

	// What you already own of this card, across all printings and languages.
	OwnedQuantity int    `json:"ownedQuantity"`
	BestCondition string `json:"bestCondition,omitempty"`
	NeedsUpgrade  bool   `json:"needsUpgrade,omitempty"`
}

// wishlistMoversDays is the window for the "x % cheaper than before" line. Same
// default as the value-movers endpoint, for comparable numbers.
const wishlistMoversDays = 30

func handleWishlist(w http.ResponseWriter, r *http.Request) {
	items, err := loadWishlist()
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}

	var value float64
	var targetsMet, unpriced, owned, upgrades int
	for i := range items {
		if items[i].Price != nil {
			value += *items[i].Price
		} else {
			unpriced++
		}
		if items[i].TargetMet {
			targetsMet++
		}
		if items[i].OwnedQuantity > 0 {
			owned++
		}
		if items[i].NeedsUpgrade {
			upgrades++
		}
	}

	writeJSON(w, map[string]interface{}{
		"summary": map[string]interface{}{
			"items":      len(items),
			"value":      value,
			"targetsMet": targetsMet,
			"unpriced":   unpriced,
			"owned":      owned,
			"upgrades":   upgrades,
			"days":       wishlistMoversDays,
		},
		"items": items,
	})
}

// loadWishlist reads the table and enriches every entry with catalog data,
// price movement and what the collection already holds.
func loadWishlist() ([]wishlistItem, error) {
	rows, err := db.Query(`
		SELECT name, COALESCE(set_code, ''), COALESCE(collector_number, ''), target_eur,
		       COALESCE(min_condition, ''), COALESCE(note, ''), COALESCE(priority, 0), COALESCE(added, '')
		FROM wishlist`)
	if err != nil {
		return nil, err
	}
	items := []wishlistItem{}
	names := []string{}
	for rows.Next() {
		var it wishlistItem
		var target sql.NullFloat64
		if err := rows.Scan(&it.Name, &it.SetCode, &it.CollectorNumber, &target,
			&it.MinCondition, &it.Note, &it.Priority, &it.Added); err != nil {
			rows.Close()
			return nil, err
		}
		if target.Valid {
			v := target.Float64
			it.TargetEur = &v
		}
		items = append(items, it)
		names = append(names, it.Name)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}
	if len(items) == 0 {
		return items, nil
	}

	cheapest, err := cheapestByName("c.name IN ("+placeholders(len(names))+")", toArgs(names)...)
	if err != nil {
		return nil, err
	}
	pinned, err := pinnedPrintings(items)
	if err != nil {
		return nil, err
	}
	setNames, err := setNameIndex()
	if err != nil {
		return nil, err
	}
	pinnedKeys := []string{}
	for _, it := range items {
		if it.SetCode != "" && it.CollectorNumber != "" {
			pinnedKeys = append(pinnedKeys, strings.ToLower(it.SetCode)+"|"+it.CollectorNumber)
		}
	}
	thenByName, thenByKey, err := priceThen(names, pinnedKeys, wishlistMoversDays)
	if err != nil {
		return nil, err
	}
	ownedByName, err := ownedForNames(names)
	if err != nil {
		return nil, err
	}

	for i := range items {
		it := &items[i]
		src := cheapest[it.Name]
		if p, ok := pinned[pinKey(it.SetCode, it.CollectorNumber)]; ok && it.SetCode != "" {
			src = p
			it.Pinned = true
		}
		if src == nil {
			it.UnknownToCatalog = true
		} else {
			it.ImageID, it.Rarity, it.CardmarketURI = src.ImageID, src.Rarity, src.CardmarketURI
			it.Price = src.Price
			it.PriceSetCode = strings.ToUpper(src.SetCode)
			it.PriceSetName = setNames[strings.ToLower(src.SetCode)]
			it.PriceNumber = src.CollectorNumber
		}
		if it.Price != nil && it.TargetEur != nil && *it.Price <= *it.TargetEur {
			it.TargetMet = true
		}
		base, ok := thenByName[it.Name]
		if it.Pinned {
			base, ok = thenByKey[strings.ToLower(it.SetCode)+"|"+it.CollectorNumber]
		}
		if ok && it.Price != nil && base > 0 {
			b := base
			it.PriceThen = &b
			pct := ((*it.Price - base) / base) * 100
			it.ChangePct = &pct
		}
		if o, ok := ownedByName[it.Name]; ok {
			it.OwnedQuantity = o.quantity
			it.BestCondition = o.bestCondition
			it.NeedsUpgrade = needsUpgrade(o.bestCondition, it.MinCondition)
		}
	}

	// Priority first, then the expensive ones – that is the order in which the
	// list is usually worked through.
	sort.Slice(items, func(i, j int) bool {
		if items[i].Priority != items[j].Priority {
			return items[i].Priority > items[j].Priority
		}
		pi, pj := 0.0, 0.0
		if items[i].Price != nil {
			pi = *items[i].Price
		}
		if items[j].Price != nil {
			pj = *items[j].Price
		}
		if pi != pj {
			return pi > pj
		}
		return items[i].Name < items[j].Name
	})
	return items, nil
}

// needsUpgrade reports whether the best copy owned is worse than the wanted
// minimum. Either side unknown means no statement – and therefore no suggestion.
func needsUpgrade(have, want string) bool {
	if have == "" || want == "" {
		return false
	}
	h, okH := conditionRankOf(have)
	wv, okW := conditionRankOf(want)
	return okH && okW && h < wv
}

func pinKey(setCode, number string) string {
	return strings.ToLower(setCode) + "|" + number
}

// pinnedPrintings looks up exactly those printings an entry pinned explicitly.
func pinnedPrintings(items []wishlistItem) (map[string]*catalogPrinting, error) {
	out := map[string]*catalogPrinting{}
	for _, it := range items {
		if it.SetCode == "" || it.CollectorNumber == "" {
			continue
		}
		key := pinKey(it.SetCode, it.CollectorNumber)
		if _, done := out[key]; done {
			continue
		}
		var p catalogPrinting
		err := db.QueryRow(`
			SELECT price_eur,
			       CASE WHEN COALESCE(image_normal, '') = '' THEN '' ELSE scryfall_id END,
			       rarity, COALESCE(cardmarket_uri, ''), set_code, collector_number
			FROM scryfall_cards WHERE set_code = ? AND collector_number = ?`,
			strings.ToLower(it.SetCode), it.CollectorNumber).
			Scan(&p.Price, &p.ImageID, &p.Rarity, &p.CardmarketURI, &p.SetCode, &p.CollectorNumber)
		if err == sql.ErrNoRows {
			continue
		}
		if err != nil {
			return nil, err
		}
		out[key] = &p
	}
	return out, nil
}

func setNameIndex() (map[string]string, error) {
	rows, err := db.Query(`SELECT code, name FROM sets`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]string{}
	for rows.Next() {
		var code, name string
		if err := rows.Scan(&code, &name); err != nil {
			return nil, err
		}
		out[strings.ToLower(code)] = name
	}
	return out, rows.Err()
}

// priceThen looks up what the wished cards cost roughly `days` ago.
//
// Two indexes, because the entries are not all the same kind of want: an entry
// without a pinned printing asks "has this card become cheaper", so the
// cheapest price of the day over all its printings is the honest comparison
// (which printing is cheapest can change between two snapshots). A pinned entry
// asks about that one printing, so it is looked up by its key - comparing a
// pinned price against the cheapest of the day would invent a change that never
// happened.
func priceThen(names, keys []string, days int) (byName, byKey map[string]float64, err error) {
	byName, byKey = map[string]float64{}, map[string]float64{}

	cutoff := time.Now().UTC().AddDate(0, 0, -days).Format(dateLayout)
	var base, latest sql.NullString
	_ = db.QueryRow(`SELECT date FROM price_history WHERE date >= ? ORDER BY date ASC LIMIT 1`, cutoff).Scan(&base)
	_ = db.QueryRow(`SELECT MAX(date) FROM price_history`).Scan(&latest)
	if !base.Valid || base.String == "" || !latest.Valid || latest.String == base.String {
		return byName, byKey, nil
	}

	if len(names) > 0 {
		args := append([]interface{}{base.String}, toArgs(names)...)
		rows, qerr := db.Query(`
			SELECT name, MIN(price_eur) FROM price_history
			WHERE date = ? AND price_eur IS NOT NULL AND name IN (`+placeholders(len(names))+`)
			GROUP BY name`, args...)
		if qerr != nil {
			return nil, nil, qerr
		}
		for rows.Next() {
			var name string
			var price float64
			if err := rows.Scan(&name, &price); err != nil {
				rows.Close()
				return nil, nil, err
			}
			byName[name] = price
		}
		rows.Close()
		if err := rows.Err(); err != nil {
			return nil, nil, err
		}
	}

	if len(keys) > 0 {
		args := append([]interface{}{base.String}, toArgs(keys)...)
		rows, qerr := db.Query(`
			SELECT scryfall_key, price_eur FROM price_history
			WHERE date = ? AND price_eur IS NOT NULL AND scryfall_key IN (`+placeholders(len(keys))+`)`, args...)
		if qerr != nil {
			return nil, nil, qerr
		}
		defer rows.Close()
		for rows.Next() {
			var key string
			var price float64
			if err := rows.Scan(&key, &price); err != nil {
				return nil, nil, err
			}
			byKey[key] = price
		}
		if err := rows.Err(); err != nil {
			return nil, nil, err
		}
	}
	return byName, byKey, nil
}

type ownedInfo struct {
	quantity      int
	bestCondition string
}

// ownedForNames reports what the collection holds for each name, across all
// printings and languages, plus the best condition among those copies.
func ownedForNames(names []string) (map[string]ownedInfo, error) {
	rows, err := db.Query(`
		SELECT name, COALESCE(condition, ''), COALESCE(SUM(quantity), 0)
		FROM collection WHERE name IN (`+placeholders(len(names))+`)
		GROUP BY name, condition`, toArgs(names)...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]ownedInfo{}
	for rows.Next() {
		var name, cond string
		var qty int
		if err := rows.Scan(&name, &cond, &qty); err != nil {
			return nil, err
		}
		o := out[name]
		o.quantity += qty
		if best, ok := conditionRankOf(o.bestCondition); !ok {
			o.bestCondition = cond
		} else if cur, ok := conditionRankOf(cond); ok && cur > best {
			o.bestCondition = cond
		}
		out[name] = o
	}
	return out, rows.Err()
}

func placeholders(n int) string {
	if n <= 0 {
		return "NULL"
	}
	return strings.TrimSuffix(strings.Repeat("?,", n), ",")
}

func toArgs(values []string) []interface{} {
	out := make([]interface{}, len(values))
	for i, v := range values {
		out[i] = v
	}
	return out
}

// ---- write endpoints ----

type wishlistInput struct {
	Name            string   `json:"name"`
	SetCode         string   `json:"setCode"`
	CollectorNumber string   `json:"collectorNumber"`
	TargetEur       *float64 `json:"targetEur"`
	MinCondition    string   `json:"minCondition"`
	Note            string   `json:"note"`
	Priority        int      `json:"priority"`
}

func handleWishlistAdd(w http.ResponseWriter, r *http.Request) {
	if !authorized(r) {
		http.Error(w, "Wrong or missing password", 403)
		return
	}
	defer r.Body.Close()
	var in wishlistInput
	if err := json.NewDecoder(r.Body).Decode(&in); err != nil {
		http.Error(w, "Invalid entry: "+err.Error(), 400)
		return
	}
	in.Name = strings.TrimSpace(in.Name)
	if in.Name == "" {
		http.Error(w, "Name is required", 400)
		return
	}
	if in.MinCondition != "" {
		if _, ok := conditionRankOf(in.MinCondition); !ok {
			http.Error(w, "Unknown condition: "+in.MinCondition, 400)
			return
		}
		in.MinCondition = strings.ToLower(strings.TrimSpace(in.MinCondition))
	}
	if in.Priority < 0 {
		in.Priority = 0
	}
	if in.Priority > 2 {
		in.Priority = 2
	}
	// A pinned printing only makes sense complete; half of it would silently
	// fall back to "cheapest" and look like the pin had no effect.
	if (in.SetCode == "") != (in.CollectorNumber == "") {
		http.Error(w, "A pinned printing needs both setCode and collectorNumber", 400)
		return
	}

	// Existing entries keep their added date, so re-marking a card does not
	// reset how long it has been waiting.
	_, err := db.Exec(`
		INSERT INTO wishlist(name, set_code, collector_number, target_eur, min_condition, note, priority, added)
		VALUES(?, ?, ?, ?, ?, ?, ?, ?)
		ON CONFLICT(name) DO UPDATE SET
			set_code=excluded.set_code, collector_number=excluded.collector_number,
			target_eur=excluded.target_eur, min_condition=excluded.min_condition,
			note=excluded.note, priority=excluded.priority`,
		in.Name, strings.ToLower(in.SetCode), in.CollectorNumber, in.TargetEur,
		in.MinCondition, in.Note, in.Priority, time.Now().UTC().Format(time.RFC3339))
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	logActivity("info", "Wishlist", "Added to the wishlist: "+in.Name)
	writeJSON(w, map[string]interface{}{"name": in.Name, "saved": true})
}

func handleWishlistDelete(w http.ResponseWriter, r *http.Request) {
	if !authorized(r) {
		http.Error(w, "Wrong or missing password", 403)
		return
	}
	name := r.PathValue("name")
	if name == "" {
		http.Error(w, "Name is required", 400)
		return
	}
	res, err := db.Exec(`DELETE FROM wishlist WHERE name = ?`, name)
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	n, _ := res.RowsAffected()
	if n > 0 {
		logActivity("info", "Wishlist", "Removed from the wishlist: "+name)
	}
	writeJSON(w, map[string]interface{}{"name": name, "removed": n > 0})
}

// ---- Cardmarket export ----

// cardmarketChunk is Cardmarket's limit per wants list (100 lists per game).
const cardmarketChunk = 150

// handleWishlistExport renders the list in Cardmarket's decklist format, ready
// for Buying → My Wants → Add Decklist.
//
// Grouped by minimum condition because the decklist format has no token for it:
// condition is a field on the wants-list row, set in Cardmarket's own UI. One
// block per condition means setting it once per list instead of correcting 150
// rows by hand.
//
// The collector number is deliberately NOT exported. Cardmarket's "(V.1)" is a
// version counter within an expansion, not a collector number – writing one
// into the other would pin the wrong printing. The expansion is pinned, the
// version is not.
func handleWishlistExport(w http.ResponseWriter, r *http.Request) {
	items, err := loadWishlist()
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	setNames, err := setNameIndex()
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}

	// Entries you already own in a good enough condition are not wanted.
	byCondition := map[string][]string{}
	order := []string{}
	for _, it := range items {
		if it.OwnedQuantity > 0 && !it.NeedsUpgrade {
			continue
		}
		line := "1 " + it.Name
		if it.SetCode != "" {
			name := setNames[strings.ToLower(it.SetCode)]
			if name == "" {
				name = strings.ToUpper(it.SetCode)
			}
			line += " (" + name + ")"
		}
		if _, seen := byCondition[it.MinCondition]; !seen {
			order = append(order, it.MinCondition)
		}
		byCondition[it.MinCondition] = append(byCondition[it.MinCondition], line)
	}
	sort.Strings(order)

	var b strings.Builder
	for _, cond := range order {
		lines := byCondition[cond]
		label := cond
		if label == "" {
			label = "any condition"
		}
		for start := 0; start < len(lines); start += cardmarketChunk {
			end := start + cardmarketChunk
			if end > len(lines) {
				end = len(lines)
			}
			fmt.Fprintf(&b, "# %s (%d-%d of %d)\n", label, start+1, end, len(lines))
			b.WriteString(strings.Join(lines[start:end], "\n"))
			b.WriteString("\n\n")
		}
	}

	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	_, _ = w.Write([]byte(strings.TrimRight(b.String(), "\n") + "\n"))
}

// wishlistGauges counts entries, their current value and how many have reached
// their target price – the three numbers worth alerting on.
func wishlistGauges() (items int64, value float64, targetsMet int64) {
	all, err := loadWishlist()
	if err != nil {
		return 0, 0, 0
	}
	for _, it := range all {
		items++
		if it.Price != nil {
			value += *it.Price
		}
		if it.TargetMet {
			targetsMet++
		}
	}
	return
}
