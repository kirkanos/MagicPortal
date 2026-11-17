/* Decks: binders of type "deck". The overview shows each one as a deck box
   with the art of a chosen card on the front; a deck opens as a grid (like
   everywhere), a text list grouped by card type, or stacks of cards per type.
   Cards come in from the collection (one copy is moved out of its binder) and
   go back out into a binder of choice. */

const DECK_VIEW_KEY = 'mtg_deck_view';
const deckParams = new URLSearchParams(location.search);
const deckName = deckParams.get('deck');

let deckBinders = [];
let deckGroups = [];
// What the current view shows; data-idx points into it.
let viewItems = [];
let deckView = 'list';
try { deckView = localStorage.getItem(DECK_VIEW_KEY) || 'list'; } catch (e) { /* ignore */ }

/* The art crop of a printing - the picture without frame and text. */
function artCropURL(imageId) {
  if (!imageId || imageId.length < 2) return '';
  return `${imageBase()}/art_crop/front/${imageId[0]}/${imageId[1]}/${imageId}.jpg`;
}

function groupImage(g, size) {
  return setCardImage({ imageId: g.imageId, hasBack: g.hasBack }, size || 'normal');
}

const DECK_TINT = { W: '#c9c0a0', U: '#3d79b0', B: '#5b5266', R: '#b44a3c', G: '#3f8a50' };

function colorPipsHTML(colors) {
  if (!colors || !colors.length) return manaSymbolImg('C');
  return colors.map((c) => manaSymbolImg(c)).join('');
}

/* ---- card types: the groups of the list and stack views ---- */

const DECK_TYPES = [
  { key: 'creature', de: 'Kreaturen', en: 'Creatures', icon: '🐾' },
  { key: 'planeswalker', de: 'Planeswalker', en: 'Planeswalkers', icon: '✶' },
  { key: 'battle', de: 'Schlachten', en: 'Battles', icon: '⚔' },
  { key: 'instant', de: 'Spontanzauber', en: 'Instants', icon: '⚡' },
  { key: 'sorcery', de: 'Hexereien', en: 'Sorceries', icon: '🌀' },
  { key: 'artifact', de: 'Artefakte', en: 'Artifacts', icon: '⚙' },
  { key: 'enchantment', de: 'Verzauberungen', en: 'Enchantments', icon: '✧' },
  { key: 'land', de: 'Länder', en: 'Lands', icon: '⛰' },
  { key: 'other', de: 'Sonstige', en: 'Other', icon: '•' },
];

/* The group of a card: the front face decides, creatures before artifacts
   (an artifact creature is a creature), lands before artifacts. */
function deckTypeOf(typeLine) {
  const front = (typeLine || '').split('//')[0].toLowerCase();
  for (const k of ['creature', 'planeswalker', 'battle', 'instant', 'sorcery', 'land', 'artifact', 'enchantment']) {
    if (front.includes(k)) return k;
  }
  return 'other';
}

function groupByType(groups) {
  const by = new Map(DECK_TYPES.map((d) => [d.key, []]));
  for (const g of groups) by.get(deckTypeOf(g.typeLine)).push(g);
  for (const list of by.values()) list.sort((a, b) => a.name.localeCompare(b.name));
  return DECK_TYPES.map((d) => ({ ...d, cards: by.get(d.key) })).filter((d) => d.cards.length);
}

/* List and stacks count a card once, whatever printings it comes in. */
function mergeByName(groups) {
  const by = new Map();
  for (const g of groups) {
    // The rows say which printing they are; the merged card has no single one.
    const variants = (g.variants || []).map((v) => ({ ...v, printing: `${g.setCode} #${g.collectorNumber}` }));
    const m = by.get(g.name);
    if (!m) {
      by.set(g.name, { ...g, variants });
    } else {
      m.totalQty += g.totalQty;
      m.variants.push(...variants);
      if (!m.imageId && g.imageId) Object.assign(m, { imageId: g.imageId, hasBack: g.hasBack, setCode: g.setCode, collectorNumber: g.collectorNumber });
    }
  }
  return [...by.values()];
}

const sumQty = (cards) => cards.reduce((n, g) => n + g.totalQty, 0);
const sumValue = (cards) => cards.reduce((n, g) => n + (g.variants || []).reduce((m, v) => m + (v.marketPrice || 0) * v.quantity, 0), 0);

/* ---- overview: deck boxes ---- */

async function loadDeckBinders() {
  try {
    const res = await fetch(API + '/binders', { cache: 'no-store' });
    deckBinders = res.ok ? await res.json() : [];
  } catch (e) {
    deckBinders = [];
  }
  return deckBinders.filter((b) => (b.type || '').toLowerCase() === 'deck' && b.name);
}

function deckBoxHTML(d) {
  const art = d.cover ? artCropURL(d.cover.imageId) : '';
  // The lid in the deck's colours: one, or a gradient over several.
  const tints = (d.colors || []).map((c) => DECK_TINT[c]).filter(Boolean);
  const lid = tints.length ? `--tint:${tints[0]};--tint2:${tints[tints.length - 1]}` : '';
  return `
    <a class="deckbox" style="${lid}" href="decks.html?deck=${encodeURIComponent(d.name)}" title="${escapeHTML(d.name)}">
      <div class="deckbox-lid"><span class="deckbox-pips">${colorPipsHTML(d.colors)}</span></div>
      <div class="deckbox-front" ${art ? `style="background-image:url('${escapeHTML(art)}')"` : ''}>
        ${art ? '' : '<span class="deckbox-empty">🃏</span>'}
      </div>
      <div class="deckbox-label">
        <div class="deckbox-name">${escapeHTML(d.name)}</div>
        <div class="deckbox-meta"><span>${d.total.toLocaleString('de-DE')} ${t('Karten', 'cards')}</span><span class="money">${formatCurrency(d.marketValue, 'EUR')}</span></div>
      </div>
    </a>`;
}

async function renderDeckList() {
  const list = await loadDeckBinders();
  hideLoading();
  document.getElementById('deck-count').textContent = `${list.length} ${list.length === 1 ? 'Deck' : 'Decks'}`;
  const el = document.getElementById('deck-list');
  if (!list.length) {
    el.innerHTML = `<p class="section-hint">${t(
      'Noch keine Decks. Lege mit „+ Neues Deck“ eines an, oder stelle einen Ordner auf der Ordner-Seite (✎) auf den Typ „Deck“ um.',
      'No decks yet. Create one with “+ New deck”, or switch a folder to the type “Deck” on the Folders page (✎).')}</p>`;
    return;
  }
  el.innerHTML = list.map(deckBoxHTML).join('');
}

function newDeckDialog() {
  openDeckDialog(`
    <h2>${t('Neues Deck', 'New deck')}</h2>
    <form class="deck-form" id="deck-new-form">
      <input type="text" class="binder-input" name="name" maxlength="100" required autocomplete="off" placeholder="${t('Name des Decks', 'Deck name')}">
      <p class="section-hint">${t('Danach fügst du Karten aus deiner Sammlung hinzu – das Deck entsteht mit der ersten Karte.', 'Then add cards from your collection – the deck comes into being with the first card.')}</p>
      <div class="deck-form-actions"><button type="submit" class="upload-btn">${t('Anlegen', 'Create')}</button></div>
    </form>`);
  const form = document.getElementById('deck-new-form');
  form.elements.name.focus();
  form.addEventListener('submit', (e) => {
    e.preventDefault();
    const name = form.elements.name.value.trim();
    if (!name) return;
    const existing = deckBinders.find((b) => b.name === name);
    if (existing && (existing.type || '') !== 'deck') {
      showNotice(t(`„${name}“ gibt es schon als Ordner oder Liste.`, `“${name}” already exists as a folder or list.`), true);
      return;
    }
    location.href = `decks.html?deck=${encodeURIComponent(name)}&add=1`;
  });
}

/* ---- one deck ---- */

function currentDeck() {
  return deckBinders.find((b) => b.name === deckName) || { name: deckName, type: 'deck', total: 0, marketValue: 0, colors: [] };
}

async function loadDeckCards() {
  const res = await fetch(`${API}/cards?binder=${encodeURIComponent(deckName)}&limit=500&sort=name`, { cache: 'no-cache' });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const data = await res.json();
  deckGroups = data.groups || [];
}

function renderDeckBanner() {
  const d = currentDeck();
  const art = d.cover ? artCropURL(d.cover.imageId) : '';
  const qty = sumQty(deckGroups);
  document.getElementById('deck-banner').innerHTML = `
    <div class="deck-banner-art" ${art ? `style="background-image:url('${escapeHTML(art)}')"` : ''}></div>
    <div class="deck-banner-text">
      <h1>${escapeHTML(deckName)}</h1>
      <div class="deck-banner-meta">
        <span class="deckbox-pips">${colorPipsHTML(d.colors)}</span>
        <span>${qty} ${t('Karten', 'cards')}</span>
        <span class="money">${formatCurrency(sumValue(deckGroups), 'EUR')}</span>
        ${d.cover ? `<span class="deck-banner-cover">🖼 ${escapeHTML(d.cover.name)}</span>` : ''}
      </div>
    </div>`;
  document.title = `MagicPortal – ${deckName}`;
}

function renderDeckCards() {
  document.querySelectorAll('.view-switch button').forEach((b) => b.classList.toggle('active', b.dataset.view === deckView));
  const el = document.getElementById('deck-cards');
  if (!deckGroups.length) {
    el.innerHTML = `<p class="section-hint deck-empty">${t('Das Deck ist leer. Füge über „+ Karten hinzufügen“ Karten aus deiner Sammlung hinzu.', 'The deck is empty. Add cards from your collection with “+ Add cards”.')}</p>`;
    return;
  }
  if (deckView === 'grid') el.innerHTML = gridViewHTML();
  else if (deckView === 'stack') el.innerHTML = stackViewHTML();
  else el.innerHTML = listViewHTML();
}

function gridViewHTML() {
  viewItems = deckGroups;
  return '<div class="grid deck-grid">' + viewItems.map((g, i) => {
    const img = groupImage(g, 'small');
    return `
      <div class="card-tile" data-idx="${i}">
        <div class="thumb">
          ${img ? `<img loading="lazy" src="${img}" alt="${escapeHTML(g.name)}">` : `<div class="no-image">${t('Kein Bild', 'No image')}</div>`}
          ${g.anyFoil ? '<span class="foil-badge">Foil</span>' : ''}
          ${g.totalQty > 1 ? `<span class="qty-badge">×${g.totalQty}</span>` : ''}
        </div>
        <div class="info">
          <div class="name">${escapeHTML(g.name)}</div>
          <div class="meta"><span>${escapeHTML(g.setCode)} #${escapeHTML(g.collectorNumber)}</span><span>${g.marketPrice ? formatCurrency(g.marketPrice, 'EUR') : '–'}</span></div>
        </div>
      </div>`;
  }).join('') + '</div>';
}

function listViewHTML() {
  viewItems = mergeByName(deckGroups);
  const idx = new Map(viewItems.map((g, i) => [g, i]));
  return '<div class="deck-list-cols">' + groupByType(viewItems).map((sec) => `
    <section class="deck-list-sec">
      <h3>${sec.icon} ${t(sec.de, sec.en)} <span>(${sumQty(sec.cards)})</span></h3>
      ${sec.cards.map((g) => `
        <div class="deck-line" data-idx="${idx.get(g)}">
          <span class="deck-line-qty">${g.totalQty}</span>
          <span class="deck-line-name">${escapeHTML(g.name)}</span>
          <span class="deck-line-mana">${renderManaCost((g.manaCost || '').split('//')[0])}</span>
        </div>`).join('')}
    </section>`).join('') + '</div><div class="deck-preview" id="deck-preview"></div>';
}

function stackViewHTML() {
  viewItems = mergeByName(deckGroups);
  const idx = new Map(viewItems.map((g, i) => [g, i]));
  return '<div class="deck-stacks">' + groupByType(viewItems).map((sec) => `
    <section class="deck-stack">
      <h3><span>${sec.icon} ${t(sec.de, sec.en)}</span><span class="deck-stack-meta">${t('Anz.', 'Qty')} ${sumQty(sec.cards)} · ${formatCurrency(sumValue(sec.cards), 'EUR')}</span></h3>
      <div class="deck-stack-cards">
        ${sec.cards.map((g) => {
          const img = groupImage(g, 'normal');
          return `<div class="deck-stack-card" data-idx="${idx.get(g)}" title="${escapeHTML(g.name)}">
            ${img ? `<img loading="lazy" src="${img}" alt="${escapeHTML(g.name)}">` : `<div class="no-image">${escapeHTML(g.name)}</div>`}
            ${g.totalQty > 1 ? `<span class="deck-stack-qty">${g.totalQty}</span>` : ''}
          </div>`;
        }).join('')}
      </div>
    </section>`).join('') + '</div>';
}

/* ---- dialog ---- */

function openDeckDialog(html) {
  document.getElementById('deck-dialog-body').innerHTML = html;
  document.getElementById('deck-dialog').classList.add('open');
}

function closeDeckDialog() {
  document.getElementById('deck-dialog').classList.remove('open');
}

/* A card of the deck: its rows, take copies out into a binder, use it as the cover. */
function openDeckCard(g) {
  const img = groupImage(g, 'normal');
  const rows = (g.variants || []).map((v) => `
    <tr>
      <td>${escapeHTML(v.printing || `${g.setCode} #${g.collectorNumber}`)}</td>
      <td>${languageFlag(v.language) || ''} ${escapeHTML((v.language || '').toUpperCase())}</td>
      <td>${v.foil && v.foil !== 'normal' ? `<span class="foil-badge inline">${escapeHTML(v.foil)}</span>` : ''}</td>
      <td>${escapeHTML(conditionLabel(v.condition))}</td>
      <td class="num">×${v.quantity}</td>
      <td class="unlock-only-cell">${isUnlocked ? `<button type="button" class="reset-btn" data-act="out" data-id="${v.id}">${t('Herausnehmen…', 'Take out…')}</button>` : ''}</td>
    </tr>`).join('');
  openDeckDialog(`
    <div class="modal-card">
      <div class="modal-image">${img ? `<img src="${img}" alt="${escapeHTML(g.name)}">` : `<div class="no-image">${t('Kein Bild', 'No image')}</div>`}</div>
      <div class="modal-details">
        <h2>${escapeHTML(g.name)}</h2>
        <p class="section-hint">${escapeHTML(g.typeLine || '')}</p>
        <table class="lang-table deck-rows"><tbody>${rows}</tbody></table>
        <div id="deck-out"></div>
        ${isUnlocked && g.imageId ? `<button type="button" class="reset-btn" data-act="cover">🖼 ${t('Als Titelbild verwenden', 'Use as cover image')}</button>` : ''}
      </div>
    </div>`);
  const body = document.getElementById('deck-dialog-body');
  body.querySelectorAll('[data-act="out"]').forEach((b) => b.addEventListener('click', () => takeOutForm(g, Number(b.dataset.id))));
  const cover = body.querySelector('[data-act="cover"]');
  if (cover) cover.addEventListener('click', () => setCover(g));
}

function takeOutForm(g, id) {
  const v = g.variants.find((x) => x.id === id);
  if (!v) return;
  const qtyOpts = Array.from({ length: v.quantity }, (_, i) => `<option value="${i + 1}"${i + 1 === v.quantity ? ' selected' : ''}>${i + 1}</option>`).join('');
  const box = document.getElementById('deck-out');
  box.innerHTML = `
    <div class="row-edit deck-out-form">
      <span>${t('Herausnehmen', 'Take out')}</span>
      <select id="deck-out-qty">${qtyOpts}</select>
      <span>${t('nach', 'into')}</span>
      ${binderInputHTML('deck-out-binder', '')}
      ${binderTypeSelect('deck-out-binder-type')}
      <button type="button" class="upload-btn" id="deck-out-ok">OK</button>
    </div>`;
  document.getElementById('deck-out-ok').addEventListener('click', async () => {
    const name = binderValue('deck-out-binder');
    if (name === null) return showNotice(t('Bitte einen Namen für den neuen Ordner eingeben.', 'Please enter a name for the new folder.'), true);
    if (name === deckName) return;
    const qty = Number(document.getElementById('deck-out-qty').value);
    try {
      const r = await writeAPI('POST', '/collection/move', {
        ids: [id], binderName: name, binderType: document.getElementById('deck-out-binder-type').value,
        quantity: qty < v.quantity ? qty : 0,
      });
      showNotice(t(`${qty}× ${g.name} nach „${r.binderName || 'ohne Ordner'}“ verschoben.`, `${qty}× ${g.name} moved to “${r.binderName || 'no folder'}”.`)
        + (r.volatile ? ' ' + t('Karten aus ManaBox kommen beim nächsten Import wieder in ihren alten Ordner.', 'Cards from ManaBox return to their old folder with the next import.') : ''));
      closeDeckDialog();
      bindersCache = null;
      await refreshDeck();
    } catch (e) {
      showNotice(t('Verschieben fehlgeschlagen: ', 'Moving failed: ') + e.message, true);
    }
  });
}

function binderTypeSelect(id) {
  return `<select id="${id}" title="${t('Typ, falls der Ordner neu ist', 'Type, if the folder is new')}">
    <option value="binder">${t('Ordner', 'Binder')}</option><option value="list">${t('Liste', 'List')}</option><option value="deck">Deck</option></select>`;
}

async function setCover(g) {
  try {
    await writeAPI('POST', `/binders/${encodeURIComponent(deckName)}/cover`, { setCode: g.setCode.toLowerCase(), collectorNumber: g.collectorNumber });
    showNotice(t(`Titelbild: ${g.name}`, `Cover image: ${g.name}`));
    closeDeckDialog();
    await loadDeckBinders();
    renderDeckBanner();
  } catch (e) {
    showNotice(t('Titelbild konnte nicht gesetzt werden: ', 'Could not set the cover image: ') + e.message, true);
  }
}

/* Every card of the deck as an art crop to pick from. */
function coverPicker() {
  const seen = new Set();
  const choices = deckGroups.filter((g) => g.imageId && !seen.has(g.imageId) && seen.add(g.imageId));
  const cur = currentDeck().cover;
  openDeckDialog(`
    <h2>${t('Titelbild wählen', 'Choose a cover image')}</h2>
    <p class="section-hint">${t('Das Artwork der gewählten Karte kommt auf die Deckbox.', 'The artwork of the chosen card goes on the deck box.')}</p>
    <div class="cover-grid">
      ${choices.map((g) => `
        <button type="button" class="cover-choice${cur && cur.imageId === g.imageId ? ' selected' : ''}" data-idx="${deckGroups.indexOf(g)}" title="${escapeHTML(g.name)}">
          <img loading="lazy" src="${artCropURL(g.imageId)}" alt="${escapeHTML(g.name)}">
          <span>${escapeHTML(g.name)}</span>
        </button>`).join('')}
    </div>
    ${cur ? `<div class="deck-form-actions"><button type="button" class="reset-btn" id="cover-remove">${t('Titelbild entfernen', 'Remove cover image')}</button></div>` : ''}`);
  const body = document.getElementById('deck-dialog-body');
  body.querySelectorAll('.cover-choice').forEach((b) => b.addEventListener('click', () => setCover(deckGroups[Number(b.dataset.idx)])));
  const rm = document.getElementById('cover-remove');
  if (rm) rm.addEventListener('click', async () => {
    try {
      await writeAPI('POST', `/binders/${encodeURIComponent(deckName)}/cover`, { setCode: '' });
      closeDeckDialog();
      await loadDeckBinders();
      renderDeckBanner();
    } catch (e) {
      showNotice(e.message, true);
    }
  });
}

/* ---- adding cards from the collection ---- */

function addCardsDialog() {
  openDeckDialog(`
    <h2>${t('Karten hinzufügen', 'Add cards')}</h2>
    <p class="section-hint">${t('Suche in deiner Sammlung. „+1“ nimmt ein Exemplar aus seinem Ordner und legt es ins Deck. Neue Karten scannst du in der App direkt in das Deck.',
      'Search your collection. “+1” takes one copy out of its folder and puts it into the deck. New cards are scanned straight into the deck in the app.')}</p>
    <input type="search" class="binder-input deck-search" id="deck-search" placeholder="${t('Name, Text …', 'Name, text …')}" autocomplete="off">
    <div id="deck-search-results" class="deck-search-results"></div>`);
  const input = document.getElementById('deck-search');
  input.focus();
  input.addEventListener('input', debounce(() => searchCollection(input.value), 250));
}

let searchToken = 0;
async function searchCollection(q) {
  const box = document.getElementById('deck-search-results');
  q = q.trim();
  if (q.length < 2) {
    box.innerHTML = '';
    return;
  }
  const token = ++searchToken;
  let groups = [];
  try {
    const res = await fetch(`${API}/cards?q=${encodeURIComponent(q)}&limit=40&sort=name`, { cache: 'no-cache' });
    groups = res.ok ? (await res.json()).groups || [] : [];
  } catch (e) { /* ignore */ }
  if (token !== searchToken) return;
  const rows = [];
  for (const g of groups) {
    for (const v of g.variants || []) {
      if (v.binderName === deckName) continue;
      rows.push({ g, v });
    }
  }
  if (!rows.length) {
    box.innerHTML = `<p class="section-hint">${t('Nichts gefunden (Karten, die schon im Deck sind, stehen hier nicht).', 'Nothing found (cards already in the deck are not listed).')}</p>`;
    return;
  }
  box.innerHTML = rows.slice(0, 80).map(({ g, v }, i) => {
    const img = groupImage(g, 'small');
    return `
      <div class="deck-search-row">
        ${img ? `<img loading="lazy" src="${img}" alt="">` : '<span class="deck-search-noimg"></span>'}
        <div class="deck-search-info">
          <div class="deck-search-name">${escapeHTML(g.name)} <span class="deck-line-mana">${renderManaCost((g.manaCost || '').split('//')[0])}</span></div>
          <div class="section-hint">${escapeHTML(g.setCode)} #${escapeHTML(g.collectorNumber)} · ${languageFlag(v.language) || ''} ${escapeHTML((v.language || '').toUpperCase())}${v.foil && v.foil !== 'normal' ? ' · ' + escapeHTML(v.foil) : ''} · ${escapeHTML(v.binderName || t('ohne Ordner', 'no folder'))} · ×${v.quantity}</div>
        </div>
        <button type="button" class="upload-btn" data-row="${i}">+1</button>
      </div>`;
  }).join('');
  box.querySelectorAll('[data-row]').forEach((b) => b.addEventListener('click', async () => {
    const { g, v } = rows[Number(b.dataset.row)];
    b.disabled = true;
    try {
      const r = await writeAPI('POST', '/collection/move', { ids: [v.id], binderName: deckName, binderType: 'deck', quantity: v.quantity > 1 ? 1 : 0 });
      showNotice(t(`${g.name} aus „${v.binderName || 'ohne Ordner'}“ ins Deck gelegt.`, `${g.name} moved from “${v.binderName || 'no folder'}” into the deck.`)
        + (r.volatile ? ' ' + t('Karten aus ManaBox kommen beim nächsten Import wieder in ihren alten Ordner.', 'Cards from ManaBox return to their old folder with the next import.') : ''));
      await refreshDeck();
      searchCollection(document.getElementById('deck-search').value);
    } catch (e) {
      b.disabled = false;
      showNotice(t('Hinzufügen fehlgeschlagen: ', 'Adding failed: ') + e.message, true);
    }
  }));
}

async function refreshDeck() {
  await Promise.all([loadDeckBinders(), loadDeckCards()]);
  renderDeckBanner();
  renderDeckCards();
}

/* Hover preview of the list view (a mouse; a tap opens the card). */
function initListPreview(container) {
  container.addEventListener('mouseover', (e) => {
    const line = e.target.closest('.deck-line');
    const pv = document.getElementById('deck-preview');
    if (!line || !pv) return;
    const g = viewItems[Number(line.dataset.idx)];
    const img = g && groupImage(g, 'normal');
    pv.innerHTML = img ? `<img src="${img}" alt="">` : '';
    const r = line.getBoundingClientRect();
    const left = r.right + 260 < window.innerWidth ? r.right + 12 : Math.max(8, r.left - 260);
    pv.style.left = left + 'px';
    pv.style.top = Math.min(window.innerHeight - 350, Math.max(8, r.top - 100)) + 'px';
    pv.classList.toggle('show', !!img);
  });
  container.addEventListener('mouseleave', () => {
    const pv = document.getElementById('deck-preview');
    if (pv) pv.classList.remove('show');
  });
}

async function initDeckPage() {
  await uiConfigReady;
  document.getElementById('deck-dialog-close').addEventListener('click', closeDeckDialog);
  document.getElementById('deck-dialog').addEventListener('click', (e) => { if (e.target.id === 'deck-dialog') closeDeckDialog(); });
  document.addEventListener('keydown', (e) => { if (e.key === 'Escape') closeDeckDialog(); });

  if (!deckName) {
    showLoading(t('Lade Decks...', 'Loading decks...'));
    document.getElementById('deck-new').addEventListener('click', newDeckDialog);
    await renderDeckList();
    return;
  }

  document.getElementById('deck-list-view').hidden = true;
  document.getElementById('deck-view').hidden = false;
  document.querySelectorAll('.view-switch button').forEach((b) => b.addEventListener('click', () => {
    deckView = b.dataset.view;
    try { localStorage.setItem(DECK_VIEW_KEY, deckView); } catch (e) { /* ignore */ }
    renderDeckCards();
  }));
  const cardsEl = document.getElementById('deck-cards');
  cardsEl.addEventListener('click', (e) => {
    const el = e.target.closest('[data-idx]');
    if (el) openDeckCard(viewItems[Number(el.dataset.idx)]);
  });
  initListPreview(cardsEl);
  document.getElementById('deck-cover-btn').addEventListener('click', coverPicker);
  document.getElementById('deck-add-btn').addEventListener('click', addCardsDialog);

  showLoading(t('Lade Deck...', 'Loading deck...'));
  try {
    await refreshDeck();
  } catch (e) {
    showNotice(t('Deck konnte nicht geladen werden: ', 'Could not load the deck: ') + e.message, true);
  }
  hideLoading();
  if (deckParams.get('add') === '1') {
    history.replaceState(null, '', `decks.html?deck=${encodeURIComponent(deckName)}`);
    const open = () => { if (isUnlocked) addCardsDialog(); };
    if (isUnlocked) open(); else document.addEventListener('auth-change', open, { once: true });
  }
}

initDeckPage();
