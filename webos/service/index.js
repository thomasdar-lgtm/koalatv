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
