/* SmartQueue - sign in / create account page */
(function () {
  'use strict';
  var app = SQ.qs('#app');
  var params = new URLSearchParams(location.search);
  var state = { tab: 'login', role: 'USER', config: { orgName: 'SmartQueue', allowRegistration: true }, board: null };

  var ROLES = [
    { key: 'USER', label: 'Customer', icon: 'user', badge: 'Public Portal', desc: 'Get tokens & track live queue' },
    { key: 'STAFF', label: 'Counter Staff', icon: 'counter', badge: 'Staff Console', desc: 'Call and serve ticket holders' },
    { key: 'ADMIN', label: 'Administrator', icon: 'shield-check', badge: 'Admin Portal', desc: 'Counters, services & analytics' }
  ];

  function hero() {
    var b = state.board;
    var crowd = b ? b.crowd : null;
    var serving = [];
    if (b) b.counters.forEach(function (c) { if (c.currentCode) serving.push(c.currentCode); });
    return SQ.html`
      <section class="auth-hero">
        <div>${SQ.brand(state.config.orgName, '/index.html')}</div>
        <div>
          <h1>Skip the wait.<br>Not the service.</h1>
          <p class="lead">Take a digital token from your phone, watch your position move in real time and arrive just when it is your turn.</p>
        </div>
        <div class="hero-features">
          <div>${SQ.icon('ticket', 18)}<span><b>Digital tokens</b><br>One tap, no paper, no standing in line.</span></div>
          <div>${SQ.icon('clock', 18)}<span><b>Accurate wait times</b><br>Predicted from live counters and history.</span></div>
          <div>${SQ.icon('bell', 18)}<span><b>Smart alerts</b><br>We tell you when your turn is approaching.</span></div>
          <div>${SQ.icon('shield-check', 18)}<span><b>Fair and priority queues</b><br>Seniors and emergencies are looked after.</span></div>
        </div>
        <div class="glass live-card">
          <div class="lc-title"><span class="conn" style="color:#fff"><i></i></span> Live right now</div>
          ${b ? SQ.html`
            <div class="live-grid">
              <div><div class="v">${crowd.queueLength}</div><div class="l">people in queue</div></div>
              <div><div class="v">${crowd.countersOpen}<small style="font-size:16px;opacity:.7">/${crowd.countersTotal}</small></div><div class="l">counters open</div></div>
              <div><div class="v" style="font-size:20px;padding-top:6px">${SQ.CROWD[crowd.crowdLevel]}</div><div class="l">crowd level</div></div>
            </div>
            ${serving.length ? SQ.html`<div class="live-now">${serving.map(function (c) { return SQ.html`<span>${c}</span>`; })}</div><div class="tiny" style="opacity:.7;margin-top:6px">Now being served</div>` : SQ.html`<div class="tiny" style="opacity:.75;margin-top:10px">No customers are being served at the moment.</div>`}`
          : SQ.html`<div class="skeleton" style="height:64px;margin-top:12px;opacity:.3"></div>`}
        </div>
        <div class="tiny" style="opacity:.75">${SQ.icon('monitor', 13)} <a href="/display.html" style="color:#c7d2fe">Open the lobby display board</a></div>
      </section>`;
  }

  function strengthBar(pw) {
    var score = 0;
    if (pw.length >= 8) score++;
    if (/[a-z]/i.test(pw) && /\d/.test(pw)) score++;
    if (pw.length >= 12 || (/[A-Z]/.test(pw) && /[a-z]/.test(pw))) score++;
    if (/[^A-Za-z0-9]/.test(pw) && pw.length >= 8) score++;
    var col = ['var(--border)', 'var(--danger)', 'var(--warning)', 'var(--info)', 'var(--success)'][score];
    var label = ['', 'Weak', 'Fair', 'Good', 'Strong'][score];
    return SQ.html`<div class="strength" style="--c:${col}">${[1, 2, 3, 4].map(function (i) { return SQ.html`<i class="${i <= score ? 'on' : ''}"></i>`; })}</div><div class="hint">${pw ? label : 'Use at least 8 characters with a letter and a number.'}</div>`;
  }

  function notice() {
    if (params.get('expired')) return SQ.html`<div class="alert tone-amber mb">${SQ.icon('clock', 18)}<div class="a-body">Your session has ended. Please sign in again.</div></div>`;
    if (params.get('bye')) return SQ.html`<div class="alert tone-green mb">${SQ.icon('check-circle', 18)}<div class="a-body">You have been signed out safely.</div></div>`;
    return '';
  }

  function roleSelector() {
    var cur = ROLES.find(function (r) { return r.key === state.role; }) || ROLES[0];
    return SQ.html`
      <div class="role-picker">
        <div class="role-picker-title">
          <span>Select your role:</span>
          <span class="role-banner-badge">${cur.badge}</span>
        </div>
        <div class="role-grid">
          ${ROLES.map(function (r) {
            var active = r.key === state.role;
            return SQ.html`
              <button type="button" class="role-btn ${active ? 'active' : ''}" data-role="${r.key}">
                ${SQ.icon(r.icon, 20)}
                <span>${r.label}</span>
              </button>`;
          })}
        </div>
      </div>`;
  }

  function loginForm() {
    var cur = ROLES.find(function (r) { return r.key === state.role; }) || ROLES[0];
    return SQ.html`
      <form id="loginForm" class="stack" novalidate>
        <div class="role-banner">
          ${SQ.icon(cur.icon, 18)}
          <div><b>${cur.label} sign in</b> – <span class="muted">${cur.desc}</span></div>
        </div>
        <div class="field"><label for="lEmail">Email address</label>
          <div class="input-icon">${SQ.icon('mail', 17)}<input class="input" id="lEmail" name="email" type="email" autocomplete="username" placeholder="you@example.com" required autofocus></div></div>
        <div class="field"><label for="lPass">Password</label>
          <div class="input-icon has-reveal">${SQ.icon('lock', 17)}<input class="input" id="lPass" name="password" type="password" autocomplete="current-password" placeholder="Your password" required>
            <button type="button" class="reveal" data-reveal aria-label="Show password">${SQ.icon('eye', 17)}</button></div></div>
        <div class="row spread"><label class="check"><input type="checkbox" name="remember"> Keep me signed in</label></div>
        <div data-error></div>
        <button class="btn btn-primary btn-lg btn-block" type="submit">Sign in ${SQ.icon('arrow-right', 18)}</button>
      </form>
      <div class="divider"></div>
      <p class="small muted center">New user? Click <b>Create account</b> above to register with your chosen role.</p>`;
  }

  function registerForm() {
    if (!state.config.allowRegistration) {
      return SQ.html`<div class="alert tone-amber">${SQ.icon('lock', 18)}<div class="a-body"><b>Registration is closed.</b><br>Please contact the front desk or administrator.</div></div>`;
    }
    var cur = ROLES.find(function (r) { return r.key === state.role; }) || ROLES[0];
    return SQ.html`
      <form id="regForm" class="stack" novalidate>
        <div class="field">
          <label>Account Role</label>
          <div class="role-seg">
            ${ROLES.map(function (r) {
              return SQ.html`<button type="button" class="${r.key === state.role ? 'active' : ''}" data-reg-role="${r.key}">${SQ.icon(r.icon, 15)} ${r.label}</button>`;
            })}
          </div>
        </div>
        <div class="field"><label for="rName">Full name</label>
          <div class="input-icon">${SQ.icon('user', 17)}<input class="input" id="rName" name="fullName" autocomplete="name" placeholder="Your full name" required maxlength="100"></div></div>
        <div class="form-grid">
          <div class="field"><label for="rEmail">Email</label>
            <div class="input-icon">${SQ.icon('mail', 17)}<input class="input" id="rEmail" name="email" type="email" autocomplete="email" placeholder="you@example.com" required></div></div>
          <div class="field"><label for="rPhone">Phone <span class="muted">(optional)</span></label>
            <div class="input-icon">${SQ.icon('phone', 17)}<input class="input" id="rPhone" name="phone" type="tel" autocomplete="tel" placeholder="9876543210" maxlength="20"></div></div>
        </div>
        ${state.role === 'USER' ? SQ.html`
          <div class="stack xs">
            <label class="check"><input type="checkbox" id="rPriority" name="isPriority"> <b>Priority access</b> <span class="muted">(Senior citizen / Medical condition / Disability)</span></label>
            <div id="rReasonWrap" style="display:none;margin-top:4px">
              <input class="input input-sm" id="rReason" name="priorityReason" placeholder="Priority reason (e.g. Senior citizen, disability)">
            </div>
          </div>` : ''}
        <div class="field"><label for="rPass">Password</label>
          <div class="input-icon has-reveal">${SQ.icon('lock', 17)}<input class="input" id="rPass" name="password" type="password" autocomplete="new-password" placeholder="Create a password" required>
            <button type="button" class="reveal" data-reveal aria-label="Show password">${SQ.icon('eye', 17)}</button></div>
          <div data-strength>${strengthBar('')}</div></div>
        <div class="field"><label for="rPass2">Confirm password</label>
          <div class="input-icon">${SQ.icon('lock', 17)}<input class="input" id="rPass2" name="confirm" type="password" autocomplete="new-password" placeholder="Repeat the password" required></div></div>
        <div data-error></div>
        <button class="btn btn-primary btn-lg btn-block" type="submit">Create ${cur.label} account</button>
        <p class="tiny muted center">By continuing you agree to use the service responsibly.</p>
      </form>`;
  }

  function render() {
    SQ.setHtml(app, SQ.html`
      <div class="auth-wrap">
        ${hero()}
        <section class="auth-panel">
          <div class="auth-card stack lg">
            <div class="row spread">
              <div><h2>${state.tab === 'login' ? 'Welcome' : 'Create your account'}</h2><p class="muted" style="margin-top:4px">${state.tab === 'login' ? 'Sign in to manage your queue.' : 'It takes less than a minute.'}</p></div>
              ${SQ.themeButton()}
            </div>
            ${roleSelector()}
            <div class="seg" style="align-self:flex-start">
              <button type="button" class="${state.tab === 'login' ? 'active' : ''}" data-tab="login">Sign in</button>
              <button type="button" class="${state.tab === 'register' ? 'active' : ''}" data-tab="register">Create account</button>
            </div>
            ${notice()}
            ${state.tab === 'login' ? loginForm() : registerForm()}
          </div>
        </section>
      </div>`);
  }

  function showError(form, msg) {
    var box = SQ.qs('[data-error]', form);
    if (box) {
      box.innerHTML = SQ.html`<div class="alert tone-red" role="alert">${SQ.icon('alert', 18)}<div class="a-body">${msg}</div></div>`.s;
    }
  }

  function signIn(email, password, remember, form, btn) {
    return SQ.busy(btn, function () {
      return SQ.post('auth/login', { email: email, password: password, remember: !!remember }, { noRedirect: true }).then(function (r) {
        SQ.toast('Welcome, ' + r.user.fullName.split(' ')[0] + '!', { type: 'success' });
        location.href = '/' + r.home;
      }, function (e) { showError(form, e.message); });
    });
  }

  function onSubmit(e) {
    var form = e.target;
    e.preventDefault();
    var btn = SQ.qs('button[type=submit]', form);
    var fd = new FormData(form);
    if (form.id === 'loginForm') {
      var email = String(fd.get('email') || '').trim(), pw = String(fd.get('password') || '');
      if (!email || !pw) { showError(form, 'Please enter your email and password.'); return; }
      signIn(email, pw, fd.get('remember') === 'on', form, btn);
    } else if (form.id === 'regForm') {
      var name = String(fd.get('fullName') || '').trim(), mail = String(fd.get('email') || '').trim();
      var phone = String(fd.get('phone') || '').trim(), p1 = String(fd.get('password') || ''), p2 = String(fd.get('confirm') || '');
      var isP = fd.get('isPriority') === 'on';
      var pReason = isP ? String(fd.get('priorityReason') || 'Senior citizen').trim() : null;
      if (name.length < 2) { showError(form, 'Please enter your full name.'); return; }
      if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(mail)) { showError(form, 'Please enter a valid email address.'); return; }
      if (p1.length < 8 || !/[A-Za-z]/.test(p1) || !/\d/.test(p1)) { showError(form, 'Password must be at least 8 characters with a letter and a number.'); return; }
      if (p1 !== p2) { showError(form, 'The two passwords do not match.'); return; }
      SQ.busy(btn, function () {
        return SQ.post('auth/register', {
          fullName: name,
          email: mail,
          phone: phone,
          password: p1,
          role: state.role,
          isPriority: isP,
          priorityReason: pReason
        }, { noRedirect: true }).then(function (r) {
          SQ.toast('Account created. Welcome aboard!', { type: 'success' });
          location.href = '/' + r.home;
        }, function (er) { showError(form, er.message); });
      });
    }
  }

  app.addEventListener('submit', onSubmit);
  app.addEventListener('click', function (e) {
    var tab = e.target.closest('[data-tab]');
    if (tab) { state.tab = tab.getAttribute('data-tab'); params.delete('expired'); params.delete('bye'); render(); return; }
    var roleBtn = e.target.closest('[data-role]');
    if (roleBtn) {
      state.role = roleBtn.getAttribute('data-role');
      render();
      return;
    }
    var regRoleBtn = e.target.closest('[data-reg-role]');
    if (regRoleBtn) {
      state.role = regRoleBtn.getAttribute('data-reg-role');
      render();
      return;
    }
    var rv = e.target.closest('[data-reveal]');
    if (rv) {
      var inp = rv.parentNode.querySelector('input'); var show = inp.type === 'password';
      inp.type = show ? 'text' : 'password'; rv.innerHTML = SQ.icon(show ? 'eye-off' : 'eye', 17).s; return;
    }
  });

  app.addEventListener('change', function (e) {
    if (e.target.id === 'rPriority') {
      var wrap = SQ.qs('#rReasonWrap');
      if (wrap) wrap.style.display = e.target.checked ? 'block' : 'none';
    }
  });

  app.addEventListener('input', function (e) {
    if (e.target.id === 'rPass') SQ.setHtml(SQ.qs('[data-strength]'), strengthBar(e.target.value));
  });

  // Already signed in? Jump straight to the right page (unless the user just logged out / expired).
  var skipAuto = params.get('bye') || params.get('expired');
  render();
  SQ.loadConfig().then(function (c) {
    state.config = c;
    document.title = 'SmartQueue – ' + c.orgName;
    if (state.tab === 'register' || !SQ.qs('#loginForm')) render();
    else { var h = SQ.qs('.auth-hero .brand small'); if (h) h.textContent = c.orgName; }
  });
  if (!skipAuto) {
    SQ.get('auth/session', { noRedirect: true }).then(function (me) { if (me.authenticated) location.replace('/' + me.home); }, function () { });
  }
  SQ.every(function () {
    return SQ.get('public/board', { noRedirect: true }).then(function (b) {
      var had = !!state.board; state.board = b;
      var ae = document.activeElement;
      // re-rendering the hero only (avoid touching the form while typing)
      var hero_ = SQ.qs('.auth-hero');
      if (hero_) { var tmp = document.createElement('div'); tmp.innerHTML = hero().s; hero_.innerHTML = tmp.firstElementChild.innerHTML; }
      else if (!had) render();
      if (ae && document.body.contains(ae)) { try { ae.focus(); } catch (e) { } }
    });
  }, 6000);
})();
