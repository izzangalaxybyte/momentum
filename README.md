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
- Coming next (phase 2): AI coach (Claude Haiku via OpenRouter) for weekly reviews and meal logging
  from text or photos.

See [PLAN.md](PLAN.md) and [ARCHITECTURE.md](ARCHITECTURE.md).

> Not medical advice. Check with a doctor before starting a training or weight-loss programme.

## Layout

| Path | What |
|---|---|
| `android/` | The app (Kotlin, Jetpack Compose, Room, Health Connect) |
| `backend/` | Sync API Lambda (Python, uv) + tests |
| `infra/` | AWS SAM template; `iam/` sets up the GitHub deploy role |
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
