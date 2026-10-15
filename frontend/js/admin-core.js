/* SmartQueue - admin console: shell, router and shared helpers */
(function (global) {
  'use strict';
  var SQA = global.SQA = { pages: {}, order: [], current: null, pollers: [], me: null };

  SQA.register = function (id, def) { def.id = id; SQA.pages[id] = def; SQA.order.push(id); };

  SQA.listeners = [];
  SQA.docListen = function (type, fn) { document.addEventListener(type, fn); SQA.listeners.push([type, fn]); };
  SQA.poll = function (fn, ms) { var p = SQ.every(fn, ms); SQA.pollers.push(p); return p; };
  SQA.stopPolling = function () { SQA.pollers.forEach(function (p) { p.stop(); }); SQA.pollers = []; };

  SQA.forecastChart = function (el, fc, height) {
    var svcs = fc.length ? fc[0].perService : [];
    var any = fc.some(function (x) { return x.expected > 0; });
    if (!any) { el.innerHTML = '<div class="empty" style="padding:28px 16px"><p>No visitors are expected in the coming hours based on the last 4 weeks.</p></div>'; return; }
    SQ.charts.bar(el, {
      labels: fc.map(function (x) { return SQA.hourShort(x.hour); }), stacked: true, height: height || 220,
      series: svcs.map(function (s, si) { return { name: s.name, color: SQ.safeColor(s.color), values: fc.map(function (x) { return x.perService[si] ? x.perService[si].expected : 0; }) }; }),
      tip: function (i) { var x = fc[i]; return '<b>' + x.label + '</b><br>' + x.expected + ' visitors expected'; }
    });
  };
  SQA.hourShort = function (h) { return h === 0 ? '12a' : h < 12 ? h + 'a' : h === 12 ? '12p' : (h - 12) + 'p'; };
  SQA.hourLong = function (h) { var n = h % 12 === 0 ? 12 : h % 12; return n + ' ' + (h < 12 ? 'AM' : 'PM'); };

  SQA.view = function () { return SQ.qs('#adminView'); };
  SQA.busyUi = function () { return !!SQ.qs('#adminView .dropdown.open') || (document.activeElement && /^(INPUT|TEXTAREA|SELECT)$/.test(document.activeElement.tagName) && document.activeElement.closest('#adminView')); };

  SQA.statCard = function (icon, tone, value, label, sub) {
    return SQ.html`<div class="card stat tone-${tone}"><span class="stat-icon">${SQ.icon(icon, 22)}</span><div><div class="stat-value">${value}</div><div class="stat-label">${label}</div>${sub ? SQ.html`<div class="stat-sub">${sub}</div>` : ''}</div></div>`;
  };

  SQA.sectionHead = function (title, sub, right) {
    return SQ.html`<div class="page-head"><div><h1>${title}</h1>${sub ? SQ.html`<p>${sub}</p>` : ''}</div>${right ? SQ.html`<div class="row wrap">${right}</div>` : ''}</div>`;
  };

  SQA.recoCard = function (r, opts) {
    var tone = { HIGH: 'red', MEDIUM: 'amber', LOW: 'blue', INFO: 'teal' }[r.severity] || 'blue';
    var icon = { OPEN_COUNTER: 'login', REASSIGN_COUNTER: 'recall', ADD_COUNTER: 'plus', ENABLE_AUTO_ASSIGN: 'zap', FORECAST: 'trending', INFO: 'info' }[r.type] || 'bulb';
    return SQ.html`<div class="reco tone-${tone}"><span class="r-ico">${SQ.icon(icon, 19)}</span>
      <div class="grow"><div class="row spread" style="gap:8px"><span class="r-title">${r.title}</span><span class="badge tone-${tone}">${({ HIGH: 'Urgent', MEDIUM: 'Suggested', LOW: 'Tip', INFO: 'Info' })[r.severity] || r.severity}</span></div>
        <div class="r-msg">${r.message}</div>
        ${r.canApply && r.action ? SQ.html`<div style="margin-top:10px"><button class="btn btn-sm btn-primary" data-apply-reco="${r.id}">${SQ.icon('zap', 14)} Apply now</button></div>` : ''}</div></div>`;
  };

  /** Applies a recommendation; resolves with the new list of recommendations. */
  SQA.applyReco = function (btn, reco, after) {
    return SQ.busy(btn, function () {
      var a = reco.action || {};
      return SQ.post('admin/recommendations/apply', { type: a.type || reco.type, counterId: a.counterId, targetServiceId: a.targetServiceId, staffId: a.staffId }).then(function (r) {
        SQ.ok('Done – recommendation applied.', { title: reco.title });
        if (after) return after(r);
      });
    });
  };

  /** Dialog showing a temporary password once. */
  SQA.showPassword = function (title, who, pw) {
    SQ.modal({
      title: title, icon: 'key', tone: 'green', size: 'sm',
      body: SQ.html`<p class="text-2 mb">Share this temporary password with <b>${who}</b>. It is shown only once.</p>
        <div class="row" style="padding:14px;border-radius:12px;background:var(--surface-2);border:1px dashed var(--border-strong);justify-content:space-between"><code class="mono bold" style="font-size:20px;letter-spacing:.03em" id="tempPw">${pw}</code>
          <button class="btn btn-sm" data-copy-pw>${SQ.icon('copy', 14)} Copy</button></div>
        <p class="tiny muted mt">The user can change it under Profile after signing in.</p>`,
      actions: [{ label: 'Done', kind: 'primary', value: true }],
      onOpen: function (c) { c.el.addEventListener('click', function (e) { if (e.target.closest('[data-copy-pw]')) SQ.copy(pw); }); }
    });
  };

  var NAV = [
    ['Overview', ['dashboard', 'queues', 'optimization']],
    ['Manage', ['counters', 'services', 'users']],
    ['Insights', ['analytics', 'reports', 'activity']],
    ['System', ['settings']]
  ];

  function shell() {
    var u = SQA.me.user;
    var nav = NAV.map(function (g) {
      return SQ.html`<div class="nav-label">${g[0]}</div>${g[1].map(function (id) {
        var p = SQA.pages[id];
        return SQ.html`<a class="nav-item" href="#/${id}" data-nav="${id}">${SQ.icon(p.icon, 19)}<span>${p.title}</span></a>`;
      })}`;
    });
    SQ.qs('#app').innerHTML = SQ.html`
      <div class="shell" id="shell">
        <aside class="sidebar" aria-label="Administration menu">
          ${SQ.brand(SQA.me.orgName, '#/dashboard')}
          ${nav}
          <div class="sidebar-foot stack sm">
            <a class="nav-item" href="/display.html" target="_blank" rel="noopener">${SQ.icon('monitor', 19)}<span>Lobby display</span></a>
            <div class="row" style="padding:6px 8px"><span class="avatar">${SQ.initials(u.fullName)}</span><div class="grow" style="min-width:0"><div class="bold small truncate">${u.fullName}</div><div class="tiny muted">Administrator</div></div>
              <button class="icon-btn" data-action="logout" title="Sign out" aria-label="Sign out">${SQ.icon('logout', 17)}</button></div>
          </div>
        </aside>
        <div class="scrim" data-close-nav></div>
        <div class="main">
          <div class="main-top">
            <button class="icon-btn menu-toggle" data-toggle-nav aria-label="Open menu">${SQ.icon('menu', 19)}</button>
            <div class="bold" id="pageTitle" style="font-size:17px"></div>
            <div class="spacer"></div>
            <span id="topCrowd" class="hide-sm"></span>
            ${SQ.connBadge()}
            ${SQ.themeButton()}
          </div>
          <div class="main-content" id="adminView"></div>
        </div>
      </div>`.s;
  }

  function route() {
    var id = (location.hash.replace(/^#\/?/, '').split('?')[0]) || 'dashboard';
    if (!SQA.pages[id]) id = 'dashboard';
    if (SQA.current) { try { if (SQA.current.unmount) SQA.current.unmount(); } catch (e) { } }
    SQA.stopPolling();
    SQA.listeners.forEach(function (l) { document.removeEventListener(l[0], l[1]); });
    SQA.listeners = [];
    var page = SQA.pages[id];
    SQA.current = page;
    SQ.qsa('[data-nav]').forEach(function (a) { a.classList.toggle('active', a.getAttribute('data-nav') === id); });
    SQ.qs('#pageTitle').textContent = page.title;
    document.title = page.title + ' – SmartQueue Admin';
    SQ.qs('#shell').classList.remove('nav-open');
    // a brand-new container drops every listener the previous page attached
    var old = SQA.view();
    var v = document.createElement('div');
    v.className = 'main-content'; v.id = 'adminView';
    old.parentNode.replaceChild(v, old);
    window.scrollTo(0, 0);
    page.mount(v);
  }

  function topCrowd() {
    return SQ.get('public/board').then(function (b) {
      SQ.setHtml(SQ.qs('#topCrowd'), SQ.crowdPill(b.crowd.crowdLevel, b.crowd.queueLength + ' in queue'));
    });
  }

  SQA.start = function () {
    SQ.requireRole(['ADMIN']).then(function (me) {
      SQA.me = me;
      shell();
      document.addEventListener('click', function (e) {
        if (e.target.closest('[data-toggle-nav]')) SQ.qs('#shell').classList.add('nav-open');
        else if (e.target.closest('[data-close-nav]') || e.target.closest('[data-nav]')) SQ.qs('#shell').classList.remove('nav-open');
      });
      window.addEventListener('hashchange', route);
      route();
      SQ.every(topCrowd, 6000);
    }).catch(function (e) { if (e && e.status !== 401) SQ.qs('#app').innerHTML = SQ.html`<div class="page"><div class="alert tone-red">${SQ.icon('alert', 20)}<div class="a-body">${e.message}</div></div></div>`.s; });
  };
})(window);
