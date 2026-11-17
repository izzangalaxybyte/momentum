"""The AI coach: meal parsing, weekly reviews and chat.

The model only suggests. Meal items are confirmed by the user before they're logged, and review
proposals are clamped by the phone's own rules (calorie floors, ±150 kcal a week, ≤10% cardio growth)
and applied only when the user taps Accept.
"""

import json
import time
import uuid
from typing import Any

from . import context, llm, store

MAX_MEAL_TEXT = 1000
MAX_IMAGE_B64 = 2_500_000
MAX_CATALOG = 800
MAX_CHAT_TEXT = 2000
CHAT_HISTORY = 20
PROPOSAL_KINDS = ("calorie_target", "exercise_easier", "exercise_harder", "cardio_easier", "cardio_harder")

COACH_VOICE = """You are Momentum's coach: a friendly, practical fitness and nutrition coach for one person in \
Pakistan who is losing a lot of weight and has only just started training, at home, with light dumbbells, \
a short bar and bodyweight exercises. They eat Pakistani/desi food. Be warm and direct, use plain English \
(a little Roman Urdu is fine), keep it short, celebrate consistency over perfection, and never shame. \
Never suggest crash diets, skipping meals, fasting protocols, supplements or medication; for pain, dizziness, \
chest symptoms or anything medical, tell them to stop and see a doctor."""


# ---------------------------------------------------------------- meal parsing

MEAL_SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "required": ["items", "note"],
    "properties": {
        "items": {
            "type": "array",
            "items": {
                "type": "object",
                "additionalProperties": False,
                "required": ["foodId", "name", "quantity", "unit", "kcal", "protein", "carbs", "fat"],
                "properties": {
                    "foodId": {"type": ["string", "null"]},
                    "name": {"type": "string"},
                    "quantity": {"type": "number"},
                    "unit": {"type": "string"},
                    "kcal": {"type": "number"},
                    "protein": {"type": "number"},
                    "carbs": {"type": "number"},
                    "fat": {"type": "number"},
                },
            },
        },
        "note": {"type": "string"},
    },
}

MEAL_SYSTEM = """You turn a description or photo of a meal into food log items for a calorie tracker.

The user's food list is given as lines "id | name | unit". For each thing eaten:
- If it matches a food in the list, use that food's id and give the quantity in that food's unit (e.g. 2 \
for two rotis when the unit is "piece", 0.5 for half a bowl). Set kcal, protein, carbs and fat to 0: the app \
fills them in from its own food list.
- If nothing in the list fits, use foodId null, a short clear name, a household unit ("1 plate", "1 cup", \
"1 piece") and your best estimate of the macros for the whole quantity eaten.
Rules: Roman Urdu is common (do = 2, aadha = half, ek = 1, teen = 3, chaar = 4, pao = quarter). \
Assume typical Pakistani home cooking (oil/ghee included) unless told otherwise. Don't invent foods that \
aren't described or visible; if a photo is unclear, give your best guess and say so in the note. \
note: one short sentence (assumptions made, or "" if none). If nothing edible is described, return no items."""


def _num(v: Any, lo: float, hi: float) -> float:
    if isinstance(v, bool) or not isinstance(v, (int, float)):
        raise ValueError("not a number")
    return round(min(hi, max(lo, float(v))), 2)


def parse_meal(payload: dict[str, Any]) -> dict[str, Any]:
    text = str(payload.get("text") or "").strip()[:MAX_MEAL_TEXT]
    image = payload.get("image")
    foods = payload.get("foods") or []
    if not text and not image:
        raise ValueError("describe the meal or add a photo")
    if not isinstance(foods, list) or len(foods) > MAX_CATALOG:
        raise ValueError("bad food list")
    catalog = {str(f["id"]): f for f in foods if isinstance(f, dict) and f.get("id")}
    lines = "\n".join(f"{i} | {f.get('name', '')} | {f.get('unit', '')}" for i, f in catalog.items())

    content: list[dict[str, Any]] = [{"type": "text", "text": f"Food list:\n{lines}\n\nMeal: {text or '(see photo)'}"}]
    if image:
        if not isinstance(image, dict) or image.get("mediaType") not in ("image/jpeg", "image/png", "image/webp"):
            raise ValueError("photo must be JPEG, PNG or WebP")
        data = str(image.get("data") or "")
        if not data or len(data) > MAX_IMAGE_B64:
            raise ValueError("photo is too large")
        content.append({"type": "image_url", "image_url": {"url": f"data:{image['mediaType']};base64,{data}"}})

    def check(ans: Any) -> dict[str, Any]:
        items = []
        for it in ans["items"][:20]:
            fid = it.get("foodId")
            fid = fid if isinstance(fid, str) and fid in catalog else None
            item = {
                "foodId": fid,
                "name": str(catalog[fid].get("name") if fid else it["name"])[:80],
                "unit": str(catalog[fid].get("unit") if fid else it["unit"])[:40] or "serving",
                "quantity": _num(it["quantity"], 0.05, 50),
            }
            # Matched foods: the phone computes macros from its own food list, so none are returned.
            for k, hi in (("kcal", 5000), ("protein", 400), ("carbs", 800), ("fat", 400)):
                item[k] = None if fid else _num(it[k], 0, hi)
            items.append(item)
        return {"items": items, "note": str(ans.get("note") or "")[:300]}

    return llm.json_call(MEAL_SYSTEM, [{"role": "user", "content": content}], MEAL_SCHEMA, check,
                         max_tokens=1500, effort="low")


# ---------------------------------------------------------------- weekly review

REVIEW_SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "required": ["summary", "wins", "concerns", "proposals", "tip"],
    "properties": {
        "summary": {"type": "string"},
        "wins": {"type": "array", "items": {"type": "string"}},
        "concerns": {"type": "array", "items": {"type": "string"}},
        "proposals": {
            "type": "array",
            "items": {
                "type": "object",
                "additionalProperties": False,
                "required": ["kind", "target", "value", "reason"],
                "properties": {
                    "kind": {"type": "string", "enum": list(PROPOSAL_KINDS)},
                    "target": {"type": ["string", "null"]},
                    "value": {"type": ["integer", "null"]},
                    "reason": {"type": "string"},
                },
            },
        },
        "tip": {"type": "string"},
    },
}

REVIEW_SYSTEM = COACH_VOICE + """

Write this week's review from the summary of their logged data. The app already adjusts everything \
automatically each week (calorie target from intake vs weight trend, strength via double progression, \
cardio volume up ≤10%/week with a lighter 4th week), so only propose a change when the data clearly says \
the automatic plan is wrong for them, e.g. repeated "hard" ratings or missed reps → easier; everything rated \
"easy" for 2+ weeks → harder; weight not moving for 3+ weeks with consistent logging → lower calories; losing \
more than 1% of bodyweight a week or feeling wiped out → higher calories. Zero proposals is a normal, good week.

Proposal kinds:
- calorie_target: value = new daily kcal target (the app clamps it to ±150 of the current target and the safety floor).
- exercise_easier / exercise_harder: target = ladder id (push, squat, lunge, hinge, row, press, core-a, core-b); value null.
- cardio_easier / cardio_harder: target null, value null.

summary: 2–3 sentences on how the week went. wins and concerns: 0–3 short items each, specific and based on \
the numbers (no generic advice). tip: one concrete, practical tip for next week (desi food swaps, scheduling \
walks, sleep, form). If little was logged, say so kindly and make the tip about logging."""


def _check_review(ans: Any) -> dict[str, Any]:
    proposals = []
    for p in ans["proposals"][:4]:
        kind = p.get("kind")
        if kind not in PROPOSAL_KINDS:
            continue
        target, value = p.get("target"), p.get("value")
        if kind == "calorie_target":
            if not isinstance(value, int) or not 1000 <= value <= 5000:
                continue
            target = None
        elif kind.startswith("exercise_"):
            if target not in context.LADDERS:
                continue
            value = None
        else:
            target, value = None, None
        proposals.append({"id": uuid.uuid4().hex[:12], "kind": kind, "target": target, "value": value,
                          "reason": str(p.get("reason") or "")[:300], "status": "pending"})
    return {
        "summary": str(ans["summary"])[:1000],
        "wins": [str(w)[:200] for w in ans["wins"][:3]],
        "concerns": [str(c)[:200] for c in ans["concerns"][:3]],
        "proposals": proposals,
        "tip": str(ans["tip"])[:400],
    }


def weekly_review(today: int | None = None) -> dict[str, Any]:
    """Build the review for the week starting this Monday, store it (it syncs to the phone) and return it."""
    today = today if today is not None else context.today_epoch_day()
    ctx = context.build(today)
    review = llm.json_call(
        REVIEW_SYSTEM,
        [{"role": "user", "content": "Data summary (JSON):\n" + json.dumps(ctx, ensure_ascii=False)}],
        REVIEW_SCHEMA, _check_review, max_tokens=3000, effort="medium",
    )
    week = context.monday(today)
    body = {**review, "period": ctx["period"], "model": llm.model()}
    return store.put_record("CoachReview", {
        "id": str(week), "weekStart": week, "createdAt": int(time.time() * 1000),
        "body": json.dumps(body, ensure_ascii=False),
    })


# ---------------------------------------------------------------- chat

CHAT_SYSTEM = COACH_VOICE + """

You're chatting in the app. Answer in a few short paragraphs or a short list; plain text, no markdown \
headings or tables. Use their data summary below when it's relevant. You can't change their plan yourself: \
if they want a change, explain what you'd suggest and tell them the weekly review (Coach tab) can propose \
it, or where in the app to change it (Settings → Profile for training days and equipment)."""


def chat(payload: dict[str, Any], today: int | None = None) -> list[dict[str, Any]]:
    text = str(payload.get("text") or "").strip()
    msg_id = payload.get("id")
    if not text:
        raise ValueError("empty message")
    if len(text) > MAX_CHAT_TEXT:
        raise ValueError("message too long")
    if not isinstance(msg_id, str) or not 8 <= len(msg_id) <= 64 or "#" in msg_id:
        raise ValueError("bad id")
    today = today if today is not None else int(payload.get("today") or context.today_epoch_day())

    history = sorted(store.records("CoachMessage"), key=lambda m: m.get("createdAt", 0))[-CHAT_HISTORY:]
    ctx = context.build(today)
    system = CHAT_SYSTEM + "\n\nTheir data summary (JSON):\n" + json.dumps(ctx, ensure_ascii=False)
    messages = [{"role": "assistant" if m.get("role") == "coach" else "user", "content": str(m.get("text") or "")}
                for m in history]
    messages.append({"role": "user", "content": text})
    answer, _ = llm.chat(system, messages, max_tokens=1200, effort="low")
    answer = answer.strip() or "Sorry, I couldn't come up with an answer. Try asking again?"

    now = int(time.time() * 1000)
    mine = store.put_record("CoachMessage", {"id": msg_id, "createdAt": now, "role": "user", "text": text}, now)
    reply = store.put_record("CoachMessage", {"id": uuid.uuid4().hex, "createdAt": now + 1, "role": "coach",
                                              "text": answer}, now + 1)
    return [mine, reply]
