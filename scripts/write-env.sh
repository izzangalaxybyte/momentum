#!/usr/bin/env bash
# Write the repo's git-ignored .env with the deployed API URL (baked into the app as the default server).
set -euo pipefail
cd "$(dirname "$0")/.."
URL=$(aws cloudformation describe-stacks --stack-name momentum --region eu-central-1 \
  --profile "${AWS_PROFILE:-default}" \
  --query "Stacks[0].Outputs[?OutputKey=='ApiUrl'].OutputValue" --output text)
printf 'MOMENTUM_API_URL=%s\n' "$URL" > .env
echo "wrote .env (MOMENTUM_API_URL=$URL)"
