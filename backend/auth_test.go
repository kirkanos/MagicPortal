package main

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func withPassword(t *testing.T, pw string) {
	t.Helper()
	oldPW, oldAllow := uploadPassword, allowNoPassword
	uploadPassword, allowNoPassword = pw, false
	authMu.Lock()
	authFails = map[string]*authBucket{}
	authMu.Unlock()
	t.Cleanup(func() { uploadPassword, allowNoPassword = oldPW, oldAllow })
}

func authReq(method string, header ...string) *http.Request {
	r := httptest.NewRequest(method, "http://mtg.example/api/x", nil)
	for i := 0; i+1 < len(header); i += 2 {
		r.Header.Set(header[i], header[i+1])
	}
	return r
}

func TestNoPasswordLocksWritesUnlessAllowed(t *testing.T) {
	withPassword(t, "")
	if authorized(authReq("POST")) {
		t.Error("open without ALLOW_NO_PASSWORD")
	}
	allowNoPassword = true
	if !authorized(authReq("POST")) {
		t.Error("closed with ALLOW_NO_PASSWORD")
	}
}

func TestWrongPasswordsAreThrottledPerClient(t *testing.T) {
	withPassword(t, "secret")
	for i := 0; i < authBurst; i++ {
		if authorized(authReq("POST", "X-Upload-Password", "guess", "X-Real-IP", "203.0.113.9")) {
			t.Fatal("wrong password accepted")
		}
	}
	// Now even the right one is refused for this client ...
	if authorized(authReq("POST", "X-Upload-Password", "secret", "X-Real-IP", "203.0.113.9")) {
		t.Error("throttled client got in")
	}
	rec := httptest.NewRecorder()
	handleAuthCheck(rec, authReq("GET", "X-Upload-Password", "secret", "X-Real-IP", "203.0.113.9"))
	if rec.Code != http.StatusTooManyRequests {
		t.Errorf("auth-check while throttled: %d", rec.Code)
	}
	// ... but not for anybody else.
	if !authorized(authReq("POST", "X-Upload-Password", "secret", "X-Real-IP", "198.51.100.4")) {
		t.Error("other client throttled too")
	}
	// And the bucket refills.
	if authThrottled("203.0.113.9", time.Now().Add(authRefill+time.Second)) {
		t.Error("no refill")
	}
}

func TestLoginSetsASessionCookie(t *testing.T) {
	withPassword(t, "secret")
	rec := httptest.NewRecorder()
	handleLogin(rec, httptest.NewRequest("POST", "/api/login", strings.NewReader(`{"password":"nope"}`)))
	if rec.Code != 403 || len(rec.Result().Cookies()) != 0 {
		t.Fatalf("wrong password: %d %v", rec.Code, rec.Result().Cookies())
	}
	rec = httptest.NewRecorder()
	handleLogin(rec, httptest.NewRequest("POST", "/api/login", strings.NewReader(`{"password":"secret"}`)))
	cookies := rec.Result().Cookies()
	if rec.Code != 204 || len(cookies) != 1 {
		t.Fatalf("login: %d %v", rec.Code, cookies)
	}
	c := cookies[0]
	if !c.HttpOnly || c.SameSite != http.SameSiteStrictMode || strings.Contains(c.Value, "secret") {
		t.Errorf("cookie: %+v", c)
	}

	r := authReq("POST", "Origin", "http://mtg.example:8088")
	r.AddCookie(c)
	if !authorized(r) {
		t.Error("cookie not accepted")
	}
	r = authReq("POST", "Origin", "https://evil.example")
	r.AddCookie(c)
	if authorized(r) {
		t.Error("cookie accepted from a foreign origin")
	}
	r = authReq("GET")
	r.AddCookie(&http.Cookie{Name: sessionCookie, Value: c.Value[:len(c.Value)-1] + "0"})
	if authorized(r) {
		t.Error("forged cookie accepted")
	}

	// A new password ends every session.
	uploadPassword = "other"
	r = authReq("GET")
	r.AddCookie(c)
	if authorized(r) {
		t.Error("session survived a password change")
	}
}

func TestSessionsExpire(t *testing.T) {
	withPassword(t, "secret")
	v := newSession(time.Now())
	if !validSession(v, time.Now()) || validSession(v, time.Now().Add(sessionTTL+time.Hour)) {
		t.Error("expiry")
	}
}

func TestStatusHidesErrorsWhenLocked(t *testing.T) {
	newTestDB(t)
	withPassword(t, "secret")
	metaSet(db, "remote_import_last_error", "PROPFIND https://cloud.example/remote.php/dav/files/me: 401")
	rec := httptest.NewRecorder()
	handleStatus(rec, httptest.NewRequest("GET", "/api/status", nil))
	if strings.Contains(rec.Body.String(), "cloud.example") || !strings.Contains(rec.Body.String(), hiddenError) {
		t.Errorf("locked: %s", rec.Body.String())
	}
	rec = httptest.NewRecorder()
	handleStatus(rec, authReq("GET", "X-Upload-Password", "secret"))
	if !strings.Contains(rec.Body.String(), "cloud.example") {
		t.Errorf("unlocked: %s", rec.Body.String())
	}
}

func TestUnknownMethodsStayOutOfTheMetrics(t *testing.T) {
	h := instrument(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { t.Error("reached the mux") }))
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest("FOOBAR", "/api/status", nil))
	if rec.Code != http.StatusMethodNotAllowed {
		t.Errorf("status %d", rec.Code)
	}
	for _, v := range metricHTTPRequests.snapshot() {
		if strings.Contains(v.labels, "FOOBAR") {
			t.Errorf("label: %s", v.labels)
		}
	}
}
