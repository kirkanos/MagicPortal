/* Shared utilities: API access, common helpers, modal, upload UI.
   Data (collection, sets, prices) now comes from the backend API; the browser
   no longer parses CSV or calls Scryfall directly. */

const API = 'api';

/* Full collection, already enriched with Scryfall metadata/prices by the backend.
   Shape per card matches what the pages expect (top-level fields + `scryfall`). */
async function loadCollection() {
  const res = await fetch(API + '/collection', { cache: 'no-store' });
  if (!res.ok) throw new Error('Sammlung konnte nicht geladen werden (HTTP ' + res.status + ')');
  return res.json();
}

/* Set index: { code(lowercase): {name, cardCount, iconSvgUri, releasedAt, setType, digital} } */
async function loadSetIndex() {
  const res = await fetch(API + '/sets', { cache: 'no-store' });
  if (!res.ok) return {};
  return res.json();
}

const setCardsCache = {};

/* All cards of a set (owned or not), for the editions overlay. */
async function fetchSetCards(code) {
  const key = String(code).toLowerCase();
  if (setCardsCache[key]) return setCardsCache[key];
  const res = await fetch(API + '/sets/' + encodeURIComponent(key) + '/cards', { cache: 'no-store' });
  if (!res.ok) return [];
  const cards = await res.json();
  setCardsCache[key] = cards;
  return cards;
}

/* ---- Upload / reset with password (custom header, no browser auth dialog) ---- */

const UPLOAD_PW_KEY = 'mtg_upload_pw';
let uploadPassword = sessionStorage.getItem(UPLOAD_PW_KEY) || null;

function uploadHeaders(extra) {
  const headers = Object.assign({}, extra || {});
  if (uploadPassword != null) headers['X-Upload-Password'] = uploadPassword;
  return headers;
}

/* Verifies the upload password against the server. Pass a password to test it,
   or null to probe whether a password is required. true = access granted (204). */
async function checkUploadAuth(pw) {
  const headers = pw != null ? { 'X-Upload-Password': pw } : {};
  const res = await fetch(API + '/auth-check', { headers, cache: 'no-store' });
  return res.ok;
}

/* Uploads a CSV file; the backend upserts (adds new, updates existing) cards. */
async function uploadCollectionCSV(file) {
  const res = await fetch(API + '/upload', { method: 'POST', headers: uploadHeaders({ 'Content-Type': 'text/csv' }), body: file });
  if (res.status === 403) throw new Error('Falsches oder fehlendes Passwort');
  if (!res.ok) throw new Error('Upload fehlgeschlagen (HTTP ' + res.status + ')');
  return res.json();
}

/* Clears the collection and re-imports the bundled sample. */
async function resetCollectionCSV() {
  const res = await fetch(API + '/reset', { method: 'POST', headers: uploadHeaders() });
  if (res.status === 403) throw new Error('Falsches oder fehlendes Passwort');
  if (!res.ok) throw new Error('Zurücksetzen fehlgeschlagen (HTTP ' + res.status + ')');
}

function cardImage(card, size) {
  if (card.scryfall && card.scryfall.image) {
    return size === 'small' && card.scryfall.imageSmall ? card.scryfall.imageSmall : card.scryfall.image;
  }
  return '';
}

const LANGUAGE_FLAG = {
  en: '🇬🇧', de: '🇩🇪', fr: '🇫🇷', it: '🇮🇹', es: '🇪🇸', pt: '🇵🇹',
  ja: '🇯🇵', ko: '🇰🇷', ru: '🇷🇺', zhs: '🇨🇳', zht: '🇹🇼',
};

function languageFlag(lang) {
  return LANGUAGE_FLAG[String(lang || '').toLowerCase()] || '';
}

function formatCurrency(value, currency) {
  if (value === null || value === undefined || isNaN(value)) return '-';
  return new Intl.NumberFormat('de-DE', { style: 'currency', currency: currency || 'EUR' }).format(value);
}

function debounce(fn, wait) {
  let t;
  return (...args) => {
    clearTimeout(t);
    t = setTimeout(() => fn(...args), wait);
  };
}

function uniqueSorted(values) {
  return [...new Set(values.filter(Boolean))].sort((a, b) => a.localeCompare(b));
}

function showLoading(message) {
  const el = document.getElementById('loading-overlay');
  if (!el) return;
  el.style.display = 'flex';
  const text = el.querySelector('.loading-text');
  if (text) text.textContent = message || 'Lade Sammlung...';
}

function updateLoadingProgress() {
  /* Progress bar no longer used (enrichment happens server-side). Kept as a
     no-op so existing callers of loadCollection(updateLoadingProgress) work. */
}

function hideLoading() {
  const el = document.getElementById('loading-overlay');
  if (el) el.style.display = 'none';
}

/* Detail modal for a grouped card: one row per language/finish variant with
   its price, quantity and date added. */
function cardGroupModalHTML(group) {
  const rep = group.rep;
  const img = cardImage(rep, 'normal');
  const variants = group.variants
    .slice()
    .sort((a, b) => (a.language || '').localeCompare(b.language || '') || a.foil.localeCompare(b.foil));

  const rows = variants
    .map((v) => {
      const price = v.scryfall && v.scryfall.priceEur ? formatCurrency(parseFloat(v.scryfall.priceEur), 'EUR') : '–';
      const foilTag = v.foil && v.foil !== 'normal' ? ` <span class="lang-foil">${escapeHTML(v.foil)}</span>` : '';
      return `
        <tr>
          <td>${languageFlag(v.language)} ${escapeHTML((v.language || '?').toUpperCase())}${foilTag}</td>
          <td class="num">${price}</td>
          <td class="num">${v.quantity}</td>
          <td>${escapeHTML((v.added || '').slice(0, 10)) || '–'}</td>
        </tr>`;
    })
    .join('');

  const totalMarket = variants.reduce(
    (a, v) => a + (v.scryfall && v.scryfall.priceEur ? parseFloat(v.scryfall.priceEur) : 0) * v.quantity,
    0
  );

  return `
    <div class="modal-card">
      <div class="modal-image">${img ? `<img src="${img}" alt="${escapeHTML(group.name)}">` : '<div class="no-image">Kein Bild</div>'}</div>
      <div class="modal-details">
        <h2>${escapeHTML(group.name)}</h2>
        <p class="modal-type">${escapeHTML((rep.scryfall && rep.scryfall.typeLine) || '')}</p>
        <dl>
          <dt>Set</dt><dd>${escapeHTML(group.setName)} (${escapeHTML(group.setCode)} #${escapeHTML(group.collectorNumber)})</dd>
          <dt>Rarität</dt><dd>${escapeHTML(rep.rarity)}</dd>
          <dt>Exemplare gesamt</dt><dd>${group.totalQty}</dd>
          <dt>Marktwert gesamt</dt><dd>${formatCurrency(totalMarket, 'EUR')}</dd>
        </dl>
        <h3 class="lang-table-title">Sprachen in deiner Sammlung</h3>
        <table class="lang-table">
          <thead><tr><th>Sprache</th><th class="num">Preis (Scryfall)</th><th class="num">Anzahl</th><th>Hinzugefügt</th></tr></thead>
          <tbody>${rows}</tbody>
        </table>
      </div>
    </div>
  `;
}

function escapeHTML(str) {
  return String(str || '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function initModal() {
  const overlay = document.getElementById('card-modal');
  if (!overlay) return;
  overlay.addEventListener('click', (e) => {
    if (e.target === overlay || e.target.classList.contains('modal-close')) {
      overlay.classList.remove('open');
    }
  });
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') overlay.classList.remove('open');
  });
}

function openCardModal(html) {
  const overlay = document.getElementById('card-modal');
  if (!overlay) return;
  overlay.querySelector('.modal-body').innerHTML = '<span class="modal-close">&times;</span>' + html;
  overlay.classList.add('open');
}

/* ---- Sync status + manual refresh ---- */

function relTime(iso) {
  if (!iso) return 'noch nie';
  const t = new Date(iso).getTime();
  if (isNaN(t)) return 'unbekannt';
  const s = Math.max(0, (Date.now() - t) / 1000);
  if (s < 60) return 'gerade eben';
  const m = Math.floor(s / 60);
  if (m < 60) return `vor ${m} Min.`;
  const h = Math.floor(m / 60);
  if (h < 24) return `vor ${h} Std.`;
  const d = Math.floor(h / 24);
  return `vor ${d} Tag${d > 1 ? 'en' : ''}`;
}

let syncStatusTimer = null;

async function fetchSyncStatus() {
  const res = await fetch(API + '/status', { cache: 'no-store' });
  if (!res.ok) throw new Error('Status nicht verfügbar');
  return res.json();
}

/* Updates the status indicator; while a sync runs, polls faster. */
async function refreshSyncStatus() {
  const el = document.getElementById('sync-status');
  if (!el) return;
  let st;
  try {
    st = await fetchSyncStatus();
  } catch (e) {
    el.textContent = '';
    return;
  }
  if (st.running) {
    el.innerHTML = '<span class="sync-spin">⟳</span> Aktualisiere Kartendaten…';
    el.classList.add('busy');
    clearTimeout(syncStatusTimer);
    syncStatusTimer = setTimeout(refreshSyncStatus, 3000);
  } else {
    el.classList.remove('busy');
    const label = 'Kartendaten: ' + relTime(st.cardsSyncedAt);
    el.textContent = st.lastError ? label + ' (Fehler)' : label;
    el.title = st.lastError
      ? 'Letzter Sync-Fehler: ' + st.lastError
      : `Sammlung: ${st.collectionCount} · Karten-DB: ${st.cardCount} · Sets: ${st.setCount}`;
  }
  return st;
}

/* Wires the CSV upload/reset/unlock buttons and the sync controls in the top
   navigation (present on every page). The upload/reset/sync buttons are only
   revealed once access is granted - either because no password is configured,
   or after the correct password has been entered. */
function initCollectionUpload() {
  const uploadInput = document.getElementById('csv-upload');
  const uploadLabel = document.getElementById('csv-upload-label');
  const resetBtn = document.getElementById('csv-reset');
  const unlockBtn = document.getElementById('csv-unlock');
  const syncBtn = document.getElementById('csv-sync');
  if (!uploadInput || !uploadLabel || !resetBtn || !unlockBtn) return;

  // Status indicator is shown to everyone; refresh on load.
  refreshSyncStatus();

  function setUnlocked(unlocked) {
    uploadLabel.style.display = unlocked ? '' : 'none';
    resetBtn.style.display = unlocked ? '' : 'none';
    if (syncBtn) syncBtn.style.display = unlocked ? '' : 'none';
    unlockBtn.style.display = unlocked ? 'none' : '';
  }

  if (syncBtn) {
    syncBtn.addEventListener('click', async () => {
      syncBtn.disabled = true;
      try {
        const res = await fetch(API + '/sync', { method: 'POST', headers: uploadHeaders() });
        if (res.status === 403) {
          alert('Falsches oder fehlendes Passwort');
          return;
        }
        // Poll until the sync finishes, then reload to show fresh data.
        await refreshSyncStatus();
        const wait = async () => {
          const st = await refreshSyncStatus();
          if (st && st.running) {
            setTimeout(wait, 3000);
          } else {
            location.reload();
          }
        };
        setTimeout(wait, 3000);
      } catch (e) {
        alert('Aktualisierung fehlgeschlagen: ' + e.message);
      } finally {
        syncBtn.disabled = false;
      }
    });
  }

  uploadInput.addEventListener('change', async () => {
    const file = uploadInput.files[0];
    if (!file) return;
    showLoading(`Lade „${file.name}" hoch...`);
    try {
      await uploadCollectionCSV(file);
      location.reload();
    } catch (e) {
      hideLoading();
      uploadInput.value = '';
      alert('CSV-Upload fehlgeschlagen: ' + e.message);
    }
  });

  resetBtn.addEventListener('click', async () => {
    if (!confirm('Gesamte Sammlung leeren? Danach kannst du eine neue CSV hochladen.')) return;
    showLoading('Setze Sammlung zurück...');
    try {
      await resetCollectionCSV();
      location.reload();
    } catch (e) {
      hideLoading();
      alert('Zurücksetzen fehlgeschlagen: ' + e.message);
    }
  });

  unlockBtn.addEventListener('click', async () => {
    const pw = prompt('Passwort für den CSV-Upload:');
    if (pw == null || pw === '') return;
    try {
      if (await checkUploadAuth(pw)) {
        uploadPassword = pw;
        sessionStorage.setItem(UPLOAD_PW_KEY, pw);
        setUnlocked(true);
      } else {
        alert('Falsches Passwort.');
      }
    } catch (e) {
      alert('Passwortprüfung fehlgeschlagen: ' + e.message);
    }
  });

  // Determine initial state: no password configured, already-unlocked session, or locked.
  (async () => {
    try {
      if (await checkUploadAuth(null)) {
        uploadPassword = null;
        setUnlocked(true);
      } else if (uploadPassword && (await checkUploadAuth(uploadPassword))) {
        setUnlocked(true);
      } else {
        sessionStorage.removeItem(UPLOAD_PW_KEY);
        uploadPassword = null;
        setUnlocked(false);
      }
    } catch (e) {
      setUnlocked(false);
    }
  })();
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', initCollectionUpload);
} else {
  initCollectionUpload();
}
