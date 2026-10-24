package main

import (
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
)

func binderCall(t *testing.T, name, body string) *httptest.ResponseRecorder {
	t.Helper()
	mux := http.NewServeMux()
	mux.HandleFunc("PATCH /api/binders/{name}", handleBinderPatch)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, httptest.NewRequest("PATCH", "/api/binders/"+url.PathEscape(name), strings.NewReader(body)))
	return rec
}

func seedBinders(t *testing.T) {
	t.Helper()
	for _, q := range []string{
		`INSERT INTO collection (binder_name, binder_type, set_code, collector_number, name, foil, language, condition, quantity, origin) VALUES
			('Scans 1/2', 'binder', 'snc', '47', 'Ledger Shredder', 'normal', 'en', 'near_mint', 2, 'app'),
			('Scans 1/2', 'binder', 'ice', '61', 'Brainstorm', 'normal', 'en', 'near_mint', 1, 'app'),
			('Box', 'list', 'snc', '47', 'Ledger Shredder', 'normal', 'en', 'near_mint', 3, 'app'),
			('Box', 'list', 'ice', '61', 'Brainstorm', 'normal', 'en', 'near_mint', 4, 'csv')`,
	} {
		if _, err := db.Exec(q); err != nil {
			t.Fatal(err)
		}
	}
}

func TestRenameIntoAnExistingBinderMerges(t *testing.T) {
	newTestDB(t)
	seedBinders(t)
	rec := binderCall(t, "Scans 1/2", `{"name":"Box"}`)
	if rec.Code != 200 {
		t.Fatalf("%d %s", rec.Code, rec.Body.String())
	}
	res := decode[binderPatchResult](t, rec)
	// Ledger merges into Box's app row; Brainstorm moves (Box's is a csv row).
	if res.Rows != 2 || res.Merged != 1 || res.Type != "list" || res.Volatile {
		t.Errorf("%+v", res)
	}
	if got := sumQty(t, "binder_name = 'Box' AND set_code = 'snc'"); got != 5 {
		t.Errorf("Ledger in Box %d, want 5", got)
	}
	if got := sumQty(t, "binder_name = 'Scans 1/2'"); got != 0 {
		t.Errorf("old binder still has %d", got)
	}
	if got := sumQty(t, "1 = 1"); got != 10 {
		t.Errorf("total %d, want 10", got)
	}
}

func TestBinderTypeChangeAndCSVRows(t *testing.T) {
	newTestDB(t)
	seedBinders(t)
	res := decode[binderPatchResult](t, binderCall(t, "Box", `{"type":"deck"}`))
	if res.Type != "deck" || !res.Volatile {
		t.Errorf("%+v", res)
	}
	var n int
	db.QueryRow(`SELECT COUNT(*) FROM collection WHERE binder_name = 'Box' AND binder_type = 'deck'`).Scan(&n)
	if n != 2 {
		t.Errorf("%d rows with the new type", n)
	}
	if rec := binderCall(t, "Nope", `{"name":"X"}`); rec.Code != 404 {
		t.Errorf("unknown binder: %d", rec.Code)
	}
	if rec := binderCall(t, "Box", `{"type":"shelf"}`); rec.Code != 400 {
		t.Errorf("bad type: %d", rec.Code)
	}
	if got := routeLabel("/api/binders/Box"); got != "/api/binders/{name}" {
		t.Errorf("route label %q", got)
	}
}
