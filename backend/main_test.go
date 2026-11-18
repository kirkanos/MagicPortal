package main

import (
	"os"
	"testing"
)

// The tests run without a password, like a stack bound to localhost; the
// auth tests set one themselves.
func TestMain(m *testing.M) {
	allowNoPassword = true
	os.Exit(m.Run())
}
