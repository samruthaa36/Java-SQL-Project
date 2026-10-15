/* SmartQueue - admin: counters, services, users, settings, activity log */
(function () {
  'use strict';
  var fmt = SQ.fmt;

  function counterBadge(status) {
    var m = { OPEN: ['green', 'Open'], BREAK: ['amber', 'On break'], CLOSED: ['gray', 'Closed'] }[status] || ['gray', status];
    return SQ.html`<span class="badge tone-${m[0]} ${status === 'OPEN' ? 'dot pulse' : ''}">${m[1]}</span>`;
  }
  function svcChip(name, color) {
    var c = SQ.safeColor(color);
    return SQ.html`<span class="row" style="gap:8px"><i style="width:10px;height:10px;border-radius:3px;background:${c}"></i><span>${name}</span></span>`;
  }

  /* ================================================================ Counters */
  SQA.register('counters', {
    title: 'Counters', icon: 'counter',
    mount: function (view) {
      var items = null, services = [], dirty = false;
      function draw() {
        if (!items) return;
        if (SQA.busyUi()) { dirty = true; return; }
        dirty = false;
        var open = items.filter(function (c) { return c.status === 'OPEN'; }).length, brk = items.filter(function (c) { return c.status === 'BREAK'; }).length;
        SQ.setHtml(view, SQ.html`
          ${SQA.sectionHead('Counters', 'Create counters, assign them to services and open or close them.', SQ.html`<button class="btn btn-primary" data-add>${SQ.icon('plus', 16)} Add counter</button>`)}
          <div class="grid grid-4 mb">
            ${SQA.statCard('counter', 'blue', items.length, 'Total counters')}
            ${SQA.statCard('check-circle', 'green', open, 'Open')}
            ${SQA.statCard('coffee', 'amber', brk, 'On break')}
            ${SQA.statCard('x-circle', 'gray', items.length - open - brk, 'Closed')}
          </div>
          <div class="card"><div class="table-wrap">${items.length ? SQ.html`<table class="table"><thead><tr><th>Counter</th><th>Service</th><th>Status</th><th>Staff</th><th>Now serving</th><th class="num">Served today</th><th></th></tr></thead><tbody>
            ${items.map(function (c) {
              return SQ.html`<tr><td class="bold">${c.name}</td><td>${svcChip(c.serviceName, c.serviceColor)}</td><td>${counterBadge(c.status)}</td>
                <td>${c.staffName ? SQ.html`<span class="row" style="gap:8px"><span class="avatar sm">${SQ.initials(c.staffName)}</span>${c.staffName}</span>` : SQ.html`<span class="muted">—</span>`}</td>
                <td>${c.currentCode ? SQ.tokenChip(c.currentCode, c.serviceColor) : SQ.html`<span class="muted">—</span>`}</td><td class="num">${c.servedToday}</td>
                <td class="actions">${c.status === 'CLOSED' ? SQ.html`<button class="btn btn-sm btn-soft" data-open="${c.id}">${SQ.icon('login', 14)} Open</button>` : SQ.html`<button class="btn btn-sm" data-close="${c.id}">${SQ.icon('logout', 14)} Close</button>`}
                  <div class="dropdown" style="display:inline-block"><button class="icon-btn" style="width:32px;height:32px" data-dd-toggle aria-label="More">${SQ.icon('more', 16)}</button>
                    <div class="popover" hidden><div class="menu">
                      <button class="menu-item" data-edit="${c.id}">${SQ.icon('edit', 16)} Edit</button>
                      <button class="menu-item danger" data-delete="${c.id}" ${c.status === 'CLOSED' ? '' : SQ.raw('disabled')}>${SQ.icon('trash', 16)} Delete</button></div></div></div></td></tr>`;
            })}</tbody></table>` : SQ.empty('counter', 'No counters yet', 'Add your first counter to start serving customers.')}</div></div>`);
      }
      function load() {
        return Promise.all([SQ.get('admin/counters'), SQ.get('admin/services')]).then(function (r) { items = r[0].items; services = r[1].items; draw(); });
      }
      function byId(id) { return items.filter(function (c) { return c.id === Number(id); })[0]; }
      function svcOptions() { return services.filter(function (s) { return s.isActive; }).map(function (s) { return { value: String(s.id), label: s.name + ' (' + s.prefix + ')' }; }); }

      function addDialog() {
        SQ.form({
          title: 'Add counter', icon: 'counter', submitLabel: 'Create counter', size: 'sm',
          initial: { name: 'Counter ' + (items.length + 1), serviceId: svcOptions()[0] ? svcOptions()[0].value : '' },
          fields: [
            { name: 'name', label: 'Counter name', required: true, maxlength: 60, full: true },
            { name: 'serviceId', label: 'Serves', type: 'select', required: true, full: true, options: svcOptions(), hint: 'Customers of this service are called to this counter.' }
          ],
          onSubmit: function (v) { return SQ.post('admin/counters', { name: v.name, serviceId: Number(v.serviceId) }).then(function () { SQ.ok('Counter created.'); return load(); }); }
        });
      }
      function editDialog(c) {
        SQ.form({
          title: 'Edit ' + c.name, icon: 'edit', submitLabel: 'Save changes', size: 'sm',
          initial: { name: c.name, serviceId: String(c.serviceId) },
          fields: [
            { name: 'name', label: 'Counter name', required: true, maxlength: 60, full: true },
            { name: 'serviceId', label: 'Serves', type: 'select', required: true, full: true, options: svcOptions().concat(services.filter(function (s) { return !s.isActive && s.id === c.serviceId; }).map(function (s) { return { value: String(s.id), label: s.name + ' (disabled)' }; })),
              hint: c.status === 'CLOSED' ? '' : 'The counter is open: it can only move to another service while it is idle.' }
          ],
          onSubmit: function (v) {
            var sid = Number(v.serviceId);
            var p = SQ.put('admin/counters/' + c.id, { name: v.name, serviceId: c.status === 'CLOSED' ? sid : c.serviceId });
            return p.then(function () { if (c.status !== 'CLOSED' && sid !== c.serviceId) return SQ.post('admin/counters/' + c.id + '/reassign', { serviceId: sid }); })
              .then(function () { SQ.ok('Counter updated.'); return load(); });
          }
        });
      }
      function openDialog(c) {
        SQ.get('admin/staff-available').then(function (r) {
          if (!r.items.length) { SQ.warn('Every active staff member already has a counter open.', { title: 'No free staff' }); return; }
          SQ.form({
            title: 'Open ' + c.name, icon: 'login', tone: 'green', submitLabel: 'Open counter', size: 'sm',
            intro: 'Choose the staff member who will work at this counter (' + c.serviceName + ').',
            fields: [{ name: 'staffId', label: 'Staff member', type: 'select', required: true, full: true, options: r.items.map(function (s) { return { value: String(s.id), label: s.fullName }; }) }],
            onSubmit: function (v) { return SQ.post('admin/counters/' + c.id + '/open', { staffId: Number(v.staffId) }).then(function () { SQ.ok(c.name + ' is now open.'); return load(); }); }
          });
        }).catch(SQ.err);
      }
      view.addEventListener('click', function (e) {
        var t, c;
        if (e.target.closest('[data-add]')) addDialog();
        else if ((t = e.target.closest('[data-open]'))) openDialog(byId(t.getAttribute('data-open')));
        else if ((t = e.target.closest('[data-close]'))) {
          c = byId(t.getAttribute('data-close'));
          SQ.confirm({ title: 'Close ' + c.name + '?', message: c.currentCode ? 'A customer (' + c.currentCode + ') is currently at this counter. Finish or hold them first.' : 'The staff member will be signed off this counter.', confirmLabel: 'Close counter', tone: 'red' }).then(function (ok) {
            if (ok) SQ.busy(t, function () { return SQ.post('admin/counters/' + c.id + '/close').then(function () { SQ.ok(c.name + ' closed.'); return load(); }); });
          });
        }
        else if ((t = e.target.closest('[data-edit]'))) editDialog(byId(t.getAttribute('data-edit')));
        else if ((t = e.target.closest('[data-delete]'))) {
          if (t.disabled) return;
          c = byId(t.getAttribute('data-delete'));
          SQ.confirm({ title: 'Delete ' + c.name + '?', message: 'The counter is removed from the list. Its past service history is kept for reports.', confirmLabel: 'Delete counter', tone: 'red' }).then(function (ok) {
            if (ok) SQ.del('admin/counters/' + c.id).then(function () { SQ.ok('Counter deleted.'); return load(); }).catch(SQ.err);
          });
        }
      });
      SQA.docListen('click', function () { setTimeout(function () { if (dirty && !SQA.busyUi() && SQA.current && SQA.current.id === 'counters') draw(); }, 60); });
      SQA.poll(load, 5000);
    }
  });

  /* ================================================================ Services */
  SQA.register('services', {
    title: 'Services', icon: 'layers',
    mount: function (view) {
      var items = null, dirty = false;
      function draw() {
        if (!items) return;
        if (SQA.busyUi()) { dirty = true; return; }
        dirty = false;
        SQ.setHtml(view, SQ.html`
          ${SQA.sectionHead('Services', 'Each service has its own queue, token prefix and average service time.', SQ.html`<button class="btn btn-primary" data-add>${SQ.icon('plus', 16)} Add service</button>`)}
          <div class="grid auto-grid wide">${items.map(function (s) {
            var col = SQ.safeColor(s.color);
            return SQ.html`<div class="card ${s.isActive ? '' : 'disabled'}" style="border-top:4px solid ${col}"><div class="card-body stack">
              <div class="row top">${SQ.serviceIcon(s, 'lg')}<div class="grow"><div class="row spread"><h3>${s.name}</h3>${SQ.tokenChip(s.prefix, col)}</div>
                <p class="small muted">${s.description || 'No description'}</p></div></div>
              <div class="row wrap" style="gap:8px">${s.isActive ? SQ.html`<span class="badge tone-green dot">Active</span>` : SQ.html`<span class="badge tone-gray">Disabled</span>`}${SQ.crowdPill(s.crowdLevel, s.waiting + ' waiting')}</div>
              <dl class="kv"><dt>Default service time</dt><dd>${fmt.minutes(s.defaultServiceMinutes)}</dd><dt>Measured average (14 days)</dt><dd>${fmt.minutes(s.avgServiceMinutes)}</dd>
                <dt>Counters</dt><dd>${s.countersTotal} (${s.countersOpen} open)</dd><dt>Tokens issued, all time</dt><dd>${fmt.num(s.totalTokens)}</dd></dl>
              <div class="row"><button class="btn btn-sm grow" data-edit="${s.id}">${SQ.icon('edit', 14)} Edit</button>
                <button class="btn btn-sm" data-toggle="${s.id}">${s.isActive ? 'Disable' : 'Enable'}</button>
                <button class="btn btn-sm btn-outline-danger" data-delete="${s.id}" title="Delete" aria-label="Delete">${SQ.icon('trash', 14)}</button></div></div></div>`;
          })}</div>`);
      }
      function load() { return SQ.get('admin/services').then(function (r) { items = r.items; draw(); }); }
      function byId(id) { return items.filter(function (s) { return s.id === Number(id); })[0]; }
      function dialog(s) {
        var edit = !!s;
        SQ.form({
          title: edit ? 'Edit ' + s.name : 'Add service', icon: 'layers', submitLabel: edit ? 'Save changes' : 'Create service',
          initial: edit ? { name: s.name, prefix: s.prefix, description: s.description || '', icon: s.icon, color: SQ.safeColor(s.color), avgServiceMinutes: s.defaultServiceMinutes, isActive: s.isActive }
            : { name: '', prefix: '', description: '', icon: 'users', color: '#4f46e5', avgServiceMinutes: 5, isActive: true },
          fields: [
            { name: 'name', label: 'Service name', required: true, maxlength: 60 },
            { name: 'prefix', label: 'Token prefix (one letter)', required: true, maxlength: 1, pattern: '[A-Za-z]', disabled: edit && s.totalTokens > 0, hint: edit && s.totalTokens > 0 ? 'Locked because tokens were already issued.' : 'e.g. A → tokens A001, A002…' },
            { name: 'description', label: 'Description', maxlength: 200, full: true },
            { name: 'avgServiceMinutes', label: 'Default service time (minutes)', type: 'number', required: true, min: 0.5, max: 180, step: 0.5, hint: 'Used until enough real data is collected.' },
            { name: 'color', label: 'Colour', type: 'color' },
            { name: 'icon', label: 'Icon', type: 'icons', full: true },
            { name: 'isActive', label: 'Active (accepting new tokens)', type: 'switch', full: true }
          ],
          onSubmit: function (v) {
            var body = { name: v.name, description: v.description, icon: v.icon, color: v.color, avgServiceMinutes: v.avgServiceMinutes, isActive: v.isActive };
            if (!edit || s.totalTokens === 0) body.prefix = String(v.prefix || '').toUpperCase();
            var p = edit ? SQ.put('admin/services/' + s.id, body) : SQ.post('admin/services', body);
            return p.then(function () { SQ.ok(edit ? 'Service updated.' : 'Service created.'); return load(); });
          }
        });
      }
      view.addEventListener('click', function (e) {
        var t, s;
        if (e.target.closest('[data-add]')) dialog(null);
        else if ((t = e.target.closest('[data-edit]'))) dialog(byId(t.getAttribute('data-edit')));
        else if ((t = e.target.closest('[data-toggle]'))) {
          s = byId(t.getAttribute('data-toggle'));
          SQ.busy(t, function () { return SQ.put('admin/services/' + s.id, { isActive: !s.isActive }).then(function () { SQ.ok(s.name + (s.isActive ? ' disabled.' : ' enabled.')); return load(); }); });
        } else if ((t = e.target.closest('[data-delete]'))) {
          s = byId(t.getAttribute('data-delete'));
          SQ.confirm({ title: 'Delete ' + s.name + '?', message: s.totalTokens > 0 ? 'This service already has token history, so it cannot be deleted. Disable it instead to stop new tokens.' : 'This permanently removes the service and its counters.', confirmLabel: s.totalTokens > 0 ? 'Try anyway' : 'Delete service', tone: 'red' }).then(function (ok) {
            if (ok) SQ.del('admin/services/' + s.id).then(function () { SQ.ok('Service deleted.'); return load(); }).catch(SQ.err);
          });
        }
      });
      SQA.docListen('click', function () { setTimeout(function () { if (dirty && !SQA.busyUi() && SQA.current && SQA.current.id === 'services') draw(); }, 60); });
      SQA.poll(load, 8000);
    }
  });

  /* ================================================================ Users */
  SQA.register('users', {
    title: 'Users', icon: 'users',
    mount: function (view) {
      var st = { role: '', search: '', page: 1, data: null };
      var ROLE = { USER: ['blue', 'Customer'], STAFF: ['teal', 'Staff'], ADMIN: ['purple', 'Admin'] };
      function draw() {
        var d = st.data, counts = d ? d.counts : { users: 0, staff: 0, admins: 0 };
        var tabs = [['', 'All', counts.users + counts.staff + counts.admins], ['USER', 'Customers', counts.users], ['STAFF', 'Staff', counts.staff], ['ADMIN', 'Admins', counts.admins]];
        var body = '';
        if (!d) body = SQ.html`<div style="padding:16px">${[1, 2, 3, 4].map(function () { return SQ.html`<div class="skeleton" style="height:46px;margin-bottom:8px"></div>`; })}</div>`;
        else if (!d.items.length) body = SQ.empty('users', 'No users found', 'Try a different search or filter.');
        else body = SQ.html`<div class="table-wrap"><table class="table"><thead><tr><th>User</th><th>Role</th><th>Phone</th><th>Status</th><th>Activity</th><th>Last sign-in</th><th></th></tr></thead><tbody>
          ${d.items.map(function (u) {
            var r = ROLE[u.role] || ['gray', u.role];
            return SQ.html`<tr><td><div class="row"><span class="avatar sm">${SQ.initials(u.fullName)}</span><div><div class="bold">${u.fullName}</div><div class="tiny muted">${u.email}</div></div></div></td>
              <td><span class="badge tone-${r[0]}">${r[1]}</span> ${u.isPriority ? SQ.html`<span class="badge tone-purple" title="${u.priorityReason || ''}">${SQ.icon('shield-check', 11)} Priority</span>` : ''}</td>
              <td>${u.phone || SQ.html`<span class="muted">—</span>`}</td>
              <td>${u.isActive ? SQ.html`<span class="badge tone-green dot">Active</span>` : SQ.html`<span class="badge tone-gray">Disabled</span>`}</td>
              <td class="small">${u.role === 'STAFF' ? fmt.plural(u.served, 'customer') + ' served' : (u.role === 'USER' ? fmt.plural(u.visits, 'visit') : '—')}${u.counterName ? SQ.html`<div class="tiny muted">At ${u.counterName}</div>` : ''}</td>
              <td class="small muted nowrap">${u.lastLoginAt ? fmt.dateTime(u.lastLoginAt) : 'Never'}</td>
              <td class="actions"><button class="btn btn-sm" data-edit="${u.id}">${SQ.icon('edit', 14)} Edit</button>
                <div class="dropdown" style="display:inline-block"><button class="icon-btn" style="width:32px;height:32px" data-dd-toggle aria-label="More">${SQ.icon('more', 16)}</button>
                  <div class="popover" hidden><div class="menu"><button class="menu-item" data-reset="${u.id}">${SQ.icon('key', 16)} Reset password</button>
                    <div class="menu-sep"></div><button class="menu-item danger" data-delete="${u.id}">${SQ.icon('trash', 16)} Delete user</button></div></div></div></td></tr>`;
          })}</tbody></table></div>${SQ.pager(d, 'data-page')}`;
        var keepSearch = SQ.qs('#userSearch', view);
        var focus = document.activeElement === keepSearch;
        var pos = keepSearch ? keepSearch.selectionStart : 0;
        SQ.setHtml(view, SQ.html`
          ${SQA.sectionHead('Users', 'Customers, counter staff and administrators.', SQ.html`<button class="btn btn-primary" data-add>${SQ.icon('user-plus', 16)} Add user</button>`)}
          <div class="card"><div class="card-head" style="padding-bottom:12px;flex-wrap:wrap"><div class="tabs" style="border:0;flex-wrap:wrap">${tabs.map(function (t) { return SQ.html`<button class="tab ${st.role === t[0] ? 'active' : ''}" data-role="${t[0]}">${t[1]} <span class="count">${t[2]}</span></button>`; })}</div>
            <div class="search">${SQ.icon('search', 16)}<input class="input" id="userSearch" type="search" placeholder="Search name, email or phone" value="${st.search}" autocomplete="off"></div></div>
            <div style="border-top:1px solid var(--border)">${body}</div></div>`);
        if (focus) { var ns = SQ.qs('#userSearch', view); ns.focus(); try { ns.setSelectionRange(pos, pos); } catch (e) { } }
      }
      function load() {
        return SQ.get('admin/users' + SQ.qstr({ role: st.role, search: st.search, page: st.page, size: 10 })).then(function (d) { st.data = d; if (d.page !== st.page) st.page = d.page; draw(); });
      }
      function byId(id) { return st.data.items.filter(function (u) { return u.id === Number(id); })[0]; }
      function userDialog(u) {
        var edit = !!u;
        SQ.form({
          title: edit ? 'Edit ' + u.fullName : 'Add user', icon: edit ? 'edit' : 'user-plus', submitLabel: edit ? 'Save changes' : 'Create account',
          initial: edit ? { fullName: u.fullName, email: u.email, phone: u.phone || '', role: u.role, isActive: u.isActive, isPriority: u.isPriority, priorityReason: u.priorityReason || '' }
            : { fullName: '', email: '', phone: '', role: 'USER', isActive: true, isPriority: false, priorityReason: '', password: '' },
          fields: [
            { name: 'fullName', label: 'Full name', required: true, maxlength: 100, full: true, autocomplete: 'off' },
            { name: 'email', label: 'Email', type: 'email', required: true, autocomplete: 'off' },
            { name: 'phone', label: 'Phone', type: 'tel', maxlength: 20 },
            { name: 'role', label: 'Role', type: 'select', options: [{ value: 'USER', label: 'Customer' }, { value: 'STAFF', label: 'Counter staff' }, { value: 'ADMIN', label: 'Administrator' }] },
            { name: 'password', label: 'Password', type: 'password', autocomplete: 'new-password', hint: 'Leave empty to generate a temporary password.', show: function () { return !edit; } },
            { name: 'isActive', label: 'Account is active', type: 'switch', show: function () { return edit; } },
            { name: 'isPriority', label: 'Priority customer (served ahead of normal tokens)', type: 'switch', full: true, show: function (v) { return v.role === 'USER'; } },
            { name: 'priorityReason', label: 'Priority reason', maxlength: 100, full: true, placeholder: 'e.g. Senior citizen, person with disability', show: function (v) { return v.role === 'USER' && v.isPriority; } }
          ],
          onSubmit: function (v) {
            var body = { fullName: v.fullName, email: v.email, phone: v.phone, role: v.role, isPriority: v.role === 'USER' && v.isPriority, priorityReason: v.priorityReason };
            if (edit) {
              body.isActive = v.isActive;
              return SQ.put('admin/users/' + u.id, body).then(function () { SQ.ok('User updated.'); return load(); });
            }
            body.password = v.password || null;
            return SQ.post('admin/users', body).then(function (r) {
              SQ.ok('Account created for ' + r.fullName + '.');
              if (r.temporaryPassword) SQA.showPassword('Account created', r.fullName, r.temporaryPassword);
              return load();
            });
          }
        });
      }
      view.addEventListener('click', function (e) {
        var t, u;
        if (e.target.closest('[data-add]')) userDialog(null);
        else if ((t = e.target.closest('[data-role]'))) { st.role = t.getAttribute('data-role'); st.page = 1; load(); }
        else if ((t = e.target.closest('[data-page]'))) { st.page = Number(t.getAttribute('data-page')); load(); }
        else if ((t = e.target.closest('[data-edit]'))) userDialog(byId(t.getAttribute('data-edit')));
        else if ((t = e.target.closest('[data-reset]'))) {
          u = byId(t.getAttribute('data-reset'));
          SQ.confirm({ title: 'Reset password?', message: 'A new temporary password will be generated for ' + u.fullName + ' and all their sessions will be signed out.', confirmLabel: 'Reset password' }).then(function (ok) {
            if (ok) SQ.post('admin/users/' + u.id + '/reset-password').then(function (r) { SQA.showPassword('Password reset', u.fullName, r.temporaryPassword); }).catch(SQ.err);
          });
        } else if ((t = e.target.closest('[data-delete]'))) {
          u = byId(t.getAttribute('data-delete'));
          SQ.confirm({ title: 'Delete ' + u.fullName + '?', message: 'Users with visit history cannot be deleted – disable the account instead. This cannot be undone.', confirmLabel: 'Delete user', tone: 'red' }).then(function (ok) {
            if (ok) SQ.del('admin/users/' + u.id).then(function () { SQ.ok('User deleted.'); return load(); }).catch(SQ.err);
          });
        }
      });
      view.addEventListener('input', SQ.debounce(function (e) { if (e.target.id === 'userSearch') { st.search = e.target.value.trim(); st.page = 1; load(); } }, 300));
      draw(); load();
    }
  });

  /* ================================================================ Settings */
  var GROUPS = [
    { title: 'Organisation', icon: 'home', sub: 'Shown in headers and on the lobby display.', keys: ['org_name'] },
    { title: 'Crowd levels', icon: 'activity', sub: 'Queue length that defines each crowd level.', keys: ['crowd_low_max', 'crowd_moderate_max', 'crowd_high_max'], preview: true },
    { title: 'Queue rules', icon: 'list', sub: 'How tokens are called, prioritised and limited.', keys: ['approach_threshold', 'no_show_minutes', 'priority_burst', 'max_queue_size', 'auto_assign'] },
    { title: 'Access & security', icon: 'lock', sub: 'Registration and session length.', keys: ['allow_registration', 'session_hours'] }
  ];
  SQA.register('settings', {
    title: 'Settings', icon: 'settings',
    mount: function (view) {
      var items = null;
      function field(s) {
        var id = 'set_' + s.key;
        if (s.type === 'bool') {
          return SQ.html`<div class="field"><label class="switch"><input type="checkbox" id="${id}" data-key="${s.key}" ${s.value === 'true' ? SQ.raw('checked') : ''}><span class="track"></span><span class="bold">${s.label}</span></label><div class="hint">${s.description}</div></div>`;
        }
        return SQ.html`<div class="field"><label for="${id}">${s.label}</label><input class="input" id="${id}" data-key="${s.key}" type="${s.type === 'int' ? 'number' : 'text'}" value="${s.value}" ${s.type === 'int' ? SQ.html`min="${s.min}" max="${s.max}" step="1"` : SQ.html`minlength="${s.min}" maxlength="${s.max}"`} required>
          <div class="hint">${s.description}${s.type === 'int' ? SQ.html` (${s.min}–${s.max})` : ''}</div></div>`;
      }
      function preview() {
        var lo = Number((SQ.qs('#set_crowd_low_max', view) || {}).value), mo = Number((SQ.qs('#set_crowd_moderate_max', view) || {}).value), hi = Number((SQ.qs('#set_crowd_high_max', view) || {}).value);
        var el = SQ.qs('#crowdPreview', view); if (!el) return;
        var ok = lo >= 0 && mo > lo && hi > mo;
        el.innerHTML = ok ? SQ.html`<div class="row wrap" style="gap:8px">
          <span class="crowd-pill crowd-LOW"><i></i>Low 0–${lo}</span><span class="crowd-pill crowd-MODERATE"><i></i>Moderate ${lo + 1}–${mo}</span>
          <span class="crowd-pill crowd-HIGH"><i></i>High ${mo + 1}–${hi}</span><span class="crowd-pill crowd-CRITICAL"><i></i>Critical ${hi + 1}+</span></div>`.s
          : SQ.html`<div class="alert tone-red">${SQ.icon('alert', 16)}<div class="a-body">Limits must increase: low &lt; moderate &lt; high.</div></div>`.s;
      }
      function draw() {
        var map = {}; items.forEach(function (s) { map[s.key] = s; });
        SQ.setHtml(view, SQ.html`
          ${SQA.sectionHead('Settings', 'Tune the queue rules and crowd thresholds. Changes apply immediately.', SQ.html`<button class="btn btn-primary" data-save>${SQ.icon('check', 16)} Save changes</button>`)}
          <div data-error></div>
          <div class="grid split-even">
            ${GROUPS.map(function (g) {
              return SQ.html`<div class="card"><div class="card-head"><div><div class="card-title">${SQ.icon(g.icon, 17)} ${g.title}</div><div class="card-sub">${g.sub}</div></div></div>
                <div class="card-body stack">${g.keys.map(function (k) { return map[k] ? field(map[k]) : ''; })}${g.preview ? SQ.html`<div><div class="label" style="margin-bottom:6px">Preview</div><div id="crowdPreview"></div></div>` : ''}</div></div>`;
            })}
            <div class="card"><div class="card-head"><div><div class="card-title">${SQ.icon('zap', 17)} Testing & Maintenance tools</div><div class="card-sub">Traffic simulation and queue management.</div></div></div>
              <div class="card-body stack">
                <div class="row wrap"><select class="select select-sm" id="simCount" style="width:auto"><option>5</option><option selected>10</option><option>20</option><option>40</option></select>
                  <button class="btn" data-simulate>${SQ.icon('users', 16)} Simulate a crowd</button></div>
                <div class="hint">Adds random walk-in customers to the queues so you can watch crowd levels, wait times and recommendations react.</div>
                <div><button class="btn btn-outline-danger" data-clear>${SQ.icon('trash', 16)} Clear all waiting customers</button></div></div></div>
          </div>`);
        preview();
      }
      function load() { return SQ.get('admin/settings').then(function (r) { items = r.items; draw(); }); }
      view.addEventListener('input', function (e) { if (/^set_crowd_/.test(e.target.id)) preview(); });
      view.addEventListener('click', function (e) {
        var t;
        if ((t = e.target.closest('[data-save]'))) {
          var body = {};
          SQ.qsa('[data-key]', view).forEach(function (inp) {
            var key = inp.getAttribute('data-key'), orig = items.filter(function (s) { return s.key === key; })[0];
            var val = inp.type === 'checkbox' ? (inp.checked ? 'true' : 'false') : inp.value.trim();
            if (orig && String(orig.value) !== val) body[key] = val;
          });
          var box = SQ.qs('[data-error]', view); box.innerHTML = '';
          if (!Object.keys(body).length) { SQ.toast('Nothing changed.', { type: 'info' }); return; }
          SQ.busy(t, function () {
            return SQ.put('admin/settings', body).then(function (r) { items = r.items; SQ.ok('Settings saved.'); view.__html = null; draw(); SQ._config = null; },
              function (er) { box.innerHTML = SQ.html`<div class="alert tone-red mb">${SQ.icon('alert', 18)}<div class="a-body">${er.message}</div></div>`.s; window.scrollTo(0, 0); });
          });
        } else if ((t = e.target.closest('[data-simulate]'))) {
          var n = Number(SQ.qs('#simCount', view).value);
          SQ.busy(t, function () { return SQ.post('admin/demo/simulate-crowd', { count: n }).then(function (r) { SQ.ok(r.created + ' walk-in customers added. Check the dashboard!'); }); });
        } else if ((t = e.target.closest('[data-clear]'))) {
          SQ.confirm({ title: 'Clear the waiting queues?', message: 'Every customer who is still waiting will be cancelled and notified. People already called or being served are not affected.', confirmLabel: 'Clear queues', tone: 'red' }).then(function (ok) {
            if (ok) SQ.post('admin/demo/clear-waiting').then(function (r) { SQ.ok(r.cancelled + ' waiting token(s) cancelled.'); }).catch(SQ.err);
          });
        }
      });
      view.innerHTML = SQ.html`<div class="grid split-even">${[1, 2, 3, 4].map(function () { return SQ.html`<div class="skeleton" style="height:200px"></div>`; })}</div>`.s;
      load();
    }
  });

  /* ================================================================ Activity log */
  SQA.register('activity', {
    title: 'Activity log', icon: 'clipboard',
    mount: function (view) {
      var st = { search: '', page: 1, data: null };
      function human(a) { return String(a || '').toLowerCase().replace(/_/g, ' ').replace(/^./, function (c) { return c.toUpperCase(); }); }
      function tone(a) {
        if (/DELETE|CANCEL|CLEAR|FAIL|LOCK/.test(a)) return 'red';
        if (/CREATE|ISSUED|OPEN|WALK/.test(a)) return 'green';
        if (/LOGIN|LOGOUT|PASSWORD/.test(a)) return 'purple';
        if (/UPDATE|SETTING|PRIORITY|MOVED|REASSIGN|RESTORED/.test(a)) return 'amber';
        return 'blue';
      }
      function draw() {
        var d = st.data, body;
        if (!d) body = SQ.html`<div style="padding:16px">${[1, 2, 3, 4, 5].map(function () { return SQ.html`<div class="skeleton" style="height:40px;margin-bottom:8px"></div>`; })}</div>`;
        else if (!d.items.length) body = SQ.empty('clipboard', 'No activity found', 'Try a different search.');
        else body = SQ.html`<div class="table-wrap"><table class="table"><thead><tr><th>When</th><th>Who</th><th>Action</th><th>Details</th></tr></thead><tbody>${d.items.map(function (a) {
          return SQ.html`<tr><td class="nowrap small muted">${fmt.dateTime(a.createdAt)}</td><td class="nowrap">${a.actorName || 'System'}</td><td><span class="badge tone-${tone(a.action)}">${human(a.action)}</span></td><td class="small text-2">${a.details || ''}</td></tr>`;
        })}</tbody></table></div>${SQ.pager({ page: d.page, pages: d.pages, total: d.total }, 'data-page')}`;
        var inp = SQ.qs('#actSearch', view), focus = document.activeElement === inp, pos = inp ? inp.selectionStart : 0;
        SQ.setHtml(view, SQ.html`${SQA.sectionHead('Activity log', 'An audit trail of important actions in the system.')}
          <div class="card"><div class="card-head" style="padding-bottom:12px"><div class="search">${SQ.icon('search', 16)}<input class="input" id="actSearch" type="search" placeholder="Search actions, people or details" value="${st.search}" autocomplete="off"></div></div>
          <div style="border-top:1px solid var(--border)">${body}</div></div>`);
        if (focus) { var n = SQ.qs('#actSearch', view); n.focus(); try { n.setSelectionRange(pos, pos); } catch (e) { } }
      }
      function load() { return SQ.get('admin/activity' + SQ.qstr({ search: st.search, page: st.page, size: 20 })).then(function (d) { st.data = d; draw(); }); }
      view.addEventListener('click', function (e) { var t = e.target.closest('[data-page]'); if (t) { st.page = Number(t.getAttribute('data-page')); load(); window.scrollTo(0, 0); } });
      view.addEventListener('input', SQ.debounce(function (e) { if (e.target.id === 'actSearch') { st.search = e.target.value.trim(); st.page = 1; load(); } }, 300));
      draw(); load();
      SQA.poll(function () { if (st.page === 1 && !st.search && !SQA.busyUi()) return load(); }, 10000);
    }
  });
})();
