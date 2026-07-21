/* Overview of binders (folders) or lists, depending on document.body.dataset.binderType.
   Each entry links into the card view filtered to that binder. */
async function initBinders() {
  const type = (document.body.dataset.binderType || 'binder').toLowerCase();
  const el = document.getElementById('binder-list');
  showLoading(t('Lade Sammlung...', 'Loading collection...'));

  let binders = [];
  try {
    const res = await fetch('api/binders', { cache: 'no-store' });
    if (res.ok) binders = await res.json();
  } catch (e) {
    /* ignore */
  }
  hideLoading();

  const list = binders.filter((b) => (b.type || '').toLowerCase() === type && b.name);
  document.getElementById('binder-count').textContent =
    `${list.length} ${type === 'list' ? t('Listen', 'lists') : t('Ordner', 'folders')}`;

  if (!list.length) {
    el.innerHTML = `<p class="section-hint">${t(
      'Noch nichts vorhanden – lade eine ManaBox-CSV hoch, die Ordner/Listen enthält.',
      'Nothing here yet – upload a ManaBox CSV that contains folders/lists.'
    )}</p>`;
    return;
  }

  el.innerHTML = list
    .map(
      (b) => `
      <a class="binder-card" href="index.html?binder=${encodeURIComponent(b.name)}" title="${escapeHTML(b.name)}">
        <div class="binder-name">${escapeHTML(b.name)}</div>
        <div class="binder-meta">
          <span>${b.total.toLocaleString('de-DE')} ${t('Karten', 'cards')}</span>
          <span class="money">${formatCurrency(b.marketValue, 'EUR')}</span>
        </div>
      </a>`
    )
    .join('');
}

initBinders();
