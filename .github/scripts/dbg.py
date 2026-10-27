"""Affiche les derniers journaux techniques des TV (dépannage, sans identifiants)."""
import json
import sys

d = json.load(sys.stdin) or {}
for k, v in sorted(d.items(), key=lambda kv: kv[1].get('at', 0))[-4:]:
    print('=====', k, v.get('v'), v.get('ua'))
    print('\n'.join(v.get('l', [])))
# relevé 1793104106
