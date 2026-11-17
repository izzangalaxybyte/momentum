#!/usr/bin/env bash
# Generate the API token the Android app uses, store its SHA-256 hash in SSM, and keep the plain
# token in ~/.momentum/api-token (mode 600). The token is never printed by this script.
# Re-running rotates the token: re-enter the new one in the app (Settings → Sync).
set -euo pipefail

PROFILE="${AWS_PROFILE:-default}"
REGION=eu-central-1
DIR="$HOME/.momentum"
mkdir -p "$DIR" && chmod 700 "$DIR"

# 25 characters from an unambiguous alphabet (no 0/O/1/I/L) ≈ 124 bits, grouped for typing on a phone.
TOKEN=$(python3 -c "import secrets; a='ABCDEFGHJKMNPQRSTUVWXYZ23456789'; t=''.join(secrets.choice(a) for _ in range(25)); print('-'.join(t[i:i+5] for i in range(0,25,5)))")
HASH=$(printf '%s' "$TOKEN" | tr -d '-' | shasum -a 256 | cut -d' ' -f1)

aws ssm put-parameter --profile "$PROFILE" --region "$REGION" --name /momentum/api-token-hash \
  --type String --value "$HASH" --overwrite >/dev/null
( umask 077; printf '%s\n' "$TOKEN" > "$DIR/api-token" )
echo "token hash stored in SSM; token saved to $DIR/api-token"
