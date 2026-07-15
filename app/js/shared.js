/* Shared utilities: CSV loading, Scryfall enrichment/cache, common helpers. */

const CSV_URL = 'data/ManaBox_Collection.csv';
const SCRYFALL_CACHE_KEY = 'mtg_scryfall_cache_v1';
const SCRYFALL_BATCH_SIZE = 75;
const SCRYFALL_DELAY_MS = 110;

function parseCSVLine(line) {
  const values = [];
  let current = '';
  let inQuotes = false;
  for (let i = 0; i < line.length; i++) {
    const ch = line[i];
    if (inQuotes) {
      if (ch === '"') {
        if (line[i + 1] === '"') {
          current += '"';
          i++;
        } else {
          inQuotes = false;
        }
      } else {
        current += ch;
      }
    } else if (ch === '"') {
      inQuotes = true;
    } else if (ch === ',') {
      values.push(current);
      current = '';
    } else {
      current += ch;
    }
  }
  values.push(current);
  return values;
}

async function loadCSV() {
  const res = await fetch(CSV_URL);
  if (!res.ok) throw new Error('Could not load ' + CSV_URL);
  const text = await res.text();
  const lines = text.split(/\r?\n/).filter((l) => l.trim().length > 0);
  const headers = parseCSVLine(lines[0]).map((h) => h.trim().toLowerCase());
  return lines.slice(1).map((line) => {
    const values = parseCSVLine(line);
    const obj = {};
    headers.forEach((h, i) => {
      obj[h] = values[i] !== undefined ? values[i].trim() : '';
    });
    return obj;
  });
}

function normalizeCard(row, index) {
  return {
    key: index,
    name: row['name'] || '',
    setCode: (row['set code'] || '').toUpperCase(),
    setName: row['set name'] || '',
    collectorNumber: row['collector number'] || '',
    foil: row['foil'] || 'normal',
    rarity: row['rarity'] || '',
    quantity: parseInt(row['quantity'], 10) || 1,
    scryfallId: row['scryfall id'] || '',
    purchasePrice: parseFloat(row['purchase price']) || 0,
    currency: row['purchase price currency'] || 'EUR',
    condition: row['condition'] || '',
    language: row['language'] || '',
    added: row['added'] || '',
  };
}

function getScryfallCache() {
  try {
    return JSON.parse(localStorage.getItem(SCRYFALL_CACHE_KEY)) || {};
  } catch (e) {
    return {};
  }
}

function saveScryfallCache(cache) {
  try {
    localStorage.setItem(SCRYFALL_CACHE_KEY, JSON.stringify(cache));
  } catch (e) {
    /* storage full or unavailable - continue without persisting */
  }
}

async function enrichWithScryfall(cards, onProgress) {
  const cache = getScryfallCache();
  const uniqueIds = [...new Set(cards.map((c) => c.scryfallId).filter(Boolean))];
  const missing = uniqueIds.filter((id) => !cache[id]);
  const total = missing.length;

  for (let i = 0; i < missing.length; i += SCRYFALL_BATCH_SIZE) {
    const batch = missing.slice(i, i + SCRYFALL_BATCH_SIZE);
    const identifiers = batch.map((id) => ({ id }));
    try {
      const res = await fetch('https://api.scryfall.com/cards/collection', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ identifiers }),
      });
      const data = await res.json();
      (data.data || []).forEach((card) => {
        const face = card.image_uris ? card : (card.card_faces && card.card_faces[0]) || {};
        cache[card.id] = {
          name: card.name,
          image: (face.image_uris && face.image_uris.normal) || '',
          imageSmall: (face.image_uris && face.image_uris.small) || '',
          imageArtCrop: (face.image_uris && face.image_uris.art_crop) || '',
          manaCost: card.mana_cost || (card.card_faces && card.card_faces[0].mana_cost) || '',
          typeLine: card.type_line || '',
          colors: card.colors || (card.card_faces && card.card_faces[0].colors) || [],
          priceEur: (card.prices && (card.prices.eur || card.prices.eur_foil)) || null,
          setName: card.set_name || '',
        };
      });
    } catch (e) {
      console.warn('Scryfall batch failed', e);
    }
    saveScryfallCache(cache);
    if (onProgress) onProgress(Math.min(i + SCRYFALL_BATCH_SIZE, total), total);
    if (i + SCRYFALL_BATCH_SIZE < missing.length) {
      await new Promise((r) => setTimeout(r, SCRYFALL_DELAY_MS));
    }
  }

  return cards.map((c) => ({ ...c, scryfall: cache[c.scryfallId] || null }));
}

async function loadCollection(onProgress) {
  const rows = await loadCSV();
  const cards = rows.map(normalizeCard);
  return enrichWithScryfall(cards, onProgress);
}

function cardImage(card, size) {
  if (card.scryfall && card.scryfall.image) {
    return size === 'small' && card.scryfall.imageSmall ? card.scryfall.imageSmall : card.scryfall.image;
  }
  return '';
}

function formatCurrency(value, currency) {
  if (value === null || value === undefined || isNaN(value)) return '-';
  return new Intl.NumberFormat('de-DE', { style: 'currency', currency: currency || 'EUR' }).format(value);
}

function debounce(fn, wait) {
  let t;
  return (...args) => {
    clearTimeout(t);
    t = setTimeout(() => fn(...args), wait);
  };
}

function uniqueSorted(values) {
  return [...new Set(values.filter(Boolean))].sort((a, b) => a.localeCompare(b));
}

function showLoading(message) {
  const el = document.getElementById('loading-overlay');
  if (!el) return;
  el.style.display = 'flex';
  const text = el.querySelector('.loading-text');
  if (text) text.textContent = message || 'Lade Sammlung...';
}

function updateLoadingProgress(done, total) {
  const bar = document.querySelector('#loading-overlay .loading-bar-fill');
  const text = document.querySelector('#loading-overlay .loading-text');
  if (bar && total > 0) bar.style.width = Math.round((done / total) * 100) + '%';
  if (text) text.textContent = `Lade Kartendaten von Scryfall... (${done}/${total})`;
}

function hideLoading() {
  const el = document.getElementById('loading-overlay');
  if (el) el.style.display = 'none';
}

function cardModalHTML(card) {
  const img = cardImage(card, 'normal');
  const price = card.scryfall && card.scryfall.priceEur ? formatCurrency(parseFloat(card.scryfall.priceEur), 'EUR') : '-';
  return `
    <div class="modal-card">
      <div class="modal-image">${img ? `<img src="${img}" alt="${escapeHTML(card.name)}">` : '<div class="no-image">Kein Bild</div>'}</div>
      <div class="modal-details">
        <h2>${escapeHTML(card.name)}</h2>
        <p class="modal-type">${escapeHTML((card.scryfall && card.scryfall.typeLine) || '')}</p>
        <dl>
          <dt>Set</dt><dd>${escapeHTML(card.setName)} (${escapeHTML(card.setCode)} #${escapeHTML(card.collectorNumber)})</dd>
          <dt>Rarität</dt><dd>${escapeHTML(card.rarity)}</dd>
          <dt>Ausführung</dt><dd>${escapeHTML(card.foil)}</dd>
          <dt>Zustand</dt><dd>${escapeHTML(card.condition)}</dd>
          <dt>Sprache</dt><dd>${escapeHTML(card.language)}</dd>
          <dt>Anzahl</dt><dd>${card.quantity}</dd>
          <dt>Kaufpreis</dt><dd>${formatCurrency(card.purchasePrice, card.currency)}</dd>
          <dt>Marktwert (Scryfall)</dt><dd>${price}</dd>
          <dt>Hinzugefügt</dt><dd>${escapeHTML((card.added || '').slice(0, 10))}</dd>
        </dl>
      </div>
    </div>
  `;
}

function escapeHTML(str) {
  return String(str || '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function initModal() {
  const overlay = document.getElementById('card-modal');
  if (!overlay) return;
  overlay.addEventListener('click', (e) => {
    if (e.target === overlay || e.target.classList.contains('modal-close')) {
      overlay.classList.remove('open');
    }
  });
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') overlay.classList.remove('open');
  });
}

function openCardModal(card) {
  const overlay = document.getElementById('card-modal');
  if (!overlay) return;
  overlay.querySelector('.modal-body').innerHTML = cardModalHTML(card);
  overlay.classList.add('open');
}
