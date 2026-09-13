#!/bin/bash
# Stealth check: scans release artifacts for identifying strings / dev markers.
# Usage: ./stealth_check.sh [files...]   (defaults to release/ artifacts)
set -uo pipefail

# FATAL: must never ship in a release by default.
FATAL=(
  '/var/home/' '/home/' 'Heterodoxin' 'dexadmin'
  'PEACE BOOT'
  'TODO' 'FIXME' 'HACK'
  'github.com/heterodoxin'
  'com\.heterodoxin'
)

# WARN: present by design, but report where they live.
WARN=(
  'peace:diag'
  'peace-injector-default'
  'dev\.peace\.plugin'
  'dev\.peace\.mod'
  'dev\.peace\.inject'
  'peace:main'
  'spread\.inject' 'spread\.status' 'spread\.unregister'
  'admin\.shutdown' 'admin\.destroy'
  'qolclient'
  'PEACE'
  'Injector'
)

if [[ $# -eq 0 ]]; then
  cd "$(dirname "$0")"
  files=(release/*.jar release/*.zip)
else
  files=("$@")
fi

declare -i fatal=0 warn=0

scan_zip() {
  local f
  f="$(realpath "$1")"
  [[ -f "$f" ]] || { echo "missing: $f"; return; }
  local tmp; tmp="$(mktemp -d)"
  ( cd "$tmp" && jar xf "$f" 2>/dev/null ) || { echo "extract failed: $f"; return; }
  local fe
  while IFS= read -r -d '' fe; do
    local rel=${fe#"$tmp"/}
    local hits
    if [[ "$rel" == *.class ]]; then
      hits="$(strings -a "$fe" 2>/dev/null)"
    else
      hits="$(tr -d '\000' < "$fe" 2>/dev/null)"
    fi
    [[ -z "$hits" ]] && continue
    for p in "${FATAL[@]}"; do
      if grep -aEq "$p" <<<"$hits"; then
        echo "FATAL $f :: $rel :: matches /$p/ (first: $(grep -aoE "$p" <<<"$hits" | head -1))"
        fatal+=1
      fi
    done
    for p in "${WARN[@]}"; do
      if grep -aEq "$p" <<<"$hits"; then
        echo "WARN  $f :: $rel :: matches /$p/"
        warn+=1
      fi
    done
  done < <(find "$tmp" -type f -print0)
  rm -rf "$tmp"
}

scan_plain() {
  local fe
  if [[ "$1" == *.class ]]; then
    fe="$(mktemp)"; strings -a "$1" > "$fe"
  else
    fe="$1"
  fi
  for p in "${FATAL[@]}"; do
    if grep -aEq "$p" "$fe"; then echo "FATAL $1 :: /$p/"; fatal+=1; fi
  done
  for p in "${WARN[@]}"; do
    if grep -aEq "$p" "$fe"; then echo "WARN  $1 :: /$p/"; warn+=1; fi
  done
  [[ "$1" == *.class ]] && rm -f "$fe"
}

for f in "${files[@]}"; do
  [[ -f "$f" ]] || { echo "missing: $f"; exit 2; }
  echo "== $f =="
  if unzip -l "$f" >/dev/null 2>&1; then
    scan_zip "$f"
  else
    scan_plain "$f"
  fi
done

echo ""
echo "summary: $fatal FATAL, $warn WARN"
if (( fatal > 0 )); then echo "FAIL: $fatal FATAL identifiers found in release artifacts."; exit 1; fi
echo "PASS"
exit 0