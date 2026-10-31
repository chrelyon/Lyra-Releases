#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODULE_GRADLE="$ROOT/innertube/build.gradle.kts"
REPOSITORY="MetrolistGroup/innertubex"
REPO_URL="https://github.com/$REPOSITORY"

if [[ ! -f "$MODULE_GRADLE" ]]; then
  echo "File non trovato: $MODULE_GRADLE" >&2
  exit 1
fi

CURRENT="$(sed -nE 's/.*com\.github\.MetrolistGroup\.innertubex:innertubex:([^\"]+).*/\1/p' "$MODULE_GRADLE" | head -n1)"
if [[ -z "$CURRENT" ]]; then
  echo "Impossibile rilevare la versione InnerTubeX" >&2
  exit 1
fi

python3 - "$CURRENT" "$REPOSITORY" "$REPO_URL" <<'PY'
import json, re, sys, urllib.request
current, repo, repo_url = sys.argv[1:]

def semver(s):
    s=s.lstrip('v')
    if not re.fullmatch(r'\d+(?:\.\d+){1,3}', s):
        return None
    return tuple(int(x) for x in s.split('.'))

headers={
    'Accept':'application/vnd.github+json',
    'User-Agent':'Lyra-InnerTubeX-Update-Checker',
    'X-GitHub-Api-Version':'2022-11-28',
}

def get(url):
    req=urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=15) as r:
        return json.load(r)

latest=None; source=None
try:
    rel=get(f'https://api.github.com/repos/{repo}/releases/latest')
    tag=rel.get('tag_name')
    if tag and semver(tag): latest=tag; source='GitHub latest release'
except Exception:
    pass
if latest is None:
    tags=get(f'https://api.github.com/repos/{repo}/tags?per_page=100')
    vals=[(semver(t.get('name','')), t.get('name','')) for t in tags]
    vals=[v for v in vals if v[0] is not None]
    if vals:
        _, latest=max(vals)
        source='GitHub tags'

print('\nInnerTubeX - controllo aggiornamenti')
print(f'Repository : {repo_url}')
print(f'Installata  : {current}')
if latest is None:
    print('Disponibile : non determinabile')
    sys.exit(0)
print(f'Disponibile : {latest} ({source})')
c=semver(current); l=semver(latest)
if l>c:
    print('STATO       : AGGIORNAMENTO DISPONIBILE')
    print('Non aggiornare automaticamente: leggere changelog/tag, usare un branch dedicato e rifare la regressione completa.')
    print(f'Tag upstream: {repo_url}/releases/tag/{latest}')
elif l==c:
    print('STATO       : aggiornata')
else:
    print("STATO       : la versione locale e' piu' recente del tag rilevato")
PY
