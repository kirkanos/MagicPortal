package main

import (
	"encoding/json"
	"os"
	"testing"
)

// The Android app normalises OCR readings with a twin of normalizeName; both
// sides read the same cases from testdata/names.json.
func TestNormalizeName(t *testing.T) {
	raw, err := os.ReadFile("testdata/names.json")
	if err != nil {
		t.Fatal(err)
	}
	var cases []struct{ In, Out string }
	if err := json.Unmarshal(raw, &cases); err != nil {
		t.Fatal(err)
	}
	if len(cases) < 10 {
		t.Fatalf("only %d cases", len(cases))
	}
	for _, c := range cases {
		if got := normalizeName(c.In); got != c.Out {
			t.Errorf("normalizeName(%q) = %q, want %q", c.In, got, c.Out)
		}
	}
}

func TestLevenshtein(t *testing.T) {
	for _, c := range []struct {
		a, b string
		d    int
	}{
		{"", "", 0}, {"abc", "abc", 0}, {"abc", "abd", 1}, {"ledger shredder", "ledgr shreder", 2},
		{"kitten", "sitting", 3}, {"jötun", "jotun", 1},
	} {
		if got := levenshtein(c.a, c.b); got != c.d {
			t.Errorf("levenshtein(%q,%q) = %d, want %d", c.a, c.b, got, c.d)
		}
	}
}

func TestSearchNames(t *testing.T) {
	c := &catalogData{
		printings: []catalogEntry{
			{SetCode: "snc", CollectorNumber: "54", Name: "Ledger Shredder", ScryfallID: "a", HasImage: true, ReleasedAt: "2022-04-29"},
			{SetCode: "mom", CollectorNumber: "1", Name: "Ledger Shredder", ScryfallID: "b", ReleasedAt: "2023-04-21"},
			{SetCode: "neo", CollectorNumber: "50", Name: "Fable of the Mirror-Breaker // Reflection of Kiki-Jiki", ScryfallID: "c"},
			{SetCode: "lea", CollectorNumber: "161", Name: "Lightning Bolt", ScryfallID: "d"},
		},
		byName: map[string][]int{},
	}
	for i, p := range c.printings {
		c.byName[normalizeName(p.Name)] = append(c.byName[normalizeName(p.Name)], i)
	}
	c.byName[normalizeName("Reflection of Kiki-Jiki")] = []int{2}
	c.byName["ledger shredder"] = []int{1, 0} // newest first, as catalog() sorts
	for n := range c.byName {
		c.names = append(c.names, n)
	}

	hits := searchNames(c, "Ledgr Shreder", 5)
	if len(hits) == 0 || hits[0].Name != "Ledger Shredder" {
		t.Fatalf("misspelling not found: %+v", hits)
	}
	if hits[0].Printings[0].SetCode != "MOM" || hits[0].Printings[1].ImageID != "a" {
		t.Errorf("printings: %+v", hits[0].Printings)
	}
	// A face leads to the full double-faced card.
	hits = searchNames(c, "Reflection of Kiki Jiki", 5)
	if len(hits) != 1 || hits[0].Name != "Fable of the Mirror-Breaker // Reflection of Kiki-Jiki" {
		t.Errorf("face alias: %+v", hits)
	}
	if hits := searchNames(c, "zz", 5); hits != nil {
		t.Errorf("too short should give nothing, got %+v", hits)
	}
	if hits := searchNames(c, "Black Lotus", 5); len(hits) != 0 {
		t.Errorf("unrelated name matched: %+v", hits)
	}
}

func TestFinishFlags(t *testing.T) {
	for in, want := range map[string]int{"": 0, "nonfoil": 4, "foil": 8, "nonfoil,foil": 12, "foil,etched": 24, "glossy": 0} {
		if got := finishFlags(in); got != want {
			t.Errorf("finishFlags(%q) = %d, want %d", in, got, want)
		}
	}
}
