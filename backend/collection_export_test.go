package main

import (
	"encoding/csv"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func exportMux() *http.ServeMux {
	mux := http.NewServeMux()
	mux.HandleFunc("POST /api/collection/cards", handleCollectionAdd)
	mux.HandleFunc("PATCH /api/collection/cards/{id}", handleCollectionPatch)
	mux.HandleFunc("POST /api/collection/export", handleCollectionExport)
	mux.HandleFunc("POST /api/collection/export/{id}/confirm", handleCollectionExportConfirm)
	return mux
}

func do(t *testing.T, mux *http.ServeMux, method, path, body string) *httptest.ResponseRecorder {
	t.Helper()
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, httptest.NewRequest(method, path, strings.NewReader(body)))
	return rec
}

func sumQty(t *testing.T, where string) int {
	t.Helper()
	var n int
	if err := db.QueryRow(`SELECT COALESCE(SUM(quantity), 0) FROM collection WHERE ` + where).Scan(&n); err != nil {
		t.Fatal(err)
	}
	return n
}

// The real header of a ManaBox collection export (taken from one).
const realManaboxHeader = "Binder Name,Binder Type,Name,Set code,Set name,Collector number,Foil,Rarity,Quantity,ManaBox ID,Scryfall ID,Purchase price,Misprint,Altered,Condition,Language,Purchase price currency,Added"

func TestExportIsAManaboxCSV(t *testing.T) {
	newTestDB(t)
	seedWriteCatalog(t)
	mux := exportMux()
	do(t, mux, "POST", "/api/collection/cards", `{"items":[
		{"setCode":"snc","collectorNumber":"47","binderName":"Scans","quantity":2},
		{"setCode":"ice","collectorNumber":"61","binderName":"Scans","language":"de","foil":"foil","condition":"good"}]}`)
	if _, err := importCSV(db, strings.NewReader(testCSV)); err != nil {
		t.Fatal(err)
	}
	rec := do(t, mux, "POST", "/api/collection/export", "")
	if rec.Code != 200 || rec.Header().Get("X-Export-Id") == "" || rec.Header().Get("X-Export-Cards") != "3" {
		t.Fatalf("%d %v", rec.Code, rec.Header())
	}
	recs, err := csv.NewReader(strings.NewReader(rec.Body.String())).ReadAll()
	if err != nil {
		t.Fatal(err)
	}
	if strings.Join(recs[0], ",") != realManaboxHeader {
		t.Errorf("header %v", recs[0])
	}
	// Only the app rows: the two scanned ones, not the CSV import.
	if len(recs) != 3 {
		t.Fatalf("%d lines", len(recs))
	}
	brainstorm := recs[1] // ordered by binder, name
	if brainstorm[2] != "Brainstorm" || brainstorm[3] != "ICE" || brainstorm[6] != "foil" || brainstorm[14] != "good" ||
		brainstorm[15] != "de" || brainstorm[10] != "id-brainstorm" || len(brainstorm[17]) != 24 {
		t.Errorf("row %v", brainstorm)
	}

	// Our own import reads it like a ManaBox export.
	newTestDB(t)
	res, err := importCSV(db, strings.NewReader(rec.Body.String()))
	if err != nil || res.Total != 2 {
		t.Errorf("re-import: %+v %v", res, err)
	}
}

func TestConfirmMovesExactlyWhatWasExported(t *testing.T) {
	newTestDB(t)
	seedWriteCatalog(t)
	mux := exportMux()
	added := decode[addResult](t, do(t, mux, "POST", "/api/collection/cards", `{"items":[
		{"setCode":"snc","collectorNumber":"47","binderName":"Box","quantity":3},
		{"setCode":"ice","collectorNumber":"61","binderName":"Box","quantity":2}]}`))
	// A csv twin of the Ledger Shredder already exists.
	db.Exec(`INSERT INTO collection (binder_name, binder_type, set_code, collector_number, name, foil, language, condition, quantity, origin)
		VALUES ('Box', 'binder', 'snc', '47', 'Ledger Shredder', 'normal', 'en', 'near_mint', 1, 'csv')`)

	exp := do(t, mux, "POST", "/api/collection/export", "")
	id := exp.Header().Get("X-Export-Id")

	// After the export: one more Ledger Shredder scanned, one Brainstorm removed.
	do(t, mux, "POST", "/api/collection/cards", `{"items":[{"setCode":"snc","collectorNumber":"47","binderName":"Box"}]}`)
	do(t, mux, "PATCH", "/api/collection/cards/"+itoa64(added.Items[1].ID), `{"quantityDelta":-1}`)

	rec := do(t, mux, "POST", "/api/collection/export/"+id+"/confirm", "")
	if rec.Code != 200 {
		t.Fatalf("%d %s", rec.Code, rec.Body.String())
	}
	res := decode[confirmResult](t, rec)
	// 3 Ledger + 1 remaining Brainstorm moved; the removed Brainstorm is missing.
	if res.Moved != 4 || res.Missing != 1 {
		t.Errorf("moved %d missing %d", res.Moved, res.Missing)
	}
	if got := sumQty(t, "set_code = 'snc' AND origin = 'csv'"); got != 4 {
		t.Errorf("csv Ledger %d, want 1 + 3", got)
	}
	// The one scanned after the export is still the app's.
	if got := sumQty(t, "set_code = 'snc' AND origin = 'app'"); got != 1 {
		t.Errorf("app Ledger %d, want 1", got)
	}
	if got := sumQty(t, "set_code = 'ice' AND origin = 'app'"); got != 0 {
		t.Errorf("app Brainstorm %d, want 0", got)
	}
	// Nothing appeared or vanished overall: 1 csv + 3 + 1 Ledger, 1 Brainstorm.
	if got := sumQty(t, "1 = 1"); got != 6 {
		t.Errorf("total %d, want 6", got)
	}
	if rec := do(t, mux, "POST", "/api/collection/export/"+id+"/confirm", ""); rec.Code != 409 {
		t.Errorf("second confirmation: %d", rec.Code)
	}
	if rec := do(t, mux, "POST", "/api/collection/export/999/confirm", ""); rec.Code != 404 {
		t.Errorf("unknown export: %d", rec.Code)
	}
}

func TestExportRouteLabels(t *testing.T) {
	for path, want := range map[string]string{
		"/api/collection/export":           "/api/collection/export",
		"/api/collection/export/7/confirm": "/api/collection/export/{id}/confirm",
	} {
		if got := routeLabel(path); got != want {
			t.Errorf("routeLabel(%q) = %q, want %q", path, got, want)
		}
	}
}
