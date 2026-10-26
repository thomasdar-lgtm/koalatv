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
 */
var MKV = { SEGMENT: 0x18538067, TRACKS: 0x1654AE6B, ENTRY: 0xAE, CLUSTER: 0x1F43B675,
  TYPE: 0x83, LANG: 0x22B59C, BCP47: 0x22B59D, NAME: 0x536E, CODEC: 0x86, FORCED: 0x55AA, DEFAULT: 0x88 };

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

function parseTracks(buf, a, b) {
  var out = [], pos = a;
  while (pos < b) {
    var id = vint(buf, pos, true); if (!id) break;
    var sz = vint(buf, pos + id.len, false); if (!sz) break;
    var s = pos + id.len + sz.len, e = Math.min(b, s + sz.val);
    if (id.val === MKV.ENTRY) {
      var t = { type: 0, lang: 'eng', name: '', codec: '', forced: false, def: true }, p = s;
      while (p < e) {
        var cid = vint(buf, p, true); if (!cid) break;
        var csz = vint(buf, p + cid.len, false); if (!csz) break;
        var cs = p + cid.len + csz.len, ce = Math.min(e, cs + csz.val);
        if (cid.val === MKV.TYPE) t.type = uint(buf, cs, ce);
        else if (cid.val === MKV.LANG) t.lang = buf.toString('utf8', cs, ce).replace(/\0+$/, '');
        else if (cid.val === MKV.BCP47) t.bcp = buf.toString('utf8', cs, ce).replace(/\0+$/, '');
        else if (cid.val === MKV.NAME) t.name = buf.toString('utf8', cs, ce).replace(/\0+$/, '');
        else if (cid.val === MKV.CODEC) t.codec = buf.toString('utf8', cs, ce).replace(/\0+$/, '');
        else if (cid.val === MKV.FORCED) t.forced = uint(buf, cs, ce) === 1;
        else if (cid.val === MKV.DEFAULT) t.def = uint(buf, cs, ce) === 1;
        p = ce;
      }
      if (t.bcp) t.lang = t.bcp;
      out.push(t);
    }
    pos = e;
  }
  return out;
}

/* Renvoie les pistes si la section a été trouvée, null s'il faut plus de données, [] si absente */
function findTracks(buf) {
  var pos = 0;
  while (pos < buf.length) {
    var id = vint(buf, pos, true); if (!id) return null;
    var sz = vint(buf, pos + id.len, false); if (!sz) return null;
    var s = pos + id.len + sz.len;
    if (id.val === MKV.SEGMENT) { pos = s; continue; }          // on entre dans le segment
    if (id.val === MKV.CLUSTER) return [];                        // les images commencent : pas de section
    if (id.val === MKV.TRACKS) {
      if (sz.unknown || s + sz.val > buf.length) return null;
      return parseTracks(buf, s, s + sz.val);
    }
    if (sz.unknown) return [];
    pos = s + sz.val;
  }
  return null;
}

service.register('probe', function (message) {
  var p = message.payload || {}, url = p.url, max = p.maxBytes || 4 * 1024 * 1024, done = false, req = null;
  if (!url || !/^https?:\/\//i.test(url)) { message.respond({ returnValue: false, errorText: 'Adresse invalide' }); return; }
  var timer = setTimeout(function () { finish(new Error('Délai dépassé')); }, (p.seconds || 6) * 1000);
  function finish(err, tracks) {
    if (done) return;
    done = true;
    clearTimeout(timer);
    try { if (req) req.abort(); } catch (e) { /* déjà fermé */ }
    if (err) message.respond({ returnValue: false, errorText: err.message });
    else message.respond({ returnValue: true, tracks: tracks });
  }
  function open(u, redirects) {
    var o = urlLib.parse(u), lib = o.protocol === 'https:' ? https : http;
    req = lib.request({
      hostname: o.hostname, port: o.port, path: o.path, method: 'GET',
      headers: { 'User-Agent': UA, 'Accept': '*/*', 'Range': 'bytes=0-' + (max - 1) }, rejectUnauthorized: false
    }, function (res) {
      var code = res.statusCode;
      if (code >= 300 && code < 400 && res.headers.location && redirects < 5) {
        res.resume();
        open(urlLib.resolve(u, res.headers.location), redirects + 1);
        return;
      }
      if (code < 200 || code >= 300) { res.resume(); finish(new Error('Erreur du serveur (' + code + ')')); return; }
      var parts = [], got = 0, lastTry = 0;
      res.on('data', function (d) {
        parts.push(d); got += d.length;
        if (got - lastTry < 65536 && got < max) return;
        lastTry = got;
        var buf = Buffer.concat(parts);
        if (buf.length >= 4 && buf.readUInt32BE(0) !== 0x1A45DFA3) { finish(null, []); return; }
        var t = findTracks(buf);
        if (t) finish(null, t);
        else if (got >= max) finish(null, []);
      });
      res.on('end', function () { var t = findTracks(Buffer.concat(parts)); finish(null, t || []); });
      res.on('error', function (e) { finish(e); });
    });
    req.on('error', function () { finish(new Error('Serveur injoignable')); });
    req.end();
  }
  open(url, 0);
});

module.exports = { findTracks: findTracks };
