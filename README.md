# Momentum

A personal fitness and weight-loss coach for Android.

- Tells you what to do today based on what you did before: walking/running volume, strength
  weights and reps, with progressive overload that keeps working with limited home equipment
  (heavier → more reps → slow tempo → harder variation).
- Calorie and protein targets that re-calibrate weekly from your real intake and weight trend.
- Food tracker with a built-in Pakistani/desi food database (roti, biryani, karahi, daal, chai…),
  measured in pieces, plates, bowls and cups, plus your own custom foods.
- Reads walks, runs, steps, heart rate and weight from a Samsung Galaxy Watch via Health Connect,
  and writes your food log back so Samsung Health shows it too.
- Looping demo, form cues and common mistakes for every exercise.
- Offline-first; syncs to a small serverless AWS backend (Lambda + DynamoDB) for backup and restore.
- AI coach (Claude Haiku 5.5 via OpenRouter): log a meal by describing it ("do roti, aadha bowl
  qeema") or snapping a photo (branded items like "KFC Zinger" are looked up online and saved to your
  foods), asking before you eat ("biryani or daal chawal tonight?"), a weekly review every Monday with suggested plan changes you accept or
  dismiss (kept within the app's safety limits), and a chat.

See [PLAN.md](PLAN.md) and [ARCHITECTURE.md](ARCHITECTURE.md).

> Not medical advice. Check with a doctor before starting a training or weight-loss programme.

## Layout

| Path | What |
|---|---|
| `android/` | The app (Kotlin, Jetpack Compose, Room, Health Connect) |
| `backend/` | API Lambda: sync + AI coach (Python, uv) + tests |
| `infra/` | AWS SAM template (API, table, weekly review schedule); `iam/` sets up the GitHub deploy role |
| `data/` | Food database, exercise catalogue and demo images (bundled into the app) |
| `scripts/` | `build_data.py`, `new-token.sh`, `write-env.sh`, `release.sh` |

## Deploying

- **Backend:** automatic. Every push to `main` touching `backend/` or `infra/` runs the tests and
  deploys the stack to eu-central-1 via GitHub OIDC (`.github/workflows/deploy.yml`).
- **App:** `scripts/release.sh "What changed"` builds a signed release APK and publishes it to Appster.
  The signing key lives in `~/.momentum/` and must never change.

## One-time setup (already done)

```bash
./infra/iam/github-oidc.sh      # deploy role + AWS_DEPLOY_ROLE_ARN repo variable (admin AWS profile)
./scripts/new-token.sh          # API token → ~/.momentum/api-token, hash → SSM
./scripts/write-env.sh          # .env with the API URL baked into the app
# OpenRouter key for the coach (SecureString; never in git):
aws ssm put-parameter --region eu-central-1 --name /momentum/openrouter-api-key --type SecureString --value <key>
```

Then in the app: Settings → Cloud sync → paste the token → Save & sync. On a new phone, use
"Restore my data from the cloud" on the welcome screen.

## Development

```bash
cd backend && uv run pytest
./android/gradlew -p android testDebugUnitTest assembleDebug
python3 scripts/build_data.py --media   # regenerate data/ after editing foods or exercises
```

Exercise images: [free-exercise-db](https://github.com/yuhonas/free-exercise-db) (Unlicense / public domain).
