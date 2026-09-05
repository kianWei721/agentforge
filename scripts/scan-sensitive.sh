#!/usr/bin/env bash
# Sensitive-data scan for public release.
#
# Unlike a secret scanner, this looks for *non-secret business metadata* that is
# still unsuitable for disclosure: private IPs, internal hostnames, and known
# proprietary endpoint markers.
#
# Usage:
#   scripts/scan-sensitive.sh            # scan the working tree
#   scripts/scan-sensitive.sh --history  # scan every reachable commit
#
# Exits non-zero on the first category that matches.

set -euo pipefail

MODE="${1:-tree}"

# RFC1918 private ranges + link-local. Deliberately generic: the previous
# hardcoded single-IP pattern let 192.168.30.103 through for 24 commits.
PRIVATE_IP='\b(10\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}|172\.(1[6-9]|2[0-9]|3[01])\.[0-9]{1,3}\.[0-9]{1,3}|192\.168\.[0-9]{1,3}\.[0-9]{1,3}|169\.254\.[0-9]{1,3}\.[0-9]{1,3})\b'

# Internal-only TLDs. `.internal` is allowed only under the example.internal
# documentation domain, which RFC 2606 reserves for exactly this purpose.
INTERNAL_HOST='\b[a-zA-Z0-9-]+\.(corp|intra|lan)\b'

# Known proprietary markers from the original integration target.
PROPRIETARY='60\.12\.4\.180|/admin-api/device/web|[Yy]udao'

EXCLUDES=(':!PUBLIC_RELEASE.md' ':!scripts/scan-sensitive.sh' ':!.github/workflows/ci.yml')

fail=0

scan() {
  local label="$1" pattern="$2"
  local hits
  if [ "$MODE" = "--history" ]; then
    # git grep needs explicit revisions; working-tree grep does not see history.
    hits=$(git grep -nIE "$pattern" $(git rev-list --all) -- "${EXCLUDES[@]}" 2>/dev/null || true)
  else
    hits=$(git grep -nIE "$pattern" -- "${EXCLUDES[@]}" 2>/dev/null || true)
  fi
  # example.internal / example.com are documentation placeholders, not leaks.
  hits=$(printf '%s' "$hits" | grep -vE 'example\.(internal|com|org)' || true)
  if [ -n "$hits" ]; then
    echo "FAIL [$label]"
    printf '%s\n' "$hits" | head -40
    echo
    fail=1
  else
    echo "ok   [$label]"
  fi
}

echo "Sensitive-data scan (mode: $MODE)"
echo
scan "private IP"        "$PRIVATE_IP"
scan "internal hostname" "$INTERNAL_HOST"
scan "proprietary marker" "$PROPRIETARY"

if [ "$fail" -ne 0 ]; then
  echo "Scan failed. Do not publish."
  exit 1
fi
echo
echo "Scan passed."
