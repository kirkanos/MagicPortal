package main

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestBinderNotes(t *testing.T) {
	newTestDB(t)
	seedBinders(t)
	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/binders", handleBinders)
	mux.HandleFunc("POST /api/binders", handleBinderCreate)
	mux.HandleFunc("PATCH /api/binders/{name}", handleBinderPatch)
	mux.HandleFunc("DELETE /api/binders/{name}", handleBinderDelete)
	mux.HandleFunc("PUT /api/binders/{name}/note", handleBinderNote)
	do := func(method, path, body string) *httptest.ResponseRecorder {
		rec := httptest.NewRecorder()
		mux.ServeHTTP(rec, httptest.NewRequest(method, path, strings.NewReader(body)))
		return rec
	}
	notes := func() map[string]string {
		out := map[string]string{}
		for _, b := range decode[[]binderOut](t, do("GET", "/api/binders", "")) {
			if b.Note != "" {
				out[b.Name] = b.Note
			}
		}
		return out
	}

	if rec := do("PUT", "/api/binders/Nope/note", `{"note":"x"}`); rec.Code != 404 {
		t.Errorf("unknown binder: %d", rec.Code)
	}
	if rec := do("PUT", "/api/binders/Box/note", `{"note":"`+strings.Repeat("x", maxBinderNote+1)+`"}`); rec.Code != 400 {
		t.Errorf("too long: %d", rec.Code)
	}
	if rec := do("PUT", "/api/binders/Box/note", `{"note":"  Seite 1: Eis\r\nSeite 2: Rest  "}`); rec.Code != 200 {
		t.Fatalf("set: %d %s", rec.Code, rec.Body)
	}
	if rec := do("PUT", "/api/binders/Scans%201%2F2/note", `{"note":"Neu"}`); rec.Code != 200 {
		t.Fatalf("set 2: %d %s", rec.Code, rec.Body)
	}
	if got := notes(); got["Box"] != "Seite 1: Eis\nSeite 2: Rest" || got["Scans 1/2"] != "Neu" {
		t.Fatalf("notes: %q", got)
	}

	// A merge keeps both notes; a rename takes the note along.
	if rec := do("PATCH", "/api/binders/Scans%201%2F2", `{"name":"Box"}`); rec.Code != 200 {
		t.Fatalf("merge: %d %s", rec.Code, rec.Body)
	}
	if rec := do("PATCH", "/api/binders/Box", `{"name":"Kiste"}`); rec.Code != 200 {
		t.Fatalf("rename: %d %s", rec.Code, rec.Body)
	}
	if got := notes(); len(got) != 1 || got["Kiste"] != "Seite 1: Eis\nSeite 2: Rest\n\nNeu" {
		t.Fatalf("after merge and rename: %q", got)
	}

	// An empty note removes it; deleting a created binder drops its note.
	if rec := do("PUT", "/api/binders/Kiste/note", `{"note":" "}`); rec.Code != 200 || len(notes()) != 0 {
		t.Fatalf("clear: %d %q", rec.Code, notes())
	}
	do("POST", "/api/binders", `{"name":"Leer","type":"binder"}`)
	do("PUT", "/api/binders/Leer/note", `{"note":"bald"}`)
	if rec := do("DELETE", "/api/binders/Leer", ""); rec.Code != 204 || len(loadBinderNotes()) != 0 {
		t.Fatalf("delete: %d %q", rec.Code, loadBinderNotes())
	}
}
