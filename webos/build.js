// Construit l'IPK webOS et le dépôt Homebrew Channel dans docs/.
// Usage : node webos/build.js <chemin vers ares-package>
var fs = require('fs'), path = require('path'), crypto = require('crypto'), cp = require('child_process');

var ROOT = path.join(__dirname, '..');
var DOCS = path.join(ROOT, 'docs');
var BASE = 'https://thomasdar-lgtm.github.io/koalatv/';
var ares = process.argv[2] || 'ares-package';

var info = JSON.parse(fs.readFileSync(path.join(__dirname, 'app', 'appinfo.json'), 'utf8'));
var appVersion = /var VERSION = '([^']+)'/.exec(fs.readFileSync(path.join(__dirname, 'app', 'app.js'), 'utf8'))[1];
var svcVersion = JSON.parse(fs.readFileSync(path.join(__dirname, 'service', 'package.json'), 'utf8')).version;
if (appVersion !== info.version || svcVersion !== info.version) {
  console.error('Versions différentes : appinfo ' + info.version + ', app.js ' + appVersion + ', service ' + svcVersion);
  process.exit(1);
}

var tmp = fs.mkdtempSync(path.join(require('os').tmpdir(), 'ktv-'));
cp.execFileSync(ares, [path.join(__dirname, 'app'), path.join(__dirname, 'service'), '-o', tmp], { stdio: 'inherit' });
var built = path.join(tmp, info.id + '_' + info.version + '_all.ipk');

fs.mkdirSync(DOCS, { recursive: true });
fs.readdirSync(DOCS).forEach(function (f) { if (/\.ipk$/.test(f)) fs.unlinkSync(path.join(DOCS, f)); });
var ipkName = 'KoalaTV-' + info.version + '.ipk';
fs.copyFileSync(built, path.join(DOCS, ipkName));

var buf = fs.readFileSync(path.join(DOCS, ipkName));
var manifest = {
  id: info.id,
  version: info.version,
  type: info.type,
  title: info.title,
  appDescription: 'Lecteur IPTV pour abonnements Xtream',
  iconUri: BASE + 'icon.png',
  sourceUrl: 'https://github.com/thomasdar-lgtm/koalatv',
  rootRequired: false,
  ipkUrl: BASE + ipkName,
  ipkSize: buf.length,
  ipkHash: { sha256: crypto.createHash('sha256').update(buf).digest('hex') }
};
fs.writeFileSync(path.join(DOCS, 'manifest.json'), JSON.stringify(manifest, null, 2) + '\n');
fs.writeFileSync(path.join(DOCS, 'apps.json'), JSON.stringify({
  paging: { page: 1, count: 1, maxPage: 1, itemsTotal: 1 },
  packages: [{
    id: info.id,
    title: info.title,
    iconUri: BASE + 'icon.png',
    manifestUrl: BASE + 'manifest.json',
    manifest: manifest,
    shortDescription: 'Lecteur IPTV pour abonnements Xtream',
    category: 'multimedia',
    pool: 'main'
  }]
}, null, 2) + '\n');
fs.writeFileSync(path.join(DOCS, '.nojekyll'), '');
fs.writeFileSync(path.join(DOCS, 'index.html'),
  '<!doctype html><meta charset="utf-8"><title>KoalaTV</title><p>Dépôt Homebrew Channel de KoalaTV : <code>' +
  BASE + 'apps.json</code></p>\n');
console.log('OK', ipkName, manifest.ipkSize, 'octets', manifest.ipkHash.sha256);
