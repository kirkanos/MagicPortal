const PALETTE = {
  W: '#f0e6c8', U: '#4fa8e0', B: '#8a8a94', R: '#e05656', G: '#4dbb6a', C: '#c9a24d',
  common: '#9a9cae', uncommon: '#c9c9d4', rare: '#c9a24d', mythic: '#e0743d', special: '#7b5cff',
};

Chart.defaults.color = '#9a9cae';
Chart.defaults.borderColor = '#2f3140';
Chart.defaults.font.family = "'Segoe UI', Roboto, Helvetica, Arial, sans-serif";

/* Richer default tooltips for the categorical charts: show the exact value
   (cards or EUR, auto-detected from the dataset label) plus its share of the
   chart total. Time-series charts set their own tooltip and are unaffected. */
Chart.defaults.plugins.tooltip.padding = 10;
Chart.defaults.plugins.tooltip.boxPadding = 4;
Chart.defaults.plugins.tooltip.callbacks.label = function (ctx) {
  const v =
    typeof ctx.raw === 'number' ? ctx.raw : (ctx.parsed && (ctx.parsed.y ?? ctx.parsed.x)) ?? ctx.parsed ?? 0;
  const data = (ctx.dataset && ctx.dataset.data) || [];
  const total = data.reduce((s, x) => s + (typeof x === 'number' ? x : 0), 0);
  const pct = total ? (v / total) * 100 : 0;
  const isEur = /eur|€/i.test((ctx.dataset && ctx.dataset.label) || '');
  const locale = typeof LANG !== 'undefined' && LANG === 'en' ? 'en-GB' : 'de-DE';
  const valStr = isEur ? formatCurrency(v, 'EUR') : v.toLocaleString(locale) + ' ' + t('Karten', 'cards');
  return total ? `${valStr} · ${pct.toFixed(1)} %` : valStr;
};

function renderStats(d) {
  // Every figure arrives ready-made from /api/dashboard; the backend groups
  // "distinct cards" with the same key the card grid uses.
  const st = d.stats;
  const totalUnique = st.entries;
  const distinctCards = st.distinct;
  const totalQty = st.totalQty;
  const totalValue = st.purchaseValue;
  const marketValue = st.marketValue;
  const avgValue = totalQty ? totalValue / totalQty : 0;
  const gain = marketValue - totalValue;
  const gainColor = gain >= 0 ? '#4dbb6a' : '#e05656';
  const gainStr = (gain >= 0 ? '+' : '') + formatCurrency(gain, 'EUR');

  const foilPct = totalQty ? (st.foilQty / totalQty) * 100 : 0;
  const setCount = st.setCount;
  const binderCount = st.binderCount;
  const topCard = { p: st.topCardPrice, name: st.topCardName || '–' };

  document.getElementById('stats-bar').innerHTML = `
    <div class="stat-tile" title="${countHint('entries')}"><div class="stat-value">${totalUnique}</div><div class="stat-label">${t('Einzelkarten', 'Card entries')}</div></div>
    <div class="stat-tile" title="${countHint('distinct')}"><div class="stat-value">${distinctCards}</div><div class="stat-label">${t('Verschiedene Karten', 'Distinct cards')}</div></div>
    <div class="stat-tile" title="${countHint('total')}"><div class="stat-value">${totalQty}</div><div class="stat-label">${t('Karten gesamt', 'Cards total')}</div></div>
    <div class="stat-tile"><div class="stat-value">${setCount}</div><div class="stat-label">${t('Editionen', 'Editions')}</div></div>
    <div class="stat-tile"><div class="stat-value">${binderCount}</div><div class="stat-label">${t('Ordner', 'Folders')}</div></div>
    <div class="stat-tile"><div class="stat-value">${formatCurrency(totalValue, 'EUR')}</div><div class="stat-label">${t('Kaufwert', 'Purchase value')}</div></div>
    <div class="stat-tile" title="${priceHint()}"><div class="stat-value">${formatCurrency(marketValue, 'EUR')}</div><div class="stat-label">${t('Marktwert', 'Market value')}</div></div>
    <div class="stat-tile" title="${priceHint()}"><div class="stat-value" style="color:${gainColor}">${gainStr}</div><div class="stat-label">${t('Wertzuwachs (Markt − Kauf)', 'Value change (market − purchase)')}</div></div>
    <div class="stat-tile"><div class="stat-value">${formatCurrency(avgValue, 'EUR')}</div><div class="stat-label">${t('Ø Wert / Karte', 'Avg. value / card')}</div></div>
    <div class="stat-tile"><div class="stat-value">${foilPct.toFixed(1)} %</div><div class="stat-label">${t('Foil-Anteil', 'Foil share')}</div></div>
    <div class="stat-tile" title="${priceHint()}"><div class="stat-value">${formatCurrency(topCard.p, 'EUR')}</div><div class="stat-label">${t('Teuerste Karte', 'Most valuable')}: ${escapeHTML(topCard.name)}</div></div>
  `;
}

/* The series from /api/dashboard arrive as [{key, qty}] or [{key, value}].
   The backend deliberately returns keys only - labelling and translation stay
   here. */
function keysOf(rows) {
  return rows.map((r) => r.key);
}

function qtyOf(rows) {
  return rows.map((r) => r.qty);
}

/* Merges series that share a label (e.g. light_played and lightly_played ->
   "Lightly Played") and sorts them descending. */
function groupByLabel(rows, labelFn) {
  const map = new Map();
  rows.forEach((r) => {
    const label = labelFn(r.key);
    map.set(label, (map.get(label) || 0) + r.qty);
  });
  return [...map.entries()].sort((a, b) => b[1] - a[1]);
}

function renderRarityChart(d) {
  const map = {};
  d.rarity.forEach((r) => (map[r.key || t('unbekannt', 'unknown')] = r.qty));
  const labels = Object.keys(map);
  new Chart(document.getElementById('chart-rarity'), {
    type: 'doughnut',
    data: { labels, datasets: [{ data: labels.map((l) => map[l]), backgroundColor: labels.map((l) => PALETTE[l] || '#7b5cff') }] },
    options: { plugins: { legend: { position: 'bottom' } } },
  });
}

function renderColorChart(d) {
  const map = {};
  d.color.forEach((r) => (map[r.key] = r.qty));
  const labels = COLOR_ORDER.filter((l) => map[l] > 0);
  new Chart(document.getElementById('chart-color'), {
    type: 'bar',
    data: {
      labels: labels.map((l) => COLOR_NAME(l)),
      datasets: [{ label: t('Karten', 'Cards'), data: labels.map((l) => map[l]), backgroundColor: labels.map((l) => PALETTE[l] || '#7b5cff') }],
    },
    options: { plugins: { legend: { display: false } }, scales: { y: { beginAtZero: true } } },
  });
}

/* A fixed order for the color groups, independent of the order they first
   appear in the data. Keys as in the backend (colorGroup). */
const COLOR_ORDER = ['W', 'U', 'B', 'R', 'G', 'multi', 'colorless'];

function COLOR_NAME(code) {
  return {
    W: t('Weiß', 'White'), U: t('Blau', 'Blue'), B: t('Schwarz', 'Black'), R: t('Rot', 'Red'), G: t('Grün', 'Green'),
    multi: t('Mehrfarbig', 'Multicolor'), colorless: t('Farblos', 'Colorless'),
  }[code] || code;
}

function renderSetsChart(d) {
  const sorted = d.sets.map((r) => [r.key, r.qty]);
  new Chart(document.getElementById('chart-sets'), {
    type: 'bar',
    data: { labels: sorted.map((e) => e[0]), datasets: [{ label: t('Karten', 'Cards'), data: sorted.map((e) => e[1]), backgroundColor: '#c9a24d' }] },
    options: { indexAxis: 'y', plugins: { legend: { display: false } }, scales: { x: { beginAtZero: true } } },
  });
}

function renderSetValueChart(d) {
  const names = {};
  d.setValue.forEach((r) => (names[r.code] = r.name || r.code));
  const sorted = d.setValue.map((r) => [r.code, r.value]);
  new Chart(document.getElementById('chart-set-value'), {
    type: 'bar',
    data: {
      labels: sorted.map((e) => names[e[0]] || e[0]),
      datasets: [{ label: t('Marktwert (EUR)', 'Market value (EUR)'), data: sorted.map((e) => e[1]), backgroundColor: '#4dbb6a' }],
    },
    options: {
      indexAxis: 'y',
      plugins: {
        legend: { display: false },
      },
      scales: { x: { beginAtZero: true } },
    },
  });
}

function renderFoilChart(d) {
  const map = {};
  d.foil.forEach((r) => (map[r.key] = r.qty));
  const labels = Object.keys(map);
  new Chart(document.getElementById('chart-foil'), {
    type: 'pie',
    data: { labels, datasets: [{ data: labels.map((l) => map[l]), backgroundColor: ['#9a9cae', '#c9a24d', '#7b5cff'] }] },
    options: { plugins: { legend: { position: 'bottom' } } },
  });
}

function renderValueOverTimeChart(d) {
  const months = d.purchaseByMonth.map((r) => r.month);
  let cumulative = 0;
  const data = d.purchaseByMonth.map((r) => (cumulative += r.value));
  new Chart(document.getElementById('chart-value-time'), {
    type: 'line',
    data: { labels: months, datasets: [{ label: t('Kumulativer Kaufwert (EUR)', 'Cumulative purchase value (EUR)'), data, borderColor: '#7b5cff', backgroundColor: 'rgba(123,92,255,0.15)', fill: true, tension: 0.25 }] },
    options: {
      plugins: {
        legend: { display: false },
        tooltip: {
          callbacks: {
            title: (items) => (items[0] ? t('Monat ', 'Month ') + items[0].label : ''),
            label: (ctx) => t('Kumulierter Kaufwert: ', 'Cumulative purchase value: ') + formatCurrency(ctx.parsed.y, 'EUR'),
            afterLabel: (ctx) => {
              const prev = ctx.dataIndex > 0 ? ctx.dataset.data[ctx.dataIndex - 1] : 0;
              const delta = ctx.parsed.y - prev;
              return t('Zugang in diesem Monat: ', 'Added this month: ') + formatCurrency(delta, 'EUR');
            },
          },
        },
      },
      scales: { y: { beginAtZero: true } },
    },
  });
}

function renderTopValueChart(d) {
  const sorted = d.topPurchase;
  new Chart(document.getElementById('chart-top-value'), {
    type: 'bar',
    data: { labels: sorted.map((c) => c.name), datasets: [{ label: t('Kaufwert (EUR)', 'Purchase value (EUR)'), data: sorted.map((c) => c.value), backgroundColor: '#e05656' }] },
    options: { indexAxis: 'y', plugins: { legend: { display: false } }, scales: { x: { beginAtZero: true } } },
  });
}

const CHART_COLORS = ['#7b5cff', '#4fa8e0', '#4dbb6a', '#e0743d', '#c9a24d', '#e05656', '#c9c9d4', '#8a8a94', '#f0e6c8', '#b57bff'];

function renderTopMarketChart(d) {
  const sorted = d.topMarket.map((r) => [r.name, r.value]);
  new Chart(document.getElementById('chart-top-market'), {
    type: 'bar',
    data: { labels: sorted.map((e) => e[0]), datasets: [{ label: t('Marktwert (EUR)', 'Market value (EUR)'), data: sorted.map((e) => e[1]), backgroundColor: '#4dbb6a' }] },
    options: {
      indexAxis: 'y',
      plugins: { legend: { display: false } },
      scales: { x: { beginAtZero: true } },
    },
  });
}

function renderTypesChart(d) {
  const map = {};
  d.types.forEach((r) => (map[r.key] = r.qty));
  const labels = keysOf(d.types);
  new Chart(document.getElementById('chart-types'), {
    type: 'bar',
    data: { labels, datasets: [{ label: t('Karten', 'Cards'), data: labels.map((l) => map[l]), backgroundColor: '#7b5cff' }] },
    options: { indexAxis: 'y', plugins: { legend: { display: false } }, scales: { x: { beginAtZero: true } } },
  });
}

function renderLanguageChart(d) {
  const map = {};
  d.language.forEach((r) => (map[r.key] = r.qty));
  const labels = keysOf(d.language);
  new Chart(document.getElementById('chart-language'), {
    type: 'doughnut',
    data: { labels, datasets: [{ data: labels.map((l) => map[l]), backgroundColor: labels.map((l, i) => CHART_COLORS[i % CHART_COLORS.length]) }] },
    options: { plugins: { legend: { position: 'bottom' } } },
  });
}

function renderConditionChart(d) {
  // Merge by label so that light_played and lightly_played end up in one bar,
  // as before.
  const rows = groupByLabel(d.condition, conditionLabel);
  new Chart(document.getElementById('chart-condition'), {
    type: 'bar',
    data: { labels: rows.map((r) => r[0]), datasets: [{ label: t('Karten', 'Cards'), data: rows.map((r) => r[1]), backgroundColor: '#c9a24d' }] },
    options: { plugins: { legend: { display: false } }, scales: { y: { beginAtZero: true } } },
  });
}

function renderCmcChart(d) {
  const buckets = {};
  d.cmc.forEach((r) => (buckets[r.key] = r.qty));
  const labels = ['0', '1', '2', '3', '4', '5', '6', '7+'].filter((k) => buckets[k]);
  new Chart(document.getElementById('chart-cmc'), {
    type: 'bar',
    data: { labels, datasets: [{ label: t('Karten', 'Cards'), data: labels.map((l) => buckets[l]), backgroundColor: '#4fa8e0' }] },
    options: { plugins: { legend: { display: false } }, scales: { y: { beginAtZero: true } } },
  });
}

function el(id) {
  return document.getElementById(id);
}

function colorFor(key) {
  if (key === 'colorless') return PALETTE.C;
  if (key === 'multi') return '#b57bff';
  return PALETTE[key] || '#7b5cff';
}

const VALUE_BUCKETS = [
  { label: '< 0,10 €', max: 0.1 },
  { label: '0,10–0,50 €', max: 0.5 },
  { label: '0,50–1 €', max: 1 },
  { label: '1–5 €', max: 5 },
  { label: '5–20 €', max: 20 },
  { label: '20–100 €', max: 100 },
  { label: '> 100 €', max: Infinity },
];

/* Cards per single-card market-value bracket. */
function renderValueDistChart(d) {
  // The bracket order is identical between the backend and VALUE_BUCKETS.
  const counts = d.valueDist;
  new Chart(el('chart-value-dist'), {
    type: 'bar',
    data: { labels: VALUE_BUCKETS.map((b) => b.label), datasets: [{ label: t('Karten', 'Cards'), data: counts, backgroundColor: '#7b5cff' }] },
    options: { plugins: { legend: { display: false } }, scales: { y: { beginAtZero: true } } },
  });
}

/* Market value grouped by color identity. */
function renderValueByColorChart(d) {
  const map = {};
  d.valueByColor.forEach((r) => (map[r.key] = r.value));
  const labels = COLOR_ORDER.filter((k) => map[k]);
  new Chart(el('chart-value-color'), {
    type: 'bar',
    data: {
      labels: labels.map(COLOR_NAME),
      datasets: [{ label: t('Marktwert (EUR)', 'Market value (EUR)'), data: labels.map((k) => map[k]), backgroundColor: labels.map(colorFor) }],
    },
    options: { plugins: { legend: { display: false } }, scales: { y: { beginAtZero: true } } },
  });
}

/* Market value grouped by rarity. */
function renderValueByRarityChart(d) {
  const order = ['common', 'uncommon', 'rare', 'mythic', 'special'];
  const map = {};
  d.valueByRarity.forEach((r) => (map[r.key || t('unbekannt', 'unknown')] = r.value));
  const labels = order.filter((k) => map[k]).concat(Object.keys(map).filter((k) => !order.includes(k)));
  new Chart(el('chart-value-rarity'), {
    type: 'bar',
    data: { labels, datasets: [{ label: t('Marktwert (EUR)', 'Market value (EUR)'), data: labels.map((k) => map[k]), backgroundColor: labels.map((k) => PALETTE[k] || '#7b5cff') }] },
    options: { plugins: { legend: { display: false } }, scales: { y: { beginAtZero: true } } },
  });
}

/* Cards added per month (non-cumulative). */
function renderAddedPerMonthChart(d) {
  const months = d.addedPerMonth.map((r) => r.month);
  new Chart(el('chart-added-month'), {
    type: 'bar',
    data: { labels: months, datasets: [{ label: t('Zugänge', 'Added'), data: d.addedPerMonth.map((r) => r.qty), backgroundColor: '#4dbb6a' }] },
    options: { plugins: { legend: { display: false } }, scales: { y: { beginAtZero: true } } },
  });
}

function statTable(headers, rows) {
  return (
    '<div class="stat-table"><table class="lang-table"><thead><tr>' +
    headers.map((h) => `<th${h.num ? ' class="num"' : ''}>${escapeHTML(h.label)}</th>`).join('') +
    '</tr></thead><tbody>' +
    rows.map((r) => '<tr>' + r.map((cell) => `<td${cell.num ? ' class="num"' : ''}>${cell.html}</td>`).join('') + '</tr>').join('') +
    '</tbody></table></div>'
  );
}

/* Detailed per-rarity breakdown: entries, copies, market value, avg, share. */
function renderRarityTable(d) {
  const agg = {};
  let totalMv = 0;
  d.rarityTable.forEach((r) => {
    agg[r.key || '—'] = { entries: r.entries, qty: r.qty, mv: r.market };
    totalMv += r.market;
  });
  const order = ['common', 'uncommon', 'rare', 'mythic', 'special'];
  const keys = order.filter((k) => agg[k]).concat(Object.keys(agg).filter((k) => !order.includes(k)));
  const rows = keys.map((k) => {
    const o = agg[k];
    const avg = o.qty ? o.mv / o.qty : 0;
    const share = totalMv ? (o.mv / totalMv) * 100 : 0;
    const label = k.charAt(0).toUpperCase() + k.slice(1);
    return [
      { html: `<span class="rarity-tag rarity-${escapeHTML(k)}">${escapeHTML(label)}</span>` },
      { html: o.entries, num: true },
      { html: o.qty, num: true },
      { html: formatCurrency(o.mv, 'EUR'), num: true },
      { html: formatCurrency(avg, 'EUR'), num: true },
      { html: share.toFixed(1) + ' %', num: true },
    ];
  });
  el('table-rarity').innerHTML = statTable(
    [
      { label: t('Rarität', 'Rarity') },
      { label: t('Einträge', 'Entries'), num: true },
      { label: t('Exemplare', 'Copies'), num: true },
      { label: t('Marktwert', 'Market value'), num: true },
      { label: t('Ø/Karte', 'Avg./card'), num: true },
      { label: t('Wertanteil', 'Value share'), num: true },
    ],
    rows
  );
}

/* Detailed per-edition breakdown (top 15 by market value). */
function renderSetTable(d) {
  const arr = d.setTable.map((r) => ({ name: r.name || r.code, code: r.code, entries: r.entries, qty: r.qty, mv: r.market }));
  const rows = arr.map((o) => [
    { html: `${escapeHTML(o.name)} <span class="edition-code">${escapeHTML(o.code)}</span>` },
    { html: o.entries, num: true },
    { html: o.qty, num: true },
    { html: formatCurrency(o.mv, 'EUR'), num: true },
    { html: formatCurrency(o.qty ? o.mv / o.qty : 0, 'EUR'), num: true },
  ]);
  el('table-sets').innerHTML = statTable(
    [
      { label: t('Edition', 'Edition') },
      { label: t('Einträge', 'Entries'), num: true },
      { label: t('Exemplare', 'Copies'), num: true },
      { label: t('Marktwert', 'Market value'), num: true },
      { label: t('Ø/Karte', 'Avg./card'), num: true },
    ],
    rows
  );
}

/* ---- Value over time (market vs. purchase), from persisted snapshots ---- */

let valueHistory = null; // cached full series
let historyChart = null;

async function loadValueHistory() {
  try {
    const res = await fetch(API + '/value-history', { cache: 'no-store' });
    valueHistory = res.ok ? await res.json() : [];
  } catch (e) {
    valueHistory = [];
  }
}

function renderValueHistory(days) {
  const note = el('history-note');
  if (!valueHistory || valueHistory.length === 0) {
    note.hidden = false;
    note.textContent = t(
      'Noch keine Historie – die Wertentwicklung wird ab jetzt täglich aufgezeichnet.',
      'No history yet – value is recorded daily from now on.'
    );
    return;
  }
  let series = valueHistory;
  if (days > 0) {
    const cutoff = new Date(Date.now() - days * 86400000).toISOString().slice(0, 10);
    series = valueHistory.filter((p) => p.date >= cutoff);
  }
  if (series.length < 2) {
    note.hidden = false;
    note.textContent = t(
      'Erst ein Messpunkt vorhanden – ab morgen entsteht eine Kurve.',
      'Only one data point so far – a curve appears from tomorrow.'
    );
  } else {
    note.hidden = true;
  }

  const labels = series.map((p) => p.date);
  if (historyChart) historyChart.destroy();
  historyChart = new Chart(el('chart-value-history'), {
    type: 'line',
    data: {
      labels,
      datasets: [
        {
          label: t('Marktwert (EUR)', 'Market value (EUR)'),
          data: series.map((p) => Math.round(p.marketEur * 100) / 100),
          borderColor: '#4dbb6a', backgroundColor: 'rgba(77,187,106,0.15)', fill: true, tension: 0.25, pointRadius: series.length > 60 ? 0 : 2,
        },
        {
          label: t('Kaufwert (EUR)', 'Purchase value (EUR)'),
          data: series.map((p) => Math.round(p.purchaseEur * 100) / 100),
          borderColor: '#7b5cff', backgroundColor: 'rgba(123,92,255,0.08)', fill: true, tension: 0.25, pointRadius: series.length > 60 ? 0 : 2,
        },
      ],
    },
    options: {
      interaction: { mode: 'index', intersect: false },
      plugins: {
        legend: { position: 'bottom' },
        tooltip: {
          callbacks: {
            label: (ctx) => ctx.dataset.label + ': ' + formatCurrency(ctx.parsed.y, 'EUR'),
            afterBody: (items) => {
              // Show market − purchase (unrealised gain) for the hovered date.
              let market = null;
              let purchase = null;
              items.forEach((it) => {
                if (/kauf|purchase/i.test(it.dataset.label)) purchase = it.parsed.y;
                else market = it.parsed.y;
              });
              if (market == null || purchase == null) return '';
              const gain = market - purchase;
              return (gain >= 0 ? '▲ ' : '▼ ') + t('Wertzuwachs: ', 'Gain: ') + (gain >= 0 ? '+' : '') + formatCurrency(gain, 'EUR');
            },
          },
        },
      },
      scales: { y: { beginAtZero: false } },
    },
  });
}

function initValueHistory() {
  const box = el('history-range');
  renderValueHistory(0);
  box.querySelectorAll('button').forEach((btn) => {
    btn.addEventListener('click', () => {
      box.querySelectorAll('button').forEach((b) => b.classList.remove('active'));
      btn.classList.add('active');
      renderValueHistory(parseInt(btn.dataset.days, 10));
    });
  });
}

/* ---- Top movers (biggest value impact over a period) ---- */

function moverRows(list) {
  if (!list.length) return `<p class="chart-note">${t('Keine Bewegungen im Zeitraum.', 'No movement in this period.')}</p>`;
  const rows = list.map((m) => {
    const sign = m.delta >= 0 ? '+' : '';
    const cls = m.delta >= 0 ? 'up' : 'down';
    const impact = (m.valueImpact >= 0 ? '+' : '') + formatCurrency(m.valueImpact, 'EUR');
    return [
      { html: `${escapeHTML(m.name)}${m.quantity > 1 ? ` <span class="edition-code">×${m.quantity}</span>` : ''}` },
      { html: `${formatCurrency(m.priceThen, 'EUR')} → ${formatCurrency(m.priceNow, 'EUR')}`, num: true },
      { html: `<span class="mv-${cls}">${sign}${m.pct.toFixed(0)} %</span>`, num: true },
      { html: `<span class="mv-${cls}">${impact}</span>`, num: true },
    ];
  });
  return statTable(
    [
      { label: t('Karte', 'Card') },
      { label: t('Preis', 'Price'), num: true },
      { label: t('Δ %', 'Δ %'), num: true },
      { label: t('Wert-Einfluss', 'Value impact'), num: true },
    ],
    rows
  );
}

async function renderMovers(days) {
  const note = el('movers-note');
  let data;
  try {
    const res = await fetch(API + '/value-movers?days=' + days, { cache: 'no-store' });
    data = res.ok ? await res.json() : null;
  } catch (e) {
    data = null;
  }
  if (!data || ((data.gainers || []).length === 0 && (data.losers || []).length === 0)) {
    el('movers-gainers').innerHTML = '';
    el('movers-losers').innerHTML = '';
    note.hidden = false;
    note.textContent = t(
      'Noch nicht genug Historie für Bewegungen – wird mit der Zeit gefüllt.',
      'Not enough history for movers yet – fills up over time.'
    );
    return;
  }
  note.hidden = data.baseDate ? true : false;
  if (data.baseDate) {
    note.hidden = false;
    note.textContent = t(
      `Vergleich seit ${data.baseDate} bis ${data.latestDate}.`,
      `Compared from ${data.baseDate} to ${data.latestDate}.`
    );
  }
  el('movers-gainers').innerHTML = moverRows(data.gainers || []);
  el('movers-losers').innerHTML = moverRows(data.losers || []);
}

function initMovers() {
  const sel = el('movers-range');
  renderMovers(parseInt(sel.value, 10));
  sel.addEventListener('change', () => renderMovers(parseInt(sel.value, 10)));
}

async function init() {
  showLoading(t('Lade Statistik...', 'Loading statistics...'));
  let d;
  try {
    d = await loadDashboard();
  } catch (e) {
    hideLoading();
    document.querySelector('main').innerHTML = `<p style="color:#e05656">${t('Fehler', 'Error')}: ${escapeHTML(e.message)}</p>`;
    return;
  }
  hideLoading();
  renderStats(d);
  renderRarityChart(d);
  renderColorChart(d);
  renderSetsChart(d);
  renderSetValueChart(d);
  renderFoilChart(d);
  renderValueOverTimeChart(d);
  renderTopValueChart(d);
  renderTopMarketChart(d);
  renderTypesChart(d);
  renderLanguageChart(d);
  renderConditionChart(d);
  renderCmcChart(d);
  renderValueDistChart(d);
  renderValueByColorChart(d);
  renderValueByRarityChart(d);
  renderAddedPerMonthChart(d);
  renderRarityTable(d);
  renderSetTable(d);

  // Value history + movers come from persisted snapshots (independent of `cards`).
  await loadValueHistory();
  initValueHistory();
  initMovers();
}

init();
