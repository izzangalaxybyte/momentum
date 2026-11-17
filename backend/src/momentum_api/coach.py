"""The AI coach: meal parsing, weekly reviews and chat.

The model only suggests. Meal items are confirmed by the user before they're logged, and review
proposals are clamped by the phone's own rules (calorie floors, ±150 kcal a week, ≤10% cardio growth)
and applied only when the user taps Accept.
"""

import json
import logging
import time
import uuid
from typing import Any

from . import context, llm, store

log = logging.getLogger()

MAX_MEAL_TEXT = 1000
MAX_IMAGE_B64 = 2_500_000
MAX_CATALOG = 800
MAX_CHAT_TEXT = 2000
CHAT_HISTORY = 20
PROPOSAL_KINDS = ("calorie_target", "exercise_easier", "exercise_harder", "cardio_easier", "cardio_harder")

COACH_VOICE = """You are Momentum's coach: a friendly, practical fitness and nutrition coach for one person in \
Pakistan who is losing a lot of weight and has only just started training, at home, with light dumbbells, \
a short bar and bodyweight exercises. They eat Pakistani/desi food. Be warm and direct and keep it short. \
Write in plain English (an occasional Roman Urdu word is fine, but not whole sentences unless they write \
to you in Urdu), celebrate consistency over perfection, and never shame. \
Never suggest crash diets, skipping meals, fasting protocols, supplements or medication; for pain, dizziness, \
chest symptoms or anything medical, tell them to stop and see a doctor."""


# ---------------------------------------------------------------- meal parsing

MAX_LOOKUPS = 3  # branded items looked up online per meal (each web search costs ~1 cent)

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
                "required": ["foodId", "name", "quantity", "unit", "kcal", "protein", "carbs", "fat", "lookup"],
                "properties": {
                    "foodId": {"type": ["string", "null"]},
                    "name": {"type": "string"},
                    "quantity": {"type": "number"},
                    "unit": {"type": "string"},
                    "kcal": {"type": "number"},
                    "protein": {"type": "number"},
                    "carbs": {"type": "number"},
                    "fat": {"type": "number"},
                    "lookup": {"type": ["string", "null"]},
                },
            },
        },
        "note": {"type": "string"},
    },
}

MEAL_SYSTEM = """You turn a description or photo of a meal into food log items for a calorie tracker.

The user's food list is given as lines "id | name | unit". For each thing eaten:
- If the list has that exact item, including the brand (the user's saved foods are in the list, e.g. "Dawn \
crispy fillet burger"), use it like any list food, with lookup null.
- Other branded or restaurant items (a brand, chain or shop is named, e.g. "KFC Zinger", "Savour pulao", "Dawn \
crispy fillet burger"): use foodId null even if the list has a generic version, keep the brand and item name \
as given, estimate the macros for the whole quantity eaten, and set lookup to a short web search query for its \
nutrition facts ("Dawn chicken crispy fillet nutrition Pakistan"). Exception: standard drinks and packaged \
basics that match a list item closely (a can of Coke → the soft drink can) use the list item, no lookup. \
Everything else gets lookup null.
- Otherwise, if it matches a food in the list, use that food's id and give the quantity in that food's unit \
(e.g. 2 for two rotis when the unit is "piece", 0.5 for half a bowl). Set kcal, protein, carbs and fat to 0: \
the app fills them in from its own food list.
- If nothing in the list fits, use foodId null, a short clear name, a household unit ("1 plate", "1 cup", \
"1 piece") and your best estimate of the macros for the whole quantity eaten.
When estimating, think about how it was made: fried or grilled, crumbed, size, cheese, mayo or sauce, bun, \
oil/ghee. Roman Urdu is common (do = 2, aadha = half, ek = 1, teen = 3, chaar = 4, pao = quarter). Assume \
typical Pakistani home cooking (oil/ghee included) unless told otherwise. Don't invent foods that aren't \
described or visible; if a photo is unclear, give your best guess and say so in the note. \
note: one short sentence on the assumptions made (e.g. "Assumed a fried fillet with one tablespoon of mayo"), \
or "" if none. If nothing edible is described, return no items."""

LOOKUP_SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "required": ["items"],
    "properties": {
        "items": {
            "type": "array",
            "items": {
                "type": "object",
                "additionalProperties": False,
                "required": ["index", "kcal", "protein", "carbs", "fat", "source"],
                "properties": {
                    "index": {"type": "integer"},
                    "kcal": {"type": "number"},
                    "protein": {"type": "number"},
                    "carbs": {"type": "number"},
                    "fat": {"type": "number"},
                    "source": {"type": ["string", "null"]},
                },
            },
        },
    },
}

LOOKUP_SYSTEM = """You look up nutrition facts for branded or restaurant foods eaten in Pakistan, for a calorie \
tracker. For each numbered item, search the web (the brand's own site, delivery apps like foodpanda, \
supermarket listings like Carrefour or Naheed, nutrition databases) and give kcal, protein, carbs and fat in \
grams for the WHOLE quantity eaten as described: check the serving size the source uses and scale it, and add \
the parts it doesn't cover (a burger made with a frozen fillet = fillet + bun + mayo/sauce). Sanity-check every \
figure: food is at most ~900 kcal per 100 g, and kcal should be close to 4×protein + 4×carbs + 9×fat; ignore \
listings that fail this. source: the domain you relied on (e.g. "carrefour.pk"), or null if nothing reliable \
turned up, in which case keep the estimate you were given. Return one entry per item, with its index."""


def _num(v: Any, lo: float, hi: float) -> float:
    if isinstance(v, bool) or not isinstance(v, (int, float)):
        raise ValueError("not a number")
    return round(min(hi, max(lo, float(v))), 2)


MACROS = (("kcal", 5000), ("protein", 400), ("carbs", 800), ("fat", 400))


def _look_up(items: list[dict[str, Any]], queries: dict[int, str]) -> str:
    """Replace the estimates of branded items with figures found online, and return a sentence for the note.
    Best effort: on any failure the first estimates stay."""
    lines = "\n".join(f"{i}. {items[i]['name']}, {items[i]['quantity']} × {items[i]['unit']} "
                      f"(estimate {round(items[i]['kcal'] or 0)} kcal) — search: {q}" for i, q in queries.items())

    def check(ans: Any) -> list[dict[str, Any]]:
        out = []
        for r in ans["items"]:
            i = r.get("index")
            if i not in queries:
                continue
            vals = {k: _num(r[k], 0, hi) for k, hi in MACROS}
            if vals["kcal"] <= 0:
                continue
            out.append({"index": i, **vals, "source": str(r["source"])[:60] if r.get("source") else None})
        return out

    try:
        found = llm.json_call(LOOKUP_SYSTEM, [{"role": "user", "content": lines}], LOOKUP_SCHEMA, check,
                              max_tokens=3000, effort="low", web=True, timeout=18, attempts=1)
    except llm.CoachError as e:
        log.warning("meal lookup failed, keeping estimates: %s", e)
        found = []
    sources, missing = [], []
    by_index = {r["index"]: r for r in found if r["source"]}
    for i in queries:
        r = by_index.get(i)
        if r is None:
            missing.append(items[i]["name"])
            continue
        items[i].update({k: r[k] for k, _ in MACROS})
        items[i]["source"] = r["source"]
        sources.append(f"{items[i]['name']}: {r['source']}")
    parts = []
    if sources:
        parts.append("Looked up online: " + "; ".join(sources) + ".")
    if missing:
        parts.append("Couldn't find " + ", ".join(missing) + " online, so that's an estimate.")
    return " ".join(parts)


def _prepare(payload: dict[str, Any], field: str, label: str, max_text: int) -> tuple[dict[str, dict], list[dict]]:
    """Validate text/photo/food list; returns (catalog by id, user message content)."""
    text = str(payload.get(field) or "").strip()[:max_text]
    image = payload.get("image")
    foods = payload.get("foods") or []
    if not text and not image:
        raise ValueError("describe the food or add a photo")
    if not isinstance(foods, list) or len(foods) > MAX_CATALOG:
        raise ValueError("bad food list")
    catalog = {str(f["id"]): f for f in foods if isinstance(f, dict) and f.get("id")}

    def line(i: str, f: dict) -> str:
        extra = ""
        if isinstance(f.get("kcal"), (int, float)):
            extra = f" | {round(f['kcal'])} kcal, {round(f.get('protein') or 0)} g protein per unit"
        return f"{i} | {f.get('name', '')} | {f.get('unit', '')}{extra}"

    lines = "\n".join(line(i, f) for i, f in catalog.items())
    content: list[dict[str, Any]] = [{"type": "text", "text": f"Food list:\n{lines}\n\n{label}: {text or '(see photo)'}"}]
    if image:
        if not isinstance(image, dict) or image.get("mediaType") not in ("image/jpeg", "image/png", "image/webp"):
            raise ValueError("photo must be JPEG, PNG or WebP")
        data = str(image.get("data") or "")
        if not data or len(data) > MAX_IMAGE_B64:
            raise ValueError("photo is too large")
        content.append({"type": "image_url", "image_url": {"url": f"data:{image['mediaType']};base64,{data}"}})
    return catalog, content


def _clean_items(raw: list[Any], catalog: dict[str, dict], queries: dict[int, str]) -> list[dict[str, Any]]:
    """Check the model's items against the food list; fills `queries` with branded items to look up."""
    items = []
    queries.clear()
    for it in raw[:20]:
        lookup = it.get("lookup")
        lookup = str(lookup).strip()[:120] if isinstance(lookup, str) and lookup.strip() else None
        fid = it.get("foodId")
        fid = fid if isinstance(fid, str) and fid in catalog and not lookup else None
        item = {
            "foodId": fid,
            "name": str(catalog[fid].get("name") if fid else it["name"])[:80],
            "unit": str(catalog[fid].get("unit") if fid else it["unit"])[:40] or "serving",
            "quantity": _num(it["quantity"], 0.05, 50),
            "branded": bool(lookup),
        }
        # Matched foods: the phone computes macros from its own food list, so none are returned.
        for k, hi in MACROS:
            item[k] = None if fid else _num(it[k], 0, hi)
        if lookup and len(queries) < MAX_LOOKUPS:
            queries[len(items)] = lookup
        items.append(item)
    return items


def _join(*parts: str) -> str:
    return " ".join(p for p in parts if p)


def parse_meal(payload: dict[str, Any]) -> dict[str, Any]:
    catalog, content = _prepare(payload, "text", "Meal", MAX_MEAL_TEXT)
    queries: dict[int, str] = {}

    def check(ans: Any) -> dict[str, Any]:
        return {"items": _clean_items(ans["items"], catalog, queries), "note": str(ans.get("note") or "")[:300]}

    result = llm.json_call(MEAL_SYSTEM, [{"role": "user", "content": content}], MEAL_SCHEMA, check,
                           max_tokens=1500, effort="low")
    if queries:
        result["note"] = _join(result["note"], _look_up(result["items"], dict(queries)))
    return result


# ---------------------------------------------------------------- ask before eating

ASK_SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "required": ["options", "best", "answer"],
    "properties": {
        "options": MEAL_SCHEMA["properties"]["items"],
        "best": {"type": ["integer", "null"]},
        "answer": {"type": "string"},
    },
}

ASK_SYSTEM = COACH_VOICE + """

They're asking about food BEFORE eating it: how many calories something has, or which of a few choices is \
better for them right now. Their calories and protein left for today are given.

options: the food choice(s) they're asking about, one entry per choice, as items in the same format as a meal \
log (a choice made of several foods, e.g. "biryani with raita", is one entry: name it as a whole, foodId null, \
macros for all of it). For a single food in the food list: its id, quantity in its unit, macros 0 (the app \
fills them in). Otherwise foodId null and your estimate for the quantity they'd typically eat (say what you \
assumed). The rules for branded items and lookup are the same as for meal logging: brand or restaurant named \
and not already in their list → foodId null, keep the name, estimate, lookup = a web search query.
best: index of the option you'd pick for them given what's left today (protein matters, fullness matters), or \
null if there's only one option or they're equivalent.
answer: 2–4 short sentences, plain text: the calories and protein of each option (use the food list's per-unit \
numbers for list foods), how it fits what's left today, and for comparisons which to pick and why. If both \
are heavy, suggest a lighter swap or portion. Double-check every comparison word against the numbers \
("lighter", "more protein"). Don't mention web searches: the app looks branded items up itself. No moralising."""

ANSWER_SYSTEM = COACH_VOICE + """

Rewrite the answer to their food question using the final numbers given (some were just looked up online). \
2–4 short sentences, plain text: calories and protein of each option, how it fits what's left today, and for \
comparisons which to pick and why. Double-check every comparison word against the numbers. Don't mention \
web searches."""


def ask_food(payload: dict[str, Any]) -> dict[str, Any]:
    catalog, content = _prepare(payload, "question", "Question", MAX_MEAL_TEXT)
    budget = []
    for key, label in (("kcalLeft", "kcal"), ("proteinLeft", "g protein")):
        v = payload.get(key)
        if isinstance(v, (int, float)) and not isinstance(v, bool):
            budget.append(f"{round(v)} {label}")
    content[0]["text"] += "\n\nLeft for today: " + (", ".join(budget) if budget else "unknown")
    queries: dict[int, str] = {}

    def check(ans: Any) -> dict[str, Any]:
        options = _clean_items(ans["options"][:4], catalog, queries)
        best = ans.get("best")
        best = best if isinstance(best, int) and not isinstance(best, bool) and 0 <= best < len(options) else None
        answer = str(ans.get("answer") or "").strip()[:800]
        if not answer:
            raise ValueError("empty answer")
        return {"options": options, "best": best, "answer": answer}

    result = llm.json_call(ASK_SYSTEM, [{"role": "user", "content": content}], ASK_SCHEMA, check,
                           max_tokens=4000, effort="medium")
    if queries:
        before = [o["kcal"] for o in result["options"]]
        note = _look_up(result["options"], dict(queries))
        if [o["kcal"] for o in result["options"]] != before:
            final = "\n".join(
                f"{i}. {o['name']}: {round(o['kcal'])} kcal, {round(o['protein'])} g protein"
                + (f" (from {o['source']})" if o.get("source") else "")
                for i, o in enumerate(result["options"]) if o["kcal"] is not None)
            msg = f"{content[0]['text']}\n\nFinal numbers:\n{final}\n\nFirst answer:\n{result['answer']}"
            try:
                text, _ = llm.chat(ANSWER_SYSTEM, [{"role": "user", "content": msg}], max_tokens=600, effort="low")
                result["answer"] = text.strip()[:800] or result["answer"]
            except llm.CoachError as e:
                log.warning("answer rewrite failed: %s", e)
        result["answer"] = _join(result["answer"], note)
    return result


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


def weekly_review(today: int | None = None, include_today: bool = False) -> dict[str, Any]:
    """Build the review for the week starting this Monday, store it (it syncs to the phone) and return it."""
    today = today if today is not None else context.today_epoch_day()
    ctx = context.build(today, include_today=include_today)
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

You're chatting in the app. Keep answers under about 120 words unless they ask for detail: a couple of \
short paragraphs or a short list, plain text, no markdown headings or tables. Use their data summary below \
when it's relevant, and only state facts about them that are in it; don't guess their history. You can't change their plan yourself: \
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
    ctx = context.build(today, include_today=True)
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
