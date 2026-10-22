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
