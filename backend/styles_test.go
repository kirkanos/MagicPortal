package main

import "testing"

func TestCardStyles(t *testing.T) {
	cases := []struct {
		c    bulkCard
		want string
	}{
		{bulkCard{}, ""},
		{bulkCard{FullArt: true, BorderColor: "black"}, "fullart"},
		{bulkCard{BorderColor: "borderless", FrameEffects: []string{"inverted", "showcase"}}, "borderless,showcase"},
		{bulkCard{FrameEffects: []string{"extendedart"}}, "extendedart"},
		{bulkCard{Layout: "art_series"}, "artseries"},
	}
	for _, tc := range cases {
		if got := cardStyles(tc.c); got != tc.want {
			t.Errorf("%+v: %q, want %q", tc.c, got, tc.want)
		}
	}
}

func TestStyleFilter(t *testing.T) {
	newTestDB(t)
	seedWriteCatalog(t)
	if _, err := db.Exec(`UPDATE scryfall_cards SET styles = 'fullart,borderless' WHERE set_code = 'snc'`); err != nil {
		t.Fatal(err)
	}
	call(t, "POST", "/api/collection/cards", `{"items":[{"setCode":"snc","collectorNumber":"47"},{"setCode":"ice","collectorNumber":"61"}]}`)
	cardIndexRows = nil // another test's database may have left an index
	rows, _, err := cardRows()
	if err != nil {
		t.Fatal(err)
	}
	q := cardQuery{style: "fullart"}
	var hits []string
	for _, c := range rows {
		if q.matches(c) {
			hits = append(hits, c.Name)
		}
	}
	if len(hits) != 1 || hits[0] != "Ledger Shredder" {
		t.Errorf("fullart filter: %v", hits)
	}
}
