/* Applies the saved colour theme before the page paints (no flash). Loaded synchronously in <head>. */
(function () {
  var t = null;
  try { t = localStorage.getItem('sq_theme'); } catch (e) { /* storage may be blocked */ }
  if (t !== 'dark' && t !== 'light') {
    t = (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches) ? 'dark' : 'light';
  }
  document.documentElement.setAttribute('data-theme', t);
})();
