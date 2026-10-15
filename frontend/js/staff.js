/* SmartQueue - counter staff console */
(function () {
  'use strict';
  var fmt = SQ.fmt;
  var app = SQ.qs('#app');
  var state = { me: null, ov: null, tab: 'queue', log: null, notes: {}, dirty: false, lastWaiting: null };

  /* ------------------------------------------------------------------ shell */
  function shell() {
    var u = state.me.user;
    app.innerHTML = SQ.html`
      <header class="topbar"><div class="topbar-inner">
        ${SQ.brand(state.me.orgName, '/staff.html')}
        <div id="counterChip" class="hide-sm" style="margin-left:14px"></div>
        <div class="spacer"></div>
        ${SQ.connBadge()}
        <a class="icon-btn" href="/display.html" target="_blank" rel="noopener" title="Open the lobby display board" aria-label="Open lobby display">${SQ.icon('monitor', 18)}</a>
        <div id="bell"></div>
        ${SQ.themeButton()}
        ${SQ.userMenu(u)}
      </div></header>
      <main class="page" id="view"><div class="grid grid-4">${[1, 2, 3, 4].map(function () { return SQ.html`<div class="skeleton" style="height:84px"></div>`; })}</div></main>`.s;
    SQ.notifier(SQ.qs('#bell'), { silent: true, interval: 8000 });
  }

  /* ------------------------------------------------------------------ helpers */
  function typing() {
    var a = document.activeElement;
    return !!(a && a.closest && a.closest('#view') && /^(INPUT|TEXTAREA|SELECT)$/.test(a.tagName));
  }
  function menuOpen() { return !!SQ.qs('#view .dropdown.open'); }

  function statCard(icon, tone, value, label, sub) {
    return SQ.html`<div class="card stat tone-${tone}"><span class="stat-icon">${SQ.icon(icon, 22)}</span><div><div class="stat-value">${value}</div><div class="stat-label">${label}</div>${sub ? SQ.html`<div class="stat-sub">${sub}</div>` : ''}</div></div>`;
  }

  function chip() {
    var el = SQ.qs('#counterChip'); if (!el) return;
    var c = state.ov && state.ov.counter;
    if (!c) { SQ.setHtml(el, SQ.html`<span class="badge tone-gray">No counter open</span>`); return; }
    SQ.setHtml(el, SQ.html`<span class="row" style="gap:8px">${SQ.tokenChip(c.name, c.serviceColor)}<span class="small bold">${c.serviceName}</span>
      <span class="badge tone-${c.status === 'OPEN' ? 'green' : 'amber'} dot ${c.status === 'OPEN' ? 'pulse' : ''}">${c.status === 'OPEN' ? 'Open' : 'On break'}</span></span>`);
  }

  /* ------------------------------------------------------------------ views */
  function closedView(ov) {
    var t = ov.today;
    return SQ.html`
      <div class="page-head"><div><h1>Hello, ${state.me.user.fullName.split(' ')[0]}</h1><p>Open a counter to start calling customers.</p></div></div>
      <div class="grid grid-4 mb">
        ${statCard('check-circle', 'green', t.served, 'Served today')}
        ${statCard('skip', 'red', t.skipped, 'Skipped today')}
        ${statCard('clock', 'blue', fmt.minutes(t.avgServiceMinutes), 'Avg. service time')}
        ${statCard('star', 'amber', t.avgRating ? fmt.dec(t.avgRating, 1) : '—', 'Average rating')}
      </div>
      <div class="row spread mb"><h2>Available counters</h2><span class="muted small">${fmt.plural(ov.availableCounters.length, 'counter')} free</span></div>
      ${ov.availableCounters.length ? SQ.html`<div class="grid auto-grid">${ov.availableCounters.map(function (c) {
        var col = SQ.safeColor(c.serviceColor);
        return SQ.html`<div class="card counter-card" style="--c:${col}">
          <div class="row spread"><div class="row">${SQ.icon('counter', 22)}<h3>${c.name}</h3></div><span class="badge tone-gray">Closed</span></div>
          <div class="row"><i style="width:10px;height:10px;border-radius:3px;background:${col}"></i><span class="small bold">${c.serviceName}</span></div>
          <div class="small muted">${fmt.plural(c.waiting, 'customer')} waiting · ${c.countersOpen} counter${c.countersOpen === 1 ? '' : 's'} open</div>
          <button class="btn btn-primary" data-open-counter="${c.id}">${SQ.icon('login', 16)} Open this counter</button></div>`;
      })}</div>` : SQ.html`<div class="card">${SQ.empty('counter', 'No free counters', 'All counters are open or none has been created yet. Ask the administrator to add one.')}</div>`}
      ${ov.recent.length ? SQ.html`<div class="card mt-lg"><div class="card-head"><div class="card-title">${SQ.icon('history', 17)} Your recent customers</div></div><div class="card-body flush">${recentRows(ov.recent)}</div></div>` : ''}`;
  }

  function recentRows(list) {
    return SQ.html`<div class="table-wrap"><table class="table"><thead><tr><th>Token</th><th>Customer</th><th>Status</th><th>Ended</th></tr></thead><tbody>${list.map(function (t) {
      return SQ.html`<tr><td>${SQ.tokenChip(t.tokenCode, t.serviceColor)}</td><td>${t.customerName}</td><td>${SQ.statusBadge(t.status)}</td><td>${fmt.time(t.endedAt)}</td></tr>`;
    })}</tbody></table></div>`;
  }

  function currentPanel(ov) {
    var cur = ov.current, c = ov.counter, col = SQ.safeColor(c.serviceColor);
    var next = ov.queue[0];
    if (!cur) {
      var onBreak = c.status === 'BREAK';
      return SQ.html`<div class="current-panel"><div class="idle-panel">
        <div class="big-ico">${SQ.icon(onBreak ? 'coffee' : 'megaphone', 40)}</div>
        <h2>${onBreak ? 'Counter is on break' : 'Ready for the next customer'}</h2>
        ${onBreak ? SQ.html`<p class="muted mt">Resume to start calling customers again.</p><div class="mt-lg"><button class="btn btn-primary btn-lg" data-break="false">${SQ.icon('play', 18)} Resume counter</button></div>`
        : (next ? SQ.html`<p class="muted mt">Up next: <b class="text-2">${next.tokenCode}</b> · ${next.customerName} ${next.priority ? SQ.priorityBadge(next.priority, next.priorityReason) : ''}</p>
            <div class="mt-lg"><button class="btn btn-primary btn-xl" data-call-next>${SQ.icon('megaphone', 22)} Call next customer <kbd style="margin-left:6px">N</kbd></button></div>
            <p class="small muted mt">${fmt.plural(ov.queue.length, 'customer')} waiting</p>`
          : SQ.html`<p class="muted mt">No one is waiting in the ${c.serviceName} queue right now.</p><div class="mt-lg"><button class="btn btn-lg" disabled>${SQ.icon('megaphone', 20)} Call next customer</button></div>`)}
      </div></div>`;
    }
    var called = cur.status === 'CALLED';
    var startIso = new Date(Date.now() - 0).toISOString();
    if (!called && cur.servingAt) startIso = new Date(Date.parse(cur.servingAt) - (cur.serviceSeconds || 0) * 1000).toISOString();
    var noteVal = state.notes[cur.id] || '';
    return SQ.html`<div class="current-panel">
      <div class="cp-head" style="--c:${col}">
        <div class="row spread wrap"><span class="status-chip">${SQ.icon(called ? 'megaphone' : 'play', 14)} ${called ? 'Called – waiting for the customer' : 'Being served'}</span>
          <span class="small" style="opacity:.9">${c.name}</span></div>
        <div class="row spread wrap" style="margin-top:8px;align-items:flex-end">
          <div><div class="cp-code">${cur.tokenCode}</div><div style="font-size:18px;font-weight:650;margin-top:4px">${cur.customerName}</div></div>
          ${called ? SQ.html`<div style="text-align:right"><div class="tiny" style="opacity:.85;text-transform:uppercase;letter-spacing:.08em">Auto-skip in</div><div class="cp-timer" data-countdown="${cur.noShowDeadline || ''}">--:--</div></div>`
          : SQ.html`<div style="text-align:right"><div class="tiny" style="opacity:.85;text-transform:uppercase;letter-spacing:.08em">Service time</div><div class="cp-timer" data-elapsed="${startIso}">0:00</div></div>`}
        </div>
      </div>
      <div class="cp-body stack">
        <div class="row wrap">${SQ.priorityBadge(cur.priority, cur.priorityReason)} ${SQ.sourceBadge(cur.source)} ${cur.callCount > 1 ? SQ.html`<span class="badge tone-orange">Called ${cur.callCount} times</span>` : ''}</div>
        <dl class="kv"><dt>Phone</dt><dd>${cur.customerPhone || '—'}</dd><dt>Issued</dt><dd>${fmt.time(cur.createdAt)} (waited ${fmt.minutes(cur.waitedMinutes, { zero: '< 1 min' })})</dd>
          ${cur.priority ? SQ.html`<dt>Priority</dt><dd>${cur.priorityReason || (cur.priority === 2 ? 'Emergency' : 'Priority')}</dd>` : ''}
          ${cur.notes && called ? SQ.html`<dt>Customer note</dt><dd>${cur.notes}</dd>` : ''}</dl>
        ${called ? SQ.html`<div class="cp-actions">
            <button class="btn btn-success btn-lg" data-start="${cur.id}">${SQ.icon('play', 18)} Start serving <kbd>S</kbd></button>
            <button class="btn btn-lg" data-recall="${cur.id}">${SQ.icon('megaphone', 18)} Recall <kbd>R</kbd></button>
            <button class="btn btn-lg" data-hold="${cur.id}">${SQ.icon('pause', 18)} Hold <kbd>H</kbd></button>
            <button class="btn btn-lg btn-outline-danger" data-skip="${cur.id}">${SQ.icon('skip', 18)} Skip <kbd>K</kbd></button></div>`
        : SQ.html`<div class="field"><label for="sNote">Session notes <span class="muted">(optional, saved with the token)</span></label>
            <textarea class="textarea" id="sNote" data-note="${cur.id}" rows="2" maxlength="255" placeholder="e.g. Documents verified, certificate issued">${noteVal || (cur.notes || '')}</textarea></div>
          <div class="cp-actions">
            <button class="btn btn-success btn-lg" data-complete="${cur.id}">${SQ.icon('check-circle', 18)} Complete <kbd>C</kbd></button>
            <button class="btn btn-lg" data-hold="${cur.id}">${SQ.icon('pause', 18)} Hold <kbd>H</kbd></button>
            <button class="btn btn-lg btn-outline-danger" data-cancel-token="${cur.id}" data-code="${cur.tokenCode}">${SQ.icon('x-circle', 18)} Cancel</button></div>`}
      </div></div>`;
  }

  function queueRows(ov) {
    if (!ov.queue.length) return SQ.empty('users', 'The queue is empty', 'New customers will appear here as soon as they take a token.');
    var canCall = !ov.current && ov.counter.status === 'OPEN';
    return SQ.html`${ov.queue.map(function (q, i) {
      return SQ.html`<div class="qrow ${i === 0 ? 'first' : ''}">
        <span class="pos">${q.position}</span>
        <div class="grow"><div class="row wrap" style="gap:8px"><span class="tok">${q.tokenCode}</span>${SQ.priorityBadge(q.priority, q.priorityReason)}${SQ.sourceBadge(q.source)}</div>
          <div class="small text-2 truncate">${q.customerName}${q.notes ? SQ.html` · <span class="muted">${q.notes}</span>` : ''}</div>
          <div class="tiny muted">Waiting ${fmt.minutes(q.waitedMinutes, { zero: '< 1 min' })} · ETA ${i === 0 ? 'next' : fmt.minutes(q.etaMinutes, { zero: 'now' })}</div></div>
        <button class="btn btn-sm btn-soft" data-call="${q.id}" ${canCall ? '' : SQ.raw('disabled')} title="Call this customer now">${SQ.icon('megaphone', 14)} Call</button>
        <div class="dropdown"><button class="icon-btn" style="width:32px;height:32px" data-dd-toggle aria-label="More actions">${SQ.icon('more', 16)}</button>
          <div class="popover" hidden><div class="menu">
            <button class="menu-item" data-move="front" data-id="${q.id}" ${i === 0 ? SQ.raw('disabled') : ''}>${SQ.icon('to-front', 16)} Move to front</button>
            <button class="menu-item" data-move="back" data-id="${q.id}" ${i === ov.queue.length - 1 ? SQ.raw('disabled') : ''}>${SQ.icon('to-back', 16)} Move to back</button>
            <button class="menu-item" data-priority="${q.id}" data-p="${q.priority}" data-code="${q.tokenCode}">${SQ.icon('arrow-up', 16)} Change priority…</button>
            <div class="menu-sep"></div>
            <button class="menu-item danger" data-cancel-token="${q.id}" data-code="${q.tokenCode}">${SQ.icon('x-circle', 16)} Cancel token…</button>
          </div></div></div>
      </div>`;
    })}`;
  }

  function holdRows(ov) {
    if (!ov.onHold.length) return SQ.empty('pause', 'Nobody on hold', 'Customers you put on hold will be listed here.');
    var canCall = !ov.current && ov.counter.status === 'OPEN';
    return SQ.html`${ov.onHold.map(function (h) {
      return SQ.html`<div class="qrow"><span class="pos" style="background:var(--orange-soft);color:var(--orange)">${SQ.icon('pause', 14)}</span>
        <div class="grow"><div class="row" style="gap:8px"><span class="tok">${h.tokenCode}</span>${SQ.priorityBadge(h.priority)}</div><div class="small text-2">${h.customerName}</div></div>
        <button class="btn btn-sm btn-soft" data-call="${h.id}" ${canCall ? '' : SQ.raw('disabled')}>${SQ.icon('megaphone', 14)} Call now</button>
        <button class="btn btn-sm" data-resume="${h.id}">${SQ.icon('recall', 14)} Back to queue</button></div>`;
    })}`;
  }

  function skippedRows(ov) {
    if (!ov.skipped.length) return SQ.empty('check-circle', 'No skipped customers', 'No-shows today will appear here so you can restore them.');
    return SQ.html`${ov.skipped.map(function (s) {
      return SQ.html`<div class="qrow"><span class="pos" style="background:var(--danger-soft);color:var(--danger)">${SQ.icon('skip', 14)}</span>
        <div class="grow"><div class="row" style="gap:8px"><span class="tok">${s.tokenCode}</span>${SQ.priorityBadge(s.priority)}</div>
          <div class="small text-2">${s.customerName}</div><div class="tiny muted">${s.closeReason || 'Skipped'} · ${fmt.time(s.endedAt)}</div></div>
        <button class="btn btn-sm" data-requeue="${s.id}">${SQ.icon('recall', 14)} Restore to end of queue</button></div>`;
    })}`;
  }

  function logRows() {
    var d = state.log;
    if (!d) return SQ.html`<div style="padding:16px">${[1, 2, 3].map(function () { return SQ.html`<div class="skeleton" style="height:40px;margin-bottom:8px"></div>`; })}</div>`;
    if (!d.items.length) return SQ.empty('history', 'Nothing yet today', 'Customers you serve will be listed here.');
    return SQ.html`<div class="table-wrap" style="max-height:520px;overflow-y:auto"><table class="table table-compact"><thead><tr><th>Token</th><th>Customer</th><th>Status</th><th>Time</th></tr></thead><tbody>
      ${d.items.slice(0, 60).map(function (t) {
        var meta = [];
        if (t.status === 'COMPLETED') meta.push(fmt.minutes(t.serviceMinutes, { zero: '< 1 min' }));
        return SQ.html`<tr><td>${SQ.tokenChip(t.tokenCode, t.serviceColor)}</td>
          <td><div class="bold">${t.customerName}</div>${meta.length || t.rating ? SQ.html`<div class="tiny muted row" style="gap:6px">${meta.join(' · ')} ${t.rating ? SQ.stars(t.rating, 11) : ''}</div>` : ''}</td>
          <td>${SQ.statusBadge(t.status)}</td><td class="nowrap">${fmt.time(t.endedAt || t.calledAt)}</td></tr>`;
      })}</tbody></table></div>`;
  }

  function openView(ov) {
    var c = ov.counter, t = ov.today, svc = ov.serviceInfo;
    var tabs = [['queue', 'Waiting', ov.queue.length], ['hold', 'On hold', ov.onHold.length], ['skipped', 'Skipped', ov.skipped.length], ['log', 'Today', null]];
    var body = state.tab === 'queue' ? queueRows(ov) : state.tab === 'hold' ? holdRows(ov) : state.tab === 'skipped' ? skippedRows(ov) : logRows();
    return SQ.html`
      <div class="page-head"><div><h1>${c.name} <span class="muted" style="font-weight:500">· ${c.serviceName}</span></h1>
        <p>Open since ${fmt.time(c.openedAt)} · ${svc ? SQ.html`${SQ.crowdPill(svc.crowdLevel)}` : ''}</p></div>
        <div class="row wrap">
          <button class="btn" data-walkin>${SQ.icon('user-plus', 16)} Add walk-in</button>
          ${c.status === 'OPEN' ? SQ.html`<button class="btn" data-break="true">${SQ.icon('coffee', 16)} Take a break</button>` : SQ.html`<button class="btn btn-primary" data-break="false">${SQ.icon('play', 16)} Resume</button>`}
          <button class="btn btn-outline-danger" data-close-counter>${SQ.icon('logout', 16)} Close counter</button></div></div>
      ${c.status === 'BREAK' ? SQ.html`<div class="alert tone-amber mb">${SQ.icon('coffee', 20)}<div class="a-body"><b>You are on a break.</b> Customers keep waiting in line, but you will not be assigned new ones until you resume.</div></div>` : ''}
      <div class="grid grid-4 mb">
        ${statCard('users', 'amber', ov.queue.length, 'Waiting now', svc ? 'ETA for a new customer: ' + fmt.minutes(svc.etaMinutes, { zero: 'none' }) : '')}
        ${statCard('check-circle', 'green', t.served, 'Served today', t.skipped ? t.skipped + ' skipped' : '')}
        ${statCard('clock', 'blue', fmt.minutes(t.avgServiceMinutes), 'Avg. service time')}
        ${statCard('star', 'purple', t.avgRating ? fmt.dec(t.avgRating, 1) : '—', 'Average rating')}
      </div>
      <div class="grid split split-staff">
        <div>${currentPanel(ov)}</div>
        <div class="card"><div class="card-head" style="padding-bottom:0"><div class="tabs" style="width:100%;border-bottom:0">
          ${tabs.map(function (tb) { return SQ.html`<button class="tab ${state.tab === tb[0] ? 'active' : ''}" data-qtab="${tb[0]}">${tb[1]}${tb[2] !== null ? SQ.html` <span class="count">${tb[2]}</span>` : ''}</button>`; })}</div></div>
          <div class="card-body flush" style="margin-top:8px;border-top:1px solid var(--border)">${body}</div></div>
      </div>
      <p class="tiny muted center mt-lg">Shortcuts: <kbd>N</kbd> call next · <kbd>S</kbd> start · <kbd>C</kbd> complete · <kbd>R</kbd> recall · <kbd>H</kbd> hold · <kbd>K</kbd> skip</p>`;
  }

  function render(force) {
    var ov = state.ov; if (!ov) return;
    chip();
    if (!force && (typing() || menuOpen())) { state.dirty = true; return; }
    state.dirty = false;
    SQ.setHtml(SQ.qs('#view'), ov.counter ? openView(ov) : closedView(ov));
    SQ.tick();
    var w = ov.counter ? ov.queue.length : 0;
    document.title = (ov.counter ? '(' + w + ') ' + ov.counter.name : 'Counter console') + ' – SmartQueue';
  }

  function refresh() {
    return SQ.get('staff/overview').then(function (ov) {
      var prevW = state.lastWaiting;
      state.ov = ov;
      var w = ov.counter ? ov.queue.length : 0;
      if (ov.counter && !ov.current && prevW !== null && w > prevW) SQ.sound.chime('soft');
      state.lastWaiting = w;
      render(false);
      if (state.tab === 'log' && ov.counter) loadLog(true);
    });
  }

  function loadLog(quiet) {
    return SQ.get('staff/history' + SQ.qstr({ date: SQ.todayIso() })).then(function (d) { state.log = d; if (state.tab === 'log') render(false); }, function (e) { if (!quiet) SQ.err(e); });
  }

  /* ------------------------------------------------------------------ actions */
  function run(btn, path, body, okMsg) {
    return SQ.busy(btn, function () {
      return SQ.post(path, body || {}).then(function (r) { if (okMsg) SQ.ok(okMsg(r)); return refresh().then(function () { return r; }); }, function (e) { SQ.err(e); return refresh().then(function () { return null; }); });
    });
  }

  function currentId() { return state.ov && state.ov.current ? state.ov.current.id : null; }

  function callNext(btn) { return run(btn || SQ.qs('[data-call-next]') || document.createElement('button'), 'staff/call-next', {}, function (t) { SQ.sound.chime('soft'); return 'Calling ' + t.tokenCode + ' (' + t.customerName + ')'; }); }

  function priorityDialog(id, code, current) {
    SQ.form({
      title: 'Change priority – ' + code, icon: 'arrow-up', tone: 'blue', size: 'sm', submitLabel: 'Apply',
      initial: { priority: String(current), reason: '' },
      intro: 'Priority tokens are served before normal tokens. Emergency tokens are served first.',
      fields: [
        { name: 'priority', label: 'Priority level', type: 'select', full: true, options: [{ value: '0', label: 'Normal' }, { value: '1', label: 'Priority (senior, disabled, etc.)' }, { value: '2', label: 'Emergency' }] },
        { name: 'reason', label: 'Reason', type: 'text', full: true, maxlength: 100, placeholder: 'e.g. Senior citizen', show: function (v) { return v.priority !== '0'; } }
      ],
      onSubmit: function (v) { return SQ.post('staff/tokens/' + id + '/priority', { priority: Number(v.priority), reason: v.reason || null }).then(function () { SQ.ok('Priority updated for ' + code + '.'); return refresh(); }); }
    });
  }

  function cancelDialog(id, code) {
    SQ.form({
      title: 'Cancel token ' + code + '?', icon: 'alert', tone: 'red', size: 'sm', submitLabel: 'Cancel token', kind: 'danger', cancelLabel: 'Keep it',
      intro: 'The customer will be notified. This cannot be undone.',
      fields: [{ name: 'reason', label: 'Reason (optional)', type: 'text', full: true, maxlength: 100, placeholder: 'e.g. Customer left the premises' }],
      onSubmit: function (v) { return SQ.post('staff/tokens/' + id + '/cancel', { reason: v.reason || null }).then(function () { SQ.ok('Token ' + code + ' was cancelled.'); return refresh(); }); }
    });
  }

  function walkInDialog() {
    var ov = state.ov;
    SQ.form({
      title: 'Add a walk-in customer', icon: 'user-plus', tone: 'teal', submitLabel: 'Issue token',
      initial: { serviceId: String(ov.counter.serviceId), priority: '0' },
      intro: 'For customers at the desk without a smartphone. A token is created and added to the queue.',
      fields: [
        { name: 'serviceId', label: 'Service', type: 'select', required: true, full: true, options: ov.services.map(function (s) { return { value: String(s.id), label: s.name + ' (' + s.prefix + ') – ' + s.waiting + ' waiting' }; }) },
        { name: 'customerName', label: 'Customer name', type: 'text', maxlength: 100, placeholder: 'Walk-in guest' },
        { name: 'phone', label: 'Phone', type: 'tel', maxlength: 20, placeholder: 'Optional' },
        { name: 'priority', label: 'Priority', type: 'select', options: [{ value: '0', label: 'Normal' }, { value: '1', label: 'Priority' }, { value: '2', label: 'Emergency' }] },
        { name: 'reason', label: 'Reason', type: 'text', maxlength: 100, placeholder: 'e.g. Senior citizen', show: function (v) { return v.priority !== '0'; } },
        { name: 'notes', label: 'Notes', type: 'text', full: true, maxlength: 255, placeholder: 'Optional' }
      ],
      onSubmit: function (v) {
        return SQ.post('staff/walk-in', { serviceId: Number(v.serviceId), customerName: v.customerName, phone: v.phone, priority: Number(v.priority), reason: v.reason || null, notes: v.notes || null }).then(function (t) {
          SQ.ok('Token ' + t.tokenCode + ' issued to ' + t.customerName + (t.position ? ' (position #' + t.position + ')' : '') + '.', { title: 'Walk-in added' });
          return refresh();
        });
      }
    });
  }

  /* ------------------------------------------------------------------ events */
  app.addEventListener('click', function (e) {
    var t;
    if ((t = e.target.closest('[data-open-counter]'))) { run(t, 'staff/counter/open', { counterId: Number(t.getAttribute('data-open-counter')) }, function () { return 'Counter opened. You are now live.'; }); return; }
    if ((t = e.target.closest('[data-close-counter]'))) {
      SQ.confirm({ title: 'Close your counter?', message: 'Make sure no customer is currently called or being served. Customers waiting in line stay in the queue for other counters.', confirmLabel: 'Close counter', tone: 'red' }).then(function (ok) { if (ok) run(t, 'staff/counter/close', {}, function () { return 'Counter closed. See you next time!'; }); });
      return;
    }
    if ((t = e.target.closest('[data-break]'))) { var on = t.getAttribute('data-break') === 'true'; run(t, 'staff/counter/break', { onBreak: on }, function () { return on ? 'You are on a break.' : 'Welcome back!'; }); return; }
    if ((t = e.target.closest('[data-call-next]'))) { callNext(t); return; }
    if ((t = e.target.closest('[data-call]'))) { var cid = Number(t.getAttribute('data-call')); run(t, 'staff/tokens/' + cid + '/call', {}, function (r) { SQ.sound.chime('soft'); return 'Calling ' + r.tokenCode + ' (' + r.customerName + ')'; }); return; }
    if ((t = e.target.closest('[data-start]'))) { run(t, 'staff/tokens/' + t.getAttribute('data-start') + '/start', {}, function (r) { return 'Serving ' + r.tokenCode; }); return; }
    if ((t = e.target.closest('[data-recall]'))) { run(t, 'staff/tokens/' + t.getAttribute('data-recall') + '/recall', {}, function (r) { SQ.sound.chime('soft'); return 'Reminder sent to ' + r.customerName; }); return; }
    if ((t = e.target.closest('[data-hold]'))) { run(t, 'staff/tokens/' + t.getAttribute('data-hold') + '/hold', {}, function (r) { return r.tokenCode + ' is on hold.'; }); return; }
    if ((t = e.target.closest('[data-skip]'))) {
      var sid = t.getAttribute('data-skip');
      SQ.confirm({ title: 'Skip this customer?', message: 'Use this when the customer did not come to the counter. You can restore the token later from the Skipped list.', confirmLabel: 'Skip customer', tone: 'red' }).then(function (ok) { if (ok) run(t, 'staff/tokens/' + sid + '/skip', {}, function (r) { return r.tokenCode + ' was skipped.'; }); });
      return;
    }
    if ((t = e.target.closest('[data-complete]'))) {
      var id = Number(t.getAttribute('data-complete'));
      var note = (SQ.qs('#sNote') || {}).value || '';
      run(t, 'staff/tokens/' + id + '/complete', { notes: note.trim() || null }, function (r) { delete state.notes[id]; return r.tokenCode + ' completed. Great work!'; });
      return;
    }
    if ((t = e.target.closest('[data-cancel-token]'))) { cancelDialog(Number(t.getAttribute('data-cancel-token')), t.getAttribute('data-code')); return; }
    if ((t = e.target.closest('[data-priority]'))) { priorityDialog(Number(t.getAttribute('data-priority')), t.getAttribute('data-code'), Number(t.getAttribute('data-p'))); return; }
    if ((t = e.target.closest('[data-move]'))) { if (!t.disabled) run(t, 'staff/tokens/' + t.getAttribute('data-id') + '/move', { position: t.getAttribute('data-move') }, function () { return 'Queue order updated.'; }); return; }
    if ((t = e.target.closest('[data-resume]'))) { run(t, 'staff/tokens/' + t.getAttribute('data-resume') + '/resume', {}, function (r) { return r.tokenCode + ' is back in the queue.'; }); return; }
    if ((t = e.target.closest('[data-requeue]'))) { run(t, 'staff/tokens/' + t.getAttribute('data-requeue') + '/requeue', {}, function (r) { return r.tokenCode + ' restored to the end of the queue.'; }); return; }
    if (e.target.closest('[data-walkin]')) { walkInDialog(); return; }
    if ((t = e.target.closest('[data-qtab]'))) { state.tab = t.getAttribute('data-qtab'); if (state.tab === 'log') { state.log = null; loadLog(); } render(true); return; }
  });
  app.addEventListener('input', function (e) {
    var n = e.target.closest('[data-note]');
    if (n) state.notes[n.getAttribute('data-note')] = n.value;
  });
  app.addEventListener('focusout', function () { setTimeout(function () { if (state.dirty && !typing() && !menuOpen()) render(false); }, 150); });
  document.addEventListener('click', function () { setTimeout(function () { if (state.dirty && !menuOpen() && !typing()) render(false); }, 50); });

  document.addEventListener('keydown', function (e) {
    if (e.ctrlKey || e.metaKey || e.altKey || SQ.modalOpen()) return;
    var a = document.activeElement;
    if (a && /^(INPUT|TEXTAREA|SELECT)$/.test(a.tagName)) return;
    var ov = state.ov; if (!ov || !ov.counter) return;
    var k = e.key.toLowerCase(), cur = ov.current;
    var click = function (sel) { var b = SQ.qs(sel); if (b && !b.disabled) { e.preventDefault(); b.click(); } };
    if (k === 'n') click('[data-call-next]');
    else if (cur && cur.status === 'CALLED') { if (k === 's') click('[data-start]'); else if (k === 'r') click('[data-recall]'); else if (k === 'h') click('[data-hold]'); else if (k === 'k') click('[data-skip]'); }
    else if (cur && cur.status === 'SERVING') { if (k === 'c') click('[data-complete]'); else if (k === 'h') click('[data-hold]'); }
  });

  /* ------------------------------------------------------------------ boot */
  SQ.requireRole(['STAFF']).then(function (me) {
    state.me = me;
    shell();
    SQ.every(refresh, 3000);
  }).catch(function (e) { if (e && e.status !== 401) app.innerHTML = SQ.html`<div class="page"><div class="alert tone-red">${SQ.icon('alert', 20)}<div class="a-body">${e.message}</div></div></div>`.s; });
})();
