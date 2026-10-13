let collectionCards = [];
let ownedByName = new Map();
let lastMissingNames = [];

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
    if (owned >= entry.qty) { status = t('Vollständig', 'Complete'); statusClass = 'status-owned'; ownedCount++; }
    else if (owned > 0) { status = t('Teilweise', 'Partial'); statusClass = 'status-partial'; partialCount++; }
    else { status = t('Fehlt', 'Missing'); statusClass = 'status-missing'; missingCount++; }

    const tr = document.createElement('tr');
    tr.innerHTML = `
      <td>${escapeHTML(entry.name)}</td>
      <td>${entry.qty}</td>
      <td>${owned}</td>
      <td class="${statusClass}">${status}</td>
      <td class="wish-cell">${owned >= entry.qty ? '' : wishButtonHTML(entry.name)}</td>
    `;
    tbody.appendChild(tr);
  });

  // Everything the deck is short of, in one go - this is where the need for a
  // card usually comes up, so it should not take one click per row.
  lastMissingNames = entries
    .filter((e) => (ownedByName.get(e.name.toLowerCase()) || 0) < e.qty)
    .map((e) => e.name);
  const bulk = document.getElementById('wish-all-missing');
  if (bulk) {
    bulk.style.display = lastMissingNames.length && isUnlocked ? '' : 'none';
    bulk.textContent = t(
      `☆ Alle ${lastMissingNames.length} fehlenden auf die Wunschliste`,
      `☆ Put all ${lastMissingNames.length} missing cards on the wishlist`
    );
  }

  document.getElementById('deck-summary').innerHTML = entries.length === 0 ? `<p style="color:#9a9cae">${t('Keine gültigen Zeilen erkannt.', 'No valid lines detected.')}</p>` : `
    <div class="stat-tile"><div class="stat-value">${entries.length}</div><div class="stat-label">${t('Kartenzeilen', 'Card lines')}</div></div>
    <div class="stat-tile"><div class="stat-value" style="color:#4dbb6a">${ownedCount}</div><div class="stat-label">${t('Vollständig', 'Complete')}</div></div>
    <div class="stat-tile"><div class="stat-value" style="color:#c9a24d">${partialCount}</div><div class="stat-label">${t('Teilweise', 'Partial')}</div></div>
    <div class="stat-tile"><div class="stat-value" style="color:#e05656">${missingCount}</div><div class="stat-label">${t('Fehlt', 'Missing')}</div></div>
  `;
}

async function init() {
  showLoading(t('Lade Sammlung...', 'Loading collection...'));
  try {
    collectionCards = await loadCollection(updateLoadingProgress);
    await loadWishlistNames();
  } catch (e) {
    hideLoading();
    document.querySelector('main').innerHTML = `<p style="color:#e05656">${t('Fehler', 'Error')}: ${escapeHTML(e.message)}</p>`;
    return;
  }
  hideLoading();
  ownedByName = buildOwnedIndex(collectionCards);
  document.getElementById('check-btn').addEventListener('click', checkDeck);
  initWishButtons(document.getElementById('deck-table'));
  document.addEventListener('auth-change', checkDeck);

  const bulk = document.getElementById('wish-all-missing');
  bulk.addEventListener('click', async () => {
    const todo = lastMissingNames.filter((n) => !onWishlist(n));
    if (!todo.length) {
      alert(t('Alle fehlenden Karten stehen schon auf der Wunschliste.', 'Every missing card is already on the wishlist.'));
      return;
    }
    if (!confirm(t(`${todo.length} Karten auf die Wunschliste setzen?`, `Put ${todo.length} cards on the wishlist?`))) return;
    bulk.disabled = true;
    let failed = 0;
    // Sequential on purpose: the backend serialises database access anyway, so
    // firing them in parallel only queues them up behind one connection.
    for (const name of todo) {
      try {
        await setWishlist(name, true);
      } catch (e) {
        failed++;
      }
    }
    bulk.disabled = false;
    checkDeck();
    if (failed) alert(t(`${failed} Karten konnten nicht gespeichert werden.`, `${failed} cards could not be saved.`));
  });
}

init();
