package main

import "testing"

// normNumber has to agree with normNumber() in editions.js, or the set overlay
// marks owned cards as missing.
func TestNormNumber(t *testing.T) {
	for in, want := range map[string]string{
		"161":   "161",
		"047":   "47",
		"0161":  "161",
		"0":     "0",
		"000":   "0",
		" 12a ": "12a",
		"012A":  "12a",
		"0a":    "0a", // the zero is not followed by a digit, so it stays
		"★1":    "★1",
		"":      "",
	} {
		if got := normNumber(in); got != want {
			t.Errorf("normNumber(%q) = %q, want %q", in, got, want)
		}
	}
}
