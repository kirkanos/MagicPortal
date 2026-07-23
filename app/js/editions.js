let allCards = [];
let setIndex = {};
let editions = [];
let editionByCode = {};

function priceEur(c) {
  return c.scryfall && c.scryfall.priceEur ? parseFloat(c.scryfall.priceEur) : 0;
}

/* Collector number is language- and finish-independent, so the same card in
   DE + EN (or normal + foil) collapses to one "verschiedene Karte". */
function normNumber(n) {
  return String(n || '').trim().toLowerCase().replace(/^0+(?=\d)/, '');
}

function distinctKey(c) {
  return c.collectorNumber ? 'n:' + normNumber(c.collectorNumber) : 'name:' + c.name.toLowerCase();
}

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

function buildEditions(cards) {
  // Group owned rows by set code (lowercased, to match the Scryfall index).
  const owned = {};
  cards.forEach((c) => {
    const lc = c.setCode.toLowerCase();
    if (!owned[lc]) owned[lc] = { code: c.setCode, name: c.setName, rows: [] };
    owned[lc].rows.push(c);
  });

  // Union: every owned set + all non-digital sets from the index. The set-type
  // filter (default: collectable types) decides what is actually shown.
  const codes = new Set(Object.keys(owned));
  Object.keys(setIndex).forEach((lc) => {
    const info = setIndex[lc];
    if (owned[lc] || !info.digital) codes.add(lc);
  });

  const result = [];
  codes.forEach((lc) => {
    const info = setIndex[lc] || null;
    const o = owned[lc];
    const rows = o ? o.rows : [];

    // Collapse language/finish variants into distinct cards.
    const distinctMap = {};
    const ownedByNumber = {};
    rows.forEach((c) => {
      const id = distinctKey(c);
      if (!distinctMap[id]) {
        distinctMap[id] = { id, name: c.name, collectorNumber: c.collectorNumber, copies: 0, byLang: {}, byBinder: {}, rep: c };
      }
      const d = distinctMap[id];
      d.copies += c.quantity;
      const lang = (c.language || '?').toLowerCase();
      d.byLang[lang] = (d.byLang[lang] || 0) + c.quantity;
      if (c.binderName) d.byBinder[c.binderName] = (d.byBinder[c.binderName] || 0) + c.quantity;
      if (c.collectorNumber) ownedByNumber[normNumber(c.collectorNumber)] = d;
    });
    const distinct = Object.values(distinctMap);

    const ownedCount = distinct.length;
    const total = info ? info.cardCount : null;
    const missing = total != null ? Math.max(0, total - ownedCount) : null;
    const dupes = distinct.filter((d) => d.copies > 1);

    result.push({
      code: o ? o.code : lc.toUpperCase(),
      name: (info && info.name) || (o && o.name) || lc.toUpperCase(),
      icon: (info && info.iconSvgUri) || '',
      setType: (info && info.setType) || '',
      released: (info && info.releasedAt) || '',
      owned: ownedCount,
      total,
      missing,
      dupeCount: dupes.length,
      dupeExtra: dupes.reduce((a, d) => a + (d.copies - 1), 0),
      totalCopies: rows.reduce((a, c) => a + c.quantity, 0),
      marketValue: rows.reduce((a, c) => a + priceEur(c) * c.quantity, 0),
      ownedByNumber,
      inCollection: ownedCount > 0,
    });
  });
  return result;
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
function populateTypeFilter() {
  const sel = document.getElementById('filter-type');
  const types = [...new Set(editions.map((e) => e.setType).filter(Boolean))]
    .sort((a, b) => setTypeLabel(a).localeCompare(setTypeLabel(b)));
  sel.innerHTML =
    `<option value="collectable">${t('Sammelbare Typen', 'Collectable types')}</option>` +
    `<option value="all">${t('Alle Typen', 'All types')}</option>` +
    types.map((ty) => `<option value="${escapeHTML(ty)}">${escapeHTML(setTypeLabel(ty))}</option>`).join('');
  sel.value = 'collectable';
}

function renderTable() {
  const q = document.getElementById('search').value.trim().toLowerCase();
  const sortBy = document.getElementById('sort-by').value;
  const ownedFilter = document.getElementById('filter-owned').value;
  const typeFilter = document.getElementById('filter-type').value;

  let list = editions;
  if (typeFilter === 'collectable') list = list.filter((e) => COLLECTABLE_SET_TYPES.has(e.setType) || e.inCollection);
  else if (typeFilter !== 'all') list = list.filter((e) => e.setType === typeFilter);
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
    if (overlayEdition.ownedByNumber[normNumber(c.collectorNumber)]) owned++;
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

function renderOverlayGrid() {
  const filter = document.querySelector('input[name="eo-filter"]:checked').value;
  const rarity = overlayRarity();
  const grid = document.getElementById('eo-grid');
  const frag = document.createDocumentFragment();

  overlayCards.forEach((c, idx) => {
    if (rarity && (c.rarity || '').toLowerCase() !== rarity) return;
    const ownedInfo = overlayEdition.ownedByNumber[normNumber(c.collectorNumber)];
    const isOwned = !!ownedInfo;
    if (filter === 'owned' && !isOwned) return;
    if (filter === 'missing' && isOwned) return;

    const tile = document.createElement('div');
    tile.className = 'eo-card ' + (isOwned ? 'owned' : 'missing');
    tile.dataset.idx = idx;
    const img = c.imageSmall || c.image;
    tile.innerHTML = `
      <div class="eo-thumb">
        ${img ? `<img loading="lazy" src="${img}" alt="${escapeHTML(c.name)}">` : `<div class="no-image">${t('Kein Bild', 'No image')}</div>`}
        ${isOwned && ownedInfo.copies > 1 ? `<span class="eo-qty">×${ownedInfo.copies}</span>` : ''}
        ${isOwned ? '<span class="eo-check">✓</span>' : `<span class="eo-missing-tag">${t('fehlt', 'missing')}</span>`}
      </div>
      <div class="eo-cardname"><span class="eo-num">#${escapeHTML(c.collectorNumber)}</span> ${escapeHTML(c.name)}</div>
    `;
    frag.appendChild(tile);
  });

  grid.innerHTML = '';
  if (!frag.childNodes.length) {
    grid.innerHTML = `<p class="eo-empty">${t('Keine Karten für diesen Filter.', 'No cards for this filter.')}</p>`;
  } else {
    grid.appendChild(frag);
  }
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
  document.getElementById('eo-grid').innerHTML = `<div class="eo-loading">${t('Lade Kartenbilder…', 'Loading card images…')}</div>`;
  document.querySelector('input[name="eo-filter"][value="all"]').checked = true;
  overlay.classList.add('open');
  document.body.style.overflow = 'hidden';

  try {
    overlayCards = await fetchSetCards(edition.code);
  } catch (e) {
    document.getElementById('eo-sub').textContent = '';
    document.getElementById('eo-grid').innerHTML = `<p class="eo-empty" style="color:#e05656">${t('Konnte Karten nicht laden', 'Could not load cards')}: ${escapeHTML(e.message)}</p>`;
    return;
  }
  // Overlay was closed again while loading.
  if (!overlay.classList.contains('open') || overlayEdition !== edition) return;

  if (!overlayCards.length) {
    document.getElementById('eo-sub').textContent = '';
    document.getElementById('eo-grid').innerHTML = `<p class="eo-empty">${t('Keine Kartenliste für diese Edition gefunden.', 'No card list found for this edition.')}</p>`;
    return;
  }

  populateOverlayRarities();
  updateOverlaySub();
  renderOverlayGrid();
}

function closeEditionOverlay() {
  document.getElementById('edition-overlay').classList.remove('open');
  document.body.style.overflow = '';
  overlayEdition = null;
}

/* ---- Detail overlay for a single card (opened from the edition overlay) ---- */

function editionCardDetailHTML(card, ownedInfo) {
  const img = card.image || card.imageSmall;
  const backImg = card.imageBack || card.imageBackSmall || '';
  const price = card.priceEur ? formatCurrency(parseFloat(card.priceEur), 'EUR') : '–';
  const foilPrice = card.priceEurFoil ? formatCurrency(parseFloat(card.priceEurFoil), 'EUR') : null;

  let ownership;
  if (ownedInfo) {
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
        ${img ? `<img src="${img}" alt="${escapeHTML(card.name)}">` : `<div class="no-image">${t('Kein Bild', 'No image')}</div>`}
        ${backImg ? `<img src="${backImg}" alt="${escapeHTML(card.name)} (${t('Rückseite', 'back')})">` : ''}
      </div>
      <div class="modal-details">
        <h2>${escapeHTML(card.name)}</h2>
        <p class="modal-type">${escapeHTML(card.typeLine || '')}</p>
        <dl>
          <dt>${t('Nummer', 'Number')}</dt><dd>#${escapeHTML(card.collectorNumber)}</dd>
          <dt>${t('Rarität', 'Rarity')}</dt><dd>${escapeHTML(card.rarity || '')}</dd>
          <dt>${t('Marktwert', 'Market value')}</dt><dd>${price}</dd>
          ${foilPrice ? `<dt>${t('Marktwert (Foil)', 'Market value (foil)')}</dt><dd>${foilPrice}</dd>` : ''}
        </dl>
        ${ownership}
      </div>
    </div>`;
}

function openEditionCardOverlay(card, ownedInfo) {
  const overlay = document.getElementById('eo-card-overlay');
  overlay.querySelector('.modal-body').innerHTML = editionCardDetailHTML(card, ownedInfo);
  overlay.classList.add('open');
}

function closeEditionCardOverlay() {
  document.getElementById('eo-card-overlay').classList.remove('open');
}

async function init() {
  showLoading(t('Lade Sammlung...', 'Loading collection...'));
  try {
    allCards = await loadCollection(updateLoadingProgress);
    setIndex = await loadSetIndex();
  } catch (e) {
    hideLoading();
    document.getElementById('editions-table').innerHTML = `<p style="color:#e05656">${t('Fehler beim Laden', 'Error loading')}: ${escapeHTML(e.message)}</p>`;
    return;
  }
  hideLoading();

  editions = buildEditions(allCards);
  editionByCode = {};
  editions.forEach((e) => (editionByCode[e.code] = e));

  populateTypeFilter();
  renderStats();
  renderTable();

  document.getElementById('search').addEventListener('input', debounce(renderTable, 150));
  document.getElementById('sort-by').addEventListener('change', renderTable);
  document.getElementById('filter-owned').addEventListener('change', renderTable);
  document.getElementById('filter-type').addEventListener('change', renderTable);

  const hintBtn = document.getElementById('hint-toggle');
  const hint = document.getElementById('section-hint');
  if (hintBtn && hint) {
    const setHint = (open) => {
      hint.hidden = !open;
      hintBtn.textContent = open ? t('Erklärung ausblenden', 'Hide explanation') : t('Erklärung anzeigen', 'Show explanation');
      hintBtn.classList.toggle('open', open);
    };
    setHint(false);
    hintBtn.addEventListener('click', () => setHint(hint.hidden));
  }

  document.getElementById('editions-table').addEventListener('click', (e) => {
    const row = e.target.closest('.edition-row');
    if (row) openEditionOverlay(editionByCode[row.dataset.code]);
  });

  const overlay = document.getElementById('edition-overlay');
  overlay.addEventListener('click', (e) => {
    if (e.target === overlay || e.target.classList.contains('eo-close')) closeEditionOverlay();
  });
  document.getElementById('eo-grid').addEventListener('click', (e) => {
    const tile = e.target.closest('.eo-card');
    if (!tile) return;
    const card = overlayCards[tile.dataset.idx];
    if (card) openEditionCardOverlay(card, overlayEdition.ownedByNumber[normNumber(card.collectorNumber)]);
  });

  const cardOverlay = document.getElementById('eo-card-overlay');
  cardOverlay.addEventListener('click', (e) => {
    if (e.target === cardOverlay || e.target.classList.contains('eo-card-close')) closeEditionCardOverlay();
  });

  document.addEventListener('keydown', (e) => {
    if (e.key !== 'Escape') return;
    if (cardOverlay.classList.contains('open')) closeEditionCardOverlay();
    else closeEditionOverlay();
  });
  document.querySelectorAll('input[name="eo-filter"]').forEach((r) => r.addEventListener('change', renderOverlayGrid));
  document.getElementById('eo-rarity').addEventListener('change', () => {
    updateOverlaySub();
    renderOverlayGrid();
  });
}

init();
