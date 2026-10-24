package main

import (
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"time"
)

// Writing single cards into the collection, for the Android app.
//
// Until now the collection came only from ManaBox CSV exports. The app adds
// scanned cards, changes quantities and moves cards between binders. Its rows
// carry origin 'app' (see collectionSchema), so the next CSV import leaves
// them alone. Rows from the import can be edited too, but such a change only
// lasts until the next import resets it; answers say so with "volatile".
//
//   - POST   /api/collection/cards       add a batch (one transaction)
//   - GET    /api/collection/cards/{id}  one row
//   - PATCH  /api/collection/cards/{id}  quantity, delta, or a new key (moves)
//   - DELETE /api/collection/cards/{id}
//
// Name, set name, rarity and Scryfall ID always come from the catalog, never
// from the client: a card the catalog does not know is refused.
//
// Deadlock rule (single connection, see openDB): inside a transaction never
// call anything that uses db - logActivity, metaGet/metaSet, countRows,
// collectionETag. They would wait forever for the connection the
// transaction holds. Pattern: decode, validate, transaction, commit, then log.

const maxBatchItems = 500

var setCodeRe = regexp.MustCompile(`^[a-z0-9]{2,6}$`)

type writeItem struct {
	SetCode         string   `json:"setCode"`
	CollectorNumber string   `json:"collectorNumber"`
	Quantity        int      `json:"quantity"`
	Foil            string   `json:"foil"`
	Language        string   `json:"language"`
	Condition       string   `json:"condition"`
	BinderName      string   `json:"binderName"`
	BinderType      string   `json:"binderType"`
	PurchasePrice   *float64 `json:"purchasePrice"`
}

type rowOut struct {
	ID              int64   `json:"id"`
	Name            string  `json:"name"`
	SetCode         string  `json:"setCode"`
	SetName         string  `json:"setName"`
	CollectorNumber string  `json:"collectorNumber"`
	ScryfallID      string  `json:"scryfallId"`
	Rarity          string  `json:"rarity"`
	Foil            string  `json:"foil"`
	Language        string  `json:"language"`
	Condition       string  `json:"condition"`
	Quantity        int     `json:"quantity"`
	PurchasePrice   float64 `json:"purchasePrice"`
	Currency        string  `json:"currency"`
	BinderName      string  `json:"binderName"`
	BinderType      string  `json:"binderType"`
	Added           string  `json:"added"`
	Origin          string  `json:"origin"`
	// The next CSV import will reset this row (origin csv).
	Volatile bool `json:"volatile,omitempty"`
}

type addResult struct {
	Items  []addedItem `json:"items"`
	Added  int         `json:"added"`
	Merged int         `json:"merged"`
}

type addedItem struct {
	rowOut
	// True when the card joined an existing row; quantity is the new total.
	Merged bool `json:"merged"`
}

// writeError carries an HTTP status and, for batches, the item it is about.
type writeError struct {
	status int
	index  int
	msg    string
}

func (e *writeError) Error() string { return e.msg }

func badItem(i int, format string, args ...any) *writeError {
	return &writeError{http.StatusBadRequest, i, fmt.Sprintf(format, args...)}
}

// normalize checks one item and fills the defaults. It never touches the db.
func (it *writeItem) normalize(i int) error {
	it.SetCode = strings.ToLower(strings.TrimSpace(it.SetCode))
	if !setCodeRe.MatchString(it.SetCode) {
		return badItem(i, "setCode %q is not a set code", it.SetCode)
	}
	it.CollectorNumber = strings.TrimSpace(it.CollectorNumber)
	if it.CollectorNumber == "" || len(it.CollectorNumber) > 20 {
		return badItem(i, "collectorNumber missing or too long")
	}
	if it.Quantity == 0 {
		it.Quantity = 1
	}
	if it.Quantity < 1 || it.Quantity > 999 {
		return badItem(i, "quantity must be 1..999")
	}
	var err error
	if it.Foil, err = normFoil(it.Foil); err != nil {
		return badItem(i, "%v", err)
	}
	if it.Language, err = normLanguage(it.Language); err != nil {
		return badItem(i, "%v", err)
	}
	if it.Condition, err = normCondition(it.Condition); err != nil {
		return badItem(i, "%v", err)
	}
	it.BinderName = strings.TrimSpace(it.BinderName)
	if len(it.BinderName) > 100 {
		return badItem(i, "binderName too long")
	}
	if it.BinderType, err = normBinderType(it.BinderType); err != nil {
		return badItem(i, "%v", err)
	}
	if it.PurchasePrice != nil && (*it.PurchasePrice < 0 || *it.PurchasePrice > 1e6) {
		return badItem(i, "purchasePrice out of range")
	}
	return nil
}

func normFoil(f string) (string, error) {
	switch f = strings.ToLower(strings.TrimSpace(f)); f {
	case "":
		return "normal", nil
	case "normal", "foil", "etched":
		return f, nil
	}
	return "", fmt.Errorf("foil must be normal, foil or etched")
}

func normLanguage(l string) (string, error) {
	l = strings.ToLower(strings.TrimSpace(l))
	if l == "" {
		return "en", nil
	}
	if len(l) < 2 || len(l) > 3 || strings.Trim(l, "abcdefghijklmnopqrstuvwxyz") != "" {
		return "", fmt.Errorf("language %q is not a language code", l)
	}
	return l, nil
}

func normCondition(c string) (string, error) {
	c = strings.ToLower(strings.TrimSpace(c))
	if c == "" {
		return "near_mint", nil
	}
	if _, ok := conditionRankOf(c); !ok {
		return "", fmt.Errorf("condition %q unknown", c)
	}
	return c, nil
}

func normBinderType(t string) (string, error) {
	switch t = strings.ToLower(strings.TrimSpace(t)); t {
	case "", "binder", "list", "deck":
		return t, nil
	}
	return "", fmt.Errorf("binderType must be binder, list or deck")
}

// manaboxTime is the format of the "Added" column in ManaBox exports.
func manaboxTime(t time.Time) string { return t.UTC().Format("2006-01-02T15:04:05.000Z") }

// ---- POST /api/collection/cards ----

func handleCollectionAdd(w http.ResponseWriter, r *http.Request) {
	if !authorized(r) {
		http.Error(w, "Wrong or missing password", 403)
		return
	}
	var body struct {
		Items []writeItem `json:"items"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20)).Decode(&body); err != nil {
		http.Error(w, "invalid JSON: "+err.Error(), 400)
		return
	}
	if len(body.Items) == 0 || len(body.Items) > maxBatchItems {
		http.Error(w, fmt.Sprintf("items: 1..%d expected", maxBatchItems), 400)
		return
	}
	for i := range body.Items {
		if err := body.Items[i].normalize(i); err != nil {
			writeWriteError(w, err)
			return
		}
	}
	key := strings.TrimSpace(r.Header.Get("Idempotency-Key"))
	if len(key) > 100 {
		http.Error(w, "Idempotency-Key too long", 400)
		return
	}

	res, replayed, err := addCards(body.Items, key, time.Now())
	if err != nil {
		writeWriteError(w, err)
		return
	}
	if replayed {
		w.Header().Set("Idempotent-Replayed", "true")
	} else {
		cards := 0
		for _, it := range body.Items {
			cards += it.Quantity
		}
		metricAppWrites.add(labels("op", "add"), float64(len(body.Items)))
		logActivity("info", "App", fmt.Sprintf("%d cards added (%d new rows, %d merged)", cards, res.Added, res.Merged))
	}
	writeJSON(w, res)
}

// addCards writes a batch in one transaction. With an idempotency key a
// repeated request gets the stored answer of the first one (replayed), and
// the key is stored together with the rows - both or neither.
func addCards(items []writeItem, key string, now time.Time) (addResult, bool, error) {
	var res addResult
	tx, err := db.Begin()
	if err != nil {
		return res, false, err
	}
	defer tx.Rollback()

	if key != "" {
		var stored string
		err := tx.QueryRow(`SELECT response FROM idempotency_keys WHERE key = ?`, key).Scan(&stored)
		if err == nil {
			if err := json.Unmarshal([]byte(stored), &res); err != nil {
				return res, false, err
			}
			return res, true, nil
		}
		if !errors.Is(err, sql.ErrNoRows) {
			return res, false, err
		}
	}

	ts := now.UTC().Format(time.RFC3339)
	added := manaboxTime(now)
	for i, it := range items {
		cat, err := catalogPrintingTx(tx, it.SetCode, it.CollectorNumber)
		if err != nil {
			if errors.Is(err, sql.ErrNoRows) {
				return res, false, &writeError{http.StatusNotFound, i, fmt.Sprintf("%s #%s is not in the catalog", strings.ToUpper(it.SetCode), it.CollectorNumber)}
			}
			return res, false, err
		}
		binderType, err := binderTypeTx(tx, it.BinderName, it.BinderType)
		if err != nil {
			return res, false, err
		}
		var existing int64
		err = tx.QueryRow(`SELECT id FROM collection WHERE binder_name = ? AND set_code = ? AND collector_number = ?
			AND foil = ? AND language = ? AND condition = ? AND origin = 'app'`,
			it.BinderName, it.SetCode, cat.number, it.Foil, it.Language, it.Condition).Scan(&existing)
		if err != nil && !errors.Is(err, sql.ErrNoRows) {
			return res, false, err
		}
		price := 0.0
		if it.PurchasePrice != nil {
			price = *it.PurchasePrice
		}
		var id int64
		if err := tx.QueryRow(`
			INSERT INTO collection (binder_name, binder_type, scryfall_id, set_code, set_name, collector_number, name,
				foil, rarity, language, quantity, purchase_price, currency, condition, added, updated_at, origin)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'EUR', ?, ?, ?, 'app')
			ON CONFLICT (binder_name, set_code, collector_number, foil, language, condition, origin)
			DO UPDATE SET quantity = quantity + excluded.quantity, updated_at = excluded.updated_at
			RETURNING id`,
			it.BinderName, binderType, cat.scryfallID, it.SetCode, cat.setName, cat.number, cat.name,
			it.Foil, cat.rarity, it.Language, it.Quantity, price, it.Condition, added, ts).Scan(&id); err != nil {
			return res, false, err
		}
		row, err := rowTx(tx, id)
		if err != nil {
			return res, false, err
		}
		res.Items = append(res.Items, addedItem{rowOut: row, Merged: existing != 0})
		if existing != 0 {
			res.Merged++
		} else {
			res.Added++
		}
	}
	if err := bumpCollectionRev(tx); err != nil {
		return res, false, err
	}
	if key != "" {
		stored, _ := json.Marshal(res)
		if _, err := tx.Exec(`INSERT INTO idempotency_keys(key, response, created_at) VALUES(?, ?, ?)`, key, string(stored), ts); err != nil {
			return res, false, err
		}
		// Keys only need to outlive a retry; a week is plenty.
		_, _ = tx.Exec(`DELETE FROM idempotency_keys WHERE created_at < ?`, now.Add(-7*24*time.Hour).UTC().Format(time.RFC3339))
	}
	return res, false, tx.Commit()
}

type catalogFacts struct {
	number, name, setName, rarity, scryfallID string
}

// catalogPrintingTx looks the printing up as the catalog spells it. The
// number is matched as given and, failing that, without leading zeros
// ("047" from a scanner for Scryfall's "47").
func catalogPrintingTx(tx *sql.Tx, set, number string) (catalogFacts, error) {
	var c catalogFacts
	q := `SELECT sc.collector_number, sc.name, COALESCE(st.name, ''), COALESCE(sc.rarity, ''), COALESCE(sc.scryfall_id, '')
		FROM scryfall_cards sc LEFT JOIN sets st ON st.code = sc.set_code
		WHERE sc.set_code = ? AND sc.collector_number = ?`
	err := tx.QueryRow(q, set, number).Scan(&c.number, &c.name, &c.setName, &c.rarity, &c.scryfallID)
	if errors.Is(err, sql.ErrNoRows) {
		if trimmed := strings.TrimLeft(number, "0"); trimmed != number && trimmed != "" {
			err = tx.QueryRow(q, set, trimmed).Scan(&c.number, &c.name, &c.setName, &c.rarity, &c.scryfallID)
		}
	}
	return c, err
}

// binderTypeTx: an existing binder keeps its type, whatever the client says;
// a new one takes the given type ("binder" if none).
func binderTypeTx(tx *sql.Tx, name, given string) (string, error) {
	if name == "" {
		return given, nil
	}
	var t string
	err := tx.QueryRow(`SELECT binder_type FROM collection WHERE binder_name = ? LIMIT 1`, name).Scan(&t)
	if err == nil {
		return t, nil
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return "", err
	}
	if given == "" {
		given = "binder"
	}
	return given, nil
}

const rowColumns = `id, name, set_code, COALESCE(set_name, ''), collector_number, COALESCE(scryfall_id, ''),
	COALESCE(rarity, ''), foil, language, condition, quantity, COALESCE(purchase_price, 0), COALESCE(currency, ''),
	COALESCE(binder_name, ''), COALESCE(binder_type, ''), COALESCE(added, ''), origin`

type rowScanner interface{ Scan(dest ...any) error }

func scanRow(s rowScanner) (rowOut, error) {
	var o rowOut
	err := s.Scan(&o.ID, &o.Name, &o.SetCode, &o.SetName, &o.CollectorNumber, &o.ScryfallID, &o.Rarity, &o.Foil,
		&o.Language, &o.Condition, &o.Quantity, &o.PurchasePrice, &o.Currency, &o.BinderName, &o.BinderType, &o.Added, &o.Origin)
	o.SetCode = strings.ToUpper(o.SetCode)
	o.Volatile = o.Origin == "csv"
	return o, err
}

func rowTx(tx *sql.Tx, id int64) (rowOut, error) {
	return scanRow(tx.QueryRow(`SELECT `+rowColumns+` FROM collection WHERE id = ?`, id))
}

// ---- GET /api/collection/cards/{id} ----

func handleCollectionRow(w http.ResponseWriter, r *http.Request) {
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		http.Error(w, "invalid id", 400)
		return
	}
	row, err := scanRow(db.QueryRow(`SELECT `+rowColumns+` FROM collection WHERE id = ?`, id))
	if errors.Is(err, sql.ErrNoRows) {
		http.Error(w, "no such row", 404)
		return
	}
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	writeJSON(w, row)
}

// ---- PATCH /api/collection/cards/{id} ----

// rowPatch: every field optional. quantity and quantityDelta exclude each
// other; a resulting quantity of 0 deletes the row. A changed binder, foil,
// language or condition moves the row - into an existing one with that key,
// if there is one (mergedInto).
type rowPatch struct {
	Quantity      *int     `json:"quantity"`
	QuantityDelta *int     `json:"quantityDelta"`
	BinderName    *string  `json:"binderName"`
	BinderType    *string  `json:"binderType"`
	Foil          *string  `json:"foil"`
	Language      *string  `json:"language"`
	Condition     *string  `json:"condition"`
	PurchasePrice *float64 `json:"purchasePrice"`
}

type patchResult struct {
	Row        *rowOut `json:"row,omitempty"`
	Deleted    bool    `json:"deleted,omitempty"`
	MergedInto int64   `json:"mergedInto,omitempty"`
}

func handleCollectionPatch(w http.ResponseWriter, r *http.Request) {
	if !authorized(r) {
		http.Error(w, "Wrong or missing password", 403)
		return
	}
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		http.Error(w, "invalid id", 400)
		return
	}
	var p rowPatch
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<16)).Decode(&p); err != nil {
		http.Error(w, "invalid JSON: "+err.Error(), 400)
		return
	}
	if p.Quantity != nil && p.QuantityDelta != nil {
		http.Error(w, "quantity and quantityDelta exclude each other", 400)
		return
	}
	res, op, err := patchRow(id, p, time.Now())
	if err != nil {
		writeWriteError(w, err)
		return
	}
	metricAppWrites.inc(labels("op", op))
	logActivity("info", "App", fmt.Sprintf("Collection row %d: %s", id, op))
	writeJSON(w, res)
}

func patchRow(id int64, p rowPatch, now time.Time) (patchResult, string, error) {
	var res patchResult
	tx, err := db.Begin()
	if err != nil {
		return res, "", err
	}
	defer tx.Rollback()
	cur, err := rowTx(tx, id)
	if errors.Is(err, sql.ErrNoRows) {
		return res, "", &writeError{http.StatusNotFound, -1, "no such row"}
	}
	if err != nil {
		return res, "", err
	}

	next := cur
	switch {
	case p.Quantity != nil:
		next.Quantity = *p.Quantity
	case p.QuantityDelta != nil:
		next.Quantity += *p.QuantityDelta
	}
	if next.Quantity < 0 || next.Quantity > 999 {
		return res, "", badItem(-1, "quantity must end up in 0..999")
	}
	if p.BinderName != nil {
		next.BinderName = strings.TrimSpace(*p.BinderName)
	}
	if p.Foil != nil {
		if next.Foil, err = normFoil(*p.Foil); err != nil {
			return res, "", badItem(-1, "%v", err)
		}
	}
	if p.Language != nil {
		if next.Language, err = normLanguage(*p.Language); err != nil {
			return res, "", badItem(-1, "%v", err)
		}
	}
	if p.Condition != nil {
		if next.Condition, err = normCondition(*p.Condition); err != nil {
			return res, "", badItem(-1, "%v", err)
		}
	}
	if p.PurchasePrice != nil {
		if *p.PurchasePrice < 0 || *p.PurchasePrice > 1e6 {
			return res, "", badItem(-1, "purchasePrice out of range")
		}
		next.PurchasePrice = *p.PurchasePrice
	}
	ts := now.UTC().Format(time.RFC3339)
	setCode := strings.ToLower(cur.SetCode)

	var op string
	switch {
	case next.Quantity == 0:
		if _, err := tx.Exec(`DELETE FROM collection WHERE id = ?`, id); err != nil {
			return res, "", err
		}
		res.Deleted, op = true, "delete"

	case next.BinderName != cur.BinderName || next.Foil != cur.Foil || next.Language != cur.Language || next.Condition != cur.Condition:
		given := ""
		if p.BinderType != nil {
			if given, err = normBinderType(*p.BinderType); err != nil {
				return res, "", badItem(-1, "%v", err)
			}
		}
		if next.BinderName == cur.BinderName {
			given = cur.BinderType
		}
		if next.BinderType, err = binderTypeTx(tx, next.BinderName, given); err != nil {
			return res, "", err
		}
		var target int64
		err := tx.QueryRow(`SELECT id FROM collection WHERE binder_name = ? AND set_code = ? AND collector_number = ?
			AND foil = ? AND language = ? AND condition = ? AND origin = ?`,
			next.BinderName, setCode, cur.CollectorNumber, next.Foil, next.Language, next.Condition, cur.Origin).Scan(&target)
		switch {
		case err == nil:
			// The target key exists: add the cards there, drop this row.
			if _, err := tx.Exec(`UPDATE collection SET quantity = quantity + ?, updated_at = ? WHERE id = ?`, next.Quantity, ts, target); err != nil {
				return res, "", err
			}
			if _, err := tx.Exec(`DELETE FROM collection WHERE id = ?`, id); err != nil {
				return res, "", err
			}
			res.MergedInto, op = target, "merge"
			id = target
		case errors.Is(err, sql.ErrNoRows):
			if _, err := tx.Exec(`UPDATE collection SET binder_name = ?, binder_type = ?, foil = ?, language = ?, condition = ?,
				quantity = ?, purchase_price = ?, updated_at = ? WHERE id = ?`,
				next.BinderName, next.BinderType, next.Foil, next.Language, next.Condition, next.Quantity, next.PurchasePrice, ts, id); err != nil {
				return res, "", err
			}
			op = "move"
		default:
			return res, "", err
		}

	default:
		if _, err := tx.Exec(`UPDATE collection SET quantity = ?, purchase_price = ?, updated_at = ? WHERE id = ?`,
			next.Quantity, next.PurchasePrice, ts, id); err != nil {
			return res, "", err
		}
		op = "update"
	}
	if !res.Deleted {
		row, err := rowTx(tx, id)
		if err != nil {
			return res, "", err
		}
		res.Row = &row
	}
	if err := bumpCollectionRev(tx); err != nil {
		return res, "", err
	}
	return res, op, tx.Commit()
}

// ---- DELETE /api/collection/cards/{id} ----

func handleCollectionDelete(w http.ResponseWriter, r *http.Request) {
	if !authorized(r) {
		http.Error(w, "Wrong or missing password", 403)
		return
	}
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		http.Error(w, "invalid id", 400)
		return
	}
	zero := 0
	if _, _, err := patchRow(id, rowPatch{Quantity: &zero}, time.Now()); err != nil {
		writeWriteError(w, err)
		return
	}
	metricAppWrites.inc(labels("op", "delete"))
	logActivity("info", "App", fmt.Sprintf("Collection row %d deleted", id))
	w.WriteHeader(http.StatusNoContent)
}

func writeWriteError(w http.ResponseWriter, err error) {
	var we *writeError
	if errors.As(err, &we) {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.WriteHeader(we.status)
		body := map[string]any{"error": we.msg}
		if we.index >= 0 {
			body["index"] = we.index
		}
		_ = json.NewEncoder(w).Encode(body)
		return
	}
	http.Error(w, err.Error(), 500)
}
