package main

import (
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"sync"
)

// Binder notes: free text per binder (what is in it, where it stands). They
// live in meta (key binder_notes, a JSON object binder name -> note) like the
// deck covers, so the backup carries them and no table is needed.

const binderNotesKey = "binder_notes"

// maxBinderNote is the longest note, in bytes.
const maxBinderNote = 4000

var binderNotesMu sync.Mutex

func loadBinderNotes() map[string]string {
	out := map[string]string{}
	if raw := metaGet(db, binderNotesKey); raw != "" {
		_ = json.Unmarshal([]byte(raw), &out)
	}
	return out
}

func saveBinderNotes(notes map[string]string) {
	if len(notes) == 0 {
		_, _ = db.Exec(`DELETE FROM meta WHERE key = ?`, binderNotesKey)
		return
	}
	b, _ := json.Marshal(notes)
	metaSet(db, binderNotesKey, string(b))
}

// renameBinderNote moves the note along with a rename; into a binder with a
// note of its own both are kept, one below the other. An empty to drops it.
func renameBinderNote(from, to string) {
	if from == to {
		return
	}
	binderNotesMu.Lock()
	defer binderNotesMu.Unlock()
	notes := loadBinderNotes()
	n, ok := notes[from]
	if !ok {
		return
	}
	delete(notes, from)
	if to != "" {
		if old := notes[to]; old != "" {
			n = old + "\n\n" + n
		}
		notes[to] = n
	}
	saveBinderNotes(notes)
}

// PUT /api/binders/{name}/note {note}: sets the note; an empty one removes it.
func handleBinderNote(w http.ResponseWriter, r *http.Request) {
	if !authorized(r) {
		http.Error(w, "Wrong or missing password", 403)
		return
	}
	name := r.PathValue("name")
	var in struct {
		Note string `json:"note"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<16)).Decode(&in); err != nil {
		http.Error(w, "invalid JSON: "+err.Error(), 400)
		return
	}
	note := strings.TrimSpace(strings.ReplaceAll(in.Note, "\r\n", "\n"))
	if len(note) > maxBinderNote {
		writeWriteError(w, badItem(-1, "note too long (max %d characters)", maxBinderNote))
		return
	}
	if name == "" || name == noBinder {
		writeWriteError(w, badItem(-1, "no such binder"))
		return
	}
	if !binderExists(w, name) {
		return
	}
	binderNotesMu.Lock()
	notes := loadBinderNotes()
	if note == "" {
		delete(notes, name)
	} else {
		notes[name] = note
	}
	saveBinderNotes(notes)
	binderNotesMu.Unlock()
	metricAppWrites.inc(labels("op", "binder"))
	logActivity("info", "App", fmt.Sprintf("Binder %q: note updated", name))
	writeJSON(w, map[string]string{"name": name, "note": note})
}
