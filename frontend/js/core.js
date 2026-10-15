/* =====================================================================
   SmartQueue - shared front-end toolkit
   API client, safe HTML templates, formatting, icons, toasts, modals,
   forms, notifications bell, polling helpers.  No external libraries.
   ===================================================================== */
(function (global) {
  'use strict';

  var SQ = global.SQ = {};

  /* ------------------------------------------------------------------
     Safe HTML templating: SQ.html`<b>${userText}</b>` escapes every
     interpolated value unless it is itself the result of SQ.html / SQ.raw.
     ------------------------------------------------------------------ */
  function Raw(s) { this.s = s; }
  Raw.prototype.toString = function () { return this.s; };

  function esc(v) {
    return String(v).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function toHtml(v) {
    if (v === null || v === undefined || v === false || v === true) return '';
    if (v instanceof Raw) return v.s;
    if (Array.isArray(v)) { var o = ''; for (var i = 0; i < v.length; i++) o += toHtml(v[i]); return o; }
    return esc(v);
  }
  SQ.esc = esc;
  SQ.raw = function (s) { return new Raw(String(s)); };
  SQ.html = function (strings) {
    var out = strings[0];
    for (var i = 1; i < arguments.length; i++) out += toHtml(arguments[i]) + strings[i];
    return new Raw(out);
  };
  SQ.setHtml = function (el, content) {
    if (!el) return false;
    var s = content instanceof Raw ? content.s : String(content);
    if (el.__html === s) return false;
    el.__html = s;
    el.innerHTML = s;
    return true;
  };

  SQ.qs = function (sel, root) { return (root || document).querySelector(sel); };
  SQ.qsa = function (sel, root) { return Array.prototype.slice.call((root || document).querySelectorAll(sel)); };
  SQ.on = function (root, evt, selector, handler) {
    root.addEventListener(evt, function (e) {
      var t = e.target.closest ? e.target.closest(selector) : null;
      if (t && root.contains(t)) handler(e, t);
    });
  };
  SQ.debounce = function (fn, ms) {
    var t;
    return function () { var a = arguments, self = this; clearTimeout(t); t = setTimeout(function () { fn.apply(self, a); }, ms); };
  };
  SQ.sleep = function (ms) { return new Promise(function (r) { setTimeout(r, ms); }); };
  SQ.store = {
    get: function (k, d) { try { var v = localStorage.getItem(k); return v === null ? d : v; } catch (e) { return d; } },
    set: function (k, v) { try { localStorage.setItem(k, v); } catch (e) { /* ignore */ } },
    del: function (k) { try { localStorage.removeItem(k); } catch (e) { /* ignore */ } }
  };

  /* ------------------------------------------------------------------
     Icons (24x24 stroke icons)
     ------------------------------------------------------------------ */
  var ICONS = {
    ticket: '<path d="M2 9a3 3 0 0 1 0 6v2a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-2a3 3 0 0 1 0-6V7a2 2 0 0 0-2-2H4a2 2 0 0 0-2 2Z"/><path d="M13 5v2"/><path d="M13 17v2"/><path d="M13 11v2"/>',
    users: '<path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M22 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/>',
    user: '<path d="M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/>',
    'user-plus': '<path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><line x1="19" x2="19" y1="8" y2="14"/><line x1="22" x2="16" y1="11" y2="11"/>',
    wallet: '<path d="M19 7V4a1 1 0 0 0-1-1H5a2 2 0 0 0 0 4h15a1 1 0 0 1 1 1v4h-3a2 2 0 0 0 0 4h3a1 1 0 0 0 1-1v-2a1 1 0 0 0-1-1"/><path d="M3 5v14a2 2 0 0 0 2 2h15a1 1 0 0 0 1-1v-4"/>',
    help: '<circle cx="12" cy="12" r="10"/><path d="M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3"/><path d="M12 17h.01"/>',
    file: '<path d="M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z"/><path d="M14 2v4a2 2 0 0 0 2 2h4"/><path d="M10 9H8"/><path d="M16 13H8"/><path d="M16 17H8"/>',
    briefcase: '<rect width="20" height="14" x="2" y="7" rx="2" ry="2"/><path d="M16 21V5a2 2 0 0 0-2-2h-4a2 2 0 0 0-2 2v16"/>',
    heart: '<path d="M19 14c1.49-1.46 3-3.21 3-5.5A5.5 5.5 0 0 0 16.5 3c-1.76 0-3 .5-4.5 2-1.5-1.5-2.74-2-4.5-2A5.5 5.5 0 0 0 2 8.5c0 2.3 1.5 4.05 3 5.5l7 7Z"/>',
    shield: '<path d="M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z"/>',
    'shield-check': '<path d="M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z"/><path d="m9 12 2 2 4-4"/>',
    phone: '<path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z"/>',
    home: '<path d="m3 9 9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/><polyline points="9 22 9 12 15 12 15 22"/>',
    bell: '<path d="M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9"/><path d="M10.3 21a1.94 1.94 0 0 0 3.4 0"/>',
    check: '<path d="M20 6 9 17l-5-5"/>',
    'check-circle': '<path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/><path d="m9 11 3 3L22 4"/>',
    x: '<path d="M18 6 6 18"/><path d="m6 6 12 12"/>',
    'x-circle': '<circle cx="12" cy="12" r="10"/><path d="m15 9-6 6"/><path d="m9 9 6 6"/>',
    clock: '<circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/>',
    hourglass: '<path d="M5 22h14"/><path d="M5 2h14"/><path d="M17 22v-4.172a2 2 0 0 0-.586-1.414L12 12l-4.414 4.414A2 2 0 0 0 7 17.828V22"/><path d="M7 2v4.172a2 2 0 0 0 .586 1.414L12 12l4.414-4.414A2 2 0 0 0 17 6.172V2"/>',
    list: '<path d="M8 6h13"/><path d="M8 12h13"/><path d="M8 18h13"/><path d="M3 6h.01"/><path d="M3 12h.01"/><path d="M3 18h.01"/>',
    play: '<polygon points="6 3 20 12 6 21 6 3"/>',
    pause: '<rect x="14" y="4" width="4" height="16" rx="1"/><rect x="6" y="4" width="4" height="16" rx="1"/>',
    skip: '<polygon points="5 4 15 12 5 20 5 4"/><line x1="19" x2="19" y1="5" y2="19"/>',
    recall: '<path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"/><path d="M3 3v5h5"/>',
    refresh: '<path d="M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8"/><path d="M21 3v5h-5"/><path d="M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16"/><path d="M8 16H3v5"/>',
    history: '<path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"/><path d="M3 3v5h5"/><path d="M12 7v5l4 2"/>',
    megaphone: '<path d="m3 11 18-5v12L3 14v-3z"/><path d="M11.6 16.8a3 3 0 1 1-5.8-1.6"/>',
    logout: '<path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" x2="9" y1="12" y2="12"/>',
    login: '<path d="M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4"/><polyline points="10 17 15 12 10 7"/><line x1="15" x2="3" y1="12" y2="12"/>',
    settings: '<path d="M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z"/><circle cx="12" cy="12" r="3"/>',
    chart: '<path d="M3 3v18h18"/><path d="M18 17V9"/><path d="M13 17V5"/><path d="M8 17v-3"/>',
    dashboard: '<rect width="7" height="9" x="3" y="3" rx="1"/><rect width="7" height="5" x="14" y="3" rx="1"/><rect width="7" height="9" x="14" y="12" rx="1"/><rect width="7" height="5" x="3" y="16" rx="1"/>',
    grid: '<rect width="7" height="7" x="3" y="3" rx="1"/><rect width="7" height="7" x="14" y="3" rx="1"/><rect width="7" height="7" x="14" y="14" rx="1"/><rect width="7" height="7" x="3" y="14" rx="1"/>',
    counter: '<rect x="3" y="9" width="18" height="11" rx="2"/><path d="M3 13h18"/><path d="M7 5h10l1.5 4h-13z"/>',
    plus: '<path d="M5 12h14"/><path d="M12 5v14"/>',
    edit: '<path d="M17 3a2.85 2.83 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5Z"/><path d="m15 5 4 4"/>',
    trash: '<path d="M3 6h18"/><path d="M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6"/><path d="M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2"/><line x1="10" x2="10" y1="11" y2="17"/><line x1="14" x2="14" y1="11" y2="17"/>',
    search: '<circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/>',
    sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2"/><path d="M12 20v2"/><path d="m4.93 4.93 1.41 1.41"/><path d="m17.66 17.66 1.41 1.41"/><path d="M2 12h2"/><path d="M20 12h2"/><path d="m6.34 17.66-1.41 1.41"/><path d="m19.07 4.93-1.41 1.41"/>',
    moon: '<path d="M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z"/>',
    menu: '<line x1="4" x2="20" y1="12" y2="12"/><line x1="4" x2="20" y1="6" y2="6"/><line x1="4" x2="20" y1="18" y2="18"/>',
    alert: '<path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3"/><path d="M12 9v4"/><path d="M12 17h.01"/>',
    info: '<circle cx="12" cy="12" r="10"/><path d="M12 16v-4"/><path d="M12 8h.01"/>',
    star: '<polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2"/>',
    download: '<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" x2="12" y1="15" y2="3"/>',
    'arrow-up': '<path d="m5 12 7-7 7 7"/><path d="M12 19V5"/>',
    'arrow-right': '<path d="M5 12h14"/><path d="m12 5 7 7-7 7"/>',
    'arrow-left': '<path d="m12 19-7-7 7-7"/><path d="M19 12H5"/>',
    'to-front': '<path d="M5 3h14"/><path d="m18 13-6-6-6 6"/><path d="M12 7v14"/>',
    'to-back': '<path d="M19 21H5"/><path d="m6 11 6 6 6-6"/><path d="M12 17V3"/>',
    zap: '<path d="M4 14a1 1 0 0 1-.78-1.63l9.9-10.2a.5.5 0 0 1 .86.46l-1.92 6.02A1 1 0 0 0 13 10h7a1 1 0 0 1 .78 1.63l-9.9 10.2a.5.5 0 0 1-.86-.46l1.92-6.02A1 1 0 0 0 11 14z"/>',
    monitor: '<rect width="20" height="14" x="2" y="3" rx="2"/><line x1="8" x2="16" y1="21" y2="21"/><line x1="12" x2="12" y1="17" y2="21"/>',
    activity: '<path d="M22 12h-2.48a2 2 0 0 0-1.93 1.46l-2.35 8.36a.25.25 0 0 1-.48 0L9.24 2.18a.25.25 0 0 0-.48 0l-2.35 8.36A2 2 0 0 1 4.49 12H2"/>',
    trending: '<polyline points="22 7 13.5 15.5 8.5 10.5 2 17"/><polyline points="16 7 22 7 22 13"/>',
    calendar: '<path d="M8 2v4"/><path d="M16 2v4"/><rect width="18" height="18" x="3" y="4" rx="2"/><path d="M3 10h18"/>',
    eye: '<path d="M2 12s3-7 10-7 10 7 10 7-3 7-10 7-10-7-10-7Z"/><circle cx="12" cy="12" r="3"/>',
    'eye-off': '<path d="M9.88 9.88a3 3 0 1 0 4.24 4.24"/><path d="M10.73 5.08A10.43 10.43 0 0 1 12 5c7 0 10 7 10 7a13.16 13.16 0 0 1-1.67 2.68"/><path d="M6.61 6.61A13.526 13.526 0 0 0 2 12s3 7 10 7a9.74 9.74 0 0 0 5.39-1.61"/><line x1="2" x2="22" y1="2" y2="22"/>',
    lock: '<rect width="18" height="11" x="3" y="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/>',
    key: '<circle cx="7.5" cy="15.5" r="5.5"/><path d="m21 2-9.6 9.6"/><path d="m15.5 7.5 3 3L22 7l-3-3"/>',
    mail: '<rect width="20" height="16" x="2" y="4" rx="2"/><path d="m22 7-8.97 5.7a1.94 1.94 0 0 1-2.06 0L2 7"/>',
    'chevron-right': '<path d="m9 18 6-6-6-6"/>',
    'chevron-left': '<path d="m15 18-6-6 6-6"/>',
    'chevron-down': '<path d="m6 9 6 6 6-6"/>',
    coffee: '<path d="M10 2v2"/><path d="M14 2v2"/><path d="M16 8a1 1 0 0 1 1 1v8a4 4 0 0 1-4 4H7a4 4 0 0 1-4-4V9a1 1 0 0 1 1-1h14a4 4 0 1 1 0 8h-1"/><path d="M6 2v2"/>',
    pin: '<path d="M20 10c0 6-8 12-8 12s-8-6-8-12a8 8 0 0 1 16 0Z"/><circle cx="12" cy="10" r="3"/>',
    filter: '<polygon points="22 3 2 3 10 12.46 10 19 14 21 14 12.46 22 3"/>',
    copy: '<rect width="14" height="14" x="8" y="8" rx="2" ry="2"/><path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"/>',
    more: '<circle cx="12" cy="12" r="1"/><circle cx="12" cy="5" r="1"/><circle cx="12" cy="19" r="1"/>',
    bulb: '<path d="M15 14c.2-1 .7-1.7 1.5-2.5 1-.9 1.5-2.2 1.5-3.5A6 6 0 0 0 6 8c0 1 .2 2.2 1.5 3.5.7.7 1.3 1.5 1.5 2.5"/><path d="M9 18h6"/><path d="M10 22h4"/>',
    volume: '<polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"/><path d="M15.54 8.46a5 5 0 0 1 0 7.07"/><path d="M19.07 4.93a10 10 0 0 1 0 14.14"/>',
    'volume-x': '<polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"/><line x1="22" x2="16" y1="9" y2="15"/><line x1="16" x2="22" y1="9" y2="15"/>',
    maximize: '<path d="M8 3H5a2 2 0 0 0-2 2v3"/><path d="M21 8V5a2 2 0 0 0-2-2h-3"/><path d="M3 16v3a2 2 0 0 0 2 2h3"/><path d="M16 21h3a2 2 0 0 0 2-2v-3"/>',
    clipboard: '<rect width="8" height="4" x="8" y="2" rx="1" ry="1"/><path d="M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2"/><path d="M12 11h4"/><path d="M12 16h4"/><path d="M8 11h.01"/><path d="M8 16h.01"/>',
    walk: '<circle cx="13" cy="4" r="2"/><path d="m4 17 5-1 2-4"/><path d="M10 13.5 8.5 20"/><path d="m14 9-3 3.5 3.5 2v6"/><path d="m16 14 4-1"/>',
    wifi: '<path d="M5 12.55a11 11 0 0 1 14.08 0"/><path d="M1.42 9a16 16 0 0 1 21.16 0"/><path d="M8.53 16.11a6 6 0 0 1 6.95 0"/><path d="M12 20h.01"/>',
    inbox: '<polyline points="22 12 16 12 14 15 10 15 8 12 2 12"/><path d="M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z"/>',
    target: '<circle cx="12" cy="12" r="10"/><circle cx="12" cy="12" r="6"/><circle cx="12" cy="12" r="2"/>',
    percent: '<line x1="19" x2="5" y1="5" y2="19"/><circle cx="6.5" cy="6.5" r="2.5"/><circle cx="17.5" cy="17.5" r="2.5"/>',
    layers: '<path d="m12.83 2.18a2 2 0 0 0-1.66 0L2.6 6.08a1 1 0 0 0 0 1.83l8.58 3.91a2 2 0 0 0 1.66 0l8.58-3.9a1 1 0 0 0 0-1.83Z"/><path d="m22 17.65-9.17 4.16a2 2 0 0 1-1.66 0L2 17.65"/><path d="m22 12.65-9.17 4.16a2 2 0 0 1-1.66 0L2 12.65"/>'
  };
  SQ.ICONS = ICONS;
  SQ.icon = function (name, size, cls) {
    var body = ICONS[name] || ICONS.help;
    var s = size || 18;
    return new Raw('<svg xmlns="http://www.w3.org/2000/svg" width="' + s + '" height="' + s + '" viewBox="0 0 24 24" fill="none" stroke="currentColor" ' +
      'stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"' + (cls ? ' class="' + cls + '"' : '') + '>' + body + '</svg>');
  };
  SQ.SERVICE_ICONS = ['users', 'wallet', 'help', 'file', 'ticket', 'briefcase', 'heart', 'shield', 'phone', 'home'];
  SQ.logoMark = function (size) {
    var s = size || 20;
    return new Raw('<svg width="' + s + '" height="' + s + '" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M4 7h10M4 12h16M4 17h7" stroke="#fff" stroke-width="2.4" stroke-linecap="round"/><circle cx="19" cy="7" r="2" fill="#fff"/></svg>');
  };
  SQ.brand = function (org, href) {
    return SQ.html`<a class="brand" href="${href || '/index.html'}"><span class="brand-mark">${SQ.logoMark(20)}</span><span>SmartQueue<small>${org || ''}</small></span></a>`;
  };

  /* ------------------------------------------------------------------
     Formatting
     ------------------------------------------------------------------ */
  var fmt = SQ.fmt = {};
  SQ.skew = 0; // server clock minus browser clock (ms)
  SQ.now = function () { return Date.now() + SQ.skew; };
  function toDate(v) { if (!v) return null; var d = v instanceof Date ? v : new Date(v); return isNaN(d.getTime()) ? null : d; }
  fmt.time = function (v) {
    var d = toDate(v); if (!d) return '—';
    return d.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
  };
  fmt.timeSec = function (v) {
    var d = toDate(v); if (!d) return '—';
    return d.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit', second: '2-digit' });
  };
  fmt.date = function (v) {
    var d = toDate(v); if (!d) return '—';
    return d.toLocaleDateString([], { day: 'numeric', month: 'short', year: 'numeric' });
  };
  fmt.dateShort = function (v) {
    var d = toDate(v); if (!d) return '—';
    return d.toLocaleDateString([], { day: 'numeric', month: 'short' });
  };
  fmt.dateTime = function (v) {
    var d = toDate(v); if (!d) return '—';
    return d.toLocaleDateString([], { day: 'numeric', month: 'short' }) + ', ' + fmt.time(d);
  };
  fmt.ago = function (v) {
    var d = toDate(v); if (!d) return '';
    var s = Math.round((SQ.now() - d.getTime()) / 1000);
    if (s < 5) return 'just now';
    if (s < 60) return s + ' s ago';
    var m = Math.round(s / 60);
    if (m < 60) return m + ' min ago';
    var h = Math.round(m / 60);
    if (h < 24) return h + ' h ago';
    return fmt.dateShort(d);
  };
  fmt.minutes = function (n, opts) {
    if (n === null || n === undefined || isNaN(n)) return '—';
    n = Number(n);
    if (n < 0.5) return opts && opts.zero ? opts.zero : '< 1 min';
    if (n < 60) return Math.round(n) + ' min';
    var h = Math.floor(n / 60), m = Math.round(n - h * 60);
    return h + ' h' + (m ? ' ' + m + ' min' : '');
  };
  fmt.dur = function (seconds) {
    seconds = Math.max(0, Math.floor(seconds || 0));
    var h = Math.floor(seconds / 3600), m = Math.floor((seconds % 3600) / 60), s = seconds % 60;
    var mm = (h ? String(m).padStart(2, '0') : String(m)), ss = String(s).padStart(2, '0');
    return h ? h + ':' + mm + ':' + ss : mm + ':' + ss;
  };
  fmt.num = function (n) { return (n === null || n === undefined || isNaN(n)) ? '—' : Number(n).toLocaleString(); };
  fmt.dec = function (n, d) { return (n === null || n === undefined || isNaN(n)) ? '—' : Number(n).toFixed(d === undefined ? 1 : d); };
  fmt.pct = function (n, d) { return (n === null || n === undefined || isNaN(n)) ? '—' : Number(n).toFixed(d === undefined ? 0 : d) + '%'; };
  fmt.plural = function (n, one, many) { return n + ' ' + (n === 1 ? one : (many || one + 's')); };
  SQ.initials = function (name) {
    var p = String(name || '?').trim().split(/\s+/);
    return ((p[0] || '?')[0] + (p.length > 1 ? p[p.length - 1][0] : '')).toUpperCase();
  };
  SQ.tint = function (hex, a) {
    var m = /^#?([0-9a-f]{6})$/i.exec(hex || '');
    if (!m) return 'rgba(79,70,229,' + a + ')';
    var n = parseInt(m[1], 16);
    return 'rgba(' + (n >> 16) + ',' + ((n >> 8) & 255) + ',' + (n & 255) + ',' + a + ')';
  };
  SQ.safeColor = function (hex) { return /^#[0-9a-f]{6}$/i.test(hex || '') ? hex : '#4f46e5'; };
  SQ.todayIso = function () {
    var d = new Date(SQ.now());
    return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
  };

  /* ------------------------------------------------------------------
     Domain look-ups (status, priority, crowd)
     ------------------------------------------------------------------ */
  SQ.STATUS = {
    WAITING: { label: 'Waiting', tone: 'amber', icon: 'hourglass' },
    CALLED: { label: 'Called', tone: 'green', icon: 'megaphone' },
    SERVING: { label: 'Being served', tone: 'purple', icon: 'play' },
    ON_HOLD: { label: 'On hold', tone: 'orange', icon: 'pause' },
    COMPLETED: { label: 'Completed', tone: 'green', icon: 'check-circle' },
    SKIPPED: { label: 'Skipped', tone: 'red', icon: 'skip' },
    CANCELLED: { label: 'Cancelled', tone: 'gray', icon: 'x-circle' }
  };
  SQ.CROWD = { LOW: 'Low', MODERATE: 'Moderate', HIGH: 'High', CRITICAL: 'Critical' };
  SQ.statusBadge = function (status, opts) {
    var s = SQ.STATUS[status] || { label: status, tone: 'gray' };
    var live = status === 'CALLED' || status === 'SERVING';
    return SQ.html`<span class="badge tone-${s.tone} dot ${live ? 'pulse' : ''} ${opts && opts.lg ? 'badge-lg' : ''}">${(opts && opts.label) || s.label}</span>`;
  };
  SQ.priorityBadge = function (p, reason) {
    if (p === 2) return SQ.html`<span class="badge tone-red" title="${reason || 'Emergency'}">${SQ.icon('zap', 12)} Emergency</span>`;
    if (p === 1) return SQ.html`<span class="badge tone-blue" title="${reason || 'Priority'}">${SQ.icon('arrow-up', 12)} Priority</span>`;
    return SQ.raw('');
  };
  SQ.sourceBadge = function (src) {
    return src === 'WALK_IN' ? SQ.html`<span class="badge tone-teal">${SQ.icon('walk', 12)} Walk-in</span>` : SQ.raw('');
  };
  SQ.crowdPill = function (level, extra) {
    var l = level || 'LOW';
    return SQ.html`<span class="crowd-pill crowd-${l}"><i></i>${SQ.CROWD[l] || l}${extra ? SQ.html` · ${extra}` : ''}</span>`;
  };
  SQ.serviceIcon = function (svc, size) {
    var c = SQ.safeColor(svc && svc.color);
    var cls = size === 'lg' ? 'lg' : (size === 'sm' ? 'sm' : '');
    var px = size === 'lg' ? 28 : (size === 'sm' ? 17 : 22);
    return SQ.html`<span class="svc-ico ${cls}" style="background:${SQ.tint(c, 0.14)};color:${c}">${SQ.icon((svc && svc.icon) || 'ticket', px)}</span>`;
  };
  SQ.tokenChip = function (code, color) {
    var c = SQ.safeColor(color);
    return SQ.html`<span class="tok-chip" style="background:${SQ.tint(c, 0.14)};color:${c}">${code}</span>`;
  };
  SQ.stars = function (n, size) {
    var out = '';
    var v = Math.round(Number(n) || 0);
    for (var i = 1; i <= 5; i++) out += '<span class="' + (i <= v ? '' : 'off') + '">' + SQ.icon('star', size || 14).s.replace('fill="none"', i <= v ? 'fill="currentColor"' : 'fill="none"') + '</span>';
    return SQ.raw('<span class="stars" title="' + v + ' / 5">' + out + '</span>');
  };
  SQ.empty = function (icon, title, text) {
    return SQ.html`<div class="empty"><div class="e-ico">${SQ.icon(icon || 'inbox', 28)}</div><h3>${title}</h3>${text ? SQ.html`<p>${text}</p>` : ''}</div>`;
  };
  SQ.pager = function (p, attr) {
    attr = attr || 'data-page';
    if (!p || p.pages <= 1) return SQ.html`<div class="pager"><span class="muted small">${fmt.plural(p ? p.total : 0, 'result')}</span></div>`;
    return SQ.html`<div class="pager"><span class="muted small">Page ${p.page} of ${p.pages} · ${fmt.plural(p.total, 'result')}</span>
      <div class="row gap-sm">
        <button class="btn btn-sm" ${SQ.raw(attr)}="${p.page - 1}" ${p.page <= 1 ? SQ.raw('disabled') : ''}>${SQ.icon('chevron-left', 15)} Prev</button>
        <button class="btn btn-sm" ${SQ.raw(attr)}="${p.page + 1}" ${p.page >= p.pages ? SQ.raw('disabled') : ''}>Next ${SQ.icon('chevron-right', 15)}</button>
      </div></div>`;
  };

  /* ------------------------------------------------------------------
     API client
     ------------------------------------------------------------------ */
  function ApiError(status, message, data) {
    this.name = 'ApiError';
    this.status = status;
    this.message = message;
    this.data = data;
  }
  ApiError.prototype = Object.create(Error.prototype);
  SQ.ApiError = ApiError;

  var online = true;
  function setOnline(v) {
    if (online === v) return;
    online = v;
    SQ.qsa('[data-conn]').forEach(function (el) {
      el.classList.toggle('off', !v);
      var t = el.querySelector('span');
      if (t) t.textContent = v ? 'Live' : 'Reconnecting…';
    });
  }
  SQ.isOnline = function () { return online; };

  SQ.api = function (method, path, body, opts) {
    opts = opts || {};
    var headers = { 'Accept': 'application/json', 'X-Requested-With': 'SmartQueue' };
    var init = { method: method, headers: headers, credentials: 'same-origin', cache: 'no-store' };
    if (body !== undefined && body !== null) {
      headers['Content-Type'] = 'application/json';
      init.body = JSON.stringify(body);
    }
    return fetch('/api/' + path.replace(/^\//, ''), init).then(function (res) {
      setOnline(true);
      var d = res.headers.get('Date');
      if (d) { var t = Date.parse(d); if (!isNaN(t)) SQ.skew = t - Date.now(); }
      return res.text().then(function (txt) {
        var data = null;
        if (txt) { try { data = JSON.parse(txt); } catch (e) { data = null; } }
        if (!res.ok) {
          var msg = (data && data.error) || (res.status === 429 ? 'Too many attempts. Please wait a moment.' : 'Something went wrong (' + res.status + ').');
          if (res.status === 401 && !opts.noRedirect && SQ.onUnauthorized) SQ.onUnauthorized();
          throw new ApiError(res.status, msg, data);
        }
        return data;
      });
    }, function () {
      setOnline(false);
      throw new ApiError(0, 'Cannot reach the server. Check your connection.', null);
    });
  };
  SQ.get = function (p, o) { return SQ.api('GET', p, undefined, o); };
  SQ.post = function (p, b, o) { return SQ.api('POST', p, b === undefined ? {} : b, o); };
  SQ.put = function (p, b, o) { return SQ.api('PUT', p, b === undefined ? {} : b, o); };
  SQ.del = function (p, o) { return SQ.api('DELETE', p, undefined, o); };
  SQ.qstr = function (obj) {
    var parts = [];
    Object.keys(obj).forEach(function (k) {
      var v = obj[k];
      if (v !== undefined && v !== null && v !== '') parts.push(encodeURIComponent(k) + '=' + encodeURIComponent(v));
    });
    return parts.length ? '?' + parts.join('&') : '';
  };

  SQ.onUnauthorized = function () {
    if (SQ.__redirecting) return;
    SQ.__redirecting = true;
    location.href = '/index.html?expired=1';
  };

  SQ.loadConfig = function () {
    if (!SQ._config) SQ._config = SQ.get('public/config', { noRedirect: true }).catch(function () { SQ._config = null; return { orgName: 'SmartQueue', noShowMinutes: 3, approachThreshold: 2 }; });
    return SQ._config;
  };

  /** Make sure the visitor is signed in with one of the allowed roles; otherwise go to the right page. */
  SQ.requireRole = function (roles) {
    return SQ.get('auth/me', { noRedirect: true }).then(function (me) {
      if (roles.indexOf(me.user.role) < 0) { location.replace('/' + me.home); return new Promise(function () { }); }
      SQ.me = me;
      return me;
    }, function (e) {
      if (e.status === 401) { location.replace('/index.html' + (location.pathname.indexOf('index') < 0 ? '?expired=1' : '')); return new Promise(function () { }); }
      throw e;
    });
  };
  SQ.logout = function () {
    return SQ.post('auth/logout', {}, { noRedirect: true }).catch(function () { }).then(function () { location.href = '/index.html?bye=1'; });
  };

  /* ------------------------------------------------------------------
     Button helpers
     ------------------------------------------------------------------ */
  SQ.busy = function (btn, fn) {
    if (!btn || btn.__busy) return Promise.resolve();
    btn.__busy = true;
    btn.classList.add('is-loading');
    btn.disabled = true;
    return Promise.resolve().then(fn).catch(function (e) { SQ.err(e); }).then(function (r) {
      btn.__busy = false;
      btn.classList.remove('is-loading');
      if (document.body.contains(btn)) btn.disabled = false;
      return r;
    });
  };

  /* ------------------------------------------------------------------
     Toasts
     ------------------------------------------------------------------ */
  var toastBox = null;
  SQ.toast = function (message, opts) {
    opts = opts || {};
    if (!toastBox) {
      toastBox = document.createElement('div');
      toastBox.className = 'toasts';
      toastBox.setAttribute('aria-live', 'polite');
      toastBox.setAttribute('role', 'status');
      document.body.appendChild(toastBox);
    }
    var type = opts.type || 'info';
    var tone = { success: 'green', error: 'red', warning: 'amber', info: 'blue', call: 'green' }[type] || 'blue';
    var icon = { success: 'check-circle', error: 'x-circle', warning: 'alert', info: 'info', call: 'megaphone' }[type] || 'info';
    var el = document.createElement('div');
    el.className = 'toast tone-' + tone;
    el.innerHTML = SQ.html`<span class="t-ico">${SQ.icon(opts.icon || icon, 20)}</span><div class="grow">${opts.title ? SQ.html`<div class="t-title">${opts.title}</div>` : ''}<div class="t-msg">${message}</div></div>`.s;
    toastBox.appendChild(el);
    while (toastBox.children.length > 4) toastBox.removeChild(toastBox.firstChild);
    var ms = opts.duration || (type === 'error' ? 6500 : 4200);
    var kill = function () { el.classList.add('leaving'); setTimeout(function () { if (el.parentNode) el.parentNode.removeChild(el); }, 220); };
    el.addEventListener('click', kill);
    setTimeout(kill, ms);
    return el;
  };
  SQ.ok = function (m, o) { return SQ.toast(m, Object.assign({ type: 'success' }, o || {})); };
  SQ.warn = function (m, o) { return SQ.toast(m, Object.assign({ type: 'warning' }, o || {})); };
  SQ.err = function (e) {
    if (e && e.status === 401) return null;
    return SQ.toast((e && e.message) || String(e), { type: 'error' });
  };

  /* ------------------------------------------------------------------
     Modals
     ------------------------------------------------------------------ */
  var modalStack = [];
  SQ.modal = function (o) {
    var back = document.createElement('div');
    back.className = 'modal-backdrop';
    var dialog = document.createElement('div');
    dialog.className = 'modal ' + (o.size || '');
    dialog.setAttribute('role', 'dialog');
    dialog.setAttribute('aria-modal', 'true');
    var titleId = 'm' + Math.random().toString(36).slice(2, 8);
    dialog.setAttribute('aria-labelledby', titleId);
    var ctl = { el: dialog, closed: false };
    var acts = o.actions || [];
    dialog.innerHTML = SQ.html`
      <div class="modal-head"><div>${o.icon ? SQ.html`<div class="icon-hero tone-${o.tone || 'blue'}">${SQ.icon(o.icon, 26)}</div>` : ''}<h3 id="${titleId}">${o.title}</h3></div>
        <button class="modal-x" type="button" data-modal-close aria-label="Close">${SQ.icon('x', 18)}</button></div>
      <div class="modal-body"></div>
      ${acts.length ? SQ.html`<div class="modal-foot">${acts.map(function (a, i) {
        return SQ.html`<button type="button" class="btn ${a.kind ? 'btn-' + a.kind : ''}" data-modal-act="${i}">${a.icon ? SQ.icon(a.icon, 16) : ''}${a.label}</button>`;
      })}</div>` : ''}`.s;
    var body = dialog.querySelector('.modal-body');
    if (o.body instanceof Raw) body.innerHTML = o.body.s;
    else if (typeof o.body === 'string') body.textContent = o.body;
    else if (o.body && o.body.nodeType) body.appendChild(o.body);
    ctl.body = body;
    back.appendChild(dialog);
    var prevFocus = document.activeElement;
    ctl.close = function (result) {
      if (ctl.closed) return;
      ctl.closed = true;
      var i = modalStack.indexOf(ctl); if (i >= 0) modalStack.splice(i, 1);
      if (back.parentNode) back.parentNode.removeChild(back);
      if (!modalStack.length) document.body.style.overflow = '';
      if (prevFocus && prevFocus.focus && document.body.contains(prevFocus)) { try { prevFocus.focus(); } catch (e) { } }
      if (o.onClose) o.onClose(result);
    };
    ctl.setBusy = function (b) { SQ.qsa('[data-modal-act]', dialog).forEach(function (x) { x.disabled = b; }); };
    back.addEventListener('mousedown', function (e) { if (e.target === back && o.dismissible !== false) ctl.close(); });
    dialog.addEventListener('click', function (e) {
      if (e.target.closest('[data-modal-close]')) { ctl.close(); return; }
      var b = e.target.closest('[data-modal-act]');
      if (!b) return;
      var a = acts[Number(b.getAttribute('data-modal-act'))];
      if (!a) return;
      if (!a.onClick) { ctl.close(a.value); return; }
      SQ.busy(b, function () { return Promise.resolve(a.onClick(ctl, b)); }).then(function (r) { if (r !== false && !ctl.closed && a.closeAfter !== false) ctl.close(a.value); });
    });
    document.body.appendChild(back);
    document.body.style.overflow = 'hidden';
    modalStack.push(ctl);
    var first = dialog.querySelector('[autofocus],input:not([type=hidden]):not([disabled]),select,textarea') || dialog.querySelector('.btn-primary,.btn-danger,.btn');
    if (first) setTimeout(function () { try { first.focus(); } catch (e) { } }, 30);
    if (o.onOpen) o.onOpen(ctl);
    return ctl;
  };
  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape' && modalStack.length) { var m = modalStack[modalStack.length - 1]; m.close(); }
    if (e.key === 'Tab' && modalStack.length) {
      var d = modalStack[modalStack.length - 1].el;
      var f = SQ.qsa('a[href],button:not([disabled]),input:not([disabled]):not([type=hidden]),select:not([disabled]),textarea:not([disabled])', d).filter(function (x) { return x.offsetParent !== null; });
      if (!f.length) return;
      var first = f[0], last = f[f.length - 1];
      if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
      else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
    }
  });
  SQ.modalOpen = function () { return modalStack.length > 0; };

  SQ.confirm = function (o) {
    return new Promise(function (resolve) {
      var tone = o.tone || 'blue';
      SQ.modal({
        title: o.title || 'Are you sure?', icon: o.icon || (tone === 'red' ? 'alert' : 'help'), tone: tone, size: 'sm',
        body: o.message instanceof Raw ? o.message : SQ.html`<p class="text-2">${o.message || ''}</p>`,
        actions: [
          { label: o.cancelLabel || 'Cancel', value: false },
          { label: o.confirmLabel || 'Confirm', kind: tone === 'red' ? 'danger' : 'primary', value: true }
        ],
        onClose: function (r) { resolve(r === true); }
      });
    });
  };

  /* Generic form dialog.  fields: [{name,label,type,required,options,hint,full,...}] */
  SQ.form = function (o) {
    return new Promise(function (resolve) {
      var vals = Object.assign({}, o.initial || {});
      var fields = o.fields || [];
      var id = 'f' + Math.random().toString(36).slice(2, 7);
      function fieldHtml(f) {
        var v = vals[f.name]; if (v === undefined || v === null) v = f.value !== undefined ? f.value : '';
        var fid = id + '_' + f.name;
        var req = f.required ? SQ.raw(' <span class="req">*</span>') : '';
        var common = SQ.html`id="${fid}" name="${f.name}" ${f.required ? SQ.raw('required') : ''} ${f.disabled ? SQ.raw('disabled') : ''} ${f.placeholder ? SQ.html`placeholder="${f.placeholder}"` : ''} ${f.maxlength ? SQ.html`maxlength="${f.maxlength}"` : ''} ${f.autocomplete ? SQ.html`autocomplete="${f.autocomplete}"` : SQ.raw('autocomplete="off"')}`;
        var input;
        switch (f.type) {
          case 'textarea':
            input = SQ.html`<textarea class="textarea" ${common} rows="${f.rows || 3}">${v}</textarea>`; break;
          case 'select':
            input = SQ.html`<select class="select" ${common}>${(f.options || []).map(function (op) { return SQ.html`<option value="${op.value}" ${String(op.value) === String(v) ? SQ.raw('selected') : ''}>${op.label}</option>`; })}</select>`; break;
          case 'switch':
            return SQ.html`<div class="field ${f.full ? 'full' : ''}" data-field="${f.name}"><label class="switch"><input type="checkbox" id="${fid}" name="${f.name}" ${v ? SQ.raw('checked') : ''}><span class="track"></span><span class="bold">${f.label}</span></label>${f.hint ? SQ.html`<div class="hint">${f.hint}</div>` : ''}</div>`;
          case 'color':
            input = SQ.html`<input class="input" type="color" ${common} value="${v || '#4f46e5'}" style="padding:4px;height:42px">`; break;
          case 'icons':
            input = SQ.html`<div class="row wrap" data-icon-pick="${f.name}">${SQ.SERVICE_ICONS.map(function (ic) {
              return SQ.html`<button type="button" class="icon-btn ${ic === v ? 'active-ico' : ''}" data-ico="${ic}" title="${ic}" style="${ic === v ? 'border-color:var(--primary);color:var(--primary);background:var(--primary-soft)' : ''}">${SQ.icon(ic, 18)}</button>`;
            })}<input type="hidden" name="${f.name}" value="${v || SQ.SERVICE_ICONS[0]}"></div>`; break;
          case 'number':
            input = SQ.html`<input class="input" type="number" ${common} value="${v}" ${f.min !== undefined ? SQ.html`min="${f.min}"` : ''} ${f.max !== undefined ? SQ.html`max="${f.max}"` : ''} ${f.step !== undefined ? SQ.html`step="${f.step}"` : ''}>`; break;
          case 'password':
            input = SQ.html`<div class="input-icon has-reveal">${SQ.icon('lock', 17)}<input class="input" type="password" ${common} value="${v}"><button type="button" class="reveal" data-reveal tabindex="-1" aria-label="Show password">${SQ.icon('eye', 17)}</button></div>`; break;
          default:
            input = SQ.html`<input class="input" type="${f.type || 'text'}" ${common} value="${v}" ${f.pattern ? SQ.html`pattern="${f.pattern}"` : ''}>`;
        }
        return SQ.html`<div class="field ${f.full ? 'full' : ''}" data-field="${f.name}"><label for="${fid}">${f.label}${req}</label>${input}${f.hint ? SQ.html`<div class="hint">${f.hint}</div>` : ''}</div>`;
      }
      var form = document.createElement('form');
      form.noValidate = false;
      form.setAttribute('autocomplete', 'off');
      form.innerHTML = SQ.html`${o.intro ? SQ.html`<p class="text-2 mb">${o.intro}</p>` : ''}<div class="form-grid">${fields.map(fieldHtml)}</div><div class="mt" data-form-error></div><button type="submit" hidden></button>`.s;
      function applyVisibility() {
        var cur = read();
        fields.forEach(function (f) {
          if (!f.show) return;
          var el = form.querySelector('[data-field="' + f.name + '"]');
          if (el) el.hidden = !f.show(cur);
          var inp = el && el.querySelector('input,select,textarea');
          if (inp && f.required) inp.required = f.show(cur);
        });
      }
      function read() {
        var out = {};
        fields.forEach(function (f) {
          var el = form.elements[f.name];
          if (!el) return;
          if (f.type === 'switch') out[f.name] = !!el.checked;
          else if (f.type === 'number') out[f.name] = el.value === '' ? null : Number(el.value);
          else out[f.name] = el.value;
        });
        return out;
      }
      form.addEventListener('input', applyVisibility);
      form.addEventListener('change', applyVisibility);
      form.addEventListener('click', function (e) {
        var rv = e.target.closest('[data-reveal]');
        if (rv) { var inp = rv.parentNode.querySelector('input'); var show = inp.type === 'password'; inp.type = show ? 'text' : 'password'; rv.innerHTML = SQ.icon(show ? 'eye-off' : 'eye', 17).s; return; }
        var ic = e.target.closest('[data-ico]');
        if (ic) {
          var wrap = ic.closest('[data-icon-pick]');
          wrap.querySelector('input[type=hidden]').value = ic.getAttribute('data-ico');
          SQ.qsa('[data-ico]', wrap).forEach(function (b) { var on = b === ic; b.style.cssText = on ? 'border-color:var(--primary);color:var(--primary);background:var(--primary-soft)' : ''; });
        }
      });
      var ctl;
      function submit() {
        if (!form.reportValidity()) return Promise.resolve(false);
        var errBox = form.querySelector('[data-form-error]');
        errBox.innerHTML = '';
        return Promise.resolve().then(function () { return o.onSubmit(read(), ctl); }).then(function (res) {
          resolve(res === undefined ? true : res); ctl.close(true); return true;
        }, function (e) {
          errBox.innerHTML = SQ.html`<div class="alert tone-red">${SQ.icon('alert', 18)}<div class="a-body">${(e && e.message) || 'Something went wrong.'}</div></div>`.s;
          return false;
        });
      }
      form.addEventListener('submit', function (e) { e.preventDefault(); var b = ctl.el.querySelector('[data-modal-act="1"]'); if (b) b.click(); });
      ctl = SQ.modal({
        title: o.title, size: o.size, icon: o.icon, tone: o.tone, body: form,
        actions: [
          { label: o.cancelLabel || 'Cancel', value: null },
          { label: o.submitLabel || 'Save', kind: o.kind || 'primary', closeAfter: false, onClick: function () { return submit(); } }
        ],
        onClose: function (r) { if (r === null || r === undefined) resolve(null); }
      });
      applyVisibility();
    });
  };

  SQ.copy = function (text) {
    var done = function () { SQ.ok('Copied to clipboard'); };
    if (navigator.clipboard && navigator.clipboard.writeText) { navigator.clipboard.writeText(text).then(done, function () { SQ.warn('Could not copy automatically.'); }); return; }
    var ta = document.createElement('textarea'); ta.value = text; document.body.appendChild(ta); ta.select();
    try { document.execCommand('copy'); done(); } catch (e) { SQ.warn('Could not copy automatically.'); }
    document.body.removeChild(ta);
  };

  /* ------------------------------------------------------------------
     Theme, dropdowns, global click handling
     ------------------------------------------------------------------ */
  SQ.theme = {
    get: function () { return document.documentElement.getAttribute('data-theme') || 'light'; },
    set: function (t) { document.documentElement.setAttribute('data-theme', t); SQ.store.set('sq_theme', t); SQ.qsa('[data-theme-icon]').forEach(function (e) { e.innerHTML = SQ.icon(t === 'dark' ? 'sun' : 'moon', 18).s; }); document.dispatchEvent(new CustomEvent('sq-theme', { detail: t })); },
    toggle: function () { SQ.theme.set(SQ.theme.get() === 'dark' ? 'light' : 'dark'); }
  };
  SQ.themeButton = function () {
    return SQ.html`<button class="icon-btn" type="button" data-action="theme" title="Switch light / dark theme" aria-label="Switch theme"><span data-theme-icon>${SQ.icon(SQ.theme.get() === 'dark' ? 'sun' : 'moon', 18)}</span></button>`;
  };
  SQ.connBadge = function () {
    return SQ.html`<span class="conn hide-sm" data-conn title="Live connection to the server"><i></i><span>Live</span></span>`;
  };
  SQ.userMenu = function (user, extraItems) {
    return SQ.html`<div class="dropdown">
      <button class="row" type="button" data-dd-toggle style="background:none;border:0;cursor:pointer;padding:2px 4px;border-radius:12px" aria-label="Account menu">
        <span class="avatar">${SQ.initials(user.fullName)}</span>
        <span class="hide-sm" style="text-align:left;line-height:1.2"><span class="bold small" style="display:block">${user.fullName}</span><span class="tiny muted">${({ ADMIN: 'Administrator', STAFF: 'Counter staff', USER: 'Customer' })[user.role] || user.role}</span></span>
        ${SQ.icon('chevron-down', 16)}
      </button>
      <div class="popover" hidden>
        <div class="menu-head"><div class="bold">${user.fullName}</div><div class="tiny muted">${user.email}</div></div>
        <div class="menu-sep"></div>
        <div class="menu">${extraItems || ''}
          <button class="menu-item" type="button" data-action="theme">${SQ.icon('moon', 17)} Switch theme</button>
          <button class="menu-item danger" type="button" data-action="logout">${SQ.icon('logout', 17)} Sign out</button>
        </div>
      </div></div>`;
  };

  document.addEventListener('click', function (e) {
    var tgl = e.target.closest('[data-dd-toggle]');
    SQ.qsa('.dropdown.open').forEach(function (d) {
      var inside = tgl && d.contains(tgl);
      var keep = !tgl && d.contains(e.target) && e.target.closest('.popover') && !e.target.closest('.menu-item');
      if (!inside && !keep) { d.classList.remove('open'); var p = d.querySelector('.popover'); if (p) p.hidden = true; }
    });
    if (tgl) {
      var dd = tgl.closest('.dropdown');
      var open = !dd.classList.contains('open');
      dd.classList.toggle('open', open);
      var pop = dd.querySelector('.popover'); if (pop) pop.hidden = !open;
      return;
    }
    var act = e.target.closest('[data-action]');
    if (!act) return;
    var a = act.getAttribute('data-action');
    if (a === 'theme') SQ.theme.toggle();
    else if (a === 'logout') SQ.logout();
  });
  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape') SQ.qsa('.dropdown.open').forEach(function (d) { d.classList.remove('open'); var p = d.querySelector('.popover'); if (p) p.hidden = true; });
  });

  /* ------------------------------------------------------------------
     Polling + live tickers
     ------------------------------------------------------------------ */
  SQ.every = function (fn, ms) {
    var timer = null, running = false, stopped = false;
    function schedule() { clearTimeout(timer); if (!stopped) timer = setTimeout(tick, ms); }
    function tick() {
      if (stopped || running) return;
      if (document.hidden) { schedule(); return; }
      running = true;
      var p; try { p = Promise.resolve(fn()); } catch (e) { p = Promise.reject(e); }
      p.catch(function () { }).then(function () { running = false; schedule(); });
    }
    function vis() { if (!document.hidden && !stopped) { clearTimeout(timer); tick(); } }
    document.addEventListener('visibilitychange', vis);
    tick();
    return {
      now: function () { clearTimeout(timer); tick(); },
      stop: function () { stopped = true; clearTimeout(timer); document.removeEventListener('visibilitychange', vis); }
    };
  };

  function runTickers() {
    var now = SQ.now();
    SQ.qsa('[data-elapsed]').forEach(function (el) {
      var t = Date.parse(el.getAttribute('data-elapsed'));
      if (!isNaN(t)) el.textContent = fmt.dur((now - t) / 1000);
    });
    SQ.qsa('[data-countdown]').forEach(function (el) {
      var t = Date.parse(el.getAttribute('data-countdown'));
      if (isNaN(t)) return;
      var left = Math.max(0, Math.round((t - now) / 1000));
      el.textContent = fmt.dur(left);
      el.classList.toggle('overdue', left === 0);
    });
    SQ.qsa('[data-ago]').forEach(function (el) { el.textContent = fmt.ago(el.getAttribute('data-ago')); });
    SQ.qsa('[data-clock]').forEach(function (el) {
      el.textContent = new Date(now).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit', second: el.getAttribute('data-clock') === 'sec' ? '2-digit' : undefined });
    });
  }
  setInterval(runTickers, 1000);
  SQ.tick = runTickers;

  /* ------------------------------------------------------------------
     Sound + speech (optional, off until the user allows it)
     ------------------------------------------------------------------ */
  var audioCtx = null;
  SQ.sound = {
    enabled: function () { return SQ.store.get('sq_sound', 'on') !== 'off'; },
    set: function (on) { SQ.store.set('sq_sound', on ? 'on' : 'off'); },
    chime: function (kind, force) {
      if (!force && !SQ.sound.enabled()) return;
      try {
        var AC = global.AudioContext || global.webkitAudioContext;
        if (!AC) return;
        audioCtx = audioCtx || new AC();
        if (audioCtx.state === 'suspended') audioCtx.resume();
        var notes = kind === 'call' ? [880, 1175, 1568] : [784, 988];
        var t0 = audioCtx.currentTime;
        notes.forEach(function (f, i) {
          var o = audioCtx.createOscillator(), g = audioCtx.createGain();
          o.type = 'sine'; o.frequency.value = f;
          g.gain.setValueAtTime(0.0001, t0 + i * 0.16);
          g.gain.exponentialRampToValueAtTime(0.18, t0 + i * 0.16 + 0.02);
          g.gain.exponentialRampToValueAtTime(0.0001, t0 + i * 0.16 + 0.32);
          o.connect(g); g.connect(audioCtx.destination);
          o.start(t0 + i * 0.16); o.stop(t0 + i * 0.16 + 0.34);
        });
      } catch (e) { /* audio is optional */ }
    },
    vibrate: function () { try { if (navigator.vibrate) navigator.vibrate([200, 100, 200]); } catch (e) { } },
    speak: function (text, force) {
      try {
        if (!global.speechSynthesis || (!force && !SQ.sound.enabled())) return;
        var u = new SpeechSynthesisUtterance(text);
        u.rate = 0.9; u.pitch = 1; u.volume = 1;
        global.speechSynthesis.speak(u);
      } catch (e) { /* optional */ }
    }
  };

  /* ------------------------------------------------------------------
     Notification bell
     ------------------------------------------------------------------ */
  var NOTIF_STYLE = {
    TOKEN_CREATED: ['ticket', 'blue'], NEXT: ['bell', 'amber'], APPROACHING: ['clock', 'amber'], CALLED: ['megaphone', 'green'],
    RECALL: ['megaphone', 'orange'], SKIPPED: ['x-circle', 'red'], HOLD: ['pause', 'orange'], QUEUE: ['list', 'blue'],
    COMPLETED: ['check-circle', 'green'], CANCELLED: ['x-circle', 'gray'], COUNTER: ['counter', 'blue'], WELCOME: ['heart', 'purple']
  };
  SQ.notifier = function (mount, opts) {
    opts = opts || {};
    mount.classList.add('dropdown');
    var lastId = null, items = [], unread = 0;
    mount.innerHTML = SQ.html`<button class="icon-btn" type="button" data-dd-toggle aria-label="Notifications" title="Notifications">${SQ.icon('bell', 19)}<span class="dot" hidden data-badge>0</span></button>
      <div class="popover notif-panel" hidden>
        <div class="notif-head"><div class="bold">Notifications</div>
          <div class="row gap-sm"><button class="btn btn-ghost btn-sm" type="button" data-n-read>Mark all read</button><button class="btn btn-ghost btn-sm" type="button" data-n-clear title="Delete all">${SQ.icon('trash', 15)}</button></div></div>
        <div class="notif-list" data-list></div></div>`.s;
    var badge = mount.querySelector('[data-badge]'), list = mount.querySelector('[data-list]');
    function render() {
      badge.hidden = unread <= 0;
      badge.textContent = unread > 99 ? '99+' : unread;
      if (!items.length) { SQ.setHtml(list, SQ.html`<div class="notif-empty">${SQ.icon('bell', 30)}<p class="mt">You're all caught up.</p><p class="small">Queue updates will appear here.</p></div>`); return; }
      SQ.setHtml(list, SQ.html`${items.map(function (n) {
        var st = NOTIF_STYLE[n.type] || ['info', 'blue'];
        return SQ.html`<div class="notif ${n.isRead ? '' : 'unread'}" data-nid="${n.id}" style="cursor:pointer"><span class="n-ico tone-${st[1]}">${SQ.icon(st[0], 18)}</span>
          <div class="grow"><div class="n-title">${n.title}</div><div class="n-msg">${n.message}</div><div class="n-time">${fmt.ago(n.createdAt)}</div></div></div>`;
      })}`);
    }
    function load() {
      return SQ.get('notifications?limit=20').then(function (d) {
        var fresh = [];
        if (lastId !== null) fresh = d.items.filter(function (n) { return n.id > lastId && !n.isRead; });
        items = d.items; unread = d.unread;
        if (d.items.length) lastId = Math.max(lastId === null ? 0 : lastId, d.items[0].id); else if (lastId === null) lastId = 0;
        render();
        if (fresh.length) {
          var shown = fresh.filter(function (n) { return n.type !== 'TOKEN_CREATED'; });
          shown.slice().reverse().forEach(function (n) {
            var call = n.type === 'CALLED' || n.type === 'RECALL';
            if (!opts.silent) SQ.toast(n.message, { type: call ? 'call' : 'info', title: n.title, icon: (NOTIF_STYLE[n.type] || [])[0], duration: call ? 9000 : 6000 });
          });
          if (!opts.silent && shown.length) {
            var hasCall = fresh.some(function (n) { return n.type === 'CALLED' || n.type === 'RECALL'; });
            SQ.sound.chime(hasCall ? 'call' : 'soft');
            if (hasCall) SQ.sound.vibrate();
          }
          if (opts.onNew) opts.onNew(fresh);
        }
      });
    }
    mount.addEventListener('click', function (e) {
      var n = e.target.closest('[data-nid]');
      if (n) {
        var id = Number(n.getAttribute('data-nid'));
        var it = items.filter(function (x) { return x.id === id; })[0];
        if (it && !it.isRead) { it.isRead = true; unread = Math.max(0, unread - 1); render(); SQ.post('notifications/' + id + '/read').catch(function () { }); }
      } else if (e.target.closest('[data-n-read]')) {
        items.forEach(function (x) { x.isRead = true; }); unread = 0; render();
        SQ.post('notifications/read-all').catch(function () { });
      } else if (e.target.closest('[data-n-clear]')) {
        SQ.del('notifications').then(function () { items = []; unread = 0; render(); }).catch(SQ.err);
      }
    });
    render();
    var poll = SQ.every(load, opts.interval || 5000);
    return { refresh: poll.now, stop: poll.stop };
  };

  /* Toggle for the notification sound, used in the customer app */
  SQ.soundButton = function () {
    var on = SQ.sound.enabled();
    return SQ.html`<button class="icon-btn" type="button" data-action="sound" title="Notification sound" aria-label="Toggle notification sound"><span data-sound-icon>${SQ.icon(on ? 'volume' : 'volume-x', 18)}</span></button>`;
  };
  document.addEventListener('click', function (e) {
    var b = e.target.closest('[data-action="sound"]');
    if (!b) return;
    var on = !SQ.sound.enabled();
    SQ.sound.set(on);
    SQ.qsa('[data-sound-icon]').forEach(function (x) { x.innerHTML = SQ.icon(on ? 'volume' : 'volume-x', 18).s; });
    if (on) SQ.sound.chime('soft');
    SQ.toast(on ? 'Sound alerts are on.' : 'Sound alerts are off.', { type: 'info' });
  });

})(window);
