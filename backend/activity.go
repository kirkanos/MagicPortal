package main

import (
	"net/http"
	"time"
)

// activityMax bounds the persisted log so it can't grow unbounded.
const activityMax = 500

// logActivity records one entry and trims the log to the newest activityMax rows.
// level is "info" or "error"; source is a short category ("Sync", "Import", …).
func logActivity(level, source, message string) {
	if db == nil {
		return
	}
	_, _ = db.Exec(`INSERT INTO activity_log(ts, level, source, message) VALUES(?, ?, ?, ?)`,
		time.Now().UTC().Format(time.RFC3339), level, source, message)
	_, _ = db.Exec(`DELETE FROM activity_log
		WHERE id NOT IN (SELECT id FROM activity_log ORDER BY id DESC LIMIT ?)`, activityMax)
}

// logActivityDedup skips the entry when the most recent one for the same source
// carries the identical message – collapses repeated (e.g. every-5-min) errors.
func logActivityDedup(level, source, message string) {
	if db == nil {
		return
	}
	var last string
	_ = db.QueryRow(`SELECT message FROM activity_log WHERE source = ? ORDER BY id DESC LIMIT 1`, source).Scan(&last)
	if last == message {
		return
	}
	logActivity(level, source, message)
}

type activityOut struct {
	Ts      string `json:"ts"`
	Level   string `json:"level"`
	Source  string `json:"source"`
	Message string `json:"message"`
}

func handleActivity(w http.ResponseWriter, r *http.Request) {
	if !authorized(r) {
		http.Error(w, "Falsches oder fehlendes Passwort", 403)
		return
	}
	rows, err := db.Query(`SELECT ts, level, source, message FROM activity_log ORDER BY id DESC LIMIT ?`, activityMax)
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	defer rows.Close()
	out := []activityOut{}
	for rows.Next() {
		var a activityOut
		if err := rows.Scan(&a.Ts, &a.Level, &a.Source, &a.Message); err != nil {
			http.Error(w, err.Error(), 500)
			return
		}
		out = append(out, a)
	}
	writeJSON(w, out)
}

func handleActivityClear(w http.ResponseWriter, r *http.Request) {
	if !authorized(r) {
		http.Error(w, "Falsches oder fehlendes Passwort", 403)
		return
	}
	if _, err := db.Exec(`DELETE FROM activity_log`); err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	logActivity("info", "Aktivität", "Protokoll geleert")
	writeJSON(w, map[string]bool{"cleared": true})
}
