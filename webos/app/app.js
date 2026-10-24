/* KoalaTV pour LG webOS — lecteur Xtream. ES5 pour compatibilité avec toutes les versions de webOS. */
(function () {
  'use strict';

  var VERSION = '1.14.0';
  var SERVICE = 'luna://com.koala.tv.service/fetch';

  var K = {
    LEFT: 37, UP: 38, RIGHT: 39, DOWN: 40, OK: 13, BACK: 461, ESC: 27, BKSP: 8,
    CH_UP: 33, CH_DOWN: 34, PLAY: 415, PAUSE: 19, STOP: 413, FF: 417, RW: 412, GUIDE: 458,
    RED: 403, GREEN: 404, YELLOW: 405, BLUE: 406, INFO: 457
  };

  var STAR = '<svg class="fav" viewBox="0 0 24 24"><path d="M12 2.8l2.85 5.78 6.38.93-4.62 4.5 1.09 6.35L12 17.36l-5.7 3 1.09-6.35-4.62-4.5 6.38-.93z"/></svg>';
  var STAR_S = '<svg class="st" viewBox="0 0 24 24"><path d="M12 2.8l2.85 5.78 6.38.93-4.62 4.5 1.09 6.35L12 17.36l-5.7 3 1.09-6.35-4.62-4.5 6.38-.93z"/></svg>';

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
    live: load('live', 'm3u8'), favs: load('favs', {}), pos: load('pos', {}), hist: load('hist', []), tzoff: load('tzoff', null)
  };
  function setP(k, v) { P[k] = v; save(k, v); }
  function loggedIn() { return !!(P.server && P.user && P.pass); }

  /* Films et épisodes commencés, du plus récent au plus ancien (rangée « Reprendre ») */
  var Hist = {
    gone: load('hgone', {}),
    remove: function (key) {
      var l = [], changed = false, at = Date.now();
      for (var i = 0; i < P.hist.length; i++) { if (P.hist[i].k === key) changed = true; else l.push(P.hist[i]); }
      if (changed) setP('hist', l);
      this.gone[key] = at;
      save('hgone', this.gone);
      Sync.put('e/' + key, { gone: true, at: at });
    },
    update: function (it, pos, dur) {
      var e = { k: it.key, t: it.title, u: it.url, i: it.icon || '', p: Math.floor(pos), d: Math.floor(dur || 0), sid: it.sid || '', sn: it.sname || '', a: Date.now() };
      var l = [e];
      for (var i = 0; i < P.hist.length && l.length < 20; i++) if (P.hist[i].k !== it.key) l.push(P.hist[i]);
      setP('hist', l);
      Sync.push(e);
    },
    /* fusion avec l'historique partagé : la version la plus récente de chaque élément gagne */
    merge: function (remote) {
      var l = P.hist.slice(), changed = false, k, i, r, local, idx;
      for (k in remote) {
        if (!remote.hasOwnProperty(k)) continue;
        r = remote[k];
        if (!r || !(r.at > 0)) continue;
        local = null; idx = -1;
        for (i = 0; i < l.length; i++) if (l[i].k === k) { local = l[i]; idx = i; }
        if (r.at <= (local ? local.a || 0 : 0) || r.at <= (this.gone[k] || 0)) continue;
        if (idx >= 0) l.splice(idx, 1);
        if (r.gone) {
          delete P.pos[k];
          this.gone[k] = r.at;
        } else {
          l.push({ k: k, t: str(r.t), u: str(r.u), i: str(r.i), p: +r.p || 0, d: +r.d || 0, sid: str(r.sid), sn: str(r.sn), a: r.at });
          P.pos[k] = +r.p || 0;
        }
        changed = true;
      }
      if (changed) {
        l.sort(function (a, b) { return (b.a || 0) - (a.a || 0); });
        setP('hist', l.slice(0, 20));
        save('pos', P.pos);
        save('hgone', this.gone);
      }
      return changed;
    }
  };

  /* SHA-256 (empreinte des identifiants pour l'adresse de l'historique partagé) */
  function sha256(msg) {
    var K2 = [0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5, 0xd807aa98, 0x12835b01,
      0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f,
      0x4a7484aa, 0x5cb0a9dc, 0x76f988da, 0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
      0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b, 0xc24b8b70,
      0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070, 0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a,
      0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2];
    var H2 = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19];
    var bytes = unescape(encodeURIComponent(msg)), n = bytes.length, words = [], i, j;
    for (i = 0; i < n; i++) words[i >> 2] |= (bytes.charCodeAt(i) & 0xff) << (24 - (i % 4) * 8);
    words[n >> 2] |= 0x80 << (24 - (n % 4) * 8);
    words[(((n + 8) >> 6) + 1) * 16 - 1] = n * 8;
    function rotr(x, c) { return (x >>> c) | (x << (32 - c)); }
    for (i = 0; i < words.length; i += 16) {
      var w = [], a = H2[0], b = H2[1], c = H2[2], d = H2[3], e = H2[4], f = H2[5], g = H2[6], h = H2[7];
      for (j = 0; j < 64; j++) {
        if (j < 16) w[j] = words[i + j] | 0;
        else {
          var s0 = rotr(w[j - 15], 7) ^ rotr(w[j - 15], 18) ^ (w[j - 15] >>> 3), s1 = rotr(w[j - 2], 17) ^ rotr(w[j - 2], 19) ^ (w[j - 2] >>> 10);
          w[j] = (w[j - 16] + s0 + w[j - 7] + s1) | 0;
        }
        var t1 = (h + (rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25)) + ((e & f) ^ (~e & g)) + K2[j] + w[j]) | 0;
        var t2 = ((rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22)) + ((a & b) ^ (a & c) ^ (b & c))) | 0;
        h = g; g = f; f = e; e = (d + t1) | 0; d = c; c = b; b = a; a = (t1 + t2) | 0;
      }
      H2[0] = (H2[0] + a) | 0; H2[1] = (H2[1] + b) | 0; H2[2] = (H2[2] + c) | 0; H2[3] = (H2[3] + d) | 0;
      H2[4] = (H2[4] + e) | 0; H2[5] = (H2[5] + f) | 0; H2[6] = (H2[6] + g) | 0; H2[7] = (H2[7] + h) | 0;
    }
    var out = '';
    for (i = 0; i < 8; i++) out += ('00000000' + (H2[i] >>> 0).toString(16)).slice(-8);
    return out;
  }

  /*
   * Historique « Reprendre » partagé entre tous les appareils du même abonnement (base Firebase).
   * Adresse calculée à partir des identifiants : impossible à deviner sans eux.
   */
  var Sync = {
    DB: 'https://koalatv-dae7b-default-rtdb.europe-west1.firebasedatabase.app',
    enabled: function () { return !!(this.DB && loggedIn()); },
    url: function (path) {
      var acc = sha256('koalatv|' + P.server.toLowerCase() + '|' + P.user + '|' + P.pass).substr(0, 40);
      return this.DB.replace(/\/+$/, '') + '/h/' + acc + '/' + path + '.json';
    },
    put: function (path, obj) {
      if (!this.enabled()) return;
      // POST « simple » (pas de pré-requête CORS), transformé en PUT par Firebase
      try {
        var x = new XMLHttpRequest();
        x.open('POST', this.url(path) + '?x-http-method-override=PUT', true);
        x.setRequestHeader('Content-Type', 'text/plain');
        x.send(JSON.stringify(obj));
      } catch (e) { /* hors ligne */ }
    },
    push: function (e) {
      this.put('e/' + e.k, { t: e.t, u: e.u, i: e.i, p: e.p, d: e.d, at: e.a, sid: e.sid, sn: e.sn });
    },
    /* Catégories masquées : clé Firebase sans . $ # [ ] / : */
    catPath: function (k) { return 'c/' + k.replace(/[.$#\[\]\/:]/g, '_'); },
    pushCat: function (k, s) { this.put(this.catPath(k), { k: k, h: s.h, at: s.at }); },
    pullCats: function (cb) {
      var self = this;
      if (!this.enabled()) return;
      getJson(this.url('c'), function (err, remote) {
        if (err) return;
        remote = remote || {};
        var changed = Hidden.merge(remote), seen = {}, p, k;
        for (p in remote) if (remote.hasOwnProperty(p) && remote[p] && remote[p].k) seen[remote[p].k] = remote[p].at;
        for (k in Hidden.states) {
          if (Hidden.states.hasOwnProperty(k) && !(seen[k] >= Hidden.states[k].at)) self.pushCat(k, Hidden.states[k]);
        }
        if (changed && cb) cb();
      });
    },
    pullSeen: function (cb) {
      var self = this;
      if (!this.enabled()) return;
      getJson(this.url('w'), function (err, remote) {
        if (err) return;
        remote = remote || {};
        var changed = Seen.merge(remote), k;
        for (k in Seen.m) if (Seen.m.hasOwnProperty(k) && !remote[k]) self.put('w/' + k, { at: Seen.m[k] });
        if (changed && cb) cb();
      });
    },
    favPath: function (k) { return 'f/' + k.replace(/[.$#\[\]\/:]/g, '_'); },
    pullFavs: function (cb) {
      var self = this;
      if (!this.enabled()) return;
      getJson(this.url('f'), function (err, remote) {
        if (err) return;
        remote = remote || {};
        var seen = {}, changed = false, p, v, k;
        for (p in remote) {
          if (!remote.hasOwnProperty(p)) continue;
          v = remote[p];
          if (!v || !v.k || !(v.at > 0)) continue;
          seen[v.k] = v.at;
          if (v.at > (Favs.at[v.k] || 0)) {
            if (!!P.favs[v.k] !== !!v.on) changed = true;
            Favs.set(v.k, !!v.on, v.at);
          }
        }
        var all = Favs.all();
        for (k in all) if (all.hasOwnProperty(k) && !(seen[k] >= all[k])) self.put(self.favPath(k), { k: k, on: !!P.favs[k], at: all[k] });
        if (changed && cb) cb();
      });
    },
    pull: function (cb) {
      var self = this;
      if (!this.enabled()) return;
      getJson(this.url('e'), function (err, remote) {
        if (err) return;
        remote = remote || {};
        var changed = Hist.merge(remote);
        // éléments locaux plus récents (ou absents) : on les envoie
        for (var i = 0; i < P.hist.length; i++) {
          var h = P.hist[i], r = remote[h.k];
          if (h.a && (!r || r.at < h.a)) self.push(h);
        }
        if (changed && cb) cb();
      });
    }
  };

  /*
   * Connexion rapide : l'écran de connexion affiche un code ; un appareil déjà connecté y envoie les identifiants.
   * Ils transitent chiffrés (clé tirée du code) par la base Firebase, à une adresse tirée du code, et sont effacés dès réception.
   */
  var Pair = {
    ABC: 'ABCDEFGHJKMNPQRSTUVWXYZ23456789',
    code: '',
    since: 0,
    timer: null,
    newCode: function () {
      var c = '', i, rnd = [];
      try { var a = new Uint8Array(8); window.crypto.getRandomValues(a); for (i = 0; i < 8; i++) rnd.push(a[i]); } catch (e) { rnd = []; }
      for (i = 0; i < 8; i++) c += this.ABC.charAt((rnd.length ? rnd[i] : Math.floor(Math.random() * 256)) % this.ABC.length);
      this.code = c;
      this.since = Date.now();
      return c;
    },
    url: function (code) { return Sync.DB.replace(/\/+$/, '') + '/h/p' + sha256('koalatv-pair|' + code).substr(0, 40) + '/x.json'; },
    /* Chiffrement par flux : octets XOR SHA-256(code, n° de bloc) */
    xor: function (code, bytes) {
      var out = [], block = '', b = -1;
      for (var i = 0; i < bytes.length; i++) {
        if (Math.floor(i / 32) !== b) { b = Math.floor(i / 32); block = sha256('koalatv-key|' + code + '|' + b); }
        out.push(bytes[i] ^ parseInt(block.substr((i % 32) * 2, 2), 16));
      }
      return out;
    },
    decrypt: function (code, hex) {
      var bytes = [];
      for (var i = 0; i + 1 < hex.length; i += 2) bytes.push(parseInt(hex.substr(i, 2), 16));
      var plain = this.xor(code, bytes), s = '';
      for (i = 0; i < plain.length; i++) s += String.fromCharCode(plain[i]);
      return JSON.parse(decodeURIComponent(escape(s)));
    },
    /* Envoi des identifiants de cette TV vers le code affiché sur un autre appareil */
    clean: function (raw) {
      var c = String(raw || '').toUpperCase().replace(/[\s\-]/g, '');
      if (c.length !== 8) return null;
      for (var i = 0; i < 8; i++) if (this.ABC.indexOf(c.charAt(i)) < 0) return null;
      return c;
    },
    send: function (code, cb) {
      var plain = unescape(encodeURIComponent(JSON.stringify({ s: P.server, u: P.user, p: P.pass }))), bytes = [], i;
      for (i = 0; i < plain.length; i++) bytes.push(plain.charCodeAt(i));
      var enc = this.xor(code, bytes), hex = '';
      for (i = 0; i < enc.length; i++) hex += ('0' + enc[i].toString(16)).slice(-2);
      try {
        var x = new XMLHttpRequest();
        x.open('POST', this.url(code) + '?x-http-method-override=PUT', true);
        x.setRequestHeader('Content-Type', 'text/plain');
        x.onload = function () { cb(x.status >= 200 && x.status < 300); };
        x.onerror = function () { cb(false); };
        x.send(JSON.stringify({ d: hex, at: Date.now() }));
      } catch (e) { cb(false); }
    },
    /* Attend que l'autre appareil ait récupéré l'envoi (il l'efface) ; sinon on l'efface au bout d'une minute */
    follow: function (code, cb) {
      var self = this, tries = 0;
      function check() {
        getJson(self.url(code), function (err, rec) {
          if (!err && rec === null) return cb(true);
          if (++tries >= 20) { self.remove(code); return cb(false); }
          setTimeout(check, 3000);
        });
      }
      setTimeout(check, 3000);
    },
    remove: function (code) {
      try {
        var x = new XMLHttpRequest();
        x.open('POST', this.url(code) + '?x-http-method-override=DELETE', true);
        x.setRequestHeader('Content-Type', 'text/plain');
        x.send('');
      } catch (e) { /* hors ligne */ }
    },
    /* Attend des identifiants pour le code affiché ; un nouveau code toutes les 10 minutes */
    start: function (onCode, onCreds) {
      var self = this;
      this.stop();
      onCode(this.newCode());
      function poll() {
        if (Date.now() - self.since > 600000) onCode(self.newCode());
        var code = self.code;
        getJson(self.url(code), function (err, rec) {
          if (!self.timer) return;
          if (!err && rec && rec.d && code === self.code) {
            var creds = null;
            try { creds = self.decrypt(code, rec.d); } catch (e) { creds = null; }
            self.remove(code);
            if (creds && creds.s && creds.u && creds.p) { self.stop(); onCreds(creds); return; }
          }
          self.timer = setTimeout(poll, 3000);
        });
      }
      this.timer = setTimeout(poll, 1500);
    },
    stop: function () { clearTimeout(this.timer); this.timer = null; }
  };

  /* Favoris partagés entre appareils : chaque favori garde la date de son dernier changement, le plus récent gagne */
  var Favs = {
    at: load('favat', {}),
    toggle: function (key) {
      this.set(key, !P.favs[key], Date.now());
      Sync.put(Sync.favPath(key), { k: key, on: !!P.favs[key], at: this.at[key] });
    },
    set: function (key, on, at) {
      if (on) P.favs[key] = 1; else delete P.favs[key];
      this.at[key] = at;
      save('favs', P.favs);
      save('favat', this.at);
    },
    /* tous les favoris connus ici (y compris retirés), avec leur date ; ceux d'avant le partage ont la date 1 */
    all: function () {
      var m = {}, k;
      for (k in this.at) if (this.at.hasOwnProperty(k)) m[k] = this.at[k];
      for (k in P.favs) if (P.favs.hasOwnProperty(k) && !m[k]) m[k] = 1;
      return m;
    }
  };

  /* Films et épisodes vus jusqu'au bout (partagés entre appareils) */
  var Seen = {
    m: load('seen', {}),
    has: function (key) { return !!this.m[key]; },
    mark: function (key) {
      if (!key || this.m[key]) return;
      this.m[key] = Date.now();
      save('seen', this.m);
      Sync.put('w/' + key, { at: this.m[key] });
    },
    merge: function (remote) {
      var changed = false, k;
      for (k in remote) if (remote.hasOwnProperty(k) && remote[k] && remote[k].at > 0 && !this.m[k]) { this.m[k] = remote[k].at; changed = true; }
      if (changed) save('seen', this.m);
      return changed;
    }
  };

  function liveItem(it) { return { title: it.name, url: X.url('live', it.id), live: true, id: it.id, num: it.num || 0, icon: it.icon }; }

  /* ---------- Catégories masquées, partagées entre les appareils (le changement le plus récent gagne) ---------- */

  var Hidden = {
    states: load('hcats', null),
    init: function () {
      if (this.states) return;
      // ancien réglage (code parental) : seules les catégories masquées sont reprises
      var old = load('cats', {}), k;
      this.states = {};
      for (k in old) if (old.hasOwnProperty(k) && old[k] === 'h') this.states[k] = { h: true, at: 1 };
      save('hcats', this.states);
      save('cats', {});
      save('pcode', '');
    },
    isHidden: function (kind, id) { var s = this.states[kind + ':' + id]; return !!(s && s.h); },
    set: function (kind, id, hidden) {
      var k = kind + ':' + id, s = { h: hidden, at: Date.now() };
      this.states[k] = s;
      save('hcats', this.states);
      Sync.pushCat(k, s);
    },
    /** Fusion avec l'état partagé ; renvoie true si quelque chose a changé. */
    merge: function (remote) {
      var changed = false, p, v, l;
      for (p in remote) {
        if (!remote.hasOwnProperty(p)) continue;
        v = remote[p];
        if (!v || !v.k || !(v.at > 0)) continue;
        l = this.states[v.k];
        if (l && l.at >= v.at) continue;
        if (!!(l && l.h) !== !!v.h) changed = true;
        this.states[v.k] = { h: !!v.h, at: v.at };
      }
      save('hcats', this.states);
      return changed;
    }
  };
  Hidden.init();

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

  /* Mesure de débit (test de connexion) : par le service de la TV, sinon directement */
  function speedTest(url, seconds, maxBytes, cb) {
    var done = false;
    function fin(err, r) { if (!done) { done = true; cb(err, r); } }
    if (window.PalmServiceBridge) {
      try {
        var b = new window.PalmServiceBridge();
        bridges.push(b);
        b.onservicecallback = function (raw) {
          var i = bridges.indexOf(b);
          if (i >= 0) bridges.splice(i, 1);
          var m;
          try { m = JSON.parse(raw); } catch (e) { return fin(new Error('Réponse invalide')); }
          if (m.returnValue === false) return fin(new Error(m.errorText || 'Serveur injoignable'));
          fin(null, { ttfb: m.ttfb, bytes: m.bytes, ms: m.ms });
        };
        b.call('luna://com.koala.tv.service/speed', JSON.stringify({ url: url, seconds: seconds, maxBytes: maxBytes }));
        return;
      } catch (e) { /* on passe au téléchargement direct */ }
    }
    var x = new XMLHttpRequest(), t0 = Date.now(), tFirst = 0, bytes = 0, stop = null;
    function end() {
      clearTimeout(stop);
      try { x.abort(); } catch (e) { /* déjà fermé */ }
      if (!tFirst) return fin(new Error('Serveur injoignable'));
      fin(null, { ttfb: tFirst - t0, bytes: bytes, ms: Date.now() - tFirst });
    }
    x.open('GET', url, true);
    x.responseType = 'arraybuffer';
    x.onprogress = function (e) {
      if (!tFirst) { tFirst = Date.now(); stop = setTimeout(end, seconds * 1000); }
      bytes = e.loaded;
      if (bytes >= maxBytes) end();
    };
    x.onload = function () { if (x.status >= 200 && x.status < 300) { if (!tFirst) tFirst = Date.now(); bytes = Math.max(bytes, x.response ? x.response.byteLength : 0); end(); } else fin(new Error('Erreur du serveur (' + x.status + ')')); };
    x.onerror = function () { if (tFirst) end(); else fin(new Error('Serveur injoignable')); };
    setTimeout(function () { if (!tFirst) { try { x.abort(); } catch (e) { /* déjà fermé */ } fin(new Error('Le serveur ne répond pas')); } }, (seconds + 8) * 1000);
    try { x.send(); } catch (e) { fin(new Error('Serveur injoignable')); }
  }
  function mbps(r) { return r && r.ms > 0 ? r.bytes * 8 / r.ms / 1000 : 0; }

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

  /** Décalage (secondes) entre l'heure locale du serveur et l'heure UTC : utile pour le replay. */
  function serverOffset(si) {
    if (!si) return null;
    var ts = parseInt(si.timestamp_now, 10), m = /^(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})/.exec(str(si.time_now));
    if (!ts || !m) return null;
    var asUtc = Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +m[6]) / 1000;
    return Math.round((asUtc - ts) / 900) * 900;
  }

  var X = {
    login: function (server, user, pass, cb) {
      getJson(apiUrl(server, user, pass), function (err, j) {
        if (err) return cb(err);
        var ui = j && j.user_info;
        if (!ui || str(ui.auth) !== '1') return cb(new Error('Identifiant ou mot de passe incorrect'));
        var st = str(ui.status).toLowerCase();
        if (st && st !== 'active') return cb(new Error(st === 'expired' ? 'Abonnement expiré' : 'Abonnement inactif'));
        ui.tzoff = serverOffset(j.server_info);
        cb(null, ui);
      });
    },

    /** Remet à jour l'expiration et le fuseau du serveur (une fois par lancement). */
    refreshAccount: function () {
      X.login(P.server, P.user, P.pass, function (err, ui) {
        if (err) return;
        setP('exp', str(ui.exp_date));
        if (ui.tzoff !== null) setP('tzoff', ui.tzoff);
      });
    },

    /** Adresse de replay : début à l'heure du serveur, durée en minutes. */
    catchupUrl: function (id, startSec, minutes) {
      var off = typeof P.tzoff === 'number' ? P.tzoff : -new Date(startSec * 1000).getTimezoneOffset() * 60;
      var d = new Date((startSec + off) * 1000);
      var start = d.getUTCFullYear() + '-' + pad2(d.getUTCMonth() + 1) + '-' + pad2(d.getUTCDate()) + ':' + pad2(d.getUTCHours()) + '-' + pad2(d.getUTCMinutes());
      return P.server + '/timeshift/' + encodeURIComponent(P.user) + '/' + encodeURIComponent(P.pass) + '/' + minutes + '/' + start + '/' + id + '.ts';
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

    epg: function (streamId, cb, limit, full) {
      var q = full ? api('get_simple_data_table', '&stream_id=' + encodeURIComponent(streamId))
        : api('get_short_epg', '&stream_id=' + encodeURIComponent(streamId) + '&limit=' + (limit || 2));
      getJson(q, function (err, j) {
        var out = [];
        if (!err && j && j.epg_listings && j.epg_listings.length) {
          for (var i = 0; i < j.epg_listings.length; i++) {
            var e = j.epg_listings[i], t = str(b64(str(e.title))), ts = parseInt(e.start_timestamp, 10),
              te = parseInt(e.stop_timestamp || e.end_timestamp, 10);
            if (!t || !ts || !te || te <= ts || (!full && te * 1000 <= Date.now())) continue;
            out.push({ h: ts ? hm(ts) : '', t: t, s: ts || 0, e: te || 0, d: str(b64(str(e.description))) });
          }
        }
        out.sort(function (a, b) { return a.s - b.s; });
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
    var FORMAT = 3, MAX_AGE = 6 * 3600 * 1000, STORE = 'lists';
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
        it.push([o.id, o.name, o.icon, o.cat, o.ext, o.num, o.archive, o.rating, o.added]);
      }
      return { v: FORMAT, account: account(), savedAt: data.savedAt, cats: c, items: it };
    }

    function unpack(kind, rec) {
      if (!rec || rec.v !== FORMAT || rec.account !== account()) return null;
      var cats = [], items = [], i, a;
      for (i = 0; i < rec.cats.length; i++) cats.push({ id: rec.cats[i][0], name: rec.cats[i][1] });
      for (i = 0; i < rec.items.length; i++) {
        a = rec.items[i];
        items.push({ kind: kind, id: a[0], name: a[1], icon: a[2], cat: a[3], ext: a[4], num: a[5], archive: a[6], rating: a[7], added: a[8] || 0 });
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
                rating: str(o.rating),
                // date d'ajout (secondes) : « Ajoutés récemment »
                added: parseInt(kind === 'series' ? (o.last_modified || o.added) : o.added, 10) || 0
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
      // OK n'est pris en compte qu'après relâchement de la touche (appui long qui a ouvert ce menu)
      this.armed = false;
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
      else if (c === K.OK) { if (this.armed) this.pick(this.idx); }
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
      var self = this;
      Pair.start(function (code) {
        $('l-code').textContent = code.substr(0, 4) + ' ' + code.substr(4);
      }, function (c) {
        self.fields[0].value = c.s;
        self.fields[1].value = c.u;
        self.fields[2].value = c.p;
        self.status('Identifiants reçus', false);
        self.submit();
      });
    },
    leave: function () { Pair.stop(); },
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
        setP('tzoff', ui.tzoff);
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
      $('h-search').addEventListener('click', function () { go('gsearch'); });
      $('h-new').addEventListener('click', function (e) {
        var n = e.target;
        while (n && n.getAttribute && n.getAttribute('data-n') === null) n = n.parentNode;
        if (!n || !n.getAttribute) return;
        self.zone = 'recent';
        self.nIdx = parseInt(n.getAttribute('data-n'), 10);
        self.paint();
        self.openRecent();
      });
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
      this.loadRecent();
      if (!this.accountDone) { this.accountDone = true; X.refreshAccount(); }
      this.pullHistory();
      Sync.pullCats();
      Sync.pullFavs();
      Sync.pullSeen();
    },
    resume: function () {
      this.drawResume();
      this.paint();
      this.loadRecent();
      this.pullHistory();
      Sync.pullCats();
      Sync.pullFavs();
      Sync.pullSeen();
      if (this.backToCats !== undefined) { var i = this.backToCats; delete this.backToCats; this.catChooser(i); }
      else if (this.backToSettings !== undefined) { var j = this.backToSettings; delete this.backToSettings; this.settings(j); }
    },
    pullHistory: function () {
      var self = this;
      Sync.pull(function () { if (cur === self) { self.drawResume(); self.paint(); } });
    },
    /* « Ajoutés récemment » : les 24 films et séries les plus récents, hors catégories masquées */
    recent: [],
    nIdx: 0,
    loadRecent: function () {
      var self = this, got = {}, n = 0;
      function done() {
        if (++n < 2) return;
        var all = [], k, i, it;
        for (k in got) if (got.hasOwnProperty(k)) for (i = 0; i < got[k].length; i++) {
          it = got[k][i];
          if (it.added > 0 && !Hidden.isHidden(it.kind, it.cat)) all.push(it);
        }
        all.sort(function (a, b) { return b.added - a.added; });
        self.recent = all.slice(0, 24);
        self.drawRecent();
      }
      Lists.get('movie', function (err, d) { got.movie = err ? [] : d.items; done(); });
      Lists.get('series', function (err, d) { got.series = err ? [] : d.items; done(); });
    },
    drawRecent: function () {
      var l = this.recent, h = '';
      for (var i = 0; i < l.length; i++) {
        h += '<div class="nc" data-n="' + i + '"><div class="ni">' + imgTag(l[i].icon) + '</div><div class="nn">' + esc(l[i].name) + '</div></div>';
      }
      $('h-new').innerHTML = h;
      cls(this.el, 'has-new', l.length > 0);
      if (this.nIdx >= l.length) this.nIdx = Math.max(0, l.length - 1);
      if (!l.length && this.zone === 'recent') this.zone = 'tiles';
      this.paint();
    },
    openRecent: function () {
      var it = this.recent[this.nIdx];
      if (it) go(it.kind === 'series' ? 'series' : 'movie', it);
    },
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
    keyup: function (c) {
      if (c !== K.OK || !this.okTimer) return;
      clearTimeout(this.okTimer);
      this.okTimer = null;
      if (this.zone === 'resume') this.resumeItem();
    },
    paint: function () {
      cls(this.el, 'resume-on', this.zone === 'resume');
      cls($('h-search'), 'focus', this.zone === 'search');
      // la rangée « Ajoutés récemment » est sous les tuiles : on fait remonter la page pour l'afficher
      var tl = this.el.querySelector('.tiles'), nr = $('h-recent');
      nr.style.top = (tl.offsetTop + tl.offsetHeight + 2.2 * REM) + 'px';
      var shift = this.zone === 'recent' ? Math.max(0, nr.offsetTop + nr.offsetHeight + 1.5 * REM - this.el.clientHeight) : 0;
      setTransform($('h-wrap'), 'translate3d(0,' + (-shift) + 'px,0)');
      var nc = $('h-new').children;
      for (var j = 0; j < nc.length; j++) cls(nc[j], 'focus', this.zone === 'recent' && j === this.nIdx);
      var f = nc[this.nIdx];
      if (f) {
        var w = $('h-new').parentNode.clientWidth, x = f.offsetLeft + f.offsetWidth - w + 2 * REM;
        setTransform($('h-new'), 'translate3d(' + (-Math.max(0, x)) + 'px,0,0)');
      }
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
        else if (c === K.UP) { this.zone = 'search'; this.paint(); }
        else if (c === K.OK) {
          // appui court : reprendre ; appui long : retirer
          var self = this;
          if (!this.okTimer) this.okTimer = setTimeout(function () { self.okTimer = null; self.forget(); }, 650);
        }
        else if (c === K.RED) this.forget();
        return true;
      }
      if (this.zone === 'search') {
        if (c === K.OK) go('gsearch');
        else if (c === K.DOWN) { this.zone = n ? 'resume' : 'tiles'; this.paint(); }
        return true;
      }
      if (this.zone === 'recent') {
        var m = this.recent.length;
        if (c === K.LEFT && this.nIdx > 0) { this.nIdx--; this.paint(); }
        else if (c === K.RIGHT && this.nIdx < m - 1) { this.nIdx++; this.paint(); }
        else if (c === K.UP) { this.zone = 'tiles'; this.paint(); }
        else if (c === K.OK) this.openRecent();
        return true;
      }
      if (c === K.DOWN && this.recent.length) { this.zone = 'recent'; this.paint(); return true; }
      if (c === K.UP) { this.zone = n ? 'resume' : 'search'; this.paint(); return true; }
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
    /* Choix du type de catégories ; le retour ramène aux réglages, et le retour de l'écran des catégories ramène ici */
    catChooser: function (idx) {
      var self = this;
      function open(kind, i) { self.backToCats = i; go('catset', { kind: kind }); }
      Dialog.show('Catégories visibles', '', [
        { label: 'TV en direct', fn: function () { open('live', 0); } },
        { label: 'Films', fn: function () { open('movie', 1); } },
        { label: 'Séries', fn: function () { open('series', 2); } },
        { label: 'Retour', fn: function () { self.settings(2); } }
      ], function () { self.settings(2); }, idx);
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
        { label: 'Catégories visibles', fn: function () { self.catChooser(0); } },
        { label: 'Connecter un appareil', fn: function () { self.backToSettings = 3; go('pairsend'); } },
        { label: 'Tester la connexion', fn: function () { self.backToSettings = 4; go('diag'); } },
        {
          label: 'Se déconnecter',
          fn: function () {
            Dialog.show('Se déconnecter ?', 'Tes favoris et reprises de lecture sont conservés.', [
              {
                label: 'Se déconnecter',
                fn: function () { setP('pass', ''); Lists.clear(); reset('login'); }
              },
              { label: 'Annuler', fn: function () { self.settings(5); } }
            ], function () { self.settings(5); });
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
    return '<div class="cat"><span class="n">' + esc(c.name) + '</span>' +
      '<span class="c">' + (c.label !== undefined ? esc(c.label) : fmtNum(c.n)) + '</span></div>';
  }
  /* Programme en cours des chaînes visibles : chargé à la demande, 4 à la fois, gardé jusqu'à la fin du programme */
  var Epg = {
    cache: {}, queue: [], running: 0, onLoaded: null,
    /** Programmes à venir (le premier peut être en cours) ; null = en cours de chargement. */
    /** Guide complet, passé compris (chaînes avec replay). */
    full: function (id) {
      var c = this.cache['H:' + id], t = Date.now() / 1000;
      if (!(c && t < c.until) && !(c && c.loading)) this.want('H:' + id);
      return c && c.rows ? c.rows : null;
    },
    want: function (key) {
      for (var i = 0; i < this.queue.length; i++) if (this.queue[i] === key) return;
      this.queue.push(key);
      if (this.queue.length > 40) this.queue.shift(); // seules les dernières lignes affichées comptent
      this.pump();
    },
    list: function (id) {
      var c = this.cache[id], t = Date.now() / 1000, out = null, i;
      if (c && c.rows) { out = []; for (i = 0; i < c.rows.length; i++) if (c.rows[i].e > t) out.push(c.rows[i]); }
      if (c && t < c.until) return out;
      if (!c || !c.loading) {
        for (i = 0; i < this.queue.length; i++) if (this.queue[i] === id) return out;
        this.queue.push(id);
        if (this.queue.length > 40) this.queue.shift(); // seules les dernières lignes affichées comptent
        this.pump();
      }
      return out;
    },
    now: function (id) {
      var l = this.list(id), t = Date.now() / 1000;
      if (l) for (var i = 0; i < l.length; i++) if (l[i].s <= t && l[i].e > t) return l[i];
      return null;
    },
    pump: function () {
      var self = this;
      while (this.running < 4 && this.queue.length) {
        var key = this.queue.pop();
        var old = this.cache[key];
        this.cache[key] = { loading: true, until: 0, rows: old ? old.rows : null };
        this.running++;
        (function (key) {
          var full = key.indexOf('H:') === 0, id = full ? key.substr(2) : key;
          X.epg(id, function (rows) {
            self.running--;
            var t = Date.now() / 1000, until = t + 1800;
            // gardé jusqu'à la fin du programme en cours ; pas de guide : on ne redemande pas avant 30 min
            for (var i = 0; i < rows.length; i++) if (rows[i].e > t) { until = rows[i].e; break; }
            self.cache[key] = { rows: rows, until: until };
            if (self.onLoaded) self.onLoaded(id);
            self.pump();
          }, 24, full);
        })(key);
      }
    }
  };

  /*
   * Recherche : titre exact, titre qui commence par la saisie, mot qui commence par la saisie,
   * puis (à partir de 2 lettres) titre qui contient la saisie. q est déjà « plié » (minuscules, sans accents).
   */
  function searchList(pool, q, max) {
    var groups = [[], [], [], []], wq = ' ' + q;
    for (var i = 0; i < pool.length; i++) {
      var it = pool[i];
      if (it._f === undefined) {
        it._f = fold(it.name);
        it._w = ' ' + it._f.replace(/[^a-z0-9]+/g, ' ');
        // sans préfixe de langue (« FR | », « EN: »…) ni année finale
        it._c = it._f.replace(/^\s*[a-z0-9+]{1,6}\s*[|:\-]\s*/, '').replace(/\s*[\(\[]?\d{4}[\)\]]?\s*$/, '');
      }
      var g = it._c === q || it._f === q ? 0 : it._c.indexOf(q) === 0 || it._f.indexOf(q) === 0 ? 1 :
        it._w.indexOf(wq) >= 0 ? 2 : q.length > 1 && it._f.indexOf(q) >= 0 ? 3 : -1;
      if (g >= 0) groups[g].push(it);
    }
    var out = groups[0].concat(groups[1], groups[2], groups[3]);
    return max ? out.slice(0, max) : out;
  }

  var KIND_LABEL = { live: 'Chaîne', movie: 'Film', series: 'Série' };
  function renderAny(it) {
    if (it.kind === 'live') return renderRow(it).replace('<div class="row">', '<div class="row"><span class="kd">Chaîne</span>');
    return '<div class="row"><span class="kd">' + KIND_LABEL[it.kind] + '</span><span class="logo pst">' + imgTag(it.icon) + '</span><span class="tx"><span class="n">' +
      esc(it.name) + '</span></span>' + (isFav(it) ? STAR : '') + '</div>';
  }

  function renderRow(it) {
    var p = Epg.now(it.id), g = '';
    if (p) {
      var pct = Math.max(0, Math.min(100, (Date.now() / 1000 - p.s) / (p.e - p.s) * 100));
      g = '<span class="g">' + hm(p.s) + ' – ' + hm(p.e) + ' · ' + esc(p.t) + '</span><span class="gb"><i style="width:' + pct.toFixed(1) + '%"></i></span>';
    }
    return '<div class="row"><span class="logo">' + imgTag(it.icon) + '</span><span class="tx"><span class="n">' + esc(it.name) +
      (it.archive > 0 ? '<em class="tagrp">Replay</em>' : '') + '</span>' +
      g + '</span>' + (isFav(it) ? STAR : '') + '</div>';
  }
  function renderPoster(it) {
    return '<div class="poster"><div class="img">' + imgTag(it.icon) + (isFav(it) ? STAR : '') +
      '</div><div class="n">' + esc(it.name) + '</div></div>';
  }

  /* ---------- Test de connexion : internet, serveur Xtream, abonnement, débit du serveur ---------- */


  screens.diag = {
    el: $('s-diag'),
    /* serveur de référence pour mesurer internet (rapide, partout) */
    net: 'https://speed.cloudflare.com/__down?bytes=',
    token: 0,
    init: function () {
      var self = this;
      $('dg-btn').addEventListener('click', function () { self.run(); });
    },
    enter: function () { this.run(); },
    leave: function () { this.token++; },
    row: function (id, state, value) {
      var el = $(id);
      el.className = 'dg-row ' + state;
      el.querySelector('.v').textContent = value;
    },
    run: function () {
      var self = this, token = ++this.token, res = {};
      function alive() { return token === self.token && cur === self; }
      this.row('dg-net', 'wait', 'Mesure…');
      this.row('dg-srv', 'wait', 'En attente');
      this.row('dg-acc', 'wait', 'En attente');
      this.row('dg-str', 'wait', 'En attente');
      $('dg-verdict').textContent = '';
      cls($('dg-verdict'), 'on', false);
      cls($('dg-btn'), 'focus', false);
      // 1. internet : temps de réponse puis débit (6 s au plus)
      speedTest(self.net + '0', 1, 1, function (e0, ping) {
        if (!alive()) return;
        speedTest(self.net + '50000000', 6, 50000000, function (e1, r1) {
          if (!alive()) return;
          if (e1) { res.net = -1; self.row('dg-net', 'bad', 'Pas d\'accès à internet'); }
          else {
            res.net = mbps(r1);
            res.ping = ping ? ping.ttfb : r1.ttfb;
            self.row('dg-net', res.net < 8 ? 'warn' : 'ok', fmtMbps(res.net) + ' · réponse en ' + res.ping + ' ms');
          }
          step2();
        });
      });
      // 2. serveur Xtream et abonnement
      function step2() {
        self.row('dg-srv', 'wait', 'Mesure…');
        var t0 = Date.now();
        getJson(apiUrl(P.server, P.user, P.pass, ''), function (err, j) {
          if (!alive()) return;
          res.srvMs = Date.now() - t0;
          var ui = j && j.user_info;
          if (err || !ui) {
            res.srv = false;
            self.row('dg-srv', 'bad', err ? err.message : 'Réponse invalide');
            self.row('dg-acc', 'bad', 'Inconnu');
            self.row('dg-str', 'bad', 'Non mesuré');
            return verdict();
          }
          res.srv = true;
          self.row('dg-srv', res.srvMs > 2000 ? 'warn' : 'ok', 'Répond en ' + res.srvMs + ' ms');
          res.auth = str(ui.auth) === '1' && /active/i.test(str(ui.status) || 'active');
          res.cons = parseInt(ui.active_cons, 10) || 0;
          res.max = parseInt(ui.max_connections, 10) || 0;
          var exp = parseInt(ui.exp_date, 10), txt = res.auth ? 'Actif' : 'Inactif (' + (str(ui.status) || 'refusé') + ')';
          if (res.max) txt += ' · ' + res.cons + ' / ' + res.max + ' connexion' + (res.max > 1 ? 's' : '');
          if (exp > 0) { var d = new Date(exp * 1000); txt += ' · expire le ' + pad2(d.getDate()) + '/' + pad2(d.getMonth() + 1) + '/' + d.getFullYear(); }
          self.row('dg-acc', !res.auth ? 'bad' : (res.max && res.cons >= res.max) ? 'warn' : 'ok', txt);
          step3();
        });
      }
      // 3. débit réel depuis le serveur Xtream : un film (le plus rapide possible), sinon une chaîne
      function step3() {
        self.row('dg-str', 'wait', 'Mesure…');
        Lists.get('movie', function (err, d) {
          if (!alive()) return;
          var m = !err && d.items.length ? d.items[0] : null;
          if (m) return measure(X.url('movie', m.id, m.ext || 'mp4'), false);
          Lists.get('live', function (err2, d2) {
            if (!alive()) return;
            var c = !err2 && d2.items.length ? d2.items[0] : null;
            if (!c) { self.row('dg-str', 'warn', 'Aucun contenu pour mesurer'); return verdict(); }
            measure(P.server + '/live/' + encodeURIComponent(P.user) + '/' + encodeURIComponent(P.pass) + '/' + c.id + '.ts', true);
          });
        });
      }
      function measure(url, live) {
        res.live = live;
        speedTest(url, 6, 50000000, function (e, r) {
          if (!alive()) return;
          if (e) { res.str = -1; self.row('dg-str', 'bad', e.message); }
          else {
            res.str = mbps(r);
            self.row('dg-str', res.str < 6 ? 'warn' : 'ok', fmtMbps(res.str) + (live ? ' (chaîne en direct)' : ''));
          }
          verdict();
        });
      }
      function verdict() {
        var v;
        if (res.net === -1) v = 'Pas d\'accès à internet : vérifie ta box ou le Wi-Fi.';
        else if (res.srv === false) v = 'Internet fonctionne, mais le serveur Xtream ne répond pas : le souci vient du fournisseur.';
        else if (res.auth === false) v = 'L\'abonnement Xtream est refusé ou inactif : contacte ton fournisseur.';
        else if (res.max && res.cons >= res.max) v = 'L\'abonnement est déjà utilisé au maximum (' + res.cons + ' / ' + res.max + ') : ferme l\'appli sur un autre appareil.';
        else if (res.net >= 0 && res.net < 8) v = 'Ta connexion internet est lente (' + fmtMbps(res.net) + ') : la HD demande au moins 8 Mbit/s.';
        else if (res.str === -1) v = 'Internet fonctionne, mais le serveur Xtream n\'envoie pas de vidéo : le souci vient du fournisseur.';
        else if (res.str >= 0 && res.str < 6 && !res.live) v = 'Le serveur Xtream est lent (' + fmtMbps(res.str) + ' contre ' + fmtMbps(res.net) + ' pour internet) : le souci vient du fournisseur.';
        else if (res.srvMs > 2000) v = 'Le serveur Xtream met du temps à répondre : les listes et le zapping peuvent être lents.';
        else v = 'Tout va bien : ta connexion et le serveur Xtream sont rapides.';
        $('dg-verdict').textContent = v;
        cls($('dg-verdict'), 'on', true);
        cls($('dg-btn'), 'focus', true);
      }
    },
    key: function (c) {
      if (c === K.OK) this.run();
      return true;
    }
  };
  function fmtMbps(v) { return (v >= 100 ? Math.round(v) : v.toFixed(1).replace('.', ',')) + ' Mbit/s'; }

  /* ---------- Connecter un appareil (envoi du code depuis la TV) ---------- */

  screens.pairsend = {
    el: $('s-pairsend'),
    idx: 0,
    init: function () {
      var self = this;
      this.input = $('ps-code');
      this.input.addEventListener('blur', function () { cls(self.input.parentNode, 'editing', false); });
      $('ps-btn').addEventListener('click', function () { self.idx = 1; self.paint(); self.send(); });
      this.input.parentNode.addEventListener('click', function () { self.idx = 0; self.paint(); self.edit(); });
    },
    enter: function () {
      this.input.value = '';
      this.idx = 0;
      this.status('', false);
      this.paint();
      var self = this;
      setTimeout(function () { if (cur === self) self.edit(); }, 50);
    },
    paint: function () {
      cls(this.input.parentNode, 'focus', this.idx === 0);
      cls($('ps-btn'), 'focus', this.idx === 1);
    },
    status: function (msg, err) { var s = $('ps-status'); s.textContent = msg; cls(s, 'err', !!err); },
    edit: function () { cls(this.input.parentNode, 'editing', true); this.input.focus(); },
    inputDone: function (input, c) {
      if (c === K.OK || c === K.DOWN) { this.idx = 1; this.paint(); if (c === K.OK) this.send(); }
    },
    send: function () {
      var self = this, code = Pair.clean(this.input.value);
      if (!code) { this.status('Code incorrect : 8 caractères, comme affiché sur l\'autre appareil', true); return; }
      this.status('Envoi des identifiants…', false);
      Pair.send(code, function (ok) {
        if (!ok) { self.status('Envoi impossible, vérifie la connexion', true); return; }
        self.status('En attente de l\'autre appareil…', false);
        Pair.follow(code, function (done) {
          if (done) { toast('Appareil connecté'); if (cur === self) back(); }
          else self.status('L\'appareil n\'a pas répondu, vérifie le code', true);
        });
      });
    },
    key: function (c) {
      if (c === K.UP && this.idx > 0) { this.idx = 0; this.paint(); return true; }
      if (c === K.DOWN && this.idx < 1) { this.idx = 1; this.paint(); return true; }
      if (c === K.OK) { if (this.idx === 0) this.edit(); else this.send(); return true; }
      return true;
    }
  };

  /* ---------- Recherche globale (chaînes, films et séries) ---------- */

  screens.gsearch = {
    el: $('s-gsearch'),
    zone: 'input',
    pool: [],
    query: '',
    init: function () {
      var self = this;
      this.input = $('gs-input');
      this.list = new VList($('gs-list'), {
        rowH: 5.2,
        render: renderAny,
        onPointer: function () { self.setZone('list'); },
        onOk: function () { self.open(); }
      });
      this.input.addEventListener('input', function () {
        clearTimeout(self.timer);
        self.timer = setTimeout(function () { self.run(self.input.value); }, 300);
      });
      this.input.addEventListener('blur', function () { cls($('gs-box'), 'editing', false); });
      $('gs-box').addEventListener('click', function () { self.setZone('input'); self.edit(); });
    },
    enter: function () {
      var self = this, n = 0, got = {};
      this.input.value = '';
      this.query = '';
      this.list.set([]);
      this.head('', '');
      this.msg('Tape le nom d\'une chaîne, d\'un film ou d\'une série');
      this.setZone('input');
      this.pool = [];
      ['live', 'movie', 'series'].forEach(function (k) {
        Lists.get(k, function (err, d) {
          got[k] = err ? [] : d.items;
          if (++n < 3) return;
          var all = [];
          ['live', 'movie', 'series'].forEach(function (kk) {
            for (var i = 0; i < got[kk].length; i++) if (!Hidden.isHidden(kk, got[kk][i].cat)) all.push(got[kk][i]);
          });
          self.pool = all;
          if (self.query) self.run(self.input.value);
        });
      });
      setTimeout(function () { if (cur === self) self.edit(); }, 50);
    },
    resume: function () { this.list.redraw(); this.paintZone(); },
    head: function (t, c) { $('gs-head').textContent = t; $('gs-count').textContent = c; },
    msg: function (txt) { var m = $('gs-msg'); m.textContent = txt; cls(m, 'on', !!txt); },
    edit: function () { cls($('gs-box'), 'editing', true); this.input.focus(); },
    run: function (raw) {
      var q = fold(str(raw)).replace(/^\s+|\s+$/g, '');
      this.query = q;
      if (!q) { this.list.set([]); this.head('', ''); this.msg('Tape le nom d\'une chaîne, d\'un film ou d\'une série'); return; }
      var out = searchList(this.pool, q, 300);
      this.list.set(out, 0);
      this.head('Résultats', out.length ? plural(out.length, 'résultat', 'résultats') : '');
      this.msg(out.length ? '' : (this.pool.length ? 'Aucun résultat' : 'Chargement des listes…'));
    },
    inputDone: function (input, c) {
      clearTimeout(this.timer);
      this.run(input.value);
      if ((c === K.OK || c === K.DOWN || c === K.RIGHT) && this.list.items.length) this.setZone('list');
      else this.setZone('input');
    },
    setZone: function (z) { this.zone = z; this.paintZone(); },
    paintZone: function () {
      cls($('gs-box'), 'focus', this.zone === 'input');
      if (this.list.active !== (this.zone === 'list')) this.list.focus(this.zone === 'list');
    },
    open: function () {
      var it = this.list.items[this.list.idx];
      if (!it) return;
      if (it.kind === 'series') { go('series', it); return; }
      if (it.kind === 'movie') { go('movie', it); return; }
      // chaîne : on zappe parmi les chaînes trouvées
      var list = [], at = 0;
      for (var i = 0; i < this.list.items.length; i++) {
        if (this.list.items[i].kind !== 'live') continue;
        if (this.list.items[i] === it) at = list.length;
        list.push(liveItem(this.list.items[i]));
      }
      go('player', { list: list, index: at });
    },
    key: function (c) {
      if (this.zone === 'input') {
        if (c === K.OK) this.edit();
        else if ((c === K.DOWN || c === K.RIGHT) && this.list.items.length) this.setZone('list');
        return true;
      }
      if (c === K.UP) { if (!this.list.move(0, -1)) this.setZone('input'); }
      else if (c === K.DOWN) this.list.move(0, 1);
      else if (c === K.CH_UP || c === K.CH_DOWN) this.list.page(c === K.CH_UP ? -1 : 1);
      else if (c === K.OK) this.open();
      return true;
    }
  };

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
        rowH: 2.55,
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
      $('b-guide').addEventListener('click', function () { self.openGuide(); });
    },
    enter: function (p) {
      var self = this, token = ++this.token;
      this.kind = p.kind;
      this.query = '';
      this.search.value = '';
      $('b-title').textContent = TITLES[this.kind];
      cls($('b-guide'), 'hide', this.kind !== 'live');
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
        // éléments affichables : sans les catégories masquées
        if (!Hidden.isHidden(kind, items[i].cat)) {
          pool.push(items[i]);
          if (isFav(items[i])) favN++;
        }
      }
      list.push({ id: '*fav', name: 'Favoris', n: favN });
      list.push({ id: '*all', name: 'Tout', n: pool.length });
      for (i = 0; i < cats.length; i++) {
        if (!counts[cats[i].id] || Hidden.isHidden(kind, cats[i].id)) continue;
        list.push({ id: cats[i].id, name: cats[i].name, n: counts[cats[i].id] });
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
      var c = this.catList[i];
      if (!c) return;
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
      var q = fold(str(raw)).replace(/^\s+|\s+$/g, '');
      this.query = q;
      if (!q) {
        this.pickCat(this.catIdx, true, true);
        return;
      }
      var out = searchList(this.pool, q);
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
    openGuide: function () {
      if (this.kind !== 'live' || !this.items.items.length) return;
      go('guide', { channels: this.items.items, index: this.zone === 'items' ? this.items.idx : 0 });
    },
    paintZone: function () {
      cls($('b-guide'), 'focus', this.zone === 'gbtn');
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
      Favs.toggle(key);
      toast(P.favs[key] ? 'Ajouté aux favoris' : 'Retiré des favoris');
      this.items.refresh(this.items.idx);
      this.catList[0].n = this.countFavs();
      this.cats.refresh(0);
    },
    inputDone: function (input, c) {
      clearTimeout(this.searchTimer);
      this.runSearch(this.search.value);
      // la recherche reste affichée : OK, bas ou droite mènent aux résultats
      if ((c === K.OK || c === K.DOWN || c === K.RIGHT) && this.items.items.length) this.setZone('items');
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
      if ((c === K.GREEN || c === K.GUIDE) && this.kind === 'live') { this.openGuide(); return true; }
      if (this.zone === 'gbtn') {
        if (c === K.OK) this.openGuide();
        else if (c === K.DOWN && this.items.items.length) this.setZone('items');
        else if (c === K.LEFT) this.setZone('cats');
        return true;
      }
      if (this.zone === 'search') {
        if (c === K.OK) this.editSearch();
        else if (c === K.DOWN) this.setZone(this.query && this.items.items.length ? 'items' : 'cats');
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
      if (c === K.UP) {
        if (!this.items.move(0, -1)) this.setZone(this.query ? 'search' : this.kind === 'live' ? 'gbtn' : 'items');
        return true;
      }
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
        rowH: 2.55,
        render: renderCat,
        onPointer: function () { self.setZone('seasons'); },
        onOk: function (i) { self.pickSeason(i); self.setZone('eps'); }
      });
      this.eList = new VList($('sr-eps'), {
        rowH: 6.4,
        render: function (e) {
          var pos = P.pos['e' + e.id] || 0, seen = !pos && Seen.has('e' + e.id);
          var title = cleanEpTitle(e.title, self.it.name) || 'Épisode ' + e.num;
          return '<div class="ep' + (seen ? ' seen' : '') + '"><div class="t"><span class="num">' + e.num + '</span><span class="tt">' + esc(title) +
            '</span>' + (pos ? '<span class="r">Reprendre à ' + fmtTime(pos) + '</span>' : '') +
            (seen ? '<span class="vu"><svg viewBox="0 0 24 24"><path d="M5 12.5l4.5 4.5L19 7.5"/></svg>Vu</span>' : '') + '</div>' +
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
        // on se place sur l'épisode en cours, sinon sur celui qui suit le dernier vu
        var t = self.nextToWatch(eps), si = 0;
        for (i = 0; i < list.length; i++) if (list[i].id === eps[t].season) si = i;
        self.pickSeason(si);
        if (t > 0 || P.pos['e' + eps[0].id] || Seen.has('e' + eps[0].id)) {
          for (i = 0; i < self.eList.items.length; i++) if (self.eList.items[i] === eps[t]) self.eList.set(self.eList.items, i);
          self.setZone('eps');
        }
      });
      Sync.pullSeen(function () { if (cur === self) self.eList.redraw(); });
    },
    resume: function () {
      this.eList.redraw();
      this.paintZone();
    },
    leave: function () { spinner(false); },
    /* Index de l'épisode à proposer : le plus récemment commencé, sinon celui après le dernier vu */
    nextToWatch: function (eps) {
      var best = -1, bestAt = 0, lastSeen = -1, i, k, h;
      for (i = 0; i < eps.length; i++) {
        k = 'e' + eps[i].id;
        if (P.pos[k]) {
          var at = 1;
          for (h = 0; h < P.hist.length; h++) if (P.hist[h].k === k) at = P.hist[h].a || 1;
          if (at > bestAt) { bestAt = at; best = i; }
        }
        if (Seen.has(k)) lastSeen = i;
      }
      if (best >= 0) return best;
      if (lastSeen >= 0 && lastSeen < eps.length - 1) return lastSeen + 1;
      return 0;
    },
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

  /* ---------- Guide TV en grille ---------- */

  screens.guide = {
    el: $('s-guide'),
    SPAN: 3 * 3600,
    rowH: 0,
    init: function () {
      var self = this;
      $('g-rows').addEventListener('click', function (e) {
        var n = e.target, prog = null, row = null;
        while (n && n !== this) {
          if (n.getAttribute && n.getAttribute('data-p') !== null && prog === null) prog = parseInt(n.getAttribute('data-p'), 10);
          if (n.getAttribute && n.getAttribute('data-r') !== null) { row = parseInt(n.getAttribute('data-r'), 10); break; }
          n = n.parentNode;
        }
        if (row === null) return;
        var list = self.rowList(row) || [], nw = Date.now() / 1000;
        if (row === self.row && prog !== null && list[prog] === self.selected(row)) { self.play(); return; }
        self.row = row;
        if (prog !== null && list[prog]) self.time = list[prog].s <= nw && list[prog].e > nw ? nw : list[prog].s;
        self.render();
      });
    },
    floor30: function (t) { return t - t % 1800; },
    enter: function (p) {
      var self = this, days = 0;
      this.ch = p.channels;
      for (var k = 0; k < this.ch.length; k++) days = Math.max(days, this.ch[k].archive || 0);
      // jusqu'à 7 jours en arrière si des chaînes ont du replay
      this.minStart = this.floor30(Math.floor(Date.now() / 1000) - Math.min(7, days) * 86400);
      this.row = Math.max(0, Math.min(p.index || 0, this.ch.length - 1));
      this.time = Date.now() / 1000;
      this.start = this.floor30(Math.floor(this.time));
      this.top = Math.max(0, this.row - 1);
      this.timer = setInterval(function () { self.render(); }, 30000);
      this.render();
    },
    leave: function () { clearInterval(this.timer); },
    resume: function () { this.render(); },
    loaded: function (id) {
      for (var i = this.top; i < Math.min(this.ch.length, this.top + this.vis + 1); i++) if (this.ch[i].id === id) { this.render(); return; }
    },
    /* programmes d'une ligne : passé compris pour les chaînes avec replay */
    rowList: function (row) {
      var c = this.ch[row];
      if (!(c.archive > 0)) return Epg.list(c.id);
      var all = Epg.full(c.id), from = Date.now() / 1000 - c.archive * 86400, out = [];
      if (!all) return null;
      for (var i = 0; i < all.length; i++) if (all[i].e > from) out.push(all[i]);
      return out;
    },
    canReplay: function (row, p) {
      var c = this.ch[row], now = Date.now() / 1000;
      return c.archive > 0 && p.e <= now && p.s >= now - c.archive * 86400;
    },
    selected: function (row) {
      var l = this.rowList(row), i;
      if (!l || !l.length) return null;
      for (i = 0; i < l.length; i++) if (l[i].s <= this.time && l[i].e > this.time) return l[i];
      for (i = 0; i < l.length; i++) if (l[i].s > this.time) return l[i];
      return l[l.length - 1];
    },
    render: function () {
      var box = $('g-rows'), now = Date.now() / 1000, end = this.start + this.SPAN, i, j;
      this.rowH = 3.7 * REM;
      this.vis = Math.max(1, Math.floor(box.clientHeight / this.rowH));
      if (this.row < this.top) this.top = this.row;
      if (this.row > this.top + this.vis - 1) this.top = this.row - this.vis + 1;
      // règle des heures
      var r = '';
      for (var t = this.start; t < end; t += 1800) r += '<span style="left:' + ((t - this.start) / this.SPAN * 100) + '%">' + hm(t) + '</span>';
      if (now >= this.start && now < end) r += '<i style="left:' + ((now - this.start) / this.SPAN * 100) + '%"></i>';
      $('g-ruler').innerHTML = r;
      var d = new Date(this.start * 1000), days = ['Dimanche', 'Lundi', 'Mardi', 'Mercredi', 'Jeudi', 'Vendredi', 'Samedi'];
      $('g-day').textContent = days[d.getDay()] + ' ' + d.getDate();
      $('g-clock').textContent = hm(now);
      // lignes visibles
      var h = '';
      for (i = this.top; i < Math.min(this.ch.length, this.top + this.vis); i++) {
        var c = this.ch[i], l = this.rowList(i), sel = i === this.row ? this.selected(i) : null, cells = '';
        if (!l || !l.length) {
          cells = '<div class="gp empty' + (i === this.row ? ' focus' : '') + '" style="left:0;width:100%"><b>' + (l ? 'Pas de programme' : 'Chargement…') + '</b></div>';
        } else {
          // passé d'une chaîne sans replay : bloc neutre
          var gapEnd = Math.min(now, l[0].s, end);
          if (!(c.archive > 0) && gapEnd - this.start >= 900) {
            cells += '<div class="gp past empty" style="left:0;width:' + ((gapEnd - this.start) / this.SPAN * 100).toFixed(3) + '%"><b>Pas de replay</b></div>';
          }
          for (j = 0; j < l.length; j++) {
            var p = l[j];
            if (p.e <= this.start || p.s >= end) continue;
            var x0 = Math.max(0, (p.s - this.start) / this.SPAN), x1 = Math.min(1, (p.e - this.start) / this.SPAN);
            var past = p.e <= now, rp = past && c.archive > 0;
            cells += '<div class="gp' + (p === sel ? ' focus' : '') + (p.s <= now && p.e > now ? ' now' : '') + (past && !rp ? ' past' : '') +
              '" data-p="' + j + '" style="left:' + (x0 * 100).toFixed(3) + '%;width:' + ((x1 - x0) * 100).toFixed(3) + '%"><b>' + esc(p.t) +
              '</b><small>' + hm(p.s) + ' – ' + hm(p.e) + (rp ? ' <em>Replay</em>' : '') + '</small></div>';
          }
        }
        if (now >= this.start && now < end) cells += '<i class="gnow" style="left:' + ((now - this.start) / this.SPAN * 100) + '%"></i>';
        h += '<div class="grow' + (i === this.row ? ' sel' : '') + '" data-r="' + i + '" style="top:' + ((i - this.top) * this.rowH) + 'px;height:' + this.rowH + 'px">' +
          '<div class="gch"><span class="logo">' + imgTag(c.icon) + '</span><span class="n">' + esc(c.name) + '</span></div>' +
          '<div class="gstrip">' + cells + '</div></div>';
      }
      box.innerHTML = h;
      // détails du programme sélectionné
      var ch = this.ch[this.row], ps = this.selected(this.row), lst = this.rowList(this.row);
      $('g-title').textContent = ps ? ps.t : ch.name;
      var when;
      if (!ps) when = lst ? 'Pas de programme pour cette chaîne' : 'Chargement du programme…';
      else when = ch.name + '  ·  ' + hm(ps.s) + ' – ' + hm(ps.e) + '  ·  ' + (ps.e <= now
        ? (this.canReplay(this.row, ps) ? 'Replay · OK pour regarder' : 'terminé')
        : ps.s <= now
        ? 'en cours · reste ' + Math.max(1, Math.floor((ps.e - now) / 60)) + ' min'
        : 'dans ' + Math.max(1, Math.floor((ps.s - now) / 60)) + ' min');
      $('g-when').textContent = when;
      $('g-desc').textContent = ps && ps.d ? ps.d : '';
    },
    moveRow: function (dlt) {
      var n = Math.max(0, Math.min(this.ch.length - 1, this.row + dlt));
      if (n === this.row) return;
      this.row = n;
      this.render();
    },
    moveProg: function (dlt) {
      var l = this.rowList(this.row) || [], cur = this.selected(this.row), idx = -1, i, now = Date.now() / 1000;
      for (i = 0; i < l.length; i++) if (l[i] === cur) idx = i;
      var next = l[idx + dlt];
      if (!next) {
        // ligne vide ou bout de la liste : on fait défiler le temps par demi-heure
        var s = Math.max(this.minStart, this.start + dlt * 1800);
        if (s === this.start) return;
        this.start = s;
        this.time = dlt < 0 ? s + 60 : Math.max(now, s);
      } else {
        this.time = next.s <= now && next.e > now ? now : next.s;
        if (next.s >= this.start + this.SPAN - 1800) this.start = this.floor30(next.s) - 1800;
        if (next.e <= this.start || (next.s < this.start && dlt < 0)) this.start = Math.max(this.minStart, this.floor30(next.s));
      }
      this.render();
    },
    play: function () {
      var p = this.selected(this.row), c = this.ch[this.row];
      if (p && this.canReplay(this.row, p)) {
        var url = X.catchupUrl(c.id, p.s, Math.ceil((p.e - p.s) / 60));
        go('player', { list: [{ title: c.name + ' · ' + p.t, url: url, live: false, id: c.id, icon: c.icon }], index: 0 });
        return;
      }
      var list = [];
      for (var i = 0; i < this.ch.length; i++) list.push(liveItem(this.ch[i]));
      go('player', { list: list, index: this.row });
    },
    key: function (c) {
      if (c === K.UP) this.moveRow(-1);
      else if (c === K.DOWN) this.moveRow(1);
      else if (c === K.CH_UP) this.moveRow(-this.vis);
      else if (c === K.CH_DOWN) this.moveRow(this.vis);
      else if (c === K.LEFT) this.moveProg(-1);
      else if (c === K.RIGHT) this.moveProg(1);
      else if (c === K.OK) this.play();
      return true;
    }
  };

  /* ---------- Catégories visibles / masquées ---------- */

  screens.catset = {
    el: $('s-catset'),
    init: function () {
      var self = this;
      this.list = new VList($('cs-list'), {
        rowH: 3.4,
        render: function (c) {
          var h = Hidden.isHidden(self.kind, c.id);
          return '<div class="cat' + (h ? ' cs-h' : '') + '"><span class="n">' + esc(c.name) + '</span>' +
            '<span class="c">' + (h ? 'Masquée' : 'Visible') + '</span></div>';
        },
        onOk: function (i) { self.toggle(i); }
      });
    },
    enter: function (p) {
      var self = this;
      this.kind = p.kind;
      $('cs-title').textContent = 'Catégories · ' + TITLES[p.kind];
      this.list.set([]);
      this.list.focus(true);
      spinner(true);
      Sync.pullCats(function () { if (cur === self) self.list.redraw(); });
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
    toggle: function (i) {
      var c = this.list.items[i];
      if (!c) return;
      Hidden.set(this.kind, c.id, !Hidden.isHidden(this.kind, c.id));
      this.list.refresh(i);
    },
    key: function (c) {
      if (c === K.UP) this.list.move(0, -1);
      else if (c === K.DOWN) this.list.move(0, 1);
      else if (c === K.CH_UP || c === K.CH_DOWN) this.list.page(c === K.CH_UP ? -1 : 1);
      else if (c === K.OK) this.toggle(this.list.idx);
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
        Favs.toggle(key);
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
      v.addEventListener('play', function () { self.paintCtl(); });
      v.addEventListener('pause', function () { self.paintCtl(); });
      // boutons à la souris (télécommande à pointeur)
      $('p-ctl').onclick = function (e) {
        var n = e.target;
        while (n && n.getAttribute && n.getAttribute('data-a') === null) n = n.parentNode;
        if (!n || !n.getAttribute) return;
        var a = n.getAttribute('data-a');
        for (var k = 0; k < self.ACT.length; k++) if (self.ACT[k] === a) self.cIdx = k;
        self.ctl = 'btn';
        self.act(a);
      };
      if (v.audioTracks && v.audioTracks.addEventListener) v.audioTracks.addEventListener('addtrack', function () { self.applyLangs(); });
      if (v.textTracks && v.textTracks.addEventListener) v.textTracks.addEventListener('addtrack', function () { self.applyLangs(); });
      v.addEventListener('loadedmetadata', function () {
        self.applyLangs();
        if (self.startAt > 0) {
          try { v.currentTime = self.startAt; } catch (e) { /* ignoré */ }
          self.startAt = 0;
        }
      });
      v.addEventListener('error', function () { if (self.active && v.getAttribute('src')) self.fail(); });
      v.addEventListener('ended', function () {
        if (!self.active) return;
        if (self.cur().live) self.fail();
        else if (self.nextOff) self.next(true);
        else self.next();
      });
      v.addEventListener('timeupdate', function () {
        if (!self.active || self.cur().live) return;
        if (v.currentTime > 0) self.lastPos = v.currentTime;
        self.paintProgress();
        self.checkNext();
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
    ACT: ['prev', 'back', 'play', 'fwd', 'next', 'audio', 'subs'],
    /* Zone active sous le titre : false (rien), 'bar' (barre de progression) ou 'btn' (boutons) */
    ctl: false,
    cIdx: 2,
    /* Boutons visibles / utilisables selon le contenu et la position dans la saison */
    usable: function (a) {
      if (a === 'prev') return this.list.length > 1 && this.i > 0;
      if (a === 'next') return this.list.length > 1 && this.i < this.list.length - 1;
      return true;
    },
    paintCtl: function () {
      var n = $('p-ctl').children, multi = this.list.length > 1;
      for (var k = 0; k < n.length; k++) {
        var a = this.ACT[k], el = n[k];
        cls(el, 'hide', (a === 'prev' || a === 'next') && !multi);
        cls(el, 'off', !this.usable(a));
        cls(el, 'focus', k === this.cIdx);
      }
      cls($('p-info'), 'ctl-on', this.ctl === 'btn');
      cls($('p-info'), 'bar-on', this.ctl === 'bar');
      cls($('p-info'), 'paused', this.v.paused);
    },
    moveCtl: function (d) {
      var k = this.cIdx;
      for (var step = 0; step < this.ACT.length; step++) {
        k += d;
        if (k < 0 || k >= this.ACT.length) return;
        if (this.usable(this.ACT[k])) { this.cIdx = k; break; }
      }
      this.showInfo(this.v.paused);
    },
    act: function (a) {
      if (!this.usable(a)) return;
      if (a === 'play') this.togglePause();
      else if (a === 'back') this.seek(-10);
      else if (a === 'fwd') this.seek(30);
      else if (a === 'prev') this.goTo(this.i - 1);
      else if (a === 'next') this.goTo(this.i + 1);
      else if (a === 'audio') { this.cycleAudio(); this.showInfo(this.v.paused); }
      else if (a === 'subs') { this.cycleSubs(); this.showInfo(this.v.paused); }
    },
    /* Encart « Épisode suivant » dans les 15 dernières secondes ; OK lance tout de suite, Retour annule */
    checkNext: function () {
      var v = this.v, dur = v.duration, left = dur - v.currentTime;
      if (this.nextOff || this.i >= this.list.length - 1 || !(dur > 60) || !isFinite(dur)) return;
      if (left <= 15 && left > 0) {
        if (!this.nextOn) {
          var nx = this.list[this.i + 1];
          $('pn-title').textContent = nx.title;
          this.nextOn = true;
          cls($('p-next'), 'on', true);
        }
        $('pn-left').textContent = 'dans ' + Math.max(1, Math.ceil(left)) + ' s';
      }
    },
    hideNext: function (cancelled) {
      this.nextOn = false;
      this.nextOff = !!cancelled;
      cls($('p-next'), 'on', false);
    },
    back: function () {
      if (this.nextOn) { this.hideNext(true); return true; }
      return false;
    },
    /* Épisode précédent / suivant choisi à la main : la progression de l'épisode en cours est gardée */
    goTo: function (i) {
      if (i < 0 || i >= this.list.length || i === this.i) return;
      this.saveProgress();
      this.stopVideo();
      this.i = i;
      if (!this.usable(this.ACT[this.cIdx])) this.cIdx = 2;
      this.start(true);
    },
    enter: function (p) {
      this.list = p.list;
      this.i = p.index || 0;
      this.forcedStart = typeof p.startAt === 'number' ? p.startAt : -1;
      this.prevList = null;
      this.prevI = -1;
      this.digits = '';
      this.okTimer = null;
      this.ctl = false;
      this.cIdx = 2;
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
      this.hideNext(false);
      this.ctl = false;
      cls($('p-info'), 'ctl-on', false);
      cls($('p-info'), 'bar-on', false);
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
      this.hideNext(false);
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
    /* Fin de l'épisode : marqué comme vu, puis le suivant (sauf si on a choisi de rester) */
    next: function (stay) {
      var it = this.cur();
      if (it.key) { delete P.pos[it.key]; save('pos', P.pos); Hist.remove(it.key); Seen.mark(it.key); }
      if (!stay && this.i < this.list.length - 1) { this.i++; this.start(true); }
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
        for (k = 0; k < data.items.length; k++) if (data.items[k].num > 0 && !Hidden.isHidden('live', data.items[k].cat)) all.push(data.items[k]);
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
        Seen.mark(it.key);
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
      $('p-pos').textContent = !it.live ? (this.list.length > 1 ? 'Épisode ' + (this.i + 1) + ' / ' + this.list.length : '') : it.num > 0 ? 'Chaîne ' + it.num : this.list.length > 1 ? (this.i + 1) + ' / ' + this.list.length : '';
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
      this.paintCtl();
      clearTimeout(this.timers.info);
      if (!keep) this.timers.info = setTimeout(function () { self.hideInfo(); }, 5000);
    },
    hideInfo: function () {
      clearTimeout(this.timers.info);
      cls($('p-info'), 'on', false);
      this.ctl = false;
      this.paintCtl();
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
    /* Réapplique la langue audio et les sous-titres choisis la dernière fois */
    applyLangs: function () {
      var v = this.v, a = load('alang', ''), t = load('slang', ''), i;
      var at = v.audioTracks;
      if (a && at && at.length > 1) {
        var hit = -1;
        for (i = 0; i < at.length; i++) if ((at[i].language || at[i].label) === a) hit = i;
        if (hit >= 0 && !at[hit].enabled) for (i = 0; i < at.length; i++) at[i].enabled = i === hit;
      }
      var tt = v.textTracks;
      if (t && tt && tt.length) {
        for (i = 0; i < tt.length; i++) {
          var want = t !== 'off' && (tt[i].language || tt[i].label) === t ? 'showing' : 'disabled';
          if (tt[i].mode !== want) tt[i].mode = want;
        }
      }
    },
    cycleAudio: function () {
      var tr = this.v.audioTracks;
      if (!tr || tr.length < 2) { toast('Une seule piste audio'); return; }
      var cur = 0, i;
      for (i = 0; i < tr.length; i++) if (tr[i].enabled) cur = i;
      var nx = (cur + 1) % tr.length;
      for (i = 0; i < tr.length; i++) tr[i].enabled = i === nx;
      // langue retenue pour les prochaines lectures
      save('alang', tr[nx].language || tr[nx].label || '');
      toast('Audio : ' + (tr[nx].label || tr[nx].language || 'piste ' + (nx + 1)));
    },
    cycleSubs: function () {
      var tr = this.v.textTracks;
      if (!tr || !tr.length) { toast('Aucun sous-titre'); return; }
      var cur = -1, i;
      for (i = 0; i < tr.length; i++) if (tr[i].mode === 'showing') cur = i;
      var nx = cur + 1 >= tr.length ? -1 : cur + 1;
      for (i = 0; i < tr.length; i++) tr[i].mode = i === nx ? 'showing' : 'disabled';
      save('slang', nx < 0 ? 'off' : (tr[nx].language || tr[nx].label || ''));
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
      if (this.nextOn && c === K.OK) { if (!(e && e.repeat)) this.next(); return true; }
      // Films et séries : la barre de progression est au-dessus des boutons.
      // Haut : barre (gauche/droite reculent/avancent) · bas : boutons · bas depuis les boutons : on cache.
      if (c === K.PLAY || c === K.PAUSE) {
        if (c === K.PLAY && !this.v.paused) this.showInfo();
        else if (c === K.PAUSE && this.v.paused) this.showInfo(true);
        else this.togglePause();
      } else if (c === K.OK) {
        if (e && e.repeat) return true;
        if (this.ctl === 'btn') this.act(this.ACT[this.cIdx]);
        else if (this.ctl === 'bar') this.togglePause();
        else { this.ctl = 'btn'; this.cIdx = 2; this.togglePause(); }
      } else if (c === K.LEFT || c === K.RIGHT) {
        if (this.ctl === 'btn') this.moveCtl(c === K.LEFT ? -1 : 1);
        else this.seek(c === K.LEFT ? -10 : 30);
      } else if (c === K.UP) {
        this.ctl = 'bar';
        this.showInfo(this.v.paused);
      } else if (c === K.DOWN || c === K.INFO) {
        if (this.ctl === 'btn' && c === K.DOWN) {
          if (!this.v.paused) this.hideInfo();
        } else {
          this.ctl = 'btn';
          if (!this.usable(this.ACT[this.cIdx])) this.cIdx = 2;
          this.showInfo(this.v.paused);
        }
      }
      else if (c === K.CH_UP) this.act('next');
      else if (c === K.CH_DOWN) this.act('prev');
      else if (c === K.RW) this.seek(-60);
      else if (c === K.FF) this.seek(60);
      return true;
    }
  };

  /* ---------- Clavier / télécommande ---------- */

  function isBack(c) { return c === K.BACK || c === K.ESC; }

  document.addEventListener('keydown', function (e) {
    var c = e.keyCode || e.which, t = e.target;
    var typing = t && t.tagName === 'INPUT' && document.activeElement === t;
    if (typing) {
      // droite en fin de saisie : on sort du champ (vers les résultats)
      var atEnd = c === K.RIGHT && t.selectionStart === t.value.length && t.selectionEnd === t.value.length;
      if (c === K.OK || c === K.UP || c === K.DOWN || atEnd || isBack(c)) {
        e.preventDefault();
        t.blur();
        if (cur && cur.inputDone) cur.inputDone(t, c);
      }
      return;
    }
    if (Dialog.open) { e.preventDefault(); Dialog.key(c); return; }
    if (isBack(c) || c === K.BKSP) {
      e.preventDefault();
      if (!(cur && cur.back && cur.back())) back();
      return;
    }
    if (cur && cur.key && cur.key(c, e)) e.preventDefault();
  }, true);

  document.addEventListener('keyup', function (e) {
    if (Dialog.open) { Dialog.armed = true; return; }
    if (cur && cur.keyup) cur.keyup(e.keyCode || e.which);
  }, true);

  window.addEventListener('resize', function () {
    sizeRoot();
    if (cur === screens.browse) { screens.browse.cats.redraw(); screens.browse.items.redraw(); }
    if (cur === screens.series) { screens.series.sList.redraw(); screens.series.eList.redraw(); }
  });

  Epg.onLoaded = function (id) {
    var b = screens.browse;
    if (cur === screens.guide) { screens.guide.loaded(id); return; }
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
    if (cur === screens.home && kind !== 'live') screens.home.loadRecent();
    if (cur === b && b.kind === kind && !b.query && b.zone !== 'retry') {
      Lists.get(kind, function (err, data) { if (!err) b.build(data.cats, data.items, true); });
    }
  };

  /* ---------- Démarrage ---------- */

  sizeRoot();
  for (var name in screens) if (screens.hasOwnProperty(name) && screens[name].init) screens[name].init();
  reset(loggedIn() ? 'home' : 'login');

  window.KoalaTV = { version: VERSION, P: P, X: X, Lists: Lists, Hidden: Hidden, go: go, Sync: Sync, screens: screens, K: K };
})();
