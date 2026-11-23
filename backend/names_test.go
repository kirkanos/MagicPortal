package main

import (
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
)

const namesDump = `{"name":"Brainstorm","lang":"en","printed_name":"Brainstorm"}
{"name":"Brainstorm","lang":"de","printed_name":"Brainstorm"}
{"name":"Ledger Shredder","lang":"de","printed_name":"Kontobuchschredder"}
{"name":"Ledger Shredder","lang":"ja","printed_name":"帳簿裂き"}
{"name":"Ledger Shredder","lang":"de","printed_name":"Kontobuchschredder"}
{"name":"Delver of Secrets // Insectile Aberration","lang":"de","card_faces":[{"printed_name":"Grübler der Geheimnisse"},{"printed_name":"Insektenartige Abnormität"}]}
`

func TestImportNamesAndSearch(t *testing.T) {
	newTestDB(t)
	seedWriteCatalog(t)
	n, err := importNames(db, strings.NewReader(namesDump))
	if err != nil {
		t.Fatal(err)
	}
	// English and same-as-English names are left out, duplicates count once.
	if n != 3 {
		t.Errorf("stored %d names, want 3", n)
	}
	var dfc string
	db.QueryRow(`SELECT printed FROM card_names WHERE name LIKE 'Delver%'`).Scan(&dfc)
	if dfc != "Grübler der Geheimnisse // Insektenartige Abnormität" {
		t.Errorf("faces: %q", dfc)
	}
	if _, err := importNames(db, strings.NewReader(`{"name":"X","lang":"en"}`)); err == nil {
		t.Error("an empty dump replaced the names")
	}
	if countRows(db, "card_names") != 3 {
		t.Error("names lost after a failed import")
	}

	call(t, "POST", "/api/collection/cards", `{"items":[{"setCode":"snc","collectorNumber":"47"},{"setCode":"ice","collectorNumber":"61"}]}`)
	for q, want := range map[string]int{"kontobuch": 1, "帳簿": 1, "ledger": 1, "KONTOBUCHSCHREDDER": 1, "brainstorm": 1, "grübler": 0} {
		rec := httptest.NewRecorder()
		handleCards(rec, httptest.NewRequest("GET", "/api/cards?q="+urlQuery(q), nil))
		res := decode[struct {
			Groups []struct{ Name string } `json:"groups"`
		}](t, rec)
		if len(res.Groups) != want {
			t.Errorf("q=%q: %d hits, want %d", q, len(res.Groups), want)
		}
	}
}

func urlQuery(s string) string { return url.QueryEscape(s) }
