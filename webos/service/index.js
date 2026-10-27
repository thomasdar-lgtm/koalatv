/* Service KoalaTV : télécharge les listes Xtream côté TV (aucune restriction CORS). ES5 pour Node 0.12+. */
var Service = require('webos-service');
var http = require('http');
var https = require('https');
var urlLib = require('url');

var service = new Service('com.koala.tv.service');
var CHUNK = 256 * 1024;
var UA = 'Mozilla/5.0 (Web0S; Linux/SmartTV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/79.0 Safari/537.36';

function get(url, redirects, cb) {
  var done = false;
  function finish(err, body) {
    if (done) return;
    done = true;
    cb(err, body);
  }
  var o = urlLib.parse(url);
  var lib = o.protocol === 'https:' ? https : http;
  var req = lib.request({
    hostname: o.hostname,
    port: o.port,
    path: o.path,
    method: 'GET',
    headers: { 'User-Agent': UA, 'Accept': '*/*' },
    rejectUnauthorized: false
  }, function (res) {
    var code = res.statusCode;
    if (code >= 300 && code < 400 && res.headers.location && redirects < 5) {
      res.resume();
      done = true;
      get(urlLib.resolve(url, res.headers.location), redirects + 1, cb);
      return;
    }
    if (code < 200 || code >= 300) {
      res.resume();
      finish(new Error('Erreur du serveur (' + code + ')'));
      return;
    }
    var parts = [];
    res.on('data', function (d) { parts.push(d); });
    res.on('end', function () { finish(null, Buffer.concat(parts).toString('utf8')); });
    res.on('error', function (e) { finish(e); });
  });
  req.on('error', function () { finish(new Error('Serveur injoignable')); });
  req.setTimeout(60000, function () {
    req.abort();
    finish(new Error('Le serveur ne répond pas'));
  });
  req.end();
}

service.register('fetch', function (message) {
  var url = message.payload && message.payload.url;
  if (!url || !/^https?:\/\//i.test(url)) {
    message.respond({ returnValue: false, errorText: 'Adresse invalide' });
    return;
  }
  get(url, 0, function (err, body) {
    if (err) {
      message.respond({ returnValue: false, errorText: err.message });
      return;
    }
    var total = Math.max(1, Math.ceil(body.length / CHUNK));
    for (var i = 0; i < total; i++) {
      message.respond({
        returnValue: true,
        index: i,
        total: total,
        chunk: body.substr(i * CHUNK, CHUNK),
        done: i === total - 1
      });
    }
  });
});

/*
 * Mesure de débit : télécharge l'adresse pendant `seconds` secondes au plus (ou `maxBytes` octets)
 * et renvoie le temps jusqu'au premier octet, les octets reçus et la durée de réception.
 */
service.register('speed', function (message) {
  var p = message.payload || {}, url = p.url, seconds = Math.min(15, p.seconds || 6), maxBytes = p.maxBytes || 60000000;
  if (!url || !/^https?:\/\//i.test(url)) {
    message.respond({ returnValue: false, errorText: 'Adresse invalide' });
    return;
  }
  var t0 = Date.now(), tFirst = 0, bytes = 0, done = false, req = null, timer = null;
  function finish(err) {
    if (done) return;
    done = true;
    clearTimeout(timer);
    try { if (req) req.abort(); } catch (e) { /* déjà fermé */ }
    if (err && !bytes) { message.respond({ returnValue: false, errorText: err.message }); return; }
    message.respond({ returnValue: true, ttfb: tFirst ? tFirst - t0 : -1, bytes: bytes, ms: tFirst ? Date.now() - tFirst : 0 });
  }
  function open(u, redirects) {
    var o = urlLib.parse(u), lib = o.protocol === 'https:' ? https : http;
    req = lib.request({
      hostname: o.hostname, port: o.port, path: o.path, method: 'GET',
      headers: { 'User-Agent': UA, 'Accept': '*/*' }, rejectUnauthorized: false
    }, function (res) {
      var code = res.statusCode;
      if (code >= 300 && code < 400 && res.headers.location && redirects < 5) {
        res.resume();
        open(urlLib.resolve(u, res.headers.location), redirects + 1);
        return;
      }
      if (code < 200 || code >= 300) { res.resume(); finish(new Error('Erreur du serveur (' + code + ')')); return; }
      res.on('data', function (d) {
        if (!tFirst) tFirst = Date.now();
        bytes += d.length;
        if (bytes >= maxBytes) finish(null);
      });
      res.on('end', function () { finish(null); });
      res.on('error', function (e) { finish(e); });
    });
    req.on('error', function () { finish(new Error('Serveur injoignable')); });
    req.end();
  }
  timer = setTimeout(function () { finish(tFirst ? null : new Error('Le serveur ne répond pas')); }, (seconds + 8) * 1000);
  open(url, 0);
  // fin de la mesure : `seconds` secondes après le premier octet
  var watch = setInterval(function () {
    if (done) { clearInterval(watch); return; }
    if (tFirst && Date.now() - tFirst >= seconds * 1000) { clearInterval(watch); finish(null); }
  }, 100);
});

/*
 * Pistes d'un fichier MKV : la TV ne donne pas la liste des sous-titres intégrés,
 * on lit donc l'en-tête du fichier (les premiers Mo) pour trouver la section « Tracks ».
 * Pour reconnaître des sous-titres « forcés » mal nommés, on compte aussi les sous-titres de chaque piste
 * (statistiques « NUMBER_OF_FRAMES » ou index « Cues », situés en fin de fichier).
 */
var MKV = { SEGMENT: 0x18538067, SEEKHEAD: 0x114D9B74, SEEK: 0x4DBB, SEEKID: 0x53AB, SEEKPOS: 0x53AC,
  INFO: 0x1549A966, SCALE: 0x2AD7B1, DURATION: 0x4489, TRACKS: 0x1654AE6B, ENTRY: 0xAE, CLUSTER: 0x1F43B675,
  NUM: 0xD7, UID: 0x73C5, TYPE: 0x83, LANG: 0x22B59C, BCP47: 0x22B59D, NAME: 0x536E, CODEC: 0x86, FORCED: 0x55AA, DEFAULT: 0x88,
  TAGS: 0x1254C367, TAG: 0x7373, TARGETS: 0x63C0, TAGTRACK: 0x63C5, SIMPLE: 0x67C8, TAGNAME: 0x45A3, TAGSTR: 0x4487,
  CUES: 0x1C53BB6B, CUEPOINT: 0xBB, CUEPOS: 0xB7, CUETRACK: 0xF7 };

function vint(buf, pos, keepMarker) {
  if (pos >= buf.length) return null;
  var b = buf[pos], len = 1, mask = 0x80;
  while (len <= 8 && !(b & mask)) { len++; mask >>= 1; }
  if (len > 8 || pos + len > buf.length) return null;
  var val = keepMarker ? b : (b & (mask - 1)), allOnes = (b & (mask - 1)) === mask - 1;
  for (var i = 1; i < len; i++) { val = val * 256 + buf[pos + i]; if (buf[pos + i] !== 0xFF) allOnes = false; }
  return { val: val, len: len, unknown: !keepMarker && allOnes };
}
function uint(buf, a, b) { var v = 0; for (var i = a; i < b; i++) v = v * 256 + buf[i]; return v; }
function txt(buf, a, b) { return buf.toString('utf8', a, b).replace(/\0+$/, ''); }
function flt(buf, a, b) { return b - a === 4 ? buf.readFloatBE(a) : b - a === 8 ? buf.readDoubleBE(a) : 0; }

/* Parcourt les éléments enfants de [a, b) : fn(id, début, fin) ; s'arrête si fn renvoie true */
function each(buf, a, b, fn) {
  var pos = a;
  while (pos < b) {
    var id = vint(buf, pos, true); if (!id) return;
    var sz = vint(buf, pos + id.len, false); if (!sz) return;
    var s = pos + id.len + sz.len, e = sz.unknown ? b : Math.min(b, s + sz.val);
    if (fn(id.val, s, e, sz)) return;
    pos = e;
  }
}

function parseTracks(buf, a, b) {
  var out = [];
  each(buf, a, b, function (id, s, e) {
    if (id !== MKV.ENTRY) return;
    var t = { type: 0, num: 0, uid: '', lang: 'eng', name: '', codec: '', forced: false, def: true };
    each(buf, s, e, function (cid, cs, ce) {
      if (cid === MKV.TYPE) t.type = uint(buf, cs, ce);
      else if (cid === MKV.NUM) t.num = uint(buf, cs, ce);
      else if (cid === MKV.UID) t.uid = buf.toString('hex', cs, ce);
      else if (cid === MKV.LANG) t.lang = txt(buf, cs, ce);
      else if (cid === MKV.BCP47) t.bcp = txt(buf, cs, ce);
      else if (cid === MKV.NAME) t.name = txt(buf, cs, ce);
      else if (cid === MKV.CODEC) t.codec = txt(buf, cs, ce);
      else if (cid === MKV.FORCED) t.forced = uint(buf, cs, ce) === 1;
      else if (cid === MKV.DEFAULT) t.def = uint(buf, cs, ce) === 1;
    });
    if (t.bcp) t.lang = t.bcp;
    out.push(t);
  });
  return out;
}

/*
 * En-tête : renvoie { tracks, seg (début des données du segment), seek: {id: position}, dur (s) },
 * null s'il faut plus de données, { tracks: [] } si ce n'est pas un MKV exploitable.
 */
function readHeader(buf) {
  var pos = 0, h = { tracks: null, seg: -1, seek: {}, dur: 0 }, scale = 1000000, rawDur = 0, more = false;
  while (pos < buf.length) {
    var id = vint(buf, pos, true); if (!id) return null;
    var sz = vint(buf, pos + id.len, false); if (!sz) return null;
    var s = pos + id.len + sz.len;
    if (id.val === MKV.SEGMENT) { h.seg = s; pos = s; continue; }          // on entre dans le segment
    if (id.val === MKV.CLUSTER) break;                                      // les images commencent
    if (!sz.unknown && s + sz.val > buf.length) { more = true; break; }
    if (id.val === MKV.SEEKHEAD) {
      each(buf, s, s + sz.val, function (sid, ss, se) {
        if (sid !== MKV.SEEK) return;
        var target = 0, at = -1;
        each(buf, ss, se, function (k, ks, ke) { if (k === MKV.SEEKID) target = uint(buf, ks, ke); else if (k === MKV.SEEKPOS) at = uint(buf, ks, ke); });
        if (target && at >= 0 && h.seek[target] === undefined) h.seek[target] = at;
      });
    } else if (id.val === MKV.INFO) {
      each(buf, s, s + sz.val, function (k, ks, ke) { if (k === MKV.SCALE) scale = uint(buf, ks, ke); else if (k === MKV.DURATION) rawDur = flt(buf, ks, ke); });
    } else if (id.val === MKV.TRACKS) {
      h.tracks = parseTracks(buf, s, s + sz.val);
    }
    if (sz.unknown) break;
    pos = s + sz.val;
  }
  h.dur = rawDur * scale / 1e9;
  if (!h.tracks) return more ? null : { tracks: [], seg: -1, seek: {}, dur: 0 };
  return h;
}

/* Statistiques : nombre de sous-titres par piste (identifiant unique → nombre) */
function readTags(buf) {
  var out = {}, found = false;
  each(buf, 0, buf.length, function (id, s, e) {
    if (id !== MKV.TAGS) return;
    each(buf, s, e, function (tid, ts, te) {
      if (tid !== MKV.TAG) return;
      var uid = '', frames = -1;
      each(buf, ts, te, function (k, ks, ke) {
        if (k === MKV.TARGETS) each(buf, ks, ke, function (x, xs, xe) { if (x === MKV.TAGTRACK) uid = buf.toString('hex', xs, xe); });
        else if (k === MKV.SIMPLE) {
          var name = '', val = '';
          each(buf, ks, ke, function (x, xs, xe) { if (x === MKV.TAGNAME) name = txt(buf, xs, xe); else if (x === MKV.TAGSTR) val = txt(buf, xs, xe); });
          if (name === 'NUMBER_OF_FRAMES') frames = parseInt(val, 10);
        }
      });
      if (uid && frames >= 0) { out[uid] = frames; found = true; }
    });
    return true;
  });
  return found ? out : null;
}

/* Index : nombre d'entrées par numéro de piste */
function readCues(buf) {
  var out = {}, found = false;
  each(buf, 0, buf.length, function (id, s, e) {
    if (id !== MKV.CUES) return;
    each(buf, s, e, function (cid, cs, ce) {
      if (cid !== MKV.CUEPOINT) return;
      each(buf, cs, ce, function (k, ks, ke) {
        if (k !== MKV.CUEPOS) return;
        each(buf, ks, ke, function (x, xs, xe) { if (x === MKV.CUETRACK) { var n = uint(buf, xs, xe); out[n] = (out[n] || 0) + 1; found = true; } });
      });
    });
    return true;
  });
  return found ? out : null;
}

/* Compat. tests : liste des pistes d'un tampon d'en-tête */
function findTracks(buf) { var h = readHeader(buf); return h ? h.tracks : null; }

service.register('probe', function (message) {
  var p = message.payload || {}, url = p.url, max = p.maxBytes || 4 * 1024 * 1024, done = false, req = null;
  if (!url || !/^https?:\/\//i.test(url)) { message.respond({ returnValue: false, errorText: 'Adresse invalide' }); return; }
  var timer = setTimeout(function () { finish(new Error('Délai dépassé')); }, (p.seconds || 6) * 1000);
  var head = null, finalUrl = url;
  function finish(err, tracks) {
    if (done) return;
    done = true;
    clearTimeout(timer);
    try { if (req) req.abort(); } catch (e) { /* déjà fermé */ }
    if (err && !head) message.respond({ returnValue: false, errorText: err.message });
    else message.respond({ returnValue: true, tracks: tracks || (head ? head.tracks : []), dur: head ? head.dur : 0 });
  }
  /* Téléchargement d'une plage d'octets ; onData(tampon, fini) renvoie true pour arrêter */
  function range(u, from, len, redirects, onData) {
    var o = urlLib.parse(u), lib = o.protocol === 'https:' ? https : http, mine;
    mine = req = lib.request({
      hostname: o.hostname, port: o.port, path: o.path, method: 'GET',
      headers: { 'User-Agent': UA, 'Accept': '*/*', 'Range': 'bytes=' + from + '-' + (from + len - 1) }, rejectUnauthorized: false
    }, function (res) {
      var code = res.statusCode;
      if (code >= 300 && code < 400 && res.headers.location && redirects < 5) {
        res.resume();
        finalUrl = urlLib.resolve(u, res.headers.location);
        range(finalUrl, from, len, redirects + 1, onData);
        return;
      }
      // une plage hors début doit être honorée (206), sinon on ne lit pas tout le fichier
      if (code < 200 || code >= 300 || (from > 0 && code !== 206)) { res.resume(); onData(null, true); return; }
      var parts = [], got = 0, lastTry = 0, stop = false;
      res.on('data', function (d) {
        if (stop) return;
        parts.push(d); got += d.length;
        if (got - lastTry < 65536 && got < len) return;
        lastTry = got;
        if (onData(Buffer.concat(parts), got >= len)) { stop = true; try { mine.abort(); } catch (e) { /* fermé */ } }
      });
      res.on('end', function () { if (!stop) { stop = true; onData(Buffer.concat(parts), true); } });
      res.on('error', function () { if (!stop) { stop = true; onData(null, true); } });
    });
    var failed = false;
    mine.on('error', function () { if (!failed) { failed = true; onData(null, true); } });
    mine.end();
  }
  /* Nombre de sous-titres par piste : statistiques, sinon index */
  function count(kind) {
    var id = kind === 'tags' ? MKV.TAGS : MKV.CUES, rel = head.seek[id];
    if (rel === undefined || head.seg < 0) return kind === 'tags' ? count('cues') : finish(null);
    range(finalUrl, head.seg + rel, kind === 'tags' ? 512 * 1024 : 4 * 1024 * 1024, 0, function (buf, end) {
      var r = buf && (kind === 'tags' ? readTags(buf) : readCues(buf));
      if (!r && !end) return false;
      var i, t, n = 0;
      if (r) for (i = 0; i < head.tracks.length; i++) {
        t = head.tracks[i];
        if (t.type !== 17) continue;
        var v = kind === 'tags' ? r[t.uid] : r[t.num];
        if (v !== undefined) { t.frames = v; n++; }
      }
      if (!n && kind === 'tags') count('cues');
      else finish(null);
      return true;
    });
  }
  range(url, 0, max, 0, function (buf, end) {
    if (!buf) { finish(new Error('Serveur injoignable')); return true; }
    if (buf.length >= 4 && buf.readUInt32BE(0) !== 0x1A45DFA3) { finish(null, []); return true; }
    var h = readHeader(buf);
    if (!h && !end) return false;
    head = h || { tracks: [], seg: -1, seek: {}, dur: 0 };
    var subs = 0;
    for (var i = 0; i < head.tracks.length; i++) if (head.tracks[i].type === 17) subs++;
    if (subs) setTimeout(function () { count('tags'); }, 0); else finish(null);
    return true;
  });
});

module.exports = { findTracks: findTracks, readHeader: readHeader, readTags: readTags, readCues: readCues };
