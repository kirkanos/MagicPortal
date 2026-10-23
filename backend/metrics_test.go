package main

import "testing"

// Every route gets its own label, and nothing that carries user data in the
// path (set code, card name) may become a label value of its own - each
// distinct value would be a new Prometheus series.
func TestRouteLabel(t *testing.T) {
	for path, want := range map[string]string{
		"/api/collection":                "/api/collection",
		"/api/collection/owned":          "/api/collection/owned",
		"/api/editions":                  "/api/editions",
		"/api/catalog/index":             "/api/catalog/index",
		"/api/catalog/search":            "/api/catalog/search",
		"/api/catalog/hashes":            "/api/catalog/hashes",
		"/api/hashes/sync":               "/api/hashes/sync",
		"/api/images/sync":               "/api/images/sync",
		"/api/catalog/x":                 "other",
		"/api/sets/snc/cards":            "/api/sets/{code}/cards",
		"/api/cards/facets":              "/api/cards/facets",
		"/api/wishlist":                  "/api/wishlist",
		"/api/wishlist/export":           "/api/wishlist/export",
		"/api/wishlist/Black Lotus":      "/api/wishlist/{name}",
		"/api/wishlist/Fire%20%2F%2FIce": "/api/wishlist/{name}",
		"/api/activity/clear":            "/api/activity/clear",
		"/api/health":                    "/api/health",
		"/api/nonsense":                  "other",
		"/api/collection/whatever":       "other",
		"/index.html":                    "other",
	} {
		if got := routeLabel(path); got != want {
			t.Errorf("routeLabel(%q) = %q, want %q", path, got, want)
		}
	}
}
