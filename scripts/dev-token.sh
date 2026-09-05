#!/usr/bin/env bash
# Mint a short-lived HS256 token for local development.
#
# Only for the shared-secret mode (agentforge.security.jwt.shared-secret).
# Deployments backed by an identity provider get their tokens from that provider.
#
# Usage:
#   scripts/dev-token.sh <userId> [role...]
#   AGENTFORGE_SECURITY_JWT_SHARED_SECRET=... scripts/dev-token.sh alice ADMIN

set -euo pipefail

USER_ID="${1:-}"
if [ -z "$USER_ID" ]; then
  echo "usage: $0 <userId> [role...]" >&2
  exit 2
fi
shift || true

SECRET="${AGENTFORGE_SECURITY_JWT_SHARED_SECRET:-}"
if [ -z "$SECRET" ] && [ -f .env.local ]; then
  SECRET=$(grep -E '^AGENTFORGE_SECURITY_JWT_SHARED_SECRET=' .env.local | cut -d= -f2- || true)
fi
if [ -z "$SECRET" ]; then
  echo "AGENTFORGE_SECURITY_JWT_SHARED_SECRET is not set (and not found in .env.local)." >&2
  exit 1
fi

# base64url without padding, as JWS requires.
b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

roles_json="[]"
if [ "$#" -gt 0 ]; then
  roles_json=$(printf '"%s",' "$@" | sed 's/,$//')
  roles_json="[$roles_json]"
fi

now=$(date +%s)
exp=$((now + 3600))

header=$(printf '{"alg":"HS256","typ":"JWT"}' | b64url)
payload=$(printf '{"sub":"%s","roles":%s,"iat":%s,"exp":%s}' \
  "$USER_ID" "$roles_json" "$now" "$exp" | b64url)

signature=$(printf '%s.%s' "$header" "$payload" \
  | openssl dgst -sha256 -mac HMAC -macopt "key:$SECRET" -binary \
  | b64url)

printf '%s.%s.%s\n' "$header" "$payload" "$signature"
