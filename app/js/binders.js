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
      'Noch nichts vorhanden – lege oben einen an oder lade eine ManaBox-CSV hoch, die Ordner/Listen enthält.',
      'Nothing here yet – create one above, or upload a ManaBox CSV that contains folders/lists.'
    )}</p>`;
    return;
  }

  el.innerHTML = list
    .map(
      (b) => `
      <div class="binder-item" data-name="${escapeHTML(b.name)}" data-type="${escapeHTML(b.type || 'binder')}">
        <a class="binder-card" href="index.html?binder=${encodeURIComponent(b.name)}" title="${escapeHTML(b.name)}">
          <div class="binder-name">${escapeHTML(b.name)}</div>
          <div class="binder-meta">
            <span>${b.total.toLocaleString('de-DE')} ${t('Karten', 'cards')}</span>
            <span class="money">${formatCurrency(b.marketValue, 'EUR')}</span>
          </div>
        </a>
        <button type="button" class="binder-rename" title="${t('Umbenennen', 'Rename')}">✎</button>
        ${b.total ? '' : `<button type="button" class="binder-delete" title="${t('Leeren Ordner löschen', 'Delete empty folder')}">🗑</button>`}
      </div>`
    )
    .join('');
}

/* Rename a binder, or change its type. A name that exists already merges the
   two binders (the server adds up equal cards). */
function openBinderRename(item) {
  const name = item.dataset.name;
  const type = (item.dataset.type || 'binder').toLowerCase();
  const opt = (v, label) => `<option value="${v}"${type === v ? ' selected' : ''}>${label}</option>`;
  item.classList.add('editing');
  item.innerHTML = `
    <form class="binder-card binder-rename-form">
      <input type="text" class="binder-input" name="name" value="${escapeHTML(name)}" required maxlength="100" autocomplete="off">
      <select name="type">${opt('binder', t('Ordner', 'Binder'))}${opt('list', t('Liste', 'List'))}${opt('deck', 'Deck')}</select>
      <p class="section-hint">${t('Ein vorhandener Name führt die Ordner zusammen.', 'An existing name merges the folders.')}</p>
      <div class="binder-rename-actions">
        <button type="button" data-act="cancel">${t('Abbrechen', 'Cancel')}</button>
        <button type="submit" class="primary">${t('Speichern', 'Save')}</button>
      </div>
    </form>`;
  const form = item.querySelector('form');
  form.elements['name'].focus();
  form.elements['name'].select();
  form.querySelector('[data-act="cancel"]').addEventListener('click', () => initBinders());
  form.addEventListener('keydown', (e) => { if (e.key === 'Escape') initBinders(); });
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    const to = form.elements['name'].value.trim();
    const newType = form.elements['type'].value;
    if (!to || (to === name && newType === type)) return initBinders();
    form.querySelector('[type="submit"]').disabled = true;
    try {
      const r = await writeAPI('PATCH', '/binders/' + encodeURIComponent(name), { name: to, type: newType });
      let msg = t(`„${name}“ heißt jetzt „${r.name}“ (${r.rows} Zeilen).`, `“${name}” is now “${r.name}” (${r.rows} rows).`);
      if (r.merged) msg += ' ' + t(`${r.merged} davon zusammengeführt.`, `${r.merged} of them merged.`);
      if (r.volatile) msg += ' ' + t('Karten aus ManaBox kommen beim nächsten Import wieder in ihren alten Ordner.', 'Cards from ManaBox return to their old folder with the next import.');
      showNotice(msg);
      await initBinders();
    } catch (err) {
      showNotice(t('Umbenennen fehlgeschlagen: ', 'Renaming failed: ') + err.message, true);
      form.querySelector('[type="submit"]').disabled = false;
    }
  });
}

/* A new, empty folder or list (the type of the page); it stays until it is
   deleted, also while it holds no card. */
function openBinderCreate() {
  const type = (document.body.dataset.binderType || 'binder').toLowerCase();
  const el = document.getElementById('binder-list');
  if (el.querySelector('.binder-create')) return;
  if (!el.querySelector('.binder-item')) el.innerHTML = '';
  const item = document.createElement('div');
  item.className = 'binder-item editing binder-create';
  item.innerHTML = `
    <form class="binder-card binder-rename-form">
      <input type="text" class="binder-input" name="name" required maxlength="100" autocomplete="off"
        placeholder="${type === 'list' ? t('Name der Liste', 'List name') : t('Name des Ordners', 'Folder name')}">
      <div class="binder-rename-actions">
        <button type="button" data-act="cancel">${t('Abbrechen', 'Cancel')}</button>
        <button type="submit" class="primary">${t('Anlegen', 'Create')}</button>
      </div>
    </form>`;
  el.prepend(item);
  const form = item.querySelector('form');
  form.elements['name'].focus();
  form.querySelector('[data-act="cancel"]').addEventListener('click', () => initBinders());
  form.addEventListener('keydown', (e) => { if (e.key === 'Escape') initBinders(); });
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    const name = form.elements['name'].value.trim();
    if (!name) return;
    form.querySelector('[type="submit"]').disabled = true;
    try {
      await writeAPI('POST', '/binders', { name, type });
      showNotice(t(`„${name}“ ist angelegt.`, `“${name}” was created.`));
      await initBinders();
    } catch (err) {
      const msg = /exists/.test(err.message) ? t('Diesen Namen gibt es schon.', 'This name exists already.') : err.message;
      showNotice(t('Anlegen fehlgeschlagen: ', 'Creating failed: ') + msg, true);
      form.querySelector('[type="submit"]').disabled = false;
    }
  });
}

async function deleteBinder(item) {
  const name = item.dataset.name;
  try {
    await writeAPI('DELETE', '/binders/' + encodeURIComponent(name));
    showNotice(t(`„${name}“ ist gelöscht.`, `“${name}” was deleted.`));
  } catch (err) {
    showNotice(t('Löschen fehlgeschlagen: ', 'Deleting failed: ') + err.message, true);
  }
  await initBinders();
}

document.getElementById('binder-list').addEventListener('click', (e) => {
  const btn = e.target.closest('.binder-rename');
  if (btn) openBinderRename(btn.closest('.binder-item'));
  const del = e.target.closest('.binder-delete');
  if (del) deleteBinder(del.closest('.binder-item'));
});
document.getElementById('binder-new').addEventListener('click', openBinderCreate);

initBinders();
