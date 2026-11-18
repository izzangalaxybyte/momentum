"""CoachContextBuilder: a compact summary of the synced data for the AI coach.

Everything is computed here from the raw synced records (the same ones the phone keeps), so the model
gets facts and numbers, not tables of rows. Days are epoch days in the phone's local time.
"""

import datetime as dt
import json
import os
from collections import Counter, defaultdict
from typing import Any

from . import store

LADDERS = ("push", "squat", "lunge", "hinge", "row", "press", "core-a", "core-b")
TREND_ALPHA = 0.1  # same EMA as the phone's WeightTrend


def today_epoch_day(now: dt.datetime | None = None) -> int:
    """Today in the phone's time zone (COACH_UTC_OFFSET_MIN, Pakistan by default)."""
    offset = int(os.environ.get("COACH_UTC_OFFSET_MIN", "300"))
    now = now or dt.datetime.now(dt.UTC)
    return (now + dt.timedelta(minutes=offset)).date().toordinal() - dt.date(1970, 1, 1).toordinal()


def monday(day: int) -> int:
    # epoch day 0 (1970-01-01) was a Thursday
    return day - (day + 3) % 7


def day_name(day: int) -> str:
    return (dt.date(1970, 1, 1) + dt.timedelta(days=day)).strftime("%a %d %b")


def _trend(weights: list[dict[str, Any]]) -> dict[int, float]:
    by_day: dict[int, list[float]] = defaultdict(list)
    for w in weights:
        by_day[int(w["epochDay"])].append(float(w["kg"]))
    out: dict[int, float] = {}
    t: float | None = None
    for d in sorted(by_day):
        kg = sum(by_day[d]) / len(by_day[d])
        t = kg if t is None else t + TREND_ALPHA * (kg - t)
        out[d] = round(t, 1)
    return out


def _trend_at(trend: dict[int, float], day: int) -> float | None:
    days = [d for d in trend if d <= day]
    return trend[max(days)] if days else None


def _workout_names() -> dict[str, str]:
    """sessionKey -> the name the user gave that workout (their custom workouts, else Strength A/B)."""
    names = {"strength-a": "Strength A", "strength-b": "Strength B"}
    setting = next((s for s in store.records("Setting") if s.get("id") == "workouts"), None)
    try:
        for w in json.loads(setting["value"]) if setting else []:
            names[str(w["key"])] = f"{w['name']} ({', '.join(w.get('ladders', []))})"
    except (ValueError, KeyError, TypeError):
        pass
    return names


def build(today: int, period_days: int = 7, include_today: bool = False) -> dict[str, Any]:
    """Summary of the last `period_days` days, plus longer-term trends. The scheduled Monday review covers the
    finished week (today excluded); on-demand reviews and chat include today so far."""
    end = today + 1 if include_today else today
    start = end - period_days
    in_period = lambda r: start <= int(r.get("epochDay", -1)) < end  # noqa: E731

    profile = next(iter(store.records("Profile")), None)
    ctx: dict[str, Any] = {"today": day_name(today), "period": f"{day_name(start)} to {day_name(end - 1)}"
                           + (" (today so far included)" if include_today else "")}
    if profile:
        week = max(0, (monday(today) - monday(int(profile.get("startEpochDay") or today))) // 7)
        ctx["profile"] = {
            "sex": profile.get("sex"), "age": dt.date.today().year - int(profile.get("birthYear") or 1995),
            "heightCm": profile.get("heightCm"), "startWeightKg": profile.get("startWeightKg"),
            "goalWeightKg": profile.get("goalWeightKg"), "calorieTarget": profile.get("targetKcal"),
            "trainingWeek": week + 1,
            "plannedStrengthDaysPerWeek": 2 if week < 4 else min(3, len(str(profile.get("trainingDays") or "").split(","))),
            "equipment": f"dumbbells {profile.get('dumbbellLoads')} kg each, bar {profile.get('barLoads')} kg, bodyweight",
        }

    # weight
    weights = store.records("WeightEntry")
    trend = _trend(weights)
    if trend:
        now_t = _trend_at(trend, today)
        week_ago, month_ago = _trend_at(trend, today - 7), _trend_at(trend, today - 28)
        ctx["weight"] = {
            "trendKg": now_t,
            "changeLast7DaysKg": round(now_t - week_ago, 1) if now_t is not None and week_ago is not None else None,
            "changeLast28DaysKg": round(now_t - month_ago, 1) if now_t is not None and month_ago is not None else None,
            "weighInsInPeriod": sum(1 for w in weights if in_period(w)),
        }

    # food
    logs = [f for f in store.records("FoodLog") if in_period(f)]
    per_day: dict[int, list[float]] = defaultdict(lambda: [0.0, 0.0])
    foods: Counter[str] = Counter()
    for f in logs:
        per_day[int(f["epochDay"])][0] += float(f.get("kcal") or 0)
        per_day[int(f["epochDay"])][1] += float(f.get("protein") or 0)
        foods[str(f.get("name"))] += float(f.get("kcal") or 0)
    if per_day:
        ctx["food"] = {
            "daysLogged": len(per_day),
            "avgKcalOnLoggedDays": round(sum(v[0] for v in per_day.values()) / len(per_day)),
            "avgProteinG": round(sum(v[1] for v in per_day.values()) / len(per_day)),
            "proteinTargetG": round(1.6 * float(profile.get("goalWeightKg") or 80)) if profile else None,
            "byDay": {day_name(d): {"kcal": round(v[0]), "proteinG": round(v[1])} for d, v in sorted(per_day.items())},
            "biggestCalorieSources": [f"{n} ({round(k)} kcal)" for n, k in foods.most_common(5)],
        }
    else:
        ctx["food"] = {"daysLogged": 0}

    # strength
    sessions = [s for s in store.records("WorkoutSession") if in_period(s) and s.get("endedAt")]
    sets = [s for s in store.records("SetLog") if in_period(s)]
    by_ex: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for s in sorted(sets, key=lambda s: (s["epochDay"], s.get("setIndex", 0))):
        by_ex[s["exerciseId"]].append(s)
    names = _workout_names()
    ctx["strength"] = {
        "sessionsDone": [f"{day_name(int(s['epochDay']))}: {names.get(s.get('sessionKey'), s.get('sessionKey'))}"
                         for s in sessions],
        "exercises": {
            ex: [f"{day_name(int(s['epochDay']))} " + (f"{s['seconds']}s" if s.get("seconds") else f"{s.get('reps')} reps")
                 + (f" @ {s['loadKg']} kg" if s.get("loadKg") else "") + (f" ({s['rating'].lower()})" if s.get("rating") else "")
                 for s in rows]
            for ex, rows in by_ex.items()
        },
    }
    last_ex: dict[str, str] = {}
    for s in sorted(store.records("SetLog"), key=lambda s: s.get("epochDay", 0)):
        last_ex[s.get("ladder", "")] = s["exerciseId"]
    ladders = {}
    for st in store.records("ExerciseState"):
        stage = {0: "8-12 reps", 1: "8-15 reps", 2: "slow tempo"}.get(int(st.get("stage") or 0), "")
        ladders[st["id"]] = {"currentExercise": last_ex.get(st["id"]), "ladderStep": st.get("step"),
                             "loadKg": st.get("loadKg"), "stage": stage, "holdSec": st.get("holdSec"),
                             "recentMisses": st.get("misses")}
    ctx["ladders"] = ladders

    # cardio and activity
    cardio = [c for c in store.records("CardioSession") if in_period(c) and c.get("kind") != "other"]
    ctx["cardio"] = {
        "sessions": [f"{day_name(int(c['epochDay']))}: {c.get('kind')} {int(c.get('durationSec') or 0) // 60} min"
                     + (f", {round(float(c['distanceM']) / 1000, 1)} km" if c.get("distanceM") else "")
                     + (f", avg HR {c['avgHr']}" if c.get("avgHr") else "")
                     + (f" ({c['rating'].lower()})" if c.get("rating") else "") for c in cardio],
        "totalMinutes": sum(int(c.get("durationSec") or 0) // 60 for c in cardio),
    }
    plan = next((s for s in store.records("Setting") if s.get("id") == "cardio"), None)
    if plan:
        ctx["cardio"]["planState"] = plan.get("value")
    steps = [int(a.get("steps") or 0) for a in store.records("DailyActivity") if in_period(a) and a.get("steps")]
    if steps:
        ctx["steps"] = {"avgPerDay": round(sum(steps) / len(steps)), "daysWithData": len(steps)}

    reviews = sorted(store.records("CoachReview"), key=lambda r: r.get("weekStart", 0))
    previous = [r for r in reviews if int(r.get("weekStart", 0)) < monday(today)]
    if previous:
        ctx["lastWeeksReview"] = previous[-1].get("body")
    return ctx
