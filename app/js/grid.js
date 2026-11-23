/* The page no longer keeps a collection in memory: search, filters, grouping
   and sorting are done by /api/cards, and only the currently visible page
   lives here. That saves around 10 MB of JSON on load and makes the page
   usable on a phone; in exchange every filter change costs a query (~60 ms). */
let facets = null;        // values for the filter fields, from /api/cards/facets
let setIndex = {};
let selectedSet = '';     // set code in the custom dropdown ('' = all)
let pageOffset = 0;       // next start index
let pageHasMore = false;
let resultTotal = 0;      // groups behind the current filter
let loadingPage = false;
let queryToken = 0;       // discards responses of superseded queries

// binder.html shows one binder, named by ?name=; index.html the whole
// collection. The binder is fixed there: no binder filter on either page.
const BINDER_VIEW = document.body.hasAttribute('data-binder-view');
const FIXED_BINDER = BINDER_VIEW ? (new URLSearchParams(location.search).get('name') || '') : '';
// The binder name on the rows: '' on the page of the cards without a binder.
const BINDER_NAME = FIXED_BINDER === NO_BINDER ? '' : FIXED_BINDER;

/* Facets; in the binder view the key figures are those of the binder. */
function facetsURL() {
  return API + '/cards/facets' + (FIXED_BINDER ? '?binder=' + encodeURIComponent(FIXED_BINDER) : '');
}

// Editing (with write access): the tiles on the page by group key, the
// multi selection, and the group whose detail dialog is open.
const tilesByKey = new Map();
const groupsByKey = new Map();
let selecting = false;
const selectedGroups = new Map(); // key -> group
let modalGroup = null;

const RARITY_LABEL = { common: 'Common', uncommon: 'Uncommon', rare: 'Rare', mythic: 'Mythic', special: 'Special' };
const COLOR_LABEL = {
  W: t('Weiß', 'White'), U: t('Blau', 'Blue'), B: t('Schwarz', 'Black'), R: t('Rot', 'Red'), G: t('Grün', 'Green'),
};

/* The backend returns flat groups; the tiles and the detail dialog expect the
   shape that grew over time, with `rep` and `variants[].scryfall`. The adapter
   restores it so nothing about the rendering has to change.

   variants[].marketPrice is already computed foil-aware. Put in as priceEur it
   makes variantMarketPrice() in shared.js return the same value - that one
   only falls back to priceEurFoil when it is set. */
function adaptGroup(g) {
  const sc = {
    typeLine: g.typeLine, manaCost: g.manaCost, oracleText: g.oracleText,
    colors: g.colors || [], imageId: g.imageId, hasBack: g.hasBack,
  };
  const variants = (g.variants || []).map((v) => ({
    ...v,
    name: g.name, setCode: g.setCode, setName: g.setName,
    collectorNumber: g.collectorNumber, rarity: g.rarity, scryfallId: g.imageId,
    scryfall: { ...sc, priceEur: v.marketPrice },
  }));
  const rep = {
    name: g.name, rarity: g.rarity, purchasePrice: g.purchasePrice,
    scryfallId: g.imageId, scryfall: sc,
  };
  return { ...g, rep, variants };
}

/* Set symbol as a tinted CSS-mask span (same technique as the card tiles). */
function setSymHTML(icon) {
  return icon
    ? `<span class="set-sym" style="-webkit-mask-image:url('${escapeHTML(icon)}');mask-image:url('${escapeHTML(icon)}')"></span>`
    : '';
}

function setIcon(code) {
  const info = setIndex[(code || '').toLowerCase()];
  return setSymbolURL(info && info.iconSvgUri);
}

function updateSetLabel() {
  const label = document.getElementById('set-label');
  if (!selectedSet) {
    label.textContent = t('Alle Sets', 'All sets');
    return;
  }
  const fromFacets = (facets && facets.sets || []).find((x) => x.code === selectedSet);
  const name = (setIndex[selectedSet.toLowerCase()] || {}).name || (fromFacets && fromFacets.name) || selectedSet;
  label.innerHTML = setSymHTML(setIcon(selectedSet)) + `<span class="set-name">${escapeHTML(name)}</span>`;
}

/* Custom set filter: a searchable dropdown showing the edition symbol + name
   (a native <select> can't render the set symbols). */
function renderSetOptions() {
  const opts = document.getElementById('set-options');
  // In a binder view only the sets in that binder (plus the chosen one, so it
  // can be seen and switched off).
  const inBinder = FIXED_BINDER && facets.setsByBinder ? new Set(facets.setsByBinder[FIXED_BINDER] || []) : null;
  const list = (facets.sets || [])
    .filter((s) => !inBinder || inBinder.has(s.code) || s.code === selectedSet)
    .map((s) => ({ code: s.code, name: (setIndex[s.code.toLowerCase()] || {}).name || s.name || s.code }))
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
}

function populateSetFilter() {
  renderSetOptions();
  const opts = document.getElementById('set-options');
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
    runQuery();
  });

  updateSetLabel();
}

const STYLE_LABEL = {
  fullart: () => 'Full Art',
  borderless: () => t('Randlos', 'Borderless'),
  showcase: () => 'Showcase',
  extendedart: () => 'Extended Art',
  artseries: () => t('Art-Karten', 'Art cards'),
};

function populateFilters() {
  const raritySel = document.getElementById('filter-rarity');
  const colorSel = document.getElementById('filter-color');
  const addOpt = (sel, value, label) => {
    const opt = document.createElement('option');
    opt.value = value;
    opt.textContent = label;
    sel.appendChild(opt);
  };

  (facets.rarities || []).forEach((r) => addOpt(raritySel, r, RARITY_LABEL[r] || r));
  ['W', 'U', 'B', 'R', 'G'].forEach((col) => {
    if ((facets.colors || []).includes(col)) addOpt(colorSel, col, COLOR_LABEL[col]);
  });
  if (facets.colorless) addOpt(colorSel, 'C', t('Farblos', 'Colorless'));

  // Card variants (scryfall_cards.styles), only those the collection has.
  const styleSel = document.getElementById('filter-style');
  if (styleSel) (facets.styles || []).forEach((s) => addOpt(styleSel, s, STYLE_LABEL[s] ? STYLE_LABEL[s]() : s));

  const typeSel = document.getElementById('filter-type');
  if (typeSel) (facets.types || []).forEach((tp) => addOpt(typeSel, tp, tp));
}

// Subtypes depend on the selected type (e.g. Creature → Dragon, Goblin …).
// Disabled when the chosen type has no subtypes / no type is selected.
function populateSubtypes(type) {
  const sel = document.getElementById('filter-subtype');
  if (!sel) return;
  // The backend returns the subtypes per type; it does not filter them down
  // to the official ones - the catalog from /api/subtypes still does that.
  let subs = (type && facets.subtypesByType && facets.subtypesByType[type]) || [];
  if (officialSubtypes && officialSubtypes.size) subs = subs.filter((x) => officialSubtypes.has(x));
  sel.innerHTML =
    `<option value="">${t('Alle Untertypen', 'All subtypes')}</option>` +
    subs.map((x) => `<option value="${escapeHTML(x)}">${escapeHTML(x)}</option>`).join('');
  sel.disabled = subs.length === 0;
}

function renderStats() {
  const st = (facets && facets.stats) || {};
  document.getElementById('stats-bar').innerHTML = `
    <div class="stat-tile" title="${countHint('distinct')}"><div class="stat-value">${st.distinct || 0}</div><div class="stat-label">${t('Verschiedene Karten', 'Distinct cards')}</div></div>
    <div class="stat-tile" title="${countHint('total')}"><div class="stat-value">${st.totalQty || 0}</div><div class="stat-label">${t('Karten gesamt', 'Cards total')}</div></div>
    <div class="stat-tile"><div class="stat-value">${formatCurrency(st.purchaseValue || 0, 'EUR')}</div><div class="stat-label">${t('Kaufwert', 'Purchase value')}</div></div>
    <div class="stat-tile"><div class="stat-value">${formatCurrency(st.marketValue || 0, 'EUR')}</div><div class="stat-label">${t('Marktwert (Scryfall)', 'Market value (Scryfall)')}</div></div>
  `;
}

/* The current filter values as query parameters. */
function currentParams() {
  const p = new URLSearchParams();
  const q = document.getElementById('search').value.trim();
  if (q) p.set('q', q);
  if (selectedSet) p.set('set', selectedSet);
  const pick = (id, key) => {
    const el = document.getElementById(id);
    if (el && el.value) p.set(key, el.value);
  };
  pick('filter-rarity', 'rarity');
  pick('filter-color', 'color');
  pick('filter-foil', 'foil');
  pick('filter-style', 'style');
  if (FIXED_BINDER) p.set('binder', FIXED_BINDER);
  pick('filter-type', 'type');
  pick('filter-subtype', 'subtype');
  pick('sort-by', 'sort');
  return p;
}

/* Fetches one page of results. append=false replaces the grid (new filters),
   append=true appends the next page (scrolling). keepView reloads as many
   cards as are loaded and keeps the scroll position: after a write the
   view stays where it was. */
async function runQuery(append = false, keepView = false) {
  // Only the scroll-driven loading may block itself. A new filter query must
  // never be dropped, otherwise the page silently keeps the previous result;
  // superseded responses are caught by queryToken.
  if (append && loadingPage) return;
  loadingPage = true;
  const token = ++queryToken;
  const p = currentParams();
  if (append) p.set('offset', String(pageOffset));
  if (keepView) p.set('limit', String(Math.min(Math.max(pageOffset, 60), 500)));
  const scrollY = window.scrollY;

  let data;
  try {
    const res = await fetch(API + '/cards?' + p.toString(), { cache: 'no-cache' });
    if (!res.ok) throw new Error('HTTP ' + res.status);
    data = await res.json();
  } catch (e) {
    loadingPage = false;
    if (!append) {
      document.getElementById('card-grid').innerHTML =
        `<p style="color:#e05656">${t('Fehler beim Laden', 'Error loading')}: ${escapeHTML(e.message)}</p>`;
    }
    return;
  }
  loadingPage = false;
  // A query started in the meantime takes precedence.
  if (token !== queryToken) return;

  const groups = (data.groups || []).map(adaptGroup);
  pageOffset = data.offset + groups.length;
  pageHasMore = data.hasMore;
  resultTotal = data.total;

  const grid = document.getElementById('card-grid');
  if (!append) {
    stopIncremental(grid);
    grid.innerHTML = '';
    tilesByKey.clear();
    groupsByKey.clear();
  }
  const frag = document.createDocumentFragment();
  groups.forEach((g) => frag.appendChild(buildCardTile(g)));
  grid.appendChild(frag);
  if (keepView) window.scrollTo(0, scrollY);

  document.getElementById('result-count').textContent =
    `${data.total} ${t('von', 'of')} ${(facets.stats || {}).distinct || data.total} ${t('Karten', 'cards')}`;
  armLoadMore();
  if (selecting && !append) renderSelectBar();
}

/* Sentinel at the end of the grid: once it comes into view, more is loaded. */
let loadMoreObserver = null;
function armLoadMore() {
  const grid = document.getElementById('card-grid');
  const old = document.getElementById('load-more-sentinel');
  if (old) old.remove();
  if (loadMoreObserver) loadMoreObserver.disconnect();
  if (!pageHasMore) return;

  const sentinel = document.createElement('div');
  sentinel.id = 'load-more-sentinel';
  grid.appendChild(sentinel);
  loadMoreObserver = new IntersectionObserver((entries) => {
    if (entries.some((e) => e.isIntersecting)) {
      loadMoreObserver.disconnect();
      runQuery(true);
    }
  }, { rootMargin: '600px' });
  loadMoreObserver.observe(sentinel);
}

function buildCardTile(g) {
  const img = cardImage(g.rep, 'small');
  const flags = g.langs
    .map((l) => `<span class="lang-flag" title="${escapeHTML(l.toUpperCase())}">${languageFlag(l) || escapeHTML(l.toUpperCase())}</span>`)
    .join('');

  const info = setIndex[g.setCode.toLowerCase()] || null;
  const setName = (info && info.name) || g.setName || g.setCode;
  const icon = setSymbolURL(info && info.iconSvgUri);
  const rarity = (g.rarity || '').toLowerCase();
  const rarityCls = rarity ? ' rarity-' + rarity : '';
  const rarityLabel = RARITY_LABEL[rarity] || g.rarity || '';
  // Set symbol tinted by rarity colour (like on a real card) via CSS mask.
  const sym = icon
    ? `<span class="set-sym${rarityCls}" style="-webkit-mask-image:url('${escapeHTML(icon)}');mask-image:url('${escapeHTML(icon)}')"></span>`
    : '';

  // Grid groups carry row ids: the detail dialog may edit them.
  g.editable = true;
  const tile = document.createElement('div');
  tile.className = 'card-tile' + (selectedGroups.has(g.key) ? ' selected' : '');
  tile.dataset.key = g.key;
  tilesByKey.set(g.key, tile);
  groupsByKey.set(g.key, g);
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
    if (selecting) {
      toggleSelected(g, tile);
      return;
    }
    openGroup(g);
  });
  return tile;
}

function openGroup(g) {
  modalGroup = g;
  openCardModal(cardGroupModalHTML(g));
  loadCardVariants(g);
}

/* ---- multi selection: move many cards into one binder ---- */

function toggleSelected(g, tile) {
  if (selectedGroups.has(g.key)) selectedGroups.delete(g.key);
  else selectedGroups.set(g.key, g);
  tile.classList.toggle('selected', selectedGroups.has(g.key));
  renderSelectBar();
}

function setSelecting(on) {
  selecting = on;
  if (!on) {
    selectedGroups.clear();
    tilesByKey.forEach((tile) => tile.classList.remove('selected'));
  }
  document.body.classList.toggle('selecting', on);
  const btn = document.getElementById('select-mode');
  if (btn) btn.textContent = on ? t('Auswahl beenden', 'Stop selecting') : t('Auswählen', 'Select');
  renderSelectBar();
}

/* The collection rows behind the selection: in a binder view only the rows
   in that binder (the tiles show only those), else all of them. */
function selectedRowIds() {
  const ids = [];
  let copies = 0;
  selectedGroups.forEach((g) => g.variants.forEach((v) => {
    if (v.id && (!FIXED_BINDER || (v.binderName || '') === BINDER_NAME)) {
      ids.push(v.id);
      copies += v.quantity;
    }
  }));
  return { ids, copies };
}

async function renderSelectBar() {
  let bar = document.getElementById('select-bar');
  if (!selecting) {
    if (bar) bar.remove();
    return;
  }
  if (!bar) {
    bar = document.createElement('div');
    bar.id = 'select-bar';
    bar.className = 'select-bar';
    bar.innerHTML = `
      <span class="select-count" id="select-count"></span>
      <button type="button" id="select-all"></button>
      <button type="button" id="select-none">${t('Keine', 'None')}</button>
      ${binderInputHTML('select-binder', '')}
      <select id="select-binder-type" title="${t('Typ, falls der Ordner neu ist', 'Type, if the folder is new')}">
        <option value="binder">${t('Ordner', 'Binder')}</option>
        <option value="list">${t('Liste', 'List')}</option>
        <option value="deck">Deck</option>
      </select>
      <button type="button" class="primary" id="select-move">${t('Verschieben', 'Move')}</button>`;
    document.body.appendChild(bar);
    bar.querySelector('#select-all').addEventListener('click', selectAllMatching);
    bar.querySelector('#select-none').addEventListener('click', () => {
      selectedGroups.clear();
      tilesByKey.forEach((tile) => tile.classList.remove('selected'));
      renderSelectBar();
    });
    bar.querySelector('#select-move').addEventListener('click', moveSelection);
    ensureBinderDatalist();
  }
  const { copies } = selectedRowIds();
  bar.querySelector('#select-count').textContent =
    t(`${selectedGroups.size} ausgewählt (${copies} Karten)`, `${selectedGroups.size} selected (${copies} cards)`);
  const all = bar.querySelector('#select-all');
  all.textContent = t(`Alle (${resultTotal})`, `All (${resultTotal})`);
  all.disabled = resultTotal === 0 || selectedGroups.size >= resultTotal;
  bar.querySelector('#select-none').disabled = selectedGroups.size === 0;
  // Binders holding the editions of the selection come first (key = "SET|…").
  setBinderSets('select-binder', [...selectedGroups.keys()].map((k) => k.split('|')[0]));
  bar.querySelector('#select-move').disabled = copies === 0;
}

/* Selects every card behind the current filter, not only the loaded pages:
   the server sends the keys and rows of all of them. Tiles loaded later are
   marked as selected when they are built. */
async function selectAllMatching() {
  const btn = document.getElementById('select-all');
  if (btn) btn.disabled = true;
  const p = currentParams();
  p.delete('sort');
  try {
    const res = await fetch(API + '/cards/selection?' + p.toString(), { cache: 'no-cache' });
    if (!res.ok) throw new Error('HTTP ' + res.status);
    const data = await res.json();
    (data.groups || []).forEach((g) => selectedGroups.set(g.key, groupsByKey.get(g.key) || g));
    tilesByKey.forEach((tile, key) => tile.classList.toggle('selected', selectedGroups.has(key)));
  } catch (e) {
    showNotice(t('Auswahl fehlgeschlagen: ', 'Selecting failed: ') + e.message, true);
  }
  renderSelectBar();
}

async function moveSelection() {
  const { ids } = selectedRowIds();
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
    if (r.volatile) msg += ' ' + t('Karten aus ManaBox kommen beim nächsten Import wieder in ihren alten Ordner.', 'Cards from ManaBox return to their old folder with the next import.');
    showNotice(msg);
    bindersCache = null;
    setSelecting(false);
    await refreshAfterWrite(r.binderName || '');
  } catch (e) {
    showNotice(t('Verschieben fehlgeschlagen: ', 'Moving failed: ') + e.message, true);
    btn.disabled = false;
  }
}

/* After a write: facets (binders, counts) and the grid anew, in the same
   view. Only when cards were moved out of the binder on show and it is now
   empty does the view follow them to `movedTo`. */
async function refreshAfterWrite(movedTo) {
  try {
    const res = await fetch(facetsURL(), { cache: 'no-cache' });
    if (res.ok) {
      facets = await res.json();
      // The key figures are those of the binder on show: none left, it is empty.
      if (FIXED_BINDER && movedTo !== undefined && movedTo !== BINDER_NAME && !(facets.stats || {}).entries) {
        location.href = binderPageURL(movedTo);
        return;
      }
      renderStats();
      renderSetOptions();
    }
  } catch (e) { /* the grid still reloads */ }
  await runQuery(false, true);
}

/* After moving one card from the detail dialog: stays put unless the binder
   on show has no cards left. */
async function followIfBinderEmptied(movedTo) {
  if (!FIXED_BINDER) return;
  try {
    const res = await fetch(facetsURL(), { cache: 'no-cache' });
    if (!res.ok) return;
    const f = await res.json();
    if ((f.stats || {}).entries) return;
  } catch (e) { return; }
  document.getElementById('card-modal').classList.remove('open');
  await refreshAfterWrite(movedTo);
}

/* ---- editing single rows in the detail dialog ---- */

/* Puts a row as the server now has it into the group, like applyWrite in the
   app: removed rows go, the row replaces the one with its id or is added
   (taking over the price of a row with the same finish). */
function applyRow(g, row, removed) {
  g.variants = g.variants.filter((v) => !(removed || []).includes(v.id));
  if (row) {
    const fields = {
      id: row.id, origin: row.origin, language: row.language, foil: row.foil, condition: row.condition,
      quantity: row.quantity, binderName: row.binderName, binderType: row.binderType,
      grader: row.grader || '', grade: row.grade || 0, signed: !!row.signed,
    };
    const existing = g.variants.find((v) => v.id === row.id);
    if (existing) Object.assign(existing, fields);
    else {
      const twin = g.variants.find((v) => v.foil === row.foil) || g.variants[0] || {};
      g.variants.push({ ...twin, added: row.added, ...fields });
    }
  }
  g.totalQty = g.variants.reduce((a, v) => a + v.quantity, 0);
  g.langs = [...new Set(g.variants.map((v) => v.language))];
  g.anyFoil = g.variants.some((v) => v.foil && v.foil !== 'normal');
  // The tile follows; a card with no copies left leaves the grid.
  const tile = tilesByKey.get(g.key);
  if (tile) {
    if (g.variants.length) tile.replaceWith(buildCardTile(g));
    else { tile.remove(); tilesByKey.delete(g.key); }
  }
  if (g.variants.length) openGroup(g);
  else document.getElementById('card-modal').classList.remove('open');
}

/* The printings of the open card as <option>s, the current one first; the
   list comes from loadCardVariants (or is fetched if that has not finished). */
function printOptionsHTML(g) {
  const cur = { setCode: g.setCode, collectorNumber: g.collectorNumber, setName: g.setName };
  const others = (g.prints || []).filter((p) => !((p.setCode || '').toLowerCase() === g.setCode.toLowerCase() && String(p.collectorNumber) === String(g.collectorNumber)));
  return [cur, ...others].map((p) => {
    const label = `${p.setName || p.setCode} (${String(p.setCode).toUpperCase()} #${p.collectorNumber})`;
    return `<option value="${escapeHTML(String(p.setCode).toUpperCase() + '|' + p.collectorNumber)}">${escapeHTML(label)}</option>`;
  }).join('');
}

async function editRow(act, id) {
  const g = modalGroup;
  const v = g && g.variants.find((x) => x.id === id);
  if (!v) return;
  try {
    if (act === 'minus') {
      const r = await writeAPI('PATCH', `/collection/cards/${id}`, { quantityDelta: -1 });
      applyRow(g, r.row, r.deleted ? [id] : []);
      if (v.origin === 'csv') showNotice(t('Geändert – aber das ist eine ManaBox-Zeile: der nächste Import setzt sie zurück.', 'Changed – but this is a ManaBox row: the next import resets it.'));
    } else if (act === 'plus') {
      const r = await writeAPI('POST', '/collection/cards', { items: [{
        setCode: g.setCode.toLowerCase(), collectorNumber: g.collectorNumber, quantity: 1,
        foil: v.foil || 'normal', language: v.language || 'en', condition: v.condition || 'near_mint',
        binderName: v.binderName || '', binderType: v.binderName ? (v.binderType || 'binder') : '',
      }] });
      applyRow(g, r.items[0], []);
    } else if (act === 'move') {
      const box = document.getElementById('row-edit');
      await ensureBinderDatalist();
      box.innerHTML = `
        <span>${t('Verschieben nach', 'Move to')}:</span>
        ${binderInputHTML('row-binder', v.binderName, [g.setCode])}
        <select id="row-binder-type"><option value="binder">${t('Ordner', 'Binder')}</option><option value="list">${t('Liste', 'List')}</option><option value="deck">Deck</option></select>
        <button type="button" class="primary" data-act="move-ok" data-id="${id}">OK</button>`;
      document.getElementById('row-binder').focus();
    } else if (act === 'edit') {
      if (!g.prints) await loadCardVariants(g);
      // Language, finish and condition of the row; a key that exists already
      // in the same binder takes the copies over (the server merges).
      const opts = (list, cur, label) => (list.includes(cur) || !cur ? list : [cur, ...list])
        .map((x) => `<option value="${escapeHTML(x)}"${x === cur ? ' selected' : ''}>${escapeHTML(label(x))}</option>`).join('');
      document.getElementById('row-edit').innerHTML = `
        <span>${t('Ändern', 'Change')}:</span>
        <select id="row-print" title="${t('Edition', 'Printing')}">${printOptionsHTML(g)}</select>
        <select id="row-lang" title="${t('Sprache', 'Language')}">${opts(CARD_LANGS, v.language || 'en', (l) => `${languageFlag(l)} ${l.toUpperCase()}`)}</select>
        <select id="row-foil" title="Foil">${opts(['normal', 'foil', 'etched'], v.foil || 'normal', (f) => (f === 'normal' ? t('Normal', 'Normal') : f === 'foil' ? 'Foil' : 'Etched'))}</select>
        <select id="row-cond" title="${t('Zustand', 'Condition')}">${opts(CONDITION_ORDER, v.condition || 'near_mint', conditionLabel)}</select>
        ${gradingFieldsHTML(v)}
        <button type="button" class="primary" data-act="edit-ok" data-id="${id}">OK</button>
        <button type="button" data-act="edit-cancel" data-id="${id}">${t('Abbrechen', 'Cancel')}</button>
        ${v.origin === 'csv' || v.quantity > 1 ? `<p class="row-edit-hint">${t(
          'Gegradet oder unterschrieben ist immer ein einzelnes Exemplar: es wird als eigene Zeile abgespalten.' + (v.origin === 'csv' ? ' Nimm es in ManaBox heraus, sonst zählt es nach dem nächsten Import doppelt.' : ''),
          'Graded or signed is always a single copy: it is split off into a row of its own.' + (v.origin === 'csv' ? ' Take it out in ManaBox, or it counts twice after the next import.' : ''))}</p>` : ''}`;
      wireGradingFields();
      document.getElementById('row-print').focus();
    } else if (act === 'edit-cancel') {
      document.getElementById('row-edit').innerHTML = '';
    } else if (act === 'edit-ok') {
      const patch = {};
      const lang = document.getElementById('row-lang').value;
      const foil = document.getElementById('row-foil').value;
      const cond = document.getElementById('row-cond').value;
      const [set, num] = document.getElementById('row-print').value.split('|');
      const printChanged = set.toLowerCase() !== g.setCode.toLowerCase() || num !== g.collectorNumber;
      if (printChanged) Object.assign(patch, { setCode: set.toLowerCase(), collectorNumber: num });
      if (lang !== (v.language || 'en')) patch.language = lang;
      if (foil !== (v.foil || 'normal')) patch.foil = foil;
      if (cond !== (v.condition || 'near_mint')) patch.condition = cond;
      const gr = gradingFieldsValue();
      if (gr === null) return showNotice(t('Bitte den Namen der Grading-Firma eingeben.', 'Please enter the name of the grading company.'), true);
      if (gr.grader !== (v.grader || '') || (gr.grader && gr.grade !== (v.grade || 0))) Object.assign(patch, { grader: gr.grader, grade: gr.grade });
      if (gr.signed !== !!v.signed) patch.signed = gr.signed;
      if (!Object.keys(patch).length) {
        document.getElementById('row-edit').innerHTML = '';
        return;
      }
      const r = await writeAPI('PATCH', `/collection/cards/${id}`, patch);
      // A graded or signed copy split off: what is left of its row.
      if (r.source) Object.assign(v, { quantity: r.source.quantity });
      if (printChanged) {
        // The copies now belong to another card: they leave this one, and
        // the grid reloads to show them where they are now.
        if (r.source) applyRow(g, r.source, []);
        else applyRow(g, null, [id]);
        showNotice(t(`Nach ${r.row.setName} (${r.row.setCode} #${r.row.collectorNumber}) geändert.`,
          `Changed to ${r.row.setName} (${r.row.setCode} #${r.row.collectorNumber}).`));
        await refreshAfterWrite();
        return;
      }
      applyRow(g, r.row, r.mergedInto || r.sourceDeleted ? [id] : []);
      if (r.source || r.sourceDeleted) showNotice(t('Ein Exemplar als eigene Zeile abgespalten.', 'One copy split off into a row of its own.'));
      else if (r.mergedInto) showNotice(t('Geändert und mit dem vorhandenen Eintrag zusammengeführt.', 'Changed and merged with the existing entry.'));
      if (v.origin === 'csv' && !r.source && !r.sourceDeleted) showNotice(t('Geändert – aber das ist eine ManaBox-Zeile: der nächste Import setzt sie zurück.', 'Changed – but this is a ManaBox row: the next import resets it.'));
    } else if (act === 'move-ok') {
      const name = binderValue('row-binder');
      if (name === null) return showNotice(t('Bitte einen Namen für den neuen Ordner eingeben.', 'Please enter a name for the new folder.'), true);
      if (name === (v.binderName || '')) return;
      const r = await writeAPI('PATCH', `/collection/cards/${id}`, { binderName: name, binderType: document.getElementById('row-binder-type').value });
      bindersCache = null;
      applyRow(g, r.row, r.mergedInto ? [id] : []);
      // The source binder may now be empty: then the view follows the card.
      if (FIXED_BINDER && BINDER_NAME === (v.binderName || '')) await followIfBinderEmptied(name);
      if (v.origin === 'csv') showNotice(t('Verschoben – Karten aus ManaBox kommen beim nächsten Import wieder in ihren alten Ordner.', 'Moved – cards from ManaBox return to their old folder with the next import.'));
    }
  } catch (e) {
    showNotice(t('Ändern fehlgeschlagen: ', 'Change failed: ') + e.message, true);
  }
}

document.addEventListener('click', (e) => {
  const btn = e.target.closest('#card-modal [data-act]');
  if (btn) editRow(btn.dataset.act, Number(btn.dataset.id));
});
document.addEventListener('keydown', (e) => {
  if (e.key === 'Enter' && ['row-binder', 'row-binder-new', 'row-print', 'row-lang', 'row-foil', 'row-cond'].includes(e.target.id)) {
    const ok = document.querySelector('#row-edit [data-act="move-ok"], #row-edit [data-act="edit-ok"]');
    if (ok) ok.click();
  }
});

function renderGrid(groups) {
  renderIncremental(document.getElementById('card-grid'), groups, buildCardTile);
}

/* The random strip above the filters. The backend picks the cards, so the
   whole collection does not have to sit in the browser for it. */
async function renderRandomStrip() {
  const el = document.getElementById('random-strip');
  if (!el) return;
  const cfg = UI_CONFIG || {};
  if (!cfg.randomEnabled) {
    el.style.display = 'none';
    el.innerHTML = '';
    return;
  }
  let picks = [];
  try {
    const res = await fetch(API + '/cards/random?count=' + encodeURIComponent(cfg.randomCount || 6), { cache: 'no-store' });
    if (res.ok) picks = (await res.json()).map(adaptGroup);
  } catch (e) {
    /* The strip is decoration - if it fails, the page stays usable. */
  }
  if (!picks.length) {
    el.style.display = 'none';
    return;
  }
  el.style.display = '';
  el.innerHTML = `<h2 class="random-title">${t('Zufällige Karten aus deiner Sammlung', 'Random cards from your collection')}</h2>`;
  const row = document.createElement('div');
  row.className = 'random-row';
  picks.forEach((g) => row.appendChild(buildCardTile(g)));
  el.appendChild(row);
}

async function init() {
  // Old links into the grid filtered to a binder now open the binder page.
  const legacyBinder = new URLSearchParams(location.search).get('binder');
  if (!BINDER_VIEW && legacyBinder) return location.replace(binderPageURL(legacyBinder));
  if (BINDER_VIEW && !FIXED_BINDER) return location.replace('binders.html');
  if (BINDER_VIEW) renderBinderHead();
  rememberSelect('sort-by', 'mtg_sort_cards');
  initModal();
  showLoading(BINDER_VIEW ? t('Lade Ordner...', 'Loading folder...') : t('Lade Sammlung...', 'Loading collection...'));
  try {
    [facets, setIndex, officialSubtypes] = await Promise.all([
      fetch(facetsURL(), { cache: 'no-cache' }).then((r) => {
        if (!r.ok) throw new Error('HTTP ' + r.status);
        return r.json();
      }),
      loadSetIndex(),
      loadSubtypes().then((x) => new Set(x)).catch(() => new Set()),
    ]);
    // Needed before the first card modal opens, so its marker starts correct.
    await loadWishlistNames();
  } catch (e) {
    hideLoading();
    document.getElementById('card-grid').innerHTML = `<p style="color:#e05656">${t('Fehler beim Laden der Sammlung', 'Error loading collection')}: ${escapeHTML(e.message)}</p>`;
    return;
  }
  hideLoading();

  if (BINDER_VIEW && !(facets.stats || {}).entries) {
    renderEmptyBinder();
    return;
  }
  if (!facets.stats || !facets.stats.entries) {
    renderEmptyState();
    return;
  }

  populateFilters();
  populateSetFilter();
  populateSubtypes('');

  renderStats();
  await runQuery();
  await uiConfigReady;
  renderRandomStrip();

  // Typing triggers a query - debounced, so not every keystroke becomes a
  // request.
  document.getElementById('search').addEventListener('input', debounce(() => runQuery(), 250));
  ['filter-rarity', 'filter-color', 'filter-foil', 'filter-style', 'filter-subtype', 'sort-by'].forEach((id) => {
    document.getElementById(id).addEventListener('change', () => runQuery());
  });
  document.getElementById('select-mode').addEventListener('click', () => setSelecting(!selecting));
  // Locked again: no more editing.
  document.addEventListener('auth-change', (e) => { if (!e.detail.unlocked && selecting) setSelecting(false); });
  document.getElementById('filter-type').addEventListener('change', (e) => {
    populateSubtypes(e.target.value);
    runQuery();
  });
}

/* Head of the binder page: back to the overview of its kind, the name, and
   its type. Folders and lists share the page; the nav marks the right one. */
async function renderBinderHead() {
  if (FIXED_BINDER === NO_BINDER) {
    const title = t('Ohne Ordner', 'No folder');
    document.getElementById('binder-title').textContent = title;
    document.title = 'MagicPortal – ' + title;
    document.getElementById('binder-kind').textContent = t('Karten', 'Cards');
    document.getElementById('binder-icon').textContent = '📭';
    document.getElementById('binder-back').textContent = '← ' + t('Alle Ordner', 'All folders');
    document.querySelector('nav.topnav a[href="binders.html"]').classList.add('active');
    return;
  }
  document.getElementById('binder-title').textContent = FIXED_BINDER;
  document.title = 'MagicPortal – ' + FIXED_BINDER;
  const b = (await loadBinderList()).find((x) => x.name === FIXED_BINDER);
  const type = ((b && b.type) || 'binder').toLowerCase();
  const isList = type === 'list';
  const isDeck = type === 'deck';
  const back = document.getElementById('binder-back');
  back.href = isDeck ? 'decks.html' : isList ? 'lists.html' : 'binders.html';
  back.textContent = '← ' + (isDeck ? 'Decks' : isList ? t('Alle Listen', 'All lists') : t('Alle Ordner', 'All folders'));
  document.getElementById('binder-kind').textContent = isDeck ? 'Deck' : isList ? t('Liste', 'List') : t('Ordner', 'Folder');
  document.getElementById('binder-icon').textContent = isDeck ? '🃏' : isList ? '📋' : '📁';
  document.querySelectorAll('nav.topnav a').forEach((a) => {
    if (a.getAttribute('href') === back.getAttribute('href')) a.classList.add('active');
  });
  if (b) renderNoteBox(document.getElementById('binder-note'), FIXED_BINDER, b.note || '');
}

function renderEmptyBinder() {
  document.getElementById('stats-bar').innerHTML = '';
  document.getElementById('result-count').textContent = '';
  const controls = document.querySelector('.controls');
  if (controls) controls.style.display = 'none';
  document.getElementById('card-grid').innerHTML =
    `<p class="section-hint">${t('Hier liegen noch keine Karten.', 'No cards here yet.')}</p>`;
}

function renderEmptyState() {
  document.getElementById('stats-bar').innerHTML = '';
  document.getElementById('result-count').textContent = '';
  const controls = document.querySelector('.controls');
  if (controls) controls.style.display = 'none';
  const grid = document.getElementById('card-grid');
  stopIncremental(grid);
  grid.innerHTML = `
    <div class="empty-state">
      <img src="images/magic-portal-logo.svg" alt="Magic Portal – Scan · Collect · Track" class="empty-logo">
      <h2>${t('Deine Sammlung ist noch leer', 'Your collection is empty')}</h2>
      <p>${t(
        'Lade oben rechts über <strong>„🔑 Login"</strong> → <strong>„⬆ CSV hochladen"</strong> deinen ManaBox-CSV-Export hoch, um deine Karten hier zu sehen.',
        'Upload your ManaBox CSV export via <strong>“🔑 Login”</strong> → <strong>“⬆ Upload CSV”</strong> in the top right to see your cards here.'
      )}</p>
    </div>`;
}

init();
