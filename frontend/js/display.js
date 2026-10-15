/* SmartQueue - public lobby display board (no sign-in needed) */
(function () {
  'use strict';
  var fmt = SQ.fmt;
  document.documentElement.setAttribute('data-theme', 'dark');
  var app = SQ.qs('#app');
  var board = null, known = null, soundOn = SQ.store.get('sq_display_sound', 'off') === 'on', flashIds = {};

  function counterCard(c) {
    var col = SQ.safeColor(c.serviceColor);
    var state = c.currentStatus === 'CALLED' ? 'called' : (c.currentStatus === 'SERVING' ? 'serving' : '');
    var label = c.status === 'BREAK' ? 'On break' : (c.currentStatus === 'CALLED' ? 'Please proceed' : (c.currentStatus === 'SERVING' ? 'Now serving' : 'Ready'));
    return SQ.html`<div class="dc ${state} ${c.currentCode ? '' : 'idle'} ${flashIds[c.id] ? 'flash-new' : ''}" style="--c:${col}">
      <div class="dc-name"><span>${c.name}</span><span class="dc-state">${label}</span></div>
      <div class="dc-svc">${c.serviceName}${c.staffName ? ' · ' + c.staffName.split(' ')[0] : ''}</div>
      <div class="dc-code">${c.currentCode || '—'}</div></div>`;
  }

  function serviceRow(s) {
    var col = SQ.safeColor(s.color);
    return SQ.html`<div class="disp-svc"><span class="svc-ico" style="background:${SQ.tint(col, 0.2)};color:${col}">${SQ.icon(s.icon, 22)}</span>
      <div class="grow"><div class="ds-name">${s.name} <span class="muted" style="font-weight:600">(${s.prefix})</span></div>
        <div class="ds-next">${s.nextTokens.length ? s.nextTokens.map(function (t) { return SQ.html`<span>${t}</span>`; }) : SQ.html`<span style="background:transparent;padding-left:0;color:rgba(255,255,255,.55);font-weight:500;font-size:13px">No one waiting</span>`}</div></div>
      <div class="ds-count"><div class="n">${s.waiting}</div><div class="l">waiting${s.noCounters ? '' : ' · ~' + fmt.minutes(s.etaMinutes, { zero: '0 min' })}</div></div></div>`;
  }

  function render() {
    if (!board) { SQ.setHtml(app, SQ.html`<div class="disp"><div class="skeleton" style="height:80px;opacity:.2"></div></div>`); return; }
    var live = board.counters.filter(function (c) { return c.status !== 'CLOSED'; });
    var closed = board.counters.length - live.length;
    var now = new Date(SQ.now());
    var services = board.services.filter(function (s) { return s.isActive; });
    SQ.setHtml(app, SQ.html`
      <div class="disp">
        <header class="disp-head">${SQ.brand(board.orgName, '/index.html')}
          ${SQ.crowdPill(board.crowd.crowdLevel, board.crowd.queueLength + ' in queue')}
          ${SQ.connBadge()}
          <div class="disp-clock"><div class="t" data-clock="min">${now.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })}</div><div class="d">${now.toLocaleDateString([], { weekday: 'long', day: 'numeric', month: 'long' })}</div></div></header>
        <div class="disp-main">
          <section class="disp-card"><div class="disp-title">${SQ.icon('megaphone', 16)} Now serving</div>
            ${live.length ? SQ.html`<div class="disp-counters">${live.map(counterCard)}</div>` : SQ.html`<div class="empty" style="color:rgba(255,255,255,.6)"><h3 style="color:#fff">No counters are open right now</h3><p>Please wait – service will resume shortly.</p></div>`}
            ${closed ? SQ.html`<p class="tiny" style="margin-top:14px;color:rgba(255,255,255,.45)">${closed} counter${closed === 1 ? '' : 's'} closed</p>` : ''}</section>
          <section class="disp-card"><div class="disp-title">${SQ.icon('users', 16)} Queues</div>${services.map(serviceRow)}
            <p class="tiny" style="margin-top:12px;color:rgba(255,255,255,.5)">Take a token on your phone or at the desk, then watch this screen for your number.</p></section>
        </div>
        <footer class="disp-ticker"><span class="lbl">Recent calls</span><div class="items">${board.recentCalls.length ? board.recentCalls.map(function (r) {
          return SQ.html`<span class="it" style="color:${SQ.safeColor(r.serviceColor)}">${r.tokenCode}<small style="color:rgba(255,255,255,.65)">→ ${r.counterName} · ${fmt.time(r.lastCalledAt)}</small></span>`;
        }) : SQ.html`<span class="it" style="color:rgba(255,255,255,.5);font-weight:500">No calls yet today</span>`}</div></footer>
      </div>
      <div class="disp-btns">
        <button class="icon-btn" id="btnSound" title="Voice and sound announcements" aria-label="Toggle announcements" style="background:rgba(255,255,255,.1);color:#fff;border-color:rgba(255,255,255,.2)">${SQ.icon(soundOn ? 'volume' : 'volume-x', 18)}</button>
        <button class="icon-btn" id="btnFull" title="Full screen" aria-label="Full screen" style="background:rgba(255,255,255,.1);color:#fff;border-color:rgba(255,255,255,.2)">${SQ.icon('maximize', 18)}</button>
      </div>`);
    SQ.tick();
  }

  function detect(b) {
    var now = {};
    b.counters.forEach(function (c) { now[c.id] = (c.currentCode || '') + '|' + (c.currentStatus || ''); });
    if (known) {
      b.counters.forEach(function (c) {
        var key = now[c.id];
        if (c.currentStatus === 'CALLED' && known[c.id] !== key) {
          flashIds[c.id] = true; setTimeout(function () { delete flashIds[c.id]; render(); }, 6000);
          if (soundOn) {
            SQ.sound.chime('call', true);
            setTimeout(function () { SQ.sound.speak('Token ' + c.currentCode.split('').join(' ') + ', please proceed to ' + c.name, true); }, 900);
          }
        }
      });
    }
    known = now;
  }

  function load() {
    return SQ.get('public/board', { noRedirect: true }).then(function (b) { detect(b); board = b; render(); });
  }

  app.addEventListener('click', function (e) {
    if (e.target.closest('#btnSound')) {
      soundOn = !soundOn; SQ.store.set('sq_display_sound', soundOn ? 'on' : 'off');
      if (soundOn) { SQ.sound.chime('call', true); SQ.sound.speak('Announcements are on', true); }
      render();
    } else if (e.target.closest('#btnFull')) {
      var el = document.documentElement;
      try { if (document.fullscreenElement) document.exitFullscreen(); else if (el.requestFullscreen) el.requestFullscreen(); } catch (er) { }
    }
  });

  function keepAwake() {
    try { if (navigator.wakeLock) navigator.wakeLock.request('screen').catch(function () { }); } catch (e) { }
  }
  document.addEventListener('visibilitychange', function () { if (!document.hidden) keepAwake(); });
  keepAwake();
  render();
  SQ.every(load, 3000);
})();
