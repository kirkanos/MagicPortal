/* Wishlist page: what is on the list, what it would cost right now, how the
   price has moved, and the bridge to Cardmarket.

   Every entry is keyed by card name. The fields (target price, minimum
   condition, note, priority) are edited inline and saved on change - they are
   writes, so they are only enabled once the session is unlocked. */

let wlItems = [];
let wlSummary = {};

async function loadWishlistPage() {
  const res = await fetch(API + '/wishlist', { cache: 'no-store' });
  if (!res.ok) throw new Error('HTTP ' + res.status);
  const data = await res.json();
  wlItems = data.items || [];
  wlSummary = data.summary || {};
  // Keep the shared marker state in step, so the stars elsewhere are right.
  wishlistNames = new Set(wlItems.map((i) => i.name.toLowerCase()));
}

function renderSummary() {
  const s = wlSummary;
  const days = s.days || 30;
  const unpriced =
    s.unpriced > 0 ? ` <span class="dim">(+${s.unpriced} ${t('ohne Preis', 'unpriced')})</span>` : '';
  document.getElementById('stats-bar').innerHTML = `
    <div class="stat-tile"><div class="stat-value">${s.items || 0}</div><div class="stat-label">${t('Einträge', 'Entries')}</div></div>
    <div class="stat-tile" title="${priceHint()}"><div class="stat-value">${formatCurrency(s.value || 0, 'EUR')}</div><div class="stat-label">${t('Kosten gesamt', 'Total cost')}${unpriced}</div></div>
    <div class="stat-tile"><div class="stat-value" style="color:#4dbb6a">${s.targetsMet || 0}</div><div class="stat-label">${t('Zielpreis erreicht', 'Target price reached')}</div></div>
    <div class="stat-tile" title="${t('Karten, die du besitzt – aber in einem schlechteren Zustand als gewünscht.', 'Cards you own – but in a worse condition than you want.')}"><div class="stat-value" style="color:#c9a24d">${s.upgrades || 0}</div><div class="stat-label">${t('Upgrade nötig', 'Upgrade wanted')}</div></div>
    <div class="stat-tile"><div class="stat-value">${days}</div><div class="stat-label">${t('Tage Preisvergleich', 'Days of price comparison')}</div></div>
  `;
}

function changeHTML(item) {
  if (item.changePct == null) {
    return `<span class="wl-change dim" title="${t('Noch keine Preishistorie für diese Karte – die kommt mit dem nächsten täglichen Snapshot.', 'No price history for this card yet – it arrives with the next daily snapshot.')}">–</span>`;
  }
  const pct = item.changePct;
  const cls = pct < 0 ? 'down' : pct > 0 ? 'up' : '';
  const sign = pct > 0 ? '+' : '';
  const then = item.priceThen != null ? formatCurrency(item.priceThen, 'EUR') : '–';
  const title = t(`Vor ${wlSummary.days || 30} Tagen: ${then}`, `${wlSummary.days || 30} days ago: ${then}`);
  return `<span class="wl-change ${cls}" title="${escapeHTML(title)}">${sign}${pct.toFixed(1)} %</span>`;
}

function ownedHTML(item) {
  if (!item.ownedQuantity) return `<span class="dim">${t('nicht vorhanden', 'not owned')}</span>`;
  const cond = item.bestCondition ? conditionLabel(item.bestCondition) : t('unbekannt', 'unknown');
  if (item.needsUpgrade) {
    return `<span class="wl-upgrade" title="${escapeHTML(
      t(
        `Du besitzt ${item.ownedQuantity}×, bester Zustand ${cond} – gewünscht ist mindestens ${conditionLabel(item.minCondition)}.`,
        `You own ${item.ownedQuantity}×, best condition ${cond} – you want at least ${conditionLabel(item.minCondition)}.`
      )
    )}">${item.ownedQuantity}× ${escapeHTML(cond)} · ${t('Upgrade', 'upgrade')}</span>`;
  }
  return `<span class="wl-owned">${item.ownedQuantity}× ${escapeHTML(cond)}</span>`;
}

function priceHTML(item) {
  if (item.unknownToCatalog) {
    return `<span class="wl-unknown" title="${t('Dieser Name steht in keinem Scryfall-Katalogeintrag. Tippfehler?', 'No Scryfall catalog entry carries this name. A typo?')}">${t('unbekannter Name', 'unknown name')}</span>`;
  }
  if (item.price == null) return `<span class="dim">${t('kein Preis', 'no price')}</span>`;
  const where = item.priceSetName
    ? `${item.priceSetName} #${item.priceNumber}`
    : `${item.priceSetCode || ''} #${item.priceNumber || ''}`;
  const label = item.pinned
    ? t('festgelegter Druck', 'pinned printing')
    : t('günstigster Druck', 'cheapest printing');
  return (
    `<span class="wl-price${item.targetMet ? ' met' : ''}">${formatCurrency(item.price, 'EUR')}</span> ` +
    `<span class="wl-where" title="${escapeHTML(label)}">${escapeHTML(where)}${item.pinned ? ' 📌' : ''}</span>`
  );
}

function rowHTML(item) {
  const img = setCardImage({ imageId: item.imageId }, 'small');
  const target = item.targetEur != null ? item.targetEur : '';
  return `
    <div class="wl-row${item.targetMet ? ' target-met' : ''}" data-name="${escapeHTML(item.name)}">
      <div class="wl-thumb">
        ${img ? `<img loading="lazy" src="${escapeHTML(img)}" alt="${escapeHTML(item.name)}">` : `<div class="no-image">${t('Kein Bild', 'No image')}</div>`}
      </div>
      <div class="wl-main">
        <div class="wl-name">
          ${escapeHTML(item.name)}
          ${item.priority > 0 ? `<span class="wl-prio" title="${t('Priorität', 'Priority')}">${'!'.repeat(item.priority)}</span>` : ''}
        </div>
        <div class="wl-meta">${priceHTML(item)} ${changeHTML(item)}</div>
        <div class="wl-meta">${ownedHTML(item)}</div>
        ${item.cardmarketUri ? `<a class="rl-cm-link" href="${escapeHTML(item.cardmarketUri)}" target="_blank" rel="noopener noreferrer">Cardmarket ↗</a>` : ''}
      </div>
      <div class="wl-fields">
        <label class="wl-field">
          <span>${t('Zielpreis', 'Target price')}</span>
          <input type="number" step="0.01" min="0" class="wl-input" data-field="targetEur" value="${target}" placeholder="–">
        </label>
        <label class="wl-field">
          <span>${t('Mindestzustand', 'Minimum condition')}</span>
          <select class="wl-input" data-field="minCondition">${conditionOptionsHTML(item.minCondition || '')}</select>
        </label>
        <label class="wl-field">
          <span>${t('Priorität', 'Priority')}</span>
          <select class="wl-input" data-field="priority">
            <option value="0"${item.priority === 0 ? ' selected' : ''}>${t('normal', 'normal')}</option>
            <option value="1"${item.priority === 1 ? ' selected' : ''}>${t('hoch', 'high')}</option>
            <option value="2"${item.priority === 2 ? ' selected' : ''}>${t('sehr hoch', 'very high')}</option>
          </select>
        </label>
        <label class="wl-field wl-field-wide">
          <span>${t('Notiz', 'Note')}</span>
          <input type="text" class="wl-input" data-field="note" value="${escapeHTML(item.note || '')}" placeholder="–">
        </label>
      </div>
      <button type="button" class="wl-remove" data-remove="${escapeHTML(item.name)}" title="${t('Von der Wunschliste entfernen', 'Remove from the wishlist')}" aria-label="${t('Von der Wunschliste entfernen', 'Remove from the wishlist')}">&times;</button>
    </div>`;
}

function renderList() {
  const q = document.getElementById('search').value.trim().toLowerCase();
  const state = document.getElementById('filter-state').value;
  const sortBy = document.getElementById('sort-by').value;

  let list = wlItems.slice();
  if (q) list = list.filter((i) => i.name.toLowerCase().includes(q) || (i.note || '').toLowerCase().includes(q));
  if (state === 'target') list = list.filter((i) => i.targetMet);
  else if (state === 'upgrade') list = list.filter((i) => i.needsUpgrade);
  else if (state === 'missing') list = list.filter((i) => !i.ownedQuantity);

  const price = (i) => (i.price != null ? i.price : 0);
  list.sort((a, b) => {
    switch (sortBy) {
      case 'price-asc':
        return price(a) - price(b) || a.name.localeCompare(b.name);
      case 'price-desc':
        return price(b) - price(a) || a.name.localeCompare(b.name);
      case 'change-asc':
        // Entries without history sort last - they say nothing about a drop.
        return (a.changePct == null ? 1e9 : a.changePct) - (b.changePct == null ? 1e9 : b.changePct);
      case 'name':
        return a.name.localeCompare(b.name);
      case 'added':
        return (b.added || '').localeCompare(a.added || '');
      default:
        return 0; // the backend already sorts by priority, then price
    }
  });

  document.getElementById('result-count').textContent = t(
    `${list.length} von ${wlItems.length} Einträgen`,
    `${list.length} of ${wlItems.length} entries`
  );

  const el = document.getElementById('wl-list');
  el.innerHTML = list.length
    ? list.map(rowHTML).join('')
    : `<p class="eo-empty">${
        wlItems.length
          ? t('Keine Einträge für diesen Filter.', 'No entries for this filter.')
          : t(
              'Noch nichts auf der Wunschliste. Markiere Karten mit dem ☆ in der Reserved List, in einer Edition oder im Deck-Checker.',
              'Nothing on the wishlist yet. Mark cards with the ☆ on the Reserved List, in an edition or in the deck checker.'
            )
      }</p>`;
}

/* Saves one changed field. The whole entry is sent back, because POST
   /api/wishlist upserts the complete row. */
async function saveField(name, field, value) {
  const item = wlItems.find((i) => i.name === name);
  if (!item) return;
  if (field === 'targetEur') item.targetEur = value === '' ? null : parseFloat(value);
  else if (field === 'priority') item.priority = parseInt(value, 10) || 0;
  else item[field] = value;

  const res = await fetch(API + '/wishlist', {
    method: 'POST',
    headers: uploadHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({
      name: item.name,
      setCode: item.setCode || '',
      collectorNumber: item.collectorNumber || '',
      targetEur: item.targetEur,
      minCondition: item.minCondition || '',
      note: item.note || '',
      priority: item.priority || 0,
    }),
  });
  if (res.status === 403) throw new Error(t('Falsches oder fehlendes Passwort', 'Wrong or missing password'));
  if (!res.ok) throw new Error(t('Speichern fehlgeschlagen', 'Saving failed') + ' (HTTP ' + res.status + ')');
  // The target comparison and the summary are computed server-side, so reload.
  await loadWishlistPage();
  renderSummary();
  renderList();
}

/* Puts the export text on the page itself: the copy confirmation when the
   clipboard worked, and the only way to get at the list when it did not. */
function showExportText(text, copied) {
  const box = document.getElementById('wl-export-out');
  if (!box) return;
  box.innerHTML = `
    <p class="wl-export-note">${
      copied
        ? t('In die Zwischenablage kopiert – und hier zum Nachlesen:', 'Copied to the clipboard – and here to read back:')
        : t('Die Zwischenablage war nicht erreichbar. Hier ist der Text zum Markieren und Kopieren:', 'The clipboard was not reachable. Here is the text to select and copy:')
    }</p>
    <textarea class="wl-export-text" readonly rows="10"></textarea>`;
  const area = box.querySelector('textarea');
  area.value = text;
  box.hidden = false;
  if (!copied) {
    area.focus();
    area.select();
  }
}

async function copyExport() {
  const btn = document.getElementById('wl-export');
  btn.disabled = true;
  try {
    const res = await fetch(API + '/wishlist/export', { cache: 'no-store' });
    if (!res.ok) throw new Error('HTTP ' + res.status);
    const text = await res.text();
    if (!text.trim()) {
      alert(t('Nichts zu exportieren.', 'Nothing to export.'));
      return;
    }
    openHelp(document.getElementById('wl-howto'));
    let copied = false;
    try {
      await navigator.clipboard.writeText(text);
      copied = true;
    } catch (e) {
      // The Clipboard API needs a secure context and a focused document, so it
      // is not always available (plain http, an unfocused window). The text is
      // shown instead - selectable and pre-selected, never a popup, which a
      // browser may well block.
      copied = false;
    }
    showExportText(text, copied);
    if (copied) {
      const original = btn.textContent;
      btn.textContent = t('✓ Kopiert', '✓ Copied');
      setTimeout(() => {
        btn.textContent = original;
      }, 2000);
    }
  } catch (e) {
    alert(t('Export fehlgeschlagen: ', 'Export failed: ') + e.message);
  } finally {
    btn.disabled = false;
  }
}

/* Write controls follow the login state, like every other action in the app. */
function applyUnlocked(unlocked) {
  document.querySelectorAll('.wl-input').forEach((el) => {
    el.disabled = !unlocked;
  });
  document.querySelectorAll('.wl-remove').forEach((el) => {
    el.style.display = unlocked ? '' : 'none';
  });
  const form = document.getElementById('wl-add-form');
  if (form) form.style.display = unlocked ? '' : 'none';
}

async function init() {
  showLoading(t('Lade Wunschliste...', 'Loading wishlist...'));
  try {
    await uiConfigReady; // decides whether images come from the mirror
    await loadWishlistPage();
  } catch (e) {
    hideLoading();
    document.querySelector('main').innerHTML = `<p style="color:#e05656">${t('Fehler beim Laden', 'Error loading')}: ${escapeHTML(e.message)}</p>`;
    return;
  }
  hideLoading();
  renderSummary();
  renderList();
  applyUnlocked(isUnlocked);

  document.getElementById('search').addEventListener('input', debounce(() => { renderList(); applyUnlocked(isUnlocked); }, 150));
  ['filter-state', 'sort-by'].forEach((id) =>
    document.getElementById(id).addEventListener('change', () => { renderList(); applyUnlocked(isUnlocked); })
  );
  document.getElementById('wl-export').addEventListener('click', copyExport);
  document.addEventListener('auth-change', (e) => applyUnlocked(e.detail.unlocked));

  const list = document.getElementById('wl-list');
  const save = async (row, input) => {
    try {
      await saveField(row.dataset.name, input.dataset.field, input.value);
      applyUnlocked(isUnlocked);
    } catch (err) {
      alert(err.message);
    }
  };
  list.addEventListener('change', (e) => {
    const input = e.target.closest('.wl-input');
    const row = e.target.closest('.wl-row');
    if (input && row) save(row, input);
  });
  list.addEventListener('click', async (e) => {
    const btn = e.target.closest('.wl-remove');
    if (!btn) return;
    try {
      await setWishlist(btn.dataset.remove, false);
      await loadWishlistPage();
      renderSummary();
      renderList();
      applyUnlocked(isUnlocked);
    } catch (err) {
      alert(err.message);
    }
  });

  document.getElementById('wl-add-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const field = document.getElementById('wl-add-name');
    const name = field.value.trim();
    if (!name) return;
    try {
      await setWishlist(name, true);
      field.value = '';
      await loadWishlistPage();
      renderSummary();
      renderList();
      applyUnlocked(isUnlocked);
    } catch (err) {
      alert(err.message);
    }
  });
}

init();
