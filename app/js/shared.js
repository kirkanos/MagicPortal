/* Shared utilities: CSV loading, Scryfall enrichment/cache, common helpers. */

const CSV_UPLOAD_URL = 'upload/collection.csv';
const CSV_FALLBACK_URL = 'data/ManaBox_Collection.csv';
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

/* Prefers an uploaded collection (upload/collection.csv), falling back to the
   bundled sample CSV when none was uploaded yet. */
async function loadCSVText() {
  try {
    const up = await fetch(CSV_UPLOAD_URL, { cache: 'no-store' });
    if (up.ok) {
      const text = await up.text();
      if (text.trim().length) return text;
    }
  } catch (e) {
    /* upload not reachable (e.g. plain static host) - use fallback */
  }
  const res = await fetch(CSV_FALLBACK_URL);
  if (!res.ok) throw new Error('Sammlung konnte nicht geladen werden (' + CSV_FALLBACK_URL + ')');
  return res.text();
}

/* Uploads a CSV file to the server's upload folder via WebDAV PUT. */
async function uploadCollectionCSV(file) {
  const res = await fetch(CSV_UPLOAD_URL, {
    method: 'PUT',
    headers: { 'Content-Type': 'text/csv' },
    body: file,
  });
  if (!res.ok) throw new Error('Upload fehlgeschlagen (HTTP ' + res.status + ')');
}

/* Removes the uploaded collection so the bundled sample is used again. */
async function resetCollectionCSV() {
  const res = await fetch(CSV_UPLOAD_URL, { method: 'DELETE' });
  if (!res.ok && res.status !== 404) throw new Error('Zurücksetzen fehlgeschlagen (HTTP ' + res.status + ')');
}

async function loadCSV() {
  const text = await loadCSVText();
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

const SCRYFALL_SETS_CACHE_KEY = 'mtg_scryfall_sets_v1';
const SCRYFALL_SETS_TTL_MS = 7 * 24 * 60 * 60 * 1000;

/* Loads the full Scryfall set index (code -> {name, cardCount, ...}), cached in localStorage.
   Used to determine how many cards a set has in total ("wie viele fehlen noch"). */
async function loadSetIndex() {
  try {
    const cached = JSON.parse(localStorage.getItem(SCRYFALL_SETS_CACHE_KEY));
    if (cached && cached.ts && Date.now() - cached.ts < SCRYFALL_SETS_TTL_MS && cached.sets) {
      return cached.sets;
    }
  } catch (e) {
    /* ignore malformed cache */
  }
  const sets = {};
  try {
    const res = await fetch('https://api.scryfall.com/sets');
    const data = await res.json();
    (data.data || []).forEach((s) => {
      sets[s.code.toLowerCase()] = {
        name: s.name,
        cardCount: s.card_count,
        iconSvgUri: s.icon_svg_uri || '',
        releasedAt: s.released_at || '',
        setType: s.set_type || '',
        digital: !!s.digital,
      };
    });
    try {
      localStorage.setItem(SCRYFALL_SETS_CACHE_KEY, JSON.stringify({ ts: Date.now(), sets }));
    } catch (e) {
      /* storage full - continue without persisting */
    }
  } catch (e) {
    console.warn('Scryfall sets fetch failed', e);
  }
  return sets;
}

const setCardsCache = {};

/* Fetches ALL cards of a set from Scryfall (every collector number, whether owned
   or not), following pagination. Cached in-memory per session. Returns a list of
   lightweight card objects used by the editions overlay. */
async function fetchSetCards(code) {
  const key = code.toLowerCase();
  if (setCardsCache[key]) return setCardsCache[key];

  const cards = [];
  let url =
    'https://api.scryfall.com/cards/search?order=set&unique=prints&q=' +
    encodeURIComponent('e:' + key);
  while (url) {
    const res = await fetch(url);
    if (!res.ok) {
      if (res.status === 404) break; // no cards found for this set code
      throw new Error('Scryfall Set-Abfrage fehlgeschlagen (' + res.status + ')');
    }
    const data = await res.json();
    (data.data || []).forEach((c) => {
      const face = c.image_uris ? c : (c.card_faces && c.card_faces[0]) || {};
      cards.push({
        collectorNumber: c.collector_number || '',
        name: c.name || '',
        rarity: c.rarity || '',
        typeLine: c.type_line || (c.card_faces && c.card_faces[0].type_line) || '',
        priceEur: (c.prices && c.prices.eur) || null,
        priceEurFoil: (c.prices && c.prices.eur_foil) || null,
        image: (face.image_uris && (face.image_uris.normal || face.image_uris.small)) || '',
        imageSmall: (face.image_uris && (face.image_uris.small || face.image_uris.normal)) || '',
      });
    });
    url = data.has_more ? data.next_page : null;
    if (url) await new Promise((r) => setTimeout(r, SCRYFALL_DELAY_MS));
  }

  setCardsCache[key] = cards;
  return cards;
}

function cardImage(card, size) {
  if (card.scryfall && card.scryfall.image) {
    return size === 'small' && card.scryfall.imageSmall ? card.scryfall.imageSmall : card.scryfall.image;
  }
  return '';
}

const LANGUAGE_FLAG = {
  en: '🇬🇧', de: '🇩🇪', fr: '🇫🇷', it: '🇮🇹', es: '🇪🇸', pt: '🇵🇹',
  ja: '🇯🇵', ko: '🇰🇷', ru: '🇷🇺', zhs: '🇨🇳', zht: '🇹🇼',
};

function languageFlag(lang) {
  return LANGUAGE_FLAG[String(lang || '').toLowerCase()] || '';
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

/* Detail modal for a grouped card: one row per language/finish variant with
   its price, quantity and date added. */
function cardGroupModalHTML(group) {
  const rep = group.rep;
  const img = cardImage(rep, 'normal');
  const variants = group.variants
    .slice()
    .sort((a, b) => (a.language || '').localeCompare(b.language || '') || a.foil.localeCompare(b.foil));

  const rows = variants
    .map((v) => {
      const price = v.scryfall && v.scryfall.priceEur ? formatCurrency(parseFloat(v.scryfall.priceEur), 'EUR') : '–';
      const foilTag = v.foil && v.foil !== 'normal' ? ` <span class="lang-foil">${escapeHTML(v.foil)}</span>` : '';
      return `
        <tr>
          <td>${languageFlag(v.language)} ${escapeHTML((v.language || '?').toUpperCase())}${foilTag}</td>
          <td class="num">${price}</td>
          <td class="num">${v.quantity}</td>
          <td>${escapeHTML((v.added || '').slice(0, 10)) || '–'}</td>
        </tr>`;
    })
    .join('');

  const totalMarket = variants.reduce(
    (a, v) => a + (v.scryfall && v.scryfall.priceEur ? parseFloat(v.scryfall.priceEur) : 0) * v.quantity,
    0
  );

  return `
    <div class="modal-card">
      <div class="modal-image">${img ? `<img src="${img}" alt="${escapeHTML(group.name)}">` : '<div class="no-image">Kein Bild</div>'}</div>
      <div class="modal-details">
        <h2>${escapeHTML(group.name)}</h2>
        <p class="modal-type">${escapeHTML((rep.scryfall && rep.scryfall.typeLine) || '')}</p>
        <dl>
          <dt>Set</dt><dd>${escapeHTML(group.setName)} (${escapeHTML(group.setCode)} #${escapeHTML(group.collectorNumber)})</dd>
          <dt>Rarität</dt><dd>${escapeHTML(rep.rarity)}</dd>
          <dt>Exemplare gesamt</dt><dd>${group.totalQty}</dd>
          <dt>Marktwert gesamt</dt><dd>${formatCurrency(totalMarket, 'EUR')}</dd>
        </dl>
        <h3 class="lang-table-title">Sprachen in deiner Sammlung</h3>
        <table class="lang-table">
          <thead><tr><th>Sprache</th><th class="num">Preis (Scryfall)</th><th class="num">Anzahl</th><th>Hinzugefügt</th></tr></thead>
          <tbody>${rows}</tbody>
        </table>
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

function openCardModal(html) {
  const overlay = document.getElementById('card-modal');
  if (!overlay) return;
  overlay.querySelector('.modal-body').innerHTML = '<span class="modal-close">&times;</span>' + html;
  overlay.classList.add('open');
}

/* Wires the CSV upload/reset buttons in the top navigation (present on every page). */
function initCollectionUpload() {
  const uploadInput = document.getElementById('csv-upload');
  if (uploadInput) {
    uploadInput.addEventListener('change', async () => {
      const file = uploadInput.files[0];
      if (!file) return;
      showLoading(`Lade „${file.name}" hoch...`);
      try {
        await uploadCollectionCSV(file);
        location.reload();
      } catch (e) {
        hideLoading();
        uploadInput.value = '';
        alert('CSV-Upload fehlgeschlagen: ' + e.message);
      }
    });
  }

  const resetBtn = document.getElementById('csv-reset');
  if (resetBtn) {
    resetBtn.addEventListener('click', async () => {
      if (!confirm('Hochgeladene Sammlung entfernen und zur mitgelieferten CSV zurückkehren?')) return;
      showLoading('Setze Sammlung zurück...');
      try {
        await resetCollectionCSV();
        location.reload();
      } catch (e) {
        hideLoading();
        alert('Zurücksetzen fehlgeschlagen: ' + e.message);
      }
    });
  }
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', initCollectionUpload);
} else {
  initCollectionUpload();
}
