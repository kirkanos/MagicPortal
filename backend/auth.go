package main

import (
	"crypto/hmac"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Access control. There is one shared secret, UPLOAD_PASSWORD. Two ways to
// present it:
//
//   - the header X-Upload-Password on every request (the Android app, scripts)
//   - a session cookie from POST /api/login (the web interface), HttpOnly, so
//     no script in the page - and no injected one - can read it. The cookie
//     carries no password, only an expiry and an HMAC keyed with the password:
//     it survives a restart, and changing the password invalidates every
//     session at once.
//
// Wrong passwords are throttled per client: a few attempts, then one per
// authRefill. Without a password the API refuses every write, unless
// ALLOW_NO_PASSWORD=true says that is intended (a stack bound to localhost).

const (
	sessionCookie = "mtg_session"
	sessionTTL    = 365 * 24 * time.Hour
	authBurst     = 5
	authRefill    = 30 * time.Second
	authMaxKeys   = 10000
)

var allowNoPassword bool

// passwordOK compares in constant time. Hashing first gives both sides the
// same length, so not even the length of the password leaks.
func passwordOK(pw string) bool {
	a := sha256.Sum256([]byte(pw))
	b := sha256.Sum256([]byte(uploadPassword))
	return subtle.ConstantTimeCompare(a[:], b[:]) == 1
}

func authorized(r *http.Request) bool {
	if uploadPassword == "" {
		return allowNoPassword
	}
	if c, err := r.Cookie(sessionCookie); err == nil && sameOriginWrite(r) && validSession(c.Value, time.Now()) {
		return true
	}
	pw := r.Header.Get("X-Upload-Password")
	if pw == "" {
		return false
	}
	key := clientKey(r)
	if authThrottled(key, time.Now()) {
		return false
	}
	if passwordOK(pw) {
		return true
	}
	authFailed(key, time.Now())
	return false
}

// sameOriginWrite: a cookie is sent by the browser on its own, so a write
// from another page (even one on a sibling subdomain, which SameSite still
// counts as the same site) must not ride on it. Browsers send Origin with
// every non-GET fetch; when it is there, its host has to be ours.
func sameOriginWrite(r *http.Request) bool {
	if r.Method == http.MethodGet || r.Method == http.MethodHead {
		return true
	}
	o := r.Header.Get("Origin")
	if o == "" {
		return true
	}
	u, err := url.Parse(o)
	if err != nil {
		return false
	}
	return strings.EqualFold(u.Hostname(), hostOnly(r.Host))
}

func hostOnly(h string) string {
	if host, _, err := net.SplitHostPort(h); err == nil {
		return host
	}
	return h
}

// ---- sessions ----

func sessionMAC(exp string) string {
	key := sha256.Sum256([]byte("mtg-portal-session|" + uploadPassword))
	m := hmac.New(sha256.New, key[:])
	m.Write([]byte(exp))
	return hex.EncodeToString(m.Sum(nil))
}

func newSession(now time.Time) string {
	exp := strconv.FormatInt(now.Add(sessionTTL).Unix(), 10)
	return exp + "." + sessionMAC(exp)
}

func validSession(v string, now time.Time) bool {
	exp, mac, ok := strings.Cut(v, ".")
	if !ok {
		return false
	}
	n, err := strconv.ParseInt(exp, 10, 64)
	if err != nil || now.Unix() > n {
		return false
	}
	return hmac.Equal([]byte(mac), []byte(sessionMAC(exp)))
}

func setSessionCookie(w http.ResponseWriter, r *http.Request, value string, maxAge int) {
	http.SetCookie(w, &http.Cookie{
		Name:     sessionCookie,
		Value:    value,
		Path:     "/",
		MaxAge:   maxAge,
		HttpOnly: true,
		// Behind Traefik the request arrives as plain HTTP; the original
		// scheme is in X-Forwarded-Proto. Locally (http://localhost) a
		// Secure cookie would never come back.
		Secure:   r.TLS != nil || r.Header.Get("X-Forwarded-Proto") == "https",
		SameSite: http.SameSiteStrictMode,
	})
}

// POST /api/login {password}: sets the session cookie. 204, 403 for a wrong
// password, 429 while the client is throttled.
func handleLogin(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Password string `json:"password"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096)).Decode(&in); err != nil {
		http.Error(w, "invalid JSON", 400)
		return
	}
	if uploadPassword == "" {
		if allowNoPassword {
			w.WriteHeader(204)
		} else {
			http.Error(w, "No password configured", 403)
		}
		return
	}
	key := clientKey(r)
	if authThrottled(key, time.Now()) {
		tooManyAttempts(w)
		return
	}
	if !passwordOK(in.Password) {
		authFailed(key, time.Now())
		http.Error(w, "Wrong password", 403)
		return
	}
	setSessionCookie(w, r, newSession(time.Now()), int(sessionTTL.Seconds()))
	w.WriteHeader(204)
}

// POST /api/logout: drops the session cookie.
func handleLogout(w http.ResponseWriter, r *http.Request) {
	setSessionCookie(w, r, "", -1)
	w.WriteHeader(204)
}

// GET /api/auth-check: 204 when the request is authorised (header, cookie or
// no password needed), 403 otherwise, 429 while throttled.
func handleAuthCheck(w http.ResponseWriter, r *http.Request) {
	if r.Header.Get("X-Upload-Password") != "" && uploadPassword != "" && authThrottled(clientKey(r), time.Now()) {
		tooManyAttempts(w)
		return
	}
	if authorized(r) {
		w.WriteHeader(204)
	} else {
		w.WriteHeader(403)
	}
}

func tooManyAttempts(w http.ResponseWriter) {
	w.Header().Set("Retry-After", strconv.Itoa(int(authRefill.Seconds())))
	http.Error(w, "Too many wrong passwords, try again later", http.StatusTooManyRequests)
}

// ---- throttling ----

// clientKey identifies the client for the throttle. nginx resolves the real
// address (real_ip from Traefik's X-Forwarded-For) and passes it as
// X-Real-IP; without nginx in front it is the peer address.
func clientKey(r *http.Request) string {
	if ip := strings.TrimSpace(r.Header.Get("X-Real-IP")); ip != "" {
		return ip
	}
	return hostOnly(r.RemoteAddr)
}

// A token bucket per client: authBurst wrong passwords, then one more per
// authRefill. Only failures take a token, so the owner never notices it.
type authBucket struct {
	tokens float64
	at     time.Time
}

var (
	authMu    sync.Mutex
	authFails = map[string]*authBucket{}
)

func (b *authBucket) refill(now time.Time) {
	b.tokens += now.Sub(b.at).Seconds() / authRefill.Seconds()
	if b.tokens > authBurst {
		b.tokens = authBurst
	}
	b.at = now
}

func authThrottled(key string, now time.Time) bool {
	authMu.Lock()
	defer authMu.Unlock()
	b := authFails[key]
	if b == nil {
		return false
	}
	b.refill(now)
	return b.tokens < 1
}

func authFailed(key string, now time.Time) {
	authMu.Lock()
	defer authMu.Unlock()
	b := authFails[key]
	if b == nil {
		if len(authFails) >= authMaxKeys {
			// Forget the clients whose bucket is full again.
			for k, o := range authFails {
				if o.refill(now); o.tokens >= authBurst {
					delete(authFails, k)
				}
			}
			if len(authFails) >= authMaxKeys {
				return
			}
		}
		b = &authBucket{tokens: authBurst, at: now}
		authFails[key] = b
	}
	b.refill(now)
	b.tokens--
	metricAuthFailures.Add(1)
}
