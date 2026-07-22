const PALETTE = {
  W: '#f0e6c8', U: '#4fa8e0', B: '#8a8a94', R: '#e05656', G: '#4dbb6a', C: '#c9a24d',
  common: '#9a9cae', uncommon: '#c9c9d4', rare: '#c9a24d', mythic: '#e0743d', special: '#7b5cff',
};

Chart.defaults.color = '#9a9cae';
Chart.defaults.borderColor = '#2f3140';
Chart.defaults.font.family = "'Segoe UI', Roboto, Helvetica, Arial, sans-serif";

function renderStats(cards) {
  const totalUnique = cards.length;
  const totalQty = cards.reduce((s, c) => s + c.quantity, 0);
  const totalValue = cards.reduce((s, c) => s + c.purchasePrice * c.quantity, 0);
  const marketValue = cards.reduce((s, c) => s + (c.scryfall && c.scryfall.priceEur ? parseFloat(c.scryfall.priceEur) : 0) * c.quantity, 0);
  const avgValue = totalQty ? totalValue / totalQty : 0;
  const gain = marketValue - totalValue;
  const gainColor = gain >= 0 ? '#4dbb6a' : '#e05656';
  const gainStr = (gain >= 0 ? '+' : '') + formatCurrency(gain, 'EUR');

  document.getElementById('stats-bar').innerHTML = `
    <div class="stat-tile"><div class="stat-value">${totalUnique}</div><div class="stat-label">${t('Einzelkarten', 'Card entries')}</div></div>
    <div class="stat-tile"><div class="stat-value">${totalQty}</div><div class="stat-label">${t('Karten gesamt', 'Cards total')}</div></div>
    <div class="stat-tile"><div class="stat-value">${formatCurrency(totalValue, 'EUR')}</div><div class="stat-label">${t('Kaufwert', 'Purchase value')}</div></div>
    <div class="stat-tile"><div class="stat-value">${formatCurrency(marketValue, 'EUR')}</div><div class="stat-label">${t('Marktwert', 'Market value')}</div></div>
    <div class="stat-tile"><div class="stat-value" style="color:${gainColor}">${gainStr}</div><div class="stat-label">${t('Wertzuwachs (Markt − Kauf)', 'Value change (market − purchase)')}</div></div>
    <div class="stat-tile"><div class="stat-value">${formatCurrency(avgValue, 'EUR')}</div><div class="stat-label">${t('Ø Wert / Karte', 'Avg. value / card')}</div></div>
  `;
}

function countBy(cards, fn) {
  const map = {};
  cards.forEach((c) => {
    const key = fn(c);
    const qty = c.quantity;
    map[key] = (map[key] || 0) + qty;
  });
  return map;
}

function renderRarityChart(cards) {
  const map = countBy(cards, (c) => c.rarity || t('unbekannt', 'unknown'));
  const labels = Object.keys(map);
  new Chart(document.getElementById('chart-rarity'), {
    type: 'doughnut',
    data: { labels, datasets: [{ data: labels.map((l) => map[l]), backgroundColor: labels.map((l) => PALETTE[l] || '#7b5cff') }] },
    options: { plugins: { legend: { position: 'bottom' } } },
  });
}

function renderColorChart(cards) {
  const map = { W: 0, U: 0, B: 0, R: 0, G: 0, Mehrfarbig: 0, Farblos: 0 };
  cards.forEach((c) => {
    const colors = (c.scryfall && c.scryfall.colors) || [];
    if (colors.length === 0) map['Farblos'] += c.quantity;
    else if (colors.length > 1) map['Mehrfarbig'] += c.quantity;
    else map[colors[0]] += c.quantity;
  });
  const labels = Object.keys(map).filter((l) => map[l] > 0);
  new Chart(document.getElementById('chart-color'), {
    type: 'bar',
    data: {
      labels: labels.map((l) => COLOR_NAME(l)),
      datasets: [{ label: t('Karten', 'Cards'), data: labels.map((l) => map[l]), backgroundColor: labels.map((l) => PALETTE[l] || '#7b5cff') }],
    },
    options: { plugins: { legend: { display: false } }, scales: { y: { beginAtZero: true } } },
  });
}

function COLOR_NAME(code) {
  return {
    W: t('Weiß', 'White'), U: t('Blau', 'Blue'), B: t('Schwarz', 'Black'), R: t('Rot', 'Red'), G: t('Grün', 'Green'),
    Mehrfarbig: t('Mehrfarbig', 'Multicolor'), Farblos: t('Farblos', 'Colorless'),
  }[code] || code;
}

function renderSetsChart(cards) {
  const map = countBy(cards, (c) => c.setCode);
  const sorted = Object.entries(map).sort((a, b) => b[1] - a[1]).slice(0, 10);
  new Chart(document.getElementById('chart-sets'), {
    type: 'bar',
    data: { labels: sorted.map((e) => e[0]), datasets: [{ label: t('Karten', 'Cards'), data: sorted.map((e) => e[1]), backgroundColor: '#c9a24d' }] },
    options: { indexAxis: 'y', plugins: { legend: { display: false } }, scales: { x: { beginAtZero: true } } },
  });
}

function renderSetValueChart(cards) {
  const map = {};
  const names = {};
  cards.forEach((c) => {
    const price = c.scryfall && c.scryfall.priceEur ? parseFloat(c.scryfall.priceEur) : 0;
    map[c.setCode] = (map[c.setCode] || 0) + price * c.quantity;
    if (!names[c.setCode]) names[c.setCode] = c.setName || c.setCode;
  });
  const sorted = Object.entries(map).sort((a, b) => b[1] - a[1]).slice(0, 10);
  new Chart(document.getElementById('chart-set-value'), {
    type: 'bar',
    data: {
      labels: sorted.map((e) => names[e[0]] || e[0]),
      datasets: [{ label: t('Marktwert (EUR)', 'Market value (EUR)'), data: sorted.map((e) => Math.round(e[1] * 100) / 100), backgroundColor: '#4dbb6a' }],
    },
    options: {
      indexAxis: 'y',
      plugins: {
        legend: { display: false },
        tooltip: { callbacks: { label: (ctx) => formatCurrency(ctx.parsed.x, 'EUR') } },
      },
      scales: { x: { beginAtZero: true } },
    },
  });
}

function renderFoilChart(cards) {
  const map = countBy(cards, (c) => c.foil);
  const labels = Object.keys(map);
  new Chart(document.getElementById('chart-foil'), {
    type: 'pie',
    data: { labels, datasets: [{ data: labels.map((l) => map[l]), backgroundColor: ['#9a9cae', '#c9a24d', '#7b5cff'] }] },
    options: { plugins: { legend: { position: 'bottom' } } },
  });
}

function renderValueOverTimeChart(cards) {
  const withDates = cards.filter((c) => c.added && c.added.length >= 7).slice();
  withDates.sort((a, b) => a.added.localeCompare(b.added));
  const monthly = {};
  withDates.forEach((c) => {
    const month = c.added.slice(0, 7);
    monthly[month] = (monthly[month] || 0) + c.purchasePrice * c.quantity;
  });
  const months = Object.keys(monthly).sort();
  let cumulative = 0;
  const data = months.map((m) => (cumulative += monthly[m]));
  new Chart(document.getElementById('chart-value-time'), {
    type: 'line',
    data: { labels: months, datasets: [{ label: t('Kumulativer Kaufwert (EUR)', 'Cumulative purchase value (EUR)'), data, borderColor: '#7b5cff', backgroundColor: 'rgba(123,92,255,0.15)', fill: true, tension: 0.25 }] },
    options: { plugins: { legend: { display: false } }, scales: { y: { beginAtZero: true } } },
  });
}

function renderTopValueChart(cards) {
  const sorted = cards.slice().sort((a, b) => b.purchasePrice * b.quantity - a.purchasePrice * a.quantity).slice(0, 10);
  new Chart(document.getElementById('chart-top-value'), {
    type: 'bar',
    data: { labels: sorted.map((c) => c.name), datasets: [{ label: t('Kaufwert (EUR)', 'Purchase value (EUR)'), data: sorted.map((c) => c.purchasePrice * c.quantity), backgroundColor: '#e05656' }] },
    options: { indexAxis: 'y', plugins: { legend: { display: false } }, scales: { x: { beginAtZero: true } } },
  });
}

const CHART_COLORS = ['#7b5cff', '#4fa8e0', '#4dbb6a', '#e0743d', '#c9a24d', '#e05656', '#c9c9d4', '#8a8a94', '#f0e6c8', '#b57bff'];

function cardPrice(c) {
  return c.scryfall && c.scryfall.priceEur ? parseFloat(c.scryfall.priceEur) : 0;
}

function renderTopMarketChart(cards) {
  const byName = {};
  cards.forEach((c) => {
    const p = cardPrice(c);
    if (p > (byName[c.name] || 0)) byName[c.name] = p;
  });
  const sorted = Object.entries(byName).sort((a, b) => b[1] - a[1]).slice(0, 10);
  new Chart(document.getElementById('chart-top-market'), {
    type: 'bar',
    data: { labels: sorted.map((e) => e[0]), datasets: [{ label: t('Marktwert (EUR)', 'Market value (EUR)'), data: sorted.map((e) => e[1]), backgroundColor: '#4dbb6a' }] },
    options: {
      indexAxis: 'y',
      plugins: { legend: { display: false }, tooltip: { callbacks: { label: (ctx) => formatCurrency(ctx.parsed.x, 'EUR') } } },
      scales: { x: { beginAtZero: true } },
    },
  });
}

function renderTypesChart(cards) {
  const map = {};
  cards.forEach((c) => cardTypes(c.scryfall && c.scryfall.typeLine).forEach((tp) => (map[tp] = (map[tp] || 0) + c.quantity)));
  const labels = Object.keys(map).sort((a, b) => map[b] - map[a]);
  new Chart(document.getElementById('chart-types'), {
    type: 'bar',
    data: { labels, datasets: [{ label: t('Karten', 'Cards'), data: labels.map((l) => map[l]), backgroundColor: '#7b5cff' }] },
    options: { indexAxis: 'y', plugins: { legend: { display: false } }, scales: { x: { beginAtZero: true } } },
  });
}

function renderLanguageChart(cards) {
  const map = countBy(cards, (c) => (c.language || '?').toUpperCase());
  const labels = Object.keys(map).sort((a, b) => map[b] - map[a]);
  new Chart(document.getElementById('chart-language'), {
    type: 'doughnut',
    data: { labels, datasets: [{ data: labels.map((l) => map[l]), backgroundColor: labels.map((l, i) => CHART_COLORS[i % CHART_COLORS.length]) }] },
    options: { plugins: { legend: { position: 'bottom' } } },
  });
}

function renderConditionChart(cards) {
  const map = countBy(cards, (c) => conditionLabel(c.condition));
  const labels = Object.keys(map).sort((a, b) => map[b] - map[a]);
  new Chart(document.getElementById('chart-condition'), {
    type: 'bar',
    data: { labels, datasets: [{ label: t('Karten', 'Cards'), data: labels.map((l) => map[l]), backgroundColor: '#c9a24d' }] },
    options: { plugins: { legend: { display: false } }, scales: { y: { beginAtZero: true } } },
  });
}

function renderCmcChart(cards) {
  const buckets = {};
  cards.forEach((c) => {
    const mv = manaValue(c.scryfall && c.scryfall.manaCost);
    if (mv === null) return;
    const key = mv >= 7 ? '7+' : String(mv);
    buckets[key] = (buckets[key] || 0) + c.quantity;
  });
  const labels = ['0', '1', '2', '3', '4', '5', '6', '7+'].filter((k) => buckets[k]);
  new Chart(document.getElementById('chart-cmc'), {
    type: 'bar',
    data: { labels, datasets: [{ label: t('Karten', 'Cards'), data: labels.map((l) => buckets[l]), backgroundColor: '#4fa8e0' }] },
    options: { plugins: { legend: { display: false } }, scales: { y: { beginAtZero: true } } },
  });
}

async function init() {
  showLoading(t('Lade Sammlung...', 'Loading collection...'));
  let cards;
  try {
    cards = await loadCollection(updateLoadingProgress);
  } catch (e) {
    hideLoading();
    document.querySelector('main').innerHTML = `<p style="color:#e05656">${t('Fehler', 'Error')}: ${escapeHTML(e.message)}</p>`;
    return;
  }
  hideLoading();
  renderStats(cards);
  renderRarityChart(cards);
  renderColorChart(cards);
  renderSetsChart(cards);
  renderSetValueChart(cards);
  renderFoilChart(cards);
  renderValueOverTimeChart(cards);
  renderTopValueChart(cards);
  renderTopMarketChart(cards);
  renderTypesChart(cards);
  renderLanguageChart(cards);
  renderConditionChart(cards);
  renderCmcChart(cards);
}

init();
