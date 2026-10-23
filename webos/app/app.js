/* KoalaTV pour LG webOS — lecteur Xtream. ES5 pour compatibilité avec toutes les versions de webOS. */
(function () {
  'use strict';

  var VERSION = '1.6.0';
  var SERVICE = 'luna://com.koala.tv.service/fetch';

  var K = {
    LEFT: 37, UP: 38, RIGHT: 39, DOWN: 40, OK: 13, BACK: 461, ESC: 27, BKSP: 8,
    CH_UP: 33, CH_DOWN: 34, PLAY: 415, PAUSE: 19, STOP: 413, FF: 417, RW: 412,
    RED: 403, GREEN: 404, YELLOW: 405, BLUE: 406, INFO: 457
  };

  var STAR = '<svg class="fav" viewBox="0 0 24 24"><path d="M12 2.8l2.85 5.78 6.38.93-4.62 4.5 1.09 6.35L12 17.36l-5.7 3 1.09-6.35-4.62-4.5 6.38-.93z"/></svg>';
  var STAR_S = '<svg class="st" viewBox="0 0 24 24"><path d="M12 2.8l2.85 5.78 6.38.93-4.62 4.5 1.09 6.35L12 17.36l-5.7 3 1.09-6.35-4.62-4.5 6.38-.93z"/></svg>';
  var LOCK = '<svg class="lk" viewBox="0 0 24 24"><rect x="5" y="10.5" width="14" height="10" rx="1.5"/><path d="M8.5 10.5v-3a3.5 3.5 0 0 1 7 0v3"/></svg>';

  /* ---------- Outils ---------- */

  function $(id) { return document.getElementById(id); }

  function esc(s) {
    return String(s === null || s === undefined ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  function str(v) { return v === null || v === undefined ? '' : String(v).replace(/^\s+|\s+$/g, ''); }

  function cls(el, name, on) {
    var c = ' ' + el.className + ' ', has = c.indexOf(' ' + name + ' ') >= 0;
    if (on && !has) el.className = (el.className + ' ' + name).replace(/^\s+/, '');
    else if (!on && has) el.className = c.replace(' ' + name + ' ', ' ').replace(/^\s+|\s+$/g, '');
  }

  function load(k, d) {
    try { var v = localStorage.getItem('ktv.' + k); return v === null ? d : JSON.parse(v); } catch (e) { return d; }
  }
  function save(k, v) { try { localStorage.setItem('ktv.' + k, JSON.stringify(v)); } catch (e) { /* stockage plein */ } }

  function pad2(n) { return (n < 10 ? '0' : '') + n; }
  function fmtTime(sec) {
    sec = Math.max(0, Math.floor(sec || 0));
    var h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60), s = sec % 60;
    return h ? h + ':' + pad2(m) + ':' + pad2(s) : m + ':' + pad2(s);
  }
  function hm(sec) { var d = new Date(sec * 1000); return pad2(d.getHours()) + ':' + pad2(d.getMinutes()); }
  function fmtNum(n) { return String(n).replace(/\B(?=(\d{3})+(?!\d))/g, ' '); }
  function plural(n, one, many) { return fmtNum(n) + ' ' + (n > 1 ? many : one); }

  function fold(s) {
    s = String(s).toLowerCase();
    if (s.normalize) s = s.normalize('NFD').replace(/[̀-ͯ]/g, '');
    return s;
  }

  function setTransform(el, v) { el.style.webkitTransform = v; el.style.transform = v; }

  var REM = 20;
  function sizeRoot() {
    REM = Math.round(window.innerWidth / 96 * 100) / 100;
    document.documentElement.style.fontSize = REM + 'px';
  }

  /* ---------- Réglages ---------- */

  var P = {
    server: load('server', ''), user: load('user', ''), pass: load('pass', ''), exp: load('exp', ''),
    live: load('live', 'm3u8'), favs: load('favs', {}), pos: load('pos', {}), hist: load('hist', [])
  };
  function setP(k, v) { P[k] = v; save(k, v); }
  function loggedIn() { return !!(P.server && P.user && P.pass); }

  /* Films et épisodes commencés, du plus récent au plus ancien (rangée « Reprendre ») */
  var Hist = {
    remove: function (key) {
      var l = [], changed = false;
      for (var i = 0; i < P.hist.length; i++) { if (P.hist[i].k === key) changed = true; else l.push(P.hist[i]); }
      if (changed) setP('hist', l);
    },
    update: function (it, pos, dur) {
      var l = [{ k: it.key, t: it.title, u: it.url, i: it.icon || '', p: Math.floor(pos), d: Math.floor(dur || 0), sid: it.sid || '', sn: it.sname || '' }];
      for (var i = 0; i < P.hist.length && l.length < 20; i++) if (P.hist[i].k !== it.key) l.push(P.hist[i]);
      setP('hist', l);
    }
  };

  function liveItem(it) { return { title: it.name, url: X.url('live', it.id), live: true, id: it.id, num: it.num || 0, icon: it.icon }; }

  /* ---------- Code parental : catégories masquées ('h') ou verrouillées ('l') ---------- */

  var Parental = {
    states: load('cats', {}),
    unlocked: false,
    state: function (kind, id) { return this.states[kind + ':' + id] || 'v'; },
    set: function (kind, id, st) {
      if (st === 'v') delete this.states[kind + ':' + id]; else this.states[kind + ':' + id] = st;
      save('cats', this.states);
    },
    hasCode: function () { return !!load('pcode', ''); },
    hash: function (code) {
      var h = 2166136261, t = 'koalatv:' + code;
      for (var i = 0; i < t.length; i++) { h ^= t.charCodeAt(i); h = (h * 16777619) >>> 0; }
      return h.toString(16);
    },
    check: function (code) { return this.hasCode() && this.hash(code) === load('pcode', ''); },
    setCode: function (code) { save('pcode', code ? this.hash(code) : ''); this.unlocked = !code; },
    blocked: function (kind, id) {
      var st = this.state(kind, id);
      return st === 'h' || (st === 'l' && this.hasCode() && !this.unlocked);
    },
    locked: function (kind, id) { return this.hasCode() && this.state(kind, id) === 'l'; },
    ask: function (cb) {
      var self = this;
      if (!this.hasCode() || this.unlocked) return cb();
      Pin.show('Code parental', function (code) {
        if (self.check(code)) { self.unlocked = true; cb(); } else toast('Code incorrect');
      });
    },
    create: function (cb) {
      var self = this;
      Pin.show('Nouveau code parental', function (a) {
        Pin.show('Confirme le code', function (b) {
          if (a !== b) { toast('Les deux codes sont différents'); return; }
          self.setCode(a);
          self.unlocked = true;
          toast('Code parental enregistré');
          if (cb) cb();
        });
      });
    },
    settings: function () {
      var self = this;
      if (!this.hasCode()) { this.create(); return; }
      Pin.show('Code parental actuel', function (code) {
        if (!self.check(code)) { toast('Code incorrect'); return; }
        self.unlocked = true;
        Dialog.show('Code parental', '', [
          { label: 'Changer le code', fn: function () { self.create(); } },
          { label: 'Supprimer le code', fn: function () { self.setCode(''); toast('Code parental supprimé'); } },
          { label: 'Fermer' }
        ]);
      });
    }
  };

  /* Clavier à 4 chiffres : flèches + OK, ou touches numériques de la télécommande */
  var Pin = {
    open: false, code: '', idx: 0, cb: null,
    KEYS: ['1', '2', '3', '4', '5', '6', '7', '8', '9', 'del', '0', 'ok'],
    show: function (title, cb) {
      var self = this;
      this.open = true;
      this.code = '';
      this.idx = 0;
      this.cb = cb;
      $('pin-title').textContent = title;
      var h = '';
      for (var i = 0; i < this.KEYS.length; i++) {
        var k = this.KEYS[i];
        h += '<div class="pk" data-k="' + i + '">' + (k === 'del'
          ? '<svg viewBox="0 0 24 24"><path d="M9 6h10.5a1.5 1.5 0 0 1 1.5 1.5v9a1.5 1.5 0 0 1-1.5 1.5H9l-5.5-6z"/><path d="M12.5 9.5l5 5M17.5 9.5l-5 5"/></svg>'
          : k === 'ok' ? 'OK' : k) + '</div>';
      }
      $('pin-keys').innerHTML = h;
      $('pin-keys').onclick = function (e) {
        var n = e.target;
        while (n && n.getAttribute && n.getAttribute('data-k') === null) n = n.parentNode;
        if (n && n.getAttribute) { self.idx = parseInt(n.getAttribute('data-k'), 10); self.press(); }
      };
      cls($('pin'), 'on', true);
      this.paint();
    },
    paint: function () {
      var d = '';
      for (var i = 0; i < 4; i++) d += '<span class="' + (i < this.code.length ? 'on' : '') + '"></span>';
      $('pin-dots').innerHTML = d;
      var n = $('pin-keys').children;
      for (i = 0; i < n.length; i++) n[i].className = 'pk' + (i === this.idx ? ' focus' : '');
    },
    close: function () { this.open = false; cls($('pin'), 'on', false); },
    type: function (d) {
      var self = this;
      if (this.code.length >= 4) return;
      this.code += d;
      this.paint();
      if (this.code.length === 4) setTimeout(function () { self.submit(); }, 180);
    },
    submit: function () {
      if (!this.open) return;
      var cb = this.cb, code = this.code;
      if (code.length !== 4) { toast('Le code fait 4 chiffres'); return; }
      this.close();
      if (cb) cb(code);
    },
    press: function () {
      var k = this.KEYS[this.idx];
      if (k === 'del') { this.code = this.code.slice(0, -1); this.paint(); }
      else if (k === 'ok') this.submit();
      else this.type(k);
    },
    key: function (c) {
      var r = Math.floor(this.idx / 3), col = this.idx % 3;
      if (c >= 48 && c <= 57) this.type(String(c - 48));
      else if (c >= 96 && c <= 105) this.type(String(c - 96));
      else if (c === K.LEFT && col > 0) this.idx--;
      else if (c === K.RIGHT && col < 2) this.idx++;
      else if (c === K.UP && r > 0) this.idx -= 3;
      else if (c === K.DOWN && r < 3) this.idx += 3;
      else if (c === K.OK) { this.press(); return; }
      else if (c === K.BACK || c === K.ESC) { this.close(); return; }
      else if (c === K.BKSP) { this.code = this.code.slice(0, -1); }
      this.paint();
    }
  };

  /** Liste de lecture d'une saison : les épisodes s'enchaînent. */
  function episodeList(sid, sname, icon, eps) {
    var list = [];
    for (var i = 0; i < eps.length; i++) {
      var e = eps[i];
      list.push({
        title: sname + ' · ' + (e.season > 0 ? 'S' + e.season + ' ' : '') + 'É' + e.num,
        url: X.url('episode', e.id, e.ext), live: false, key: 'e' + e.id, icon: icon, sid: sid, sname: sname
      });
    }
    return list;
  }

  /* ---------- Réseau ---------- */

  var useService = false;
  var bridges = [];

  function serviceGet(url, cb) {
    var chunks = [], done = false, bridge;
    var timer = setTimeout(function () { finish(new Error('Le serveur ne répond pas')); }, 90000);
    function finish(err, txt) {
      if (done) return;
      done = true;
      clearTimeout(timer);
      var i = bridges.indexOf(bridge);
      if (i >= 0) bridges.splice(i, 1);
      cb(err, txt);
    }
    try {
      bridge = new window.PalmServiceBridge();
      bridges.push(bridge);
      bridge.onservicecallback = function (raw) {
        var m;
        try { m = JSON.parse(raw); } catch (e) { return finish(new Error('Réponse invalide')); }
        if (m.returnValue === false) return finish(new Error(m.errorText || 'Serveur injoignable'));
        if (typeof m.chunk === 'string') chunks[m.index] = m.chunk;
        if (m.done) finish(null, chunks.join(''));
      };
      bridge.call(SERVICE, JSON.stringify({ url: url, subscribe: true }));
    } catch (e) {
      finish(new Error('Serveur injoignable'));
    }
  }

  function httpGet(url, cb) {
    if (useService) return serviceGet(url, cb);
    var x = new XMLHttpRequest(), done = false;
    function fin(err, txt) { if (!done) { done = true; cb(err, txt); } }
    function fallback() {
      if (done) return;
      if (window.PalmServiceBridge) { done = true; useService = true; serviceGet(url, cb); }
      else fin(new Error('Serveur injoignable'));
    }
    x.open('GET', url, true);
    x.timeout = 60000;
    x.onload = function () {
      if (x.status >= 200 && x.status < 300) fin(null, x.responseText);
      else if (x.status === 0) fallback();
      else fin(new Error('Erreur du serveur (' + x.status + ')'));
    };
    x.onerror = fallback;
    x.ontimeout = function () { fin(new Error('Le serveur ne répond pas')); };
    try { x.send(); } catch (e) { fallback(); }
  }

  function getJson(url, cb) {
    httpGet(url, function (err, txt) {
      if (err) return cb(err);
      var j;
      try { j = JSON.parse(txt); } catch (e) { return cb(new Error('Réponse invalide du serveur')); }
      cb(null, j);
    });
  }

  /* ---------- API Xtream ---------- */

  function normServer(raw) {
    var m = String(raw || '').replace(/^\s+|\s+$/g, '').match(/^(https?:\/\/)?([^\/?#\s]+)/i);
    if (!m) return '';
    return (m[1] ? m[1].toLowerCase() : 'http://') + m[2];
  }

  function decodeParam(v) { try { return decodeURIComponent(v.replace(/\+/g, ' ')); } catch (e) { return v; } }

  function parseM3u(raw) {
    var s = String(raw || ''), u = s.match(/[?&]username=([^&#\s]*)/), p = s.match(/[?&]password=([^&#\s]*)/);
    if (!u || !p) return null;
    return { server: normServer(s), user: decodeParam(u[1]), pass: decodeParam(p[1]) };
  }

  function apiUrl(server, user, pass, action, extra) {
    return server + '/player_api.php?username=' + encodeURIComponent(user) + '&password=' + encodeURIComponent(pass) +
      (action ? '&action=' + action : '') + (extra || '');
  }
  function api(action, extra) { return apiUrl(P.server, P.user, P.pass, action, extra); }

  function b64(s) {
    try { return decodeURIComponent(escape(window.atob(s))); } catch (e) {
      try { return window.atob(s); } catch (e2) { return s; }
    }
  }

  var X = {
    login: function (server, user, pass, cb) {
      getJson(apiUrl(server, user, pass), function (err, j) {
        if (err) return cb(err);
        var ui = j && j.user_info;
        if (!ui || str(ui.auth) !== '1') return cb(new Error('Identifiant ou mot de passe incorrect'));
        var st = str(ui.status).toLowerCase();
        if (st && st !== 'active') return cb(new Error(st === 'expired' ? 'Abonnement expiré' : 'Abonnement inactif'));
        cb(null, ui);
      });
    },

    episodes: function (id, cb) {
      getJson(api('get_series_info', '&series_id=' + encodeURIComponent(id)), function (err, j) {
        if (err) return cb(err);
        var out = [], eps = j && j.episodes, k, i;
        function add(arr, hint) {
          for (var n = 0; n < arr.length; n++) {
            var e = arr[n];
            if (!e || !str(e.id)) continue;
            var info = e.info || {};
            out.push({
              id: str(e.id), title: str(e.title), ext: str(e.container_extension) || 'mp4',
              season: parseInt(e.season, 10) || hint, num: parseInt(e.episode_num, 10) || (n + 1),
              plot: str(info.plot || info.description)
            });
          }
        }
        if (eps && eps.length !== undefined) {
          for (i = 0; i < eps.length; i++) if (eps[i] && eps[i].length) add(eps[i], i + 1);
        } else if (eps && typeof eps === 'object') {
          for (k in eps) if (eps.hasOwnProperty(k) && eps[k] && eps[k].length) add(eps[k], parseInt(k, 10) || 0);
        }
        out.sort(function (a, b) { return a.season - b.season || a.num - b.num; });
        cb(null, out);
      });
    },

    epg: function (streamId, cb) {
      getJson(api('get_short_epg', '&stream_id=' + encodeURIComponent(streamId) + '&limit=2'), function (err, j) {
        var out = [];
        if (!err && j && j.epg_listings && j.epg_listings.length) {
          for (var i = 0; i < j.epg_listings.length; i++) {
            var e = j.epg_listings[i], t = str(b64(str(e.title))), ts = parseInt(e.start_timestamp, 10),
              te = parseInt(e.stop_timestamp || e.end_timestamp, 10);
            if (!t || (te && te * 1000 <= Date.now())) continue;
            out.push({ h: ts ? hm(ts) : '', t: t, s: ts || 0, e: te || 0 });
          }
        }
        cb(out);
      });
    },

    movie: function (id, cb) {
      getJson(api('get_vod_info', '&vod_id=' + encodeURIComponent(id)), function (err, j) {
        if (err || !j) return cb(err || new Error('Fiche indisponible'));
        var info = j.info || {}, data = j.movie_data || {};
        var back = info.backdrop_path;
        if (back && back.length !== undefined && typeof back !== 'string') back = back[0];
        var secs = parseInt(info.duration_secs, 10);
        if (!secs && info.duration) {
          var d = String(info.duration).split(':');
          if (d.length === 3) secs = (parseInt(d[0], 10) || 0) * 3600 + (parseInt(d[1], 10) || 0) * 60;
        }
        var date = str(info.releasedate || info.release_date), y = date.match(/\b(19|20)\d{2}\b/), r = parseFloat(info.rating);
        cb(null, {
          title: str(info.name || data.name), poster: str(info.movie_image || info.cover_big), backdrop: str(back),
          plot: str(info.plot || info.description), year: y ? y[0] : '', minutes: secs ? Math.floor(secs / 60) : 0,
          genre: str(info.genre), rating: r > 0 ? r.toFixed(1).replace('.', ',') : '', director: str(info.director),
          cast: str(info.cast || info.actors), ext: str(data.container_extension)
        });
      });
    },

    url: function (kind, id, ext) {
      var base = P.server + '/' + ({ live: 'live', movie: 'movie', episode: 'series' }[kind]) + '/' +
        encodeURIComponent(P.user) + '/' + encodeURIComponent(P.pass) + '/' + id;
      return base + '.' + (kind === 'live' ? P.live : ext);
    }
  };


  /* ---------- Listes : mémoire → appareil (IndexedDB) → serveur ---------- */

  var Lists = (function () {
    var FORMAT = 2, MAX_AGE = 6 * 3600 * 1000, STORE = 'lists';
    var mem = {}, waiting = {}, db = null, dbState = 0, dbQueue = [];
    var self = { onUpdated: null };

    function account() { return P.server + '|' + P.user; }

    function withDb(cb) {
      if (dbState === 2) return cb(db);
      if (dbState === 3) return cb(null);
      dbQueue.push(cb);
      if (dbState === 1) return;
      dbState = 1;
      function done(d) {
        db = d;
        dbState = d ? 2 : 3;
        var q = dbQueue; dbQueue = [];
        for (var i = 0; i < q.length; i++) q[i](d);
      }
      try {
        var rq = window.indexedDB.open('koalatv', 1);
        rq.onupgradeneeded = function () { rq.result.createObjectStore(STORE); };
        rq.onsuccess = function () { done(rq.result); };
        rq.onerror = function () { done(null); };
        rq.onblocked = function () { done(null); };
      } catch (e) { done(null); }
    }

    function dbGet(kind, cb) {
      withDb(function (d) {
        if (!d) return cb(null);
        try {
          var rq = d.transaction(STORE, 'readonly').objectStore(STORE).get(kind);
          rq.onsuccess = function () { cb(rq.result || null); };
          rq.onerror = function () { cb(null); };
        } catch (e) { cb(null); }
      });
    }

    function dbPut(kind, rec) {
      withDb(function (d) {
        if (!d) return;
        try { d.transaction(STORE, 'readwrite').objectStore(STORE).put(rec, kind); } catch (e) { /* stockage plein */ }
      });
    }

    function pack(kind, data) {
      var c = [], it = [], i, o;
      for (i = 0; i < data.cats.length; i++) c.push([data.cats[i].id, data.cats[i].name]);
      for (i = 0; i < data.items.length; i++) {
        o = data.items[i];
        it.push([o.id, o.name, o.icon, o.cat, o.ext, o.num, o.archive, o.rating]);
      }
      return { v: FORMAT, account: account(), savedAt: data.savedAt, cats: c, items: it };
    }

    function unpack(kind, rec) {
      if (!rec || rec.v !== FORMAT || rec.account !== account()) return null;
      var cats = [], items = [], i, a;
      for (i = 0; i < rec.cats.length; i++) cats.push({ id: rec.cats[i][0], name: rec.cats[i][1] });
      for (i = 0; i < rec.items.length; i++) {
        a = rec.items[i];
        items.push({ kind: kind, id: a[0], name: a[1], icon: a[2], cat: a[3], ext: a[4], num: a[5], archive: a[6], rating: a[7] });
      }
      return { cats: cats, items: items, savedAt: rec.savedAt };
    }

    function download(kind, cb) {
      var A = {
        live: ['get_live_categories', 'get_live_streams'],
        movie: ['get_vod_categories', 'get_vod_streams'],
        series: ['get_series_categories', 'get_series']
      }[kind];
      getJson(api(A[0]), function (err, rawCats) {
        if (err) return cb(err);
        getJson(api(A[1]), function (err2, rawList) {
          if (err2) return cb(err2);
          var cats = [], items = [], i, o, cat, it;
          if (rawCats && rawCats.length) {
            for (i = 0; i < rawCats.length; i++) {
              o = rawCats[i];
              if (o) cats.push({ id: str(o.category_id), name: str(o.category_name) });
            }
          }
          if (rawList && rawList.length) {
            for (i = 0; i < rawList.length; i++) {
              o = rawList[i];
              if (!o) continue;
              cat = str(o.category_id);
              if (!cat && o.category_ids && o.category_ids.length) cat = str(o.category_ids[0]);
              it = {
                kind: kind,
                id: str(kind === 'series' ? o.series_id : o.stream_id),
                name: str(o.name),
                icon: str(kind === 'series' ? o.cover : o.stream_icon),
                cat: cat,
                ext: kind === 'movie' ? (str(o.container_extension) || 'mp4') : '',
                num: parseInt(o.num, 10) || 0,
                archive: str(o.tv_archive) === '1' ? (parseInt(o.tv_archive_duration, 10) || 0) : 0,
                rating: str(o.rating)
              };
              if (it.id) items.push(it);
            }
          }
          cb(null, { cats: cats, items: items, savedAt: Date.now() });
        });
      });
    }

    function same(a, b) {
      if (a.items.length !== b.items.length || a.cats.length !== b.cats.length) return false;
      for (var i = 0; i < a.items.length; i++) {
        var x = a.items[i], y = b.items[i];
        if (x.id !== y.id || x.name !== y.name || x.cat !== y.cat || x.icon !== y.icon) return false;
      }
      for (i = 0; i < a.cats.length; i++) if (a.cats[i].id !== b.cats[i].id || a.cats[i].name !== b.cats[i].name) return false;
      return true;
    }

    function refresh(kind, cb) {
      download(kind, function (err, fresh) {
        if (err) { if (cb) cb(err); return; }
        var old = mem[kind];
        mem[kind] = fresh;
        dbPut(kind, pack(kind, fresh));
        if (old && !same(old, fresh) && self.onUpdated) self.onUpdated(kind);
        if (cb) cb(null);
      });
    }

    /** Liste prête à afficher : mémoire, sinon appareil, sinon serveur. */
    self.get = function (kind, cb) {
      if (mem[kind]) return cb(null, mem[kind]);
      if (waiting[kind]) { waiting[kind].push(cb); return; }
      waiting[kind] = [cb];
      function finish(err, data) {
        var q = waiting[kind]; waiting[kind] = null;
        for (var i = 0; i < q.length; i++) q[i](err, data);
      }
      dbGet(kind, function (rec) {
        var disk = unpack(kind, rec);
        if (disk) {
          mem[kind] = disk;
          finish(null, disk);
          if (Date.now() - disk.savedAt > MAX_AGE) refresh(kind);
          return;
        }
        download(kind, function (err, fresh) {
          if (err) return finish(err);
          mem[kind] = fresh;
          dbPut(kind, pack(kind, fresh));
          finish(null, fresh);
        });
      });
    };

    /** Prépare les trois listes en arrière-plan, l'une après l'autre. */
    self.warmAll = function () {
      var kinds = ['live', 'movie', 'series'], n = 0;
      (function next() {
        if (n >= kinds.length) return;
        self.get(kinds[n++], function () { setTimeout(next, 50); });
      })();
    };

    /** Force le téléchargement des trois listes. */
    self.refreshAll = function (done) {
      var kinds = ['live', 'movie', 'series'], n = 0, ok = true;
      (function next() {
        if (n >= kinds.length) return done(ok);
        refresh(kinds[n++], function (err) { if (err) ok = false; next(); });
      })();
    };

    self.clear = function () {
      mem = {};
      withDb(function (d) {
        if (!d) return;
        try { d.transaction(STORE, 'readwrite').objectStore(STORE).clear(); } catch (e) { /* ignoré */ }
      });
    };

    return self;
  })();

  /* ---------- Éléments communs ---------- */

  var toastTimer;
  function toast(msg) {
    var t = $('toast');
    t.textContent = msg;
    cls(t, 'on', true);
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { cls(t, 'on', false); }, 2200);
  }

  function spinner(on) { cls($('spinner'), 'on', on); }

  var Dialog = {
    open: false, opts: [], idx: 0, onCancel: null,
    show: function (title, msg, opts, onCancel, idx) {
      this.open = true;
      this.opts = opts;
      this.idx = idx || 0;
      this.onCancel = onCancel || null;
      $('d-title').textContent = title || '';
      $('d-msg').textContent = msg || '';
      $('d-msg').style.display = msg ? 'block' : 'none';
      var h = '';
      for (var i = 0; i < opts.length; i++) h += '<div class="opt" data-i="' + i + '">' + esc(opts[i].label) + '</div>';
      $('d-opts').innerHTML = h;
      cls($('dialog'), 'on', true);
      this.paint();
    },
    paint: function () {
      var n = $('d-opts').children;
      for (var i = 0; i < n.length; i++) n[i].className = 'opt' + (i === this.idx ? ' focus' : '');
    },
    close: function () { this.open = false; cls($('dialog'), 'on', false); },
    pick: function (i) {
      var o = this.opts[i];
      this.close();
      if (o && o.fn) o.fn();
    },
    cancel: function () {
      var cb = this.onCancel;
      this.close();
      if (cb) cb();
    },
    key: function (c) {
      if (c === K.UP && this.idx > 0) { this.idx--; this.paint(); }
      else if (c === K.DOWN && this.idx < this.opts.length - 1) { this.idx++; this.paint(); }
      else if (c === K.OK) this.pick(this.idx);
      else if (c === K.BACK || c === K.ESC || c === K.BKSP) this.cancel();
    }
  };
  $('d-opts').addEventListener('click', function (e) {
    var n = e.target;
    while (n && n.getAttribute && n.getAttribute('data-i') === null) n = n.parentNode;
    if (n && n.getAttribute) Dialog.pick(parseInt(n.getAttribute('data-i'), 10));
  });

  /* ---------- Liste virtualisée (seuls les éléments visibles existent dans le DOM) ---------- */

  function VList(box, o) {
    var self = this;
    this.box = box;
    this.o = o;
    this.items = [];
    this.idx = 0;
    this.sel = -1;
    this.active = false;
    this.top = 0;
    this.nodes = {};
    this.inner = document.createElement('div');
    this.inner.className = 'vinner';
    box.appendChild(this.inner);
    this.inner.addEventListener('click', function (e) {
      var n = e.target;
      while (n && n !== self.inner && n._i === undefined) n = n.parentNode;
      if (n && n._i !== undefined) {
        self.idx = n._i;
        if (self.o.onPointer) self.o.onPointer();
        self.render();
        if (self.o.onOk) self.o.onOk(n._i);
      }
    });
  }
  VList.prototype.measure = function () {
    var h = this.box.clientHeight, w = this.box.clientWidth;
    if (this.o.itemW) {
      // grille d'affiches : hauteur déduite de la largeur pour garder le format 2:3
      this.cols = Math.max(1, Math.floor(w / (this.o.itemW * REM)));
      this.rowH = Math.round((w / this.cols - 0.9 * REM) * 1.5 + 3.5 * REM);
      this.vis = Math.max(1, Math.floor(h / this.rowH));
    } else {
      // liste : lignes ajustées pour remplir exactement la hauteur
      this.cols = 1;
      this.vis = Math.max(1, Math.round(h / (this.o.rowH * REM)));
      this.rowH = h / this.vis;
    }
  };
  VList.prototype.set = function (items, idx, keepTop) {
    this.items = items || [];
    this.idx = Math.max(0, Math.min(idx || 0, this.items.length - 1));
    if (!keepTop) this.top = 0;
    this.inner.innerHTML = '';
    this.nodes = {};
    this.measure();
    this.render();
  };
  VList.prototype.redraw = function () { this.set(this.items, this.idx, true); };
  VList.prototype.render = function () {
    var n = this.items.length, cols = this.cols, rowH = this.rowH, vis = this.vis;
    var rows = Math.ceil(n / cols), fr = Math.floor(this.idx / cols), margin = vis >= 4 ? 1 : 0;
    if (fr - margin < this.top) this.top = Math.max(0, fr - margin);
    else if (fr + margin > this.top + vis - 1) this.top = fr + margin - vis + 1;
    this.top = Math.max(0, Math.min(this.top, Math.max(0, rows - vis)));
    this.inner.style.height = rows * rowH + 'px';
    setTransform(this.inner, 'translate3d(0,' + (-this.top * rowH) + 'px,0)');
    var from = Math.max(0, (this.top - 2) * cols), to = Math.min(n, (this.top + vis + 3) * cols);
    var keep = {}, i, el, c;
    for (i = from; i < to; i++) {
      el = this.nodes[i];
      if (!el) {
        el = document.createElement('div');
        el._i = i;
        el.style.cssText = 'left:' + ((i % cols) * 100 / cols) + '%;top:' + (Math.floor(i / cols) * rowH) +
          'px;width:' + (100 / cols) + '%;height:' + rowH + 'px';
        el.innerHTML = this.o.render(this.items[i], i);
        this.inner.appendChild(el);
      }
      c = 'vitem' + (this.o.itemW ? ' grid' : '') + (i === this.idx && this.active ? ' focused' : '') + (i === this.sel ? ' selected' : '');
      if (el.className !== c) el.className = c;
      keep[i] = el;
    }
    for (var k in this.nodes) if (this.nodes.hasOwnProperty(k) && !keep[k]) this.inner.removeChild(this.nodes[k]);
    this.nodes = keep;
  };
  VList.prototype.refresh = function (i) {
    var el = this.nodes[i];
    if (el) el.innerHTML = this.o.render(this.items[i], i);
  };
  VList.prototype.focus = function (on) { this.active = on; this.render(); };
  VList.prototype.select = function (i) { this.sel = i; this.render(); };
  VList.prototype.col = function () { return this.idx % this.cols; };
  VList.prototype.move = function (dx, dy) {
    var n = this.items.length, cols = this.cols, i = this.idx;
    if (!n) return false;
    if (dx) {
      var c = i % cols;
      if ((dx < 0 && c === 0) || (dx > 0 && (c === cols - 1 || i + 1 >= n))) return false;
      i += dx;
    }
    if (dy) {
      var j = i + dy * cols;
      if (j < 0) return false;
      if (j >= n) {
        if (Math.floor((n - 1) / cols) > Math.floor(i / cols)) j = n - 1;
        else return false;
      }
      i = j;
    }
    this.idx = i;
    this.render();
    return true;
  };
  VList.prototype.page = function (dir) {
    var j = Math.max(0, Math.min(this.items.length - 1, this.idx + dir * this.vis * this.cols));
    if (j === this.idx) return false;
    this.idx = j;
    this.render();
    return true;
  };

  /* ---------- Navigation entre écrans ---------- */

  var screens = {}, stack = [], cur = null;

  function show(name) {
    for (var k in screens) if (screens.hasOwnProperty(k)) cls(screens[k].el, 'on', k === name);
    cur = screens[name];
  }
  function go(name, params) {
    if (cur && cur.leave) cur.leave();
    stack.push(name);
    show(name);
    cur.enter(params || {});
  }
  function reset(name, params) {
    if (cur && cur.leave) cur.leave();
    stack = [name];
    show(name);
    cur.enter(params || {});
  }
  function back() {
    if (stack.length <= 1) {
      Dialog.show('Quitter KoalaTV ?', '', [
        { label: 'Quitter', fn: function () { window.close(); } },
        { label: 'Annuler' }
      ]);
      return;
    }
    if (cur && cur.leave) cur.leave();
    stack.pop();
    show(stack[stack.length - 1]);
    if (cur.resume) cur.resume();
  }

  /* ---------- Écran de connexion ---------- */

  screens.login = {
    el: $('s-login'),
    rows: null,
    fields: [$('l-server'), $('l-user'), $('l-pass')],
    idx: 0,
    busy: false,
    init: function () {
      var self = this;
      this.rows = this.el.querySelectorAll('[data-i]');
      for (var i = 0; i < this.rows.length; i++) {
        this.rows[i].addEventListener('click', function (e) {
          e.preventDefault();
          self.idx = parseInt(this.getAttribute('data-i'), 10);
          self.paint();
          self.ok();
        });
      }
      for (i = 0; i < this.fields.length; i++) {
        this.fields[i].addEventListener('blur', function () { cls(this.parentNode, 'editing', false); });
      }
    },
    enter: function () {
      this.fields[0].value = P.server;
      this.fields[1].value = P.user;
      this.fields[2].value = '';
      this.status('', false);
      this.idx = P.server && P.user ? 2 : 0;
      this.paint();
    },
    paint: function () {
      for (var i = 0; i < this.rows.length; i++) cls(this.rows[i], 'focus', i === this.idx);
    },
    status: function (msg, err) {
      var s = $('l-status');
      s.textContent = msg;
      cls(s, 'err', !!err);
    },
    edit: function (i) {
      var f = this.fields[i];
      cls(f.parentNode, 'editing', true);
      f.focus();
      try { f.setSelectionRange(f.value.length, f.value.length); } catch (e) { /* ignoré */ }
    },
    ok: function () {
      if (this.idx < 3) this.edit(this.idx);
      else this.submit();
    },
    key: function (c) {
      if (this.busy) return true;
      if (c === K.UP && this.idx > 0) { this.idx--; this.paint(); return true; }
      if (c === K.DOWN && this.idx < 3) { this.idx++; this.paint(); return true; }
      if (c === K.OK) { this.ok(); return true; }
      return false;
    },
    inputDone: function (input, c) {
      var i = this.fields.indexOf(input);
      if (i < 0) return;
      if (i === 0) this.applyM3u();
      if (c === K.OK || c === K.DOWN) this.idx = i + 1;
      else if (c === K.UP) this.idx = Math.max(0, i - 1);
      this.paint();
      if (c === K.OK && this.idx < 3 && !this.fields[this.idx].value) this.edit(this.idx);
    },
    applyM3u: function () {
      var m = parseM3u(this.fields[0].value);
      if (m) {
        this.fields[0].value = m.server;
        this.fields[1].value = m.user;
        this.fields[2].value = m.pass;
      }
    },
    submit: function () {
      var self = this;
      this.applyM3u();
      var server = normServer(this.fields[0].value), user = str(this.fields[1].value), pass = str(this.fields[2].value);
      if (!server || !user || !pass) { this.status('Renseigne les trois champs', true); return; }
      this.busy = true;
      this.status('Connexion…', false);
      spinner(true);
      X.login(server, user, pass, function (err, ui) {
        self.busy = false;
        spinner(false);
        if (err) { self.status(err.message, true); return; }
        if (server !== P.server || user !== P.user) Lists.clear();
        setP('server', server);
        setP('user', user);
        setP('pass', pass);
        setP('exp', str(ui.exp_date));
        reset('home');
      });
    }
  };

  /* ---------- Accueil ---------- */

  function resumeLeft(h) {
    var m = h.d > 0 ? Math.max(0, Math.floor((h.d - h.p) / 60)) : 0;
    if (m >= 60) return 'Reste ' + Math.floor(m / 60) + ' h ' + pad2(m % 60);
    if (m > 0) return 'Reste ' + m + ' min';
    return 'Reprendre à ' + fmtTime(h.p);
  }

  screens.home = {
    el: $('s-home'),
    idx: 0,
    rIdx: 0,
    zone: 'tiles',
    tiles: null,
    init: function () {
      var self = this;
      this.tiles = this.el.querySelectorAll('.tile');
      for (var i = 0; i < this.tiles.length; i++) {
        this.tiles[i].addEventListener('click', function () {
          self.zone = 'tiles';
          self.idx = parseInt(this.getAttribute('data-i'), 10);
          self.paint();
          self.open();
        });
      }
      $('h-row').addEventListener('click', function (e) {
        var n = e.target;
        while (n && n.getAttribute && n.getAttribute('data-r') === null) n = n.parentNode;
        if (!n || !n.getAttribute) return;
        self.zone = 'resume';
        self.rIdx = parseInt(n.getAttribute('data-r'), 10);
        self.paint();
        self.resumeItem();
      });
    },
    enter: function () {
      var exp = parseInt(P.exp, 10), txt = P.user;
      if (exp > 0) {
        var d = new Date(exp * 1000);
        txt += ' · expire le ' + pad2(d.getDate()) + '/' + pad2(d.getMonth() + 1) + '/' + d.getFullYear();
      }
      $('h-account').textContent = txt;
      this.zone = 'tiles';
      this.drawResume();
      this.paint();
      Lists.warmAll();
    },
    resume: function () { this.drawResume(); this.paint(); },
    drawResume: function () {
      var l = P.hist, h = '';
      for (var i = 0; i < l.length; i++) {
        var it = l[i], pct = it.d > 0 ? Math.min(100, it.p / it.d * 100) : 0;
        h += '<div class="rc" data-r="' + i + '"><div class="rp">' + imgTag(it.i) + '</div><div class="rt">' +
          '<div class="rn">' + esc(it.t) + '</div><div class="bar"><div style="width:' + pct.toFixed(1) + '%"></div></div>' +
          '<div class="rl">' + esc(resumeLeft(it)) + '</div></div></div>';
      }
      $('h-row').innerHTML = h;
      cls(this.el, 'has-resume', l.length > 0);
      if (this.rIdx >= l.length) this.rIdx = Math.max(0, l.length - 1);
      if (!l.length && this.zone === 'resume') this.zone = 'tiles';
    },
    paint: function () {
      for (var i = 0; i < this.tiles.length; i++) cls(this.tiles[i], 'focus', this.zone === 'tiles' && i === this.idx);
      var cards = $('h-row').children;
      for (i = 0; i < cards.length; i++) cls(cards[i], 'focus', this.zone === 'resume' && i === this.rIdx);
      // fait défiler la rangée pour garder la carte choisie visible
      var c = cards[this.rIdx];
      if (c) {
        var row = $('h-row'), w = row.parentNode.clientWidth, x = c.offsetLeft + c.offsetWidth - w + 2 * REM;
        setTransform(row, 'translate3d(' + (-Math.max(0, x)) + 'px,0,0)');
      }
    },
    key: function (c) {
      var n = P.hist.length;
      if (this.zone === 'resume') {
        if (c === K.LEFT && this.rIdx > 0) { this.rIdx--; this.paint(); }
        else if (c === K.RIGHT && this.rIdx < n - 1) { this.rIdx++; this.paint(); }
        else if (c === K.DOWN) { this.zone = 'tiles'; this.paint(); }
        else if (c === K.OK) this.resumeItem();
        else if (c === K.RED) this.forget();
        return true;
      }
      if (c === K.UP && n) { this.zone = 'resume'; this.paint(); return true; }
      if (c === K.LEFT && this.idx > 0) { this.idx--; this.paint(); return true; }
      if (c === K.RIGHT && this.idx < this.tiles.length - 1) { this.idx++; this.paint(); return true; }
      if (c === K.OK) { this.open(); return true; }
      return false;
    },
    resumeItem: function () {
      var h = P.hist[this.rIdx];
      if (!h) return;
      var single = { title: h.t, url: h.u, live: false, key: h.k, icon: h.i, sid: h.sid, sname: h.sn };
      if (!h.sid) { go('player', { list: [single], index: 0 }); return; }
      // épisode : on recharge la saison pour enchaîner les épisodes suivants
      spinner(true);
      X.episodes(h.sid, function (err, eps) {
        spinner(false);
        if (cur !== screens.home) return;
        var epId = h.k.substr(1), ep = null, i;
        if (!err) for (i = 0; i < eps.length; i++) if (eps[i].id === epId) ep = eps[i];
        if (!ep) { go('player', { list: [single], index: 0 }); return; }
        var season = [];
        for (i = 0; i < eps.length; i++) if (eps[i].season === ep.season) season.push(eps[i]);
        go('player', { list: episodeList(h.sid, h.sn, h.i, season), index: season.indexOf(ep) });
      });
    },
    forget: function () {
      var self = this, h = P.hist[this.rIdx];
      if (!h) return;
      Dialog.show(h.t, '', [
        {
          label: 'Retirer de « Reprendre »',
          fn: function () { Hist.remove(h.k); delete P.pos[h.k]; save('pos', P.pos); self.drawResume(); self.paint(); }
        },
        { label: 'Annuler' }
      ]);
    },
    open: function () {
      if (this.idx === 3) this.settings(0);
      else go('browse', { kind: ['live', 'movie', 'series'][this.idx] });
    },
    settings: function (idx) {
      var self = this;
      Dialog.show('Réglages', '', [
        {
          label: 'Format du direct : ' + (P.live === 'm3u8' ? 'HLS (recommandé)' : 'MPEG-TS'),
          fn: function () { setP('live', P.live === 'm3u8' ? 'ts' : 'm3u8'); self.settings(0); }
        },
        {
          label: 'Actualiser les listes',
          fn: function () {
            toast('Actualisation des listes…');
            Lists.refreshAll(function (ok) { toast(ok ? 'Listes à jour' : 'Actualisation impossible, réessaie plus tard'); });
          }
        },
        {
          label: 'Catégories visibles',
          fn: function () {
            Parental.ask(function () {
              Dialog.show('Catégories visibles', '', [
                { label: 'TV en direct', fn: function () { go('catset', { kind: 'live' }); } },
                { label: 'Films', fn: function () { go('catset', { kind: 'movie' }); } },
                { label: 'Séries', fn: function () { go('catset', { kind: 'series' }); } },
                { label: 'Fermer' }
              ]);
            });
          }
        },
        {
          label: 'Code parental : ' + (Parental.hasCode() ? 'activé' : 'désactivé'),
          fn: function () { Parental.settings(); }
        },
        {
          label: 'Se déconnecter',
          fn: function () {
            Dialog.show('Se déconnecter ?', 'Tes favoris et reprises de lecture sont conservés.', [
              {
                label: 'Se déconnecter',
                fn: function () { setP('pass', ''); Lists.clear(); reset('login'); }
              },
              { label: 'Annuler' }
            ]);
          }
        },
        { label: 'Fermer' }
      ], null, idx);
      var v = document.createElement('div');
      v.className = 'opt';
      v.style.cssText = 'color:#4D5664;font-size:.9rem;height:2rem;line-height:2rem;border:0';
      v.textContent = 'KoalaTV ' + VERSION;
      $('d-opts').appendChild(v);
    }
  };

  /* ---------- Rendu des éléments ---------- */

  function isFav(it) { return !!P.favs[it.kind + ':' + it.id]; }

  function imgTag(src) {
    return /^https?:\/\//i.test(src) ? '<img src="' + esc(src) + '" onerror="this.style.display=\'none\'">' : '';
  }

  function renderCat(c) {
    return '<div class="cat"><span class="n">' + esc(c.name) + '</span>' + (c.locked ? LOCK : '') +
      '<span class="c">' + (c.label !== undefined ? esc(c.label) : fmtNum(c.n)) + '</span></div>';
  }
  /* Programme en cours des chaînes visibles : chargé à la demande, 4 à la fois, gardé jusqu'à la fin du programme */
  var Epg = {
    cache: {}, queue: [], running: 0, onLoaded: null,
    now: function (id) {
      var c = this.cache[id], t = Date.now() / 1000;
      if (c && t < c.until) return c.p && c.p.e > t ? c.p : null;
      if (!c || !c.loading) {
        for (var i = 0; i < this.queue.length; i++) if (this.queue[i] === id) return null;
        this.queue.push(id);
        if (this.queue.length > 40) this.queue.shift(); // seules les dernières lignes affichées comptent
        this.pump();
      }
      return null;
    },
    pump: function () {
      var self = this;
      while (this.running < 4 && this.queue.length) {
        var id = this.queue.pop();
        this.cache[id] = { loading: true, until: 0 };
        this.running++;
        (function (id) {
          X.epg(id, function (rows) {
            self.running--;
            var t = Date.now() / 1000, p = null;
            for (var i = 0; i < rows.length; i++) if (rows[i].s <= t && rows[i].e > t) { p = rows[i]; break; }
            self.cache[id] = { p: p, until: p ? p.e : (rows.length && rows[0].s > t ? rows[0].s : t + 1800) };
            if (self.onLoaded) self.onLoaded(id);
            self.pump();
          });
        })(id);
      }
    }
  };

  function renderRow(it) {
    var p = Epg.now(it.id), g = '';
    if (p) {
      var pct = Math.max(0, Math.min(100, (Date.now() / 1000 - p.s) / (p.e - p.s) * 100));
      g = '<span class="g">' + hm(p.s) + ' – ' + hm(p.e) + ' · ' + esc(p.t) + '</span><span class="gb"><i style="width:' + pct.toFixed(1) + '%"></i></span>';
    }
    return '<div class="row"><span class="logo">' + imgTag(it.icon) + '</span><span class="tx"><span class="n">' + esc(it.name) + '</span>' +
      g + '</span>' + (isFav(it) ? STAR : '') + '</div>';
  }
  function renderPoster(it) {
    return '<div class="poster"><div class="img">' + imgTag(it.icon) + (isFav(it) ? STAR : '') +
      '</div><div class="n">' + esc(it.name) + '</div></div>';
  }

  /* ---------- Parcourir (direct, films, séries) ---------- */

  var TITLES = { live: 'TV en direct', movie: 'Films', series: 'Séries' };
  var UNITS = { live: ['chaîne', 'chaînes'], movie: ['film', 'films'], series: ['série', 'séries'] };

  screens.browse = {
    el: $('s-browse'),
    kind: 'live',
    zone: 'cats',
    all: [],
    pool: [],
    data: null,
    catList: [],
    token: 0,
    selTimer: null,
    searchTimer: null,
    okTimer: null,
    query: '',
    catIdx: 0,
    init: function () {
      var self = this;
      this.search = $('b-search');
      this.cats = new VList($('b-cats'), {
        rowH: 3.1,
        render: renderCat,
        onPointer: function () { self.setZone('cats'); },
        onOk: function (i) { self.pickCat(i); }
      });
      this.items = new VList($('b-items'), {
        rowH: 5.2,
        render: renderRow,
        onPointer: function () { self.setZone('items'); },
        onOk: function () { self.open(); }
      });
      this.search.addEventListener('input', function () {
        clearTimeout(self.searchTimer);
        self.searchTimer = setTimeout(function () { self.runSearch(self.search.value); }, 350);
      });
      this.search.addEventListener('blur', function () { cls($('b-search-box'), 'editing', false); });
      $('b-search-box').addEventListener('click', function () { self.setZone('search'); self.editSearch(); });
    },
    enter: function (p) {
      var self = this, token = ++this.token;
      this.kind = p.kind;
      this.query = '';
      this.search.value = '';
      $('b-title').textContent = TITLES[this.kind];
      this.items.o.render = this.kind === 'live' ? renderRow : renderPoster;
      this.items.o.itemW = this.kind === 'live' ? 0 : 11.6;
      this.all = [];
      this.catList = [];
      this.cats.set([]);
      this.items.set([]);
      this.head('', '');
      this.msg('');
      this.zone = 'cats';
      this.paintZone();
      spinner(true);
      Lists.get(this.kind, function (err, data) {
        if (token !== self.token) return;
        spinner(false);
        if (err) {
          self.msg('Impossible de charger la liste.\n' + err.message + '\n\nOK pour réessayer');
          self.zone = 'retry';
          return;
        }
        self.build(data.cats, data.items);
      });
    },
    build: function (cats, items, keep) {
      var counts = {}, list = [], favN = 0, i, prev = this.catList[this.catIdx], pool = [], kind = this.kind;
      for (i = 0; i < items.length; i++) {
        counts[items[i].cat] = (counts[items[i].cat] || 0) + 1;
        // éléments affichables : sans les catégories masquées ni verrouillées (tant que le code n'est pas saisi)
        if (!Parental.blocked(kind, items[i].cat)) {
          pool.push(items[i]);
          if (isFav(items[i])) favN++;
        }
      }
      list.push({ id: '*fav', name: 'Favoris', n: favN });
      list.push({ id: '*all', name: 'Tout', n: pool.length });
      for (i = 0; i < cats.length; i++) {
        if (!counts[cats[i].id] || Parental.state(kind, cats[i].id) === 'h') continue;
        list.push({ id: cats[i].id, name: cats[i].name, n: counts[cats[i].id], locked: Parental.locked(kind, cats[i].id) });
      }
      this.data = { cats: cats, items: items };
      this.all = items;
      this.pool = pool;
      this.catList = list;
      if (keep && prev) {
        // liste rafraîchie en arrière-plan : même catégorie, même position
        var at = 1, itemIdx = this.items.idx;
        for (i = 0; i < list.length; i++) if (list[i].id === prev.id) at = i;
        this.cats.set(list, at, true);
        this.pickCat(at, true, true, true);
        this.items.idx = Math.min(itemIdx, Math.max(0, this.items.items.length - 1));
        this.items.render();
        return;
      }
      var start = favN ? 0 : (list.length > 2 ? 2 : 1);
      this.cats.set(list, start);
      this.pickCat(start, true);
    },
    resume: function () {
      if (this.catList.length) {
        this.catList[0].n = this.countFavs();
        this.cats.redraw();
      }
      this.items.redraw();
      this.paintZone();
    },
    leave: function () {
      clearTimeout(this.selTimer);
      clearTimeout(this.searchTimer);
      clearTimeout(this.okTimer);
      this.okTimer = null;
      spinner(false);
      if (document.activeElement === this.search) this.search.blur();
    },
    countFavs: function () {
      var n = 0;
      for (var i = 0; i < this.pool.length; i++) if (isFav(this.pool[i])) n++;
      return n;
    },
    head: function (title, count) {
      $('b-head').textContent = title;
      $('b-count').textContent = count;
    },
    msg: function (txt) {
      var m = $('b-msg');
      m.textContent = txt;
      cls(m, 'on', !!txt);
    },
    pickCat: function (i, stayInCats, keepSearch, keepTop) {
      clearTimeout(this.selTimer);
      var c = this.catList[i], self = this;
      if (!c) return;
      if (c.locked && Parental.blocked(this.kind, c.id)) {
        // catégorie verrouillée : code parental, puis on reconstruit les listes déverrouillées
        Parental.ask(function () {
          self.build(self.data.cats, self.data.items, true);
          for (var k = 0; k < self.catList.length; k++) if (self.catList[k].id === c.id) self.pickCat(k, stayInCats);
        });
        return;
      }
      this.catIdx = i;
      this.cats.idx = i;
      this.cats.select(i);
      if (!keepSearch && this.query) { this.query = ''; this.search.value = ''; }
      var list = [], k;
      if (c.id === '*all') list = this.pool;
      else if (c.id === '*fav') { for (k = 0; k < this.pool.length; k++) if (isFav(this.pool[k])) list.push(this.pool[k]); }
      else { for (k = 0; k < this.all.length; k++) if (this.all[k].cat === c.id) list.push(this.all[k]); }
      this.showItems(list, c.name, c.id === '*fav'
        ? 'Aucun favori pour le moment.\nMaintiens OK ou appuie sur le bouton rouge pour en ajouter.'
        : 'Cette catégorie est vide.', keepTop);
      if (!stayInCats && list.length) this.setZone('items');
    },
    showItems: function (list, title, empty, keepTop) {
      this.items.set(list, 0, keepTop);
      var u = UNITS[this.kind];
      this.head(title, list.length ? plural(list.length, u[0], u[1]) : '');
      this.msg(list.length ? '' : empty);
    },
    runSearch: function (raw) {
      var q = fold(str(raw));
      this.query = q;
      if (q.length < 2) {
        this.pickCat(this.catIdx, true, true);
        return;
      }
      var out = [];
      for (var i = 0; i < this.pool.length; i++) {
        var it = this.pool[i];
        if (it._f === undefined) it._f = fold(it.name);
        if (it._f.indexOf(q) >= 0) out.push(it);
      }
      this.cats.select(-1);
      this.showItems(out, 'Recherche : ' + str(raw), 'Aucun résultat');
    },
    editSearch: function () {
      cls($('b-search-box'), 'editing', true);
      this.search.focus();
    },
    setZone: function (z) {
      this.zone = z;
      this.paintZone();
    },
    paintZone: function () {
      cls($('b-search-box'), 'focus', this.zone === 'search');
      if (this.cats.active !== (this.zone === 'cats')) this.cats.focus(this.zone === 'cats');
      if (this.items.active !== (this.zone === 'items')) this.items.focus(this.zone === 'items');
    },
    current: function () { return this.items.items[this.items.idx]; },
    open: function () {
      var it = this.current();
      if (!it) return;
      if (it.kind === 'series') { go('series', it); return; }
      if (it.kind === 'movie') { go('movie', it); return; }
      var list = [], src = this.items.items;
      for (var i = 0; i < src.length; i++) {
        list.push(liveItem(src[i]));
      }
      go('player', { list: list, index: this.items.idx });
    },
    toggleFav: function () {
      var it = this.current();
      if (!it) return;
      var key = it.kind + ':' + it.id;
      if (P.favs[key]) delete P.favs[key];
      else P.favs[key] = 1;
      save('favs', P.favs);
      toast(P.favs[key] ? 'Ajouté aux favoris' : 'Retiré des favoris');
      this.items.refresh(this.items.idx);
      this.catList[0].n = this.countFavs();
      this.cats.refresh(0);
    },
    inputDone: function (input, c) {
      clearTimeout(this.searchTimer);
      this.runSearch(this.search.value);
      if ((c === K.OK || c === K.DOWN) && this.items.items.length) this.setZone(c === K.OK ? 'items' : 'cats');
      else this.setZone(c === K.DOWN ? 'cats' : 'search');
    },
    back: function () {
      if (this.query) {
        this.search.value = '';
        this.runSearch('');
        this.setZone('cats');
        return true;
      }
      if (this.zone === 'items') { this.setZone('cats'); return true; }
      return false;
    },
    keyup: function (c) {
      if (c !== K.OK || this.zone !== 'items' || !this.okTimer) return;
      if (this.okTimer !== 'long') { clearTimeout(this.okTimer); this.open(); }
      this.okTimer = null;
    },
    key: function (c, e) {
      var self = this;
      if (this.zone === 'retry') {
        if (c === K.OK) this.enter({ kind: this.kind });
        return true;
      }
      if (this.zone === 'search') {
        if (c === K.OK) this.editSearch();
        else if (c === K.DOWN) this.setZone('cats');
        else if (c === K.RIGHT && this.items.items.length) this.setZone('items');
        return true;
      }
      if (this.zone === 'cats') {
        if (c === K.UP) {
          if (!this.cats.move(0, -1)) this.setZone('search');
          else this.schedulePick();
        } else if (c === K.DOWN) {
          if (this.cats.move(0, 1)) this.schedulePick();
        } else if (c === K.CH_UP || c === K.CH_DOWN) {
          if (this.cats.page(c === K.CH_UP ? -1 : 1)) this.schedulePick();
        } else if (c === K.OK || c === K.RIGHT) {
          if (this.cats.idx !== this.cats.sel || this.query) this.pickCat(this.cats.idx);
          else if (this.items.items.length) this.setZone('items');
        }
        return true;
      }
      // zone éléments
      if (c === K.OK) {
        if (e && e.repeat) return true;
        if (!this.okTimer) {
          this.okTimer = setTimeout(function () { self.okTimer = 'long'; self.toggleFav(); }, 650);
        }
        return true;
      }
      if (c === K.RED) { this.toggleFav(); return true; }
      if (c === K.LEFT) { if (!this.items.move(-1, 0)) this.setZone('cats'); return true; }
      if (c === K.RIGHT) { this.items.move(1, 0); return true; }
      if (c === K.UP) { this.items.move(0, -1); return true; }
      if (c === K.DOWN) { this.items.move(0, 1); return true; }
      if (c === K.CH_UP || c === K.CH_DOWN) { this.items.page(c === K.CH_UP ? -1 : 1); return true; }
      return false;
    },
    schedulePick: function () {
      var self = this;
      clearTimeout(this.selTimer);
      this.selTimer = setTimeout(function () { self.pickCat(self.cats.idx, true); }, 300);
    }
  };

  /* ---------- Série : saisons et épisodes ---------- */

  function cleanEpTitle(t, series) {
    t = str(t);
    var low = fold(t), sl = fold(series);
    if (sl && low.indexOf(sl) === 0) t = t.substr(series.length);
    t = t.replace(/\b[sS]\d{1,2}\s*[eE]\d{1,3}\b/g, '').replace(/^[\s\-–—:|.·]+|[\s\-–—:|.·]+$/g, '');
    return t;
  }

  screens.series = {
    el: $('s-series'),
    zone: 'seasons',
    token: 0,
    eps: [],
    seasons: [],
    init: function () {
      var self = this;
      this.sList = new VList($('sr-seasons'), {
        rowH: 3.1,
        render: renderCat,
        onPointer: function () { self.setZone('seasons'); },
        onOk: function (i) { self.pickSeason(i); self.setZone('eps'); }
      });
      this.eList = new VList($('sr-eps'), {
        rowH: 6.4,
        render: function (e) {
          var pos = P.pos['e' + e.id] || 0;
          var title = cleanEpTitle(e.title, self.it.name) || 'Épisode ' + e.num;
          return '<div class="ep"><div class="t"><span class="num">' + e.num + '</span><span class="tt">' + esc(title) +
            '</span>' + (pos ? '<span class="r">Reprendre à ' + fmtTime(pos) + '</span>' : '') + '</div>' +
            '<div class="p">' + esc(e.plot) + '</div></div>';
        },
        onPointer: function () { self.setZone('eps'); },
        onOk: function () { self.play(); }
      });
    },
    enter: function (it) {
      var self = this, token = ++this.token;
      this.it = it;
      $('sr-title').textContent = it.name;
      var img = $('sr-cover');
      img.style.opacity = '0';
      img.onload = function () { img.style.opacity = '1'; };
      img.onerror = function () { img.style.opacity = '0'; };
      img.src = /^https?:\/\//i.test(it.icon) ? it.icon : '';
      this.sList.set([]);
      this.eList.set([]);
      this.head('', '');
      this.msg('');
      this.zone = 'seasons';
      this.paintZone();
      spinner(true);
      X.episodes(it.id, function (err, eps) {
        if (token !== self.token) return;
        spinner(false);
        if (err) { self.msg('Impossible de charger la série.\n' + err.message + '\n\nOK pour réessayer'); self.zone = 'retry'; return; }
        if (!eps.length) { self.msg('Aucun épisode disponible.'); return; }
        self.eps = eps;
        var seen = {}, list = [];
        for (var i = 0; i < eps.length; i++) {
          var s = eps[i].season;
          if (!seen[s]) { seen[s] = { id: s, name: s > 0 ? 'Saison ' + s : 'Épisodes', n: 0 }; list.push(seen[s]); }
          seen[s].n++;
        }
        self.seasons = list;
        self.sList.set(list, 0);
        self.pickSeason(0);
      });
    },
    resume: function () {
      this.eList.redraw();
      this.paintZone();
    },
    leave: function () { spinner(false); },
    head: function (t, c) { $('sr-head').textContent = t; $('sr-count').textContent = c; },
    msg: function (txt) { var m = $('sr-msg'); m.textContent = txt; cls(m, 'on', !!txt); },
    pickSeason: function (i) {
      var s = this.seasons[i];
      if (!s) return;
      this.sList.idx = i;
      this.sList.select(i);
      var list = [];
      for (var k = 0; k < this.eps.length; k++) if (this.eps[k].season === s.id) list.push(this.eps[k]);
      this.eList.set(list, 0);
      this.head(s.name, plural(list.length, 'épisode', 'épisodes'));
    },
    setZone: function (z) { this.zone = z; this.paintZone(); },
    paintZone: function () {
      if (this.sList.active !== (this.zone === 'seasons')) this.sList.focus(this.zone === 'seasons');
      if (this.eList.active !== (this.zone === 'eps')) this.eList.focus(this.zone === 'eps');
    },
    play: function () {
      go('player', { list: episodeList(this.it.id, this.it.name, this.it.icon, this.eList.items), index: this.eList.idx });
    },
    back: function () {
      if (this.zone === 'eps') { this.setZone('seasons'); return true; }
      return false;
    },
    key: function (c) {
      if (this.zone === 'retry') { if (c === K.OK) this.enter(this.it); return true; }
      if (this.zone === 'seasons') {
        if (c === K.UP) { if (this.sList.move(0, -1)) this.pickSeason(this.sList.idx); }
        else if (c === K.DOWN) { if (this.sList.move(0, 1)) this.pickSeason(this.sList.idx); }
        else if ((c === K.RIGHT || c === K.OK) && this.eList.items.length) this.setZone('eps');
        return true;
      }
      if (c === K.UP) this.eList.move(0, -1);
      else if (c === K.DOWN) this.eList.move(0, 1);
      else if (c === K.CH_UP || c === K.CH_DOWN) this.eList.page(c === K.CH_UP ? -1 : 1);
      else if (c === K.LEFT) this.setZone('seasons');
      else if (c === K.OK) this.play();
      return true;
    }
  };

  /* ---------- Catégories visibles / verrouillées / masquées ---------- */

  var STATE_LABEL = { v: 'Visible', l: 'Verrouillée', h: 'Masquée' };

  screens.catset = {
    el: $('s-catset'),
    init: function () {
      var self = this;
      this.list = new VList($('cs-list'), {
        rowH: 3.4,
        render: function (c) {
          var st = Parental.state(self.kind, c.id);
          return '<div class="cat cs-' + st + '"><span class="n">' + esc(c.name) + '</span>' + (st === 'l' ? LOCK : '') +
            '<span class="c">' + STATE_LABEL[st] + '</span></div>';
        },
        onOk: function (i) { self.cycle(i); }
      });
    },
    enter: function (p) {
      var self = this;
      this.kind = p.kind;
      $('cs-title').textContent = 'Catégories · ' + TITLES[p.kind];
      this.list.set([]);
      this.list.focus(true);
      spinner(true);
      Lists.get(p.kind, function (err, data) {
        spinner(false);
        if (err) { toast('Impossible de charger les catégories'); return; }
        var counts = {}, out = [], i;
        for (i = 0; i < data.items.length; i++) counts[data.items[i].cat] = 1;
        for (i = 0; i < data.cats.length; i++) if (counts[data.cats[i].id]) out.push(data.cats[i]);
        self.list.set(out, 0);
      });
    },
    leave: function () { spinner(false); },
    cycle: function (i) {
      var self = this, c = this.list.items[i];
      if (!c) return;
      var next = { v: 'l', l: 'h', h: 'v' }[Parental.state(this.kind, c.id)];
      if (next === 'l' && !Parental.hasCode()) {
        // verrouiller demande un code : on le crée d'abord
        Parental.create(function () { Parental.set(self.kind, c.id, 'l'); self.list.refresh(i); });
        return;
      }
      Parental.set(this.kind, c.id, next);
      this.list.refresh(i);
    },
    key: function (c) {
      if (c === K.UP) this.list.move(0, -1);
      else if (c === K.DOWN) this.list.move(0, 1);
      else if (c === K.CH_UP || c === K.CH_DOWN) this.list.page(c === K.CH_UP ? -1 : 1);
      else if (c === K.OK) this.cycle(this.list.idx);
      return true;
    }
  };

  /* ---------- Fiche film ---------- */

  screens.movie = {
    el: $('s-movie'),
    idx: 0,
    token: 0,
    init: function () {
      var self = this;
      $('m-btns').addEventListener('click', function (e) {
        var n = e.target;
        while (n && n.getAttribute && n.getAttribute('data-b') === null) n = n.parentNode;
        if (n && n.getAttribute) { self.idx = parseInt(n.getAttribute('data-b'), 10); self.paint(); self.ok(); }
      });
    },
    enter: function (it) {
      var self = this, token = ++this.token;
      this.it = it;
      this.ext = it.ext || 'mp4';
      this.idx = 0;
      var r = parseFloat(it.rating);
      this.show({ title: it.name, poster: it.icon, backdrop: '', plot: '', year: '', minutes: 0, genre: '', rating: r > 0 ? r.toFixed(1).replace('.', ',') : '', director: '', cast: '' });
      this.paint();
      X.movie(it.id, function (err, m) {
        if (err || token !== self.token) return;
        if (m.ext) self.ext = m.ext;
        if (!m.title) m.title = it.name;
        if (!m.poster) m.poster = it.icon;
        self.show(m);
      });
    },
    resume: function () { this.paint(); },
    show: function (m) {
      $('m-title').textContent = m.title;
      var meta = [];
      if (m.rating) meta.push('<span class="r">' + STAR_S + esc(m.rating) + '</span>');
      if (m.year) meta.push(esc(m.year));
      if (m.minutes) meta.push(m.minutes >= 60 ? Math.floor(m.minutes / 60) + ' h ' + pad2(m.minutes % 60) : m.minutes + ' min');
      $('m-meta').innerHTML = meta.join('  ·  ');
      $('m-genre').textContent = m.genre;
      $('m-plot').textContent = m.plot;
      var cr = [];
      if (m.director) cr.push('Réalisation : ' + m.director);
      if (m.cast) cr.push('Avec : ' + m.cast);
      $('m-credits').innerHTML = esc(cr.join('\n')).replace(/\n/g, '<br>');
      var img = $('m-poster');
      img.style.visibility = 'hidden';
      img.onload = function () { img.style.visibility = 'visible'; };
      img.onerror = function () { img.style.visibility = 'hidden'; };
      img.src = /^https?:\/\//i.test(m.poster) ? m.poster : '';
      var back = m.backdrop || m.poster;
      $('m-back').style.backgroundImage = /^https?:\/\//i.test(back) ? 'url("' + back.replace(/"/g, '%22') + '")' : 'none';
    },
    key0: function () { return 'm' + this.it.id; },
    paint: function () {
      var pos = P.pos[this.key0()] || 0;
      $('m-play').textContent = pos > 0 ? 'Reprendre à ' + fmtTime(pos) : 'Lecture';
      cls($('m-restart'), 'hide', !(pos > 0));
      if (!(pos > 0) && this.idx === 1) this.idx = 0;
      cls($('m-fav'), 'on', isFav(this.it));
      var b = [$('m-play'), $('m-restart'), $('m-fav')];
      for (var i = 0; i < b.length; i++) cls(b[i], 'focus', i === this.idx);
    },
    ok: function () {
      var it = this.it, pos = P.pos[this.key0()] || 0;
      if (this.idx === 2) {
        var key = it.kind + ':' + it.id;
        if (P.favs[key]) delete P.favs[key]; else P.favs[key] = 1;
        save('favs', P.favs);
        toast(P.favs[key] ? 'Ajouté aux favoris' : 'Retiré des favoris');
        this.paint();
        return;
      }
      var item = { title: it.name, url: X.url('movie', it.id, this.ext), live: false, key: this.key0(), icon: it.icon };
      go('player', { list: [item], index: 0, startAt: this.idx === 1 ? 0 : pos });
    },
    key: function (c) {
      var order = (P.pos[this.key0()] || 0) > 0 ? [0, 1, 2] : [0, 2], k = order.indexOf(this.idx);
      if (c === K.LEFT && k > 0) { this.idx = order[k - 1]; this.paint(); }
      else if (c === K.RIGHT && k < order.length - 1) { this.idx = order[k + 1]; this.paint(); }
      else if (c === K.OK) this.ok();
      else if (c === K.RED) { this.idx = 2; this.ok(); }
      return true;
    }
  };

  /* ---------- Lecteur ---------- */

  screens.player = {
    el: $('s-player'),
    v: $('video'),
    list: [],
    i: 0,
    retries: 0,
    lastPos: 0,
    startAt: 0,
    lastT: -1,
    stuck: 0,
    lastSave: 0,
    active: false,
    timers: {},
    init: function () {
      var self = this, v = this.v;
      v.addEventListener('playing', function () {
        self.retries = 0;
        self.stuck = 0;
        self.status('');
        spinner(false);
        cls($('p-pause'), 'on', false);
      });
      v.addEventListener('waiting', function () { if (self.active) spinner(true); });
      v.addEventListener('loadedmetadata', function () {
        if (self.startAt > 0) {
          try { v.currentTime = self.startAt; } catch (e) { /* ignoré */ }
          self.startAt = 0;
        }
      });
      v.addEventListener('error', function () { if (self.active && v.getAttribute('src')) self.fail(); });
      v.addEventListener('ended', function () {
        if (!self.active) return;
        if (self.cur().live) self.fail();
        else self.next();
      });
      v.addEventListener('timeupdate', function () {
        if (!self.active || self.cur().live) return;
        if (v.currentTime > 0) self.lastPos = v.currentTime;
        self.paintProgress();
        var now = Date.now();
        if (now - self.lastSave > 10000) { self.lastSave = now; self.saveProgress(); }
      });
      document.addEventListener('visibilitychange', function () {
        if (!self.active) return;
        if (document.hidden) { self.saveProgress(); self.stopVideo(); }
        else self.load(self.cur().live ? 0 : self.lastPos);
      });
    },
    cur: function () { return this.list[this.i]; },
    enter: function (p) {
      this.list = p.list;
      this.i = p.index || 0;
      this.forcedStart = typeof p.startAt === 'number' ? p.startAt : -1;
      this.prevList = null;
      this.prevI = -1;
      this.digits = '';
      this.okTimer = null;
      this.active = true;
      this.start(true);
      var self = this;
      this.timers.watch = setInterval(function () { self.watchdog(); }, 2000);
    },
    leave: function () {
      this.saveProgress();
      this.active = false;
      for (var k in this.timers) if (this.timers.hasOwnProperty(k)) { clearTimeout(this.timers[k]); clearInterval(this.timers[k]); }
      this.timers = {};
      this.stopVideo();
      this.status('');
      cls($('p-info'), 'on', false);
      cls($('p-pause'), 'on', false);
      cls($('p-num'), 'on', false);
      clearTimeout(this.okTimer);
      this.okTimer = null;
      this.digits = '';
      spinner(false);
    },
    start: function (askResume) {
      var self = this, it = this.cur();
      this.retries = 0;
      this.lastPos = 0;
      if (this.forcedStart >= 0) {
        var at = this.forcedStart;
        this.forcedStart = -1;
        this.load(at);
        return;
      }
      var saved = !it.live ? (P.pos[it.key] || 0) : 0;
      if (askResume && saved > 0) {
        Dialog.show(it.title, '', [
          { label: 'Reprendre à ' + fmtTime(saved), fn: function () { self.load(saved); } },
          { label: 'Reprendre au début', fn: function () { self.load(0); } }
        ], function () { back(); });
        return;
      }
      this.load(0);
    },
    load: function (at) {
      var v = this.v, it = this.cur();
      clearTimeout(this.timers.retry);
      this.timers.retry = null;
      this.startAt = at || 0;
      this.lastPos = at || 0;
      this.stuck = 0;
      this.lastT = -1;
      spinner(true);
      cls($('p-pause'), 'on', false);
      v.setAttribute('src', it.url);
      v.load();
      var pr = v.play();
      if (pr && pr['catch']) pr['catch'](function () { /* la lecture démarre dès que possible */ });
      this.showInfo();
    },
    stopVideo: function () {
      var v = this.v;
      try { v.pause(); } catch (e) { /* ignoré */ }
      v.removeAttribute('src');
      try { v.load(); } catch (e2) { /* ignoré */ }
    },
    status: function (txt) {
      var s = $('p-status');
      s.textContent = txt;
      cls(s, 'on', !!txt);
    },
    fail: function () {
      var self = this, it = this.cur();
      if (this.timers.retry || Dialog.open) return;
      this.retries++;
      if (!it.live && this.retries > 3) {
        spinner(false);
        this.status('');
        this.stopVideo();
        Dialog.show('Lecture impossible', 'Ce contenu ne peut pas être lu pour le moment.', [
          { label: 'Réessayer', fn: function () { self.retries = 0; self.load(self.lastPos); } },
          { label: 'Fermer', fn: function () { back(); } }
        ], function () { back(); });
        return;
      }
      this.status(this.retries > 2 ? 'Reconnexion en cours (' + this.retries + ')' : 'Reconnexion…');
      var at = it.live ? 0 : this.lastPos;
      this.timers.retry = setTimeout(function () { self.timers.retry = null; self.load(at); }, Math.min(5, this.retries) * 1000);
    },
    watchdog: function () {
      var v = this.v;
      if (!this.active || Dialog.open || this.timers.retry || v.paused || v.seeking) return;
      if (v.currentTime === this.lastT) {
        this.stuck += 2;
        if (this.stuck >= 16) this.fail();
      } else {
        this.stuck = 0;
        this.lastT = v.currentTime;
      }
    },
    next: function () {
      var it = this.cur();
      if (it.key) { delete P.pos[it.key]; save('pos', P.pos); Hist.remove(it.key); }
      if (this.i < this.list.length - 1) { this.i++; this.start(true); }
      else back();
    },
    /* Changement de chaîne : le titre s'affiche tout de suite, le flux démarre quand on arrête de zapper */
    switchTo: function (list, i) {
      var self = this;
      if (!list.length || (list === this.list && i === this.i)) return;
      this.prevList = this.list;
      this.prevI = this.i;
      this.list = list;
      this.i = Math.max(0, Math.min(i, list.length - 1));
      this.retries = 0;
      clearTimeout(this.timers.retry);
      this.timers.retry = null;
      this.status('');
      this.stopVideo();
      spinner(true);
      this.showInfo();
      clearTimeout(this.timers.zap);
      this.timers.zap = setTimeout(function () { self.load(0); }, 350);
    },
    zap: function (d) {
      if (this.list.length < 2) return;
      this.switchTo(this.list, (this.i + d + this.list.length) % this.list.length);
    },
    /* Retour à la chaîne regardée juste avant */
    previous: function () {
      if (!this.prevList || !this.prevList[this.prevI]) { toast('Pas encore de chaîne précédente'); return; }
      this.switchTo(this.prevList, this.prevI);
    },
    /* Saisie d'un numéro de chaîne */
    typeDigit: function (d) {
      var self = this;
      if (this.digits.length >= 4) this.digits = '';
      this.digits += d;
      $('p-num').textContent = this.digits;
      cls($('p-num'), 'on', true);
      clearTimeout(this.timers.digits);
      this.timers.digits = setTimeout(function () { self.commitDigits(); }, 2000);
    },
    commitDigits: function () {
      clearTimeout(this.timers.digits);
      var n = parseInt(this.digits, 10), i, self = this;
      this.digits = '';
      cls($('p-num'), 'on', false);
      if (!(n > 0)) return;
      for (i = 0; i < this.list.length; i++) if (this.list[i].num === n) { this.switchTo(this.list, i); return; }
      // pas dans la catégorie en cours : on cherche dans toutes les chaînes
      Lists.get('live', function (err, data) {
        if (err || !self.active) return;
        var all = [], at = -1, k;
        for (k = 0; k < data.items.length; k++) if (data.items[k].num > 0 && !Parental.blocked('live', data.items[k].cat)) all.push(data.items[k]);
        all.sort(function (a, b) { return a.num - b.num; });
        for (k = 0; k < all.length; k++) if (all[k].num === n) at = k;
        if (at < 0) { toast('Chaîne ' + n + ' introuvable'); return; }
        var list = [];
        for (k = 0; k < all.length; k++) list.push(liveItem(all[k]));
        self.switchTo(list, at);
      });
    },
    saveProgress: function () {
      var it = this.cur(), v = this.v;
      if (!it || it.live || !it.key) return;
      var pos = this.lastPos, dur = v.duration;
      if (dur > 0 && isFinite(dur) && pos > dur - 120) {
        delete P.pos[it.key];
        Hist.remove(it.key);
      } else if (pos > 30) {
        P.pos[it.key] = Math.floor(pos);
        Hist.update(it, pos, isFinite(dur) ? dur : 0);
      } else return;
      save('pos', P.pos);
    },
    paintProgress: function () {
      var v = this.v, dur = v.duration, t = v.currentTime;
      var ok = dur > 0 && isFinite(dur);
      $('p-prog').style.width = (ok ? Math.min(100, t / dur * 100) : 0) + '%';
      $('p-cur').textContent = fmtTime(t);
      $('p-dur').textContent = ok ? '-' + fmtTime(dur - t) : '';
    },
    showInfo: function (keep) {
      var self = this, it = this.cur();
      $('p-title').textContent = it.title;
      $('p-pos').textContent = !it.live ? '' : it.num > 0 ? 'Chaîne ' + it.num : this.list.length > 1 ? (this.i + 1) + ' / ' + this.list.length : '';
      cls($('p-vod'), 'on', !it.live);
      if (!it.live) this.paintProgress();
      var epg = $('p-epg');
      epg.innerHTML = '';
      if (it.live && it.id) {
        clearTimeout(this.timers.epg);
        this.timers.epg = setTimeout(function () {
          var want = it;
          X.epg(it.id, function (rows) {
            if (!self.active || self.cur() !== want) return;
            var h = '';
            for (var i = 0; i < rows.length; i++) {
              h += '<div class="' + (i === 0 ? 'now ' : '') + 'ell"><span class="h">' + esc(rows[i].h) + '</span>' + esc(rows[i].t) + '</div>';
            }
            epg.innerHTML = h;
          });
        }, 500);
      }
      cls($('p-info'), 'on', true);
      clearTimeout(this.timers.info);
      if (!keep) this.timers.info = setTimeout(function () { cls($('p-info'), 'on', false); }, 5000);
    },
    togglePause: function () {
      var v = this.v;
      if (v.paused) {
        var pr = v.play();
        if (pr && pr['catch']) pr['catch'](function () { /* ignoré */ });
        cls($('p-pause'), 'on', false);
        this.showInfo();
      } else {
        v.pause();
        cls($('p-pause'), 'on', true);
        this.showInfo(true);
      }
    },
    seek: function (delta) {
      var v = this.v, dur = v.duration;
      var t = Math.max(0, v.currentTime + delta);
      if (dur > 0 && isFinite(dur)) t = Math.min(t, dur - 1);
      try { v.currentTime = t; } catch (e) { /* ignoré */ }
      this.lastPos = t;
      this.showInfo(v.paused);
    },
    cycleAudio: function () {
      var tr = this.v.audioTracks;
      if (!tr || tr.length < 2) { toast('Une seule piste audio'); return; }
      var cur = 0, i;
      for (i = 0; i < tr.length; i++) if (tr[i].enabled) cur = i;
      var nx = (cur + 1) % tr.length;
      for (i = 0; i < tr.length; i++) tr[i].enabled = i === nx;
      toast('Audio : ' + (tr[nx].label || tr[nx].language || 'piste ' + (nx + 1)));
    },
    cycleSubs: function () {
      var tr = this.v.textTracks;
      if (!tr || !tr.length) { toast('Aucun sous-titre'); return; }
      var cur = -1, i;
      for (i = 0; i < tr.length; i++) if (tr[i].mode === 'showing') cur = i;
      var nx = cur + 1 >= tr.length ? -1 : cur + 1;
      for (i = 0; i < tr.length; i++) tr[i].mode = i === nx ? 'showing' : 'disabled';
      toast(nx < 0 ? 'Sous-titres désactivés' : 'Sous-titres : ' + (tr[nx].label || tr[nx].language || 'piste ' + (nx + 1)));
    },
    keyup: function (c) {
      if (c !== K.OK || !this.okTimer) return;
      if (this.okTimer !== 'long') {
        clearTimeout(this.okTimer);
        if (this.digits) this.commitDigits(); else this.showInfo();
      }
      this.okTimer = null;
    },
    key: function (c, e) {
      var it = this.cur();
      if (c === K.STOP) { back(); return true; }
      if (c === K.GREEN) { this.cycleAudio(); return true; }
      if (c === K.YELLOW) { this.cycleSubs(); return true; }
      if (it.live) {
        var self = this;
        if (c >= 48 && c <= 57) this.typeDigit(c - 48);
        else if (c >= 96 && c <= 105) this.typeDigit(c - 96);
        else if (c === K.UP) this.zap(-1);
        else if (c === K.DOWN) this.zap(1);
        else if (c === K.CH_UP) this.zap(1);
        else if (c === K.CH_DOWN) this.zap(-1);
        else if (c === K.BLUE) this.previous();
        else if (c === K.OK) {
          // appui court : infos (ou valide le numéro) · appui long : chaîne précédente
          if (e && e.repeat) return true;
          if (!this.okTimer) this.okTimer = setTimeout(function () { self.okTimer = 'long'; self.previous(); }, 650);
        }
        else if (c === K.INFO || c === K.LEFT || c === K.RIGHT) this.showInfo();
        return true;
      }
      if (c === K.OK || c === K.PLAY || c === K.PAUSE) {
        if (c === K.PLAY && !this.v.paused) this.showInfo();
        else if (c === K.PAUSE && this.v.paused) this.showInfo(true);
        else this.togglePause();
      } else if (c === K.LEFT) this.seek(-10);
      else if (c === K.RIGHT) this.seek(30);
      else if (c === K.RW) this.seek(-60);
      else if (c === K.FF) this.seek(60);
      else if (c === K.UP || c === K.DOWN || c === K.INFO) this.showInfo(this.v.paused);
      return true;
    }
  };

  /* ---------- Clavier / télécommande ---------- */

  function isBack(c) { return c === K.BACK || c === K.ESC; }

  document.addEventListener('keydown', function (e) {
    var c = e.keyCode || e.which, t = e.target;
    var typing = t && t.tagName === 'INPUT' && document.activeElement === t;
    if (typing) {
      if (c === K.OK || c === K.UP || c === K.DOWN || isBack(c)) {
        e.preventDefault();
        t.blur();
        if (cur && cur.inputDone) cur.inputDone(t, c);
      }
      return;
    }
    if (Pin.open) { e.preventDefault(); Pin.key(c); return; }
    if (Dialog.open) { e.preventDefault(); Dialog.key(c); return; }
    if (isBack(c) || c === K.BKSP) {
      e.preventDefault();
      if (!(cur && cur.back && cur.back())) back();
      return;
    }
    if (cur && cur.key && cur.key(c, e)) e.preventDefault();
  }, true);

  document.addEventListener('keyup', function (e) {
    if (!Dialog.open && cur && cur.keyup) cur.keyup(e.keyCode || e.which);
  }, true);

  window.addEventListener('resize', function () {
    sizeRoot();
    if (cur === screens.browse) { screens.browse.cats.redraw(); screens.browse.items.redraw(); }
    if (cur === screens.series) { screens.series.sList.redraw(); screens.series.eList.redraw(); }
  });

  Epg.onLoaded = function (id) {
    var b = screens.browse;
    if (cur !== b || b.kind !== 'live') return;
    for (var k in b.items.nodes) {
      if (b.items.nodes.hasOwnProperty(k) && b.items.items[k] && b.items.items[k].id === id) b.items.refresh(+k);
    }
  };
  setInterval(function () {
    var b = screens.browse;
    if (cur === b && b.kind === 'live') for (var k in b.items.nodes) if (b.items.nodes.hasOwnProperty(k)) b.items.refresh(+k);
  }, 60000);

  Lists.onUpdated = function (kind) {
    var b = screens.browse;
    if (cur === b && b.kind === kind && !b.query && b.zone !== 'retry') {
      Lists.get(kind, function (err, data) { if (!err) b.build(data.cats, data.items, true); });
    }
  };

  /* ---------- Démarrage ---------- */

  sizeRoot();
  for (var name in screens) if (screens.hasOwnProperty(name) && screens[name].init) screens[name].init();
  reset(loggedIn() ? 'home' : 'login');

  window.KoalaTV = { version: VERSION, P: P, X: X, Lists: Lists, Parental: Parental, screens: screens, K: K };
})();
