package main

import (
	"bytes"
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Remote auto-import: periodically checks a Nextcloud (WebDAV) file and/or a
// Google Drive file for a newer ManaBox CSV export and, when it changed,
// downloads it and runs the normal full-replace import. Change detection uses a
// per-source marker stored in meta (ETag / Last-Modified / md5), so an unchanged
// file is never re-imported and – where the server supports it – not even
// re-downloaded.
//
// All sources are optional and disabled when their required env vars are empty.
// No external dependencies: the Google service-account JWT is signed with the
// standard library.

var (
	remoteImportMu     sync.Mutex
	remoteImportClient = &http.Client{Timeout: 5 * time.Minute}
)

// startRemoteImportScheduler starts the periodic remote-import checker unless no
// source is configured. Interval defaults to 15 min (REMOTE_IMPORT_INTERVAL_MINUTES).
func startRemoteImportScheduler() {
	if !webdavConfigured() && !gdriveConfigured() {
		log.Printf("[remote-import] deaktiviert (keine Quelle konfiguriert)")
		return
	}
	interval := 15 * time.Minute
	if v := os.Getenv("REMOTE_IMPORT_INTERVAL_MINUTES"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			interval = time.Duration(n) * time.Minute
		}
	}
	sources := []string{}
	if webdavConfigured() {
		sources = append(sources, "Nextcloud/WebDAV")
	}
	if gdriveConfigured() {
		sources = append(sources, "Google Drive")
	}
	log.Printf("[remote-import] aktiv (%s), Intervall: %s", strings.Join(sources, " + "), interval)

	go func() {
		checkRemoteImports()
		ticker := time.NewTicker(interval)
		defer ticker.Stop()
		for range ticker.C {
			checkRemoteImports()
		}
	}()
}

// checkRemoteImports runs one round over all configured sources. Only one round
// runs at a time. The first source that yields a changed file wins the round.
func checkRemoteImports() {
	if !remoteImportMu.TryLock() {
		return
	}
	defer remoteImportMu.Unlock()

	if webdavConfigured() {
		if err := checkWebDAV(); err != nil {
			log.Printf("[remote-import] Nextcloud/WebDAV: %v", err)
			metaSet(db, "remote_import_last_error", "WebDAV: "+err.Error())
		}
	}
	if gdriveConfigured() {
		if err := checkGDrive(); err != nil {
			log.Printf("[remote-import] Google Drive: %v", err)
			metaSet(db, "remote_import_last_error", "Google Drive: "+err.Error())
		}
	}
}

// applyRemoteCSV imports downloaded CSV bytes (full replace) and records success.
func applyRemoteCSV(source string, body []byte, marker, markerKey string) error {
	res, err := importCSV(db, bytes.NewReader(body))
	if err != nil {
		return err
	}
	metaSet(db, markerKey, marker)
	metaSet(db, "remote_import_last_at", time.Now().UTC().Format(time.RFC3339))
	metaSet(db, "remote_import_last_source", source)
	metaSet(db, "remote_import_last_error", "")
	log.Printf("[remote-import] %s: %d neu, %d aktualisiert (%d gesamt)", source, res.Added, res.Updated, res.Total)
	// Fill metadata/prices for any new cards.
	go startSync(false)
	return nil
}

// ---- Nextcloud / WebDAV ----

func webdavConfigured() bool {
	return os.Getenv("WEBDAV_URL") != ""
}

func checkWebDAV() error {
	rawURL := os.Getenv("WEBDAV_URL")
	user := os.Getenv("WEBDAV_USER")
	pass := os.Getenv("WEBDAV_PASSWORD")
	const markerKey = "remote_import_webdav_tag"
	stored := metaGet(db, markerKey)

	// Cheap change check via HEAD (ETag preferred, else Last-Modified).
	tag := ""
	if req, err := http.NewRequest("HEAD", rawURL, nil); err == nil {
		if user != "" {
			req.SetBasicAuth(user, pass)
		}
		if resp, err := remoteImportClient.Do(req); err == nil {
			resp.Body.Close()
			if resp.StatusCode == http.StatusOK {
				tag = resp.Header.Get("ETag")
				if tag == "" {
					tag = resp.Header.Get("Last-Modified")
				}
			}
		}
	}
	if tag != "" && tag == stored {
		return nil // unchanged, no download needed
	}

	// Download and confirm the change via a content hash (covers servers that
	// don't expose a usable ETag/Last-Modified).
	req, err := http.NewRequest("GET", rawURL, nil)
	if err != nil {
		return err
	}
	if user != "" {
		req.SetBasicAuth(user, pass)
	}
	resp, err := remoteImportClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("HTTP %d beim Download", resp.StatusCode)
	}
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return err
	}

	marker := tag
	if marker == "" {
		marker = "sha256:" + sha256hex(body)
	}
	if marker == stored {
		return nil // content unchanged despite header change
	}
	return applyRemoteCSV("Nextcloud", body, marker, markerKey)
}

// ---- Google Drive (service account, stdlib-only JWT) ----

func gdriveConfigured() bool {
	return os.Getenv("GDRIVE_FILE_ID") != "" && os.Getenv("GDRIVE_SA_JSON") != ""
}

type gdriveSA struct {
	ClientEmail string `json:"client_email"`
	PrivateKey  string `json:"private_key"`
	TokenURI    string `json:"token_uri"`
}

func loadGDriveSA() (*gdriveSA, error) {
	v := strings.TrimSpace(os.Getenv("GDRIVE_SA_JSON"))
	var raw []byte
	if strings.HasPrefix(v, "{") {
		raw = []byte(v) // inline JSON
	} else if b, err := base64.StdEncoding.DecodeString(v); err == nil && bytes.HasPrefix(bytes.TrimSpace(b), []byte("{")) {
		raw = b // base64-encoded inline JSON
	} else {
		f, err := os.ReadFile(v) // path to mounted JSON file
		if err != nil {
			return nil, fmt.Errorf("Service-Account nicht lesbar: %w", err)
		}
		raw = f
	}
	var sa gdriveSA
	if err := json.Unmarshal(raw, &sa); err != nil {
		return nil, fmt.Errorf("Service-Account JSON ungültig: %w", err)
	}
	if sa.ClientEmail == "" || sa.PrivateKey == "" {
		return nil, errors.New("Service-Account JSON unvollständig (client_email/private_key)")
	}
	if sa.TokenURI == "" {
		sa.TokenURI = "https://oauth2.googleapis.com/token"
	}
	return &sa, nil
}

func checkGDrive() error {
	fileID := os.Getenv("GDRIVE_FILE_ID")
	const markerKey = "remote_import_gdrive_tag"
	stored := metaGet(db, markerKey)

	sa, err := loadGDriveSA()
	if err != nil {
		return err
	}
	token, err := gdriveAccessToken(sa)
	if err != nil {
		return err
	}

	// Metadata: md5Checksum is the ideal change marker; fall back to modifiedTime.
	metaURL := "https://www.googleapis.com/drive/v3/files/" + url.PathEscape(fileID) +
		"?fields=md5Checksum,modifiedTime,name&supportsAllDrives=true"
	req, _ := http.NewRequest("GET", metaURL, nil)
	req.Header.Set("Authorization", "Bearer "+token)
	resp, err := remoteImportClient.Do(req)
	if err != nil {
		return err
	}
	metaBody, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("Metadaten HTTP %d: %s", resp.StatusCode, strings.TrimSpace(string(metaBody)))
	}
	var meta struct {
		Md5Checksum  string `json:"md5Checksum"`
		ModifiedTime string `json:"modifiedTime"`
	}
	_ = json.Unmarshal(metaBody, &meta)
	marker := meta.Md5Checksum
	if marker == "" {
		marker = meta.ModifiedTime
	}
	if marker != "" && marker == stored {
		return nil // unchanged
	}

	// Download file content.
	dlURL := "https://www.googleapis.com/drive/v3/files/" + url.PathEscape(fileID) +
		"?alt=media&supportsAllDrives=true"
	dreq, _ := http.NewRequest("GET", dlURL, nil)
	dreq.Header.Set("Authorization", "Bearer "+token)
	dresp, err := remoteImportClient.Do(dreq)
	if err != nil {
		return err
	}
	defer dresp.Body.Close()
	if dresp.StatusCode != http.StatusOK {
		b, _ := io.ReadAll(dresp.Body)
		return fmt.Errorf("Download HTTP %d: %s", dresp.StatusCode, strings.TrimSpace(string(b)))
	}
	body, err := io.ReadAll(dresp.Body)
	if err != nil {
		return err
	}
	if marker == "" {
		marker = "sha256:" + sha256hex(body)
	}
	if marker == stored {
		return nil
	}
	return applyRemoteCSV("Google Drive", body, marker, markerKey)
}

// gdriveAccessToken builds and signs a service-account JWT and exchanges it for a
// short-lived OAuth access token with read-only Drive scope.
func gdriveAccessToken(sa *gdriveSA) (string, error) {
	key, err := parseRSAPrivateKey(sa.PrivateKey)
	if err != nil {
		return "", err
	}
	now := time.Now()
	header := base64url(`{"alg":"RS256","typ":"JWT"}`)
	claims := fmt.Sprintf(
		`{"iss":%q,"scope":"https://www.googleapis.com/auth/drive.readonly","aud":%q,"iat":%d,"exp":%d}`,
		sa.ClientEmail, sa.TokenURI, now.Unix(), now.Add(time.Hour).Unix())
	signingInput := header + "." + base64url(claims)

	h := sha256.Sum256([]byte(signingInput))
	sig, err := rsa.SignPKCS1v15(rand.Reader, key, crypto.SHA256, h[:])
	if err != nil {
		return "", err
	}
	jwt := signingInput + "." + base64.RawURLEncoding.EncodeToString(sig)

	form := url.Values{}
	form.Set("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
	form.Set("assertion", jwt)
	resp, err := remoteImportClient.PostForm(sa.TokenURI, form)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("Token HTTP %d: %s", resp.StatusCode, strings.TrimSpace(string(b)))
	}
	var tok struct {
		AccessToken string `json:"access_token"`
	}
	if err := json.Unmarshal(b, &tok); err != nil || tok.AccessToken == "" {
		return "", fmt.Errorf("Token-Antwort ungültig: %s", strings.TrimSpace(string(b)))
	}
	return tok.AccessToken, nil
}

func parseRSAPrivateKey(pemStr string) (*rsa.PrivateKey, error) {
	// Env vars often carry the key with literal "\n" instead of real newlines.
	pemStr = strings.ReplaceAll(pemStr, `\n`, "\n")
	block, _ := pem.Decode([]byte(pemStr))
	if block == nil {
		return nil, errors.New("private_key: kein gültiger PEM-Block")
	}
	if k, err := x509.ParsePKCS8PrivateKey(block.Bytes); err == nil {
		if rk, ok := k.(*rsa.PrivateKey); ok {
			return rk, nil
		}
		return nil, errors.New("private_key: kein RSA-Schlüssel")
	}
	return x509.ParsePKCS1PrivateKey(block.Bytes)
}

// ---- helpers ----

func base64url(s string) string {
	return base64.RawURLEncoding.EncodeToString([]byte(s))
}

func sha256hex(b []byte) string {
	h := sha256.Sum256(b)
	return fmt.Sprintf("%x", h[:])
}
