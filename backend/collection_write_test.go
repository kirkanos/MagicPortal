package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func seedWriteCatalog(t *testing.T) {
	t.Helper()
	for _, q := range []string{
		`INSERT INTO sets(code, name, released_at) VALUES ('snc', 'Streets of New Capenna', '2022-04-29'), ('ice', 'Ice Age', '1995-06-03')`,
		`INSERT INTO scryfall_cards(set_code, collector_number, scryfall_id, name, rarity) VALUES
			('snc', '47', 'id-ledger', 'Ledger Shredder', 'rare'),
			('ice', '61', 'id-brainstorm', 'Brainstorm', 'common')`,
	} {
		if _, err := db.Exec(q); err != nil {
			t.Fatal(err)
		}
	}
}

// call runs one request through the real mux routes.
func call(t *testing.T, method, path, body string, header ...string) *httptest.ResponseRecorder {
	t.Helper()
	mux := http.NewServeMux()
	mux.HandleFunc("POST /api/collection/cards", handleCollectionAdd)
	mux.HandleFunc("GET /api/collection/cards/{id}", handleCollectionRow)
	mux.HandleFunc("PATCH /api/collection/cards/{id}", handleCollectionPatch)
	mux.HandleFunc("DELETE /api/collection/cards/{id}", handleCollectionDelete)
	req := httptest.NewRequest(method, path, strings.NewReader(body))
	for i := 0; i+1 < len(header); i += 2 {
		req.Header.Set(header[i], header[i+1])
	}
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)
	return rec
}

func decode[T any](t *testing.T, rec *httptest.ResponseRecorder) T {
	t.Helper()
	var v T
	if err := json.Unmarshal(rec.Body.Bytes(), &v); err != nil {
		t.Fatalf("status %d, body %s: %v", rec.Code, rec.Body.String(), err)
	}
	return v
}

func TestBatchAddMerges(t *testing.T) {
	newTestDB(t)
	seedWriteCatalog(t)
	body := `{"items":[
		{"setCode":"SNC","collectorNumber":"047","binderName":"Scans"},
		{"setCode":"snc","collectorNumber":"47","binderName":"Scans","quantity":2},
		{"setCode":"ice","collectorNumber":"61","binderName":"Scans","language":"DE","foil":"foil"}]}`
	rec := call(t, "POST", "/api/collection/cards", body)
	if rec.Code != 200 {
		t.Fatalf("%d %s", rec.Code, rec.Body.String())
	}
	res := decode[addResult](t, rec)
	if res.Added != 2 || res.Merged != 1 {
		t.Errorf("added %d merged %d", res.Added, res.Merged)
	}
	ledger := res.Items[1]
	if !ledger.Merged || ledger.Quantity != 3 || ledger.Name != "Ledger Shredder" || ledger.ScryfallID != "id-ledger" ||
		ledger.SetName != "Streets of New Capenna" || ledger.Origin != "app" || ledger.BinderType != "binder" {
		t.Errorf("merged row: %+v", ledger)
	}
	// "047" was stored the way the catalog spells it.
	if ledger.CollectorNumber != "47" {
		t.Errorf("collector number %q", ledger.CollectorNumber)
	}
	if b := res.Items[2]; b.Language != "de" || b.Foil != "foil" || b.Condition != "near_mint" || len(b.Added) != 24 {
		t.Errorf("defaults/normalisation: %+v", b)
	}
}

func TestAddRefusesUnknownCardsAndBadInput(t *testing.T) {
	newTestDB(t)
	seedWriteCatalog(t)
	rec := call(t, "POST", "/api/collection/cards", `{"items":[{"setCode":"snc","collectorNumber":"47"},{"setCode":"snc","collectorNumber":"999"}]}`)
	if rec.Code != 404 || !strings.Contains(rec.Body.String(), `"index":1`) {
		t.Errorf("unknown card: %d %s", rec.Code, rec.Body.String())
	}
	// All or nothing: the first, valid item was not written either.
	if n := countRows(db, "collection"); n != 0 {
		t.Errorf("%d rows after a failed batch", n)
	}
	for _, bad := range []string{
		`{"items":[{"setCode":"snc","collectorNumber":"47","foil":"shiny"}]}`,
		`{"items":[{"setCode":"snc","collectorNumber":"47","condition":"like new"}]}`,
		`{"items":[{"setCode":"snc","collectorNumber":"47","quantity":1000}]}`,
		`{"items":[{"setCode":"s n c","collectorNumber":"47"}]}`,
		`{"items":[]}`,
	} {
		if rec := call(t, "POST", "/api/collection/cards", bad); rec.Code != 400 {
			t.Errorf("%s: %d", bad, rec.Code)
		}
	}
}

func TestAddNeedsThePassword(t *testing.T) {
	newTestDB(t)
	seedWriteCatalog(t)
	old := uploadPassword
	uploadPassword = "secret"
	defer func() { uploadPassword = old }()
	body := `{"items":[{"setCode":"snc","collectorNumber":"47"}]}`
	if rec := call(t, "POST", "/api/collection/cards", body); rec.Code != 403 {
		t.Errorf("without password: %d", rec.Code)
	}
	if rec := call(t, "POST", "/api/collection/cards", body, "X-Upload-Password", "secret"); rec.Code != 200 {
		t.Errorf("with password: %d", rec.Code)
	}
}

func TestIdempotentRetry(t *testing.T) {
	newTestDB(t)
	seedWriteCatalog(t)
	body := `{"items":[{"setCode":"snc","collectorNumber":"47","quantity":2}]}`
	first := call(t, "POST", "/api/collection/cards", body, "Idempotency-Key", "session-1")
	again := call(t, "POST", "/api/collection/cards", body, "Idempotency-Key", "session-1")
	if again.Header().Get("Idempotent-Replayed") != "true" || again.Body.String() != first.Body.String() {
		t.Errorf("replay: %s vs %s", again.Body.String(), first.Body.String())
	}
	var qty int
	db.QueryRow(`SELECT SUM(quantity) FROM collection`).Scan(&qty)
	if qty != 2 {
		t.Errorf("quantity %d after a retry, want 2", qty)
	}
	// A new key is a new write.
	call(t, "POST", "/api/collection/cards", body, "Idempotency-Key", "session-2")
	db.QueryRow(`SELECT SUM(quantity) FROM collection`).Scan(&qty)
	if qty != 4 {
		t.Errorf("quantity %d after a second session, want 4", qty)
	}
}

func TestExistingBinderKeepsItsType(t *testing.T) {
	newTestDB(t)
	seedWriteCatalog(t)
	db.Exec(`INSERT INTO collection (binder_name, binder_type, set_code, collector_number, name, foil, language, condition, quantity)
		VALUES ('Trades', 'list', 'ice', '61', 'Brainstorm', 'normal', 'en', 'near_mint', 1)`)
	res := decode[addResult](t, call(t, "POST", "/api/collection/cards", `{"items":[{"setCode":"snc","collectorNumber":"47","binderName":"Trades","binderType":"deck"}]}`))
	if res.Items[0].BinderType != "list" {
		t.Errorf("binder type %q, want the existing list", res.Items[0].BinderType)
	}
}

func TestPatchRow(t *testing.T) {
	newTestDB(t)
	seedWriteCatalog(t)
	res := decode[addResult](t, call(t, "POST", "/api/collection/cards", `{"items":[
		{"setCode":"snc","collectorNumber":"47","binderName":"Scans","quantity":3},
		{"setCode":"snc","collectorNumber":"47","binderName":"Box"}]}`))
	scans, box := res.Items[0].ID, res.Items[1].ID
	path := func(id int64) string { return "/api/collection/cards/" + itoa64(id) }

	p := decode[patchResult](t, call(t, "PATCH", path(scans), `{"quantityDelta":-1}`))
	if p.Row.Quantity != 2 {
		t.Errorf("delta: %+v", p.Row)
	}
	// Moving into a binder that has the same card merges.
	p = decode[patchResult](t, call(t, "PATCH", path(scans), `{"binderName":"Box"}`))
	if p.MergedInto != box || p.Row.Quantity != 3 {
		t.Errorf("merge: %+v %+v", p, p.Row)
	}
	if rec := call(t, "GET", path(scans), ""); rec.Code != 404 {
		t.Errorf("merged-away row still there: %d", rec.Code)
	}
	// A new key without a twin just moves.
	p = decode[patchResult](t, call(t, "PATCH", path(box), `{"foil":"foil","binderName":"Foils","binderType":"list"}`))
	if p.Row.ID != box || p.Row.Foil != "foil" || p.Row.BinderType != "list" || p.MergedInto != 0 {
		t.Errorf("move: %+v", p.Row)
	}
	if rec := call(t, "PATCH", path(box), `{"quantity":1,"quantityDelta":1}`); rec.Code != 400 {
		t.Errorf("quantity and delta together: %d", rec.Code)
	}
	if rec := call(t, "PATCH", path(box), `{"quantityDelta":-3}`); rec.Code != 200 || !decode[patchResult](t, rec).Deleted {
		t.Errorf("down to zero: %d %s", rec.Code, rec.Body.String())
	}
	if rec := call(t, "DELETE", path(box), ""); rec.Code != 404 {
		t.Errorf("deleting a deleted row: %d", rec.Code)
	}
}

func TestEditingACSVRowIsVolatile(t *testing.T) {
	newTestDB(t)
	if _, err := importCSV(db, strings.NewReader(testCSV)); err != nil {
		t.Fatal(err)
	}
	var id int64
	db.QueryRow(`SELECT id FROM collection WHERE set_code = 'snc'`).Scan(&id)
	p := decode[patchResult](t, call(t, "PATCH", "/api/collection/cards/"+itoa64(id), `{"quantity":7}`))
	if !p.Row.Volatile || p.Row.Origin != "csv" {
		t.Errorf("csv row edit: %+v", p.Row)
	}
}

func TestWriteRouteLabels(t *testing.T) {
	for path, want := range map[string]string{
		"/api/collection/cards":     "/api/collection/cards",
		"/api/collection/cards/123": "/api/collection/cards/{id}",
		"/api/collection/owned":     "/api/collection/owned",
	} {
		if got := routeLabel(path); got != want {
			t.Errorf("routeLabel(%q) = %q, want %q", path, got, want)
		}
	}
}

func itoa64(n int64) string {
	b, _ := json.Marshal(n)
	return string(b)
}
