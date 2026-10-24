package main

import (
	"encoding/binary"
	"encoding/hex"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync/atomic"
	"testing"
)

func seedHashCatalog(t *testing.T, scanURL string) {
	t.Helper()
	for _, q := range []string{
		`INSERT INTO sets(code, name, released_at, digital) VALUES
			('ice', 'Ice Age', '1995-06-03', 0),
			('snc', 'Streets of New Capenna', '2022-04-29', 0),
			('me1', 'Masters Edition', '2007-09-10', 1)`,
		`INSERT INTO scryfall_cards(set_code, collector_number, scryfall_id, name, image_small) VALUES
			('ice', '61', 'aaaaaaaa-0000-0000-0000-000000000001', 'Brainstorm', '` + scanURL + `/ice.jpg?1'),
			('snc', '47', 'bbbbbbbb-0000-0000-0000-000000000002', 'Ledger Shredder', '` + scanURL + `/snc.jpg?2'),
			('snc', '48', 'cccccccc-0000-0000-0000-000000000003', 'Not Owned', '` + scanURL + `/snc.jpg?3'),
			('me1', '1', 'dddddddd-0000-0000-0000-000000000004', 'Digital', '` + scanURL + `/ice.jpg?4')`,
		`INSERT INTO collection(binder_name, set_code, collector_number, foil, language, condition, quantity)
			VALUES('A', 'snc', '47', 'normal', 'en', 'near_mint', 1)`,
	} {
		if _, err := db.Exec(q); err != nil {
			t.Fatal(err)
		}
	}
}

func TestHashTargetsScope(t *testing.T) {
	newTestDB(t)
	seedHashCatalog(t, "http://x")
	keys := func(scope string) string {
		ts, err := hashTargets(scope)
		if err != nil {
			t.Fatal(err)
		}
		var out []string
		for _, x := range ts {
			out = append(out, x.set+"/"+x.number)
		}
		return strings.Join(out, ",")
	}
	// Old frame plus owned; never the digital-only set.
	if got := keys("old"); got != "ice/61,snc/47" {
		t.Errorf("old: %s", got)
	}
	if got := keys("collection"); got != "snc/47" {
		t.Errorf("collection: %s", got)
	}
	if got := keys("all"); got != "ice/61,snc/47,snc/48" {
		t.Errorf("all: %s", got)
	}
}

func TestHashSyncAndBinaryIndex(t *testing.T) {
	newTestDB(t)
	scans := map[string][]byte{}
	for _, f := range []string{"ice", "snc"} {
		g, err := readPGM("testdata/dhash/" + map[string]string{"ice": "ice_61", "snc": "snc_47"}[f] + ".pgm")
		if err != nil {
			t.Fatal(err)
		}
		scans[f] = encodeJPEG(t, g)
	}
	var fetched atomic.Int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		fetched.Add(1)
		if r.Header.Get("User-Agent") != userAgent {
			http.Error(w, "no UA", 400)
			return
		}
		_, _ = w.Write(scans[strings.TrimSuffix(strings.TrimPrefix(r.URL.Path, "/"), ".jpg")])
	}))
	defer srv.Close()
	seedHashCatalog(t, srv.URL)
	t.Setenv("HASH_RATE_PER_SECOND", "1000")
	os.Unsetenv("IMAGE_MIRROR")

	runHashSync()
	if n := countRows(db, "card_hashes"); n != 2 {
		t.Fatalf("%d hashes, want 2", n)
	}
	// A second run finds nothing to do.
	before := fetched.Load()
	runHashSync()
	if fetched.Load() != before {
		t.Errorf("unchanged scans were fetched again")
	}

	rec := httptest.NewRecorder()
	handleCatalogHashes(rec, httptest.NewRequest("GET", "/api/catalog/hashes", nil))
	body := rec.Body.Bytes()
	if string(body[:4]) != "MPH1" || binary.BigEndian.Uint32(body[4:8]) != 2 || len(body) != 8+2*32 {
		t.Fatalf("bad index: %d bytes, header %q", len(body), body[:8])
	}
	// Records sorted by ID: Brainstorm first; its hash is the one from the fixture.
	if id := hex.EncodeToString(body[8:24]); id != "aaaaaaaa000000000000000000000001" {
		t.Errorf("first id %s", id)
	}
	g, _ := readPGM("testdata/dhash/ice_61.pgm")
	wantH, wantV := g.dhash()
	h, v := binary.BigEndian.Uint64(body[24:32]), binary.BigEndian.Uint64(body[32:40])
	if d := dhashDistance(h, v, wantH, wantV); d > 8 {
		t.Errorf("served hash is %d bits from the fixture", d)
	}

	etag := rec.Header().Get("ETag")
	req := httptest.NewRequest("GET", "/api/catalog/hashes", nil)
	req.Header.Set("If-None-Match", etag)
	rec = httptest.NewRecorder()
	handleCatalogHashes(rec, req)
	if rec.Code != 304 {
		t.Errorf("revalidation: %d", rec.Code)
	}
}
