/* SmartQueue - admin: dashboard, live queues, optimisation */
(function () {
  'use strict';
  var fmt = SQ.fmt, C = SQ.charts;

  function counterBadge(status) {
    var m = { OPEN: ['green', 'Open'], BREAK: ['amber', 'On break'], CLOSED: ['gray', 'Closed'] }[status] || ['gray', status];
    return SQ.html`<span class="badge tone-${m[0]} ${status === 'OPEN' ? 'dot pulse' : ''}">${m[1]}</span>`;
  }

  function counterTile(c) {
    var col = SQ.safeColor(c.serviceColor);
    var closed = c.status === 'CLOSED';
    return SQ.html`<div class="card counter-card ${closed ? 'closed' : ''}" style="--c:${col}">
      <div class="row spread"><b class="cc-name">${c.name}</b>${counterBadge(c.status)}</div>
      <div class="row small"><i style="width:9px;height:9px;border-radius:3px;background:${col}"></i><span class="text-2">${c.serviceName}</span></div>
      <div class="row small spread" style="min-height:28px">
        ${c.staffName ? SQ.html`<span class="row" style="gap:7px"><span class="avatar sm">${SQ.initials(c.staffName)}</span><span class="truncate">${c.staffName}</span></span>` : SQ.html`<span class="muted">No staff</span>`}
        ${c.currentCode ? SQ.html`<span class="row" style="gap:6px">${SQ.tokenChip(c.currentCode, col)}${SQ.statusBadge(c.currentStatus)}</span>` : (c.status === 'OPEN' ? SQ.html`<span class="muted">Idle</span>` : '')}
      </div></div>`;
  }

  function serviceTile(s) {
    var col = SQ.safeColor(s.color);
    var pct = s.todayTotal ? Math.round((s.servedToday / s.todayTotal) * 100) : 0;
    return SQ.html`<div class="card" style="padding:16px;border-top:4px solid ${col}">
      <div class="row">${SQ.serviceIcon(s)}<div class="grow"><div class="bold">${s.name}</div><div class="tiny muted">Prefix ${s.prefix} · avg ${fmt.minutes(s.avgServiceMinutes)} / customer</div></div>${SQ.crowdPill(s.crowdLevel)}</div>
      <div class="svc-metrics" style="margin-top:12px">
        <div><div class="v">${s.waiting}</div><div class="l">Waiting</div></div>
        <div><div class="v">${s.noCounters ? '—' : fmt.minutes(s.etaMinutes, { zero: 'None' })}</div><div class="l">Est. wait</div></div>
        <div><div class="v">${s.countersOpen}<span class="muted" style="font-weight:600">/${s.countersTotal}</span></div><div class="l">Counters</div></div></div>
      <div class="row spread tiny muted" style="margin:10px 0 5px"><span>Served today ${s.servedToday} of ${s.todayTotal}</span><span>${pct}%</span></div>
      <div class="progress" style="--c:${col}"><i style="width:${pct}%"></i></div></div>`;
  }

  /* ================================================================ Dashboard */
  SQA.register('dashboard', {
    title: 'Dashboard', icon: 'dashboard',
    mount: function (view) {
      var last = null, cfg = null;
      function draw(d) {
        var c = d.cards, crowd = d.crowd;
        var hours = d.hourlyToday, first = 24, lastH = -1;
        hours.forEach(function (v, h) { if (v > 0) { first = Math.min(first, h); lastH = Math.max(lastH, h); } });
        var from = Math.min(8, first === 24 ? 8 : first), to = Math.max(19, lastH + 1);
        var hrs = []; for (var h = from; h <= Math.min(23, to); h++) hrs.push(h);
        var nowH = new Date(SQ.now()).getHours();
        var fc = (d.forecast && d.forecast.hours) || [];
        var html = SQ.html`
          ${SQA.sectionHead('Dashboard', 'Live overview of queues, counters and crowd levels.', SQ.html`<a class="btn" href="#/queues">${SQ.icon('list', 16)} Live queues</a><a class="btn btn-primary" href="#/analytics">${SQ.icon('chart', 16)} Full analytics</a>`)}
          <div class="grid grid-4 mb">
            ${SQA.statCard('users', 'blue', fmt.num(c.totalTokens), 'Visitors today', fmt.pct(c.completionRate, 0) + ' completed')}
            ${SQA.statCard('hourglass', 'amber', crowd.waiting, 'Waiting now', crowd.called + ' called · ' + crowd.onHold + ' on hold')}
            ${SQA.statCard('play', 'purple', crowd.serving, 'Being served', crowd.countersOpen + ' of ' + crowd.countersTotal + ' counters open')}
            ${SQA.statCard('check-circle', 'green', fmt.num(c.completed), 'Completed today')}
            ${SQA.statCard('clock', 'teal', fmt.minutes(c.avgWaitMinutes), 'Average wait', 'before being called')}
            ${SQA.statCard('activity', 'blue', fmt.minutes(c.avgServiceMinutes), 'Average service time')}
            ${SQA.statCard('x-circle', 'red', c.cancelled + c.skipped, 'Cancelled / skipped', c.cancelled + ' cancelled · ' + c.skipped + ' no-show')}
            ${SQA.statCard('trending', 'orange', c.peakWindow || '—', 'Peak window', c.peakBasis ? 'based on ' + c.peakBasis : '')}
          </div>
          <div class="grid split mb">
            <div class="card"><div class="card-head"><div><div class="card-title">${SQ.icon('chart', 17)} Visitors by hour – today</div><div class="card-sub">Tokens issued per hour</div></div></div>
              <div class="card-body"><div id="chHourly"></div></div></div>
            <div class="card"><div class="card-head"><div class="card-title">${SQ.icon('activity', 17)} Crowd level</div>${SQ.crowdPill(crowd.crowdLevel)}</div>
              <div class="card-body stack"><div id="chGauge" style="padding-bottom:18px"></div>
                <dl class="kv"><dt>Busiest service</dt><dd>${crowd.busiestService ? crowd.busiestService + ' (' + crowd.busiestServiceCount + ')' : '—'}</dd><dt>Idle staff available</dt><dd>${d.idleStaff}</dd></dl></div></div>
          </div>
          <div class="row spread mb"><h2>Service queues</h2><a class="small" href="#/services">Manage services</a></div>
          <div class="grid auto-grid wide mb">${d.services.filter(function (s) { return s.isActive; }).map(serviceTile)}</div>
          <div class="grid split mb">
            <div><div class="row spread mb"><h2>Counters</h2><a class="small" href="#/counters">Manage counters</a></div>
              <div class="grid auto-grid">${d.counters.map(counterTile)}</div></div>
            <div><div class="row spread mb"><h2>Smart recommendations</h2><a class="small" href="#/optimization">See all</a></div>
              <div class="stack sm">${d.recommendations.length ? d.recommendations.slice(0, 3).map(SQA.recoCard) : SQ.html`<div class="card"><div class="card-body center"><span class="svc-ico tone-green" style="margin:0 auto 10px;background:var(--tone-soft);color:var(--tone)">${SQ.icon('check-circle', 22)}</span><div class="bold">Everything looks balanced</div><div class="small muted">No action is needed right now.</div></div></div>`}</div></div>
          </div>
          <div class="card"><div class="card-head"><div><div class="card-title">${SQ.icon('trending', 17)} Expected visitors – next hours</div><div class="card-sub">Forecast from the same weekday over the last 4 weeks</div></div></div>
            <div class="card-body"><div id="chForecast"></div></div></div>`;
        if (SQ.setHtml(view, html)) {
          C.bar(SQ.qs('#chHourly', view), {
            labels: hrs.map(SQA.hourShort), series: [{ name: 'Visitors', color: 'var(--primary)', values: hrs.map(function (x) { return hours[x]; }) }], height: 230,
            tip: function (i) { return '<b>' + SQA.hourLong(hrs[i]) + (hrs[i] === nowH ? ' (now)' : '') + '</b><br>' + hours[hrs[i]] + ' visitors'; }
          });
          C.gauge(SQ.qs('#chGauge', view), { value: crowd.queueLength, low: cfg.crowdLowMax, moderate: cfg.crowdModerateMax, high: cfg.crowdHighMax, level: crowd.crowdLevel });
          SQA.forecastChart(SQ.qs('#chForecast', view), fc, 220);
        }
      }
      function load() { return SQ.get('admin/dashboard').then(function (d) { last = d; draw(d); }); }
      view.addEventListener('click', function (e) {
        var b = e.target.closest('[data-apply-reco]'); if (!b || !last) return;
        var r = last.recommendations.filter(function (x) { return x.id === b.getAttribute('data-apply-reco'); })[0];
        if (r) SQA.applyReco(b, r, load);
      });
      SQ.loadConfig().then(function (c) { cfg = c; SQA.poll(load, 4000); });
    }
  });

  /* ================================================================ shared token dialogs */
  SQA.priorityDialog = function (id, code, current, after) {
    SQ.form({
      title: 'Change priority – ' + code, icon: 'arrow-up', tone: 'blue', size: 'sm', submitLabel: 'Apply',
      initial: { priority: String(current), reason: '' },
      intro: 'Priority tokens are served before normal tokens. Emergency tokens are served first.',
      fields: [
        { name: 'priority', label: 'Priority level', type: 'select', full: true, options: [{ value: '0', label: 'Normal' }, { value: '1', label: 'Priority (senior, disabled, etc.)' }, { value: '2', label: 'Emergency' }] },
        { name: 'reason', label: 'Reason', type: 'text', full: true, maxlength: 100, placeholder: 'e.g. Senior citizen', show: function (v) { return v.priority !== '0'; } }
      ],
      onSubmit: function (v) { return SQ.post('staff/tokens/' + id + '/priority', { priority: Number(v.priority), reason: v.reason || null }).then(function () { SQ.ok('Priority updated for ' + code + '.'); if (after) after(); }); }
    });
  };
  SQA.cancelDialog = function (id, code, after) {
    SQ.form({
      title: 'Cancel token ' + code + '?', icon: 'alert', tone: 'red', size: 'sm', submitLabel: 'Cancel token', kind: 'danger', cancelLabel: 'Keep it',
      intro: 'The customer will be notified. This cannot be undone.',
      fields: [{ name: 'reason', label: 'Reason (optional)', type: 'text', full: true, maxlength: 100, placeholder: 'e.g. Duplicate token' }],
      onSubmit: function (v) { return SQ.post('staff/tokens/' + id + '/cancel', { reason: v.reason || null }).then(function () { SQ.ok('Token ' + code + ' was cancelled.'); if (after) after(); }); }
    });
  };
  SQA.walkInDialog = function (services, defaultId, after) {
    SQ.form({
      title: 'Add a walk-in customer', icon: 'user-plus', tone: 'teal', submitLabel: 'Issue token',
      initial: { serviceId: String(defaultId || (services[0] && services[0].id) || ''), priority: '0' },
      intro: 'Create a token for a customer who is at the desk without a smartphone.',
      fields: [
        { name: 'serviceId', label: 'Service', type: 'select', required: true, full: true, options: services.map(function (s) { return { value: String(s.id), label: s.name + ' (' + s.prefix + ') – ' + s.waiting + ' waiting' }; }) },
        { name: 'customerName', label: 'Customer name', type: 'text', maxlength: 100, placeholder: 'Walk-in guest' },
        { name: 'phone', label: 'Phone', type: 'tel', maxlength: 20, placeholder: 'Optional' },
        { name: 'priority', label: 'Priority', type: 'select', options: [{ value: '0', label: 'Normal' }, { value: '1', label: 'Priority' }, { value: '2', label: 'Emergency' }] },
        { name: 'reason', label: 'Reason', type: 'text', maxlength: 100, placeholder: 'e.g. Senior citizen', show: function (v) { return v.priority !== '0'; } },
        { name: 'notes', label: 'Notes', type: 'text', full: true, maxlength: 255, placeholder: 'Optional' }
      ],
      onSubmit: function (v) {
        return SQ.post('staff/walk-in', { serviceId: Number(v.serviceId), customerName: v.customerName, phone: v.phone, priority: Number(v.priority), reason: v.reason || null, notes: v.notes || null }).then(function (t) {
          SQ.ok('Token ' + t.tokenCode + ' issued to ' + t.customerName + '.', { title: 'Walk-in added' }); if (after) after();
        });
      }
    });
  };

  /* ================================================================ Live queues */
  SQA.register('queues', {
    title: 'Live queues', icon: 'list',
    mount: function (view) {
      var data = null, dirty = false;
      function row(q, i, n) {
        return SQ.html`<div class="qrow ${i === 0 ? 'first' : ''}"><span class="pos">${q.position}</span>
          <div class="grow"><div class="row wrap" style="gap:8px"><span class="tok">${q.tokenCode}</span>${SQ.priorityBadge(q.priority, q.priorityReason)}${SQ.sourceBadge(q.source)}</div>
            <div class="small text-2 truncate">${q.customerName}</div><div class="tiny muted">Waiting ${fmt.minutes(q.waitedMinutes, { zero: '< 1 min' })} · ETA ${i === 0 ? 'next' : fmt.minutes(q.etaMinutes, { zero: 'now' })}</div></div>
          <div class="dropdown"><button class="icon-btn" style="width:32px;height:32px" data-dd-toggle aria-label="Actions">${SQ.icon('more', 16)}</button>
            <div class="popover" hidden><div class="menu">
              <button class="menu-item" data-move="front" data-id="${q.id}" ${i === 0 ? SQ.raw('disabled') : ''}>${SQ.icon('to-front', 16)} Move to front</button>
              <button class="menu-item" data-move="back" data-id="${q.id}" ${i === n - 1 ? SQ.raw('disabled') : ''}>${SQ.icon('to-back', 16)} Move to back</button>
              <button class="menu-item" data-priority="${q.id}" data-p="${q.priority}" data-code="${q.tokenCode}">${SQ.icon('arrow-up', 16)} Change priority…</button>
              <div class="menu-sep"></div>
              <button class="menu-item danger" data-cancel-token="${q.id}" data-code="${q.tokenCode}">${SQ.icon('x-circle', 16)} Cancel token…</button></div></div></div></div>`;
      }
      function card(s) {
        var col = SQ.safeColor(s.color);
        return SQ.html`<div class="card ${s.isActive ? '' : 'disabled'}" style="border-top:4px solid ${col}">
          <div class="card-head" style="flex-wrap:wrap;gap:10px"><div class="row" style="flex:1 1 190px;min-width:0">${SQ.serviceIcon(s)}<div><div class="bold">${s.name} ${s.isActive ? '' : SQ.html`<span class="badge tone-gray">Disabled</span>`}</div>
            <div class="tiny muted">${s.waiting} waiting · est. ${s.noCounters ? 'n/a' : fmt.minutes(s.etaMinutes, { zero: 'none' })} · ${s.countersOpen}/${s.countersTotal} counters</div></div></div>
            <div class="row" style="margin-left:auto">${SQ.crowdPill(s.crowdLevel)}<button class="btn btn-sm" data-walkin="${s.id}" ${s.isActive ? '' : SQ.raw('disabled')}>${SQ.icon('user-plus', 14)} Walk-in</button></div></div>
          <div class="card-body" style="padding-bottom:8px"><div class="row wrap" style="gap:8px">${s.counters.length ? s.counters.map(function (c) {
            var live = c.status === 'OPEN' || c.status === 'BREAK';
            return SQ.html`<div class="chip" style="${live ? '' : 'opacity:.55'}" title="${c.staffName || 'No staff'}">${SQ.icon('counter', 14)} ${c.name}${c.currentCode ? SQ.html` · <b>${c.currentCode}</b> ${SQ.statusBadge(c.currentStatus)}` : (c.status === 'OPEN' ? ' · idle' : (c.status === 'BREAK' ? ' · break' : ' · closed'))}</div>`;
          }) : SQ.html`<span class="small muted">No counters assigned.</span>`}</div></div>
          <div style="border-top:1px solid var(--border)">${s.queue.length ? s.queue.map(function (q, i) { return row(q, i, s.queue.length); }) : SQ.html`<div class="empty" style="padding:26px 16px"><p>Nobody is waiting.</p></div>`}
            ${s.onHoldTokens.length ? SQ.html`<div class="tiny bold muted" style="padding:10px 14px 4px;text-transform:uppercase;letter-spacing:.06em;background:var(--surface-2);border-top:1px solid var(--border)">On hold (${s.onHoldTokens.length})</div>${s.onHoldTokens.map(function (h) {
              return SQ.html`<div class="qrow"><span class="pos" style="background:var(--orange-soft);color:var(--orange)">${SQ.icon('pause', 14)}</span><div class="grow"><span class="tok">${h.tokenCode}</span> <span class="small text-2">${h.customerName}</span></div><button class="btn btn-sm" data-resume="${h.id}">${SQ.icon('recall', 14)} Resume</button></div>`;
            })}` : ''}</div></div>`;
      }
      function draw() {
        if (!data) return;
        if (SQA.busyUi()) { dirty = true; return; }
        dirty = false;
        var total = data.crowd.queueLength;
        SQ.setHtml(view, SQ.html`${SQA.sectionHead('Live queues', 'Every service queue in real time. Reorder, prioritise or cancel tokens.', SQ.html`${SQ.crowdPill(data.crowd.crowdLevel, total + ' in queue')}`)}
          <div class="grid auto-grid wide">${data.services.map(card)}</div>`);
      }
      function load() { return SQ.get('admin/queues').then(function (d) { data = d; draw(); }); }
      function act(btn, path, body, msg) { return SQ.busy(btn, function () { return SQ.post(path, body || {}).then(function () { if (msg) SQ.ok(msg); return load(); }, function (e) { SQ.err(e); return load(); }); }); }
      view.addEventListener('click', function (e) {
        var t;
        if ((t = e.target.closest('[data-move]'))) { if (!t.disabled) act(t, 'staff/tokens/' + t.getAttribute('data-id') + '/move', { position: t.getAttribute('data-move') }, 'Queue order updated.'); }
        else if ((t = e.target.closest('[data-priority]'))) SQA.priorityDialog(Number(t.getAttribute('data-priority')), t.getAttribute('data-code'), Number(t.getAttribute('data-p')), load);
        else if ((t = e.target.closest('[data-cancel-token]'))) SQA.cancelDialog(Number(t.getAttribute('data-cancel-token')), t.getAttribute('data-code'), load);
        else if ((t = e.target.closest('[data-resume]'))) act(t, 'staff/tokens/' + t.getAttribute('data-resume') + '/resume', {}, 'Token is back in the queue.');
        else if ((t = e.target.closest('[data-walkin]'))) SQA.walkInDialog(data.services.filter(function (s) { return s.isActive; }), Number(t.getAttribute('data-walkin')), load);
      });
      view.addEventListener('focusout', function () { setTimeout(function () { if (dirty && !SQA.busyUi()) draw(); }, 150); });
      SQA.docListen('click', function () { setTimeout(function () { if (dirty && !SQA.busyUi() && SQA.current && SQA.current.id === 'queues') draw(); }, 60); });
      SQ.setHtml(view, SQ.html`<div class="grid auto-grid wide">${[1, 2].map(function () { return SQ.html`<div class="skeleton" style="height:260px"></div>`; })}</div>`);
      SQA.poll(load, 3000);
    }
  });

  /* ================================================================ Optimisation */
  SQA.register('optimization', {
    title: 'Optimisation', icon: 'bulb',
    mount: function (view) {
      var recs = [], dash = null, settings = null;
      function draw() {
        var fc = (dash && dash.forecast && dash.forecast.hours) || [];
        var auto = settings && settings.filter(function (s) { return s.key === 'auto_assign'; })[0];
        var html = SQ.html`
          ${SQA.sectionHead('Queue optimisation', 'Recommendations generated from live queues, counter load and historical patterns.')}
          <div class="grid split">
            <div class="stack">
              <div class="card"><div class="card-head"><div class="card-title">${SQ.icon('bulb', 17)} Recommendations</div><span class="badge tone-gray">${recs.length}</span></div>
                <div class="card-body stack sm">${recs.length ? recs.map(SQA.recoCard) : SQ.empty('check-circle', 'All balanced', 'Queues, counters and staffing look healthy. New suggestions appear here automatically.')}</div></div>
              <div class="card"><div class="card-head"><div><div class="card-title">${SQ.icon('trending', 17)} Forecast – next hours</div><div class="card-sub">Based on the same weekday over the last 4 weeks</div></div></div>
                <div class="card-body"><div id="chForecast"></div></div></div>
            </div>
            <div class="stack">
              <div class="card"><div class="card-head"><div class="card-title">${SQ.icon('zap', 17)} Auto-assign</div></div><div class="card-body stack">
                <p class="small text-2">When enabled, free counters automatically call the next customer in their queue without staff pressing the button. Staff can still hold, skip and complete.</p>
                <label class="switch"><input type="checkbox" id="autoAssign" ${auto && auto.value === 'true' ? SQ.raw('checked') : ''}><span class="track"></span><span class="bold">Auto-assign customers to free counters</span></label></div></div>
              <div class="card"><div class="card-head"><div class="card-title">${SQ.icon('info', 17)} How suggestions are made</div></div><div class="card-body stack sm small text-2">
                <div class="row top">${SQ.icon('login', 16)}<span><b>Open a counter</b> when waiting customers outnumber what open counters can handle.</span></div>
                <div class="row top">${SQ.icon('recall', 16)}<span><b>Move a counter</b> from a calm service to a busy one.</span></div>
                <div class="row top">${SQ.icon('plus', 16)}<span><b>Add a counter</b> when every counter of a service is already open.</span></div>
                <div class="row top">${SQ.icon('trending', 16)}<span><b>Forecast</b> upcoming busy hours from past weeks.</span></div></div></div>
            </div>
          </div>`;
        if (SQ.setHtml(view, html)) {
          SQA.forecastChart(SQ.qs('#chForecast', view), fc, 230);
        }
      }
      function load() {
        return Promise.all([SQ.get('admin/recommendations'), SQ.get('admin/dashboard'), SQ.get('admin/settings')]).then(function (r) { recs = r[0].items; dash = r[1]; settings = r[2].items; draw(); });
      }
      view.addEventListener('click', function (e) {
        var b = e.target.closest('[data-apply-reco]'); if (!b) return;
        var r = recs.filter(function (x) { return x.id === b.getAttribute('data-apply-reco'); })[0];
        if (r) SQA.applyReco(b, r, function () { return load(); });
      });
      view.addEventListener('change', function (e) {
        if (e.target.id !== 'autoAssign') return;
        var on = e.target.checked;
        SQ.put('admin/settings', { auto_assign: on ? 'true' : 'false' }).then(function () { SQ.ok(on ? 'Auto-assign is on.' : 'Auto-assign is off.'); return load(); }).catch(function (er) { SQ.err(er); e.target.checked = !on; });
      });
      SQA.poll(load, 6000);
    }
  });
})();
