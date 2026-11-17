# Momentum — Plan

Two parts: **what the app will have me do** (training and nutrition approach) and **how the app gets
built** (phases and milestones). Architecture details are in [ARCHITECTURE.md](ARCHITECTURE.md).

---

## Part A — Training and nutrition approach

Starting point: not training at all, a lot of weight to lose, training at home.

**Equipment (initial):** 2 × adjustable dumbbells (up to 5 kg each), 1 short bar (10 kg total),
bodyweight. Exact dumbbell load steps are entered during onboarding.

### Nutrition
- Calorie target = estimated daily burn − 500 to 750 kcal (≤ 1% bodyweight loss per week, never below
  the safety floor).
- Protein target ≈ 1.6 g per kg of goal weight, to keep muscle while losing fat.
- Log everything, including chai, oil-heavy curries and sweets; custom versions of home dishes.
- Weekly weigh-in average drives the adjustment (daily weigh-ins are fine; the trend is what counts).
- After 2 weeks, the target re-calibrates from real intake vs real weight change.

### Training progression

| Weeks | Strength | Cardio | Core |
|---|---|---|---|
| 1–4 (foundation) | 2× full body (A/B), easiest ladder steps, learn form | Walk 4–5×/week, start ~20–30 min, +≤10%/week | Short finisher each strength day |
| 5–12 (build) | 3× full body (A/B alternating), double progression | Walk → walk/jog intervals once gated | Finisher + plank ladder |
| 13+ (progress) | Continue ladders; consider more equipment or a gym | Continuous running if desired | Harder core variations |

Every 4th week is lighter (deload). Missed time steps the plan back automatically.

**Strength A:** squat ladder · push ladder · row ladder · glute bridge / hinge ladder · plank ladder
**Strength B:** split-squat ladder · push ladder · press ladder · RDL (bar/DB) · dead bug / side plank

Progress when all sets hit the top of the rep range and it felt easy/good; at the max weight move to
more reps → slow tempo → harder variation. See ARCHITECTURE.md §4.4 for exact rules.

---

## Part B — Build plan

### Decisions made

| Topic | Decision |
|---|---|
| Name | Momentum |
| Platform | Android (Kotlin + Compose), distributed via Appster |
| Backend | AWS Lambda (Python) + DynamoDB + API Gateway + S3/CloudFront, SAM, eu-central-1 |
| Data | Offline-first Room on phone; DynamoDB as durable copy; last-write-wins sync |
| Watch | Samsung Galaxy Watch → Samsung Health → Health Connect |
| AI | Claude Haiku via OpenRouter, called from Lambda only |
| Food | Built-in Pakistani/desi food DB + AI parsing (text/photo) |
| Exercise demos | free-exercise-db images (looping) + cues + curated video links, cached offline |
| Auth | Single-user bearer token (hash in SSM) |
| Repo | Public, github.com/izzangalaxybyte/momentum, push straight to main |

### Phase 0 — Foundations

- [ ] Android project skeleton (`android/`): Compose, Hilt, Room, Navigation, theme, CI build
- [ ] Release signing key created and stored outside the repo; `keystore.properties` gitignored
- [ ] Backend skeleton (`backend/`, uv) with `GET /health`, unit tests
- [ ] SAM template (`infra/`): HTTP API, authorizer, DynamoDB table + GSI1, SSM params, budget alarm
- [ ] Dedicated least-privilege `momentum` AWS profile; first `sam deploy`
- [ ] Token generation script; SSM hash
- [ ] GitHub Actions: Android build + unit tests, backend tests

### Phase 1 — Core app (v0.1)

**1a. Onboarding and calories**
- [ ] Profile, equipment, training days, goal
- [ ] CalorieEngine + WeightTrend with unit tests
- [ ] Today screen calorie/protein ring

**1b. Food logging**
- [ ] `data/foods_pk.json` (~250 desi items) + validation script
- [ ] Seed into Room, FTS search over English/Roman Urdu/aliases
- [ ] Log by meal, quantities in household units, recents/favourites, custom foods
- [ ] Write nutrition to Health Connect

**1c. Weight and Health Connect**
- [ ] Manual weigh-ins + trend chart
- [ ] Health Connect permissions, incremental import (exercise, distance, steps, HR, calories, weight)
- [ ] Dedup and hourly background import

**1d. Training**
- [ ] `data/exercises.json`: ladders, cues, mistakes, muscles, media refs, video links
- [ ] Verify free-exercise-db licence + coverage; upload media to S3/CloudFront; media manifest
- [ ] ProgressionEngine, CardioPlanner, WeekPlanner with unit tests
- [ ] Session runner (demo, targets, set logging, ratings, rest timer)
- [ ] Exercise detail screen with history
- [ ] Media prefetch on Wi-Fi

**1e. Sync**
- [ ] `/sync/push` and `/sync/pull` Lambdas with conditional writes + tests
- [ ] Android outbox/pull sync engine, triggered on open, after writes, periodically
- [ ] Pairing screen (enter token), sync status in Settings, JSON export

**1f. Release v0.1**
- [ ] Progress screen (weight, calories, strength, cardio, steps)
- [ ] App icon PNG, release build, publish to Appster
- [ ] Use it for 1–2 weeks; fix issues

### Phase 2 — AI coach (v0.2)

- [ ] OpenRouter key in SSM; model id configurable
- [ ] `/coach/meal`: text + photo parsing → matched food items, confirm-before-save UI
- [ ] `review` Lambda + EventBridge weekly schedule; CoachContextBuilder
- [ ] Weekly review screen: accept/reject proposals, clamped by domain engines
- [ ] Coach chat with persisted history

### Phase 3 — Extras (v0.3+)

- [ ] Reminders (log food, weigh-in, today's session)
- [ ] Home-screen widget (calories left, today's session)
- [ ] Walk → run programme polish, pace targets from watch data
- [ ] Barcode scanning via Open Food Facts
- [ ] More equipment / gym mode
- [ ] Richer charts and monthly summaries

### Open items

- Exact dumbbell load steps (ask in onboarding).
- Exact OpenRouter model id for Claude Haiku (confirm when wiring phase 2).
- Confirm Samsung Health → Health Connect sharing is enabled on the phone.
- Doctor check-up before starting training.
