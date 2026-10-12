/* The page no longer keeps a collection in memory: search, filters, grouping
   and sorting are done by /api/cards, and only the currently visible page
   lives here. That saves around 10 MB of JSON on load and makes the page
   usable on a phone; in exchange every filter change costs a query (~60 ms). */
let facets = null;        // values for the filter fields, from /api/cards/facets
let setIndex = {};
let selectedSet = '';     // set code in the custom dropdown ('' = all)
let pageOffset = 0;       // next start index
let pageHasMore = false;
let loadingPage = false;
let queryToken = 0;       // discards responses of superseded queries

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
function populateSetFilter() {
  const opts = document.getElementById('set-options');
  const list = (facets.sets || [])
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

  const binderSel = document.getElementById('filter-binder');
  if (binderSel) (facets.binders || []).forEach((b) => addOpt(binderSel, b, b));

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
  pick('filter-binder', 'binder');
  pick('filter-type', 'type');
  pick('filter-subtype', 'subtype');
  pick('sort-by', 'sort');
  return p;
}

/* Fetches one page of results. append=false replaces the grid (new filters),
   append=true appends the next page (scrolling). */
async function runQuery(append = false) {
  // Only the scroll-driven loading may block itself. A new filter query must
  // never be dropped, otherwise the page silently keeps the previous result;
  // superseded responses are caught by queryToken.
  if (append && loadingPage) return;
  loadingPage = true;
  const token = ++queryToken;
  const p = currentParams();
  if (append) p.set('offset', String(pageOffset));

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

  const grid = document.getElementById('card-grid');
  if (!append) {
    stopIncremental(grid);
    grid.innerHTML = '';
  }
  const frag = document.createDocumentFragment();
  groups.forEach((g) => frag.appendChild(buildCardTile(g)));
  grid.appendChild(frag);

  document.getElementById('result-count').textContent =
    `${data.total} ${t('von', 'of')} ${(facets.stats || {}).distinct || data.total} ${t('Karten', 'cards')}`;
  armLoadMore();
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
  initModal();
  showLoading(t('Lade Sammlung...', 'Loading collection...'));
  try {
    [facets, setIndex, officialSubtypes] = await Promise.all([
      fetch(API + '/cards/facets', { cache: 'no-cache' }).then((r) => {
        if (!r.ok) throw new Error('HTTP ' + r.status);
        return r.json();
      }),
      loadSetIndex(),
      loadSubtypes().then((x) => new Set(x)).catch(() => new Set()),
    ]);
  } catch (e) {
    hideLoading();
    document.getElementById('card-grid').innerHTML = `<p style="color:#e05656">${t('Fehler beim Laden der Sammlung', 'Error loading collection')}: ${escapeHTML(e.message)}</p>`;
    return;
  }
  hideLoading();

  if (!facets.stats || !facets.stats.entries) {
    renderEmptyState();
    return;
  }

  populateFilters();
  populateSetFilter();
  populateSubtypes('');

  // Optional deep-link from the Ordner/Listen pages: index.html?binder=<name>
  const binderParam = new URLSearchParams(location.search).get('binder');
  if (binderParam) {
    const binderSel = document.getElementById('filter-binder');
    if ([...binderSel.options].some((o) => o.value === binderParam)) binderSel.value = binderParam;
  }

  renderStats();
  await runQuery();
  await uiConfigReady;
  renderRandomStrip();

  // Typing triggers a query - debounced, so not every keystroke becomes a
  // request.
  document.getElementById('search').addEventListener('input', debounce(() => runQuery(), 250));
  ['filter-rarity', 'filter-color', 'filter-foil', 'filter-binder', 'filter-subtype', 'sort-by'].forEach((id) => {
    document.getElementById(id).addEventListener('change', () => runQuery());
  });
  document.getElementById('filter-type').addEventListener('change', (e) => {
    populateSubtypes(e.target.value);
    runQuery();
  });
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
      <img src="images/magic-portal-logo.svg" alt="Magic Portal – A ManaBox Interface" class="empty-logo">
      <h2>${t('Deine Sammlung ist noch leer', 'Your collection is empty')}</h2>
      <p>${t(
        'Lade oben rechts über <strong>„🔑 Login"</strong> → <strong>„⬆ CSV hochladen"</strong> deinen ManaBox-CSV-Export hoch, um deine Karten hier zu sehen.',
        'Upload your ManaBox CSV export via <strong>“🔑 Login”</strong> → <strong>“⬆ Upload CSV”</strong> in the top right to see your cards here.'
      )}</p>
    </div>`;
}

init();
