package main

import (
	"bytes"
	"compress/gzip"
	"encoding/json"
	"encoding/xml"
	"fmt"
	"io"
	"log"
	"mime/multipart"
	"net/http"
	"net/textproto"
	"net/url"
	"os"
	"path"
	"sort"
	"strconv"
	"strings"
	"time"
)

// Backup: periodically snapshots the non-reconstructable / user-owned data
// (collection + value_snapshots + price_history + the meta table, which holds
// admin settings) into a small gzipped SQLite file and uploads a dated copy to
// every configured remote (Nextcloud/WebDAV and/or Google Drive). On a fresh
// (empty) database the newest remote backup is restored automatically at
// startup. Restore is also available manually.
//
// The Scryfall catalog (sets, scryfall_cards) is intentionally NOT backed up –
// it is re-downloaded on the next sync (its tables are empty after a restore,
// which forces that download regardless of restored sync markers).

const backupPrefix = "mtg-portal-backup-"
const backupSuffix = ".db.gz"

// meta is included so admin settings (ui_config) and other non-derivable keys
// come back on restore. The Scryfall catalog (scryfall_cards, sets) is still not
// backed up – it is empty after a fresh restore, which makes the sync re-download
// it regardless of any restored sync markers.
var backupTables = []string{"collection", "value_snapshots", "price_history", "meta"}

// ---- config ----

func backupConfigured() bool {
	return os.Getenv("WEBDAV_BACKUP_DIR") != "" ||
		(os.Getenv("GDRIVE_BACKUP_FOLDER_ID") != "" && os.Getenv("GDRIVE_SA_JSON") != "")
}

func backupKeep() int {
	keep := 30
	if v := os.Getenv("BACKUP_KEEP"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n >= 0 {
			keep = n
		}
	}
	return keep
}

// ---- scheduler ----

func startBackupScheduler() {
	if !backupConfigured() {
		log.Printf("[backup] deaktiviert (kein Ziel konfiguriert)")
		return
	}
	interval := 24 * time.Hour
	if v := os.Getenv("BACKUP_INTERVAL_HOURS"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			interval = time.Duration(n) * time.Hour
		}
	}
	log.Printf("[backup] aktiv, Intervall: %s", interval)
	go func() {
		runBackup()
		ticker := time.NewTicker(interval)
		defer ticker.Stop()
		for range ticker.C {
			runBackup()
		}
	}()
}

// runBackup builds one backup and uploads it to every configured target.
func runBackup() {
	// Nothing worth saving yet.
	if countRows(db, "value_snapshots") == 0 && countRows(db, "collection") == 0 {
		return
	}
	data, err := buildBackup()
	if err != nil {
		log.Printf("[backup] Erstellung fehlgeschlagen: %v", err)
		metaSet(db, "backup_last_error", err.Error())
		logActivityDedup("error", "Backup", "Backup-Erstellung fehlgeschlagen: "+err.Error())
		return
	}
	name := backupPrefix + time.Now().UTC().Format(dateLayout) + backupSuffix

	ok := false
	if os.Getenv("WEBDAV_BACKUP_DIR") != "" {
		if err := webdavBackup(name, data); err != nil {
			log.Printf("[backup] WebDAV: %v", err)
			metaSet(db, "backup_last_error", "WebDAV: "+err.Error())
			logActivityDedup("error", "Backup", "Backup zu Nextcloud fehlgeschlagen: "+err.Error())
		} else {
			ok = true
		}
	}
	if os.Getenv("GDRIVE_BACKUP_FOLDER_ID") != "" && os.Getenv("GDRIVE_SA_JSON") != "" {
		if err := gdriveBackup(name, data); err != nil {
			log.Printf("[backup] Google Drive: %v", err)
			metaSet(db, "backup_last_error", "Google Drive: "+err.Error())
			logActivityDedup("error", "Backup", "Backup zu Google Drive fehlgeschlagen: "+err.Error())
		} else {
			ok = true
		}
	}
	if ok {
		metaSet(db, "backup_last_at", time.Now().UTC().Format(time.RFC3339))
		metaSet(db, "backup_last_file", name)
		metaSet(db, "backup_last_error", "")
		log.Printf("[backup] gespeichert: %s (%d Bytes)", name, len(data))
		logActivity("info", "Backup", fmt.Sprintf("Backup gespeichert: %s (%d KB)", name, len(data)/1024))
	}
}

// ---- build / restore ----

// buildBackup copies the user tables into a fresh SQLite file and returns it
// gzip-compressed. Serialized against other writes via the single DB connection.
func buildBackup() ([]byte, error) {
	tmp := "/tmp/mtg-backup.db"
	for _, s := range []string{tmp, tmp + "-wal", tmp + "-shm"} {
		_ = os.Remove(s)
	}
	if _, err := db.Exec(`ATTACH DATABASE '` + tmp + `' AS bak`); err != nil {
		return nil, err
	}
	var buildErr error
	for _, tbl := range backupTables {
		if _, err := db.Exec(`CREATE TABLE bak.` + tbl + ` AS SELECT * FROM main.` + tbl); err != nil {
			buildErr = err
			break
		}
	}
	if _, err := db.Exec(`DETACH DATABASE bak`); err != nil && buildErr == nil {
		buildErr = err
	}
	if buildErr != nil {
		return nil, buildErr
	}

	raw, err := os.ReadFile(tmp)
	for _, s := range []string{tmp, tmp + "-wal", tmp + "-shm"} {
		_ = os.Remove(s)
	}
	if err != nil {
		return nil, err
	}

	var buf bytes.Buffer
	gz := gzip.NewWriter(&buf)
	if _, err := gz.Write(raw); err != nil {
		return nil, err
	}
	if err := gz.Close(); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}

// restoreFromGz replaces the user tables with the contents of a gzipped backup.
func restoreFromGz(gzData []byte) error {
	gr, err := gzip.NewReader(bytes.NewReader(gzData))
	if err != nil {
		return fmt.Errorf("kein gültiges gzip: %w", err)
	}
	raw, err := io.ReadAll(gr)
	gr.Close()
	if err != nil {
		return err
	}
	tmp := "/tmp/mtg-restore.db"
	for _, s := range []string{tmp, tmp + "-wal", tmp + "-shm"} {
		_ = os.Remove(s)
	}
	if err := os.WriteFile(tmp, raw, 0o600); err != nil {
		return err
	}
	defer func() {
		for _, s := range []string{tmp, tmp + "-wal", tmp + "-shm"} {
			_ = os.Remove(s)
		}
	}()

	if _, err := db.Exec(`ATTACH DATABASE '` + tmp + `' AS rst`); err != nil {
		return err
	}
	defer db.Exec(`DETACH DATABASE rst`)

	tx, err := db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	for _, tbl := range backupTables {
		if _, err := tx.Exec(`DELETE FROM main.` + tbl); err != nil {
			return err
		}
		if _, err := tx.Exec(`INSERT INTO main.` + tbl + ` SELECT * FROM rst.` + tbl); err != nil {
			return fmt.Errorf("Tabelle %s: %w", tbl, err)
		}
	}
	return tx.Commit()
}

// maybeAutoRestore restores the newest remote backup when the local DB has no
// user data yet (fresh volume). Best-effort: tries each configured source.
func maybeAutoRestore() {
	if !backupConfigured() {
		return
	}
	if countRows(db, "collection") > 0 || countRows(db, "value_snapshots") > 0 {
		return // already have data, never overwrite
	}
	data, src := fetchLatestBackup()
	if data == nil {
		return
	}
	if err := restoreFromGz(data); err != nil {
		log.Printf("[backup] Auto-Restore (%s) fehlgeschlagen: %v", src, err)
		return
	}
	log.Printf("[backup] Auto-Restore aus %s: %d Einträge, %d Wert-Snapshots wiederhergestellt",
		src, countRows(db, "collection"), countRows(db, "value_snapshots"))
	logActivity("info", "Restore", fmt.Sprintf("Auto-Wiederherstellung aus %s (%d Einträge)", src, countRows(db, "collection")))
	metaSet(db, "backup_last_restore_at", time.Now().UTC().Format(time.RFC3339))
}

// fetchLatestBackup returns the newest backup found across configured sources.
func fetchLatestBackup() ([]byte, string) {
	if os.Getenv("WEBDAV_BACKUP_DIR") != "" {
		if data, err := webdavFetchLatest(); err != nil {
			log.Printf("[backup] WebDAV Restore-Suche: %v", err)
		} else if data != nil {
			return data, "Nextcloud"
		}
	}
	if os.Getenv("GDRIVE_BACKUP_FOLDER_ID") != "" && os.Getenv("GDRIVE_SA_JSON") != "" {
		if data, err := gdriveFetchLatest(); err != nil {
			log.Printf("[backup] Google Drive Restore-Suche: %v", err)
		} else if data != nil {
			return data, "Google Drive"
		}
	}
	return nil, ""
}

func isBackupName(name string) bool {
	return strings.HasPrefix(name, backupPrefix) && strings.HasSuffix(name, backupSuffix)
}

// ---- Nextcloud / WebDAV ----

func webdavCreds() (dir, user, pass string) {
	return strings.TrimRight(os.Getenv("WEBDAV_BACKUP_DIR"), "/"),
		os.Getenv("WEBDAV_USER"), os.Getenv("WEBDAV_PASSWORD")
}

func webdavBackup(name string, data []byte) error {
	dir, user, pass := webdavCreds()
	req, err := http.NewRequest("PUT", dir+"/"+name, bytes.NewReader(data))
	if err != nil {
		return err
	}
	if user != "" {
		req.SetBasicAuth(user, pass)
	}
	req.Header.Set("Content-Type", "application/gzip")
	resp, err := remoteImportClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 300 {
		return fmt.Errorf("PUT HTTP %d", resp.StatusCode)
	}
	pruneWebDAV()
	return nil
}

type davMultistatus struct {
	Responses []struct {
		Href string `xml:"href"`
	} `xml:"response"`
}

// webdavList returns the backup file names present in the backup directory.
func webdavList() ([]string, error) {
	dir, user, pass := webdavCreds()
	req, err := http.NewRequest("PROPFIND", dir+"/", strings.NewReader(
		`<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:getlastmodified/></d:prop></d:propfind>`))
	if err != nil {
		return nil, err
	}
	if user != "" {
		req.SetBasicAuth(user, pass)
	}
	req.Header.Set("Depth", "1")
	req.Header.Set("Content-Type", "application/xml")
	resp, err := remoteImportClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 300 {
		return nil, fmt.Errorf("PROPFIND HTTP %d", resp.StatusCode)
	}
	body, _ := io.ReadAll(resp.Body)
	var ms davMultistatus
	if err := xml.Unmarshal(body, &ms); err != nil {
		return nil, err
	}
	names := []string{}
	for _, r := range ms.Responses {
		base := path.Base(r.Href)
		if unesc, err := url.PathUnescape(base); err == nil {
			base = unesc
		}
		if isBackupName(base) {
			names = append(names, base)
		}
	}
	sort.Sort(sort.Reverse(sort.StringSlice(names))) // newest (by date name) first
	return names, nil
}

func webdavFetchLatest() ([]byte, error) {
	names, err := webdavList()
	if err != nil || len(names) == 0 {
		return nil, err
	}
	dir, user, pass := webdavCreds()
	req, _ := http.NewRequest("GET", dir+"/"+names[0], nil)
	if user != "" {
		req.SetBasicAuth(user, pass)
	}
	resp, err := remoteImportClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("GET HTTP %d", resp.StatusCode)
	}
	return io.ReadAll(resp.Body)
}

func pruneWebDAV() {
	keep := backupKeep()
	if keep <= 0 {
		return
	}
	names, err := webdavList()
	if err != nil || len(names) <= keep {
		return
	}
	dir, user, pass := webdavCreds()
	for _, name := range names[keep:] {
		req, _ := http.NewRequest("DELETE", dir+"/"+name, nil)
		if user != "" {
			req.SetBasicAuth(user, pass)
		}
		if resp, err := remoteImportClient.Do(req); err == nil {
			resp.Body.Close()
		}
	}
}

// ---- Google Drive ----

func gdriveToken() (string, error) {
	sa, err := loadGDriveSA()
	if err != nil {
		return "", err
	}
	return gdriveAccessToken(sa, "https://www.googleapis.com/auth/drive")
}

type gdriveFile struct {
	ID   string `json:"id"`
	Name string `json:"name"`
}

func gdriveListBackups(token string) ([]gdriveFile, error) {
	folder := os.Getenv("GDRIVE_BACKUP_FOLDER_ID")
	q := "'" + folder + "' in parents and trashed=false"
	u := "https://www.googleapis.com/drive/v3/files?" + url.Values{
		"q":                         {q},
		"fields":                    {"files(id,name)"},
		"orderBy":                   {"name desc"},
		"pageSize":                  {"1000"},
		"supportsAllDrives":         {"true"},
		"includeItemsFromAllDrives": {"true"},
	}.Encode()
	req, _ := http.NewRequest("GET", u, nil)
	req.Header.Set("Authorization", "Bearer "+token)
	resp, err := remoteImportClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("list HTTP %d: %s", resp.StatusCode, strings.TrimSpace(string(body)))
	}
	var out struct {
		Files []gdriveFile `json:"files"`
	}
	_ = json.Unmarshal(body, &out)
	files := []gdriveFile{}
	for _, f := range out.Files {
		if isBackupName(f.Name) {
			files = append(files, f)
		}
	}
	return files, nil
}

func gdriveBackup(name string, data []byte) error {
	token, err := gdriveToken()
	if err != nil {
		return err
	}
	existing, _ := gdriveListBackups(token)
	var sameDay string
	for _, f := range existing {
		if f.Name == name {
			sameDay = f.ID
			break
		}
	}

	if sameDay != "" {
		// Update the media of the existing same-day file.
		u := "https://www.googleapis.com/upload/drive/v3/files/" + sameDay + "?uploadType=media&supportsAllDrives=true"
		req, _ := http.NewRequest("PATCH", u, bytes.NewReader(data))
		req.Header.Set("Authorization", "Bearer "+token)
		req.Header.Set("Content-Type", "application/gzip")
		resp, err := remoteImportClient.Do(req)
		if err != nil {
			return err
		}
		defer resp.Body.Close()
		if resp.StatusCode >= 300 {
			b, _ := io.ReadAll(resp.Body)
			return fmt.Errorf("update HTTP %d: %s", resp.StatusCode, strings.TrimSpace(string(b)))
		}
	} else {
		if err := gdriveCreate(token, name, data); err != nil {
			return err
		}
	}
	pruneGDrive(token)
	return nil
}

func gdriveCreate(token, name string, data []byte) error {
	folder := os.Getenv("GDRIVE_BACKUP_FOLDER_ID")
	var body bytes.Buffer
	mw := multipart.NewWriter(&body)

	metaPart, _ := mw.CreatePart(textproto.MIMEHeader{"Content-Type": {"application/json; charset=UTF-8"}})
	meta, _ := json.Marshal(map[string]interface{}{"name": name, "parents": []string{folder}})
	metaPart.Write(meta)

	mediaPart, _ := mw.CreatePart(textproto.MIMEHeader{"Content-Type": {"application/gzip"}})
	mediaPart.Write(data)
	mw.Close()

	u := "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&supportsAllDrives=true"
	req, _ := http.NewRequest("POST", u, &body)
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Content-Type", "multipart/related; boundary="+mw.Boundary())
	resp, err := remoteImportClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 300 {
		b, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("create HTTP %d: %s", resp.StatusCode, strings.TrimSpace(string(b)))
	}
	return nil
}

func gdriveFetchLatest() ([]byte, error) {
	token, err := gdriveToken()
	if err != nil {
		return nil, err
	}
	files, err := gdriveListBackups(token)
	if err != nil || len(files) == 0 {
		return nil, err
	}
	u := "https://www.googleapis.com/drive/v3/files/" + files[0].ID + "?alt=media&supportsAllDrives=true"
	req, _ := http.NewRequest("GET", u, nil)
	req.Header.Set("Authorization", "Bearer "+token)
	resp, err := remoteImportClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		b, _ := io.ReadAll(resp.Body)
		return nil, fmt.Errorf("download HTTP %d: %s", resp.StatusCode, strings.TrimSpace(string(b)))
	}
	return io.ReadAll(resp.Body)
}

func pruneGDrive(token string) {
	keep := backupKeep()
	if keep <= 0 {
		return
	}
	files, err := gdriveListBackups(token)
	if err != nil || len(files) <= keep {
		return
	}
	for _, f := range files[keep:] {
		req, _ := http.NewRequest("DELETE", "https://www.googleapis.com/drive/v3/files/"+f.ID+"?supportsAllDrives=true", nil)
		req.Header.Set("Authorization", "Bearer "+token)
		if resp, err := remoteImportClient.Do(req); err == nil {
			resp.Body.Close()
		}
	}
}

// ---- HTTP handlers ----

func handleBackupNow(w http.ResponseWriter, r *http.Request) {
	if !authorized(r) {
		http.Error(w, "Falsches oder fehlendes Passwort", 403)
		return
	}
	if !backupConfigured() {
		http.Error(w, "Kein Backup-Ziel konfiguriert", 400)
		return
	}
	go runBackup()
	writeJSON(w, map[string]bool{"started": true})
}

// handleRestoreLatest pulls the newest remote backup and restores it (replaces
// the current user data). Password-protected.
func handleRestoreLatest(w http.ResponseWriter, r *http.Request) {
	if !authorized(r) {
		http.Error(w, "Falsches oder fehlendes Passwort", 403)
		return
	}
	data, src := fetchLatestBackup()
	if data == nil {
		http.Error(w, "Kein Backup gefunden", 404)
		return
	}
	if err := restoreFromGz(data); err != nil {
		http.Error(w, "Restore fehlgeschlagen: "+err.Error(), 500)
		return
	}
	metaSet(db, "backup_last_restore_at", time.Now().UTC().Format(time.RFC3339))
	logActivity("info", "Restore", fmt.Sprintf("Backup wiederhergestellt aus %s (%d Einträge)", src, countRows(db, "collection")))
	go startSync(false)
	writeJSON(w, map[string]interface{}{
		"restored": true, "source": src,
		"collectionCount": countRows(db, "collection"),
		"snapshotCount":   countRows(db, "value_snapshots"),
	})
}

// handleRestoreUpload restores from a gzipped backup file uploaded in the body.
func handleRestoreUpload(w http.ResponseWriter, r *http.Request) {
	if !authorized(r) {
		http.Error(w, "Falsches oder fehlendes Passwort", 403)
		return
	}
	defer r.Body.Close()
	data, err := io.ReadAll(io.LimitReader(r.Body, 512*1024*1024))
	if err != nil {
		http.Error(w, err.Error(), 400)
		return
	}
	if err := restoreFromGz(data); err != nil {
		http.Error(w, "Restore fehlgeschlagen: "+err.Error(), 400)
		return
	}
	metaSet(db, "backup_last_restore_at", time.Now().UTC().Format(time.RFC3339))
	logActivity("info", "Restore", fmt.Sprintf("Backup aus Datei wiederhergestellt (%d Einträge)", countRows(db, "collection")))
	go startSync(false)
	writeJSON(w, map[string]interface{}{
		"restored":        true,
		"collectionCount": countRows(db, "collection"),
		"snapshotCount":   countRows(db, "value_snapshots"),
	})
}

// backupStatus is embedded into /api/status.
func backupStatus() map[string]interface{} {
	return map[string]interface{}{
		"enabled":       backupConfigured(),
		"lastAt":        metaGet(db, "backup_last_at"),
		"lastFile":      metaGet(db, "backup_last_file"),
		"lastError":     metaGet(db, "backup_last_error"),
		"lastRestoreAt": metaGet(db, "backup_last_restore_at"),
	}
}
