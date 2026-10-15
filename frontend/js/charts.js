/* =====================================================================
   SmartQueue - tiny SVG chart library (no external dependencies)
   bar (grouped / stacked), line / area, donut, gauge, heat-map, bar list
   ===================================================================== */
(function (global) {
  'use strict';
  var SQ = global.SQ;
  var C = SQ.charts = {};
  var uid = 0;

  function nice(max, count) {
    count = count || 4;
    if (!(max > 0)) return { max: count, step: 1 };
    var raw = max / count;
    var mag = Math.pow(10, Math.floor(Math.log(raw) / Math.LN10));
    var norm = raw / mag;
    var step = (norm <= 1 ? 1 : norm <= 2 ? 2 : norm <= 2.5 ? 2.5 : norm <= 5 ? 5 : 10) * mag;
    return { max: Math.ceil(max / step - 1e-9) * step, step: step };
  }
  function esc(s) { return SQ.esc(s); }
  function f1(n) { return Math.round(n * 10) / 10; }
  function defFmt(v) { return Math.abs(v) >= 1000 ? (v / 1000).toFixed(v % 1000 ? 1 : 0) + 'k' : (Math.round(v * 10) / 10).toString(); }

  /** Mounts a chart: draws now and again whenever the container is resized. */
  function mount(el, spec) {
    if (!el) return;
    el.classList.add('chart');
    el.__spec = spec;
    var tip = el.querySelector('.chart-tip');
    if (!tip) { tip = document.createElement('div'); tip.className = 'chart-tip'; }
    el.__tip = tip;
    draw(el);
    if (!el.__bound) {
      el.__bound = true;
      var last = el.clientWidth;
      if (global.ResizeObserver) {
        var ro = new ResizeObserver(function () {
          if (Math.abs(el.clientWidth - last) > 2) { last = el.clientWidth; draw(el); }
        });
        ro.observe(el);
      }
      el.addEventListener('mousemove', function (e) {
        var t = e.target.closest ? e.target.closest('[data-i]') : null;
        var sp = el.__spec;
        if (!t || !sp || !sp.tip) { el.__tip.classList.remove('show'); return; }
        var i = Number(t.getAttribute('data-i'));
        var s = t.getAttribute('data-s');
        var html = sp.tip(i, s === null ? null : Number(s));
        if (!html) { el.__tip.classList.remove('show'); return; }
        el.__tip.innerHTML = html;
        var r = el.getBoundingClientRect();
        var x = e.clientX - r.left, y = e.clientY - r.top;
        el.__tip.style.left = Math.max(60, Math.min(r.width - 60, x)) + 'px';
        el.__tip.style.top = (y - 10) + 'px';
        el.__tip.classList.add('show');
      });
      el.addEventListener('mouseleave', function () { el.__tip.classList.remove('show'); });
    }
  }
  function draw(el) {
    var sp = el.__spec;
    var w = Math.max(240, el.clientWidth || 600);
    var svg = sp.render(w);
    el.innerHTML = svg;
    el.appendChild(el.__tip);
  }

  function legendHtml(series) {
    if (!series || series.length < 2) return '';
    return '<div class="legend" style="margin-top:10px">' + series.map(function (s) {
      return '<span><i style="background:' + esc(s.color) + '"></i>' + esc(s.name) + '</span>';
    }).join('') + '</div>';
  }

  /* ---------------------------------------------------------------
     Bars: opts = {labels, series:[{name,color,values}], stacked, height, yFormat, tip(i,s), labelEvery, empty}
     --------------------------------------------------------------- */
  C.bar = function (el, o) {
    var h = o.height || 240;
    var fmtY = o.yFormat || defFmt;
    mount(el, {
      tip: o.tip,
      render: function (W) {
        var m = { l: 40, r: 8, t: 12, b: 28 };
        var n = o.labels.length;
        var maxV = 0;
        for (var i = 0; i < n; i++) {
          if (o.stacked) { var sum = 0; o.series.forEach(function (s) { sum += Math.max(0, s.values[i] || 0); }); maxV = Math.max(maxV, sum); }
          else o.series.forEach(function (s) { maxV = Math.max(maxV, s.values[i] || 0); });
        }
        var sc = nice(o.yMax || maxV, 4);
        var pw = W - m.l - m.r, ph = h - m.t - m.b;
        var gw = pw / n;
        var ns = o.stacked ? 1 : o.series.length;
        var bw = Math.max(3, Math.min(o.stacked ? 38 : 30, (gw * 0.72) / ns));
        var every = o.labelEvery || Math.max(1, Math.ceil((n * (o.labelWidth || 38)) / pw));
        var out = '<svg width="' + W + '" height="' + h + '" viewBox="0 0 ' + W + ' ' + h + '" role="img" aria-label="' + esc(o.title || 'Bar chart') + '">';
        for (var t = 0; t <= sc.max + 1e-9; t += sc.step) {
          var y = m.t + ph - (t / sc.max) * ph;
          out += '<line class="grid-line" x1="' + m.l + '" x2="' + (W - m.r) + '" y1="' + f1(y) + '" y2="' + f1(y) + '"/>';
          out += '<text x="' + (m.l - 7) + '" y="' + f1(y + 4) + '" text-anchor="end">' + esc(fmtY(t)) + '</text>';
        }
        for (var i2 = 0; i2 < n; i2++) {
          var cx = m.l + gw * i2 + gw / 2;
          if (i2 % every === 0) out += '<text x="' + f1(cx) + '" y="' + (h - 8) + '" text-anchor="middle">' + esc(o.labels[i2]) + '</text>';
          if (o.stacked) {
            var acc = 0;
            o.series.forEach(function (s, si) {
              var v = Math.max(0, s.values[i2] || 0);
              if (v <= 0) return;
              var bh = (v / sc.max) * ph;
              var by = m.t + ph - ((acc + v) / sc.max) * ph;
              out += '<rect class="bar" data-i="' + i2 + '" data-s="' + si + '" x="' + f1(cx - bw / 2) + '" y="' + f1(by) + '" width="' + f1(bw) + '" height="' + f1(Math.max(1, bh)) + '" fill="' + esc(s.color) + '"' + (si === o.series.length - 1 || false ? ' rx="3"' : '') + '/>';
              acc += v;
            });
          } else {
            o.series.forEach(function (s, si) {
              var v = s.values[i2] || 0;
              var bh = (v / sc.max) * ph;
              var bx = cx - (ns * bw) / 2 + si * bw + (ns > 1 ? si * 0 : 0);
              out += '<rect class="bar" data-i="' + i2 + '" data-s="' + si + '" x="' + f1(bx) + '" y="' + f1(m.t + ph - bh) + '" width="' + f1(Math.max(2, bw - (ns > 1 ? 2 : 0))) + '" height="' + f1(Math.max(v > 0 ? 1 : 0, bh)) + '" rx="3" fill="' + esc(s.color) + '"/>';
            });
          }
          // invisible hover column for easier tooltips on short bars
          out += '<rect data-i="' + i2 + '" x="' + f1(m.l + gw * i2) + '" y="' + m.t + '" width="' + f1(gw) + '" height="' + ph + '" fill="transparent"/>';
        }
        out += '<line class="axis-line" x1="' + m.l + '" x2="' + (W - m.r) + '" y1="' + (m.t + ph) + '" y2="' + (m.t + ph) + '"/></svg>';
        return out + legendHtml(o.series);
      }
    });
  };

  /* ---------------------------------------------------------------
     Lines / areas: opts = {labels, series:[{name,color,values,area}], height, yFormat, smooth, tip(i), yMin}
     --------------------------------------------------------------- */
  C.line = function (el, o) {
    var h = o.height || 240;
    var fmtY = o.yFormat || defFmt;
    mount(el, {
      tip: o.tip,
      render: function (W) {
        var m = { l: 40, r: 14, t: 12, b: 28 };
        var n = o.labels.length;
        var maxV = 0;
        o.series.forEach(function (s) { s.values.forEach(function (v) { if (v !== null && v !== undefined) maxV = Math.max(maxV, v); }); });
        var sc = nice(o.yMax || maxV, 4);
        var pw = W - m.l - m.r, ph = h - m.t - m.b;
        var gid = 'g' + (++uid);
        var every = o.labelEvery || Math.max(1, Math.ceil((n * (o.labelWidth || 52)) / pw));
        var xs = function (i) { return m.l + (n === 1 ? pw / 2 : (i * pw) / (n - 1)); };
        var ys = function (v) { return m.t + ph - (v / sc.max) * ph; };
        var out = '<svg width="' + W + '" height="' + h + '" viewBox="0 0 ' + W + ' ' + h + '" role="img" aria-label="' + esc(o.title || 'Line chart') + '"><defs>';
        o.series.forEach(function (s, si) {
          out += '<linearGradient id="' + gid + si + '" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="' + esc(s.color) + '" stop-opacity=".28"/><stop offset="1" stop-color="' + esc(s.color) + '" stop-opacity="0"/></linearGradient>';
        });
        out += '</defs>';
        for (var t = 0; t <= sc.max + 1e-9; t += sc.step) {
          var y = ys(t);
          out += '<line class="grid-line" x1="' + m.l + '" x2="' + (W - m.r) + '" y1="' + f1(y) + '" y2="' + f1(y) + '"/>';
          out += '<text x="' + (m.l - 7) + '" y="' + f1(y + 4) + '" text-anchor="end">' + esc(fmtY(t)) + '</text>';
        }
        for (var i = 0; i < n; i += every) out += '<text x="' + f1(xs(i)) + '" y="' + (h - 8) + '" text-anchor="middle">' + esc(o.labels[i]) + '</text>';
        o.series.forEach(function (s, si) {
          var pts = [];
          s.values.forEach(function (v, i2) { if (v !== null && v !== undefined) pts.push([xs(i2), ys(v)]); });
          if (!pts.length) return;
          var d = 'M' + f1(pts[0][0]) + ' ' + f1(pts[0][1]);
          if (o.smooth !== false && pts.length > 2) {
            for (var k = 0; k < pts.length - 1; k++) {
              var p0 = pts[k - 1] || pts[k], p1 = pts[k], p2 = pts[k + 1], p3 = pts[k + 2] || p2;
              var lo = Math.min(p1[1], p2[1]), hi = Math.max(p1[1], p2[1]);
              var c1y = Math.max(lo, Math.min(hi, p1[1] + (p2[1] - p0[1]) / 6));
              var c2y = Math.max(lo, Math.min(hi, p2[1] - (p3[1] - p1[1]) / 6));
              d += ' C' + f1(p1[0] + (p2[0] - p0[0]) / 6) + ' ' + f1(c1y) + ',' + f1(p2[0] - (p3[0] - p1[0]) / 6) + ' ' + f1(c2y) + ',' + f1(p2[0]) + ' ' + f1(p2[1]);
            }
          } else {
            for (var k2 = 1; k2 < pts.length; k2++) d += ' L' + f1(pts[k2][0]) + ' ' + f1(pts[k2][1]);
          }
          if (s.area !== false && o.area !== false) {
            out += '<path d="' + d + ' L' + f1(pts[pts.length - 1][0]) + ' ' + (m.t + ph) + ' L' + f1(pts[0][0]) + ' ' + (m.t + ph) + ' Z" fill="url(#' + gid + si + ')"/>';
          }
          out += '<path d="' + d + '" fill="none" stroke="' + esc(s.color) + '" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"/>';
          if (n <= 32) pts.forEach(function (p) { out += '<circle cx="' + f1(p[0]) + '" cy="' + f1(p[1]) + '" r="3" fill="var(--surface)" stroke="' + esc(s.color) + '" stroke-width="2"/>'; });
        });
        var cw = n === 1 ? pw : pw / (n - 1);
        for (var j = 0; j < n; j++) out += '<rect class="hot" data-i="' + j + '" x="' + f1(xs(j) - cw / 2) + '" y="' + m.t + '" width="' + f1(cw) + '" height="' + ph + '" fill="transparent"/>';
        out += '<line class="axis-line" x1="' + m.l + '" x2="' + (W - m.r) + '" y1="' + (m.t + ph) + '" y2="' + (m.t + ph) + '"/></svg>';
        return out + legendHtml(o.series);
      }
    });
  };

  /* ---------------------------------------------------------------
     Donut: opts = {items:[{label,value,color}], centerValue, centerLabel, size, tip}
     --------------------------------------------------------------- */
  C.donut = function (el, o) {
    var size = o.size || 190;
    var items = o.items.filter(function (i) { return i.value > 0; });
    var total = items.reduce(function (a, b) { return a + b.value; }, 0);
    mount(el, {
      tip: function (i) { var it = items[i]; return it ? '<b>' + esc(it.label) + '</b><br>' + esc(SQ.fmt.num(it.value)) + ' (' + Math.round((it.value / total) * 100) + '%)' : ''; },
      render: function () {
        var r = size / 2 - 14, cx = size / 2, cy = size / 2, circ = 2 * Math.PI * r;
        var out = '<div style="display:flex;align-items:center;gap:22px;flex-wrap:wrap;justify-content:center"><div style="position:relative;width:' + size + 'px;height:' + size + 'px">';
        out += '<svg width="' + size + '" height="' + size + '" viewBox="0 0 ' + size + ' ' + size + '" role="img" aria-label="' + esc(o.title || 'Donut chart') + '"><circle cx="' + cx + '" cy="' + cy + '" r="' + r + '" fill="none" stroke="var(--surface-3)" stroke-width="22"/>';
        var offset = 0;
        items.forEach(function (it, i) {
          var len = total ? (it.value / total) * circ : 0;
          var gap = items.length > 1 ? Math.min(3, len * 0.2) : 0;
          out += '<circle class="hot" data-i="' + i + '" cx="' + cx + '" cy="' + cy + '" r="' + r + '" fill="none" stroke="' + esc(it.color) + '" stroke-width="22" stroke-dasharray="' + f1(Math.max(0, len - gap)) + ' ' + f1(circ - Math.max(0, len - gap)) + '" stroke-dashoffset="' + f1(-offset) + '" transform="rotate(-90 ' + cx + ' ' + cy + ')"/>';
          offset += len;
        });
        out += '</svg><div style="position:absolute;inset:0;display:grid;place-items:center;text-align:center;pointer-events:none"><div><div style="font-size:26px;font-weight:800;letter-spacing:-.02em;line-height:1.1">' + esc(o.centerValue !== undefined ? o.centerValue : SQ.fmt.num(total)) + '</div><div class="tiny muted" style="text-transform:uppercase;letter-spacing:.06em;font-weight:650">' + esc(o.centerLabel || 'Total') + '</div></div></div></div>';
        out += '<div class="stack sm" style="min-width:150px">' + items.map(function (it) {
          return '<div class="row small" style="gap:8px"><i style="width:10px;height:10px;border-radius:3px;background:' + esc(it.color) + ';flex-shrink:0"></i><span class="grow truncate">' + esc(it.label) + '</span><b>' + Math.round((it.value / total) * 100) + '%</b></div>';
        }).join('') + '</div></div>';
        return out;
      }
    });
    if (!items.length) el.innerHTML = '<div class="empty" style="padding:20px">No data for this period.</div>';
  };

  /* ---------------------------------------------------------------
     Gauge: opts = {value, low, moderate, high, level}
     --------------------------------------------------------------- */
  C.gauge = function (el, o) {
    var low = o.low, mod = o.moderate, high = o.high;
    var maxScale = Math.max(high * 1.4, o.value * 1.15, high + 5);
    var w = 280, h = 156, cx = 140, cy = 138, r = 110;
    function pt(frac, rad) {
      var a = Math.PI * (1 - frac);
      return [cx + rad * Math.cos(a), cy - rad * Math.sin(a)];
    }
    function arc(f0, f1_, color) {
      var a = pt(f0, r), b = pt(f1_, r);
      var large = (f1_ - f0) > 0.5 ? 1 : 0;
      return '<path d="M' + f1(a[0]) + ' ' + f1(a[1]) + ' A' + r + ' ' + r + ' 0 ' + large + ' 1 ' + f1(b[0]) + ' ' + f1(b[1]) + '" fill="none" stroke="' + color + '" stroke-width="18" stroke-linecap="butt"/>';
    }
    var segs = [[0, low + 0.5, 'var(--crowd-low)'], [low + 0.5, mod + 0.5, 'var(--crowd-moderate)'], [mod + 0.5, high + 0.5, 'var(--crowd-high)'], [high + 0.5, maxScale, 'var(--crowd-critical)']];
    var out = '<div class="gauge"><svg viewBox="0 0 ' + w + ' ' + h + '" role="img" aria-label="Crowd gauge">';
    segs.forEach(function (s) { out += arc(Math.min(1, s[0] / maxScale), Math.min(1, s[1] / maxScale), s[2]); });
    var frac = Math.max(0, Math.min(1, o.value / maxScale));
    var tip = pt(frac, r - 28), base = pt(frac, 0);
    out += '<line x1="' + cx + '" y1="' + cy + '" x2="' + f1(tip[0]) + '" y2="' + f1(tip[1]) + '" stroke="var(--text)" stroke-width="4" stroke-linecap="round" style="transition:all .6s ease"/>';
    out += '<circle cx="' + cx + '" cy="' + cy + '" r="8" fill="var(--text)"/><circle cx="' + cx + '" cy="' + cy + '" r="3" fill="var(--surface)"/>';
    out += '</svg><div class="g-center"><div class="g-value" style="color:var(--' + ({ LOW: 'crowd-low', MODERATE: 'crowd-moderate', HIGH: 'crowd-high', CRITICAL: 'crowd-critical' }[o.level] || 'text') + ')">' + SQ.esc(o.value) + '</div><div class="g-label">people in queue</div></div></div>';
    el.innerHTML = out;
  };

  /* ---------------------------------------------------------------
     Heat-map: opts = {rows:[{label,cells:[24 numbers]}], unit, hourLabels}
     --------------------------------------------------------------- */
  C.heatmap = function (el, o) {
    var max = 0;
    o.rows.forEach(function (r) { r.cells.forEach(function (v) { max = Math.max(max, v); }); });
    function hl(h) { return h === 0 ? '12a' : h < 12 ? h + 'a' : h === 12 ? '12p' : (h - 12) + 'p'; }
    var out = '<div style="overflow-x:auto"><div class="heat" style="min-width:560px"><div></div>';
    for (var h = 0; h < 24; h++) out += '<div class="hh">' + (h % 3 === 0 ? hl(h) : '') + '</div>';
    o.rows.forEach(function (r) {
      out += '<div class="hl">' + esc(r.label) + '</div>';
      r.cells.forEach(function (v, hr) {
        var op = v <= 0 ? 0.06 : 0.14 + 0.86 * (v / (max || 1));
        out += '<div class="cell" style="opacity:' + op.toFixed(2) + '" title="' + esc(r.label + ' ' + hl(hr) + ': ' + SQ.fmt.dec(v, 1) + ' ' + (o.unit || '')) + '"></div>';
      });
    });
    out += '</div></div><div class="row tiny muted mt" style="gap:6px"><span>Quiet</span><span style="display:inline-flex;gap:2px">' +
      [0.1, 0.3, 0.5, 0.75, 1].map(function (x) { return '<i style="width:18px;height:10px;border-radius:3px;background:var(--primary);opacity:' + x + '"></i>'; }).join('') + '</span><span>Busy</span></div>';
    el.innerHTML = out;
  };

  /* ---------------------------------------------------------------
     Horizontal bar list (HTML): items = [{label, value, color, sub, display}]
     --------------------------------------------------------------- */
  C.barList = function (items, o) {
    o = o || {};
    var max = o.max || items.reduce(function (a, b) { return Math.max(a, b.value || 0); }, 0) || 1;
    return SQ.raw('<div class="stack" style="gap:12px">' + items.map(function (it) {
      var pct = Math.max(0, Math.min(100, ((it.value || 0) / max) * 100));
      return '<div><div class="row spread small" style="margin-bottom:5px"><span class="row" style="gap:8px">' + (it.color ? '<i style="width:10px;height:10px;border-radius:3px;background:' + esc(it.color) + '"></i>' : '') +
        '<span class="bold">' + esc(it.label) + '</span>' + (it.sub ? '<span class="muted tiny">' + esc(it.sub) + '</span>' : '') + '</span><b>' + esc(it.display !== undefined ? it.display : SQ.fmt.num(it.value)) + '</b></div>' +
        '<div class="progress" style="--c:' + esc(it.color || 'var(--primary)') + '"><i style="width:' + pct.toFixed(1) + '%"></i></div></div>';
    }).join('') + '</div>');
  };

})(window);
