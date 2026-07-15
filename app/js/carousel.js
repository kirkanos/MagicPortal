let carouselCards = [];
let carouselFiltered = [];
let carouselIndex = 0;

function renderCarouselCard() {
  const container = document.getElementById('carousel-card');
  if (carouselFiltered.length === 0) {
    container.innerHTML = '<p>Keine Karten gefunden.</p>';
    document.getElementById('carousel-index').textContent = '';
    return;
  }
  const card = carouselFiltered[carouselIndex];
  const img = cardImage(card, 'normal');
  const price = card.scryfall && card.scryfall.priceEur ? formatCurrency(parseFloat(card.scryfall.priceEur), 'EUR') : '-';
  container.innerHTML = `
    <div class="carousel-image">${img ? `<img src="${img}" alt="${escapeHTML(card.name)}">` : '<div class="no-image">Kein Bild</div>'}</div>
    <div class="carousel-info">
      <h2>${escapeHTML(card.name)}</h2>
      <p class="modal-type">${escapeHTML((card.scryfall && card.scryfall.typeLine) || '')}</p>
      <dl>
        <dt>Set</dt><dd>${escapeHTML(card.setName)} (${escapeHTML(card.setCode)})</dd>
        <dt>Rarität</dt><dd>${escapeHTML(card.rarity)}</dd>
        <dt>Ausführung</dt><dd>${escapeHTML(card.foil)}</dd>
        <dt>Anzahl</dt><dd>${card.quantity}</dd>
        <dt>Kaufpreis</dt><dd>${formatCurrency(card.purchasePrice, card.currency)}</dd>
        <dt>Marktwert</dt><dd>${price}</dd>
      </dl>
    </div>
  `;
  document.getElementById('carousel-index').textContent = `${carouselIndex + 1} / ${carouselFiltered.length}`;
}

function applyCarouselFilters() {
  const q = document.getElementById('search').value.trim().toLowerCase();
  const set = document.getElementById('filter-set').value;
  carouselFiltered = carouselCards.filter((c) => {
    if (q && !c.name.toLowerCase().includes(q)) return false;
    if (set && c.setCode !== set) return false;
    return true;
  }).sort((a, b) => a.name.localeCompare(b.name));
  carouselIndex = 0;
  renderCarouselCard();
}

function step(delta) {
  if (carouselFiltered.length === 0) return;
  carouselIndex = (carouselIndex + delta + carouselFiltered.length) % carouselFiltered.length;
  renderCarouselCard();
}

async function init() {
  showLoading('Lade CSV-Datei...');
  try {
    carouselCards = await loadCollection(updateLoadingProgress);
  } catch (e) {
    hideLoading();
    document.getElementById('carousel-card').innerHTML = `<p style="color:#e05656">Fehler: ${escapeHTML(e.message)}</p>`;
    return;
  }
  hideLoading();

  const setSel = document.getElementById('filter-set');
  uniqueSorted(carouselCards.map((c) => c.setCode + ' — ' + c.setName)).forEach((s) => {
    const opt = document.createElement('option');
    opt.value = s.split(' — ')[0];
    opt.textContent = s;
    setSel.appendChild(opt);
  });

  applyCarouselFilters();

  document.getElementById('search').addEventListener('input', debounce(applyCarouselFilters, 150));
  document.getElementById('filter-set').addEventListener('change', applyCarouselFilters);
  document.getElementById('prev-card').addEventListener('click', () => step(-1));
  document.getElementById('next-card').addEventListener('click', () => step(1));
  document.addEventListener('keydown', (e) => {
    if (e.key === 'ArrowLeft') step(-1);
    if (e.key === 'ArrowRight') step(1);
  });
}

init();
