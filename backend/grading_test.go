package main

import (
	"database/sql"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestGradingSplitsACopy(t *testing.T) {
	newTestDB(t)
	seedWriteCatalog(t)
	res := decode[addResult](t, call(t, "POST", "/api/collection/cards", `{"items":[
		{"setCode":"snc","collectorNumber":"47","binderName":"Box","quantity":3}]}`))
	box := res.Items[0].ID
	path := func(id int64) string { return "/api/collection/cards/" + itoa64(id) }

	for _, bad := range []string{`{"grader":"PSA"}`, `{"grader":"PSA","grade":10.5}`, `{"grader":"PSA","grade":9.3}`, `{"grader":"PSA","grade":9,"quantityDelta":-1}`} {
		if rec := call(t, "PATCH", path(box), bad); rec.Code != 400 {
			t.Errorf("%s: %d", bad, rec.Code)
		}
	}
	// One of three copies graded: split off, the rest stays.
	p := decode[patchResult](t, call(t, "PATCH", path(box), `{"grader":"psa","grade":9.5}`))
	if p.Row == nil || p.Row.ID == box || p.Row.Grader != "PSA" || p.Row.Grade != 9.5 || p.Row.Quantity != 1 || p.Row.Origin != "app" {
		t.Fatalf("split: %+v", p.Row)
	}
	if p.Source == nil || p.Source.Quantity != 2 || p.SourceDeleted {
		t.Errorf("source after split: %+v", p.Source)
	}
	slab := p.Row.ID
	// The single graded copy is edited in place.
	p = decode[patchResult](t, call(t, "PATCH", path(slab), `{"signed":true}`))
	if p.Row.ID != slab || !p.Row.Signed || p.Row.Grader != "PSA" {
		t.Errorf("signing the slab: %+v", p.Row)
	}
	// Graded copies stay out of the ManaBox export.
	_, rows, err := createExport(time.Now())
	if err != nil {
		t.Fatal(err)
	}
	for _, r := range rows {
		if r.ID == slab {
			t.Error("graded copy exported to ManaBox")
		}
	}
	// Back to a plain copy: it joins the row it came from.
	p = decode[patchResult](t, call(t, "PATCH", path(slab), `{"grader":"","signed":false}`))
	if p.MergedInto != box || p.Row.Quantity != 3 {
		t.Errorf("unmarking: %+v %+v", p, p.Row)
	}
}

func TestGradingACSVRowSplitsIntoAnAppRow(t *testing.T) {
	newTestDB(t)
	if _, err := importCSV(db, strings.NewReader(testCSV)); err != nil {
		t.Fatal(err)
	}
	var id int64
	db.QueryRow(`SELECT id FROM collection WHERE set_code = 'ice'`).Scan(&id)
	p := decode[patchResult](t, call(t, "PATCH", "/api/collection/cards/"+itoa64(id), `{"signed":true}`))
	if !p.SourceDeleted || p.Row.Origin != "app" || !p.Row.Signed || p.Row.Name != "Brainstorm" {
		t.Errorf("signed csv copy: %+v %+v", p, p.Row)
	}
	// The next import brings the ManaBox row back, the signed copy stays.
	if _, err := importCSV(db, strings.NewReader(testCSV)); err != nil {
		t.Fatal(err)
	}
	var n int
	db.QueryRow(`SELECT COUNT(*) FROM collection WHERE set_code = 'ice'`).Scan(&n)
	if n != 2 {
		t.Errorf("%d Brainstorm rows after import, want csv + signed app row", n)
	}
}

func TestMigrateAddsGrading(t *testing.T) {
	path := filepath.Join(t.TempDir(), "old.db")
	old, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	beforeGrading := strings.Replace(strings.Replace(collectionSchema,
		"  grader           TEXT NOT NULL DEFAULT '',\n  grade            REAL NOT NULL DEFAULT 0,\n  signed           INTEGER NOT NULL DEFAULT 0,\n", "", 1),
		", grader, grade, signed)", ")", 1)
	if beforeGrading == collectionSchema {
		t.Fatal("test schema still has the grading columns")
	}
	for _, q := range []string{beforeGrading, `INSERT INTO collection (id, binder_name, set_code, collector_number, name, foil, language, condition, quantity, origin)
		VALUES (7, 'Box', 'snc', '47', 'Ledger Shredder', 'normal', 'en', 'near_mint', 2, 'app')`} {
		if _, err := old.Exec(q); err != nil {
			t.Fatal(err)
		}
	}
	old.Close()
	d, err := openDB(path)
	if err != nil {
		t.Fatal(err)
	}
	defer d.Close()
	var id, qty int
	var origin, grader string
	if err := d.QueryRow(`SELECT id, quantity, origin, grader FROM collection`).Scan(&id, &qty, &origin, &grader); err != nil {
		t.Fatal(err)
	}
	if id != 7 || qty != 2 || origin != "app" || grader != "" {
		t.Errorf("after migration: %d %d %s %q", id, qty, origin, grader)
	}
	// A graded twin of the same card now fits next to it.
	if _, err := d.Exec(`INSERT INTO collection (binder_name, set_code, collector_number, name, foil, language, condition, quantity, origin, grader, grade)
		VALUES ('Box', 'snc', '47', 'Ledger Shredder', 'normal', 'en', 'near_mint', 1, 'app', 'PSA', 10)`); err != nil {
		t.Errorf("graded twin: %v", err)
	}
	if idx := collectionIndexNames(t, d); strings.Join(idx, ",") != "idx_collection_binder,idx_collection_set" {
		t.Errorf("indexes after migration: %v", idx)
	}
}
