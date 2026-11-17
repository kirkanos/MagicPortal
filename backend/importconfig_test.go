package main

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestImportConfigEnvDefaultsAndOverride(t *testing.T) {
	newTestDB(t)
	t.Setenv("REMOTE_IMPORT_MODE", "manual")
	t.Setenv("REMOTE_IMPORT_INTERVAL_MINUTES", "30")
	if c := loadImportConfig(); c.Mode != "manual" || c.IntervalMinutes != 30 {
		t.Fatalf("defaults from env: %+v", c)
	}

	// Saving only the mode keeps the interval from the environment.
	rec := httptest.NewRecorder()
	handleImportConfigSet(rec, httptest.NewRequest("POST", "/api/import/config", strings.NewReader(`{"mode":"auto"}`)))
	if rec.Code != 200 {
		t.Fatalf("set: %d %s", rec.Code, rec.Body)
	}
	if c := loadImportConfig(); c.Mode != "auto" || c.IntervalMinutes != 30 {
		t.Fatalf("after mode change: %+v", c)
	}
	rec = httptest.NewRecorder()
	handleImportConfigSet(rec, httptest.NewRequest("POST", "/api/import/config", strings.NewReader(`{"intervalMinutes":120}`)))
	if c := loadImportConfig(); rec.Code != 200 || c.Mode != "auto" || c.IntervalMinutes != 120 {
		t.Fatalf("after interval change: %d %+v", rec.Code, c)
	}
	// The database wins over a changed environment.
	t.Setenv("REMOTE_IMPORT_INTERVAL_MINUTES", "5")
	if c := loadImportConfig(); c.IntervalMinutes != 120 {
		t.Fatalf("db should win: %+v", c)
	}

	for _, bad := range []string{`{"mode":"sometimes"}`, `{"intervalMinutes":0}`, `{"intervalMinutes":99999}`} {
		rec = httptest.NewRecorder()
		handleImportConfigSet(rec, httptest.NewRequest("POST", "/api/import/config", strings.NewReader(bad)))
		if rec.Code != http.StatusBadRequest {
			t.Errorf("%s: want 400, got %d", bad, rec.Code)
		}
	}
}

func TestNextImportWait(t *testing.T) {
	now := time.Date(2026, 11, 17, 12, 0, 0, 0, time.UTC)
	auto := importConfig{Mode: "auto", IntervalMinutes: 15}
	if due, wait := nextImportWait(auto, time.Time{}, now); !due || wait != 15*time.Minute {
		t.Errorf("first round: %v %v", due, wait)
	}
	if due, wait := nextImportWait(auto, now.Add(-5*time.Minute), now); due || wait != 10*time.Minute {
		t.Errorf("mid interval: %v %v", due, wait)
	}
	if due, _ := nextImportWait(auto, now.Add(-15*time.Minute), now); !due {
		t.Errorf("interval over: not due")
	}
	if due, _ := nextImportWait(importConfig{Mode: "manual", IntervalMinutes: 15}, time.Time{}, now); due {
		t.Errorf("manual must never be due")
	}
}
