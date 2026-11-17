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
│ UI: Jetpack Compose                 │          │ API Gateway (HTTP API)               │
│ ViewModels                          │          │   └─ Lambda authorizer (bearer token)│
│ Domain: rule engines (pure Kotlin)  │  HTTPS   │ Lambdas (Python 3.13, arm64)         │
│ Room DB (full local copy)           │◄────────►│   ├─ sync    push / pull changes     │
│ Sync engine (outbox + pull cursor)  │          │   ├─ coach   chat, meal parsing      │──► OpenRouter
│ Health Connect reader/writer        │          │   └─ review  weekly review (cron)    │     (Claude Haiku)
│ Media cache (exercise demos)        │◄──┐      │ DynamoDB (single table, on-demand)   │
│ WorkManager (sync, prefetch)        │   │      │ EventBridge Scheduler (weekly)       │
└──────────────▲──────────────────────┘   └──────│ S3 + CloudFront (exercise media)     │
               │                                 │ SSM Parameter Store (secrets)        │
   Samsung Galaxy Watch                          └──────────────────────────────────────┘
   → Samsung Health → Health Connect
```

Key principle: **the phone is the working copy; DynamoDB is the durable copy.** Every screen reads
from Room. Writes go to Room first and are queued for sync. The app is fully usable offline except
for AI features.

---

## 3. Repository layout

```
momentum/
  android/             Android app (Kotlin, Gradle)
  backend/             Lambda code (Python, managed with uv)
    momentum_api/      handlers: sync, coach, review, authorizer
    tests/
  infra/               AWS SAM template + deploy config
  data/
    foods_pk.json      desi food database (seed)
    exercises.json     exercise catalogue, ladders, cues, media refs
    media/             media manifest (files themselves live in S3)
  scripts/             helpers (seed/validate data, upload media, publish)
  ARCHITECTURE.md
  PLAN.md
```

---

## 4. Android app

### 4.1 Stack

| Concern | Choice |
|---|---|
| Language / UI | Kotlin, Jetpack Compose, Material 3, single activity, Navigation Compose |
| DI | Hilt |
| Local DB | Room (+ FTS4 for food search) |
| Settings | DataStore |
| Secrets on device | Android Keystore-backed encryption (Tink) for the API token |
| Background work | WorkManager |
| Health data | Health Connect SDK (`androidx.health.connect:connect-client`) |
| Networking | Ktor client + kotlinx.serialization |
| Images / demos | Coil (disk cache) |
| Charts | Vico |
| Min / target SDK | minSdk 28, target latest |

One Gradle module, organised by layer and feature. Split into modules later only if build times hurt.

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
| `Profile` | sex, birthYear, heightCm, startWeightKg, goalWeightKg, activityLevel, trainingDays |
| `Equipment` | kind (dumbbell, bar, plate), availableLoadsKg (list), count |
| `WeightEntry` | date, kg, source (manual, health_connect) |
| `Food` | nameEn, nameUrdu (Roman), aliases, unit, gramsPerUnit, kcal, proteinG, carbsG, fatG, category, isCustom |
| `FoodFts` | FTS4 index over names + aliases |
| `FoodLog` | date, meal, foodId?, freeText?, quantity, kcal, proteinG, carbsG, fatG, source (manual, ai_text, ai_photo) |
| `Exercise` | slug, name, type (weight_reps, reps, time), ladderId, ladderStep, mediaRef, cues |
| `ExerciseState` | exerciseId / ladderId, currentStep, loadKg, repMin, repMax, tempo, consecutiveMisses |
| `WorkoutSession` | date, plannedDayId, startedAt, endedAt, notes |
| `SetLog` | sessionId, exerciseId, setIndex, loadKg, reps, durationSec, rating (easy, good, hard) |
| `CardioSession` | hcRecordId, type, start, durationSec, distanceM, avgHr, maxHr, kcal, rating |
| `DailyActivity` | date, steps, activeKcal |
| `PlannedDay` | date, items (strength A/B, walk 30 min, intervals…), status |
| `CoachMessage` | role, content, createdAt (phase 2) |
| `WeeklyReview` | weekStart, summary, proposals (JSON), decision (pending, accepted, rejected) (phase 2) |
| `SyncState` | lastPullCursor, lastPushAt |

`Food` (built-in rows) and `Exercise` are seeded from `data/*.json` bundled as app assets and
versioned; seed upgrades merge without touching custom rows. Only custom foods sync.

---

## 5. Health Connect (Samsung watch)

- One-time setup on the phone: Samsung Health → Settings → Health Connect → allow sharing.
- Read permissions: `ExerciseSessionRecord`, `DistanceRecord`, `StepsRecord`, `HeartRateRecord`,
  `ActiveCaloriesBurnedRecord`, `TotalCaloriesBurnedRecord`, `WeightRecord`.
- Write permissions: `NutritionRecord` (food log shows up in Samsung Health), `WeightRecord`
  (manual weigh-ins).
- Incremental sync with Health Connect **changes tokens**: on app open and hourly in WorkManager.
- Dedup by Health Connect record id (`metadata.id`); records the app wrote itself are skipped on read.
- Imported data lands in Room, then syncs to AWS like everything else. Health Connect has no cloud
  API, so the phone is the only bridge.

---

## 6. Backend (AWS)

### 6.1 Components

| Component | Purpose |
|---|---|
| API Gateway HTTP API | Single HTTPS entry point, throttled |
| Lambda authorizer | Validates the bearer token |
| `sync` Lambda | Push / pull changes |
| `coach` Lambda | Chat and meal parsing (phase 2) |
| `review` Lambda | Weekly review, triggered by EventBridge Scheduler (phase 2) |
| DynamoDB table `momentum` | All user data, on-demand capacity, point-in-time recovery on |
| S3 bucket + CloudFront | Exercise demo media (bucket private, CloudFront Origin Access Control) |
| SSM Parameter Store | `/momentum/api-token-hash`, `/momentum/openrouter-key` (SecureString) |
| CloudWatch | Logs (14-day retention, no food/photo content logged), $5 budget alarm |

- Region: **eu-central-1 (Frankfurt)**, closest EU/US region to Pakistan.
- Lambdas: Python 3.13, arm64, AWS Lambda Powertools, pydantic models; deps via uv.
- Infra as code: AWS SAM (`infra/template.yaml`). Deploy with a dedicated, least-privilege
  `momentum` AWS profile (separate from the Appster one).

### 6.2 API

All endpoints require `Authorization: Bearer <token>`.

| Method & path | Body / query | Response |
|---|---|---|
| `POST /sync/push` | `{changes: [{type, id, updatedAt, deleted, data}]}` | `{applied: [...ids], stale: [...ids]}` |
| `GET /sync/pull` | `?since=<cursor>&limit=500` | `{changes: [...], cursor, hasMore}` |
| `POST /coach/meal` | `{text}` or `{imageBase64, mimeType}` | `{items: [{name, matchedFoodId?, quantity, unit, kcal, proteinG, carbsG, fatG, confidence}]}` |
| `POST /coach/chat` | `{message}` | `{reply}` (history persisted as `CoachMessage`) |
| `POST /coach/review/run` | — | triggers a review now (manual) |
| `GET /media/manifest` | — | exercise media URLs + versions |
| `GET /health` | — | liveness |

Weekly reviews and coach messages are ordinary synced records, so the app gets them through `pull`.

### 6.3 DynamoDB design (single table)

| Attribute | Example |
|---|---|
| `pk` | `USER#me` |
| `sk` | `FOODLOG#2026-11-17#<uuid>`, `WEIGHT#2026-11-17#<uuid>`, `SET#<sessionId>#<uuid>` … |
| `type` | `FoodLog` |
| `updatedAt` | client timestamp (conflict resolution) |
| `serverUpdatedAt` | server timestamp (pull cursor) |
| `deleted` | tombstone flag |
| `data` | the record body (map) |
| `gsi1pk` / `gsi1sk` | `USER#me` / `<serverUpdatedAt>#<type>#<id>` |

- `GSI1` answers "everything changed since cursor" for pull.
- Date-prefixed sort keys answer "this user's food log for a date range" for the review Lambda.
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
- Generated once (`scripts/new-token`), shown once, entered in the app during onboarding (or via QR).
- Stored on the phone encrypted with Keystore; only its SHA-256 hash is stored in SSM.
- The authorizer compares hashes in constant time and caches the decision briefly.
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
- Media is uploaded to S3 and served via CloudFront. The app caches with Coil and prefetches the
  coming week's exercises on Wi-Fi, so demos work offline.
- The manifest (`GET /media/manifest`) is versioned so new/changed media is picked up automatically.

---

## 10. Security and privacy

- Public repo: no secrets in git. `.gitignore` covers `keystore.properties`, `*.jks`, `.env`,
  `samconfig` overrides. Secrets live in SSM or on-device encrypted storage.
- HTTPS only; API Gateway throttling; authorizer on every route.
- Health and food data is sensitive: DynamoDB encryption at rest (default), no payload logging,
  14-day log retention, PITR backups.
- Local export (JSON) from Settings as an extra backup.

---

## 11. Release

- Release APK signed with Momentum's own release key, created once and stored outside the repo
  (keep it forever; Appster refuses a changed certificate).
- `versionCode` bumped every release.
- Publish to Appster:

  ```bash
  uv run --project ~/Documents/GitHub/appster ~/Documents/GitHub/appster/scripts/publish.py \
    android/app/build/outputs/apk/release/app-release.apk \
    --changelog "What changed" --icon data/icon.png --name "Momentum"
  ```

- Backend: `sam build && sam deploy --profile momentum` from `infra/`.

---

## 12. Expected cost

| Item | Monthly |
|---|---|
| Lambda, API Gateway, DynamoDB on-demand, S3, CloudFront | ~$0–1 (mostly free tier) |
| OpenRouter, Claude Haiku (daily meal parsing + weekly review + some chat) | < $1 |
| **Total** | **~$1–2** |

A $5 AWS budget alarm catches surprises.
