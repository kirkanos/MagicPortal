let collectionCards = [];
let ownedByName = new Map();

function buildOwnedIndex(cards) {
  const map = new Map();
  cards.forEach((c) => {
    const key = c.name.toLowerCase();
    map.set(key, (map.get(key) || 0) + c.quantity);
  });
  return map;
}

function parseDecklist(text) {
  const lines = text.split(/\r?\n/);
  const entries = [];
  const skipHeaders = /^(deck|deckliste|sideboard|maybeboard|commander|companion)\s*:?\s*$/i;
  lines.forEach((raw) => {
    const line = raw.trim();
    if (!line || skipHeaders.test(line)) return;
    const match = line.match(/^(\d+)\s*x?\s+(.+)$/i);
    if (!match) return;
    let qty = parseInt(match[1], 10) || 1;
    let name = match[2].trim();
    // strip trailing set/collector info e.g. "(2X2) 141" or "[2X2]"
    name = name.replace(/\s*[\(\[][A-Za-z0-9]{2,6}[\)\]]\s*[\w-]*\s*$/, '').trim();
    // strip trailing "*F*" foil markers etc.
    name = name.replace(/\s*\*[^*]*\*\s*$/, '').trim();
    entries.push({ name, qty });
  });
  return entries;
}

function checkDeck() {
  const text = document.getElementById('decklist-input').value;
  const entries = parseDecklist(text);
  const tbody = document.querySelector('#deck-table tbody');
  tbody.innerHTML = '';

  let ownedCount = 0, partialCount = 0, missingCount = 0;

  entries.forEach((entry) => {
    const owned = ownedByName.get(entry.name.toLowerCase()) || 0;
    let status, statusClass;
    if (owned >= entry.qty) { status = 'Vollständig'; statusClass = 'status-owned'; ownedCount++; }
    else if (owned > 0) { status = 'Teilweise'; statusClass = 'status-partial'; partialCount++; }
    else { status = 'Fehlt'; statusClass = 'status-missing'; missingCount++; }

    const tr = document.createElement('tr');
    tr.innerHTML = `
      <td>${escapeHTML(entry.name)}</td>
      <td>${entry.qty}</td>
      <td>${owned}</td>
      <td class="${statusClass}">${status}</td>
    `;
    tbody.appendChild(tr);
  });

  document.getElementById('deck-summary').innerHTML = entries.length === 0 ? '<p style="color:#9a9cae">Keine gültigen Zeilen erkannt.</p>' : `
    <div class="stat-tile"><div class="stat-value">${entries.length}</div><div class="stat-label">Kartenzeilen</div></div>
    <div class="stat-tile"><div class="stat-value" style="color:#4dbb6a">${ownedCount}</div><div class="stat-label">Vollständig</div></div>
    <div class="stat-tile"><div class="stat-value" style="color:#c9a24d">${partialCount}</div><div class="stat-label">Teilweise</div></div>
    <div class="stat-tile"><div class="stat-value" style="color:#e05656">${missingCount}</div><div class="stat-label">Fehlt</div></div>
  `;
}

async function init() {
  showLoading('Lade Sammlung...');
  try {
    collectionCards = await loadCollection(updateLoadingProgress);
  } catch (e) {
    hideLoading();
    document.querySelector('main').innerHTML = `<p style="color:#e05656">Fehler: ${escapeHTML(e.message)}</p>`;
    return;
  }
  hideLoading();
  ownedByName = buildOwnedIndex(collectionCards);
  document.getElementById('check-btn').addEventListener('click', checkDeck);
}

init();
