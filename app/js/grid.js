let allCards = [];
let filteredCards = [];

const RARITY_LABEL = { common: 'Common', uncommon: 'Uncommon', rare: 'Rare', mythic: 'Mythic', special: 'Special' };
const COLOR_LABEL = { W: 'Weiß', U: 'Blau', B: 'Schwarz', R: 'Rot', G: 'Grün' };

function populateFilters(cards) {
  const setSel = document.getElementById('filter-set');
  const raritySel = document.getElementById('filter-rarity');
  const colorSel = document.getElementById('filter-color');

  const sets = uniqueSorted(cards.map((c) => c.setCode + ' — ' + c.setName));
  sets.forEach((s) => {
    const code = s.split(' — ')[0];
    const opt = document.createElement('option');
    opt.value = code;
    opt.textContent = s;
    setSel.appendChild(opt);
  });

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
    opt.textContent = 'Farblos';
    colorSel.appendChild(opt);
  }
}

function renderStats(cards) {
  const totalUnique = cards.length;
  const totalQty = cards.reduce((s, c) => s + c.quantity, 0);
  const totalValue = cards.reduce((s, c) => s + c.purchasePrice * c.quantity, 0);
  const marketValue = cards.reduce((s, c) => {
    const p = c.scryfall && c.scryfall.priceEur ? parseFloat(c.scryfall.priceEur) : 0;
    return s + p * c.quantity;
  }, 0);

  document.getElementById('stats-bar').innerHTML = `
    <div class="stat-tile"><div class="stat-value">${totalUnique}</div><div class="stat-label">Einzelkarten</div></div>
    <div class="stat-tile"><div class="stat-value">${totalQty}</div><div class="stat-label">Karten gesamt</div></div>
    <div class="stat-tile"><div class="stat-value">${formatCurrency(totalValue, 'EUR')}</div><div class="stat-label">Kaufwert</div></div>
    <div class="stat-tile"><div class="stat-value">${formatCurrency(marketValue, 'EUR')}</div><div class="stat-label">Marktwert (Scryfall)</div></div>
  `;
}

function applyFilters() {
  const q = document.getElementById('search').value.trim().toLowerCase();
  const set = document.getElementById('filter-set').value;
  const rarity = document.getElementById('filter-rarity').value;
  const color = document.getElementById('filter-color').value;
  const foil = document.getElementById('filter-foil').value;
  const sortBy = document.getElementById('sort-by').value;

  filteredCards = allCards.filter((c) => {
    if (q && !c.name.toLowerCase().includes(q)) return false;
    if (set && c.setCode !== set) return false;
    if (rarity && c.rarity !== rarity) return false;
    if (foil && c.foil !== foil) return false;
    if (color) {
      const colors = (c.scryfall && c.scryfall.colors) || [];
      if (color === 'C' && colors.length !== 0) return false;
      if (color !== 'C' && !colors.includes(color)) return false;
    }
    return true;
  });

  filteredCards.sort((a, b) => {
    switch (sortBy) {
      case 'set':
        return a.setCode.localeCompare(b.setCode) || a.name.localeCompare(b.name);
      case 'price-desc':
        return b.purchasePrice - a.purchasePrice;
      case 'price-asc':
        return a.purchasePrice - b.purchasePrice;
      case 'rarity':
        return a.rarity.localeCompare(b.rarity);
      case 'added-desc':
        return (b.added || '').localeCompare(a.added || '');
      default:
        return a.name.localeCompare(b.name);
    }
  });

  document.getElementById('result-count').textContent = `${filteredCards.length} von ${allCards.length} Karten`;
  renderGrid(filteredCards);
}

function renderGrid(cards) {
  const grid = document.getElementById('card-grid');
  grid.innerHTML = '';
  const fragment = document.createDocumentFragment();
  cards.forEach((card) => {
    const img = cardImage(card, 'small');
    const tile = document.createElement('div');
    tile.className = 'card-tile';
    tile.innerHTML = `
      <div class="thumb">
        ${img ? `<img loading="lazy" src="${img}" alt="${escapeHTML(card.name)}">` : '<div class="no-image">Kein Bild</div>'}
        ${card.foil !== 'normal' ? `<span class="foil-badge">${escapeHTML(card.foil)}</span>` : ''}
        ${card.quantity > 1 ? `<span class="qty-badge">×${card.quantity}</span>` : ''}
      </div>
      <div class="info">
        <div class="name">${escapeHTML(card.name)}</div>
        <div class="meta"><span>${escapeHTML(card.setCode)}</span><span>${formatCurrency(card.purchasePrice, card.currency)}</span></div>
      </div>
    `;
    tile.addEventListener('click', () => openCardModal(card));
    fragment.appendChild(tile);
  });
  grid.appendChild(fragment);
}

async function init() {
  initModal();
  showLoading('Lade CSV-Datei...');
  try {
    allCards = await loadCollection(updateLoadingProgress);
  } catch (e) {
    hideLoading();
    document.getElementById('card-grid').innerHTML = `<p style="color:#e05656">Fehler beim Laden der Sammlung: ${escapeHTML(e.message)}</p>`;
    return;
  }
  hideLoading();
  populateFilters(allCards);
  renderStats(allCards);
  applyFilters();

  document.getElementById('search').addEventListener('input', debounce(applyFilters, 150));
  ['filter-set', 'filter-rarity', 'filter-color', 'filter-foil', 'sort-by'].forEach((id) => {
    document.getElementById(id).addEventListener('change', applyFilters);
  });
}

init();
