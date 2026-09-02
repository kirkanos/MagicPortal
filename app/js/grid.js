let allCards = [];
let allGroups = [];
let filteredCards = [];
let setIndex = {};
let officialSubtypes = null;
let selectedSet = ''; // set-code chosen in the custom set filter ('' = all sets)

const RARITY_LABEL = { common: 'Common', uncommon: 'Uncommon', rare: 'Rare', mythic: 'Mythic', special: 'Special' };
const COLOR_LABEL = {
  W: t('Weiß', 'White'), U: t('Blau', 'Blue'), B: t('Schwarz', 'Black'), R: t('Rot', 'Red'), G: t('Grün', 'Green'),
};

/* ---- Group language/finish variants of the same card into one tile
   (cardTypes / cardSubtypes / manaValue live in shared.js) ---- */
function groupKey(c) {
  return c.setCode + '|' + (c.collectorNumber ? 'n:' + c.collectorNumber : 'name:' + c.name.toLowerCase());
}

function pickRep(variants) {
  return (
    variants.find((v) => v.scryfall && v.scryfall.image && (v.language || '').toLowerCase() === 'en') ||
    variants.find((v) => v.scryfall && v.scryfall.image) ||
    variants[0]
  );
}

function groupCards(rows) {
  const map = {};
  rows.forEach((c) => {
    const k = groupKey(c);
    if (!map[k]) {
      map[k] = { key: k, name: c.name, setCode: c.setCode, setName: c.setName, collectorNumber: c.collectorNumber, variants: [] };
    }
    map[k].variants.push(c);
  });

  return Object.values(map).map((g) => {
    g.rep = pickRep(g.variants);
    g.rarity = g.rep.rarity;
    g.totalQty = g.variants.reduce((a, v) => a + v.quantity, 0);
    g.anyFoil = g.variants.some((v) => v.foil && v.foil !== 'normal');
    const seen = new Set();
    g.langs = [];
    g.variants.forEach((v) => {
      const l = (v.language || '').toLowerCase();
      if (l && !seen.has(l)) {
        seen.add(l);
        g.langs.push(l);
      }
    });
    g.marketPrice = variantMarketPrice(g.rep) || 0;
    g.purchasePrice = g.rep.purchasePrice;
    g.added = g.variants.reduce((m, v) => ((v.added || '') > m ? v.added || '' : m), '');
    g.released = (setIndex[g.setCode.toLowerCase()] || {}).releasedAt || '';
    return g;
  });
}

/* Set symbol as a tinted CSS-mask span (same technique as the card tiles). */
function setSymHTML(icon) {
  return icon
    ? `<span class="set-sym" style="-webkit-mask-image:url('${escapeHTML(icon)}');mask-image:url('${escapeHTML(icon)}')"></span>`
    : '';
}

function setIcon(code) {
  const info = setIndex[(code || '').toLowerCase()];
  return (info && info.iconSvgUri) || '';
}

function updateSetLabel() {
  const label = document.getElementById('set-label');
  if (!selectedSet) {
    label.textContent = t('Alle Sets', 'All sets');
    return;
  }
  const card = allCards.find((c) => c.setCode === selectedSet);
  const name = (setIndex[selectedSet.toLowerCase()] || {}).name || (card && card.setName) || selectedSet;
  label.innerHTML = setSymHTML(setIcon(selectedSet)) + `<span class="set-name">${escapeHTML(name)}</span>`;
}

/* Custom set filter: a searchable dropdown showing the edition symbol + name
   (a native <select> can't render the set symbols). */
function populateSetFilter(cards) {
  const opts = document.getElementById('set-options');
  const seen = {};
  cards.forEach((c) => {
    if (!seen[c.setCode]) seen[c.setCode] = (setIndex[c.setCode.toLowerCase()] || {}).name || c.setName || c.setCode;
  });
  const list = Object.keys(seen)
    .map((code) => ({ code, name: seen[code] }))
    .sort((a, b) => a.name.localeCompare(b.name));

  opts.innerHTML =
    `<div class="ms-option" data-code="" data-search="alle sets all sets">${t('Alle Sets', 'All sets')}</div>` +
    list
      .map(
        (s) =>
          `<div class="ms-option" data-code="${escapeHTML(s.code)}" data-search="${escapeHTML((s.name + ' ' + s.code).toLowerCase())}">${setSymHTML(
            setIcon(s.code)
          )}<span>${escapeHTML(s.name)}</span></div>`
      )
      .join('');

  const wrap = document.getElementById('set-filter');
  const toggle = document.getElementById('set-toggle');
  const panel = document.getElementById('set-panel');
  const search = document.getElementById('set-search');
  const setOpen = (open) => {
    panel.hidden = !open;
    toggle.setAttribute('aria-expanded', open ? 'true' : 'false');
    if (open) {
      search.value = '';
      opts.querySelectorAll('.ms-option').forEach((o) => (o.style.display = ''));
      search.focus();
    }
  };
  toggle.addEventListener('click', (e) => {
    e.stopPropagation();
    setOpen(panel.hidden);
  });
  document.addEventListener('click', (e) => {
    if (!wrap.contains(e.target)) setOpen(false);
  });
  search.addEventListener('input', () => {
    const q = search.value.trim().toLowerCase();
    opts.querySelectorAll('.ms-option').forEach((o) => {
      o.style.display = !q || o.dataset.search.includes(q) ? '' : 'none';
    });
  });
  opts.addEventListener('click', (e) => {
    const opt = e.target.closest('.ms-option');
    if (!opt) return;
    selectedSet = opt.dataset.code;
    updateSetLabel();
    setOpen(false);
    applyFilters();
  });

  updateSetLabel();
}

function populateFilters(cards) {
  const raritySel = document.getElementById('filter-rarity');
  const colorSel = document.getElementById('filter-color');

  const rarities = uniqueSorted(cards.map((c) => c.rarity));
  rarities.forEach((r) => {
    const opt = document.createElement('option');
    opt.value = r;
    opt.textContent = RARITY_LABEL[r] || r;
    raritySel.appendChild(opt);
  });

  const colorSet = new Set();
  cards.forEach((c) => (c.scryfall && c.scryfall.colors ? c.scryfall.colors : []).forEach((col) => colorSet.add(col)));
  ['W', 'U', 'B', 'R', 'G'].forEach((col) => {
    if (colorSet.has(col)) {
      const opt = document.createElement('option');
      opt.value = col;
      opt.textContent = COLOR_LABEL[col];
      colorSel.appendChild(opt);
    }
  });
  if (cards.some((c) => c.scryfall && (!c.scryfall.colors || c.scryfall.colors.length === 0))) {
    const opt = document.createElement('option');
    opt.value = 'C';
    opt.textContent = t('Farblos', 'Colorless');
    colorSel.appendChild(opt);
  }

  const binderSel = document.getElementById('filter-binder');
  if (binderSel) {
    uniqueSorted(cards.map((c) => c.binderName)).forEach((b) => {
      const opt = document.createElement('option');
      opt.value = b;
      opt.textContent = b;
      binderSel.appendChild(opt);
    });
  }

  const typeSel = document.getElementById('filter-type');
  if (typeSel) {
    const typeSet = new Set();
    cards.forEach((c) => cardTypes((c.scryfall && c.scryfall.typeLine) || '').forEach((tp) => typeSet.add(tp)));
    [...typeSet].sort((a, b) => a.localeCompare(b)).forEach((tp) => {
      const opt = document.createElement('option');
      opt.value = tp;
      opt.textContent = tp;
      typeSel.appendChild(opt);
    });
  }
}

// Subtypes depend on the selected type (e.g. Creature → Dragon, Goblin …).
// Disabled when the chosen type has no subtypes / no type is selected.
function populateSubtypes(type) {
  const sel = document.getElementById('filter-subtype');
  if (!sel) return;
  let subs = [];
  if (type) {
    const set = new Set();
    allCards.forEach((c) => {
      const tl = (c.scryfall && c.scryfall.typeLine) || '';
      if (cardTypes(tl).includes(type)) cardSubtypes(tl).forEach((s) => set.add(s));
    });
    subs = [...set].sort((a, b) => a.localeCompare(b));
    // Only keep officially recognised subtypes (filters out joke/Un-set noise).
    if (officialSubtypes && officialSubtypes.size) subs = subs.filter((s) => officialSubtypes.has(s));
  }
  sel.innerHTML =
    `<option value="">${t('Alle Untertypen', 'All subtypes')}</option>` +
    subs.map((s) => `<option value="${escapeHTML(s)}">${escapeHTML(s)}</option>`).join('');
  sel.disabled = subs.length === 0;
}

function renderStats(rows, distinctCount) {
  const totalQty = rows.reduce((s, c) => s + c.quantity, 0);
  const totalValue = rows.reduce((s, c) => s + c.purchasePrice * c.quantity, 0);
  const marketValue = rows.reduce((s, c) => s + (variantMarketPrice(c) || 0) * c.quantity, 0);

  document.getElementById('stats-bar').innerHTML = `
    <div class="stat-tile" title="${countHint('distinct')}"><div class="stat-value">${distinctCount}</div><div class="stat-label">${t('Verschiedene Karten', 'Distinct cards')}</div></div>
    <div class="stat-tile" title="${countHint('total')}"><div class="stat-value">${totalQty}</div><div class="stat-label">${t('Karten gesamt', 'Cards total')}</div></div>
    <div class="stat-tile"><div class="stat-value">${formatCurrency(totalValue, 'EUR')}</div><div class="stat-label">${t('Kaufwert', 'Purchase value')}</div></div>
    <div class="stat-tile"><div class="stat-value">${formatCurrency(marketValue, 'EUR')}</div><div class="stat-label">${t('Marktwert (Scryfall)', 'Market value (Scryfall)')}</div></div>
  `;
}

function applyFilters() {
  const q = document.getElementById('search').value.trim().toLowerCase();
  // Stichwortsuche (UND): jedes Wort muss irgendwo vorkommen, Reihenfolge egal.
  const terms = q ? q.split(/\s+/).filter(Boolean) : [];
  const set = selectedSet;
  const rarity = document.getElementById('filter-rarity').value;
  const color = document.getElementById('filter-color').value;
  const foil = document.getElementById('filter-foil').value;
  const binder = document.getElementById('filter-binder').value;
  const cardType = document.getElementById('filter-type').value;
  const subtype = document.getElementById('filter-subtype').value;
  const sortBy = document.getElementById('sort-by').value;

  filteredCards = allCards.filter((c) => {
    if (terms.length) {
      const sc = c.scryfall || {};
      // Durchsucht Name + Kartentext + Manakosten (ohne Klammern/Schrägstriche).
      const hay = (
        c.name + ' ' + (sc.oracleText || '') + ' ' + String(sc.manaCost || '').replace(/[{}\/]/g, '')
      ).toLowerCase();
      if (!terms.every((term) => hay.includes(term))) return false;
    }
    if (set && c.setCode !== set) return false;
    if (rarity && c.rarity !== rarity) return false;
    if (foil && c.foil !== foil) return false;
    if (binder && c.binderName !== binder) return false;
    if (cardType || subtype) {
      const tl = (c.scryfall && c.scryfall.typeLine) || '';
      if (cardType && !cardTypes(tl).includes(cardType)) return false;
      if (subtype && !cardSubtypes(tl).includes(subtype)) return false;
    }
    if (color) {
      const colors = (c.scryfall && c.scryfall.colors) || [];
      if (color === 'C' && colors.length !== 0) return false;
      if (color !== 'C' && !colors.includes(color)) return false;
    }
    return true;
  });

  const groups = groupCards(filteredCards);
  groups.sort((a, b) => {
    switch (sortBy) {
      case 'set':
        return a.setCode.localeCompare(b.setCode) || a.name.localeCompare(b.name);
      case 'price-desc':
        return b.purchasePrice - a.purchasePrice;
      case 'price-asc':
        return a.purchasePrice - b.purchasePrice;
      case 'value-desc':
        return b.marketPrice - a.marketPrice || a.name.localeCompare(b.name);
      case 'value-asc':
        return a.marketPrice - b.marketPrice || a.name.localeCompare(b.name);
      case 'rarity':
        return (a.rarity || '').localeCompare(b.rarity || '');
      case 'added-desc':
        return (b.added || '').localeCompare(a.added || '');
      case 'released-desc':
        return (b.released || '').localeCompare(a.released || '') || a.name.localeCompare(b.name);
      case 'released-asc':
        return (a.released || '9999').localeCompare(b.released || '9999') || a.name.localeCompare(b.name);
      default:
        return a.name.localeCompare(b.name);
    }
  });

  document.getElementById('result-count').textContent = `${groups.length} ${t('von', 'of')} ${allGroups.length} ${t('Karten', 'cards')}`;
  renderGrid(groups);
}

function buildCardTile(g) {
  const img = cardImage(g.rep, 'small');
  const flags = g.langs
    .map((l) => `<span class="lang-flag" title="${escapeHTML(l.toUpperCase())}">${languageFlag(l) || escapeHTML(l.toUpperCase())}</span>`)
    .join('');

  const info = setIndex[g.setCode.toLowerCase()] || null;
  const setName = (info && info.name) || g.setName || g.setCode;
  const icon = info && info.iconSvgUri;
  const rarity = (g.rarity || '').toLowerCase();
  const rarityCls = rarity ? ' rarity-' + rarity : '';
  const rarityLabel = RARITY_LABEL[rarity] || g.rarity || '';
  // Set symbol tinted by rarity colour (like on a real card) via CSS mask.
  const sym = icon
    ? `<span class="set-sym${rarityCls}" style="-webkit-mask-image:url('${escapeHTML(icon)}');mask-image:url('${escapeHTML(icon)}')"></span>`
    : '';

  const tile = document.createElement('div');
  tile.className = 'card-tile';
  tile.innerHTML = `
    <div class="thumb">
      ${img ? `<img loading="lazy" src="${img}" alt="${escapeHTML(g.name)}">` : `<div class="no-image">${t('Kein Bild', 'No image')}</div>`}
      ${g.anyFoil ? '<span class="foil-badge">Foil</span>' : ''}
      ${g.totalQty > 1 ? `<span class="qty-badge">×${g.totalQty}</span>` : ''}
      ${flags ? `<div class="lang-flags">${flags}</div>` : ''}
    </div>
    <div class="info">
      <div class="name">${escapeHTML(g.name)}</div>
      <div class="meta-set">${sym}<span class="set-name" title="${escapeHTML(setName)} (${escapeHTML(g.setCode)})">${escapeHTML(setName)}</span></div>
      <div class="meta">
        ${rarityLabel ? `<span class="rarity-tag${rarityCls}">${escapeHTML(rarityLabel)}</span>` : '<span></span>'}
        <span>${g.marketPrice ? formatCurrency(g.marketPrice, 'EUR') : '–'}</span>
      </div>
    </div>
  `;
  tile.addEventListener('click', () => {
    openCardModal(cardGroupModalHTML(g));
    loadCardVariants(g);
  });
  return tile;
}

function renderGrid(groups) {
  const grid = document.getElementById('card-grid');
  grid.innerHTML = '';
  const fragment = document.createDocumentFragment();
  groups.forEach((g) => fragment.appendChild(buildCardTile(g)));
  grid.appendChild(fragment);
}

/* Random-cards strip above the filters, controlled by the admin config. */
function renderRandomStrip() {
  const el = document.getElementById('random-strip');
  if (!el) return;
  const cfg = UI_CONFIG || {};
  if (!cfg.randomEnabled || !allGroups.length) {
    el.style.display = 'none';
    el.innerHTML = '';
    return;
  }
  const n = Math.max(1, Math.min(cfg.randomCount || 6, allGroups.length));
  const pool = allGroups.slice();
  for (let i = pool.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [pool[i], pool[j]] = [pool[j], pool[i]];
  }
  const picks = pool.slice(0, n);

  el.style.display = '';
  el.innerHTML = `<h2 class="random-title">${t('Zufällige Karten aus deiner Sammlung', 'Random cards from your collection')}</h2>`;
  const row = document.createElement('div');
  row.className = 'random-row';
  picks.forEach((g) => row.appendChild(buildCardTile(g)));
  el.appendChild(row);
}

async function init() {
  initModal();
  showLoading(t('Lade Sammlung...', 'Loading collection...'));
  try {
    allCards = await loadCollection(updateLoadingProgress);
    setIndex = await loadSetIndex();
    officialSubtypes = new Set(await loadSubtypes().catch(() => []));
  } catch (e) {
    hideLoading();
    document.getElementById('card-grid').innerHTML = `<p style="color:#e05656">${t('Fehler beim Laden der Sammlung', 'Error loading collection')}: ${escapeHTML(e.message)}</p>`;
    return;
  }
  hideLoading();
  allGroups = groupCards(allCards);

  if (allGroups.length === 0) {
    renderEmptyState();
    return;
  }

  populateFilters(allCards);
  populateSetFilter(allCards);
  populateSubtypes('');

  // Optional deep-link from the Ordner/Listen pages: index.html?binder=<name>
  const binderParam = new URLSearchParams(location.search).get('binder');
  if (binderParam) {
    const binderSel = document.getElementById('filter-binder');
    if ([...binderSel.options].some((o) => o.value === binderParam)) binderSel.value = binderParam;
  }

  renderStats(allCards, allGroups.length);
  applyFilters();
  await uiConfigReady;
  renderRandomStrip();

  document.getElementById('search').addEventListener('input', debounce(applyFilters, 150));
  // The set filter (custom dropdown) wires its own events in populateSetFilter().
  ['filter-rarity', 'filter-color', 'filter-foil', 'filter-binder', 'filter-subtype', 'sort-by'].forEach((id) => {
    document.getElementById(id).addEventListener('change', applyFilters);
  });
  document.getElementById('filter-type').addEventListener('change', (e) => {
    populateSubtypes(e.target.value);
    applyFilters();
  });
}

function renderEmptyState() {
  document.getElementById('stats-bar').innerHTML = '';
  document.getElementById('result-count').textContent = '';
  const controls = document.querySelector('.controls');
  if (controls) controls.style.display = 'none';
  document.getElementById('card-grid').innerHTML = `
    <div class="empty-state">
      <img src="images/magic-portal-logo.svg" alt="Magic Portal – A ManaBox Interface" class="empty-logo">
      <h2>${t('Deine Sammlung ist noch leer', 'Your collection is empty')}</h2>
      <p>${t(
        'Lade oben rechts über <strong>„🔑 Login"</strong> → <strong>„⬆ CSV hochladen"</strong> deinen ManaBox-CSV-Export hoch, um deine Karten hier zu sehen.',
        'Upload your ManaBox CSV export via <strong>“🔑 Login”</strong> → <strong>“⬆ Upload CSV”</strong> in the top right to see your cards here.'
      )}</p>
    </div>`;
}

init();
