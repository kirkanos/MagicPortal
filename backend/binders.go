package main

import (
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"sort"
	"strings"
	"sync"
)

// A binder is otherwise only the binder_name/binder_type pair on collection
// rows, so it exists while it holds a card. Binders created by hand (folders,
// lists, decks) are kept in meta (key created_binders, a JSON list of
// {name, type}): they show in /api/binders even while empty and stay until
// they are deleted. The backup carries them with the rest of meta.

type createdBinder struct {
	Name string `json:"name"`
	Type string `json:"type"`
}

const createdBindersKey = "created_binders"

var createdBindersMu sync.Mutex

func parseCreatedBinders(raw string) []createdBinder {
	var out []createdBinder
	if raw != "" {
		_ = json.Unmarshal([]byte(raw), &out)
	}
	return out
}

func loadCreatedBinders() []createdBinder {
	return parseCreatedBinders(metaGet(db, createdBindersKey))
}

func saveCreatedBinders(list []createdBinder) {
	if len(list) == 0 {
		_, _ = db.Exec(`DELETE FROM meta WHERE key = ?`, createdBindersKey)
		return
	}
	sort.Slice(list, func(i, j int) bool { return list[i].Name < list[j].Name })
	b, _ := json.Marshal(list)
	metaSet(db, createdBindersKey, string(b))
}

// createdBinderTypeTx is the type of a created binder, read inside a
// transaction (metaGet would wait for the one connection the tx holds).
func createdBinderTypeTx(tx *sql.Tx, name string) string {
	var raw string
	_ = tx.QueryRow(`SELECT value FROM meta WHERE key = ?`, createdBindersKey).Scan(&raw)
	for _, b := range parseCreatedBinders(raw) {
		if b.Name == name {
			return b.Type
		}
	}
	return ""
}

// createdBinderType is "" for a binder that was not created by hand.
func createdBinderType(name string) string {
	for _, b := range loadCreatedBinders() {
		if b.Name == name {
			return b.Type
		}
	}
	return ""
}

// renameCreatedBinder follows a rename (or merge) and a type change.
func renameCreatedBinder(from, to, typ string) {
	createdBindersMu.Lock()
	defer createdBindersMu.Unlock()
	list := loadCreatedBinders()
	changed := false
	out := list[:0]
	for _, b := range list {
		if b.Name == from {
			changed = true
			continue
		}
		if b.Name == to {
			changed = true
			continue
		}
		out = append(out, b)
	}
	if !changed {
		return
	}
	saveCreatedBinders(append(out, createdBinder{to, typ}))
}

func binderRowCount(name string) (int, error) {
	var n int
	err := db.QueryRow(`SELECT COUNT(*) FROM collection WHERE binder_name = ?`, name).Scan(&n)
	return n, err
}

// POST /api/binders {name, type}: a new, empty binder of type binder, list or deck.
func handleBinderCreate(w http.ResponseWriter, r *http.Request) {
	if !authorized(r) {
		http.Error(w, "Wrong or missing password", 403)
		return
	}
	var in createdBinder
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<16)).Decode(&in); err != nil {
		http.Error(w, "invalid JSON: "+err.Error(), 400)
		return
	}
	in.Name = strings.TrimSpace(in.Name)
	typ, err := normBinderType(in.Type)
	switch {
	case err != nil || typ == "":
		writeWriteError(w, badItem(-1, "type must be binder, list or deck"))
		return
	case in.Name == "":
		writeWriteError(w, badItem(-1, "name is required"))
		return
	case len(in.Name) > 100:
		writeWriteError(w, badItem(-1, "name too long"))
		return
	}
	in.Type = typ
	n, err := binderRowCount(in.Name)
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	createdBindersMu.Lock()
	list := loadCreatedBinders()
	taken := n > 0
	for _, b := range list {
		taken = taken || b.Name == in.Name
	}
	if !taken {
		saveCreatedBinders(append(list, in))
	}
	createdBindersMu.Unlock()
	if taken {
		writeWriteError(w, &writeError{http.StatusConflict, -1, "a binder with this name exists"})
		return
	}
	metricAppWrites.inc(labels("op", "binder"))
	logActivity("info", "App", fmt.Sprintf("Binder %q (%s) created", in.Name, in.Type))
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(http.StatusCreated)
	_ = json.NewEncoder(w).Encode(binderOut{Name: in.Name, Type: in.Type})
}

// DELETE /api/binders/{name}: removes a created binder; only an empty one, a
// binder with cards is emptied by moving them first.
func handleBinderDelete(w http.ResponseWriter, r *http.Request) {
	if !authorized(r) {
		http.Error(w, "Wrong or missing password", 403)
		return
	}
	name := r.PathValue("name")
	n, err := binderRowCount(name)
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	if n > 0 {
		writeWriteError(w, &writeError{http.StatusConflict, -1, "the binder still holds cards"})
		return
	}
	createdBindersMu.Lock()
	list := loadCreatedBinders()
	out := list[:0]
	for _, b := range list {
		if b.Name != name {
			out = append(out, b)
		}
	}
	found := len(out) != len(list)
	if found {
		saveCreatedBinders(out)
	}
	createdBindersMu.Unlock()
	if !found {
		writeWriteError(w, &writeError{http.StatusNotFound, -1, "no such binder"})
		return
	}
	renameDeckCover(name, "") // drops cover and format of a deck
	metricAppWrites.inc(labels("op", "binder"))
	logActivity("info", "App", fmt.Sprintf("Binder %q deleted", name))
	w.WriteHeader(http.StatusNoContent)
}

// patchCreatedBinder renames or retypes a created binder that holds no cards
// (patchBinder works on rows and has none to move).
func patchCreatedBinder(from string, p binderPatch) (binderPatchResult, error) {
	createdBindersMu.Lock()
	defer createdBindersMu.Unlock()
	list := loadCreatedBinders()
	idx := -1
	for i, b := range list {
		if b.Name == from {
			idx = i
		}
	}
	if idx < 0 {
		return binderPatchResult{}, &writeError{http.StatusNotFound, -1, "no such binder"}
	}
	b := list[idx]
	if p.Name != nil {
		to := strings.TrimSpace(*p.Name)
		if to == "" || len(to) > 100 {
			return binderPatchResult{}, badItem(-1, "invalid name")
		}
		if to != from {
			n, err := binderRowCount(to)
			if err != nil {
				return binderPatchResult{}, err
			}
			for _, o := range list {
				if o.Name == to {
					n++
				}
			}
			if n > 0 {
				// Into an existing binder: this one is simply gone.
				list = append(list[:idx], list[idx+1:]...)
				saveCreatedBinders(list)
				typ := b.Type
				_ = db.QueryRow(`SELECT binder_type FROM collection WHERE binder_name = ? LIMIT 1`, to).Scan(&typ)
				return binderPatchResult{Name: to, Type: typ}, nil
			}
		}
		b.Name = to
	}
	if p.Type != nil {
		t, err := normBinderType(*p.Type)
		if err != nil || t == "" {
			return binderPatchResult{}, badItem(-1, "type must be binder, list or deck")
		}
		b.Type = t
	}
	list[idx] = b
	saveCreatedBinders(list)
	return binderPatchResult{Name: b.Name, Type: b.Type}, nil
}

// isNoSuchBinder: patchBinder found no rows under that name.
func isNoSuchBinder(err error) bool {
	var we *writeError
	return errors.As(err, &we) && we.status == http.StatusNotFound
}
