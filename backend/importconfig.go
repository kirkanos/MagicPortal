package main

import (
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"
)

// Import settings: whether the remote import (Nextcloud / Google Drive) runs on
// its own and how often. The environment gives the defaults
// (REMOTE_IMPORT_MODE, REMOTE_IMPORT_INTERVAL_MINUTES); what the settings page
// saves lives in meta and wins over them. The sources themselves (URLs,
// credentials) stay in the environment.

type importConfig struct {
	Mode            string `json:"mode"` // "auto" or "manual"
	IntervalMinutes int    `json:"intervalMinutes"`
}

const (
	importModeAuto   = "auto"
	importModeManual = "manual"
	// A week; longer makes no sense for a poll.
	maxImportInterval = 7 * 24 * 60
)

// importConfigChanged wakes the scheduler when the settings change.
var importConfigChanged = make(chan struct{}, 1)

func importConfigDefaults() importConfig {
	c := importConfig{Mode: importModeAuto, IntervalMinutes: 15}
	switch strings.ToLower(strings.TrimSpace(os.Getenv("REMOTE_IMPORT_MODE"))) {
	case importModeManual, "off", "false":
		c.Mode = importModeManual
	}
	if v := os.Getenv("REMOTE_IMPORT_INTERVAL_MINUTES"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			c.IntervalMinutes = n
		}
	}
	return clampImportConfig(c)
}

func clampImportConfig(c importConfig) importConfig {
	if c.Mode != importModeManual {
		c.Mode = importModeAuto
	}
	if c.IntervalMinutes < 1 {
		c.IntervalMinutes = 1
	}
	if c.IntervalMinutes > maxImportInterval {
		c.IntervalMinutes = maxImportInterval
	}
	return c
}

// loadImportConfig: the saved settings, field by field over the defaults.
func loadImportConfig() importConfig {
	c := importConfigDefaults()
	if raw := metaGet(db, "import_config"); raw != "" {
		var saved struct {
			Mode            *string `json:"mode"`
			IntervalMinutes *int    `json:"intervalMinutes"`
		}
		if json.Unmarshal([]byte(raw), &saved) == nil {
			if saved.Mode != nil {
				c.Mode = *saved.Mode
			}
			if saved.IntervalMinutes != nil {
				c.IntervalMinutes = *saved.IntervalMinutes
			}
		}
	}
	return clampImportConfig(c)
}

func remoteImportSources() []string {
	sources := []string{}
	if webdavConfigured() {
		sources = append(sources, "Nextcloud")
	}
	if gdriveConfigured() {
		sources = append(sources, "Google Drive")
	}
	return sources
}

// remoteImportStatus is the remoteImport block of /api/status.
func remoteImportStatus() map[string]interface{} {
	cfg := loadImportConfig()
	st := map[string]interface{}{
		"enabled":         webdavConfigured() || gdriveConfigured(),
		"running":         remoteImportRunning.Load(),
		"sources":         remoteImportSources(),
		"mode":            cfg.Mode,
		"intervalMinutes": cfg.IntervalMinutes,
		"defaults":        importConfigDefaults(),
		"lastAt":          metaGet(db, "remote_import_last_at"),
		"lastSource":      metaGet(db, "remote_import_last_source"),
		"lastError":       metaGet(db, "remote_import_last_error"),
		"lastCheckAt":     metaGet(db, "remote_import_last_check_at"),
	}
	return st
}

// POST /api/import/config {mode, intervalMinutes}; a missing field keeps its value.
func handleImportConfigSet(w http.ResponseWriter, r *http.Request) {
	if !authorized(r) {
		http.Error(w, "Wrong or missing password", 403)
		return
	}
	var in struct {
		Mode            *string `json:"mode"`
		IntervalMinutes *int    `json:"intervalMinutes"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<12)).Decode(&in); err != nil {
		http.Error(w, "invalid JSON: "+err.Error(), 400)
		return
	}
	c := loadImportConfig()
	if in.Mode != nil {
		switch *in.Mode {
		case importModeAuto, importModeManual:
			c.Mode = *in.Mode
		default:
			http.Error(w, "mode must be auto or manual", 400)
			return
		}
	}
	if in.IntervalMinutes != nil {
		if *in.IntervalMinutes < 1 || *in.IntervalMinutes > maxImportInterval {
			http.Error(w, fmt.Sprintf("intervalMinutes must be 1..%d", maxImportInterval), 400)
			return
		}
		c.IntervalMinutes = *in.IntervalMinutes
	}
	b, _ := json.Marshal(c)
	metaSet(db, "import_config", string(b))
	select {
	case importConfigChanged <- struct{}{}:
	default:
	}
	logActivity("info", "Config", fmt.Sprintf("Import settings: %s, every %d min", c.Mode, c.IntervalMinutes))
	writeJSON(w, c)
}

// POST /api/import/run: look for a new CSV now, in either mode.
func handleImportRun(w http.ResponseWriter, r *http.Request) {
	if !authorized(r) {
		http.Error(w, "Wrong or missing password", 403)
		return
	}
	if !webdavConfigured() && !gdriveConfigured() {
		http.Error(w, "No import source configured", 400)
		return
	}
	logActivity("info", "Import", "Manual check for a new CSV started")
	go checkRemoteImports()
	writeJSON(w, map[string]bool{"started": true})
}

// nextImportWait: how long the scheduler sleeps before the next look. In
// manual mode it only wakes for a settings change (and once an hour).
func nextImportWait(c importConfig, last, now time.Time) (due bool, wait time.Duration) {
	if c.Mode != importModeAuto {
		return false, time.Hour
	}
	interval := time.Duration(c.IntervalMinutes) * time.Minute
	if last.IsZero() || !now.Before(last.Add(interval)) {
		return true, interval
	}
	return false, last.Add(interval).Sub(now)
}
