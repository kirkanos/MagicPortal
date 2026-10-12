package main

import (
	"database/sql"
	"net/http"
	"regexp"
	"sort"
	"strconv"
	"strings"
)

// Pre-aggregated numbers for the statistics page.
//
// The page used to fetch /api/collection (~15 MB of JSON for a real collection,
// most of it oracle text and image URLs it never looks at) and reduce it in the
// browser. Everything here is computed from the same rows in Go instead, which
// turns that into a response of a few dozen KB.
//
// Two rules keep this honest:
//   - the aggregation logic mirrors dashboard.js one-to-one, including its
//     quirks (foil-aware prices, "distinct" keyed like the card grid, only
//     official card types, mana value ignoring X/Y/Z);
//   - only keys are returned, never display labels. Rarity, condition, language
//     and colour names stay in the frontend, which is where the translation
//     lives.

type dashboardOut struct {
	Stats         dashStats     `json:"stats"`
	Rarity        []keyQty      `json:"rarity"`
	Color         []keyQty      `json:"color"`
	Sets          []keyQty      `json:"sets"`
	SetValue      []setValue    `json:"setValue"`
	Foil          []keyQty      `json:"foil"`
	PurchaseMonth []monthValue  `json:"purchaseByMonth"`
	TopPurchase   []namedValue  `json:"topPurchase"`
	TopMarket     []namedValue  `json:"topMarket"`
	Types         []keyQty      `json:"types"`
	Language      []keyQty      `json:"language"`
	Condition     []keyQty      `json:"condition"`
	Cmc           []keyQty      `json:"cmc"`
	ValueDist     []int         `json:"valueDist"`
	ValueByColor  []keyValue    `json:"valueByColor"`
	ValueByRarity []keyValue    `json:"valueByRarity"`
	AddedPerMonth []monthQty    `json:"addedPerMonth"`
	RarityTable   []rarityRow   `json:"rarityTable"`
	SetTable      []setTableRow `json:"setTable"`
}

type dashStats struct {
	Entries       int     `json:"entries"`
	Distinct      int     `json:"distinct"`
	TotalQty      int     `json:"totalQty"`
	PurchaseValue float64 `json:"purchaseValue"`
	MarketValue   float64 `json:"marketValue"`
	SetCount      int     `json:"setCount"`
	BinderCount   int     `json:"binderCount"`
	FoilQty       int     `json:"foilQty"`
	TopCardName   string  `json:"topCardName"`
	TopCardPrice  float64 `json:"topCardPrice"`
}

type keyQty struct {
	Key string `json:"key"`
	Qty int    `json:"qty"`
}

type keyValue struct {
	Key   string  `json:"key"`
	Value float64 `json:"value"`
}

type setValue struct {
	Code  string  `json:"code"`
	Name  string  `json:"name"`
	Value float64 `json:"value"`
}

type namedValue struct {
	Name  string  `json:"name"`
	Value float64 `json:"value"`
}

type monthValue struct {
	Month string  `json:"month"`
	Value float64 `json:"value"`
}

type monthQty struct {
	Month string `json:"month"`
	Qty   int    `json:"qty"`
}

type rarityRow struct {
	Key     string  `json:"key"`
	Entries int     `json:"entries"`
	Qty     int     `json:"qty"`
	Market  float64 `json:"market"`
}

type setTableRow struct {
	Code    string  `json:"code"`
	Name    string  `json:"name"`
	Entries int     `json:"entries"`
	Qty     int     `json:"qty"`
	Market  float64 `json:"market"`
}

// officialTypes mirrors OFFICIAL_TYPES in shared.js: standard card types only,
// so joke/Un-set oddities don't show up as their own bar.
var officialTypes = map[string]bool{
	"Creature": true, "Instant": true, "Sorcery": true, "Enchantment": true,
	"Artifact": true, "Planeswalker": true, "Land": true, "Battle": true,
}

// valueBucketMax mirrors VALUE_BUCKETS in dashboard.js; the last bucket is open
// ended. Labels stay in the frontend, only the counts are returned.
var valueBucketMax = []float64{0.1, 0.5, 1, 5, 20, 100}

var manaTokenRe = regexp.MustCompile(`\{([^}]+)\}`)

// dashRow is one collection row with just the fields the statistics need.
type dashRow struct {
	quantity               int
	purchasePrice          float64
	foil                   string
	rarity                 string
	language               string
	condition              string
	setCode                string
	setName                string
	collectorNumber        string
	binderName             string
	name                   string
	added                  string
	typeLine               string
	manaCost               string
	colors                 []string
	priceEur, priceEurFoil sql.NullFloat64
}

// marketPrice is the per-copy price, foil-aware - the Go twin of
// variantMarketPrice() in shared.js and of foilAwareMarket in SQL.
func (r dashRow) marketPrice() float64 {
	isFoil := r.foil != "" && strings.ToLower(r.foil) != "normal"
	if isFoil && r.priceEurFoil.Valid {
		return r.priceEurFoil.Float64
	}
	if r.priceEur.Valid {
		return r.priceEur.Float64
	}
	return 0
}

// colorGroup mirrors colorGroup() in dashboard.js.
func (r dashRow) colorGroup() string {
	switch len(r.colors) {
	case 0:
		return "colorless"
	case 1:
		return r.colors[0]
	default:
		return "multi"
	}
}

// cardTypes mirrors cardTypes() in shared.js: per face, everything before the
// em dash, keeping only official types.
func cardTypesOf(typeLine string) []string {
	seen := map[string]bool{}
	out := []string{}
	for _, face := range strings.Split(typeLine, "//") {
		head := strings.Split(face, "—")[0]
		for _, w := range strings.Fields(head) {
			if officialTypes[w] && !seen[w] {
				seen[w] = true
				out = append(out, w)
			}
		}
	}
	return out
}

// manaValueOf mirrors manaValue() in shared.js. The bool reports whether a mana
// cost was present at all - cards without one (lands, tokens) are left out of
// the curve rather than counted as zero.
func manaValueOf(manaCost string) (int, bool) {
	if manaCost == "" {
		return 0, false
	}
	total := 0
	for _, m := range manaTokenRe.FindAllStringSubmatch(manaCost, -1) {
		s := m[1]
		if n, err := strconv.Atoi(s); err == nil {
			total += n
			continue
		}
		if s == "X" || s == "Y" || s == "Z" {
			continue
		}
		total++ // colored, hybrid, phyrexian, colorless, snow…
	}
	return total, true
}

func handleDashboard(w http.ResponseWriter, r *http.Request) {
	// Same validator as the collection: both views change together.
	if notModified(w, r, collectionETag(db)) {
		return
	}
	rows, err := db.Query(`
		SELECT c.quantity, c.purchase_price, c.foil, c.rarity, c.language, c.condition,
		       c.set_code, c.set_name, c.collector_number, c.binder_name, c.name, c.added,
		       COALESCE(s.type_line, ''), COALESCE(s.mana_cost, ''), COALESCE(s.colors, ''),
		       s.price_eur, s.price_eur_foil
		FROM collection c
		LEFT JOIN scryfall_cards s
		       ON s.set_code = c.set_code AND s.collector_number = c.collector_number`)
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	defer rows.Close()

	var all []dashRow
	for rows.Next() {
		var d dashRow
		var colorsJSON string
		if err := rows.Scan(&d.quantity, &d.purchasePrice, &d.foil, &d.rarity, &d.language, &d.condition,
			&d.setCode, &d.setName, &d.collectorNumber, &d.binderName, &d.name, &d.added,
			&d.typeLine, &d.manaCost, &colorsJSON, &d.priceEur, &d.priceEurFoil); err != nil {
			http.Error(w, err.Error(), 500)
			return
		}
		d.colors = colorsFromJSON(colorsJSON)
		// Set codes are stored lowercase but shown uppercase everywhere in the
		// UI (/api/collection does the same), so the chart labels match.
		d.setCode = strings.ToUpper(d.setCode)
		all = append(all, d)
	}

	writeJSONCacheable(w, aggregateDashboard(all))
}

// aggregateDashboard does the actual reduction. Split out from the handler so
// the logic is testable without a database.
func aggregateDashboard(all []dashRow) dashboardOut {
	out := dashboardOut{ValueDist: make([]int, len(valueBucketMax)+1)}

	distinct := map[string]bool{}
	setCodes := map[string]bool{}
	binders := map[string]bool{}

	rarityQty := map[string]int{}
	colorQty := map[string]int{}
	setQty := map[string]int{}
	setValueMap := map[string]float64{}
	setNames := map[string]string{}
	foilQtyMap := map[string]int{}
	purchaseMonth := map[string]float64{}
	addedMonth := map[string]int{}
	typeQty := map[string]int{}
	langQty := map[string]int{}
	condQty := map[string]int{}
	cmcQty := map[string]int{}
	valueByColor := map[string]float64{}
	valueByRarity := map[string]float64{}
	topMarketByName := map[string]float64{}
	rarityAgg := map[string]*rarityRow{}
	setAgg := map[string]*setTableRow{}

	// Insertion order of the first occurrence, so charts keep the same ordering
	// the browser produced from object keys.
	var rarityOrder, colorOrder, foilOrder, typeOrder, langOrder, condOrder []string
	remember := func(order *[]string, seen map[string]int, key string) {
		if _, ok := seen[key]; !ok {
			*order = append(*order, key)
		}
	}

	for _, c := range all {
		price := c.marketPrice()
		mv := price * float64(c.quantity)

		out.Stats.Entries++
		out.Stats.TotalQty += c.quantity
		out.Stats.PurchaseValue += c.purchasePrice * float64(c.quantity)
		out.Stats.MarketValue += mv
		if c.foil != "" && strings.ToLower(c.foil) != "normal" {
			out.Stats.FoilQty += c.quantity
		}
		if price > out.Stats.TopCardPrice {
			out.Stats.TopCardPrice = price
			out.Stats.TopCardName = c.name
		}

		// Grouping key of the card grid: merges languages/foil/condition.
		key := c.setCode + "|"
		if c.collectorNumber != "" {
			key += "n:" + c.collectorNumber
		} else {
			key += "name:" + strings.ToLower(c.name)
		}
		distinct[key] = true
		setCodes[c.setCode] = true
		if c.binderName != "" {
			binders[c.binderName] = true
		}

		remember(&rarityOrder, rarityQty, c.rarity)
		rarityQty[c.rarity] += c.quantity

		cg := c.colorGroup()
		remember(&colorOrder, colorQty, cg)
		colorQty[cg] += c.quantity
		valueByColor[cg] += mv

		setQty[c.setCode] += c.quantity
		setValueMap[c.setCode] += mv
		if _, ok := setNames[c.setCode]; !ok {
			setNames[c.setCode] = c.setName
		}

		remember(&foilOrder, foilQtyMap, c.foil)
		foilQtyMap[c.foil] += c.quantity

		valueByRarity[c.rarity] += mv

		if len(c.added) >= 7 {
			m := c.added[:7]
			purchaseMonth[m] += c.purchasePrice * float64(c.quantity)
			addedMonth[m] += c.quantity
		}

		for _, tp := range cardTypesOf(c.typeLine) {
			remember(&typeOrder, typeQty, tp)
			typeQty[tp] += c.quantity
		}

		lang := strings.ToUpper(c.language)
		if lang == "" {
			lang = "?"
		}
		remember(&langOrder, langQty, lang)
		langQty[lang] += c.quantity

		remember(&condOrder, condQty, c.condition)
		condQty[c.condition] += c.quantity

		if mval, ok := manaValueOf(c.manaCost); ok {
			bucket := strconv.Itoa(mval)
			if mval >= 7 {
				bucket = "7+"
			}
			cmcQty[bucket] += c.quantity
		}

		if price > topMarketByName[c.name] {
			topMarketByName[c.name] = price
		}

		out.ValueDist[valueBucket(price)] += c.quantity

		ra := rarityAgg[c.rarity]
		if ra == nil {
			ra = &rarityRow{Key: c.rarity}
			rarityAgg[c.rarity] = ra
		}
		ra.Entries++
		ra.Qty += c.quantity
		ra.Market += mv

		sa := setAgg[c.setCode]
		if sa == nil {
			sa = &setTableRow{Code: c.setCode, Name: c.setName}
			setAgg[c.setCode] = sa
		}
		sa.Entries++
		sa.Qty += c.quantity
		sa.Market += mv
	}

	out.Stats.Distinct = len(distinct)
	out.Stats.SetCount = len(setCodes)
	out.Stats.BinderCount = len(binders)

	out.Rarity = inOrder(rarityOrder, rarityQty)
	out.Color = inOrder(colorOrder, colorQty)
	out.Foil = inOrder(foilOrder, foilQtyMap)
	out.Types = sortedByQtyDesc(typeQty)
	out.Language = sortedByQtyDesc(langQty)
	out.Condition = sortedByQtyDesc(condQty)

	for _, k := range []string{"0", "1", "2", "3", "4", "5", "6", "7+"} {
		if cmcQty[k] > 0 {
			out.Cmc = append(out.Cmc, keyQty{k, cmcQty[k]})
		}
	}

	out.Sets = topQty(setQty, 10)
	for _, e := range topValue(setValueMap, 10) {
		out.SetValue = append(out.SetValue, setValue{Code: e.Key, Name: setNames[e.Key], Value: round2(e.Value)})
	}

	out.PurchaseMonth = monthsValue(purchaseMonth)
	out.AddedPerMonth = monthsQty(addedMonth)

	out.ValueByColor = asKeyValues(valueByColor)
	out.ValueByRarity = asKeyValues(valueByRarity)

	// Top purchase value is per entry (quantity included), top market price is
	// the most expensive single copy per card name - same as the old charts.
	purchases := make([]namedValue, 0, len(all))
	for _, c := range all {
		purchases = append(purchases, namedValue{c.name, c.purchasePrice * float64(c.quantity)})
	}
	sort.SliceStable(purchases, func(i, j int) bool { return purchases[i].Value > purchases[j].Value })
	if len(purchases) > 10 {
		purchases = purchases[:10]
	}
	out.TopPurchase = purchases

	for _, e := range topValue(topMarketByName, 10) {
		out.TopMarket = append(out.TopMarket, namedValue{e.Key, e.Value})
	}

	rarities := make([]rarityRow, 0, len(rarityAgg))
	for _, v := range rarityAgg {
		v.Market = round2(v.Market)
		rarities = append(rarities, *v)
	}
	sort.Slice(rarities, func(i, j int) bool { return rarities[i].Key < rarities[j].Key })
	out.RarityTable = rarities

	setsTable := make([]setTableRow, 0, len(setAgg))
	for _, v := range setAgg {
		v.Market = round2(v.Market)
		setsTable = append(setsTable, *v)
	}
	sort.Slice(setsTable, func(i, j int) bool {
		if setsTable[i].Market != setsTable[j].Market {
			return setsTable[i].Market > setsTable[j].Market
		}
		return setsTable[i].Code < setsTable[j].Code
	})
	if len(setsTable) > 15 {
		setsTable = setsTable[:15]
	}
	out.SetTable = setsTable

	out.Stats.PurchaseValue = round2(out.Stats.PurchaseValue)
	out.Stats.MarketValue = round2(out.Stats.MarketValue)
	return out
}

// valueBucket returns the index of the single-card price bracket.
func valueBucket(price float64) int {
	for i, max := range valueBucketMax {
		if price < max {
			return i
		}
	}
	return len(valueBucketMax)
}

func inOrder(order []string, m map[string]int) []keyQty {
	out := make([]keyQty, 0, len(order))
	for _, k := range order {
		out = append(out, keyQty{k, m[k]})
	}
	return out
}

func sortedByQtyDesc(m map[string]int) []keyQty {
	out := make([]keyQty, 0, len(m))
	for k, v := range m {
		out = append(out, keyQty{k, v})
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].Qty != out[j].Qty {
			return out[i].Qty > out[j].Qty
		}
		return out[i].Key < out[j].Key
	})
	return out
}

func topQty(m map[string]int, n int) []keyQty {
	out := sortedByQtyDesc(m)
	if len(out) > n {
		out = out[:n]
	}
	return out
}

func topValue(m map[string]float64, n int) []keyValue {
	out := asKeyValues(m)
	sort.Slice(out, func(i, j int) bool {
		if out[i].Value != out[j].Value {
			return out[i].Value > out[j].Value
		}
		return out[i].Key < out[j].Key
	})
	if len(out) > n {
		out = out[:n]
	}
	return out
}

func asKeyValues(m map[string]float64) []keyValue {
	out := make([]keyValue, 0, len(m))
	for k, v := range m {
		out = append(out, keyValue{k, round2(v)})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Key < out[j].Key })
	return out
}

func monthsValue(m map[string]float64) []monthValue {
	keys := sortedKeysFloat(m)
	out := make([]monthValue, 0, len(keys))
	for _, k := range keys {
		out = append(out, monthValue{k, round2(m[k])})
	}
	return out
}

func monthsQty(m map[string]int) []monthQty {
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	out := make([]monthQty, 0, len(keys))
	for _, k := range keys {
		out = append(out, monthQty{k, m[k]})
	}
	return out
}

func sortedKeysFloat(m map[string]float64) []string {
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	return keys
}
