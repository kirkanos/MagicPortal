/* Shared utilities: API access, common helpers, modal, upload UI.
   Data (collection, sets, prices) now comes from the backend API; the browser
   no longer parses CSV or calls Scryfall directly. */

const API = 'api';

/* Full collection, already enriched with Scryfall metadata/prices by the backend.
   Shape per card matches what the pages expect (top-level fields + `scryfall`). */
async function loadCollection() {
  await uiConfigReady; // decides whether images come from the mirror or Scryfall
  // 'no-cache' rather than 'no-store': the browser keeps the response and
  // revalidates with If-None-Match. Unchanged, the backend answers 304 with no
  // body - with a collection of several MB that is the difference between
  // seconds and instant.
  const res = await fetch(API + '/collection', { cache: 'no-cache' });
  if (!res.ok) throw new Error(t('Sammlung konnte nicht geladen werden', 'Could not load collection') + ' (HTTP ' + res.status + ')');
  return res.json();
}

/* Ready-made figures for the statistics page. The backend aggregates the
   collection so the page does not have to load the full card data - just under
   15 MB of JSON become a few dozen KB. */
async function loadDashboard() {
  await uiConfigReady; // decides whether images come from the mirror or Scryfall
  const res = await fetch(API + '/dashboard', { cache: 'no-cache' });
  if (!res.ok) throw new Error(t('Statistik konnte nicht geladen werden', 'Could not load statistics') + ' (HTTP ' + res.status + ')');
  return res.json();
}

/* Set index: { code(lowercase): {name, cardCount, iconSvgUri, releasedAt, setType, digital} } */
async function loadSetIndex() {
  await uiConfigReady; // decides whether images come from the mirror or Scryfall
  const res = await fetch(API + '/sets', { cache: 'no-cache' });
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
  await uiConfigReady;
  const key = String(code).toLowerCase();
  if (setCardsCache[key]) return setCardsCache[key];
  const res = await fetch(API + '/sets/' + encodeURIComponent(key) + '/cards', { cache: 'no-store' });
  if (!res.ok) return [];
  const cards = await res.json();
  setCardsCache[key] = cards;
  return cards;
}

/* ---- Upload / reset with password (custom header, no browser auth dialog) ---- */

/* The page never keeps the password: POST /api/login answers with an
   HttpOnly session cookie, which the browser sends along with every API
   request on its own and no script can read. Older versions stored the
   password in localStorage; such a value is used once to log in and removed. */
const UPLOAD_PW_KEY = 'mtg_upload_pw';
// Whether write actions are available (no password configured, or logged in).
// Pages listen for the `auth-change` event instead of polling this.
let isUnlocked = false;

function uploadHeaders(extra) {
  return Object.assign({}, extra || {});
}

/* Logs in with the password; true when it was right. */
async function loginWithPassword(pw) {
  const res = await fetch(API + '/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ password: pw }),
    cache: 'no-store',
  });
  if (res.status === 429) {
    throw new Error(t('Zu viele falsche Versuche, bitte später noch einmal.', 'Too many wrong attempts, please try again later.'));
  }
  return res.ok;
}

/* /api/status hides its error texts from visitors who are not unlocked. */
const HIDDEN_STATUS_ERROR = 'error (unlock for details)';
function statusError(s) {
  return s === HIDDEN_STATUS_ERROR ? t('Fehler (Details nach dem Entsperren)', 'Error (unlock for details)') : s;
}

/* A write to the collection API with the upload password. Resolves to the
   JSON answer (or null for 204); rejects with the server's reason. */
async function writeAPI(method, path, body) {
  const res = await fetch(API + path, {
    method,
    headers: uploadHeaders(body != null ? { 'Content-Type': 'application/json' } : {}),
    body: body != null ? JSON.stringify(body) : undefined,
    cache: 'no-store',
  });
  let data = null;
  try { data = await res.json(); } catch (e) { /* 204 or plain text */ }
  if (!res.ok) throw new Error((data && data.error) || `HTTP ${res.status}`);
  return data;
}

/* The note of a binder (folder, list or deck) in full, in el: free text on
   what it holds. Shown to everyone; editing (and adding one) needs the
   unlock. */
function renderNoteBox(el, binderName, note) {
  el.hidden = false;
  el.classList.toggle('empty', !note);
  el.innerHTML = `${note ? `<div class="binder-note-text">${escapeHTML(note)}</div>` : ''}
    <button type="button" class="binder-note-edit unlock-only">${note ? '✎ ' + t('Notiz bearbeiten', 'Edit note') : '+ ' + t('Notiz hinzufügen', 'Add note')}</button>`;
  el.querySelector('.binder-note-edit').addEventListener('click', () => editNoteBox(el, binderName, note));
}

function editNoteBox(el, binderName, note) {
  el.classList.remove('empty');
  el.innerHTML = `
    <form class="binder-note-form">
      <textarea name="note" rows="5" maxlength="4000"
        placeholder="${t('Was ist drin? Z. B. Seite 1–4: Magic 2011, Seite 5: Tauschkarten', 'What is in it? E.g. pages 1–4: Magic 2011, page 5: trades')}"></textarea>
      <div class="binder-rename-actions">
        <button type="button" data-act="cancel">${t('Abbrechen', 'Cancel')}</button>
        <button type="submit" class="primary">${t('Speichern', 'Save')}</button>
      </div>
    </form>`;
  const form = el.querySelector('form');
  const area = form.elements['note'];
  area.value = note;
  area.focus();
  form.querySelector('[data-act="cancel"]').addEventListener('click', () => renderNoteBox(el, binderName, note));
  form.addEventListener('keydown', (e) => { if (e.key === 'Escape') { e.stopPropagation(); renderNoteBox(el, binderName, note); } });
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    form.querySelector('[type="submit"]').disabled = true;
    try {
      const r = await writeAPI('PUT', '/binders/' + encodeURIComponent(binderName) + '/note', { note: area.value });
      renderNoteBox(el, binderName, r.note);
      showNotice(r.note ? t('Notiz gespeichert.', 'Note saved.') : t('Notiz entfernt.', 'Note removed.'));
    } catch (err) {
      showNotice(t('Speichern fehlgeschlagen: ', 'Saving failed: ') + err.message, true);
      form.querySelector('[type="submit"]').disabled = false;
    }
  });
}

/* The note in an overview: a symbol, the text as tooltip on hover/focus. */
function noteIconHTML(note) {
  if (!note) return '';
  return `<span class="binder-note-icon" tabindex="0" aria-label="${t('Notiz', 'Note')}">📝<span class="binder-note-tip" role="tooltip">${escapeHTML(note)}</span></span>`;
}

/* A short message at the bottom of the page, gone after a few seconds. */
function showNotice(text, isError) {
  let el = document.getElementById('notice');
  if (!el) {
    el = document.createElement('div');
    el.id = 'notice';
    el.className = 'notice';
    el.setAttribute('role', 'status');
    document.body.appendChild(el);
  }
  el.textContent = text;
  el.classList.toggle('error', !!isError);
  el.classList.add('show');
  clearTimeout(showNotice.timer);
  showNotice.timer = setTimeout(() => el.classList.remove('show'), 6000);
}

/* The page of one binder (folder or list). No name means the cards without
   a binder: NO_BINDER, the name the backend reserves for them in ?binder=. */
const NO_BINDER = '__none__';
function binderPageURL(name) {
  return 'binder.html?name=' + encodeURIComponent(name || NO_BINDER);
}

/* The binders for the binder pickers (name + type), loaded once per page,
   and which editions each holds ({binder: {SETCODE: copies}}), for the
   suggestions on top of the pickers. */
let bindersCache = null;
let binderSetsCache = {};
async function loadBinderList(force) {
  if (bindersCache && !force) return bindersCache;
  const [list, sets] = await Promise.all([
    fetch(API + '/binders', { cache: 'no-store' }).then((r) => (r.ok ? r.json() : [])).catch(() => []),
    fetch(API + '/binders/sets', { cache: 'no-cache' }).then((r) => (r.ok ? r.json() : {})).catch(() => ({})),
  ]);
  bindersCache = list;
  binderSetsCache = sets || {};
  return bindersCache;
}

/* Folders (not lists or decks) that already hold any of these editions,
   most copies first: [{b, copies}]. */
function binderSuggestions(sets) {
  const codes = (sets || []).map((s) => String(s).toUpperCase()).filter(Boolean);
  if (!codes.length) return [];
  return (bindersCache || [])
    .filter((b) => b.name && binderTypeOf(b) === 'binder')
    .map((b) => ({ b, copies: codes.reduce((n, c) => n + (((binderSetsCache[b.name] || {})[c]) || 0), 0) }))
    .filter((x) => x.copies > 0)
    .sort((x, y) => y.copies - x.copies || x.b.name.localeCompare(y.b.name, 'de'));
}

/* A binder field: a list of every binder (grouped by type), "no folder" and
   "new folder", which shows a name field. The type select a caller puts right
   after the field only shows for a new folder (CSS): an existing binder keeps
   its type on the server anyway. Read it with binderValue(id). */
const BINDER_NEW = '__new__';

function binderTypeOf(b) {
  const type = (b.type || '').toLowerCase();
  return type === 'list' || type === 'deck' ? type : 'binder';
}

/* sets: the editions of the cards being added or moved. Binders that hold
   them already come first, as suggestions with their copies of it. */
function binderOptionsHTML(value, sets) {
  const v = value || '';
  const suggested = binderSuggestions(sets);
  const taken = new Set(suggested.map((x) => x.b.name));
  const list = (bindersCache || []).filter((b) => b.name && !taken.has(b.name));
  let html = '';
  let known = v === '';
  if (suggested.length) {
    html += `<optgroup label="${escapeHTML(t('Vorschläge: haben diese Edition schon', 'Suggested: already hold this edition'))}">` + suggested.map(({ b, copies }) => {
      if (b.name === v) known = true;
      return `<option value="${escapeHTML(b.name)}"${b.name === v ? ' selected' : ''}>★ ${escapeHTML(b.name)} (${copies})</option>`;
    }).join('') + '</optgroup>';
  }
  html += `<option value=""${v === '' ? ' selected' : ''}>${t('– ohne Ordner –', '– no folder –')}</option>`;
  html += `<option value="${BINDER_NEW}">${t('+ Neuer Ordner …', '+ New folder …')}</option>`;
  for (const [type, label] of [['binder', t('Ordner', 'Binders')], ['list', t('Listen', 'Lists')], ['deck', 'Decks']]) {
    const group = list.filter((b) => binderTypeOf(b) === type).sort((a, b) => a.name.localeCompare(b.name, 'de'));
    if (!group.length) continue;
    html += `<optgroup label="${escapeHTML(label)}">` + group.map((b) => {
      if (b.name === v) known = true;
      return `<option value="${escapeHTML(b.name)}"${b.name === v ? ' selected' : ''}>${escapeHTML(b.name)}</option>`;
    }).join('') + '</optgroup>';
  }
  // Not (yet) in the list - keep it selectable rather than losing it.
  if (!known) html += `<option value="${escapeHTML(v)}" selected>${escapeHTML(v)}</option>`;
  return html;
}

/* sets: the editions of the cards in question (see binderOptionsHTML). */
function binderInputHTML(id, value, sets) {
  // Not loaded yet: fill the field once the list is there (after this HTML is in the page).
  if (!bindersCache) ensureBinderDatalist();
  const codes = (sets || []).filter(Boolean).join(',');
  return `<span class="binder-field"><select class="binder-select" id="${id}" data-sets="${escapeHTML(codes)}">${binderOptionsHTML(value, sets)}</select><input
    type="text" class="binder-input" id="${id}-new" placeholder="${t('Name des neuen Ordners', 'Name of the new folder')}" autocomplete="off"></span>`;
}

/* The chosen binder name ('' = no folder), or null for a new folder without a name. */
function binderValue(id) {
  const sel = document.getElementById(id);
  if (!sel) return null;
  if (sel.value !== BINDER_NEW) return sel.value;
  const name = document.getElementById(id + '-new').value.trim();
  return name || null;
}

document.addEventListener('change', (e) => {
  if (!e.target.classList || !e.target.classList.contains('binder-select')) return;
  const isNew = e.target.value === BINDER_NEW;
  e.target.parentElement.classList.toggle('is-new', isNew);
  if (isNew) e.target.parentElement.querySelector('.binder-input').focus();
});

/* Loads the binders and fills every binder field on the page with them. */
async function ensureBinderDatalist() {
  await loadBinderList();
  document.querySelectorAll('select.binder-select').forEach((sel) => {
    if (sel.value !== BINDER_NEW) sel.innerHTML = binderOptionsHTML(sel.value, setsOf(sel));
  });
}

function setsOf(sel) {
  return (sel.dataset.sets || '').split(',').filter(Boolean);
}

/* New editions for a binder field (a selection that changed): the
   suggestions follow, the chosen binder stays. */
function setBinderSets(id, sets) {
  const sel = document.getElementById(id);
  if (!sel) return;
  const codes = [...new Set((sets || []).filter(Boolean).map((s) => String(s).toUpperCase()))];
  if (codes.join(',') === (sel.dataset.sets || '')) return;
  sel.dataset.sets = codes.join(',');
  if (sel.value !== BINDER_NEW) sel.innerHTML = binderOptionsHTML(sel.value, codes);
}

/* Mouseover explanations for the different card-count definitions used across
   the Cards grid and the statistics page. */
function countHint(kind) {
  if (kind === 'entries') {
    return t(
      'Einzelne Sammlungseinträge – jede Sprache, Foil-Variante und jeder Zustand zählt separat.',
      'Individual collection entries – each language, foil variant and condition counts separately.'
    );
  }
  if (kind === 'distinct') {
    return t(
      'Verschiedene Drucke – dieselbe Karte in mehreren Sprachen/Foil/Zustand wird zusammengefasst (wie in der Karten-Ansicht).',
      'Distinct printings – the same card across languages/foil/condition is merged (like the Cards view).'
    );
  }
  return t('Physische Exemplare gesamt (Summe aller Mengen).', 'Total physical copies (sum of all quantities).');
}

/* Explains what the shown market value represents – used as a mouseover title on
   market-value figures. Prices come from Scryfall (Cardmarket trend, daily). */
function priceHint() {
  return t(
    'Marktwert = Cardmarket-Preistrend, von Scryfall einmal täglich übernommen – nicht der aktuell günstigste Kaufpreis. Abweichungen zu Cardmarket oder ManaBox (andere Metrik/Quelle/Währung) sind daher normal.',
    'Market value = Cardmarket price trend, pulled from Scryfall once a day – not the current lowest buy price. Differences from Cardmarket or ManaBox (different metric/source/currency) are therefore expected.'
  );
}

/* Whether writes are allowed right now: no password configured, or a valid
   session cookie. true = access granted (204). */
async function checkUploadAuth() {
  const res = await fetch(API + '/auth-check', { cache: 'no-store' });
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

/* Scryfall's image URLs follow a fixed scheme:
     cards.scryfall.io/<size>/<face>/<1st char>/<2nd char>/<id>.jpg
   So the backend no longer transfers them, only the id - which saves several
   MB per call on a large collection. They are built here and nowhere else, so
   that a later local image mirror only has to touch this one function. */
const SCRYFALL_IMAGE_BASE = 'https://cards.scryfall.io';
/* When the local mirror is active (IMAGE_MIRROR in the backend), the images
   go through our own domain. Which file actually exists there and which is
   fetched from Scryfall is decided by nginx via try_files - the browser does
   not need to know and saves itself a check per card. */
let imageMirrorActive = false;
const SCRYFALL_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function imageBase() {
  return imageMirrorActive ? '/img/cards' : SCRYFALL_IMAGE_BASE;
}

/* Set symbols: the catalog has Scryfall's SVG URL. Only that host and a plain
   file name are passed on - the value ends up in HTML attributes. */
function setSymbolURL(uri) {
  const m = /^https:\/\/svgs\.scryfall\.io\/sets\/([a-z0-9_-]+\.svg)(\?\d+)?$/i.exec(uri || '');
  if (!m) return '';
  return imageMirrorActive ? '/img/sets/' + m[1] : uri;
}

function scryfallImageURL(card, size, face) {
  const sc = card.scryfall;
  if (!sc) return '';
  // A few special printings (art series) have no image in the catalog at all -
  // no valid URL can be built from their id, so it stays the placeholder.
  if (sc.noImage) return '';
  // imageId is only present where the catalog carries a different id than the
  // collection row (a diverging printing in the ManaBox export).
  const id = sc.imageId || card.scryfallId || '';
  // The id lands in a URL inside HTML; ManaBox CSVs are user data, so only a
  // real Scryfall id (a UUID) is turned into a URL.
  if (!SCRYFALL_ID.test(id)) return '';
  if (face === 'back' && !sc.hasBack) return '';
  return `${imageBase()}/${size}/${face || 'front'}/${id[0]}/${id[1]}/${id}.jpg`;
}

function cardImage(card, size) {
  return scryfallImageURL(card, size === 'small' ? 'small' : 'normal', 'front');
}

/* Cards from /api/sets/{code}/cards are flat (imageId/hasBack directly on the
   object) but use the same derivation. */
function setCardImage(card, size, face) {
  return scryfallImageURL({ scryfallId: card.imageId, scryfall: { hasBack: card.hasBack } }, size, face || 'front');
}

/* ---- Wishlist ----
   The set of marked names is loaded once per page and kept in memory, so the
   toggles on a grid of several hundred cards do not each need a request. Names
   are compared lowercased, like everywhere else that keys by card name. */

let wishlistNames = null;
let wishlistReady = null;

async function loadWishlistNames(force) {
  if (wishlistReady && !force) return wishlistReady;
  wishlistReady = (async () => {
    try {
      const res = await fetch(API + '/wishlist', { cache: 'no-store' });
      const data = res.ok ? await res.json() : { items: [] };
      wishlistNames = new Set((data.items || []).map((i) => i.name.toLowerCase()));
    } catch (e) {
      wishlistNames = new Set();
    }
    return wishlistNames;
  })();
  return wishlistReady;
}

function onWishlist(name) {
  return !!wishlistNames && wishlistNames.has(String(name || '').toLowerCase());
}

/* Adds or removes one card. `extra` may carry setCode/collectorNumber (pin a
   printing), targetEur, minCondition, note and priority. Returns the new state. */
async function setWishlist(name, on, extra) {
  const res = on
    ? await fetch(API + '/wishlist', {
        method: 'POST',
        headers: uploadHeaders({ 'Content-Type': 'application/json' }),
        body: JSON.stringify(Object.assign({ name }, extra || {})),
      })
    : await fetch(API + '/wishlist/' + encodeURIComponent(name), {
        method: 'DELETE',
        headers: uploadHeaders(),
      });
  if (res.status === 403) throw new Error(t('Falsches oder fehlendes Passwort', 'Wrong or missing password'));
  if (!res.ok) throw new Error(t('Wunschliste konnte nicht gespeichert werden', 'Could not save the wishlist') + ' (HTTP ' + res.status + ')');
  if (!wishlistNames) wishlistNames = new Set();
  if (on) wishlistNames.add(name.toLowerCase());
  else wishlistNames.delete(name.toLowerCase());
  return on;
}

/* The marker rendered on a card tile or in a detail view. Hidden by CSS unless
   the session is unlocked - marking is a write, like every other action. */
function wishButtonHTML(name, extraClass) {
  const active = onWishlist(name);
  const title = active
    ? t('Von der Wunschliste entfernen', 'Remove from the wishlist')
    : t('Auf die Wunschliste setzen', 'Add to the wishlist');
  return (
    `<button type="button" class="wish-btn${active ? ' on' : ''}${extraClass ? ' ' + extraClass : ''}" ` +
    `data-wish="${escapeHTML(name)}" title="${escapeHTML(title)}" aria-label="${escapeHTML(title)}">` +
    `${active ? '★' : '☆'}</button>`
  );
}

/* One delegated handler per container for every marker inside it. `onChange` is
   called after a successful toggle, e.g. to refresh a counter. */
function initWishButtons(container, onChange) {
  if (!container || container.dataset.wishWired) return;
  container.dataset.wishWired = '1';
  container.addEventListener('click', async (e) => {
    const btn = e.target.closest('.wish-btn');
    if (!btn || !container.contains(btn)) return;
    // Tiles and table rows have click handlers of their own (open the detail
    // view); marking must not also open it. stopImmediatePropagation, not
    // stopPropagation: the other handler often sits on the same container, and
    // only this stops it - which is why this listener is wired first.
    e.stopImmediatePropagation();
    e.preventDefault();
    const name = btn.dataset.wish;
    const on = !btn.classList.contains('on');
    btn.disabled = true;
    try {
      await setWishlist(name, on);
      syncWishButtons(name);
      if (onChange) onChange(name, on);
    } catch (err) {
      alert(err.message);
    } finally {
      btn.disabled = false;
    }
  });
}

/* Keeps every marker for the same card in sync - a name can appear more than
   once on a page (grid tile plus open detail view). */
function syncWishButtons(name) {
  const active = onWishlist(name);
  document.querySelectorAll('.wish-btn').forEach((b) => {
    if (b.dataset.wish.toLowerCase() !== String(name).toLowerCase()) return;
    b.classList.toggle('on', active);
    b.textContent = active ? '★' : '☆';
    const title = active
      ? t('Von der Wunschliste entfernen', 'Remove from the wishlist')
      : t('Auf die Wunschliste setzen', 'Add to the wishlist');
    b.title = title;
    b.setAttribute('aria-label', title);
  });
}

const LANGUAGE_FLAG = {
  en: '🇬🇧', de: '🇩🇪', fr: '🇫🇷', it: '🇮🇹', es: '🇪🇸', pt: '🇵🇹',
  ja: '🇯🇵', ko: '🇰🇷', ru: '🇷🇺', zhs: '🇨🇳', zht: '🇹🇼',
};

function languageFlag(lang) {
  return LANGUAGE_FLAG[String(lang || '').toLowerCase()] || '';
}

// Official, standard card types (keeps joke/Un-set oddities out). type_line per
// face: "{supertypes} {types} — {subtypes}"; split (//) and DFC handled per face.
const OFFICIAL_TYPES = new Set([
  'Creature', 'Instant', 'Sorcery', 'Enchantment', 'Artifact', 'Planeswalker', 'Land', 'Battle',
]);

function cardTypes(typeLine) {
  const set = new Set();
  String(typeLine || '')
    .split('//')
    .forEach((face) => {
      face.split('—')[0].trim().split(/\s+/).forEach((w) => {
        if (OFFICIAL_TYPES.has(w)) set.add(w);
      });
    });
  return [...set];
}

function cardSubtypes(typeLine) {
  const set = new Set();
  String(typeLine || '')
    .split('//')
    .forEach((face) => {
      const parts = face.split('—');
      if (parts.length >= 2) {
        parts.slice(1).join('—').trim().split(/\s+/).forEach((s) => {
          if (s) set.add(s);
        });
      }
    });
  return [...set];
}

/* Converted mana cost (mana value) from a "{2}{R}{R}" string; null if none. */
function manaValue(manaCost) {
  if (!manaCost) return null;
  let total = 0;
  (String(manaCost).match(/\{([^}]+)\}/g) || []).forEach((tok) => {
    const s = tok.slice(1, -1);
    if (/^\d+$/.test(s)) total += parseInt(s, 10);
    else if (/^[XYZ]$/.test(s)) total += 0;
    else total += 1; // colored, hybrid, phyrexian, colorless, snow…
  });
  return total;
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

/* Cancels a pending renderIncremental() run on `container`, if any (used
   whenever a grid is about to be replaced by something other than another
   renderIncremental call, e.g. a loading/error/empty-state message). */
function stopIncremental(container) {
  if (container._incrementalStop) {
    container._incrementalStop();
    container._incrementalStop = null;
  }
}

/* Fills `container` with `items` via `buildTile(item)`, appending in batches
   instead of turning the whole array into DOM nodes synchronously – matters
   once a filter/search matches thousands of cards, since that work would
   otherwise re-run in full on every keystroke. The first batch renders right
   away; further batches append as a sentinel row scrolls near the viewport.
   Safe to call repeatedly on the same container – any run still in progress
   from a previous call is cancelled first. */
function renderIncremental(container, items, buildTile, batchSize = 60) {
  stopIncremental(container);
  container.innerHTML = '';
  if (!items.length) return;

  let cursor = 0;
  let stopped = false;

  function appendBatch() {
    const frag = document.createDocumentFragment();
    const end = Math.min(cursor + batchSize, items.length);
    for (; cursor < end; cursor++) frag.appendChild(buildTile(items[cursor]));
    container.appendChild(frag);
    if (stopped) return;
    if (cursor >= items.length) {
      container._incrementalStop = null;
      return;
    }
    armSentinel();
  }

  function armSentinel() {
    const sentinel = document.createElement('div');
    sentinel.className = 'grid-sentinel';
    container.appendChild(sentinel);
    // Grids that scroll within themselves (e.g. the edition overlay) need
    // that element as the intersection root instead of the page viewport.
    const overflowY = getComputedStyle(container).overflowY;
    const root = overflowY === 'auto' || overflowY === 'scroll' ? container : null;
    const observer = new IntersectionObserver((entries) => {
      if (!entries[0].isIntersecting) return;
      observer.disconnect();
      sentinel.remove();
      if (!stopped) appendBatch();
    }, { root, rootMargin: '800px 0px' });
    observer.observe(sentinel);
    container._incrementalStop = () => {
      stopped = true;
      observer.disconnect();
      sentinel.remove();
    };
  }

  appendBatch();
}

function showLoading(message) {
  const el = document.getElementById('loading-overlay');
  if (!el) return;
  el.style.display = 'flex';
  const text = el.querySelector('.loading-text');
  if (text) text.textContent = message || t('Lade Sammlung…', 'Loading collection…');
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

/* The vocabulary in the order Cardmarket and ManaBox use it, best first. Drives
   the condition selects; the labels above stay the single source for the text.
   `light_played`/`lightly_played` are the same grade in two spellings, so only
   one of them is offered for new input. */
const CONDITION_ORDER = ['mint', 'near_mint', 'excellent', 'good', 'light_played', 'played', 'poor'];

function conditionOptionsHTML(selected) {
  return (
    `<option value="">${t('egal', 'any')}</option>` +
    CONDITION_ORDER.map(
      (c) => `<option value="${c}"${c === selected ? ' selected' : ''}>${escapeHTML(conditionLabel(c))}</option>`
    ).join('')
  );
}

function conditionLabel(c) {
  if (!c) return '–';
  const k = String(c).toLowerCase();
  return CONDITION_LABEL[k] || String(c).replace(/_/g, ' ').replace(/\b\w/g, (m) => m.toUpperCase());
}

/* Renders a single mana/text symbol token (without braces) as a Scryfall SVG. */
function manaSymbolImg(token) {
  const code = token.toUpperCase().replace(/\//g, '');
  const base = imageMirrorActive ? '/img/symbols' : 'https://svgs.scryfall.io/card-symbols';
  return `<img class="mana-symbol" src="${base}/${encodeURIComponent(code)}.svg" alt="{${escapeHTML(token)}}" title="{${escapeHTML(token)}}" loading="lazy">`;
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
/* Foil-aware market price of a single collection variant: foil/etched copies use
   the foil price when available, everything else the normal price. */
function variantMarketPrice(v) {
  const s = v.scryfall;
  if (!s) return null;
  const isFoil = v.foil && v.foil.toLowerCase() !== 'normal' && v.foil !== '';
  if (isFoil && s.priceEurFoil != null) return parseFloat(s.priceEurFoil);
  return s.priceEur != null ? parseFloat(s.priceEur) : null;
}

function cardGroupModalHTML(group) {
  const rep = group.rep;
  const img = cardImage(rep, 'normal');
  const backImg = scryfallImageURL(rep, 'normal', 'back');
  const variants = group.variants
    .slice()
    .sort((a, b) => (a.language || '').localeCompare(b.language || '') || a.foil.localeCompare(b.foil));

  // The grid's groups carry row ids; with write access each row can be
  // changed right here (grid.js handles the buttons).
  const editable = !!group.editable && isUnlocked;
  const rows = variants
    .map((v) => {
      const mp = variantMarketPrice(v);
      const price = mp != null ? formatCurrency(mp, 'EUR') : '–';
      const foilTag = v.foil && v.foil !== 'normal' ? ` <span class="lang-foil">${escapeHTML(v.foil)}</span>` : '';
      return `
        <tr>
          <td>${languageFlag(v.language)} ${escapeHTML((v.language || '?').toUpperCase())}${foilTag}</td>
          <td>${escapeHTML(conditionLabel(v.condition))}</td>
          <td>${escapeHTML(v.binderName || '–')}</td>
          <td class="num">${price}</td>
          <td class="num">${v.quantity}</td>
          <td>${escapeHTML((v.added || '').slice(0, 10)) || '–'}</td>
          ${editable ? `<td class="row-actions">
            <button type="button" data-act="minus" data-id="${v.id}" title="${t('Ein Exemplar weniger', 'One copy less')}">−1</button>
            <button type="button" data-act="plus" data-id="${v.id}" title="${t('Ein Exemplar mehr', 'One copy more')}">+1</button>
            <button type="button" data-act="move" data-id="${v.id}">${t('Ordner…', 'Folder…')}</button>
          </td>` : ''}
        </tr>`;
    })
    .join('');

  const totalMarket = variants.reduce((a, v) => a + (variantMarketPrice(v) || 0) * v.quantity, 0);

  return `
    <div class="modal-card">
      <div class="modal-image">
        ${cardFaceHTML(group.name, img, backImg)}
      </div>
      <div class="modal-details">
        <h2>${escapeHTML(group.name)}</h2>
        ${rep.scryfall && rep.scryfall.manaCost ? `<div class="mana-cost">${renderManaCost(rep.scryfall.manaCost)}</div>` : ''}
        <p class="modal-type">${escapeHTML((rep.scryfall && rep.scryfall.typeLine) || '')}</p>
        ${rep.scryfall && rep.scryfall.oracleText ? `<div class="oracle-text">${renderOracleText(rep.scryfall.oracleText)}</div>` : ''}
        <dl>
          <dt>Set</dt><dd>${escapeHTML(group.setName)} (${escapeHTML(group.setCode)} #${escapeHTML(group.collectorNumber)})</dd>
          <dt>${t('Rarität', 'Rarity')}</dt><dd>${escapeHTML(rep.rarity)}</dd>
          <dt>${t('Exemplare gesamt', 'Copies total')}</dt><dd>${group.totalQty}</dd>
          <dt title="${priceHint()}">${t('Marktwert gesamt', 'Total market value')}</dt><dd title="${priceHint()}">${formatCurrency(totalMarket, 'EUR')}</dd>
        </dl>
        <div class="wish-line">${wishButtonHTML(group.name)}<span class="wish-line-label">${t('Wunschliste', 'Wishlist')}</span></div>
        <h3 class="lang-table-title">${t('Sprachen in deiner Sammlung', 'Languages in your collection')}</h3>
        <table class="lang-table">
          <thead><tr><th>${t('Sprache', 'Language')}</th><th>${t('Zustand', 'Condition')}</th><th>${t('Ordner', 'Folder')}</th><th class="num" title="${priceHint()}">${t('Preis (Cardmarket-Trend)', 'Price (Cardmarket trend)')}</th><th class="num">${t('Anzahl', 'Quantity')}</th><th>${t('Hinzugefügt', 'Added')}</th>${editable ? '<th></th>' : ''}</tr></thead>
          <tbody>${rows}</tbody>
        </table>
        ${editable ? '<div class="row-edit" id="row-edit"></div>' : ''}
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
      // Image and set symbol are derived so the printings go through the
      // local mirror as well when it is active.
      const img = setCardImage({ imageId: p.imageId }, 'small');
      const iconURL = setSymbolURL(p.iconSvgUri);
      const icon = iconURL
        ? `<img class="variant-icon" src="${escapeHTML(iconURL)}" alt="" loading="lazy">`
        : '';
      return `
        <div class="variant" title="${escapeHTML(p.setName || p.setCode)} #${escapeHTML(p.collectorNumber)}">
          <div class="variant-thumb">${img ? `<img loading="lazy" src="${escapeHTML(img)}" alt="">` : ''}</div>
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

/* The card image in the detail dialog. Double-faced cards (transform, modal
   DFCs, sagas) used to be shown as two images below each other; instead there
   is now one image that can be flipped.

   The back face only sits in the markup as data-src and is loaded on the first
   flip - saving around 100 KB every time such a card is opened, because most
   of the time nobody wants to see it. */
function cardFaceHTML(name, frontURL, backURL) {
  if (!frontURL) return `<div class="no-image">${t('Kein Bild', 'No image')}</div>`;
  if (!backURL) return `<img src="${escapeHTML(frontURL)}" alt="${escapeHTML(name)}">`;
  const backLabel = `${escapeHTML(name)} (${t('Rückseite', 'back')})`;
  return `
    <div class="card-flip" data-flipped="false">
      <div class="card-flip-inner">
        <img class="card-face card-front" src="${escapeHTML(frontURL)}" alt="${escapeHTML(name)}">
        <img class="card-face card-back" data-src="${escapeHTML(backURL)}" alt="${backLabel}" aria-hidden="true">
      </div>
    </div>
    <button type="button" class="flip-btn" aria-pressed="false">
      <span aria-hidden="true">↻</span> ${t('Umdrehen', 'Flip')}
    </button>`;
}

/* Clicking the image or the button flips the card. */
function initCardFlip(root) {
  const flip = root.querySelector('.card-flip');
  if (!flip) return;
  const btn = root.querySelector('.flip-btn');
  const back = flip.querySelector('.card-back');
  const front = flip.querySelector('.card-front');

  const toggle = () => {
    const flipped = flip.dataset.flipped !== 'true';
    // Load the back face on the first flip.
    if (flipped && !back.getAttribute('src')) back.setAttribute('src', back.dataset.src);
    flip.dataset.flipped = String(flipped);
    if (btn) btn.setAttribute('aria-pressed', String(flipped));
    // Take the hidden face out of the screen-reader flow.
    back.setAttribute('aria-hidden', String(!flipped));
    front.setAttribute('aria-hidden', String(flipped));
  };

  flip.addEventListener('click', toggle);
  if (btn) btn.addEventListener('click', toggle);
}

function openCardModal(html) {
  const overlay = document.getElementById('card-modal');
  if (!overlay) return;
  const body = overlay.querySelector('.modal-body');
  body.innerHTML = '<span class="modal-close">&times;</span>' + html;
  initCardFlip(body);
  initWishButtons(overlay);
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
    if (st.lastError) {
      el.innerHTML = label + ' <span class="sync-err">⚠ ' + t('Fehler', 'error') + '</span>';
      el.classList.add('has-error');
      el.title = t('Letzter Sync-Fehler (klicken für Details): ', 'Last sync error (click for details): ') + statusError(st.lastError);
      el.style.cursor = 'pointer';
      el.onclick = () => alert(t('Letzter Sync-Fehler:\n\n', 'Last sync error:\n\n') + statusError(st.lastError));
    } else {
      el.textContent = label;
      el.classList.remove('has-error');
      el.title = t(`Sammlung: ${st.collectionCount} · Karten-DB: ${st.cardCount} · Sets: ${st.setCount}`,
        `Collection: ${st.collectionCount} · Card DB: ${st.cardCount} · Sets: ${st.setCount}`);
      el.style.cursor = '';
      el.onclick = null;
    }
  }
  return st;
}

/* ---- Admin-configurable UI settings (served by the backend) ---- */

let UI_CONFIG = null;
async function loadUIConfig() {
  try {
    const res = await fetch(API + '/config', { cache: 'no-store' });
    UI_CONFIG = res.ok ? await res.json() : null;
  } catch (e) {
    UI_CONFIG = null;
  }
  if (!UI_CONFIG) UI_CONFIG = { randomEnabled: true, randomCount: 6, hiddenNav: [] };
  if (!Array.isArray(UI_CONFIG.hiddenNav)) UI_CONFIG.hiddenNav = [];
  imageMirrorActive = UI_CONFIG.imageMirror === true;
  return UI_CONFIG;
}
// Kicked off immediately so pages can `await uiConfigReady`.
const uiConfigReady = loadUIConfig();

// Configurable navigation entries (key = page filename without .html).
const NAV_ITEMS = [
  { key: 'index', label: () => t('Karten', 'Cards') },
  { key: 'editions', label: () => t('Editionen', 'Editions') },
  { key: 'reserved', label: () => t('Reserved List', 'Reserved List') },
  { key: 'wishlist', label: () => t('Wunschliste', 'Wishlist') },
  { key: 'binders', label: () => t('Ordner', 'Folders') },
  { key: 'lists', label: () => t('Listen', 'Lists') },
  { key: 'decks', label: () => t('Decks', 'Decks') },
  { key: 'dashboard', label: () => t('Statistik', 'Statistics') },
  { key: 'deck-checker', label: () => t('Deck-Checker', 'Deck Checker') },
];

function navKeyFromHref(href) {
  return ((href || '').split('/').pop() || '').replace('.html', '') || 'index';
}

/* Hides the nav entries the admin disabled. Applied on every page. */
function applyNavConfig() {
  const hidden = (UI_CONFIG && UI_CONFIG.hiddenNav) || [];
  document.querySelectorAll('nav.topnav a[href$=".html"]').forEach((a) => {
    if (a.classList.contains('brand') || a.id === 'nav-activity') return;
    a.style.display = hidden.includes(navKeyFromHref(a.getAttribute('href'))) ? 'none' : '';
  });
}

/* Builds the admin overlay once and appends it to the body. It also hosts the
   maintenance buttons (Reset/Backup/Restore) that used to sit in the nav – they
   keep their ids so initCollectionUpload wires them as before. */
function buildAdminOverlay() {
  if (document.getElementById('admin-overlay')) return;
  const ov = document.createElement('div');
  ov.className = 'modal-overlay admin-overlay';
  ov.id = 'admin-overlay';
  ov.innerHTML = `
    <div class="admin-panel">
      <div class="admin-header">
        <h2>${t('Einstellungen', 'Settings')}</h2>
        <span class="modal-close admin-close">&times;</span>
      </div>
      <div class="admin-body">
        <section class="admin-section">
          <h3>${t('Import & Daten', 'Import & data')}</h3>
          <p class="admin-status" id="cfg-data-status"></p>
          <div class="admin-actions">
            <button type="button" id="csv-sync" class="reset-btn">🔄 ${t('Kartendaten aktualisieren', 'Update card data')}</button>
            <label class="reset-btn upload-label" id="csv-upload-label">⬆ ${t('CSV hochladen', 'Upload CSV')}
              <input type="file" id="csv-upload" accept=".csv,text/csv" hidden>
            </label>
            ${helpHTML('„Kartendaten aktualisieren“ holt Karten und Preise sofort von Scryfall (läuft sonst automatisch). „CSV hochladen“ übernimmt einen ManaBox-Export und ersetzt alle bisher importierten Karten – gedacht für die Ersteinrichtung; in der App hinzugefügte Karten bleiben.',
              '“Update card data” fetches cards and prices from Scryfall now (otherwise this runs automatically). “Upload CSV” takes a ManaBox export and replaces all cards imported so far – meant for the initial setup; cards added in the app stay.')}
          </div>
          <div id="cfg-import" class="admin-import"></div>
        </section>
        <section class="admin-section">
          <h3>${t('Zufallskarten', 'Random cards')}</h3>
          <label class="admin-check"><input type="checkbox" id="cfg-random-enabled"> ${t('Zufällige Karten auf der Karten-Seite anzeigen', 'Show random cards on the Cards page')}</label>
          <label class="admin-num">${t('Anzahl', 'Count')} <input type="number" id="cfg-random-count" min="1" max="30"></label>
        </section>
        <section class="admin-section">
          <h3>${t('Menüpunkte', 'Menu items')} ${helpHTML('Abgewählte Punkte werden im Menü ausgeblendet.', 'Unchecked items are hidden from the menu.')}</h3>
          <div id="cfg-nav-list" class="admin-navlist"></div>
        </section>
        <section class="admin-section">
          <h3>${t('Wartung', 'Maintenance')} ${helpHTML('Das Backup enthält deine Sammlung, die Wunschliste, den Wert- und Preisverlauf und die Einstellungen und wird als Datei in Nextcloud bzw. Google Drive abgelegt. Der Scryfall-Katalog (Karten, Sets, aktuelle Preise) ist nicht enthalten – er wird nach einer Wiederherstellung automatisch neu geladen.',
            'The backup holds your collection, the wishlist, the value and price history and the settings, stored as a file in Nextcloud or Google Drive. The Scryfall catalog (cards, sets, current prices) is not included – it is downloaded again automatically after a restore.')}</h3>
          <p class="admin-status" id="cfg-backup-status"></p>
          <div class="admin-actions">
            <button type="button" id="csv-reset" class="reset-btn" title="Gesamte Sammlung leeren" data-en-title="Clear the entire collection">${t('Sammlung leeren', 'Clear collection')}</button>
            <button type="button" id="csv-backup" class="reset-btn" title="Jetzt sichern (Sammlung + Wert-Historie)" data-en-title="Back up now">💾 ${t('Backup', 'Backup')}</button>
            <button type="button" id="csv-restore" class="reset-btn" title="Neuestes Backup wiederherstellen" data-en-title="Restore latest backup">⟲ ${t('Wiederherstellen', 'Restore')}</button>
          </div>
        </section>
      </div>
      <div class="admin-footer">
        <button type="button" class="reset-btn admin-close">${t('Abbrechen', 'Cancel')}</button>
        <button type="button" id="cfg-save" class="upload-btn admin-save">${t('Speichern', 'Save')}</button>
      </div>
    </div>`;
  document.body.appendChild(ov);

  ov.querySelectorAll('.admin-close').forEach((el) => el.addEventListener('click', closeAdminOverlay));
  ov.addEventListener('click', (e) => {
    if (e.target === ov) closeAdminOverlay();
  });
  document.getElementById('cfg-save').addEventListener('click', saveAdminConfig);
}

async function openAdminOverlay() {
  await uiConfigReady;
  const ov = document.getElementById('admin-overlay');
  if (!ov) return;
  document.getElementById('cfg-random-enabled').checked = !!UI_CONFIG.randomEnabled;
  document.getElementById('cfg-random-count').value = UI_CONFIG.randomCount || 6;
  const list = document.getElementById('cfg-nav-list');
  list.innerHTML = NAV_ITEMS.map(
    (n) =>
      `<label class="admin-check"><input type="checkbox" data-nav-key="${n.key}" ${
        UI_CONFIG.hiddenNav.includes(n.key) ? '' : 'checked'
      }> ${escapeHTML(n.label())}</label>`
  ).join('');
  ov.classList.add('open');
  renderImportSettings();
}

/* The remote import part of the settings: automatic every n minutes or only
   on demand. Sources and credentials stay in the server's environment. */
let importStatus = null;
async function renderImportSettings() {
  const box = document.getElementById('cfg-import');
  const dataEl = document.getElementById('cfg-data-status');
  if (!box) return;
  let st;
  try {
    st = await fetchSyncStatus();
  } catch (e) {
    box.innerHTML = '';
    return;
  }
  dataEl.textContent = st.running
    ? t('Kartendaten werden gerade aktualisiert …', 'Card data is being updated …')
    : t(`Kartendaten: ${relTime(st.cardsSyncedAt)} · Sammlung: ${st.collectionCount} Einträge`,
      `Card data: ${relTime(st.cardsSyncedAt)} · Collection: ${st.collectionCount} entries`);
  renderBackupStatus(st.backup || {});
  const ri = st.remoteImport || {};
  importStatus = ri;
  if (!ri.enabled) {
    box.innerHTML = `<p class="section-hint">${t(
      'Automatischer Import aus Nextcloud/Google Drive: nicht eingerichtet (Zugangsdaten in der .env).',
      'Automatic import from Nextcloud/Google Drive: not set up (credentials in the .env).')}</p>`;
    return;
  }
  const mode = ri.mode || 'auto';
  const last = ri.lastAt
    ? t(`Letzter Import: ${relTime(ri.lastAt)} aus ${ri.lastSource || '?'}`, `Last import: ${relTime(ri.lastAt)} from ${ri.lastSource || '?'}`)
    : t('Noch kein automatischer Import.', 'No automatic import yet.');
  const checked = ri.lastCheckAt ? ' · ' + t('zuletzt geprüft ', 'last checked ') + relTime(ri.lastCheckAt) : '';
  box.innerHTML = `
    <h4>${t('Import aus', 'Import from')} ${escapeHTML((ri.sources || []).join(' + '))}
      ${helpHTML('Automatisch: der Server schaut im eingestellten Abstand nach einer neuen ManaBox-CSV und importiert sie. Manuell: nur über „Jetzt importieren“. Die Werte aus der .env gelten, solange hier nichts gespeichert wurde.',
        'Automatic: the server looks for a new ManaBox CSV at the set interval and imports it. Manual: only via “Import now”. The .env values apply as long as nothing was saved here.')}</h4>
    <label class="admin-check"><input type="radio" name="cfg-import-mode" value="auto" ${mode === 'auto' ? 'checked' : ''}> ${t('Automatisch', 'Automatic')}</label>
    <label class="admin-num" id="cfg-import-interval-row">${t('alle', 'every')} <input type="number" id="cfg-import-interval" min="1" max="10080" value="${ri.intervalMinutes || 15}"> ${t('Minuten', 'minutes')}</label>
    <label class="admin-check"><input type="radio" name="cfg-import-mode" value="manual" ${mode === 'manual' ? 'checked' : ''}> ${t('Manuell', 'Manual')}</label>
    <p class="admin-status">${escapeHTML(last + checked)}</p>
    ${ri.lastError ? `<p class="admin-status sync-err">⚠ ${escapeHTML(statusError(ri.lastError))}</p>` : ''}
    <div class="admin-actions"><button type="button" id="cfg-import-run" class="reset-btn" ${ri.running ? 'disabled' : ''}>📥 ${ri.running ? t('Import läuft …', 'Import running …') : t('Jetzt importieren', 'Import now')}</button></div>`;
  const syncInterval = () => {
    const auto = box.querySelector('input[name="cfg-import-mode"]:checked').value === 'auto';
    document.getElementById('cfg-import-interval').disabled = !auto;
  };
  box.querySelectorAll('input[name="cfg-import-mode"]').forEach((r) => r.addEventListener('change', syncInterval));
  syncInterval();
  document.getElementById('cfg-import-run').addEventListener('click', runImportNow);
}

/* When the last backup ran (and the last restore, or an error). */
function renderBackupStatus(b) {
  const el = document.getElementById('cfg-backup-status');
  if (!el) return;
  if (!b.enabled) {
    el.textContent = t('Kein Backup-Ziel eingerichtet (Nextcloud/Google Drive in der .env).', 'No backup target set up (Nextcloud/Google Drive in the .env).');
    return;
  }
  let html = escapeHTML(b.lastAt
    ? t(`Letztes Backup: ${relTime(b.lastAt)}`, `Last backup: ${relTime(b.lastAt)}`) + ` (${new Date(b.lastAt).toLocaleString(LANG === 'en' ? 'en-GB' : 'de-DE')})`
    : t('Noch kein Backup.', 'No backup yet.'));
  if (b.lastRestoreAt) html += '<br>' + escapeHTML(t(`Zuletzt wiederhergestellt: ${relTime(b.lastRestoreAt)}`, `Last restored: ${relTime(b.lastRestoreAt)}`));
  if (b.lastError) html += `<br><span class="sync-err">⚠ ${escapeHTML(statusError(b.lastError))}</span>`;
  el.innerHTML = html;
}

/* Looks for a new CSV now and follows the status until the round is over. */
async function runImportNow() {
  const btn = document.getElementById('cfg-import-run');
  btn.disabled = true;
  const before = importStatus ? importStatus.lastAt : '';
  try {
    await writeAPI('POST', '/import/run');
  } catch (e) {
    btn.disabled = false;
    return showNotice(t('Import fehlgeschlagen: ', 'Import failed: ') + e.message, true);
  }
  btn.textContent = '📥 ' + t('Import läuft …', 'Import running …');
  for (let i = 0; i < 150; i++) {
    await new Promise((r) => setTimeout(r, 2000));
    let ri;
    try { ri = (await fetchSyncStatus()).remoteImport || {}; } catch (e) { continue; }
    if (ri.running) continue;
    if (ri.lastAt && ri.lastAt !== before) {
      showNotice(t('Neue CSV importiert.', 'New CSV imported.'));
      setTimeout(() => location.reload(), 1200);
    } else if (ri.lastError) {
      showNotice(t('Import fehlgeschlagen: ', 'Import failed: ') + statusError(ri.lastError), true);
    } else {
      showNotice(t('Keine neue CSV gefunden.', 'No new CSV found.'));
    }
    break;
  }
  renderImportSettings();
}

function closeAdminOverlay() {
  const ov = document.getElementById('admin-overlay');
  if (ov) ov.classList.remove('open');
}

async function saveAdminConfig() {
  const hiddenNav = [...document.querySelectorAll('#cfg-nav-list input[data-nav-key]')]
    .filter((c) => !c.checked)
    .map((c) => c.dataset.navKey);
  const body = {
    randomEnabled: document.getElementById('cfg-random-enabled').checked,
    randomCount: parseInt(document.getElementById('cfg-random-count').value, 10) || 6,
    hiddenNav,
  };
  try {
    const modeEl = document.querySelector('input[name="cfg-import-mode"]:checked');
    if (modeEl) {
      const minutes = parseInt(document.getElementById('cfg-import-interval').value, 10);
      if (!(minutes >= 1 && minutes <= 10080)) {
        alert(t('Das Intervall muss zwischen 1 und 10080 Minuten liegen.', 'The interval must be between 1 and 10080 minutes.'));
        return;
      }
      await writeAPI('POST', '/import/config', { mode: modeEl.value, intervalMinutes: minutes });
    }
    const res = await fetch(API + '/config', {
      method: 'POST',
      headers: uploadHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify(body),
    });
    if (res.status === 403) {
      alert(t('Falsches oder fehlendes Passwort', 'Wrong or missing password'));
      return;
    }
    if (!res.ok) {
      alert(t('Speichern fehlgeschlagen.', 'Saving failed.'));
      return;
    }
    location.reload(); // apply everywhere cleanly
  } catch (e) {
    alert(t('Speichern fehlgeschlagen: ', 'Saving failed: ') + e.message);
  }
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
  // Reset/Backup/Restore now live inside the admin overlay (built in JS).
  const backupBtn = document.getElementById('csv-backup');
  const restoreBtn = document.getElementById('csv-restore');
  const activityLink = document.getElementById('nav-activity');
  const adminGear = document.getElementById('admin-gear');
  if (!uploadInput || !uploadLabel || !resetBtn || !unlockBtn) return;

  // Status indicator is shown to everyone; refresh on load.
  refreshSyncStatus();

  function setUnlocked(unlocked) {
    // Pages read this to decide whether write controls (wishlist markers) are
    // offered; the class drives the CSS, the variable the JS.
    isUnlocked = unlocked;
    document.body.classList.toggle('is-unlocked', unlocked);
    document.dispatchEvent(new CustomEvent('auth-change', { detail: { unlocked } }));
    // Admin settings + activity log are only offered once logged in.
    if (adminGear) adminGear.style.display = unlocked ? '' : 'none';
    if (activityLink) activityLink.style.display = unlocked ? '' : 'none';
    unlockBtn.style.display = unlocked ? 'none' : '';
  }

  if (adminGear) adminGear.addEventListener('click', openAdminOverlay);

  if (syncBtn) {
    syncBtn.addEventListener('click', async () => {
      syncBtn.disabled = true;
      const statusEl = document.getElementById('cfg-data-status');
      if (statusEl) statusEl.textContent = t('Kartendaten werden gerade aktualisiert …', 'Card data is being updated …');
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
          } else if (st && st.lastError) {
            alert(t('Aktualisierung mit Fehler beendet:\n\n', 'Update finished with an error:\n\n') + statusError(st.lastError));
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
    if (!confirm(t(`„${file.name}“ importieren? Alle bisher aus ManaBox importierten Karten werden durch diese Datei ersetzt; in der App hinzugefügte Karten bleiben.`,
      `Import “${file.name}”? All cards imported from ManaBox so far are replaced by this file; cards added in the app stay.`))) {
      uploadInput.value = '';
      return;
    }
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

  if (backupBtn) {
    backupBtn.addEventListener('click', async () => {
      backupBtn.disabled = true;
      try {
        const res = await fetch(API + '/backup', { method: 'POST', headers: uploadHeaders() });
        if (res.status === 403) {
          alert(t('Falsches oder fehlendes Passwort', 'Wrong or missing password'));
        } else if (res.status === 400) {
          alert(t('Kein Backup-Ziel konfiguriert (Nextcloud/Google Drive).', 'No backup target configured (Nextcloud/Google Drive).'));
        } else if (!res.ok) {
          alert(t('Backup fehlgeschlagen.', 'Backup failed.'));
        } else {
          alert(t('Backup wurde gestartet.', 'Backup started.'));
          setTimeout(renderImportSettings, 5000); // show the new "last backup" time
        }
      } catch (e) {
        alert(t('Backup fehlgeschlagen: ', 'Backup failed: ') + e.message);
      } finally {
        backupBtn.disabled = false;
      }
    });
  }

  if (restoreBtn) {
    restoreBtn.addEventListener('click', async () => {
      if (!confirm(t('Neuestes Backup wiederherstellen? Die aktuelle Sammlung und Wert-Historie werden ersetzt.', 'Restore the latest backup? The current collection and value history will be replaced.'))) return;
      showLoading(t('Stelle Backup wieder her...', 'Restoring backup...'));
      try {
        const res = await fetch(API + '/backup/restore-latest', { method: 'POST', headers: uploadHeaders() });
        if (res.status === 403) {
          hideLoading();
          alert(t('Falsches oder fehlendes Passwort', 'Wrong or missing password'));
        } else if (res.status === 404) {
          hideLoading();
          alert(t('Kein Backup gefunden.', 'No backup found.'));
        } else if (!res.ok) {
          hideLoading();
          alert(t('Wiederherstellung fehlgeschlagen.', 'Restore failed.'));
        } else {
          location.reload();
        }
      } catch (e) {
        hideLoading();
        alert(t('Wiederherstellung fehlgeschlagen: ', 'Restore failed: ') + e.message);
      }
    });
  }

  unlockBtn.addEventListener('click', async () => {
    const pw = prompt(t('Passwort:', 'Password:'));
    if (pw == null || pw === '') return;
    try {
      if (await loginWithPassword(pw)) {
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
      let stored = null;
      try {
        stored = localStorage.getItem(UPLOAD_PW_KEY) || sessionStorage.getItem(UPLOAD_PW_KEY);
        localStorage.removeItem(UPLOAD_PW_KEY);
        sessionStorage.removeItem(UPLOAD_PW_KEY);
      } catch (e) {
        /* ignore */
      }
      if (await checkUploadAuth()) {
        setUnlocked(true);
      } else if (stored && (await loginWithPassword(stored))) {
        setUnlocked(true);
      } else {
        setUnlocked(false);
      }
    } catch (e) {
      setUnlocked(false);
    }
  })();
}

/* Mobile hamburger: toggles the collapsed nav (links + actions). */
function initNavToggle() {
  const nav = document.querySelector('nav.topnav');
  const btn = document.getElementById('nav-toggle');
  if (!nav || !btn) return;
  const setOpen = (open) => {
    nav.classList.toggle('open', open);
    btn.setAttribute('aria-expanded', open ? 'true' : 'false');
    btn.textContent = open ? '✕' : '☰';
  };
  btn.addEventListener('click', () => setOpen(!nav.classList.contains('open')));
  // A link navigates away, so close on tap; action buttons keep it open.
  nav.querySelectorAll('a:not(.brand)').forEach((a) => a.addEventListener('click', () => setOpen(false)));
}

/* "?" help: explanations live behind a small button instead of on the page.
   They show on hover with a mouse and on tap/click (then they stay until a
   click elsewhere or Escape). The bubble is fixed-positioned so no scrolling
   container clips it, and moved inside the viewport. */
function helpHTML(de, en) {
  return `<span class="help"><button type="button" class="help-btn" aria-expanded="false" aria-label="${t('Hilfe', 'Help')}">?</button>`
    + `<span class="help-pop" role="tooltip">${t(de, en)}</span></span>`;
}

function placeHelp(help) {
  const btn = help.querySelector('.help-btn').getBoundingClientRect();
  const pop = help.querySelector('.help-pop');
  const m = 8;
  const w = pop.offsetWidth;
  const h = pop.offsetHeight;
  const left = Math.min(Math.max(btn.left + btn.width / 2 - w / 2, m), window.innerWidth - w - m);
  const below = btn.bottom + 6;
  const top = below + h <= window.innerHeight - m ? below : Math.max(m, btn.top - 6 - h);
  pop.style.left = `${Math.max(m, left)}px`;
  pop.style.top = `${top}px`;
}

function openHelp(help, pinned = true) {
  document.querySelectorAll('.help.open').forEach((h) => { if (h !== help) closeHelp(h); });
  help.classList.add('open');
  help.classList.toggle('pinned', pinned);
  help.querySelector('.help-btn').setAttribute('aria-expanded', 'true');
  placeHelp(help);
}

function closeHelp(help) {
  help.classList.remove('open', 'pinned');
  help.querySelector('.help-btn').setAttribute('aria-expanded', 'false');
}

function initHelp() {
  const hover = window.matchMedia('(hover: hover)');
  document.addEventListener('click', (e) => {
    const btn = e.target.closest('.help-btn');
    if (btn) {
      const help = btn.closest('.help');
      if (help.classList.contains('pinned')) closeHelp(help);
      else openHelp(help);
      return;
    }
    if (!e.target.closest('.help-pop')) document.querySelectorAll('.help.open').forEach(closeHelp);
  });
  document.addEventListener('mouseover', (e) => {
    const help = e.target.closest('.help');
    if (help && hover.matches && !help.classList.contains('open')) openHelp(help, false);
  });
  document.addEventListener('mouseout', (e) => {
    const help = e.target.closest('.help');
    if (help && !help.contains(e.relatedTarget) && !help.classList.contains('pinned')) closeHelp(help);
  });
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') document.querySelectorAll('.help.open').forEach(closeHelp);
  });
  // A fixed bubble would drift away from its button.
  window.addEventListener('scroll', () => document.querySelectorAll('.help.open').forEach(closeHelp), true);
  window.addEventListener('resize', () => document.querySelectorAll('.help.open').forEach(closeHelp));
}

/* Restores a select's last value and stores every change - the sort order
   of a page survives reloads. Call it before the page's first render. */
function rememberSelect(id, key) {
  const el = document.getElementById(id);
  if (!el) return;
  try {
    const v = localStorage.getItem(key);
    if (v !== null && [...el.options].some((o) => o.value === v)) el.value = v;
  } catch (e) {
    /* ignore */
  }
  el.addEventListener('change', () => {
    try {
      localStorage.setItem(key, el.value);
    } catch (e) {
      /* ignore */
    }
  });
}

function initShared() {
  initHelp();
  buildAdminOverlay(); // must exist before initCollectionUpload wires its buttons
  initCollectionUpload();
  initNavToggle();
  uiConfigReady.then(applyNavConfig); // hide disabled menu items once config is loaded
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', initShared);
} else {
  initShared();
}
