/* Shared utilities: API access, common helpers, modal, upload UI.
   Data (collection, sets, prices) now comes from the backend API; the browser
   no longer parses CSV or calls Scryfall directly. */

const API = 'api';

/* Full collection, already enriched with Scryfall metadata/prices by the backend.
   Shape per card matches what the pages expect (top-level fields + `scryfall`). */
async function loadCollection() {
  const res = await fetch(API + '/collection', { cache: 'no-store' });
  if (!res.ok) throw new Error(t('Sammlung konnte nicht geladen werden', 'Could not load collection') + ' (HTTP ' + res.status + ')');
  return res.json();
}

/* Set index: { code(lowercase): {name, cardCount, iconSvgUri, releasedAt, setType, digital} } */
async function loadSetIndex() {
  const res = await fetch(API + '/sets', { cache: 'no-store' });
  if (!res.ok) return {};
  return res.json();
}

/* Official subtypes (union of Scryfall's catalogs) for filtering out joke subtypes. */
async function loadSubtypes() {
  const res = await fetch(API + '/subtypes', { cache: 'no-store' });
  if (!res.ok) return [];
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
  if (res.status === 403) throw new Error(t('Falsches oder fehlendes Passwort', 'Wrong or missing password'));
  if (!res.ok) throw new Error(t('Upload fehlgeschlagen', 'Upload failed') + ' (HTTP ' + res.status + ')');
  return res.json();
}

/* Clears the collection and re-imports the bundled sample. */
async function resetCollectionCSV() {
  const res = await fetch(API + '/reset', { method: 'POST', headers: uploadHeaders() });
  if (res.status === 403) throw new Error(t('Falsches oder fehlendes Passwort', 'Wrong or missing password'));
  if (!res.ok) throw new Error(t('Leeren fehlgeschlagen', 'Clearing failed') + ' (HTTP ' + res.status + ')');
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

const CONDITION_LABEL = {
  mint: 'Mint',
  near_mint: 'Near Mint',
  excellent: 'Excellent',
  good: 'Good',
  light_played: 'Lightly Played',
  lightly_played: 'Lightly Played',
  played: 'Played',
  moderately_played: 'Moderately Played',
  heavily_played: 'Heavily Played',
  poor: 'Poor',
  damaged: 'Damaged',
};

function conditionLabel(c) {
  if (!c) return '–';
  const k = String(c).toLowerCase();
  return CONDITION_LABEL[k] || String(c).replace(/_/g, ' ').replace(/\b\w/g, (m) => m.toUpperCase());
}

/* Renders a single mana/text symbol token (without braces) as a Scryfall SVG. */
function manaSymbolImg(token) {
  const code = token.toUpperCase().replace(/\//g, '');
  return `<img class="mana-symbol" src="https://svgs.scryfall.io/card-symbols/${encodeURIComponent(code)}.svg" alt="{${escapeHTML(token)}}" title="{${escapeHTML(token)}}" loading="lazy">`;
}

/* A mana cost string like "{2}{R}{R}" → row of symbol images. */
function renderManaCost(mana) {
  if (!mana) return '';
  return (String(mana).match(/\{[^}]+\}/g) || []).map((tok) => manaSymbolImg(tok.slice(1, -1))).join('');
}

/* Oracle text with inline {…} symbols rendered and line breaks preserved. */
function renderOracleText(text) {
  if (!text) return '';
  return String(text)
    .split(/(\{[^}]+\})/g)
    .map((part) => {
      const m = part.match(/^\{([^}]+)\}$/);
      return m ? manaSymbolImg(m[1]) : escapeHTML(part).replace(/\n/g, '<br>');
    })
    .join('');
}

/* Detail modal for a grouped card: one row per language/finish/condition variant
   with its condition, price, quantity and date added. */
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
          <td>${escapeHTML(conditionLabel(v.condition))}</td>
          <td>${escapeHTML(v.binderName || '–')}</td>
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
        ${rep.scryfall && rep.scryfall.manaCost ? `<div class="mana-cost">${renderManaCost(rep.scryfall.manaCost)}</div>` : ''}
        <p class="modal-type">${escapeHTML((rep.scryfall && rep.scryfall.typeLine) || '')}</p>
        ${rep.scryfall && rep.scryfall.oracleText ? `<div class="oracle-text">${renderOracleText(rep.scryfall.oracleText)}</div>` : ''}
        <dl>
          <dt>Set</dt><dd>${escapeHTML(group.setName)} (${escapeHTML(group.setCode)} #${escapeHTML(group.collectorNumber)})</dd>
          <dt>${t('Rarität', 'Rarity')}</dt><dd>${escapeHTML(rep.rarity)}</dd>
          <dt>${t('Exemplare gesamt', 'Copies total')}</dt><dd>${group.totalQty}</dd>
          <dt>${t('Marktwert gesamt', 'Total market value')}</dt><dd>${formatCurrency(totalMarket, 'EUR')}</dd>
        </dl>
        <h3 class="lang-table-title">${t('Sprachen in deiner Sammlung', 'Languages in your collection')}</h3>
        <table class="lang-table">
          <thead><tr><th>${t('Sprache', 'Language')}</th><th>${t('Zustand', 'Condition')}</th><th>${t('Ordner', 'Folder')}</th><th class="num">${t('Preis (Scryfall)', 'Price (Scryfall)')}</th><th class="num">${t('Anzahl', 'Quantity')}</th><th>${t('Hinzugefügt', 'Added')}</th></tr></thead>
          <tbody>${rows}</tbody>
        </table>
        <h3 class="lang-table-title">${t('Weitere Editionen dieser Karte', 'Other printings of this card')}</h3>
        <div class="modal-variants" id="modal-variants">${t('Lade Varianten…', 'Loading printings…')}</div>
      </div>
    </div>
  `;
}

/* Loads all printings of the card (across editions) and fills #modal-variants. */
async function loadCardVariants(group) {
  const el = document.getElementById('modal-variants');
  if (!el) return;
  const name = (group.rep.scryfall && group.rep.scryfall.name) || group.name;
  let prints = [];
  try {
    const res = await fetch(API + '/prints?name=' + encodeURIComponent(name), { cache: 'no-store' });
    if (res.ok) prints = await res.json();
  } catch (e) {
    /* ignore */
  }
  // Drop the printing that is currently open – only OTHER editions are listed.
  const others = prints.filter(
    (p) =>
      !(
        (p.setCode || '').toLowerCase() === (group.setCode || '').toLowerCase() &&
        String(p.collectorNumber) === String(group.collectorNumber)
      )
  );
  if (!others.length) {
    el.textContent = t('Keine weiteren Editionen gefunden.', 'No other printings found.');
    return;
  }
  el.innerHTML = others
    .map((p) => {
      const price =
        p.priceEur != null
          ? formatCurrency(p.priceEur, 'EUR')
          : p.priceEurFoil != null
          ? formatCurrency(p.priceEurFoil, 'EUR') + ' (Foil)'
          : '–';
      const img = p.imageSmall || p.image;
      const icon = p.iconSvgUri
        ? `<img class="variant-icon" src="${escapeHTML(p.iconSvgUri)}" alt="" loading="lazy">`
        : '';
      return `
        <div class="variant" title="${escapeHTML(p.setName || p.setCode)} #${escapeHTML(p.collectorNumber)}">
          <div class="variant-thumb">${img ? `<img loading="lazy" src="${img}" alt="">` : ''}</div>
          <div class="variant-set">${icon}<span>${escapeHTML(p.setName || p.setCode)}</span></div>
          <div class="variant-price">${price}</div>
        </div>`;
    })
    .join('');
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
  if (!iso) return t('noch nie', 'never');
  const ms = new Date(iso).getTime();
  if (isNaN(ms)) return t('unbekannt', 'unknown');
  const s = Math.max(0, (Date.now() - ms) / 1000);
  if (s < 60) return t('gerade eben', 'just now');
  const m = Math.floor(s / 60);
  if (m < 60) return t(`vor ${m} Min.`, `${m} min ago`);
  const h = Math.floor(m / 60);
  if (h < 24) return t(`vor ${h} Std.`, `${h} h ago`);
  const d = Math.floor(h / 24);
  return t(`vor ${d} Tag${d > 1 ? 'en' : ''}`, `${d} day${d > 1 ? 's' : ''} ago`);
}

let syncStatusTimer = null;

async function fetchSyncStatus() {
  const res = await fetch(API + '/status', { cache: 'no-store' });
  if (!res.ok) throw new Error(t('Status nicht verfügbar', 'Status unavailable'));
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
    el.innerHTML = '<span class="sync-spin">⟳</span> ' + t('Aktualisiere Kartendaten…', 'Updating card data…');
    el.classList.add('busy');
    clearTimeout(syncStatusTimer);
    syncStatusTimer = setTimeout(refreshSyncStatus, 3000);
  } else {
    el.classList.remove('busy');
    const label = t('Kartendaten: ', 'Card data: ') + relTime(st.cardsSyncedAt);
    el.textContent = st.lastError ? label + t(' (Fehler)', ' (error)') : label;
    el.title = st.lastError
      ? t('Letzter Sync-Fehler: ', 'Last sync error: ') + st.lastError
      : t(`Sammlung: ${st.collectionCount} · Karten-DB: ${st.cardCount} · Sets: ${st.setCount}`,
          `Collection: ${st.collectionCount} · Card DB: ${st.cardCount} · Sets: ${st.setCount}`);
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
          alert(t('Falsches oder fehlendes Passwort', 'Wrong or missing password'));
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
        alert(t('Aktualisierung fehlgeschlagen: ', 'Refresh failed: ') + e.message);
      } finally {
        syncBtn.disabled = false;
      }
    });
  }

  uploadInput.addEventListener('change', async () => {
    const file = uploadInput.files[0];
    if (!file) return;
    showLoading(t(`Lade „${file.name}" hoch...`, `Uploading “${file.name}”...`));
    try {
      await uploadCollectionCSV(file);
      location.reload();
    } catch (e) {
      hideLoading();
      uploadInput.value = '';
      alert(t('CSV-Upload fehlgeschlagen: ', 'CSV upload failed: ') + e.message);
    }
  });

  resetBtn.addEventListener('click', async () => {
    if (!confirm(t('Gesamte Sammlung leeren? Danach kannst du eine neue CSV hochladen.', 'Clear the entire collection? You can upload a new CSV afterwards.'))) return;
    showLoading(t('Leere Sammlung...', 'Clearing collection...'));
    try {
      await resetCollectionCSV();
      location.reload();
    } catch (e) {
      hideLoading();
      alert(t('Leeren fehlgeschlagen: ', 'Clearing failed: ') + e.message);
    }
  });

  unlockBtn.addEventListener('click', async () => {
    const pw = prompt(t('Passwort für den CSV-Upload:', 'Password for CSV upload:'));
    if (pw == null || pw === '') return;
    try {
      if (await checkUploadAuth(pw)) {
        uploadPassword = pw;
        sessionStorage.setItem(UPLOAD_PW_KEY, pw);
        setUnlocked(true);
      } else {
        alert(t('Falsches Passwort.', 'Wrong password.'));
      }
    } catch (e) {
      alert(t('Passwortprüfung fehlgeschlagen: ', 'Password check failed: ') + e.message);
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
