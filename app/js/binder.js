const SLOTS_PER_PAGE = 9;
let binderCards = [];
let binderFiltered = [];
let currentPage = 0;

function populateBinderFilters(cards) {
  const setSel = document.getElementById('filter-set');
  uniqueSorted(cards.map((c) => c.setCode + ' — ' + c.setName)).forEach((s) => {
    const opt = document.createElement('option');
    opt.value = s.split(' — ')[0];
    opt.textContent = s;
    setSel.appendChild(opt);
  });
}

function applyBinderFilters() {
  const q = document.getElementById('search').value.trim().toLowerCase();
  const set = document.getElementById('filter-set').value;
  const sortBy = document.getElementById('sort-by').value;

  binderFiltered = binderCards.filter((c) => {
    if (q && !c.name.toLowerCase().includes(q)) return false;
    if (set && c.setCode !== set) return false;
    return true;
  });

  binderFiltered.sort((a, b) => {
    if (sortBy === 'set') return a.setCode.localeCompare(b.setCode) || a.name.localeCompare(b.name);
    if (sortBy === 'price-desc') return b.purchasePrice - a.purchasePrice;
    return a.name.localeCompare(b.name);
  });

  currentPage = 0;
  renderBinderPage();
}

function renderBinderPage() {
  const totalPages = Math.max(1, Math.ceil(binderFiltered.length / SLOTS_PER_PAGE));
  currentPage = Math.min(currentPage, totalPages - 1);
  const start = currentPage * SLOTS_PER_PAGE;
  const pageCards = binderFiltered.slice(start, start + SLOTS_PER_PAGE);

  const container = document.getElementById('binder-page');
  container.innerHTML = '';
  for (let i = 0; i < SLOTS_PER_PAGE; i++) {
    const card = pageCards[i];
    const slot = document.createElement('div');
    if (!card) {
      slot.className = 'binder-slot empty';
      container.appendChild(slot);
      continue;
    }
    slot.className = 'binder-slot';
    const img = cardImage(card, 'small');
    slot.innerHTML = `
      <div class="card-tile">
        <div class="thumb">
          ${img ? `<img loading="lazy" src="${img}" alt="${escapeHTML(card.name)}">` : '<div class="no-image">Kein Bild</div>'}
          ${card.foil !== 'normal' ? `<span class="foil-badge">${escapeHTML(card.foil)}</span>` : ''}
        </div>
        <div class="info"><div class="name">${escapeHTML(card.name)}</div><div class="meta"><span>${escapeHTML(card.setCode)}</span></div></div>
      </div>`;
    slot.querySelector('.card-tile').addEventListener('click', () => openCardModal(card));
    container.appendChild(slot);
  }

  document.getElementById('page-label').textContent = `Seite ${currentPage + 1} / ${totalPages}`;
  document.getElementById('prev-page').disabled = currentPage === 0;
  document.getElementById('next-page').disabled = currentPage >= totalPages - 1;
}

async function init() {
  initModal();
  showLoading('Lade CSV-Datei...');
  try {
    binderCards = await loadCollection(updateLoadingProgress);
  } catch (e) {
    hideLoading();
    document.getElementById('binder-page').innerHTML = `<p style="color:#e05656">Fehler: ${escapeHTML(e.message)}</p>`;
    return;
  }
  hideLoading();
  populateBinderFilters(binderCards);
  applyBinderFilters();

  document.getElementById('search').addEventListener('input', debounce(applyBinderFilters, 150));
  document.getElementById('filter-set').addEventListener('change', applyBinderFilters);
  document.getElementById('sort-by').addEventListener('change', applyBinderFilters);
  document.getElementById('prev-page').addEventListener('click', () => { currentPage--; renderBinderPage(); });
  document.getElementById('next-page').addEventListener('click', () => { currentPage++; renderBinderPage(); });
}

init();
