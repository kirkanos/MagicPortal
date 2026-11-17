package main

import "testing"

// normNumber has to agree with normNumber() in editions.js, or the set overlay
// marks owned cards as missing.
func TestNormNumber(t *testing.T) {
	for in, want := range map[string]string{
		"161":   "161",
		"047":   "47",
		"0161":  "161",
		"0":     "0",
		"000":   "0",
		" 12a ": "12a",
		"012A":  "12a",
		"0a":    "0a", // the zero is not followed by a digit, so it stays
		"★1":    "★1",
		"":      "",
	} {
		if got := normNumber(in); got != want {
			t.Errorf("normNumber(%q) = %q, want %q", in, got, want)
		}
	}
}

// The set overlay edits rows by id, so ownedBySet has to hand them over -
// one distinct card, its rows across languages and binders.
func TestOwnedBySetCarriesRows(t *testing.T) {
	rows := []*cardRow{
		{ID: 1, SetCode: "SNC", CollectorNumber: "047", Name: "A", Language: "en", Foil: "normal", Quantity: 2, BinderName: "X", Origin: "csv"},
		{ID: 2, SetCode: "snc", CollectorNumber: "47", Name: "A", Language: "de", Foil: "foil", Quantity: 1, Origin: "app"},
		{ID: 3, SetCode: "snc", CollectorNumber: "48", Name: "B", Language: "en", Foil: "normal", Quantity: 1},
	}
	d := ownedBySet(rows)["snc"]["n:47"]
	if d == nil || d.Copies != 3 || len(d.Rows) != 2 {
		t.Fatalf("owned = %+v", d)
	}
	if d.Rows[0].ID != 1 || d.Rows[0].BinderName != "X" || d.Rows[1].ID != 2 || d.Rows[1].Origin != "app" {
		t.Errorf("rows = %+v", d.Rows)
	}
}
