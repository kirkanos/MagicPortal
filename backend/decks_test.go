package main

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestMovePartOfARow(t *testing.T) {
	newTestDB(t)
	seedWriteCatalog(t)
	res := decode[addResult](t, call(t, "POST", "/api/collection/cards", `{"items":[
		{"setCode":"snc","collectorNumber":"47","binderName":"Box","quantity":4}]}`))
	id := res.Items[0].ID

	// One copy into a new deck: the row splits.
	m := decode[moveResult](t, call(t, "POST", "/api/collection/move", `{"ids":[`+itoa64(id)+`],"binderName":"Deck","binderType":"deck","quantity":1}`))
	if m.Moved != 1 || m.Merged != 0 || m.BinderType != "deck" {
		t.Fatalf("split: %+v", m)
	}
	// A second copy merges into the deck's row.
	m = decode[moveResult](t, call(t, "POST", "/api/collection/move", `{"ids":[`+itoa64(id)+`],"binderName":"Deck","quantity":1}`))
	if m.Merged != 1 {
		t.Fatalf("merge: %+v", m)
	}
	qty := map[string]int{}
	rows, _ := db.Query(`SELECT binder_name, quantity FROM collection`)
	for rows.Next() {
		var b string
		var q int
		rows.Scan(&b, &q)
		qty[b] = q
	}
	rows.Close()
	if qty["Box"] != 2 || qty["Deck"] != 2 {
		t.Errorf("quantities: %v", qty)
	}
	// All that is left moves as a whole row.
	m = decode[moveResult](t, call(t, "POST", "/api/collection/move", `{"ids":[`+itoa64(id)+`],"binderName":"Deck","quantity":5}`))
	var left int
	db.QueryRow(`SELECT COUNT(*) FROM collection WHERE binder_name = 'Box'`).Scan(&left)
	if m.Merged != 1 || left != 0 {
		t.Errorf("rest: %+v, rows left in Box %d", m, left)
	}
	if rec := call(t, "POST", "/api/collection/move", `{"ids":[1,2],"binderName":"Deck","quantity":1}`); rec.Code != 400 {
		t.Errorf("quantity with two rows: %d", rec.Code)
	}
}

func TestDeckCoverFollowsRename(t *testing.T) {
	newTestDB(t)
	seedWriteCatalog(t)
	decode[addResult](t, call(t, "POST", "/api/collection/cards", `{"items":[
		{"setCode":"snc","collectorNumber":"47","binderName":"Mill","binderType":"deck"}]}`))
	mux := http.NewServeMux()
	mux.HandleFunc("POST /api/binders/{name}/cover", handleDeckCover)
	mux.HandleFunc("PATCH /api/binders/{name}", handleBinderPatch)
	mux.HandleFunc("GET /api/binders", handleBinders)
	do := func(method, path, body string) *httptest.ResponseRecorder {
		rec := httptest.NewRecorder()
		mux.ServeHTTP(rec, httptest.NewRequest(method, path, strings.NewReader(body)))
		return rec
	}
	if rec := do("POST", "/api/binders/Mill/cover", `{"setCode":"SNC","collectorNumber":"47"}`); rec.Code != 200 {
		t.Fatalf("cover: %d %s", rec.Code, rec.Body)
	}
	if rec := do("POST", "/api/binders/Mill/cover", `{"setCode":"snc","collectorNumber":"999"}`); rec.Code != 404 {
		t.Errorf("unknown card: %d", rec.Code)
	}
	if rec := do("POST", "/api/binders/Nope/cover", `{"setCode":"snc","collectorNumber":"47"}`); rec.Code != 404 {
		t.Errorf("unknown binder: %d", rec.Code)
	}
	if rec := do("PATCH", "/api/binders/Mill", `{"name":"Mill 2"}`); rec.Code != 200 {
		t.Fatalf("rename: %d %s", rec.Code, rec.Body)
	}
	bs := decode[[]binderOut](t, do("GET", "/api/binders", ""))
	if len(bs) != 1 || bs[0].Name != "Mill 2" || bs[0].Cover == nil || bs[0].Cover.ImageID != "id-ledger" || bs[0].Cover.Name != "Ledger Shredder" {
		t.Fatalf("binders: %+v", bs)
	}
	if rec := do("POST", "/api/binders/Mill%202/cover", `{"setCode":""}`); rec.Code != 204 {
		t.Errorf("remove: %d", rec.Code)
	}
	if bs := decode[[]binderOut](t, do("GET", "/api/binders", "")); bs[0].Cover != nil {
		t.Errorf("cover not removed: %+v", bs[0].Cover)
	}
}
