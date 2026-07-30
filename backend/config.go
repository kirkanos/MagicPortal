package main

import (
	"encoding/json"
	"net/http"
)

// uiConfig holds admin-configurable UI settings, persisted in meta as JSON and
// served publicly (it controls what every visitor sees). Writing requires auth.
type uiConfig struct {
	RandomEnabled bool     `json:"randomEnabled"`
	RandomCount   int      `json:"randomCount"`
	HiddenNav     []string `json:"hiddenNav"` // nav keys to hide (e.g. "decks")
}

func clampConfig(c *uiConfig) {
	if c.RandomCount < 1 {
		c.RandomCount = 1
	}
	if c.RandomCount > 30 {
		c.RandomCount = 30
	}
	if c.HiddenNav == nil {
		c.HiddenNav = []string{}
	}
}

func loadUIConfig() uiConfig {
	cfg := uiConfig{RandomEnabled: true, RandomCount: 6, HiddenNav: []string{}}
	if raw := metaGet(db, "ui_config"); raw != "" {
		_ = json.Unmarshal([]byte(raw), &cfg)
	}
	clampConfig(&cfg)
	return cfg
}

func handleConfigGet(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, loadUIConfig())
}

func handleConfigSet(w http.ResponseWriter, r *http.Request) {
	if !authorized(r) {
		http.Error(w, "Falsches oder fehlendes Passwort", 403)
		return
	}
	defer r.Body.Close()
	var c uiConfig
	if err := json.NewDecoder(r.Body).Decode(&c); err != nil {
		http.Error(w, "Ungültige Konfiguration: "+err.Error(), 400)
		return
	}
	clampConfig(&c)
	b, _ := json.Marshal(c)
	metaSet(db, "ui_config", string(b))
	logActivity("info", "Konfiguration", "Einstellungen im Admin-Interface geändert")
	writeJSON(w, c)
}
