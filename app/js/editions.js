/* The table comes from /api/editions and the overlay from /api/sets/{code}/cards
   (ownership and collection rows included): the page never loads the whole
   collection, and after an edit both are fetched again cheaply. */
let editions = [];
let editionByCode = {};
// Set-type filter state. mode: 'collectable' | 'all' | 'custom'; types = chosen set-types.
let typeState = { mode: 'collectable', types: new Set() };

// Set types considered "collectable" editions – the default view. Other types
// (promo, token, …) are available via the set-type filter.
const COLLECTABLE_SET_TYPES = new Set([
  'core', 'expansion', 'masters', 'draft_innovation', 'commander', 'remastered',
]);

const SET_TYPE_LABEL = {
  core: t('Core', 'Core'),
  expansion: t('Erweiterung', 'Expansion'),
  masters: 'Masters',
  draft_innovation: t('Draft-Innovation', 'Draft innovation'),
  commander: 'Commander',
  remastered: 'Remastered',
  promo: 'Promo',
  token: 'Token',
  memorabilia: 'Memorabilia',
  duel_deck: 'Duel Deck',
  box: t('Box-Set', 'Box set'),
  funny: t('Spaß-Set', 'Funny'),
  masterpiece: 'Masterpiece',
  minigame: 'Minigame',
  starter: 'Starter',
  from_the_vault: 'From the Vault',
  planechase: 'Planechase',
  eternal: 'Eternal',
  archenemy: 'Archenemy',
  arsenal: 'Arsenal',
  premium_deck: 'Premium Deck',
  spellbook: 'Spellbook',
  vanguard: 'Vanguard',
  alchemy: 'Alchemy',
};

function setTypeLabel(type) {
  return SET_TYPE_LABEL[type] || type || '–';
}

async function loadEditions() {
  await uiConfigReady;
  const res = await fetch(API + '/editions', { cache: 'no-cache' });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const list = await res.json();
  editions = list.map((e) => ({
    ...e,
    icon: setSymbolURL(e.iconSvgUri),
    released: e.releasedAt || '',
  }));
  editionByCode = {};
  editions.forEach((e) => (editionByCode[e.code] = e));
}

function renderStats() {
  const inColl = editions.filter((e) => e.inCollection);
  const complete = inColl.filter((e) => e.total != null && e.missing === 0).length;
  const totalOwned = editions.reduce((a, e) => a + e.owned, 0);
  const totalDupeCards = editions.reduce((a, e) => a + e.dupeCount, 0);

  document.getElementById('stats-bar').innerHTML = `
    <div class="stat-tile"><div class="stat-value">${inColl.length}</div><div class="stat-label">${t('Gesammelte Editionen', 'Collected editions')}</div></div>
    <div class="stat-tile"><div class="stat-value">${totalOwned.toLocaleString('de-DE')}</div><div class="stat-label">${t('Verschiedene Karten', 'Distinct cards')}</div></div>
    <div class="stat-tile"><div class="stat-value">${complete}</div><div class="stat-label">${t('Komplette Editionen', 'Complete editions')}</div></div>
    <div class="stat-tile"><div class="stat-value">${totalDupeCards.toLocaleString('de-DE')}</div><div class="stat-label">${t('Karten mehrfach vorhanden', 'Cards owned multiple times')}</div></div>
  `;
}

function sortEditions(list, sortBy) {
  const arr = list.slice();
  arr.sort((a, b) => {
    switch (sortBy) {
      case 'name':
        return a.name.localeCompare(b.name);
      case 'owned-desc':
        return b.owned - a.owned || a.name.localeCompare(b.name);
      case 'missing-desc':
        return (b.missing ?? -1) - (a.missing ?? -1) || a.name.localeCompare(b.name);
      case 'missing-asc':
        return (a.missing ?? Infinity) - (b.missing ?? Infinity) || a.name.localeCompare(b.name);
      case 'complete-desc': {
        const pa = a.total ? a.owned / a.total : -1;
        const pb = b.total ? b.owned / b.total : -1;
        return pb - pa || a.name.localeCompare(b.name);
      }
      case 'dupes-desc':
        return b.dupeCount - a.dupeCount || a.name.localeCompare(b.name);
      case 'released-desc':
        return (b.released || '').localeCompare(a.released || '') || a.name.localeCompare(b.name);
      case 'released-asc':
        return (a.released || '9999').localeCompare(b.released || '9999') || a.name.localeCompare(b.name);
      default: // value-desc
        return b.marketValue - a.marketValue || a.name.localeCompare(b.name);
    }
  });
  return arr;
}

function progressCell(e) {
  if (e.total == null) {
    return `
      <div class="progress-cell">
        <div class="progress-head"><strong>${e.owned}</strong> <span class="dim">${t('Karten', 'cards')}</span></div>
        <div class="progress-sub dim">${t('Set-Größe unbekannt', 'Set size unknown')}</div>
      </div>`;
  }
  const pct = e.total ? Math.min(100, Math.round((e.owned / e.total) * 100)) : 0;
  return `
    <div class="progress-cell">
      <div class="progress-head"><strong>${e.owned}</strong> <span class="dim">${t('von', 'of')} ${e.total}</span> <span class="progress-pct">${pct}%</span></div>
      <div class="progress-track"><div class="progress-fill" style="width:${pct}%"></div></div>
    </div>`;
}

/* Fills the set-type dropdown: "collectable" (default) + "all" + every type present. */
function allSetTypes() {
  return [...new Set(editions.map((e) => e.setType).filter(Boolean))].sort((a, b) =>
    setTypeLabel(a).localeCompare(setTypeLabel(b))
  );
}

/* Which set-types are currently effectively selected (for the checkbox state). */
function effectiveSelectedTypes() {
  if (typeState.mode === 'all') return new Set(allSetTypes());
  if (typeState.mode === 'collectable') return new Set(allSetTypes().filter((ty) => COLLECTABLE_SET_TYPES.has(ty)));
  return typeState.types;
}

function updateTypeLabel() {
  const label = document.getElementById('type-label');
  if (typeState.mode === 'collectable') label.textContent = t('Sammelbare Typen', 'Collectable types');
  else if (typeState.mode === 'all') label.textContent = t('Alle Typen', 'All types');
  else {
    const n = typeState.types.size;
    if (n === 0) label.textContent = t('Keine Typen', 'No types');
    else if (n === 1) label.textContent = setTypeLabel([...typeState.types][0]);
    else label.textContent = t(`${n} Typen`, `${n} types`);
  }
}

/* Reflect the current selection in the checkboxes. */
function syncTypeCheckboxes() {
  const sel = effectiveSelectedTypes();
  document.querySelectorAll('#type-options input[type="checkbox"]').forEach((b) => (b.checked = sel.has(b.value)));
}

const EDITIONS_TYPE_KEY = 'mtg_editions_type';

function saveTypeState() {
  try {
    localStorage.setItem(EDITIONS_TYPE_KEY, JSON.stringify({ mode: typeState.mode, types: [...typeState.types] }));
  } catch (e) {
    /* ignore */
  }
}

function loadTypeState() {
  try {
    const raw = localStorage.getItem(EDITIONS_TYPE_KEY);
    if (raw) {
      const o = JSON.parse(raw);
      if (o && (o.mode === 'collectable' || o.mode === 'all' || o.mode === 'custom')) {
        return { mode: o.mode, types: new Set(Array.isArray(o.types) ? o.types : []) };
      }
    }
  } catch (e) {
    /* ignore */
  }
  return { mode: 'collectable', types: new Set() };
}

function populateTypeFilter() {
  const opts = document.getElementById('type-options');
  opts.innerHTML = allSetTypes()
    .map(
      (ty) =>
        `<label class="ms-check"><input type="checkbox" value="${escapeHTML(ty)}"> ${escapeHTML(setTypeLabel(ty))}</label>`
    )
    .join('');

  // Presets keep the exact previous behaviour.
  document.querySelectorAll('#type-panel .ms-preset').forEach((btn) =>
    btn.addEventListener('click', () => {
      typeState = { mode: btn.dataset.preset, types: new Set() };
      syncTypeCheckboxes();
      updateTypeLabel();
      saveTypeState();
      renderTable();
    })
  );

  // Individual checkboxes → custom multi-selection.
  opts.querySelectorAll('input[type="checkbox"]').forEach((b) =>
    b.addEventListener('change', () => {
      typeState = {
        mode: 'custom',
        types: new Set([...opts.querySelectorAll('input:checked')].map((c) => c.value)),
      };
      updateTypeLabel();
      saveTypeState();
      renderTable();
    })
  );

  // Dropdown open/close.
  const wrap = document.getElementById('type-filter');
  const toggle = document.getElementById('type-toggle');
  const panel = document.getElementById('type-panel');
  const setOpen = (open) => {
    panel.hidden = !open;
    toggle.setAttribute('aria-expanded', open ? 'true' : 'false');
  };
  toggle.addEventListener('click', (e) => {
    e.stopPropagation();
    setOpen(panel.hidden);
  });
  document.addEventListener('click', (e) => {
    if (!wrap.contains(e.target)) setOpen(false);
  });

  typeState = loadTypeState();
  syncTypeCheckboxes();
  updateTypeLabel();
}

function renderTable() {
  const q = document.getElementById('search').value.trim().toLowerCase();
  const sortBy = document.getElementById('sort-by').value;
  const ownedFilter = document.getElementById('filter-owned').value;

  let list = editions;
  if (typeState.mode === 'collectable') list = list.filter((e) => COLLECTABLE_SET_TYPES.has(e.setType) || e.inCollection);
  else if (typeState.mode === 'custom') list = list.filter((e) => typeState.types.has(e.setType));
  // mode 'all' → no type restriction
  if (ownedFilter === 'owned') list = list.filter((e) => e.inCollection);
  else if (ownedFilter === 'missing') list = list.filter((e) => !e.inCollection);
  if (q) list = list.filter((e) => e.name.toLowerCase().includes(q) || e.code.toLowerCase().includes(q));
  list = sortEditions(list, sortBy);

  document.getElementById('result-count').textContent = `${list.length} ${t('von', 'of')} ${editions.length} ${t('Editionen', 'editions')}`;

  const rows = list
    .map((e) => {
      const missingCell =
        e.missing == null
          ? `<span class="dim">${t('unbekannt', 'unknown')}</span>`
          : e.missing === 0
          ? `<span class="status-owned">✓ ${t('komplett', 'complete')}</span>`
          : `<strong>${e.missing.toLocaleString('de-DE')}</strong> <span class="dim">${t('fehlen', 'missing')}</span>`;
      const dupeCell = e.dupeCount
        ? `<strong>${e.dupeCount}</strong> <span class="dim">${t('Karten', 'cards')} (+${e.dupeExtra} ${t('Ex.', 'cp.')})</span>`
        : '<span class="dim">–</span>';
      return `
        <tr class="edition-row${e.inCollection ? '' : ' edition-row--empty'}" data-code="${escapeHTML(e.code)}" title="${t('Alle Karten der Edition anzeigen', 'Show all cards of the edition')}">
          <td>
            <div class="edition-name">
              ${e.icon
                ? `<img class="set-icon" src="${escapeHTML(e.icon)}" alt="" loading="lazy">`
                : `<span class="edition-icon">${e.inCollection ? '🗂️' : '⬚'}</span>`}
              <span class="edition-title">${escapeHTML(e.name)}</span>
              <span class="edition-code">${escapeHTML(e.code)}</span>
            </div>
          </td>
          <td>${progressCell(e)}</td>
          <td class="num">${missingCell}</td>
          <td class="num">${dupeCell}</td>
          <td class="num">${e.totalCopies.toLocaleString('de-DE')}</td>
          <td class="num money">${formatCurrency(e.marketValue, 'EUR')}</td>
        </tr>`;
    })
    .join('');

  document.getElementById('editions-table').innerHTML = `
    <table class="edition-table">
      <thead>
        <tr>
          <th>${t('Edition', 'Edition')}</th>
          <th>${t('Vollständigkeit', 'Completion')}</th>
          <th class="num">${t('Fehlende Karten', 'Missing cards')}</th>
          <th class="num">${t('Mehrfach', 'Duplicates')}</th>
          <th class="num">${t('Exemplare gesamt', 'Copies total')}</th>
          <th class="num">${t('Marktwert', 'Market value')}</th>
        </tr>
      </thead>
      <tbody>${rows}</tbody>
    </table>`;
}

/* ---- Edition overlay: all cards with images, missing ones greyed out ---- */

let overlayCards = [];
let overlayEdition = null;

const RARITY_LABEL = { common: 'Common', uncommon: 'Uncommon', rare: 'Rare', mythic: 'Mythic', special: 'Special', bonus: 'Bonus' };
const RARITY_ORDER = ['common', 'uncommon', 'rare', 'mythic', 'special', 'bonus'];

function cardMarketPrice(c) {
  if (c.priceEur) return parseFloat(c.priceEur);
  if (c.priceEurFoil) return parseFloat(c.priceEurFoil);
  return 0;
}

function overlayRarity() {
  const sel = document.getElementById('eo-rarity');
  return sel ? sel.value : '';
}

/* Owned/total/missing + missing value, restricted to the selected rarity. */
function overlayStats(rarity) {
  let owned = 0;
  let total = 0;
  let missingValue = 0;
  overlayCards.forEach((c) => {
    if (rarity && (c.rarity || '').toLowerCase() !== rarity) return;
    total++;
    if (c.owned) owned++;
    else missingValue += cardMarketPrice(c);
  });
  return { owned, total, missing: total - owned, missingValue };
}

function populateOverlayRarities() {
  const sel = document.getElementById('eo-rarity');
  const present = [...new Set(overlayCards.map((c) => (c.rarity || '').toLowerCase()).filter(Boolean))];
  present.sort((a, b) => {
    const ia = RARITY_ORDER.indexOf(a);
    const ib = RARITY_ORDER.indexOf(b);
    return (ia < 0 ? 99 : ia) - (ib < 0 ? 99 : ib);
  });
  sel.innerHTML =
    `<option value="">${t('Alle Raritäten', 'All rarities')}</option>` +
    present.map((r) => `<option value="${escapeHTML(r)}">${escapeHTML(RARITY_LABEL[r] || r)}</option>`).join('');
  sel.value = '';
}

/* Recomputes the header line for the current rarity selection. */
function updateOverlaySub() {
  const { owned, total, missing, missingValue } = overlayStats(overlayRarity());
  const pct = total ? Math.round((owned / total) * 100) : 0;
  document.getElementById('eo-sub').innerHTML =
    `<strong>${owned}</strong> ${t('von', 'of')} <strong>${total}</strong> ${t('Karten', 'cards')} (${pct}%) · ` +
    `<span class="eo-sub-missing">${t(`${missing} fehlen für ${formatCurrency(missingValue, 'EUR')}`, `${missing} missing worth ${formatCurrency(missingValue, 'EUR')}`)}</span>`;
}

function buildEoTile({ card: c, idx, ownedInfo, isOwned }) {
  const tile = document.createElement('div');
  tile.className = 'eo-card ' + (isOwned ? 'owned' : 'missing') + (eoSelected.has(c.collectorNumber) ? ' selected' : '');
  tile.dataset.idx = idx;
  const img = setCardImage(c, 'small');
  tile.innerHTML = `
    <div class="eo-thumb">
      ${img ? `<img loading="lazy" src="${img}" alt="${escapeHTML(c.name)}">` : `<div class="no-image">${t('Kein Bild', 'No image')}</div>`}
      ${isOwned && ownedInfo.copies > 1 ? `<span class="eo-qty">×${ownedInfo.copies}</span>` : ''}
      ${isOwned ? '<span class="eo-check">✓</span>' : `<span class="eo-missing-tag">${t('fehlt', 'missing')}</span>`}
      ${wishButtonHTML(c.name, 'on-thumb')}
    </div>
    <div class="eo-cardname"><span class="eo-num">#${escapeHTML(c.collectorNumber)}</span> ${escapeHTML(c.name)}</div>
  `;
  return tile;
}

function renderOverlayGrid() {
  const filter = document.querySelector('input[name="eo-filter"]:checked').value;
  const rarity = overlayRarity();
  const grid = document.getElementById('eo-grid');

  const items = [];
  overlayCards.forEach((c, idx) => {
    if (rarity && (c.rarity || '').toLowerCase() !== rarity) return;
    const ownedInfo = c.owned;
    const isOwned = !!ownedInfo;
    if (filter === 'owned' && !isOwned) return;
    if (filter === 'missing' && isOwned) return;
    items.push({ card: c, idx, ownedInfo, isOwned });
  });

  if (!items.length) {
    stopIncremental(grid);
    grid.innerHTML = `<p class="eo-empty">${t('Keine Karten für diesen Filter.', 'No cards for this filter.')}</p>`;
    return;
  }
  renderIncremental(grid, items, buildEoTile);
}

async function openEditionOverlay(edition) {
  overlayEdition = edition;
  overlayCards = [];
  const overlay = document.getElementById('edition-overlay');
  const iconEl = document.getElementById('eo-icon');
  if (edition.icon) {
    iconEl.src = edition.icon;
    iconEl.style.display = '';
  } else {
    iconEl.style.display = 'none';
  }
  document.getElementById('eo-title').textContent = edition.name;
  document.getElementById('eo-code').textContent = edition.code;
  document.getElementById('eo-sub').textContent = t('Lade Karten von Scryfall…', 'Loading cards from Scryfall…');
  const grid = document.getElementById('eo-grid');
  stopIncremental(grid);
  grid.innerHTML = `<div class="eo-loading">${t('Lade Kartenbilder…', 'Loading card images…')}</div>`;
  document.querySelector('input[name="eo-filter"][value="all"]').checked = true;
  overlay.classList.add('open');
  document.body.style.overflow = 'hidden';

  try {
    overlayCards = await fetchSetCards(edition.code);
  } catch (e) {
    document.getElementById('eo-sub').textContent = '';
    stopIncremental(grid);
    grid.innerHTML = `<p class="eo-empty" style="color:#e05656">${t('Konnte Karten nicht laden', 'Could not load cards')}: ${escapeHTML(e.message)}</p>`;
    return;
  }
  // Overlay was closed again while loading.
  if (!overlay.classList.contains('open') || overlayEdition !== edition) return;

  if (!overlayCards.length) {
    document.getElementById('eo-sub').textContent = '';
    stopIncremental(grid);
    grid.innerHTML = `<p class="eo-empty">${t('Keine Kartenliste für diese Edition gefunden.', 'No card list found for this edition.')}</p>`;
    return;
  }

  populateOverlayRarities();
  updateOverlaySub();
  renderOverlayGrid();
}

function closeEditionOverlay() {
  setEoSelecting(false);
  document.getElementById('edition-overlay').classList.remove('open');
  document.body.style.overflow = '';
  overlayEdition = null;
}

/* ---- Detail overlay for a single card (opened from the edition overlay) ---- */

function editionCardDetailHTML(card, ownedInfo) {
  const img = setCardImage(card, 'normal');
  const backImg = setCardImage(card, 'normal', 'back');
  const price = card.priceEur ? formatCurrency(parseFloat(card.priceEur), 'EUR') : '–';
  const foilPrice = card.priceEurFoil ? formatCurrency(parseFloat(card.priceEurFoil), 'EUR') : null;

  let ownership;
  if (isUnlocked) {
    ownership = ownershipEditHTML(card);
  } else if (ownedInfo) {
    const rows = Object.entries(ownedInfo.byLang)
      .sort((a, b) => b[1] - a[1])
      .map(([l, n]) => `<tr><td>${languageFlag(l) || escapeHTML(l.toUpperCase())} ${escapeHTML(l.toUpperCase())}</td><td class="num">${n}</td></tr>`)
      .join('');
    const binders = Object.entries(ownedInfo.byBinder || {})
      .sort((a, b) => b[1] - a[1])
      .map(([n, q]) => `${escapeHTML(n)} (${q})`)
      .join(', ');
    ownership = `
      <h3 class="lang-table-title">${t('In deiner Sammlung', 'In your collection')} (${ownedInfo.copies}×)</h3>
      <table class="lang-table">
        <thead><tr><th>${t('Sprache', 'Language')}</th><th class="num">${t('Anzahl', 'Quantity')}</th></tr></thead>
        <tbody>${rows}</tbody>
      </table>
      ${binders ? `<p class="eo-binders"><strong>${t('Ordner', 'Folder')}:</strong> ${binders}</p>` : ''}`;
  } else {
    ownership = `<p class="eo-detail-missing">${t('Diese Karte fehlt dir noch.', 'You don’t own this card yet.')}</p>`;
  }

  return `
    <span class="modal-close eo-card-close">&times;</span>
    <div class="modal-card">
      <div class="modal-image">
        ${cardFaceHTML(card.name, img, backImg)}
      </div>
      <div class="modal-details">
        <h2>${escapeHTML(card.name)}</h2>
        <p class="modal-type">${escapeHTML(card.typeLine || '')}</p>
        <dl>
          <dt>${t('Nummer', 'Number')}</dt><dd>#${escapeHTML(card.collectorNumber)}</dd>
          <dt>${t('Rarität', 'Rarity')}</dt><dd>${escapeHTML(card.rarity || '')}</dd>
          <dt title="${priceHint()}">${t('Marktwert', 'Market value')}</dt><dd title="${priceHint()}">${price}</dd>
          ${foilPrice ? `<dt title="${priceHint()}">${t('Marktwert (Foil)', 'Market value (foil)')}</dt><dd title="${priceHint()}">${foilPrice}</dd>` : ''}
        </dl>
        ${
          card.cardmarketUri
            ? `<a class="cardmarket-link" href="${escapeHTML(card.cardmarketUri)}" target="_blank" rel="noopener noreferrer">${t('Auf Cardmarket ansehen', 'View on Cardmarket')} ↗</a>`
            : ''
        }
        <div class="wish-line">${wishButtonHTML(card.name)}<span class="wish-line-label">${t('Wunschliste', 'Wishlist')}</span></div>
        ${ownership}
      </div>
    </div>`;
}

function openEditionCardOverlay(card, ownedInfo) {
  detailCard = card;
  const overlay = document.getElementById('eo-card-overlay');
  const body = overlay.querySelector('.modal-body');
  body.innerHTML = editionCardDetailHTML(card, ownedInfo);
  initCardFlip(body);
  overlay.classList.add('open');
}

function closeEditionCardOverlay() {
  document.getElementById('eo-card-overlay').classList.remove('open');
  detailCard = null;
}

/* ---- Editing (with write access): rows of a card, adding, moving many ---- */

let detailCard = null;      // the card whose detail dialog is open
let eoSelecting = false;
const eoSelected = new Map(); // collectorNumber -> card (owned ones only)

const ADD_LANG_KEY = 'mtg_add_lang';

function binderTypeSelectHTML(id) {
  return `<select id="${id}" title="${t('Typ, falls der Ordner neu ist', 'Type, if the folder is new')}">
    <option value="binder">${t('Ordner', 'Binder')}</option><option value="list">${t('Liste', 'List')}</option><option value="deck">Deck</option></select>`;
}

/* The rows of a card with −1 / +1 / folder, and a line to add copies - also
   for cards that are still missing. */
function ownershipEditHTML(card) {
  const o = card.owned;
  const rows = (o ? o.rows || [] : [])
    .slice()
    .sort((a, b) => (a.language || '').localeCompare(b.language || '') || (a.foil || '').localeCompare(b.foil || ''))
    .map((r) => {
      const foilTag = r.foil && r.foil !== 'normal' ? ` <span class="lang-foil">${escapeHTML(r.foil)}</span>` : '';
      return `
        <tr>
          <td>${languageFlag(r.language)} ${escapeHTML((r.language || '?').toUpperCase())}${foilTag}</td>
          <td>${escapeHTML(conditionLabel(r.condition))}</td>
          <td>${escapeHTML(r.binderName || '–')}</td>
          <td class="num">${r.quantity}</td>
          <td class="row-actions">
            <button type="button" data-act="minus" data-id="${r.id}" title="${t('Ein Exemplar weniger', 'One copy less')}">−1</button>
            <button type="button" data-act="plus" data-id="${r.id}" title="${t('Ein Exemplar mehr', 'One copy more')}">+1</button>
            <button type="button" data-act="move" data-id="${r.id}">${t('Ordner…', 'Folder…')}</button>
          </td>
        </tr>`;
    })
    .join('');
  let lang = 'en';
  try { lang = localStorage.getItem(ADD_LANG_KEY) || 'en'; } catch (e) { /* ignore */ }
  const langOpts = CARD_LANGS.map((l) => `<option value="${l}"${l === lang ? ' selected' : ''}>${languageFlagText(l)} ${l.toUpperCase()}</option>`).join('');
  return `
    ${o
      ? `<h3 class="lang-table-title">${t('In deiner Sammlung', 'In your collection')} (${o.copies}×)</h3>
         <table class="lang-table">
           <thead><tr><th>${t('Sprache', 'Language')}</th><th>${t('Zustand', 'Condition')}</th><th>${t('Ordner', 'Folder')}</th><th class="num">${t('Anzahl', 'Quantity')}</th><th></th></tr></thead>
           <tbody>${rows}</tbody>
         </table>
         <div class="row-edit" id="row-edit"></div>`
      : `<p class="eo-detail-missing">${t('Diese Karte fehlt dir noch.', 'You don’t own this card yet.')}</p>`}
    <h3 class="lang-table-title">${t('Zur Sammlung hinzufügen', 'Add to collection')}</h3>
    <div class="row-edit">
      <select id="add-lang">${langOpts}</select>
      <select id="add-foil"><option value="normal">${t('Normal', 'Normal')}</option><option value="foil">Foil</option><option value="etched">Etched</option></select>
      ${binderInputHTML('add-binder', '', [overlayEdition.code])}
      ${binderTypeSelectHTML('add-binder-type')}
      <button type="button" class="primary" data-act="add">+1 ${t('hinzufügen', 'add')}</button>
    </div>`;
}

const VOLATILE_HINT = () => t('Karten aus ManaBox kommen beim nächsten Import wieder in ihren alten Zustand.', 'Cards from ManaBox return to their old state with the next import.');

async function editSetRow(act, id) {
  const card = detailCard;
  if (!card || !overlayEdition) return;
  const row = card.owned && (card.owned.rows || []).find((r) => r.id === id);
  try {
    if (act === 'add') {
      const lang = document.getElementById('add-lang').value;
      try { localStorage.setItem(ADD_LANG_KEY, lang); } catch (e) { /* ignore */ }
      const binder = binderValue('add-binder');
      if (binder === null) return showNotice(t('Bitte einen Namen für den neuen Ordner eingeben.', 'Please enter a name for the new folder.'), true);
      await writeAPI('POST', '/collection/cards', { items: [{
        setCode: overlayEdition.code.toLowerCase(), collectorNumber: card.collectorNumber, quantity: 1,
        foil: document.getElementById('add-foil').value, language: lang, condition: 'near_mint',
        binderName: binder, binderType: binder ? document.getElementById('add-binder-type').value : '',
      }] });
      bindersCache = null;
      showNotice(t(`${card.name} hinzugefügt.`, `${card.name} added.`));
    } else if (!row) {
      return;
    } else if (act === 'minus') {
      await writeAPI('PATCH', `/collection/cards/${id}`, { quantityDelta: -1 });
      if (row.origin === 'csv') showNotice(t('Geändert – aber das ist eine ManaBox-Zeile: der nächste Import setzt sie zurück.', 'Changed – but this is a ManaBox row: the next import resets it.'));
    } else if (act === 'plus') {
      await writeAPI('POST', '/collection/cards', { items: [{
        // The catalog's number: the row's may carry leading zeros.
        setCode: overlayEdition.code.toLowerCase(), collectorNumber: card.collectorNumber, quantity: 1,
        foil: row.foil || 'normal', language: row.language || 'en', condition: row.condition || 'near_mint',
        binderName: row.binderName || '', binderType: row.binderName ? (row.binderType || 'binder') : '',
      }] });
    } else if (act === 'move') {
      await ensureBinderDatalist();
      const box = document.getElementById('row-edit');
      box.innerHTML = `
        <span>${t('Verschieben nach', 'Move to')}:</span>
        ${binderInputHTML('row-binder', row.binderName, [overlayEdition.code])}
        ${binderTypeSelectHTML('row-binder-type')}
        <button type="button" class="primary" data-act="move-ok" data-id="${id}">OK</button>`;
      document.getElementById('row-binder').focus();
      return;
    } else if (act === 'move-ok') {
      const name = binderValue('row-binder');
      if (name === null) return showNotice(t('Bitte einen Namen für den neuen Ordner eingeben.', 'Please enter a name for the new folder.'), true);
      if (name === (row.binderName || '')) return;
      await writeAPI('PATCH', `/collection/cards/${id}`, { binderName: name, binderType: document.getElementById('row-binder-type').value });
      bindersCache = null;
      if (row.origin === 'csv') showNotice(t('Verschoben – ', 'Moved – ') + VOLATILE_HINT());
    } else {
      return;
    }
    await refreshAfterEdit();
  } catch (e) {
    showNotice(t('Ändern fehlgeschlagen: ', 'Change failed: ') + e.message, true);
  }
}

/* After a write: the set's cards (ownership, rows) and the table anew; the
   open detail follows its card. */
async function refreshAfterEdit() {
  const edition = overlayEdition;
  if (!edition) return;
  delete setCardsCache[edition.code.toLowerCase()];
  try {
    overlayCards = await fetchSetCards(edition.code);
  } catch (e) { /* keep the old list */ }
  if (overlayEdition !== edition) return;
  updateOverlaySub();
  renderOverlayGrid();
  if (detailCard) {
    const fresh = overlayCards.find((c) => c.collectorNumber === detailCard.collectorNumber);
    if (fresh) openEditionCardOverlay(fresh, fresh.owned);
  }
  try {
    await loadEditions();
    overlayEdition = editionByCode[edition.code] || edition;
    renderStats();
    renderTable();
  } catch (e) { /* the table stays as it was */ }
}

function setEoSelecting(on) {
  eoSelecting = on;
  if (!on) {
    eoSelected.clear();
    document.querySelectorAll('#eo-grid .eo-card.selected').forEach((t) => t.classList.remove('selected'));
  }
  document.body.classList.toggle('selecting', on);
  const btn = document.getElementById('eo-select-mode');
  if (btn) btn.textContent = on ? t('Auswahl beenden', 'Stop selecting') : t('Auswählen', 'Select');
  renderEoSelectBar();
}

function toggleEoSelected(card, tile) {
  // Missing cards have no rows to move.
  if (!card.owned) return;
  if (eoSelected.has(card.collectorNumber)) eoSelected.delete(card.collectorNumber);
  else eoSelected.set(card.collectorNumber, card);
  tile.classList.toggle('selected', eoSelected.has(card.collectorNumber));
  renderEoSelectBar();
}

function eoSelectedRows() {
  const ids = [];
  let copies = 0;
  eoSelected.forEach((c) => ((c.owned && c.owned.rows) || []).forEach((r) => {
    ids.push(r.id);
    copies += r.quantity;
  }));
  return { ids, copies };
}

function renderEoSelectBar() {
  let bar = document.getElementById('select-bar');
  if (!eoSelecting) {
    if (bar) bar.remove();
    return;
  }
  if (!bar) {
    bar = document.createElement('div');
    bar.id = 'select-bar';
    bar.className = 'select-bar';
    bar.innerHTML = `
      <span class="select-count" id="select-count"></span>
      <button type="button" id="select-all">${t('Alle besessenen', 'All owned')}</button>
      <button type="button" id="select-none">${t('Keine', 'None')}</button>
      ${binderInputHTML('select-binder', '', [overlayEdition && overlayEdition.code])}
      ${binderTypeSelectHTML('select-binder-type')}
      <button type="button" class="primary" id="select-move">${t('Verschieben', 'Move')}</button>`;
    document.body.appendChild(bar);
    bar.querySelector('#select-all').addEventListener('click', () => {
      // What the grid shows right now (filter and rarity apply).
      document.querySelectorAll('#eo-grid .eo-card.owned').forEach((tile) => {
        const c = overlayCards[tile.dataset.idx];
        if (c && c.owned) {
          eoSelected.set(c.collectorNumber, c);
          tile.classList.add('selected');
        }
      });
      renderEoSelectBar();
    });
    bar.querySelector('#select-none').addEventListener('click', () => {
      eoSelected.clear();
      document.querySelectorAll('#eo-grid .eo-card.selected').forEach((t) => t.classList.remove('selected'));
      renderEoSelectBar();
    });
    bar.querySelector('#select-move').addEventListener('click', moveEoSelection);
    ensureBinderDatalist();
  }
  const { copies } = eoSelectedRows();
  bar.querySelector('#select-count').textContent =
    t(`${eoSelected.size} ausgewählt (${copies} Karten)`, `${eoSelected.size} selected (${copies} cards)`);
  const ownedShown = document.querySelectorAll('#eo-grid .eo-card.owned').length;
  bar.querySelector('#select-all').disabled = ownedShown === 0 || document.querySelectorAll('#eo-grid .eo-card.owned.selected').length >= ownedShown;
  bar.querySelector('#select-none').disabled = eoSelected.size === 0;
  bar.querySelector('#select-move').disabled = copies === 0;
  if (overlayEdition) setBinderSets('select-binder', [overlayEdition.code]);
}

async function moveEoSelection() {
  const { ids } = eoSelectedRows();
  if (!ids.length) return;
  const name = binderValue('select-binder');
  if (name === null) return showNotice(t('Bitte einen Namen für den neuen Ordner eingeben.', 'Please enter a name for the new folder.'), true);
  const type = document.getElementById('select-binder-type').value;
  const btn = document.getElementById('select-move');
  btn.disabled = true;
  try {
    const r = await writeAPI('POST', '/collection/move', { ids, binderName: name, binderType: type });
    let msg = t(`${r.moved} Zeilen nach „${r.binderName || 'ohne Ordner'}“ verschoben.`,
      `${r.moved} rows moved to “${r.binderName || 'no folder'}”.`);
    if (r.merged) msg += ' ' + t(`${r.merged} davon zusammengeführt.`, `${r.merged} of them merged.`);
    if (r.volatile) msg += ' ' + VOLATILE_HINT();
    showNotice(msg);
    bindersCache = null;
    setEoSelecting(false);
    await refreshAfterEdit();
  } catch (e) {
    showNotice(t('Verschieben fehlgeschlagen: ', 'Moving failed: ') + e.message, true);
    btn.disabled = false;
  }
}

async function init() {
  rememberSelect('sort-by', 'mtg_sort_editions');
  showLoading(t('Lade Editionen...', 'Loading editions...'));
  try {
    await loadEditions();
    await loadWishlistNames();
  } catch (e) {
    hideLoading();
    document.getElementById('editions-table').innerHTML = `<p style="color:#e05656">${t('Fehler beim Laden', 'Error loading')}: ${escapeHTML(e.message)}</p>`;
    return;
  }
  hideLoading();

  populateTypeFilter();
  renderStats();
  renderTable();

  document.getElementById('search').addEventListener('input', debounce(renderTable, 150));
  document.getElementById('sort-by').addEventListener('change', renderTable);
  document.getElementById('filter-owned').addEventListener('change', renderTable);
  // The type multi-select wires its own listeners in populateTypeFilter().

  document.getElementById('editions-table').addEventListener('click', (e) => {
    const row = e.target.closest('.edition-row');
    if (row) openEditionOverlay(editionByCode[row.dataset.code]);
  });

  const overlay = document.getElementById('edition-overlay');
  overlay.addEventListener('click', (e) => {
    if (e.target === overlay || e.target.classList.contains('eo-close')) closeEditionOverlay();
  });
  initWishButtons(document.getElementById('eo-grid'));
  initWishButtons(document.getElementById('eo-card-overlay'));
  document.getElementById('eo-grid').addEventListener('click', (e) => {
    const tile = e.target.closest('.eo-card');
    if (!tile || e.target.closest('.wish-btn')) return;
    const card = overlayCards[tile.dataset.idx];
    if (!card) return;
    if (eoSelecting) {
      toggleEoSelected(card, tile);
      return;
    }
    openEditionCardOverlay(card, card.owned);
  });
  document.getElementById('eo-select-mode').addEventListener('click', () => setEoSelecting(!eoSelecting));
  document.getElementById('eo-card-overlay').addEventListener('click', (e) => {
    const btn = e.target.closest('[data-act]');
    if (btn) editSetRow(btn.dataset.act, Number(btn.dataset.id || 0));
  });
  document.getElementById('eo-card-overlay').addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && (e.target.id === 'row-binder' || e.target.id === 'row-binder-new')) {
      const ok = document.querySelector('#row-edit [data-act="move-ok"]');
      if (ok) ok.click();
    }
  });
  // Logged in or out: the detail shows or hides its edit controls.
  document.addEventListener('auth-change', (e) => {
    if (!e.detail.unlocked) setEoSelecting(false);
    if (detailCard) openEditionCardOverlay(detailCard, detailCard.owned);
  });

  const cardOverlay = document.getElementById('eo-card-overlay');
  cardOverlay.addEventListener('click', (e) => {
    if (e.target === cardOverlay || e.target.classList.contains('eo-card-close')) closeEditionCardOverlay();
  });

  document.addEventListener('keydown', (e) => {
    if (e.key !== 'Escape') return;
    if (cardOverlay.classList.contains('open')) closeEditionCardOverlay();
    else if (eoSelecting) setEoSelecting(false);
    else closeEditionOverlay();
  });
  document.querySelectorAll('input[name="eo-filter"]').forEach((r) => r.addEventListener('change', renderOverlayGrid));
  document.getElementById('eo-rarity').addEventListener('change', () => {
    updateOverlaySub();
    renderOverlayGrid();
  });
}

init();
