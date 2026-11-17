#!/usr/bin/env bash
# One-time (re-runnable): let GitHub Actions deploy momentum without stored AWS keys (OIDC).
# Run with admin credentials (ADMIN_PROFILE, default "default").
#
# Creates or updates:
#   - policies momentum-boundary (caps every role the stack creates) and momentum-deployer
#   - the GitHub OIDC identity provider, if the account doesn't have one yet
#   - role github-momentum-deploy, assumable only by workflows on this repo's main branch
#   - the AWS_DEPLOY_ROLE_ARN repository variable the deploy workflow reads
# The role name deliberately does NOT start with "momentum-": the deployer policy can manage
# momentum-* roles, and the deploy role must not be able to edit its own trust policy.
set -euo pipefail
cd "$(dirname "$0")"

ADMIN_PROFILE="${ADMIN_PROFILE:-default}"
REPO="${REPO:-izzangalaxybyte/momentum}"
ROLE=github-momentum-deploy
aws_admin() { aws --profile "$ADMIN_PROFILE" "$@"; }

ACCOUNT_ID=$(aws_admin sts get-caller-identity --query Account --output text)
render() { sed "s/\${ACCOUNT_ID}/$ACCOUNT_ID/g" "$1"; }

upsert_policy() {  # name file
  local arn="arn:aws:iam::$ACCOUNT_ID:policy/$1"
  if aws_admin iam get-policy --policy-arn "$arn" >/dev/null 2>&1; then
    local old
    old=$(aws_admin iam list-policy-versions --policy-arn "$arn" \
      --query 'reverse(sort_by(Versions[?!IsDefaultVersion], &CreateDate))[3:].VersionId' --output text)
    for v in $old; do aws_admin iam delete-policy-version --policy-arn "$arn" --version-id "$v"; done
    aws_admin iam create-policy-version --policy-arn "$arn" --policy-document "$(render "$2")" --set-as-default >/dev/null
    echo "updated policy $1"
  else
    aws_admin iam create-policy --policy-name "$1" --policy-document "$(render "$2")" \
      --tags Key=app,Value=momentum >/dev/null
    echo "created policy $1"
  fi
}

upsert_policy momentum-boundary boundary-policy.json
upsert_policy momentum-deployer deployer-policy.json

# GitHub's subject prefix for this repo ("repo:owner/name", or the immutable-ID form). Ask, don't guess.
SUB_PREFIX=$(gh api "repos/$REPO/actions/oidc/customization/sub" --jq '.sub_claim_prefix // empty' 2>/dev/null || true)
SUB_PREFIX="${SUB_PREFIX:-repo:$REPO}"
PROVIDER_ARN="arn:aws:iam::$ACCOUNT_ID:oidc-provider/token.actions.githubusercontent.com"

if ! aws_admin iam get-open-id-connect-provider --open-id-connect-provider-arn "$PROVIDER_ARN" >/dev/null 2>&1; then
  aws_admin iam create-open-id-connect-provider \
    --url https://token.actions.githubusercontent.com \
    --client-id-list sts.amazonaws.com \
    --thumbprint-list 6938fd4d98bab03faadb97b34396831e3780aea1 >/dev/null
  echo "created GitHub OIDC provider"
fi

TRUST=$(cat <<JSON
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": {"Federated": "$PROVIDER_ARN"},
    "Action": "sts:AssumeRoleWithWebIdentity",
    "Condition": {
      "StringEquals": {
        "token.actions.githubusercontent.com:aud": "sts.amazonaws.com",
        "token.actions.githubusercontent.com:sub": "$SUB_PREFIX:ref:refs/heads/main"
      }
    }
  }]
}
JSON
)

if aws_admin iam get-role --role-name "$ROLE" >/dev/null 2>&1; then
  aws_admin iam update-assume-role-policy --role-name "$ROLE" --policy-document "$TRUST"
  echo "updated trust policy on $ROLE"
else
  aws_admin iam create-role --role-name "$ROLE" --assume-role-policy-document "$TRUST" \
    --max-session-duration 3600 --tags Key=app,Value=momentum >/dev/null
  echo "created role $ROLE"
fi
aws_admin iam attach-role-policy --role-name "$ROLE" --policy-arn "arn:aws:iam::$ACCOUNT_ID:policy/momentum-deployer"

ROLE_ARN="arn:aws:iam::$ACCOUNT_ID:role/$ROLE"
gh variable set AWS_DEPLOY_ROLE_ARN --repo "$REPO" --body "$ROLE_ARN"
echo "done: $ROLE_ARN (stored as repo variable AWS_DEPLOY_ROLE_ARN)"
