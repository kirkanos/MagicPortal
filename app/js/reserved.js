let rlCards = [];
let rlSummary = null;

async function loadReserved() {
  const res = await fetch(API + '/reserved', { cache: 'no-store' });
  if (!res.ok) throw new Error('HTTP ' + res.status);
  const data = await res.json();
  rlCards = data.cards || [];
  rlSummary = data.summary || {};
}

function renderSummary() {
  const s = rlSummary;
  const pct = s.total ? (s.owned / s.total) * 100 : 0;
  const unpriced =
    s.missingUnpriced > 0
      ? ` <span class="dim">(+${s.missingUnpriced} ${t('ohne Preis', 'unpriced')})</span>`
      : '';
  document.getElementById('stats-bar').innerHTML = `
    <div class="stat-tile"><div class="stat-value">${s.total}</div><div class="stat-label">${t('Reserved List gesamt', 'Reserved List total')}</div></div>
    <div class="stat-tile"><div class="stat-value">${s.owned}</div><div class="stat-label">${t('Gesammelt', 'Collected')}</div></div>
    <div class="stat-tile"><div class="stat-value">${s.missing}</div><div class="stat-label">${t('Fehlt', 'Missing')}</div></div>
    <div class="stat-tile"><div class="stat-value">${pct.toFixed(1)} %</div><div class="stat-label">${t('Vollständigkeit', 'Completion')}</div></div>
    <div class="stat-tile" title="${priceHint()}"><div class="stat-value">${formatCurrency(s.ownedValue, 'EUR')}</div><div class="stat-label">${t('Wert gesammelt', 'Collected value')}</div></div>
    <div class="stat-tile" title="${priceHint()}"><div class="stat-value">${formatCurrency(s.costToComplete, 'EUR')}</div><div class="stat-label">${t('Investition für den Rest', 'Cost to complete')}${unpriced}</div></div>
  `;
}

function renderGrid() {
  const q = document
    .getElementById('search')
    .value.trim()
    .toLowerCase();
  const ownedFilter = document.getElementById('filter-owned').value;
  const sortBy = document.getElementById('sort-by').value;

  let list = rlCards.slice();
  if (q) list = list.filter((c) => c.name.toLowerCase().includes(q));
  if (ownedFilter === 'owned') list = list.filter((c) => c.owned);
  else if (ownedFilter === 'missing') list = list.filter((c) => !c.owned);

  const price = (c) => (c.cheapestPrice != null ? c.cheapestPrice : 0);
  list.sort((a, b) => {
    switch (sortBy) {
      case 'price-asc':
        return price(a) - price(b) || a.name.localeCompare(b.name);
      case 'name':
        return a.name.localeCompare(b.name);
      case 'price-desc':
      default:
        return price(b) - price(a) || a.name.localeCompare(b.name);
    }
  });

  document.getElementById('result-count').textContent = t(
    `${list.length} von ${rlCards.length} Karten`,
    `${list.length} of ${rlCards.length} cards`
  );

  const grid = document.getElementById('rl-grid');
  const frag = document.createDocumentFragment();
  list.forEach((c) => {
    const tile = document.createElement('div');
    tile.className = 'eo-card ' + (c.owned ? 'owned' : 'missing');
    const priceLine = c.owned
      ? `<span class="rl-owned">${t('Wert', 'Value')}: ${formatCurrency(c.ownedValue, 'EUR')}</span>`
      : c.cheapestPrice != null
      ? `<span class="rl-price">${t('ab', 'from')} ${formatCurrency(c.cheapestPrice, 'EUR')}</span>`
      : `<span class="dim">${t('kein Preis', 'no price')}</span>`;
    tile.innerHTML = `
      <div class="eo-thumb">
        ${c.image ? `<img loading="lazy" src="${c.image}" alt="${escapeHTML(c.name)}">` : `<div class="no-image">${t('Kein Bild', 'No image')}</div>`}
        ${c.owned && c.quantity > 1 ? `<span class="eo-qty">×${c.quantity}</span>` : ''}
        ${c.owned ? '<span class="eo-check">✓</span>' : `<span class="eo-missing-tag">${t('fehlt', 'missing')}</span>`}
      </div>
      <div class="eo-cardname">${escapeHTML(c.name)}</div>
      <div class="rl-cardprice">${priceLine}</div>
      ${
        !c.owned && c.cardmarketUri
          ? `<a class="rl-cm-link" href="${escapeHTML(c.cardmarketUri)}" target="_blank" rel="noopener noreferrer">${t('Cardmarket', 'Cardmarket')} ↗</a>`
          : ''
      }
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

async function init() {
  showLoading(t('Lade Reserved List...', 'Loading Reserved List...'));
  try {
    await loadReserved();
  } catch (e) {
    hideLoading();
    document.querySelector('main').innerHTML = `<p style="color:#e05656">${t('Fehler beim Laden', 'Error loading')}: ${escapeHTML(e.message)}</p>`;
    return;
  }
  hideLoading();
  renderSummary();
  renderGrid();
  document.getElementById('search').addEventListener('input', debounce(renderGrid, 150));
  document.getElementById('filter-owned').addEventListener('change', renderGrid);
  document.getElementById('sort-by').addEventListener('change', renderGrid);
}

init();
