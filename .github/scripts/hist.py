"""Dépannage « Reprendre » : entrées d'historique d'une série (titres et états seulement)."""
import json
import sys
import urllib.request

db, needle = sys.argv[1], sys.argv[2].lower()
keys = json.load(urllib.request.urlopen(db + '/h.json?shallow=true', timeout=30)) or {}
for k in keys:
    if k.startswith('p') or k.startswith('dbg'):
        continue
    try:
        e = json.load(urllib.request.urlopen(db + '/h/' + k + '/e.json', timeout=30)) or {}
    except Exception as ex:
        print(k[:6], 'erreur', ex)
        continue
    rows = [(v.get('at', 0), ek, v) for ek, v in e.items() if isinstance(v, dict) and needle in (str(v.get('sn', '')) + str(v.get('t', ''))).lower()]
    for at, ek, v in sorted(rows):
        print(k[:6], ek, at, 'gone' if v.get('gone') else '', 'f=%s' % v.get('f'), v.get('t'), 'sid=%s' % v.get('sid'), 'p=%s d=%s' % (v.get('p'), v.get('d')))
