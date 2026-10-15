/* SmartQueue - admin: analytics dashboard and reports */
(function () {
  'use strict';
  var fmt = SQ.fmt, C = SQ.charts;
  var GREEN = 'var(--crowd-low)', ORANGE = 'var(--crowd-high)', GRAY = 'var(--muted)', PRIMARY = 'var(--primary)', TEAL = '#0d9488';

  /* ================================================================ Analytics */
  SQA.register('analytics', {
    title: 'Analytics', icon: 'chart',
    mount: function (view) {
      var days = Number(SQ.store.get('sq_an_days', '14')) || 14;
      var data = null;

      function perfTable(rows, label) {
        if (!rows.length) return SQ.empty('inbox', 'No data yet', 'Performance appears once customers are served.');
        var max = rows.reduce(function (a, b) { return Math.max(a, b.served); }, 0) || 1;
        return SQ.html`<div class="table-wrap"><table class="table"><thead><tr><th>${label}</th><th class="num">Served</th><th class="num">Avg. service</th><th>Rating</th></tr></thead><tbody>${rows.map(function (r) {
          return SQ.html`<tr><td class="bold">${r.name}</td><td class="num"><div class="row" style="justify-content:flex-end;gap:8px"><span class="mini-bar"><i style="width:${Math.round((r.served / max) * 100)}%"></i></span>${fmt.num(r.served)}</div></td>
            <td class="num">${fmt.minutes(r.avgServiceMinutes)}</td><td>${r.avgRating ? SQ.html`<span class="row" style="gap:6px">${SQ.stars(r.avgRating, 12)}<span class="small">${fmt.dec(r.avgRating, 1)}</span></span>` : SQ.html`<span class="muted">—</span>`}</td></tr>`;
        })}</tbody></table></div>`;
      }

      function draw() {
        var d = data; if (!d) return;
        var s = d.summary;
        var html = SQ.html`
          ${SQA.sectionHead('Analytics', fmt.date(d.from) + ' – ' + fmt.date(d.to) + ' · ' + s.days + ' days',
            SQ.html`<div class="seg">${[7, 14, 30, 60, 90].map(function (n) { return SQ.html`<button class="${days === n ? 'active' : ''}" data-days="${n}">${n} days</button>`; })}</div>`)}
          <div class="grid grid-4 mb">
            ${SQA.statCard('users', 'blue', fmt.num(s.total), 'Total visitors', fmt.num(s.avgPerDay) + ' per day on average')}
            ${SQA.statCard('check-circle', 'green', fmt.pct(s.completionRate, 1), 'Completion rate', fmt.num(s.completed) + ' completed')}
            ${SQA.statCard('clock', 'amber', fmt.minutes(s.avgWaitMinutes), 'Average wait')}
            ${SQA.statCard('activity', 'purple', fmt.minutes(s.avgServiceMinutes), 'Average service time')}
            ${SQA.statCard('star', 'orange', s.avgRating ? fmt.dec(s.avgRating, 2) : '—', 'Customer rating', fmt.num(s.ratings) + ' ratings')}
            ${SQA.statCard('skip', 'red', fmt.pct(s.noShowRate, 1), 'No-show rate', fmt.pct(s.cancelRate, 1) + ' cancelled')}
            ${SQA.statCard('calendar', 'teal', s.busiestWeekday || '—', 'Busiest weekday')}
            ${SQA.statCard('trending', 'blue', s.peakHourLabel || '—', 'Peak hour', s.mostRequestedService ? 'Top service: ' + s.mostRequestedService : '')}
          </div>
          <div class="grid split-even mb">
            <div class="card"><div class="card-head"><div><div class="card-title">${SQ.icon('users', 17)} Daily visitors</div><div class="card-sub">Tokens issued per day</div></div></div><div class="card-body"><div id="chDaily"></div></div></div>
            <div class="card"><div class="card-head"><div><div class="card-title">${SQ.icon('check-circle', 17)} Completed vs cancelled vs skipped</div><div class="card-sub">Outcome of every token</div></div></div><div class="card-body"><div id="chOutcome"></div></div></div>
          </div>
          <div class="grid split-even mb">
            <div class="card"><div class="card-head"><div><div class="card-title">${SQ.icon('clock', 17)} Average wait &amp; service time</div><div class="card-sub">Minutes per customer, by day</div></div></div><div class="card-body"><div id="chTimes"></div></div></div>
            <div class="card"><div class="card-head"><div><div class="card-title">${SQ.icon('layers', 17)} Service-wise demand</div><div class="card-sub">Share of all tokens</div></div></div><div class="card-body"><div id="chDemand"></div></div></div>
          </div>
          <div class="grid split-even mb">
            <div class="card"><div class="card-head"><div><div class="card-title">${SQ.icon('trending', 17)} Peak hours</div><div class="card-sub">Average tokens per day, by hour</div></div></div><div class="card-body"><div id="chPeak"></div></div></div>
            <div class="card"><div class="card-head"><div><div class="card-title">${SQ.icon('calendar', 17)} Busiest days of the week</div><div class="card-sub">Average tokens on each weekday</div></div></div><div class="card-body" id="busyDays"></div></div>
          </div>
          <div class="card mb"><div class="card-head"><div><div class="card-title">${SQ.icon('grid', 17)} Crowd heat-map</div><div class="card-sub">Average tokens issued per weekday and hour – darker means busier</div></div></div><div class="card-body" id="chHeat"></div></div>
          <div class="grid split-even mb">
            <div class="card"><div class="card-head"><div class="card-title">${SQ.icon('layers', 17)} Service performance</div></div><div class="card-body flush" style="margin-top:10px">${d.serviceTime.length ? SQ.html`<div class="table-wrap"><table class="table"><thead><tr><th>Service</th><th class="num">Avg. wait</th><th class="num">Avg. service</th><th>Rating</th></tr></thead><tbody>${d.serviceTime.map(function (r) {
              return SQ.html`<tr><td>${SQ.html`<span class="row" style="gap:8px"><i style="width:10px;height:10px;border-radius:3px;background:${SQ.safeColor(r.color)}"></i><b>${r.name}</b></span>`}</td><td class="num">${fmt.minutes(r.avgWaitMinutes)}</td><td class="num">${fmt.minutes(r.avgServiceMinutes)}</td><td>${r.avgRating ? SQ.html`${SQ.stars(r.avgRating, 12)} <span class="small">${fmt.dec(r.avgRating, 1)}</span>` : '—'}</td></tr>`;
            })}</tbody></table></div>` : SQ.empty('inbox', 'No data')}</div></div>
            <div class="stack">
              <div class="card"><div class="card-head"><div class="card-title">${SQ.icon('target', 17)} Wait-time prediction accuracy</div></div><div class="card-body">
                ${d.predictionAccuracy.samples ? SQ.html`<div class="grid grid-3" style="gap:10px;text-align:center">
                  <div><div class="stat-value" style="font-size:24px">${fmt.dec(d.predictionAccuracy.meanAbsErrorMinutes, 1)}<small>min</small></div><div class="stat-label">average error</div></div>
                  <div><div class="stat-value" style="font-size:24px">${fmt.pct(d.predictionAccuracy.within5MinPercent, 1)}</div><div class="stat-label">within 5 minutes</div></div>
                  <div><div class="stat-value" style="font-size:24px">${fmt.num(d.predictionAccuracy.samples)}</div><div class="stat-label">tokens compared</div></div></div>
                  <p class="tiny muted mt">Compares the wait promised when a token was issued with the actual wait.</p>` : SQ.html`<p class="muted small">Not enough completed tokens yet.</p>`}</div></div>
              <div class="card"><div class="card-head"><div class="card-title">${SQ.icon('alert', 17)} Busiest periods</div></div><div class="card-body">
                ${d.peakCrowdPeriods.length ? SQ.html`<div class="stack sm">${d.peakCrowdPeriods.map(function (p, i) {
                  return SQ.html`<div class="row spread small" style="padding:7px 10px;border-radius:9px;background:var(--surface-2)"><span class="row" style="gap:8px"><b class="tok-chip" style="min-width:26px">${i + 1}</b>${p.label}</span><b>${p.peakWaiting} waiting</b></div>`;
                })}</div>` : SQ.html`<p class="muted small">No crowded periods in this range.</p>`}</div></div>
            </div>
          </div>
          <div class="grid split-even">
            <div class="card"><div class="card-head"><div class="card-title">${SQ.icon('counter', 17)} Counter performance</div></div><div class="card-body flush" style="margin-top:10px">${perfTable(d.counterPerformance, 'Counter')}</div></div>
            <div class="card"><div class="card-head"><div class="card-title">${SQ.icon('user', 17)} Staff performance</div></div><div class="card-body flush" style="margin-top:10px">${perfTable(d.staffPerformance, 'Staff member')}</div></div>
          </div>`;
        if (SQ.setHtml(view, html)) {
          var labels = d.daily.map(function (x) { return x.label; });
          C.line(SQ.qs('#chDaily', view), {
            labels: labels, height: 250, series: [{ name: 'Visitors', color: PRIMARY, values: d.daily.map(function (x) { return x.total; }) }, { name: 'Completed', color: GREEN, values: d.daily.map(function (x) { return x.completed; }), area: false }],
            tip: function (i) { var x = d.daily[i]; return '<b>' + x.weekday + ' ' + x.label + '</b><br>' + x.total + ' visitors · ' + x.completed + ' completed'; }
          });
          C.bar(SQ.qs('#chOutcome', view), {
            labels: labels, stacked: true, height: 250,
            series: [{ name: 'Completed', color: GREEN, values: d.daily.map(function (x) { return x.completed; }) }, { name: 'Skipped (no-show)', color: ORANGE, values: d.daily.map(function (x) { return x.skipped; }) }, { name: 'Cancelled', color: GRAY, values: d.daily.map(function (x) { return x.cancelled; }) }],
            tip: function (i) { var x = d.daily[i]; return '<b>' + x.weekday + ' ' + x.label + '</b><br>' + x.completed + ' completed<br>' + x.skipped + ' skipped<br>' + x.cancelled + ' cancelled'; }
          });
          C.line(SQ.qs('#chTimes', view), {
            labels: labels, height: 250, area: false,
            series: [{ name: 'Average wait', color: ORANGE, values: d.daily.map(function (x) { return x.avgWaitMinutes; }) }, { name: 'Average service', color: TEAL, values: d.daily.map(function (x) { return x.avgServiceMinutes; }) }],
            yFormat: function (v) { return v + 'm'; },
            tip: function (i) { var x = d.daily[i]; return '<b>' + x.weekday + ' ' + x.label + '</b><br>Wait ' + fmt.dec(x.avgWaitMinutes, 1) + ' min<br>Service ' + fmt.dec(x.avgServiceMinutes, 1) + ' min'; }
          });
          C.donut(SQ.qs('#chDemand', view), { items: d.serviceDemand.map(function (x) { return { label: x.name + ' (' + x.prefix + ')', value: x.count, color: SQ.safeColor(x.color) }; }), centerLabel: 'tokens' });
          var ph = d.peakHours;
          var f = 24, l = -1; ph.forEach(function (x) { if (x.total > 0) { f = Math.min(f, x.hour); l = Math.max(l, x.hour); } });
          var from = f === 24 ? 8 : f, to = l === -1 ? 18 : l, sl = ph.slice(from, to + 1);
          var top = ph.reduce(function (a, b) { return b.avgPerDay > a.avgPerDay ? b : a; }, ph[0]);
          C.bar(SQ.qs('#chPeak', view), {
            labels: sl.map(function (x) { return SQA.hourShort(x.hour); }), height: 250,
            series: [{ name: 'Avg tokens / day', color: PRIMARY, values: sl.map(function (x) { return x.avgPerDay; }) }],
            tip: function (i) { var x = sl[i]; return '<b>' + x.label + (x.hour === top.hour ? ' (peak)' : '') + '</b><br>' + fmt.dec(x.avgPerDay, 1) + ' tokens per day<br>' + fmt.num(x.total) + ' in total'; }
          });
          SQ.qs('#busyDays', view).innerHTML = C.barList(d.busiestDays.map(function (x) { return { label: x.weekday, value: x.avgTokens, display: fmt.num(Math.round(x.avgTokens)) + ' / day', color: 'var(--primary)' }; })).s;
          C.heatmap(SQ.qs('#chHeat', view), { rows: d.heatmap.map(function (r) { return { label: r.weekday, cells: r.cells }; }), unit: 'tokens' });
        }
      }
      function load() { return SQ.get('admin/analytics?days=' + days).then(function (d) { data = d; draw(); }); }
      view.addEventListener('click', function (e) {
        var b = e.target.closest('[data-days]'); if (!b) return;
        days = Number(b.getAttribute('data-days')); SQ.store.set('sq_an_days', String(days)); view.__html = null; load();
      });
      view.innerHTML = SQ.html`<div class="grid grid-4">${[1, 2, 3, 4].map(function () { return SQ.html`<div class="skeleton" style="height:84px"></div>`; })}</div>`.s;
      SQA.poll(load, 30000);
    }
  });

  /* ================================================================ Reports */
  function isoDay(d) { return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0'); }
  SQA.register('reports', {
    title: 'Reports', icon: 'file',
    mount: function (view) {
      var today = new Date(SQ.now());
      var wk = new Date(SQ.now() - 6 * 86400000);
      var st = { from: isoDay(wk), to: isoDay(today), serviceId: '', status: '', page: 1, data: null, services: [], loading: false };
      var PAGE = 25;
      function query() { return SQ.qstr({ from: st.from, to: st.to, serviceId: st.serviceId, status: st.status }); }
      function draw() {
        var d = st.data, body = '';
        if (st.loading && !d) body = SQ.html`<div style="padding:16px">${[1, 2, 3, 4].map(function () { return SQ.html`<div class="skeleton" style="height:42px;margin-bottom:8px"></div>`; })}</div>`;
        else if (d && !d.items.length) body = SQ.empty('file', 'No tokens in this range', 'Change the dates or filters above.');
        else if (d) {
          var pages = Math.max(1, Math.ceil(d.items.length / PAGE));
          if (st.page > pages) st.page = pages;
          var slice = d.items.slice((st.page - 1) * PAGE, st.page * PAGE);
          body = SQ.html`<div class="table-wrap"><table class="table"><thead><tr><th>Token</th><th>Date</th><th>Service</th><th>Customer</th><th>Status</th><th>Counter / staff</th><th class="num">Waited</th><th class="num">Service</th><th>Rating</th></tr></thead><tbody>
            ${slice.map(function (t) {
              return SQ.html`<tr><td><span class="tok">${t.tokenCode}</span></td><td class="nowrap small">${fmt.dateShort(t.tokenDate)}, ${fmt.time(t.createdAt)}</td><td>${t.serviceName}</td>
                <td>${t.customerName} ${SQ.priorityBadge(t.priority)} ${SQ.sourceBadge(t.source)}</td><td>${SQ.statusBadge(t.status)}</td>
                <td class="small">${t.counterName || '—'}${t.servedByName ? SQ.html`<div class="tiny muted">${t.servedByName}</div>` : ''}</td>
                <td class="num">${t.status === 'WAITING' ? '—' : fmt.minutes(t.waitedMinutes, { zero: '< 1 min' })}</td><td class="num">${t.status === 'COMPLETED' ? fmt.minutes(t.serviceMinutes, { zero: '< 1 min' }) : '—'}</td>
                <td>${t.rating ? SQ.stars(t.rating, 12) : '—'}</td></tr>`;
            })}</tbody></table></div>
            ${SQ.pager({ page: st.page, pages: pages, total: d.items.length }, 'data-rpage')}
            ${d.truncated ? SQ.html`<div class="alert tone-amber" style="margin:12px"><div class="a-body">Showing the first ${fmt.num(d.limit)} tokens. Narrow the date range to see everything.</div></div>` : ''}`;
        }
        var s = d ? d.summary : null;
        SQ.setHtml(view, SQ.html`
          ${SQA.sectionHead('Reports', 'Filter token history and export it as a spreadsheet (CSV).', SQ.html`<a class="btn btn-primary" href="/api/admin/reports/export${query()}" download>${SQ.icon('download', 16)} Export CSV</a>`)}
          <div class="card mb"><div class="card-body"><div class="row wrap" style="gap:12px;align-items:flex-end">
            <div class="field"><label for="rpFrom">From</label><input class="input input-sm" type="date" id="rpFrom" value="${st.from}" max="${st.to}"></div>
            <div class="field"><label for="rpTo">To</label><input class="input input-sm" type="date" id="rpTo" value="${st.to}"></div>
            <div class="field"><label for="rpSvc">Service</label><select class="select select-sm" id="rpSvc"><option value="">All services</option>${st.services.map(function (x) { return SQ.html`<option value="${x.id}" ${String(x.id) === String(st.serviceId) ? SQ.raw('selected') : ''}>${x.name}</option>`; })}</select></div>
            <div class="field"><label for="rpStatus">Status</label><select class="select select-sm" id="rpStatus">${[['', 'All statuses'], ['COMPLETED', 'Completed'], ['CANCELLED', 'Cancelled'], ['SKIPPED', 'Skipped'], ['WAITING', 'Waiting'], ['ON_HOLD', 'On hold']].map(function (o) { return SQ.html`<option value="${o[0]}" ${o[0] === st.status ? SQ.raw('selected') : ''}>${o[1]}</option>`; })}</select></div>
            <div class="row" style="gap:6px"><button class="btn btn-sm" data-range="0">Today</button><button class="btn btn-sm" data-range="6">7 days</button><button class="btn btn-sm" data-range="29">30 days</button></div>
          </div></div></div>
          ${s ? SQ.html`<div class="grid grid-4 mb">
            ${SQA.statCard('file', 'blue', fmt.num(s.total), 'Tokens in range')}
            ${SQA.statCard('check-circle', 'green', fmt.num(s.completed), 'Completed', s.cancelled + ' cancelled · ' + s.skipped + ' skipped')}
            ${SQA.statCard('clock', 'amber', fmt.minutes(s.avgWaitMinutes), 'Average wait')}
            ${SQA.statCard('star', 'purple', s.avgRating ? fmt.dec(s.avgRating, 2) : '—', 'Average rating', 'Avg. service ' + fmt.minutes(s.avgServiceMinutes))}</div>` : ''}
          <div class="card">${body}</div>`);
      }
      function load() {
        st.loading = true;
        return SQ.get('admin/reports' + query()).then(function (d) { st.data = d; st.loading = false; draw(); }, function (e) { st.loading = false; SQ.err(e); draw(); });
      }
      view.addEventListener('change', function (e) {
        var id = e.target.id;
        if (id === 'rpFrom') st.from = e.target.value || st.from;
        else if (id === 'rpTo') st.to = e.target.value || st.to;
        else if (id === 'rpSvc') st.serviceId = e.target.value;
        else if (id === 'rpStatus') st.status = e.target.value;
        else return;
        if (st.from > st.to) { SQ.warn('The start date is after the end date.'); return; }
        st.page = 1; load();
      });
      view.addEventListener('click', function (e) {
        var t;
        if ((t = e.target.closest('[data-range]'))) { var n = Number(t.getAttribute('data-range')); st.to = isoDay(new Date(SQ.now())); st.from = isoDay(new Date(SQ.now() - n * 86400000)); st.page = 1; load(); }
        else if ((t = e.target.closest('[data-rpage]'))) { st.page = Number(t.getAttribute('data-rpage')); draw(); window.scrollTo(0, 0); }
      });
      draw();
      SQ.get('admin/services').then(function (r) { st.services = r.items; draw(); });
      load();
    }
  });
})();
