/* SmartQueue - customer portal */
(function () {
  'use strict';
  var fmt = SQ.fmt;
  var app = SQ.qs('#app');
  var state = { me: null, config: null, dash: null, tab: 'home', outcome: null, hist: { page: 1, status: '', data: null, loading: false }, notifier: null };
  var STEPS = ['Issued', 'Waiting', 'Called', 'Serving', 'Done'];

  /* ------------------------------------------------------------------ shell */
  function shell() {
    var u = state.me.user;
    app.innerHTML = SQ.html`
      <header class="topbar"><div class="topbar-inner">
        ${SQ.brand(state.me.orgName, '/user.html')}
        <nav class="nav-tabs" aria-label="Main">
          <button class="nav-tab" data-tab="home">${SQ.icon('ticket', 18)}<span>My queue</span></button>
          <button class="nav-tab" data-tab="history">${SQ.icon('history', 18)}<span>History</span></button>
          <button class="nav-tab" data-tab="profile">${SQ.icon('user', 18)}<span>Profile</span></button>
        </nav>
        <div class="spacer"></div>
        ${SQ.connBadge()}
        ${SQ.soundButton()}
        <div id="bell"></div>
        ${SQ.themeButton()}
        ${SQ.userMenu(u)}
      </div></header>
      <main class="page" id="view"></main>`.s;
    state.notifier = SQ.notifier(SQ.qs('#bell'), { onNew: function () { refresh(); } });
    setTab(state.tab);
  }

  function setTab(t) {
    state.tab = t;
    SQ.qsa('.nav-tab').forEach(function (b) { b.classList.toggle('active', b.getAttribute('data-tab') === t); });
    var v = SQ.qs('#view'); v.__html = null; v.innerHTML = '';
    if (t === 'home') renderHome();
    else if (t === 'history') { renderHistory(); loadHistory(); }
    else renderProfile();
    window.scrollTo(0, 0);
    if (location.hash !== '#' + t) history.replaceState(null, '', '#' + t);
  }

  /* ------------------------------------------------------------------ home */
  function greeting() {
    var h = new Date(SQ.now()).getHours();
    return h < 12 ? 'Good morning' : (h < 17 ? 'Good afternoon' : 'Good evening');
  }

  function crowdMeter(crowd) {
    var c = state.config || { crowdLowMax: 5, crowdModerateMax: 15, crowdHighMax: 30 };
    var low = c.crowdLowMax + 1, mod = c.crowdModerateMax + 1, high = c.crowdHighMax + 1;
    var scale = Math.max(high * 1.3, crowd.queueLength + 2);
    var segs = [[0, low, 'var(--crowd-low)'], [low, mod, 'var(--crowd-moderate)'], [mod, high, 'var(--crowd-high)'], [high, scale, 'var(--crowd-critical)']];
    var pos = Math.min(100, (crowd.queueLength / scale) * 100);
    return SQ.html`<div style="position:relative;padding-top:14px">
      <div style="display:flex;height:10px;border-radius:6px;overflow:hidden;gap:2px">${segs.map(function (s) { return SQ.html`<i style="flex:${s[1] - s[0]};background:${SQ.raw(s[2])};opacity:.85"></i>`; })}</div>
      <i style="position:absolute;top:0;left:calc(${pos.toFixed(1)}% - 7px);width:0;height:0;border-left:7px solid transparent;border-right:7px solid transparent;border-top:10px solid var(--text);transition:left .6s"></i>
    </div>`;
  }

  function serviceCard(s) {
    var c = SQ.safeColor(s.color);
    return SQ.html`<article class="card svc-card" style="--c:${c}">
      <div class="row top">
        ${SQ.serviceIcon(s)}
        <div class="grow"><div class="row spread"><h3>${s.name}</h3>${SQ.tokenChip(s.prefix, c)}</div>
          <p class="small muted" style="margin-top:2px">${s.description || ''}</p></div>
      </div>
      <div class="svc-metrics">
        <div><div class="v">${s.waiting}</div><div class="l">Waiting</div></div>
        <div><div class="v">${fmt.minutes(s.etaMinutes, { zero: 'None' })}</div><div class="l">Est. wait</div></div>
        <div><div class="v">${s.countersOpen}<span class="muted" style="font-weight:600">/${s.countersTotal}</span></div><div class="l">Counters</div></div>
      </div>
      <div class="row spread">${SQ.crowdPill(s.crowdLevel)}<button class="btn btn-primary" data-take="${s.id}">${SQ.icon('ticket', 17)} Get token</button></div>
      ${s.noCounters ? SQ.html`<div class="tiny muted row" style="gap:6px">${SQ.icon('info', 13)} No counter is open right now. You can still join; staff will call you when one opens.</div>` : ''}
    </article>`;
  }

  function outcomeCard() {
    var t = state.outcome;
    if (!t) return '';
    if (t.status === 'COMPLETED') {
      return SQ.html`<div class="card elevated mb" style="border-color:var(--primary)"><div class="card-body row wrap top">
        <span class="svc-ico lg tone-green" style="background:var(--tone-soft);color:var(--tone)">${SQ.icon('check-circle', 28)}</span>
        <div class="grow"><h3>Token ${t.tokenCode} completed. Thank you!</h3>
          <p class="muted small">${t.rating ? 'Thanks for rating your visit.' : 'How was your visit to ' + t.serviceName + '? Your feedback helps us improve.'}</p></div>
        <div class="row">${t.rating ? '' : SQ.html`<button class="btn btn-primary" data-rate="${t.id}">${SQ.icon('star', 16)} Rate visit</button>`}<button class="btn btn-ghost" data-dismiss-outcome>Dismiss</button></div></div></div>`;
    }
    if (t.status === 'SKIPPED') {
      return SQ.html`<div class="alert tone-red mb" role="alert">${SQ.icon('x-circle', 20)}<div class="a-body grow"><b>You missed your turn.</b> Token ${t.tokenCode} was skipped because you did not reach the counter in time. You can ask the staff to restore it, or take a new token below.</div><button class="btn btn-sm" data-dismiss-outcome>Dismiss</button></div>`;
    }
    return SQ.html`<div class="alert tone-gray mb">${SQ.icon('info', 20)}<div class="a-body grow"><b>Token ${t.tokenCode} was cancelled.</b> ${t.closeReason || ''}</div><button class="btn btn-sm" data-dismiss-outcome>Dismiss</button></div>`;
  }

  function stepsHtml(t) {
    var idx = { WAITING: 1, ON_HOLD: 1, CALLED: 2, SERVING: 3 }[t.status];
    if (idx === undefined) idx = 4;
    return SQ.html`<div class="steps">${STEPS.map(function (name, i) {
      var cls = i < idx ? 'done' : (i === idx ? 'current' : '');
      return SQ.html`<div class="step ${cls}"><div class="s-dot">${i < idx ? SQ.icon('check', 14) : (i + 1)}</div>${name}</div>`;
    })}</div>`;
  }

  function barcode(code) {
    var bars = [], seed = 7;
    for (var i = 0; i < code.length; i++) seed = (seed * 31 + code.charCodeAt(i)) % 9973;
    for (var k = 0; k < 46; k++) { seed = (seed * 1103515245 + 12345) % 2147483647; bars.push(1 + (seed % 3)); }
    return SQ.html`<div class="barcode" aria-hidden="true">${bars.map(function (w, i) { return SQ.html`<i style="width:${w}px;height:${i % 5 === 0 ? 34 : 24 + (w * 3)}px"></i>`; })}</div>`;
  }

  function banner(t) {
    var cfg = state.config || { noShowMinutes: 3 };
    if (t.status === 'CALLED') {
      var deadline = new Date(Date.parse(t.lastCalledAt || t.calledAt) + cfg.noShowMinutes * 60000).toISOString();
      return SQ.html`<div class="call-banner" role="alert"><span class="cb-ico">${SQ.icon('megaphone', 28)}</span>
        <div class="grow"><h3>It's your turn!</h3><div>Please proceed to <b>${t.counterName}</b> now.</div>
          <div class="small" style="opacity:.9;margin-top:4px">Reach the counter within <b class="mono" data-countdown="${deadline}">--:--</b> or your token may be skipped.</div></div></div>`;
    }
    if (t.status === 'SERVING') {
      return SQ.html`<div class="call-banner serving"><span class="cb-ico">${SQ.icon('play', 26)}</span>
        <div class="grow"><h3>You are being served</h3><div>${t.counterName}${t.servedByName ? ' · ' + t.servedByName : ''}</div></div></div>`;
    }
    if (t.status === 'ON_HOLD') {
      return SQ.html`<div class="call-banner hold"><span class="cb-ico">${SQ.icon('pause', 26)}</span>
        <div class="grow"><h3>Your token is on hold</h3><div>Staff set it aside for a moment. You will be called again shortly. Please stay nearby.</div></div></div>`;
    }
    if (t.ahead === 0) {
      return SQ.html`<div class="alert tone-amber" style="padding:14px 16px">${SQ.icon('bell', 20)}<div class="a-body"><b>You're next!</b> Please be ready near the counters.</div></div>`;
    }
    var thr = (state.config && state.config.approachThreshold) || 2;
    if (t.ahead <= thr) {
      return SQ.html`<div class="alert tone-amber" style="padding:14px 16px">${SQ.icon('clock', 20)}<div class="a-body"><b>Your turn is approaching.</b> ${fmt.plural(t.ahead, 'person is', 'people are')} ahead of you. Start making your way to the counters.</div></div>`;
    }
    return SQ.html`<div class="alert tone-blue" style="padding:14px 16px">${SQ.icon('info', 20)}<div class="a-body">You're in the queue. We'll alert you when your turn is approaching, so you don't have to stand in line.</div></div>`;
  }

  function initialAhead(t) {
    var key = 'sq_init_' + t.id, v = Number(SQ.store.get(key, '0')) || 0;
    if (t.status === 'WAITING' && t.ahead !== null && t.ahead > v) { v = t.ahead; SQ.store.set(key, String(v)); }
    return v;
  }

  function ticket(t) {
    var c = SQ.safeColor(t.serviceColor);
    var waiting = t.status === 'WAITING' || t.status === 'ON_HOLD';
    var stats;
    if (t.status === 'WAITING') {
      stats = SQ.html`<div><div class="v">${t.position === null ? '—' : '#' + t.position}</div><div class="l">Your position</div></div>
        <div><div class="v">${t.ahead === null ? '—' : t.ahead}</div><div class="l">People ahead</div></div>
        <div><div class="v">${t.ahead === 0 ? 'Next' : fmt.minutes(t.etaMinutes, { zero: 'Now' })}</div><div class="l">Est. wait</div></div>`;
    } else {
      stats = SQ.html`<div><div class="v" style="font-size:22px">${t.counterName || '—'}</div><div class="l">Counter</div></div>
        <div><div class="v" style="font-size:22px">${fmt.time(t.createdAt)}</div><div class="l">Issued</div></div>
        <div><div class="v" style="font-size:22px">${fmt.minutes(t.waitedMinutes, { zero: '< 1 min' })}</div><div class="l">Waited</div></div>`;
    }
    var init = initialAhead(t);
    var prog = '';
    if (t.status === 'WAITING' && init > 0) {
      var pct = Math.max(4, Math.min(100, Math.round(((init - t.ahead) / init) * 100)));
      prog = SQ.html`<div class="progress mt" style="--c:${c}"><i style="width:${pct}%"></i></div><div class="row spread tiny muted" style="margin-top:6px"><span>Joined</span><span>${pct}% of the way there</span><span>Your turn</span></div>`;
    }
    return SQ.html`<div class="ticket">
      <div class="ticket-top" style="--c:${c}">
        <div class="row spread"><div class="ticket-svc">${SQ.icon(t.serviceIcon || 'ticket', 20)} ${t.serviceName}</div>
          <span class="status-chip">${SQ.icon((SQ.STATUS[t.status] || {}).icon || 'ticket', 14)} ${(SQ.STATUS[t.status] || { label: t.status }).label}</span></div>
        <div class="ticket-code">${t.tokenCode}</div>
        <div class="row wrap" style="gap:8px;opacity:.95">
          <span class="small">Issued ${fmt.time(t.createdAt)}</span>
          ${t.priority > 0 ? SQ.html`<span class="status-chip">${SQ.icon(t.priority === 2 ? 'zap' : 'arrow-up', 13)} ${t.priority === 2 ? 'Emergency' : 'Priority'}</span>` : ''}
        </div>
      </div>
      <div class="ticket-tear"></div>
      <div class="ticket-bottom">
        <div class="ticket-stats">${stats}</div>
        ${prog}
        ${waiting && t.etaAt && t.status === 'WAITING' ? SQ.html`<p class="center small muted mt">Expected around <b class="text-2">${fmt.time(t.etaAt)}</b></p>` : ''}
        <div class="divider"></div>
        <div class="row spread wrap">${barcode(t.tokenCode)}
          ${t.status !== 'SERVING' ? SQ.html`<button class="btn btn-outline-danger" data-cancel="${t.id}">${SQ.icon('x-circle', 16)} Cancel token</button>` : ''}</div>
      </div>
    </div>`;
  }

  function nowServingCard(svc) {
    var list = (svc && svc.nowServing) || [];
    return SQ.html`<div class="card"><div class="card-head"><div class="card-title">${SQ.icon('megaphone', 17)} Now serving</div>${svc ? SQ.html`<span class="muted small">${svc.name}</span>` : ''}</div>
      <div class="card-body">${list.length ? SQ.html`<div class="stack sm">${list.map(function (n) {
        return SQ.html`<div class="row spread" style="padding:8px 12px;border-radius:10px;background:var(--surface-2)"><span class="tok" style="font-size:20px">${n.tokenCode}</span><span class="row small text-2">${SQ.icon('arrow-right', 14)} ${n.counterName}</span>${SQ.statusBadge(n.status)}</div>`;
      })}</div>` : SQ.html`<p class="muted small">No one is being served at the moment.</p>`}</div></div>`;
  }

  function activeView(d) {
    var t = d.activeToken, svc = t.service;
    return SQ.html`
      <div class="grid split">
        <div class="stack">${banner(t)}${ticket(t)}
          <div class="card"><div class="card-body">${stepsHtml(t)}</div></div></div>
        <div class="stack">
          ${nowServingCard(svc)}
          <div class="card"><div class="card-head"><div class="card-title">${SQ.icon('users', 17)} Crowd in ${t.serviceName}</div>${SQ.crowdPill(svc.crowdLevel)}</div>
            <div class="card-body stack sm">
              <dl class="kv"><dt>People waiting</dt><dd>${svc.waiting}</dd><dt>Counters open</dt><dd>${svc.countersOpen} of ${svc.countersTotal}</dd>
              <dt>Avg. service time</dt><dd>${fmt.minutes(svc.avgServiceMinutes)}</dd></dl>
              ${svc.noCounters ? SQ.html`<div class="alert tone-amber tiny" style="padding:8px 10px">${SQ.icon('alert', 14)}<div class="a-body">No counter is open right now, so the wait time is only an estimate.</div></div>` : ''}
            </div></div>
          ${t.notes ? SQ.html`<div class="card"><div class="card-body"><div class="label">Your note</div><p class="text-2 small">${t.notes}</p></div></div>` : ''}
          <div class="card"><div class="card-body"><div class="row top" style="gap:12px">${SQ.icon('bell', 20)}<div class="small text-2">You can close this page. Keep it open (or enable sound with the speaker button) to hear an alert when you are called.</div></div></div></div>
        </div>
      </div>`;
  }

  function chooseView(d) {
    var crowd = d.crowd;
    return SQ.html`
      ${state.me.user.isPriority ? SQ.html`<div class="alert tone-blue mb">${SQ.icon('shield-check', 20)}<div class="a-body"><b>Priority customer</b> – ${state.me.user.priorityReason || 'priority access'}. Your tokens are served ahead of regular tokens.</div></div>` : ''}
      <div class="grid split">
        <div><div class="row spread mb"><h2>Choose a service</h2><span class="muted small">${fmt.plural(d.services.length, 'service')} available</span></div>
          ${d.services.length ? SQ.html`<div class="grid auto-grid">${d.services.map(serviceCard)}</div>` : SQ.html`<div class="card">${SQ.empty('inbox', 'No services available', 'Please check back later.')}</div>`}
        </div>
        <div class="stack">
          <div class="card"><div class="card-head"><div class="card-title">${SQ.icon('activity', 17)} Crowd right now</div>${SQ.crowdPill(crowd.crowdLevel)}</div>
            <div class="card-body stack">${crowdMeter(crowd)}
              <dl class="kv"><dt>People in queue</dt><dd>${crowd.queueLength}</dd><dt>Counters open</dt><dd>${crowd.countersOpen} of ${crowd.countersTotal}</dd>
              <dt>Busiest service</dt><dd>${crowd.busiestService || '—'}</dd></dl></div></div>
          <div class="card"><div class="card-head"><div class="card-title">${SQ.icon('bulb', 17)} How it works</div></div>
            <div class="card-body stack sm small text-2">
              <div class="row top"><b class="tok-chip">1</b><span>Pick a service and take a digital token.</span></div>
              <div class="row top"><b class="tok-chip">2</b><span>Watch your position and wait time update live.</span></div>
              <div class="row top"><b class="tok-chip">3</b><span>Get alerted when your turn is near, then walk to the counter shown.</span></div></div></div>
        </div>
      </div>`;
  }

  function renderHome() {
    if (state.tab !== 'home') return;
    var v = SQ.qs('#view'), d = state.dash;
    if (!d) { SQ.setHtml(v, SQ.html`<div class="grid auto-grid">${[1, 2, 3].map(function () { return SQ.html`<div class="skeleton" style="height:230px"></div>`; })}</div>`); return; }
    var u = state.me.user;
    SQ.setHtml(v, SQ.html`
      <div class="page-head"><div><h1>${greeting()}, ${u.fullName.split(' ')[0]}</h1>
        <p>${d.activeToken ? 'Here is your live queue status.' : 'Take a digital token and skip the line.'}</p></div>
        <div class="row wrap">${SQ.crowdPill(d.crowd.crowdLevel, d.crowd.queueLength + ' in queue')}</div></div>
      ${outcomeCard()}
      ${d.activeToken ? activeView(d) : chooseView(d)}`);
    SQ.tick();
  }

  function updateTitle() {
    var t = state.dash && state.dash.activeToken;
    if (!t) { document.title = 'SmartQueue – My Queue'; return; }
    if (t.status === 'CALLED') document.title = '🔔 Your turn! ' + t.tokenCode + ' – ' + t.counterName;
    else if (t.status === 'SERVING') document.title = t.tokenCode + ' – being served';
    else if (t.status === 'WAITING') document.title = '#' + t.position + ' · ' + t.tokenCode + ' – SmartQueue';
    else document.title = t.tokenCode + ' – SmartQueue';
  }

  function refresh() {
    return SQ.get('user/dashboard').then(function (d) {
      var prev = state.dash ? state.dash.activeToken : null;
      var changed = prev && (!d.activeToken || d.activeToken.id !== prev.id);
      var statusChanged = prev && d.activeToken && prev.id === d.activeToken.id && prev.status !== d.activeToken.status;
      state.dash = d;
      updateTitle();
      renderHome();
      if (statusChanged && state.notifier) state.notifier.refresh();
      if (changed) return onTokenEnded(prev);
    });
  }

  function onTokenEnded(prev) {
    SQ.store.del('sq_init_' + prev.id);
    return SQ.get('user/tokens/' + prev.id).then(function (t) {
      if (t.status === 'WAITING' || t.status === 'CALLED' || t.status === 'SERVING' || t.status === 'ON_HOLD') return;
      state.outcome = t;
      renderHome();
      if (state.notifier) state.notifier.refresh();
      if (t.status === 'COMPLETED' && !t.rating && state.tab === 'home') openRating(t);
    }).catch(function () { });
  }

  /* ------------------------------------------------------------------ actions */
  function takeToken(serviceId) {
    var s = state.dash.services.filter(function (x) { return x.id === serviceId; })[0];
    if (!s) return;
    var ctl;
    var c = SQ.safeColor(s.color);
    ctl = SQ.modal({
      title: 'Get a token for ' + s.name,
      body: SQ.html`
        <div class="stack">
          <div class="row" style="padding:14px;border-radius:12px;background:var(--surface-2)">${SQ.serviceIcon(s, 'lg')}
            <div class="grow"><div class="bold">${s.name}</div><div class="small muted">Token prefix <b style="color:${c}">${s.prefix}</b> · ${s.countersOpen} of ${s.countersTotal} counters open</div></div></div>
          <div class="grid grid-3" style="gap:10px;text-align:center">
            <div class="card card-body" style="padding:12px"><div class="stat-value" style="font-size:22px">${s.waiting}</div><div class="stat-label">in the queue</div></div>
            <div class="card card-body" style="padding:12px"><div class="stat-value" style="font-size:22px">${fmt.minutes(s.etaMinutes, { zero: 'None' })}</div><div class="stat-label">estimated wait</div></div>
            <div class="card card-body" style="padding:12px"><div style="margin-top:2px">${SQ.crowdPill(s.crowdLevel)}</div><div class="stat-label" style="margin-top:6px">crowd level</div></div></div>
          ${state.me.user.isPriority ? SQ.html`<div class="alert tone-blue">${SQ.icon('shield-check', 18)}<div class="a-body">Your priority status (${state.me.user.priorityReason || 'priority'}) will be applied automatically.</div></div>` : ''}
          <div class="field"><label for="tkNote">Note for the staff <span class="muted">(optional)</span></label>
            <textarea class="textarea" id="tkNote" maxlength="255" rows="2" placeholder="e.g. I need help with a birth certificate"></textarea></div>
          <p class="tiny muted">You can only hold one active token at a time. You can cancel it whenever you like.</p>
        </div>`,
      actions: [
        { label: 'Not now', value: false },
        { label: 'Confirm and get token', kind: 'primary', icon: 'ticket', closeAfter: false, onClick: function () {
          var note = SQ.qs('#tkNote', ctl.el).value.trim();
          return SQ.post('user/tokens', { serviceId: serviceId, notes: note || null }).then(function (t) {
            SQ.store.set('sq_init_' + t.id, String(t.ahead || 0));
            state.outcome = null;
            ctl.close(true);
            SQ.ok('Your token is ' + t.tokenCode + '. We will keep you posted.', { title: 'Token issued' });
            if (state.notifier) state.notifier.refresh();
            return refresh();
          }, function (e) { SQ.err(e); refresh(); return false; });
        } }
      ]
    });
  }

  function cancelToken(id) {
    SQ.confirm({ title: 'Cancel this token?', message: 'You will lose your place in the queue. You can take a new token later, but it will be at the end of the line.', confirmLabel: 'Yes, cancel token', cancelLabel: 'Keep my token', tone: 'red' }).then(function (ok) {
      if (!ok) return;
      SQ.post('user/tokens/' + id + '/cancel').then(function () { SQ.ok('Your token was cancelled.'); state.outcome = null; return refresh(); }).catch(function (e) { SQ.err(e); refresh(); });
    });
  }

  function openRating(t) {
    var rating = 0;
    var ctl = SQ.modal({
      title: 'How was your visit?', icon: 'star', tone: 'amber', size: 'sm',
      body: SQ.html`<p class="text-2 mb">Token <b>${t.tokenCode}</b> · ${t.serviceName}${t.servedByName ? ' · served by ' + t.servedByName : ''}</p>
        <div class="center mb"><div class="rate-stars" data-stars>${[1, 2, 3, 4, 5].map(function (i) { return SQ.html`<button type="button" data-star="${i}" aria-label="${i} stars">${SQ.icon('star', 36)}</button>`; })}</div><div class="small muted mt" data-star-label>Tap a star to rate</div></div>
        <div class="field"><label for="rtComment">Comments <span class="muted">(optional)</span></label><textarea class="textarea" id="rtComment" maxlength="255" rows="2" placeholder="Tell us what went well or what we can improve"></textarea></div>`,
      actions: [
        { label: 'Maybe later', value: false },
        { label: 'Submit feedback', kind: 'primary', closeAfter: false, onClick: function () {
          if (!rating) { SQ.warn('Please choose a star rating first.'); return false; }
          return SQ.post('user/tokens/' + t.id + '/feedback', { rating: rating, comment: SQ.qs('#rtComment', ctl.el).value.trim() || null }).then(function () {
            SQ.ok('Thank you for your feedback!');
            if (state.outcome && state.outcome.id === t.id) state.outcome.rating = rating;
            ctl.close(true); renderHome(); if (state.tab === 'history') loadHistory();
          }, function (e) { SQ.err(e); return false; });
        } }
      ]
    });
    var labels = ['', 'Poor', 'Fair', 'Good', 'Very good', 'Excellent'];
    ctl.el.addEventListener('click', function (e) {
      var b = e.target.closest('[data-star]'); if (!b) return;
      rating = Number(b.getAttribute('data-star'));
      SQ.qsa('[data-star]', ctl.el).forEach(function (x) { var on = Number(x.getAttribute('data-star')) <= rating; x.classList.toggle('on', on); x.querySelector('svg').setAttribute('fill', on ? 'currentColor' : 'none'); });
      SQ.qs('[data-star-label]', ctl.el).textContent = labels[rating];
    });
  }

  /* ------------------------------------------------------------------ history */
  function loadHistory() {
    var h = state.hist;
    h.loading = true;
    return SQ.get('user/history' + SQ.qstr({ status: h.status, page: h.page, size: 10 })).then(function (d) { h.data = d; h.loading = false; renderHistory(); }, function (e) { h.loading = false; SQ.err(e); renderHistory(); });
  }

  function renderHistory() {
    if (state.tab !== 'history') return;
    var h = state.hist, d = h.data, v = SQ.qs('#view');
    var filters = [['', 'All'], ['COMPLETED', 'Completed'], ['CANCELLED', 'Cancelled'], ['SKIPPED', 'Skipped']];
    var rows = '';
    if (!d) rows = SQ.html`<div style="padding:16px">${[1, 2, 3, 4].map(function () { return SQ.html`<div class="skeleton" style="height:44px;margin-bottom:10px"></div>`; })}</div>`;
    else if (!d.items.length) rows = SQ.empty('history', 'No visits yet', h.status ? 'Nothing matches this filter.' : 'Your tokens will be listed here after your first visit.');
    else rows = SQ.html`<div class="table-wrap"><table class="table"><thead><tr><th>Token</th><th>Service</th><th>Date</th><th>Waited</th><th>Service time</th><th>Status</th><th>Rating</th><th></th></tr></thead><tbody>
      ${d.items.map(function (t) {
        var done = t.status === 'COMPLETED';
        return SQ.html`<tr style="cursor:pointer" data-detail="${t.id}"><td>${SQ.tokenChip(t.tokenCode, t.serviceColor)}</td><td>${t.serviceName}</td><td class="nowrap">${fmt.dateTime(t.createdAt)}</td>
          <td>${t.status === 'WAITING' ? '—' : fmt.minutes(t.waitedMinutes, { zero: '< 1 min' })}</td><td>${done ? fmt.minutes(t.serviceMinutes, { zero: '< 1 min' }) : '—'}</td>
          <td>${SQ.statusBadge(t.status)}</td><td>${done ? (t.rating ? SQ.stars(t.rating) : SQ.html`<button class="btn btn-sm btn-soft" data-rate="${t.id}">Rate</button>`) : '—'}</td>
          <td class="actions">${SQ.icon('chevron-right', 16)}</td></tr>`;
      })}</tbody></table></div>${SQ.pager(d, 'data-hpage')}`;
    SQ.setHtml(v, SQ.html`
      <div class="page-head"><div><h1>My visit history</h1><p>${d ? fmt.plural(d.total, 'visit') + ' in total' : 'Loading…'}</p></div>
        <div class="seg">${filters.map(function (f) { return SQ.html`<button class="${h.status === f[0] ? 'active' : ''}" data-hfilter="${f[0]}">${f[1]}</button>`; })}</div></div>
      <div class="card">${rows}</div>`);
  }

  function openDetail(id) {
    SQ.get('user/tokens/' + id).then(function (t) {
      var events = [['Token issued', t.createdAt, 'ticket']];
      if (t.calledAt) events.push(['Called to ' + (t.counterName || 'counter'), t.calledAt, 'megaphone']);
      if (t.servingAt) events.push(['Service started', t.servingAt, 'play']);
      if (t.endedAt) events.push([({ COMPLETED: 'Completed', SKIPPED: 'Skipped', CANCELLED: 'Cancelled' })[t.status] || 'Ended', t.endedAt, 'flag']);
      SQ.modal({
        title: 'Token ' + t.tokenCode, size: 'sm',
        body: SQ.html`<div class="stack">
          <div class="row">${SQ.serviceIcon({ color: t.serviceColor, icon: t.serviceIcon })}<div class="grow"><div class="bold">${t.serviceName}</div><div class="small muted">${fmt.dateTime(t.createdAt)}</div></div>${SQ.statusBadge(t.status)}</div>
          <dl class="kv"><dt>Counter</dt><dd>${t.counterName || '—'}</dd><dt>Served by</dt><dd>${t.servedByName || '—'}</dd>
            <dt>Waited</dt><dd>${t.status === 'WAITING' ? '—' : fmt.minutes(t.waitedMinutes, { zero: '< 1 min' })}</dd>
            <dt>Service time</dt><dd>${t.status === 'COMPLETED' ? fmt.minutes(t.serviceMinutes, { zero: '< 1 min' }) : '—'}</dd>
            ${t.priority ? SQ.html`<dt>Priority</dt><dd>${t.priorityReason || (t.priority === 2 ? 'Emergency' : 'Priority')}</dd>` : ''}
            ${t.closeReason ? SQ.html`<dt>Reason</dt><dd>${t.closeReason}</dd>` : ''}
            ${t.rating ? SQ.html`<dt>Your rating</dt><dd>${SQ.stars(t.rating)}</dd>` : ''}</dl>
          ${t.notes ? SQ.html`<div><div class="label">Note</div><p class="small text-2">${t.notes}</p></div>` : ''}
          <div class="stack sm">${events.map(function (e) { return SQ.html`<div class="row small"><span class="svc-ico sm" style="background:var(--surface-3);color:var(--muted)">${SQ.icon(e[2] === 'flag' ? 'check' : e[2], 15)}</span><span class="grow">${e[0]}</span><span class="muted">${fmt.time(e[1])}</span></div>`; })}</div>
        </div>`,
        actions: t.status === 'COMPLETED' && !t.rating ? [{ label: 'Close', value: false }, { label: 'Rate this visit', kind: 'primary', icon: 'star', value: true, onClick: function (ctl) { ctl.close(true); openRating(t); return false; } }] : [{ label: 'Close', kind: 'primary', value: true }]
      });
    }).catch(SQ.err);
  }

  /* ------------------------------------------------------------------ profile */
  function renderProfile() {
    if (state.tab !== 'profile') return;
    var u = state.me.user, v = SQ.qs('#view');
    SQ.setHtml(v, SQ.html`
      <div class="page-head"><div><h1>My profile</h1><p>Keep your details up to date so staff can reach you.</p></div></div>
      <div class="grid split-even">
        <div class="stack">
          <div class="card"><div class="card-body stack">
            <div class="row"><span class="avatar lg">${SQ.initials(u.fullName)}</span>
              <div class="grow"><h2>${u.fullName}</h2><div class="muted">${u.email}</div></div></div>
            <div class="row wrap"><span class="badge tone-blue">${SQ.icon('user', 12)} Customer</span>
              ${u.isPriority ? SQ.html`<span class="badge tone-purple">${SQ.icon('shield-check', 12)} Priority – ${u.priorityReason || 'priority access'}</span>` : ''}
              ${state.dash ? SQ.html`<span class="badge tone-gray">${fmt.plural(state.dash.totalVisits, 'visit')}</span>` : ''}</div>
          </div></div>
          <form id="profileForm" class="card" novalidate><div class="card-head"><div class="card-title">${SQ.icon('edit', 17)} Personal details</div></div>
            <div class="card-body stack">
              <div class="field"><label for="pName">Full name</label><input class="input" id="pName" name="fullName" value="${u.fullName}" required maxlength="100" autocomplete="name"></div>
              <div class="field"><label for="pPhone">Phone</label><input class="input" id="pPhone" name="phone" value="${u.phone || ''}" type="tel" maxlength="20" autocomplete="tel" placeholder="Optional"></div>
              <div class="field"><label>Email</label><input class="input" value="${u.email}" disabled><div class="hint">Your email is your sign-in name and cannot be changed.</div></div>
              <div data-error></div>
              <div><button class="btn btn-primary" type="submit">Save changes</button></div></div></form>
        </div>
        <form id="pwForm" class="card" novalidate><div class="card-head"><div class="card-title">${SQ.icon('lock', 17)} Change password</div></div>
          <div class="card-body stack">
            <div class="field"><label for="pwCur">Current password</label><input class="input" id="pwCur" name="cur" type="password" autocomplete="current-password" required></div>
            <div class="field"><label for="pwNew">New password</label><input class="input" id="pwNew" name="next" type="password" autocomplete="new-password" required><div class="hint">At least 8 characters with a letter and a number.</div></div>
            <div class="field"><label for="pwNew2">Confirm new password</label><input class="input" id="pwNew2" name="next2" type="password" autocomplete="new-password" required></div>
            <div data-error></div>
            <div><button class="btn btn-primary" type="submit">Update password</button></div></div></form>
      </div>`);
  }

  function formError(form, msg) { SQ.qs('[data-error]', form).innerHTML = msg ? SQ.html`<div class="alert tone-red">${SQ.icon('alert', 18)}<div class="a-body">${msg}</div></div>`.s : ''; }

  /* ------------------------------------------------------------------ events */
  app.addEventListener('click', function (e) {
    var t;
    if ((t = e.target.closest('[data-tab]'))) { setTab(t.getAttribute('data-tab')); return; }
    if ((t = e.target.closest('[data-take]'))) { takeToken(Number(t.getAttribute('data-take'))); return; }
    if ((t = e.target.closest('[data-cancel]'))) { cancelToken(Number(t.getAttribute('data-cancel'))); return; }
    if ((t = e.target.closest('[data-rate]'))) {
      e.stopPropagation();
      var id = Number(t.getAttribute('data-rate'));
      if (state.outcome && state.outcome.id === id) openRating(state.outcome);
      else SQ.get('user/tokens/' + id).then(openRating).catch(SQ.err);
      return;
    }
    if (e.target.closest('[data-dismiss-outcome]')) { if (state.outcome) SQ.store.set('sq_out_' + state.outcome.id, '1'); state.outcome = null; renderHome(); return; }
    if ((t = e.target.closest('[data-hfilter]'))) { state.hist.status = t.getAttribute('data-hfilter'); state.hist.page = 1; loadHistory(); return; }
    if ((t = e.target.closest('[data-hpage]'))) { state.hist.page = Number(t.getAttribute('data-hpage')); loadHistory(); window.scrollTo(0, 0); return; }
    if ((t = e.target.closest('[data-detail]'))) { openDetail(Number(t.getAttribute('data-detail'))); return; }
  });

  app.addEventListener('submit', function (e) {
    e.preventDefault();
    var form = e.target, btn = SQ.qs('button[type=submit]', form);
    if (form.id === 'profileForm') {
      var name = form.elements.fullName.value.trim(), phone = form.elements.phone.value.trim();
      if (name.length < 2) { formError(form, 'Please enter your full name.'); return; }
      formError(form, '');
      SQ.busy(btn, function () {
        return SQ.put('auth/profile', { fullName: name, phone: phone }).then(function (r) {
          state.me.user = r.user; SQ.ok('Profile updated.'); shellRefreshUser();
        }, function (er) { formError(form, er.message); });
      });
    } else if (form.id === 'pwForm') {
      var cur = form.elements.cur.value, nw = form.elements.next.value, n2 = form.elements.next2.value;
      if (!cur) { formError(form, 'Enter your current password.'); return; }
      if (nw.length < 8 || !/[A-Za-z]/.test(nw) || !/\d/.test(nw)) { formError(form, 'The new password needs at least 8 characters, a letter and a number.'); return; }
      if (nw !== n2) { formError(form, 'The two new passwords do not match.'); return; }
      formError(form, '');
      SQ.busy(btn, function () {
        return SQ.post('auth/change-password', { currentPassword: cur, newPassword: nw }).then(function () {
          form.reset(); SQ.ok('Password updated. Other devices were signed out.');
        }, function (er) { formError(form, er.message); });
      });
    }
  });

  function shellRefreshUser() {
    var u = state.me.user, dd = SQ.qs('.topbar .dropdown:last-child');
    if (dd) dd.outerHTML = SQ.userMenu(u).s;
    renderProfile();
  }

  /* ------------------------------------------------------------------ boot */
  Promise.all([SQ.requireRole(['USER']), SQ.loadConfig()]).then(function (r) {
    state.me = r[0]; state.config = r[1];
    var h = location.hash.replace('#', '');
    if (h === 'history' || h === 'profile') state.tab = h;
    shell();
    // after a completed visit that was not rated yet, offer the rating card
    SQ.get('user/history?size=1').then(function (d) {
      var t = d.items[0];
      if (t && t.status === 'COMPLETED' && !t.rating && !SQ.store.get('sq_out_' + t.id) && Date.now() - Date.parse(t.endedAt) < 6 * 3600 * 1000 && !(state.dash && state.dash.activeToken)) {
        state.outcome = t; renderHome();
      }
    }).catch(function () { });
    SQ.every(refresh, 3000);
  }).catch(function (e) { if (e && e.status !== 401) { app.innerHTML = SQ.html`<div class="page"><div class="alert tone-red">${SQ.icon('alert', 20)}<div class="a-body">${e.message}</div></div></div>`.s; } });
})();
