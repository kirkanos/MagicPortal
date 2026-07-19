/* Lightweight bilingual (de/en) support.
   German is the default and lives inline in the markup. English comes from
   data-en* attributes (static text) and the t(de, en) helper (JS-generated
   text). The choice is remembered in localStorage; default is German. */
const LANG = localStorage.getItem('lang') === 'en' ? 'en' : 'de';

function t(de, en) {
  return LANG === 'en' ? en : de;
}

function applyI18n() {
  if (LANG !== 'en') return; // German is the inline default – nothing to swap
  document.querySelectorAll('[data-en]').forEach((el) => { el.textContent = el.dataset.en; });
  document.querySelectorAll('[data-en-html]').forEach((el) => { el.innerHTML = el.dataset.enHtml; });
  document.querySelectorAll('[data-en-placeholder]').forEach((el) => { el.placeholder = el.dataset.enPlaceholder; });
  document.querySelectorAll('[data-en-title]').forEach((el) => { el.title = el.dataset.enTitle; });
  document.querySelectorAll('[data-en-aria]').forEach((el) => { el.setAttribute('aria-label', el.dataset.enAria); });
}

function setLang(lang) {
  localStorage.setItem('lang', lang === 'en' ? 'en' : 'de');
  location.reload();
}

function initLangSwitch() {
  document.documentElement.lang = LANG;
  applyI18n();
  const btn = document.getElementById('lang-toggle');
  if (btn) {
    // The button shows the language you switch TO.
    btn.textContent = LANG === 'en' ? 'DE' : 'EN';
    btn.title = LANG === 'en' ? 'Auf Deutsch umschalten' : 'Switch to English';
    btn.addEventListener('click', () => setLang(LANG === 'en' ? 'de' : 'en'));
  }
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', initLangSwitch);
} else {
  initLangSwitch();
}
