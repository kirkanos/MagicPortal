package main

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
)

// Conditional GETs for the two large read-only endpoints.
//
// /api/collection is by far the heaviest response (several MB gzipped for a
// real collection) and every page that shows cards fetches it again. The data
// only changes on an import, an edit or a price sync, so a browser that already
// has it should get a 304 instead of the whole payload.
//
// The validator is derived from the data, not from the rendered body: hashing
// the body would mean building the whole response in memory on every request,
// which is exactly the cost we are trying to avoid. The inputs below cover
// everything that can change the response:
//
//   - collection: row count and the newest updated_at (covers insert, update
//     and delete, since an import rewrites every row);
//   - bulk_synced_at: the Scryfall catalog carries the prices, images and
//     oracle text that get joined into each row.
//
// ETags are weak: nginx gzips the response, which makes it no longer
// byte-identical to what the handler produced.
//
// payloadVersion is part of every validator. Without it a release that only
// changes the SHAPE of a response (a renamed or dropped field) would keep
// answering 304 to clients whose cached copy still has the old shape, because
// the underlying rows did not change - they would render stale data until the
// next import. Bump this whenever a cacheable response's JSON changes.
const payloadVersion = "2"

// collectionETag builds the validator for /api/collection.
func collectionETag(db *sql.DB) string {
	var count int
	var maxUpdated sql.NullString
	_ = db.QueryRow(`SELECT COUNT(*), MAX(updated_at) FROM collection`).Scan(&count, &maxUpdated)
	return weakETag("c", fmt.Sprintf("%d|%s|%s", count, maxUpdated.String, metaGet(db, "bulk_synced_at")))
}

// setsETag builds the validator for /api/sets.
func setsETag(db *sql.DB) string {
	var count int
	var maxUpdated sql.NullString
	_ = db.QueryRow(`SELECT COUNT(*), MAX(updated_at) FROM sets`).Scan(&count, &maxUpdated)
	return weakETag("s", fmt.Sprintf("%d|%s|%s", count, maxUpdated.String, metaGet(db, "sets_synced_at")))
}

func weakETag(prefix, input string) string {
	return fmt.Sprintf(`W/"%s%s-%s"`, prefix, payloadVersion, sha256hex([]byte(payloadVersion + "|" + input))[:16])
}

// notModified sets the caching headers and reports whether the client's copy is
// still current, in which case the caller must not write a body.
//
// Cache-Control is "no-cache", not "no-store": the browser may keep the
// response but has to revalidate it every time. That is what turns a repeat
// visit into a 304 with an empty body.
func notModified(w http.ResponseWriter, r *http.Request, etag string) bool {
	w.Header().Set("ETag", etag)
	w.Header().Set("Cache-Control", "no-cache")
	if matchesETag(r.Header.Get("If-None-Match"), etag) {
		w.WriteHeader(http.StatusNotModified)
		return true
	}
	return false
}

// matchesETag implements the If-None-Match comparison: a comma-separated list,
// "*" matches anything, and the weak prefix is ignored in the comparison (weak
// comparison per RFC 9110, which is what a GET needs).
func matchesETag(header, etag string) bool {
	if header == "" {
		return false
	}
	want := strings.TrimPrefix(etag, "W/")
	for _, candidate := range strings.Split(header, ",") {
		candidate = strings.TrimSpace(candidate)
		if candidate == "*" {
			return true
		}
		if strings.TrimPrefix(candidate, "W/") == want {
			return true
		}
	}
	return false
}

// writeJSONCacheable writes a JSON body that the browser may cache and
// revalidate. Unlike writeJSON it leaves Cache-Control and ETag alone - those
// belong to notModified, which ran first.
func writeJSONCacheable(w http.ResponseWriter, v interface{}) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	_ = json.NewEncoder(w).Encode(v)
}
