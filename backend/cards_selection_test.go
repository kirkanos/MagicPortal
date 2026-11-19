package main

import (
	"encoding/json"
	"net/http/httptest"
	"testing"
)

func selectionCall(t *testing.T, query string) (keys map[string]int, copies int) {
	t.Helper()
	rec := httptest.NewRecorder()
	handleCardSelection(rec, httptest.NewRequest("GET", "/api/cards/selection?"+query, nil))
	if rec.Code != 200 {
		t.Fatalf("%d %s", rec.Code, rec.Body.String())
	}
	var out struct {
		Groups []struct {
			Key      string `json:"key"`
			Variants []struct {
				ID       int `json:"id"`
				Quantity int `json:"quantity"`
			} `json:"variants"`
		} `json:"groups"`
		Copies int `json:"copies"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &out); err != nil {
		t.Fatal(err)
	}
	keys = map[string]int{}
	for _, g := range out.Groups {
		for _, v := range g.Variants {
			if v.ID == 0 {
				t.Fatalf("row without id in %s", g.Key)
			}
			keys[g.Key] += v.Quantity
		}
	}
	return keys, out.Copies
}

// "Select all" must cover every card behind the filter, also beyond the first
// page, and only the rows the filter matches.
func TestCardSelectionCoversTheWholeFilter(t *testing.T) {
	newTestDB(t)
	cardIndexMu.Lock()
	cardIndexRows = nil
	cardIndexMu.Unlock()
	if _, err := db.Exec(`INSERT INTO collection (binder_name, binder_type, set_code, set_name, collector_number, name, rarity, foil, language, condition, quantity, purchase_price, currency, added, origin) VALUES
		('Scans', 'binder', 'snc', 'Streets of New Capenna', '47', 'Ledger Shredder', 'rare', 'normal', 'en', 'near_mint', 2, 0, 'EUR', '', 'app'),
		('Scans', 'binder', 'ice', 'Ice Age', '61', 'Brainstorm', 'common', 'normal', 'en', 'near_mint', 1, 0, 'EUR', '', 'app'),
		('Box', 'list', 'snc', 'Streets of New Capenna', '47', 'Ledger Shredder', 'rare', 'normal', 'en', 'near_mint', 3, 0, 'EUR', '', 'app'),
		('Box', 'list', 'ice', 'Ice Age', '61', 'Brainstorm', 'common', 'normal', 'en', 'near_mint', 4, 0, 'EUR', '', 'csv')`); err != nil {
		t.Fatal(err)
	}

	keys, copies := selectionCall(t, "")
	if len(keys) != 2 || copies != 10 {
		t.Fatalf("all: %v copies %d", keys, copies)
	}
	keys, copies = selectionCall(t, "binder=Box")
	if len(keys) != 2 || copies != 7 {
		t.Fatalf("binder: %v copies %d", keys, copies)
	}
	keys, copies = selectionCall(t, "q=brainstorm")
	if len(keys) != 1 || copies != 5 {
		t.Fatalf("search: %v copies %d", keys, copies)
	}
	// The same keys as the paged grid, so the clients can mark loaded tiles.
	rec := httptest.NewRecorder()
	handleCards(rec, httptest.NewRequest("GET", "/api/cards?limit=1", nil))
	var page struct {
		Groups []struct {
			Key string `json:"key"`
		} `json:"groups"`
		Total int `json:"total"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &page); err != nil {
		t.Fatal(err)
	}
	all, _ := selectionCall(t, "")
	if page.Total != len(all) || len(page.Groups) != 1 {
		t.Fatalf("page %+v vs %v", page, all)
	}
	if _, ok := all[page.Groups[0].Key]; !ok {
		t.Fatalf("key %q not in selection %v", page.Groups[0].Key, all)
	}
}

// The set filter of a binder view offers only the sets in that binder.
func TestFacetsListSetsPerBinder(t *testing.T) {
	newTestDB(t)
	cardIndexMu.Lock()
	cardIndexRows = nil
	cardIndexMu.Unlock()
	if _, err := db.Exec(`INSERT INTO collection (binder_name, binder_type, set_code, set_name, collector_number, name, rarity, foil, language, condition, quantity, purchase_price, currency, added, origin) VALUES
		('Scans', 'binder', 'snc', 'Streets of New Capenna', '47', 'Ledger Shredder', 'rare', 'normal', 'en', 'near_mint', 2, 0, 'EUR', '', 'app'),
		('Box', 'list', 'snc', 'Streets of New Capenna', '47', 'Ledger Shredder', 'rare', 'normal', 'en', 'near_mint', 3, 0, 'EUR', '', 'app'),
		('Box', 'list', 'ice', 'Ice Age', '61', 'Brainstorm', 'common', 'normal', 'en', 'near_mint', 4, 0, 'EUR', '', 'csv')`); err != nil {
		t.Fatal(err)
	}
	rec := httptest.NewRecorder()
	handleCardFacets(rec, httptest.NewRequest("GET", "/api/cards/facets", nil))
	var f struct {
		SetsByBinder map[string][]string `json:"setsByBinder"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &f); err != nil {
		t.Fatal(err)
	}
	if got := f.SetsByBinder["Scans"]; len(got) != 1 || got[0] != "SNC" && got[0] != "snc" {
		t.Fatalf("Scans: %v", got)
	}
	if got := f.SetsByBinder["Box"]; len(got) != 2 {
		t.Fatalf("Box: %v", got)
	}
}

func TestFacetsStatsForOneBinder(t *testing.T) {
	newTestDB(t)
	cardIndexMu.Lock()
	cardIndexRows = nil
	cardIndexMu.Unlock()
	if _, err := db.Exec(`INSERT INTO collection (binder_name, binder_type, set_code, set_name, collector_number, name, rarity, foil, language, condition, quantity, purchase_price, currency, added, origin) VALUES
		('Scans', 'binder', 'snc', 'Streets of New Capenna', '47', 'Ledger Shredder', 'rare', 'normal', 'en', 'near_mint', 2, 0, 'EUR', '', 'app'),
		('Box', 'list', 'snc', 'Streets of New Capenna', '47', 'Ledger Shredder', 'rare', 'normal', 'en', 'near_mint', 3, 0, 'EUR', '', 'app'),
		('Box', 'list', 'ice', 'Ice Age', '61', 'Brainstorm', 'common', 'normal', 'en', 'near_mint', 4, 0, 'EUR', '', 'csv')`); err != nil {
		t.Fatal(err)
	}
	stats := func(url string) (entries, distinct, qty int, binders []string) {
		rec := httptest.NewRecorder()
		handleCardFacets(rec, httptest.NewRequest("GET", url, nil))
		var f struct {
			Binders []string `json:"binders"`
			Stats   struct {
				Entries  int `json:"entries"`
				Distinct int `json:"distinct"`
				TotalQty int `json:"totalQty"`
			} `json:"stats"`
		}
		if err := json.Unmarshal(rec.Body.Bytes(), &f); err != nil {
			t.Fatal(err)
		}
		return f.Stats.Entries, f.Stats.Distinct, f.Stats.TotalQty, f.Binders
	}
	if e, d, q, _ := stats("/api/cards/facets"); e != 3 || d != 2 || q != 9 {
		t.Fatalf("all: entries %d distinct %d qty %d", e, d, q)
	}
	e, d, q, b := stats("/api/cards/facets?binder=Box")
	if e != 2 || d != 2 || q != 7 {
		t.Fatalf("Box: entries %d distinct %d qty %d", e, d, q)
	}
	if len(b) != 2 {
		t.Fatalf("binders stay those of the whole collection: %v", b)
	}
}

func TestNoBinderQuery(t *testing.T) {
	newTestDB(t)
	cardIndexMu.Lock()
	cardIndexRows = nil
	cardIndexMu.Unlock()
	if _, err := db.Exec(`INSERT INTO collection (binder_name, binder_type, set_code, set_name, collector_number, name, rarity, foil, language, condition, quantity, purchase_price, currency, added, origin) VALUES
		('', '', 'm11', 'Magic 2011', '1', 'Ajani''s Mantra', 'common', 'normal', 'en', 'near_mint', 2, 0, 'EUR', '', 'app'),
		('Box', 'binder', 'ice', 'Ice Age', '61', 'Brainstorm', 'common', 'normal', 'en', 'near_mint', 4, 0, 'EUR', '', 'csv')`); err != nil {
		t.Fatal(err)
	}
	rec := httptest.NewRecorder()
	handleCards(rec, httptest.NewRequest("GET", "/api/cards?binder="+noBinder, nil))
	var c struct {
		Total  int `json:"total"`
		Groups []struct {
			Name string `json:"name"`
		} `json:"groups"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &c); err != nil {
		t.Fatal(err)
	}
	if c.Total != 1 || c.Groups[0].Name != "Ajani's Mantra" {
		t.Fatalf("cards without binder: %+v", c)
	}
	rec = httptest.NewRecorder()
	handleCardFacets(rec, httptest.NewRequest("GET", "/api/cards/facets?binder="+noBinder, nil))
	var f struct {
		SetsByBinder map[string][]string `json:"setsByBinder"`
		Stats        struct {
			TotalQty int `json:"totalQty"`
		} `json:"stats"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &f); err != nil {
		t.Fatal(err)
	}
	if f.Stats.TotalQty != 2 || len(f.SetsByBinder[noBinder]) != 1 {
		t.Fatalf("facets without binder: %+v", f)
	}
}

func TestBinderSets(t *testing.T) {
	newTestDB(t)
	cardIndexMu.Lock()
	cardIndexRows = nil
	cardIndexMu.Unlock()
	if _, err := db.Exec(`INSERT INTO collection (binder_name, binder_type, set_code, set_name, collector_number, name, rarity, foil, language, condition, quantity, purchase_price, currency, added, origin) VALUES
		('', '', 'm11', 'Magic 2011', '1', 'Ajani''s Mantra', 'common', 'normal', 'en', 'near_mint', 2, 0, 'EUR', '', 'app'),
		('Box', 'binder', 'm11', 'Magic 2011', '2', 'Brainstorm', 'common', 'normal', 'en', 'near_mint', 4, 0, 'EUR', '', 'csv'),
		('Box', 'binder', 'm11', 'Magic 2011', '3', 'Counsel', 'common', 'foil', 'en', 'near_mint', 1, 0, 'EUR', '', 'csv'),
		('Old', 'list', 'ice', 'Ice Age', '61', 'Brainstorm', 'common', 'normal', 'en', 'near_mint', 3, 0, 'EUR', '', 'csv')`); err != nil {
		t.Fatal(err)
	}
	rec := httptest.NewRecorder()
	handleBinderSets(rec, httptest.NewRequest("GET", "/api/binders/sets", nil))
	var got map[string]map[string]int
	if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil {
		t.Fatal(err)
	}
	if len(got) != 2 || got["Box"]["M11"] != 5 || got["Old"]["ICE"] != 3 {
		t.Fatalf("binder sets: %v", got)
	}
}
