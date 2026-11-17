# Momentum — Architecture

Momentum is a personal fitness and weight-loss coach: an offline-first Android app backed by a small
serverless AWS backend. It tracks food (with a Pakistani/desi food database), body weight, strength
training and cardio (from a Samsung Galaxy Watch via Health Connect), and tells you what to do today
based on what you did before. An AI coach (Claude Haiku via OpenRouter) reviews each week and helps
log meals from text or photos.

> Not medical advice. The app uses conservative, well-established rules (safe deficit, gradual
> progression), but check with a doctor before starting, especially with a lot of weight to lose.

---

## 1. Goals and constraints

| Goal | How |
|---|---|
| Know what to do today | Rule engines turn history into today's workout, distance and calorie target |
| Progressive overload with limited home equipment | Progression ladders that continue past the max weight (reps → tempo → harder variation) |
| Weight loss | Calorie target from a TDEE estimate, re-calibrated weekly from real intake and weight trend |
| Desi food logging | Built-in food database in household units (roti, plate, bowl, cup) + AI parsing |
| Use the Samsung watch | Samsung Health → Health Connect → app |
| Exercise demos | Looping demo + form cues for every exercise, no searching |
| Works at the gym/home without signal | Offline-first: the phone has a full local copy; sync when online |
| Cheap, low maintenance | Serverless AWS (pay-per-request), cents per month |
| Public repo | No secrets in git; all secrets in AWS SSM or on-device encrypted storage |

Single user (me). Multi-user is a possible future, not a design target.

---

## 2. System overview

```
PHONE (Android, offline-first)                         AWS  (eu-central-1, Frankfurt)
┌─────────────────────────────────────┐          ┌──────────────────────────────────────┐
│ UI: Jetpack Compose                 │          │ API Gateway (HTTP API, throttled)    │
│ Domain: rule engines (pure Kotlin)  │  HTTPS   │ Lambda momentum-api (Python 3.13)    │
│ Room DB (full local copy)           │◄────────►│   ├─ /sync/push, /sync/pull          │
│ Sync engine (dirty rows + cursor)   │          │   └─ bearer-token check (hash in SSM)│
│ Health Connect reader/writer        │          │ DynamoDB momentum-data (single table)│
│ Exercise demos + food DB (bundled)  │          │ SSM Parameter Store                  │
│ WorkManager (hourly import + sync)  │          │ phase 2: coach/review Lambdas,       │──► OpenRouter
└──────────────▲──────────────────────┘          │   EventBridge weekly schedule        │    (Claude Haiku)
               │                                 └──────────────────────────────────────┘
   Samsung Galaxy Watch
   → Samsung Health → Health Connect
```

Key principle: **the phone is the working copy; DynamoDB is the durable copy.** Every screen reads
from Room. Writes go to Room first and are queued for sync. The app is fully usable offline except
for AI features.

---

## 3. Repository layout

```
momentum/
  android/             Android app (Kotlin, Gradle); icon.png for the store
  backend/             Lambda code (Python, uv)
    src/momentum_api/  app.py (routes), auth.py (token), store.py (DynamoDB)
    tests/
  infra/               AWS SAM template + samconfig; iam/ (deploy role + policies)
  data/                bundled into the APK as assets
    foods_pk.json      desi food database (generated)
    exercises.json     exercise catalogue, ladders, cues (generated)
    exercises/<id>/    demo frames 0.jpg, 1.jpg (public domain)
  scripts/             build_data.py, new-token.sh, write-env.sh, release.sh
  .github/workflows/   deploy.yml (backend test + deploy), android.yml (build + tests)
  ARCHITECTURE.md
  PLAN.md
```

---

## 4. Android app

### 4.1 Stack

| Concern | Choice |
|---|---|
| Language / UI | Kotlin, Jetpack Compose, Material 3, single activity, Navigation Compose |
| DI | A small hand-written `AppContainer` (no framework needed at this size) |
| Local DB | Room (KSP) |
| Food search | In memory over the bundled list + custom foods (English, Roman Urdu, aliases) |
| Settings | DataStore |
| Secrets on device | API token encrypted with an AES-GCM key held in the Android Keystore |
| Background work | WorkManager (hourly import + sync, and a sync a few seconds after each change) |
| Health data | Health Connect SDK (`androidx.health.connect:connect-client` 1.1.0) |
| Networking | OkHttp + kotlinx.serialization |
| Images / demos | Coil, loading bundled asset images |
| Charts | Compose Canvas (weight trend, calories, cardio minutes) |
| Min / target SDK | minSdk 28, target 36 |
| Release | R8 minify + resource shrinking (~7.5 MB APK) |

One Gradle module, organised by layer. Split into modules later only if build times hurt.

### 4.2 Layers

```
ui/        Screens + ViewModels (StateFlow of immutable UI state, one-way data flow)
domain/    Pure Kotlin rule engines and use cases. No Android imports. Fully unit-tested.
data/      Room entities/DAOs, repositories, Health Connect source, API client, sync engine
work/      WorkManager workers (sync, Health Connect import, media prefetch)
```

The domain layer is the brain. AI suggestions never bypass it: anything the coach proposes is
validated and clamped by the same engines before it is shown or applied.

### 4.3 Screens

| Screen | Content |
|---|---|
| Onboarding | Height, weight, age, sex, goal weight, activity, training days, equipment, Health Connect permissions, backend pairing |
| Today | Calorie ring (eaten / target / protein), today's session(s), steps, quick-add food, weigh-in prompt |
| Food | Day log by meal, search desi food DB, recent/favourite foods, custom foods, AI add (text/photo, phase 2) |
| Train | Today's session runner: exercise demo, cues, target sets × reps × weight, log each set, rate easy/good/hard, rest timer |
| Exercise detail | Looping demo, form cues, common mistakes, muscles, "watch full video" link, history chart |
| Progress | Weight trend, calories vs target, strength progress per exercise, weekly cardio volume, steps |
| Coach (phase 2) | Weekly reviews (accept / reject proposed changes), chat |
| Settings | Profile, equipment, training days, units, sync status, export backup |

### 4.4 Domain engines

All engines are pure functions over data from the repositories, deterministic and unit-tested.

**CalorieEngine**
- BMR: Mifflin-St Jeor (`10·kg + 6.25·cm − 5·age + 5` for men, `−161` for women).
- Initial TDEE: BMR × activity factor (start at 1.2 sedentary, adjusted from average steps).
- Deficit: 500–750 kcal/day, capped so expected loss ≤ 1% of bodyweight per week.
- Floors: never below 1500 kcal (men) / 1200 kcal (women).
- Protein target: ~1.6 g per kg of **goal** weight (more sensible than current weight at high body fat).
- Adaptive TDEE: after ≥ 14 days with ≥ 10 logged days, observed TDEE =
  `avg intake − (Δ trend weight kg × 7700) / days`. Blended with the formula estimate, weighting the
  observed value more as data accumulates. Target changes at most ±150 kcal per week.

**WeightTrend**
- Exponentially smoothed moving average of daily weigh-ins (α ≈ 0.1) so water swings don't move the plan.
- Missing days are skipped, not interpolated.

**ProgressionEngine** (strength and core)
- Every exercise belongs to a **ladder** (ordered list of variations) and is tracked by
  `ExerciseState`: current variation, load, rep range, consecutive misses.
- Double progression: rep range 8–12 (3 sets by default).
  - All sets hit the top of the range and the rating is easy/good → increase load by the next
    available step from the `Equipment` table.
  - Any set below the bottom of the range, or rated hard while under target → a "miss".
    Two consecutive misses → drop one load step (or one variation step).
- When the load is maxed (5 kg per dumbbell, 10 kg bar):
  1. extend the rep range to 15,
  2. then slow tempo (3 s down, 1 s pause, 3 s up),
  3. then move to the next, harder variation and reset to 8 reps.
- Time-based exercises (plank etc.): 3 × 20 s → +5 s per session when easy/good; at 60 s move to the
  next variation.
- Example ladders:
  - Push: wall push-up → incline push-up (table) → knee push-up → full push-up → feet-elevated push-up
  - Squat: bodyweight squat → goblet squat (DB) → split squat → rear-foot-elevated split squat → step-up
  - Hinge: glute bridge → single-leg glute bridge → DB/bar Romanian deadlift → single-leg RDL
  - Row: DB bent-over row → single-arm DB row (slow tempo) → bar row
  - Press: DB floor press → DB overhead press → pike push-up
  - Core: plank → side plank → dead bug → bird dog → hollow hold
- Equipment is data, not code: adding a gym or heavier dumbbells later just extends the load steps.

**CardioPlanner**
- Phases: **Walk** → **Walk/Jog intervals** → **Continuous run**.
- Baseline comes from the last 7–14 days of Health Connect data (steps, walks, runs).
- Weekly volume (minutes) grows ≤ 10%; every 4th week is a deload (−20 to −30%).
- Walk phase: start ~20–30 min, 4–5×/week, grow to 45 min brisk.
- Gate to intervals: three 45-min brisk walks in a week, none rated hard. Staying in the walk phase
  longer is fine (joint-friendly while heavy).
- Interval phase: e.g. 8 × (1 min jog / 2 min walk), progressing the jog share each week.
- Gaps: 7–13 days missed → repeat previous week; 14+ days → step back two weeks.
- Watch heart rate is stored for review; if average HR is unusually high for a session, the next
  session is not progressed.

**WeekPlanner**
- Input: available days, phase, equipment. Output: `PlannedDay` rows for the coming week.
- Weeks 1–4: 2 full-body strength sessions + 4–5 walks + core finishers.
- Weeks 5+: 3 strength sessions (A/B alternating, never on consecutive days) + cardio + 1 full rest day.
- Re-planned every Monday and whenever settings change; already-completed days are never rewritten.

**CoachContextBuilder** (phase 2)
- Builds a compact JSON summary of the last 1–4 weeks for the AI (weight trend, average intake and
  protein, adherence, sessions done and ratings, cardio volume, steps, current plan). Never raw tables.

### 4.5 Local data model (Room)

Every syncable table has: `id` (UUID string), `updatedAt` (epoch ms, client clock), `deleted`
(tombstone), `dirty` (pending push).

| Table | Key fields |
|---|---|
| `Profile` | sex, birthYear, heightCm, start/goal weight, trainingDays, dumbbellLoads, barLoads, startEpochDay, targetKcal (+ when it was set) |
| `WeightEntry` | date, kg, source (manual, health_connect) |
| `Food` | custom foods only: name, unit, grams, kcal, protein, carbs, fat (built-ins ship as an asset) |
| `FoodLog` | date, meal, foodId?, freeText?, quantity, kcal, proteinG, carbsG, fatG, source (manual, ai_text, ai_photo) |
| `ExerciseState` | per ladder: step, loadKg, stage (normal / high reps / slow tempo), misses, holdSec |
| `WorkoutSession` | date, plannedDayId, startedAt, endedAt, notes |
| `SetLog` | sessionId, exerciseId, setIndex, loadKg, reps, durationSec, rating (easy, good, hard) |
| `CardioSession` | hcRecordId, type, start, durationSec, distanceM, avgHr, maxHr, kcal, rating |
| `DailyActivity` | date, steps, activeKcal |
| `Setting` | small synced values, e.g. the cardio plan state (phase, weekly minutes, interval level) |
| `CoachMessage`, `WeeklyReview` | phase 2 |

The week plan is computed on the fly from these (never stored), and the sync cursor lives in
device-local DataStore.

The built-in foods and the exercise catalogue are read straight from the bundled `data/*.json`
(generated by `scripts/build_data.py`), so app updates bring new items without a migration.

---

## 5. Health Connect (Samsung watch)

- One-time setup on the phone: Samsung Health → Settings → Health Connect → allow sharing.
- Read permissions: `ExerciseSessionRecord`, `DistanceRecord`, `StepsRecord`, `HeartRateRecord`,
  `ActiveCaloriesBurnedRecord`, `TotalCaloriesBurnedRecord`, `WeightRecord`.
- Write permissions: `NutritionRecord` (food log shows up in Samsung Health), `WeightRecord`
  (manual weigh-ins).
- On app open and hourly in WorkManager the app re-reads the **last 14 days** (simpler and self-healing
  compared with change tokens) and upserts only rows that changed.
- Dedup by Health Connect record id (`metadata.id`); records the app wrote itself are skipped on read.
- Imported data lands in Room, then syncs to AWS like everything else. Health Connect has no cloud
  API, so the phone is the only bridge.

---

## 6. Backend (AWS)

### 6.1 Components

| Component | Purpose |
|---|---|
| API Gateway HTTP API | Single HTTPS entry point, throttled (10 req/s, burst 20) |
| `momentum-api` Lambda | Routes `/health`, `/sync/push`, `/sync/pull`; checks the bearer token itself |
| `coach` / `review` Lambdas | Chat, meal parsing, weekly review via EventBridge Scheduler (phase 2) |
| DynamoDB table `momentum-data` | All user data, on-demand, point-in-time recovery, TTL on tombstones, retained on stack delete |
| SSM Parameter Store | `/momentum/api-token-hash`; phase 2: `/momentum/openrouter-key` (SecureString) |
| CloudWatch | Logs with 14-day retention; request bodies are never logged |

Exercise demos are bundled in the APK (about 3 MB), so there is no media bucket or CDN.

- Region: **eu-central-1 (Frankfurt)**, closest EU/US region to Pakistan.
- Lambdas: Python 3.13, arm64, AWS Lambda Powertools, pydantic models; deps via uv.
- Infra as code: AWS SAM (`infra/template.yaml`).
- **Auto-deploy:** every push to `main` that touches `backend/` or `infra/` runs
  `.github/workflows/deploy.yml`: backend tests, then `sam build && sam deploy`, then a `/health`
  smoke test. AWS credentials come from GitHub OIDC: the role `github-momentum-deploy` (created by
  `infra/iam/github-oidc.sh`) can only be assumed from this repo's `main` branch and only manage
  `momentum-*` resources; roles it creates are capped by the `momentum-boundary` policy. No AWS keys
  are stored anywhere.

### 6.2 API

All endpoints require `Authorization: Bearer <token>`.

| Method & path | Body / query | Response |
|---|---|---|
| `POST /sync/push` | `{changes: [{type, id, updatedAt, deleted, data}]}` | `{applied: [...ids], stale: [...ids]}` |
| `GET /sync/pull` | `?since=<cursor>&limit=500` | `{changes: [...], cursor, hasMore}` |
| `POST /coach/meal` | `{text}` or `{imageBase64, mimeType}` | `{items: [{name, matchedFoodId?, quantity, unit, kcal, proteinG, carbsG, fatG, confidence}]}` |
| `POST /coach/chat` | `{message}` | `{reply}` (history persisted as `CoachMessage`) |
| `POST /coach/review/run` | — | triggers a review now (manual) |
| `GET /health` | — | liveness |

Weekly reviews and coach messages are ordinary synced records, so the app gets them through `pull`.

### 6.3 DynamoDB design (single table)

| Attribute | Example |
|---|---|
| `pk` | `USER#me` |
| `sk` | `<type>#<id>`, e.g. `FoodLog#<uuid>`, `ExerciseState#push`, `Profile#me` |
| `type` | `FoodLog` |
| `updatedAt` | client timestamp (conflict resolution) |
| `serverUpdatedAt` | server timestamp (pull cursor) |
| `deleted` | tombstone flag |
| `data` | the record body (JSON string) |
| `gsi1pk` / `gsi1sk` | `USER#me` / `<serverUpdatedAt>#<type>#<id>` |

- `GSI1` answers "everything changed since cursor" for pull.
- Records carry their own `epochDay`, so the phase-2 review Lambda filters by date after a type query.
- Tombstones expire via DynamoDB TTL after 180 days.

### 6.4 Sync protocol

1. Every local write sets `updatedAt = now`, `dirty = true`.
2. Push: send dirty rows in batches. Server applies a row only if its `updatedAt` is newer than the
   stored one (conditional write) → **last-write-wins** per record. Applied rows get a fresh
   `serverUpdatedAt`. The client clears `dirty` for applied and stale ids.
3. Pull: `GET /sync/pull?since=cursor` returns rows ordered by `serverUpdatedAt`; the client upserts
   where the incoming `updatedAt` is newer than local, then stores the new cursor.
4. Push always runs before pull. Runs on app open, after any write (debounced), and periodically.
5. Fresh install / new phone: pull from cursor 0 restores everything.

Using server time for the cursor and client time for conflicts avoids missing changes due to clock
skew while keeping "my latest edit wins" semantics.

### 6.5 Authentication

Single user, so a long random token instead of an identity provider:
- Generated once by `scripts/new-token.sh` (25 characters, ~124 bits, grouped for typing), saved to
  `~/.momentum/api-token`, entered in the app (Settings → Cloud sync, or "Restore" on first launch).
- Stored on the phone encrypted with a Keystore key; only its SHA-256 hash is stored in SSM.
- The Lambda compares hashes in constant time (case, spaces and dashes ignored) and caches the hash
  for 5 minutes.
- Rotation: generate a new token, update SSM, re-pair the app.
- Upgrade path if other users are ever added: Amazon Cognito with Google sign-in, `pk = USER#<sub>`.

---

## 7. AI coach (phase 2)

- Provider: **OpenRouter**, model **Claude Haiku** (exact OpenRouter model id configured in SSM /
  Lambda env, so it can be switched without an app release).
- Called only from Lambda; the OpenRouter key never leaves AWS.
- All AI responses that drive app behaviour are JSON, validated with pydantic against a schema;
  one retry on invalid output, otherwise the feature fails gracefully.

| Feature | Input | Output |
|---|---|---|
| Meal parsing | Text ("do roti, aadha bowl qeema, ek chai") or photo + the food DB names list | Items with quantities; matched to `Food` rows where possible; user confirms before saving |
| Weekly review | `CoachContextBuilder` summary (built server-side from DynamoDB) | `{summary, wins, concerns, proposals: [{kind, target, value, reason}], tip}` |
| Chat | Message + compact context + recent messages | Free text |

- **Guardrails:** proposals (calorie change, progress/deload an exercise, add a cardio day…) are
  clamped by the phone's domain engines (calorie floors, ±150 kcal/week, ≤10% cardio growth) and
  applied only after the user taps Accept.
- Photos are sent through to the model and discarded; never stored or logged.
- Privacy note: meal text/photos and weekly summaries are sent to OpenRouter and the model provider.

---

## 8. Food database (desi)

- `data/foods_pk.json`, ~250 items to start, bundled as an app asset and seeded into Room.
- Fields: `id`, `nameEn`, `nameUrdu` (Roman Urdu), `aliases` (roti/chapati/phulka), `unit` (piece,
  plate, bowl, cup, tbsp…), `gramsPerUnit`, `kcal`, `proteinG`, `carbsG`, `fatG`, `category`, `source`.
- Coverage: breads (roti, paratha, naan, puri), rice (biryani, pulao, plain), curries (karahi, qorma,
  nihari, haleem, aloo gosht, qeema), daals and chana, sabzi, BBQ (tikka, seekh kebab), snacks
  (samosa, pakora, chaat), breakfast (halwa puri, anda paratha), sweets (kheer, gulab jamun, halwa),
  drinks (chai, lassi, doodh patti), fruits, plus common packaged items.
- Values are averages from food composition tables; home cooking (especially oil/ghee) varies, so
  every item can be overridden with a custom version.
- Search: FTS over English, Roman Urdu and aliases; recents and favourites first.
- Phase 3: barcode lookup via Open Food Facts.

---

## 9. Exercise demos

- Every exercise in `data/exercises.json` has: demo media ref, 3–4 form cues, 2 common mistakes,
  muscles worked, and a curated YouTube link ("watch full video").
- Source for demos: **free-exercise-db** (github.com/yuhonas/free-exercise-db, public domain /
  Unlicense) — start and end position images, shown as a looping two-frame animation. Licence and
  coverage to be verified for every exercise in the home programme; gaps are filled with curated
  video links.
- The frames are bundled in the APK (`data/exercises/`, ~3 MB), so demos always work offline.
- Where the library had no exact match (wall push-up, knee push-up, chair squat) the closest movement
  is shown and the first cue says so.

---

## 10. Security and privacy

- Public repo: no secrets in git. The signing key and its password live in `~/.momentum/`, the API
  token in `~/.momentum/api-token` and SSM (hash only); `.env` only holds the public API URL.
- HTTPS only; API Gateway throttling; authorizer on every route.
- Health and food data is sensitive: DynamoDB encryption at rest (default), no payload logging,
  14-day log retention, PITR backups.
- Local export (JSON) from Settings as an extra backup.

---

## 11. Release

- Release APK signed with Momentum's own key (`~/.momentum/release.p12`, created once; keep it and
  `~/.momentum/keystore.properties` backed up forever: Appster refuses a changed certificate).
- `versionCode` = number of commits on `main`, so it always increases.
- Build and publish to Appster in one step: `scripts/release.sh "What changed"`.
- Backend: automatic on push to `main`; manual fallback `sam build && sam deploy` from `infra/`.

---

## 12. Expected cost

| Item | Monthly |
|---|---|
| Lambda, API Gateway, DynamoDB on-demand | ~$0–1 (mostly free tier) |
| OpenRouter, Claude Haiku (daily meal parsing + weekly review + some chat) | < $1 |
| **Total** | **~$1–2** |

