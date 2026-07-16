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

// Set types that are meaningful "editions" to collect. Not-owned sets are only
// listed for these types (owned sets are always listed, whatever their type).
const COLLECTABLE_SET_TYPES = new Set([
  'core', 'expansion', 'masters', 'draft_innovation', 'commander', 'remastered',
]);

function buildEditions(cards) {
  // Group owned rows by set code (lowercased, to match the Scryfall index).
  const owned = {};
  cards.forEach((c) => {
    const lc = c.setCode.toLowerCase();
    if (!owned[lc]) owned[lc] = { code: c.setCode, name: c.setName, rows: [] };
    owned[lc].rows.push(c);
  });

  // Union: every owned set + all collectable, non-digital sets from the index.
  const codes = new Set(Object.keys(owned));
  Object.keys(setIndex).forEach((lc) => {
    const info = setIndex[lc];
    if (owned[lc] || (!info.digital && COLLECTABLE_SET_TYPES.has(info.setType))) codes.add(lc);
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
        distinctMap[id] = { id, name: c.name, collectorNumber: c.collectorNumber, copies: 0, byLang: {}, rep: c };
      }
      const d = distinctMap[id];
      d.copies += c.quantity;
      const lang = (c.language || '?').toLowerCase();
      d.byLang[lang] = (d.byLang[lang] || 0) + c.quantity;
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
    <div class="stat-tile"><div class="stat-value">${inColl.length}</div><div class="stat-label">Gesammelte Editionen</div></div>
    <div class="stat-tile"><div class="stat-value">${totalOwned.toLocaleString('de-DE')}</div><div class="stat-label">Verschiedene Karten</div></div>
    <div class="stat-tile"><div class="stat-value">${complete}</div><div class="stat-label">Komplette Editionen</div></div>
    <div class="stat-tile"><div class="stat-value">${totalDupeCards.toLocaleString('de-DE')}</div><div class="stat-label">Karten mehrfach vorhanden</div></div>
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
      case 'complete-desc': {
        const pa = a.total ? a.owned / a.total : -1;
        const pb = b.total ? b.owned / b.total : -1;
        return pb - pa || a.name.localeCompare(b.name);
      }
      case 'dupes-desc':
        return b.dupeCount - a.dupeCount || a.name.localeCompare(b.name);
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
        <div class="progress-head"><strong>${e.owned}</strong> <span class="dim">Karten</span></div>
        <div class="progress-sub dim">Set-Größe unbekannt</div>
      </div>`;
  }
  const pct = e.total ? Math.min(100, Math.round((e.owned / e.total) * 100)) : 0;
  return `
    <div class="progress-cell">
      <div class="progress-head"><strong>${e.owned}</strong> <span class="dim">von ${e.total}</span> <span class="progress-pct">${pct}%</span></div>
      <div class="progress-track"><div class="progress-fill" style="width:${pct}%"></div></div>
    </div>`;
}

function renderTable() {
  const q = document.getElementById('search').value.trim().toLowerCase();
  const sortBy = document.getElementById('sort-by').value;
  const ownedFilter = document.getElementById('filter-owned').value;

  let list = editions;
  if (ownedFilter === 'owned') list = list.filter((e) => e.inCollection);
  else if (ownedFilter === 'missing') list = list.filter((e) => !e.inCollection);
  if (q) list = list.filter((e) => e.name.toLowerCase().includes(q) || e.code.toLowerCase().includes(q));
  list = sortEditions(list, sortBy);

  document.getElementById('result-count').textContent = `${list.length} von ${editions.length} Editionen`;

  const rows = list
    .map((e) => {
      const missingCell =
        e.missing == null
          ? '<span class="dim">unbekannt</span>'
          : e.missing === 0
          ? '<span class="status-owned">✓ komplett</span>'
          : `<strong>${e.missing.toLocaleString('de-DE')}</strong> <span class="dim">fehlen</span>`;
      const dupeCell = e.dupeCount
        ? `<strong>${e.dupeCount}</strong> <span class="dim">Karten (+${e.dupeExtra} Ex.)</span>`
        : '<span class="dim">–</span>';
      return `
        <tr class="edition-row${e.inCollection ? '' : ' edition-row--empty'}" data-code="${escapeHTML(e.code)}" title="Alle Karten der Edition anzeigen">
          <td>
            <div class="edition-name">
              <span class="edition-icon">${e.inCollection ? '🗂️' : '⬚'}</span>
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
          <th>Edition</th>
          <th>Vollständigkeit</th>
          <th class="num">Fehlende Karten</th>
          <th class="num">Mehrfach</th>
          <th class="num">Exemplare gesamt</th>
          <th class="num">Marktwert</th>
        </tr>
      </thead>
      <tbody>${rows}</tbody>
    </table>`;
}

/* ---- Edition overlay: all cards with images, missing ones greyed out ---- */

let overlayCards = [];
let overlayEdition = null;

function overlayCounts() {
  let owned = 0;
  overlayCards.forEach((c) => {
    if (overlayEdition.ownedByNumber[normNumber(c.collectorNumber)]) owned++;
  });
  return { owned, total: overlayCards.length, missing: overlayCards.length - owned };
}

function renderOverlayGrid() {
  const filter = document.querySelector('input[name="eo-filter"]:checked').value;
  const grid = document.getElementById('eo-grid');
  const frag = document.createDocumentFragment();

  overlayCards.forEach((c, idx) => {
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
        ${img ? `<img loading="lazy" src="${img}" alt="${escapeHTML(c.name)}">` : '<div class="no-image">Kein Bild</div>'}
        ${isOwned && ownedInfo.copies > 1 ? `<span class="eo-qty">×${ownedInfo.copies}</span>` : ''}
        ${isOwned ? '<span class="eo-check">✓</span>' : '<span class="eo-missing-tag">fehlt</span>'}
      </div>
      <div class="eo-cardname"><span class="eo-num">#${escapeHTML(c.collectorNumber)}</span> ${escapeHTML(c.name)}</div>
    `;
    frag.appendChild(tile);
  });

  grid.innerHTML = '';
  if (!frag.childNodes.length) {
    grid.innerHTML = '<p class="eo-empty">Keine Karten für diesen Filter.</p>';
  } else {
    grid.appendChild(frag);
  }
}

async function openEditionOverlay(edition) {
  overlayEdition = edition;
  overlayCards = [];
  const overlay = document.getElementById('edition-overlay');
  document.getElementById('eo-title').textContent = edition.name;
  document.getElementById('eo-code').textContent = edition.code;
  document.getElementById('eo-sub').textContent = 'Lade Karten von Scryfall…';
  document.getElementById('eo-grid').innerHTML = '<div class="eo-loading">Lade Kartenbilder…</div>';
  document.querySelector('input[name="eo-filter"][value="all"]').checked = true;
  overlay.classList.add('open');
  document.body.style.overflow = 'hidden';

  try {
    overlayCards = await fetchSetCards(edition.code);
  } catch (e) {
    document.getElementById('eo-sub').textContent = '';
    document.getElementById('eo-grid').innerHTML = `<p class="eo-empty" style="color:#e05656">Konnte Karten nicht laden: ${escapeHTML(e.message)}</p>`;
    return;
  }
  // Overlay was closed again while loading.
  if (!overlay.classList.contains('open') || overlayEdition !== edition) return;

  if (!overlayCards.length) {
    document.getElementById('eo-sub').textContent = '';
    document.getElementById('eo-grid').innerHTML = '<p class="eo-empty">Keine Kartenliste für diese Edition gefunden.</p>';
    return;
  }

  const { owned, total, missing } = overlayCounts();
  const pct = total ? Math.round((owned / total) * 100) : 0;
  document.getElementById('eo-sub').innerHTML =
    `<strong>${owned}</strong> von <strong>${total}</strong> Karten (${pct}%) · <span class="eo-sub-missing">${missing} fehlen</span>`;
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
  const price = card.priceEur ? formatCurrency(parseFloat(card.priceEur), 'EUR') : '–';
  const foilPrice = card.priceEurFoil ? formatCurrency(parseFloat(card.priceEurFoil), 'EUR') : null;

  let ownership;
  if (ownedInfo) {
    const rows = Object.entries(ownedInfo.byLang)
      .sort((a, b) => b[1] - a[1])
      .map(([l, n]) => `<tr><td>${languageFlag(l) || escapeHTML(l.toUpperCase())} ${escapeHTML(l.toUpperCase())}</td><td class="num">${n}</td></tr>`)
      .join('');
    ownership = `
      <h3 class="lang-table-title">In deiner Sammlung (${ownedInfo.copies}×)</h3>
      <table class="lang-table">
        <thead><tr><th>Sprache</th><th class="num">Anzahl</th></tr></thead>
        <tbody>${rows}</tbody>
      </table>`;
  } else {
    ownership = '<p class="eo-detail-missing">Diese Karte fehlt dir noch.</p>';
  }

  return `
    <span class="modal-close eo-card-close">&times;</span>
    <div class="modal-card">
      <div class="modal-image">${img ? `<img src="${img}" alt="${escapeHTML(card.name)}">` : '<div class="no-image">Kein Bild</div>'}</div>
      <div class="modal-details">
        <h2>${escapeHTML(card.name)}</h2>
        <p class="modal-type">${escapeHTML(card.typeLine || '')}</p>
        <dl>
          <dt>Nummer</dt><dd>#${escapeHTML(card.collectorNumber)}</dd>
          <dt>Rarität</dt><dd>${escapeHTML(card.rarity || '')}</dd>
          <dt>Marktwert</dt><dd>${price}</dd>
          ${foilPrice ? `<dt>Marktwert (Foil)</dt><dd>${foilPrice}</dd>` : ''}
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
  showLoading('Lade CSV-Datei...');
  try {
    allCards = await loadCollection(updateLoadingProgress);
    setIndex = await loadSetIndex();
  } catch (e) {
    hideLoading();
    document.getElementById('editions-table').innerHTML = `<p style="color:#e05656">Fehler beim Laden: ${escapeHTML(e.message)}</p>`;
    return;
  }
  hideLoading();

  editions = buildEditions(allCards);
  editionByCode = {};
  editions.forEach((e) => (editionByCode[e.code] = e));

  renderStats();
  renderTable();

  document.getElementById('search').addEventListener('input', debounce(renderTable, 150));
  document.getElementById('sort-by').addEventListener('change', renderTable);
  document.getElementById('filter-owned').addEventListener('change', renderTable);

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
}

init();
