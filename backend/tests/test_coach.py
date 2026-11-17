import json

import boto3
import pytest

from test_api import TOKEN, api, call, change, pull_all  # noqa: F401  (fixture re-export)

TODAY = 20_402  # Monday 10 Nov 2025


@pytest.fixture
def coach_api(api, monkeypatch):  # noqa: F811
    from momentum_api import llm
    boto3.client("ssm").put_parameter(Name="/momentum/openrouter-api-key", Value="sk-or-test", Type="SecureString")
    llm.reset_cache()
    calls = []
    answers = []

    def fake_post(body, timeout):
        calls.append(body)
        a = answers.pop(0)
        if isinstance(a, Exception):
            raise a
        return {"choices": [{"message": {"content": a if isinstance(a, str) else json.dumps(a)},
                             "finish_reason": "stop"}], "usage": {"prompt_tokens": 10, "completion_tokens": 5}}

    monkeypatch.setattr(llm, "_post", fake_post)
    api.calls, api.answers = calls, answers
    return api


FOODS = [{"id": "roti", "name": "Roti (chapati)", "unit": "1 roti"},
         {"id": "chai", "name": "Doodh patti chai", "unit": "1 cup"}]


def test_monday_and_today():
    from momentum_api import context
    assert context.monday(TODAY) == TODAY
    assert context.monday(TODAY + 6) == TODAY
    assert context.day_name(TODAY) == "Mon 10 Nov"


def test_meal_matches_catalog_and_cleans_answer(coach_api):
    coach_api.answers.append({"items": [
        {"foodId": "roti", "name": "x", "quantity": 2, "unit": "x", "kcal": 240, "protein": 8, "carbs": 46, "fat": 2},
        {"foodId": "made-up", "name": "Qeema", "quantity": 0.5, "unit": "1 bowl", "kcal": 250, "protein": 18,
         "carbs": 5, "fat": 18},
        {"foodId": None, "name": "Huge", "quantity": 999, "unit": "plate", "kcal": 99999, "protein": 1, "carbs": 1, "fat": 1},
    ], "note": "Assumed home-made qeema."})
    status, body = call(coach_api, "POST", "/coach/meal", {"text": "do roti, aadha bowl qeema", "foods": FOODS})
    assert status == 200
    roti, qeema, huge = body["items"]
    assert roti == {"foodId": "roti", "name": "Roti (chapati)", "unit": "1 roti", "quantity": 2.0, "kcal": None,
                    "protein": None, "carbs": None, "fat": None, "branded": False}
    assert qeema["foodId"] is None and qeema["name"] == "Qeema" and qeema["kcal"] == 250
    assert huge["quantity"] == 50 and huge["kcal"] == 5000
    sent = coach_api.calls[0]
    assert sent["model"] == "anthropic/claude-haiku-5.5"
    assert sent["provider"]["data_collection"] == "deny"
    assert "roti | Roti (chapati) | 1 roti" in sent["messages"][1]["content"][0]["text"]


def test_meal_photo_is_sent_as_image(coach_api):
    coach_api.answers.append({"items": [], "note": ""})
    status, _ = call(coach_api, "POST", "/coach/meal",
                     {"image": {"mediaType": "image/jpeg", "data": "QUJD"}, "foods": FOODS})
    assert status == 200
    parts = coach_api.calls[0]["messages"][1]["content"]
    assert parts[1]["image_url"]["url"] == "data:image/jpeg;base64,QUJD"


def test_meal_retries_once_on_bad_json(coach_api):
    coach_api.answers += ["not json", {"items": [], "note": "ok"}]
    status, body = call(coach_api, "POST", "/coach/meal", {"text": "chai", "foods": FOODS})
    assert status == 200 and body["note"] == "ok"
    assert len(coach_api.calls) == 2


def test_meal_gives_502_when_model_keeps_failing(coach_api):
    coach_api.answers += ["nope", "still nope"]
    status, body = call(coach_api, "POST", "/coach/meal", {"text": "chai", "foods": FOODS})
    assert status == 502 and "unavailable" in body["error"]


def test_meal_validation(coach_api):
    assert call(coach_api, "POST", "/coach/meal", {"foods": FOODS})[0] == 400
    assert call(coach_api, "POST", "/coach/meal", {"image": {"mediaType": "image/gif", "data": "x"}})[0] == 400
    assert call(coach_api, "POST", "/coach/meal", {"text": "x"}, token="WRONG")[0] == 401
    assert coach_api.calls == []


def seed(app):
    day = TODAY
    rows = [change("me", 1, {"id": "me", "sex": "MALE", "birthYear": 1995, "heightCm": 175, "startWeightKg": 110,
                             "goalWeightKg": 80, "trainingDays": "1,3,5", "dumbbellLoads": "2,3,4,5", "barLoads": "10",
                             "startEpochDay": day - 21, "targetKcal": 1700}, type_="Profile")]
    for i in range(28):
        rows.append(change(f"w{i}", 1, {"id": f"w{i}", "epochDay": day - 28 + i, "kg": 110 - i * 0.1}, type_="WeightEntry"))
    for i in range(7):
        rows.append(change(f"f{i}", 1, {"id": f"f{i}", "epochDay": day - 7 + i, "name": "Roti", "kcal": 1600,
                                        "protein": 90}, type_="FoodLog"))
    rows.append(change("s1", 1, {"id": "s1", "epochDay": day - 3, "sessionKey": "strength-a", "endedAt": 5},
                       type_="WorkoutSession"))
    rows.append(change("x1", 1, {"id": "x1", "epochDay": day - 3, "exerciseId": "knee-push-up", "ladder": "push",
                                 "setIndex": 0, "loadKg": 0, "reps": 12, "seconds": 0, "rating": "EASY"}, type_="SetLog"))
    rows.append(change("push", 1, {"id": "push", "step": 1, "loadKg": 0, "stage": 1, "misses": 0, "holdSec": 20},
                       type_="ExerciseState"))
    rows.append(change("c1", 1, {"id": "c1", "epochDay": day - 2, "kind": "walk", "durationSec": 1800,
                                 "distanceM": 2500, "avgHr": 110}, type_="CardioSession"))
    rows.append(change("old", 1, {"id": "old", "epochDay": day - 30, "kind": "walk", "durationSec": 600},
                       type_="CardioSession"))
    assert call(app, "POST", "/sync/push", {"changes": rows})[0] == 200


def test_context_summarises_the_week(coach_api):
    from momentum_api import context
    seed(coach_api)
    ctx = context.build(TODAY)
    assert ctx["profile"]["trainingWeek"] == 4 and ctx["profile"]["calorieTarget"] == 1700
    assert ctx["weight"]["changeLast7DaysKg"] < 0
    assert ctx["food"]["daysLogged"] == 7 and ctx["food"]["avgKcalOnLoggedDays"] == 1600
    assert ctx["strength"]["sessionsDone"] == ["Fri 07 Nov: strength-a"]
    assert ctx["strength"]["exercises"]["knee-push-up"] == ["Fri 07 Nov 12 reps (easy)"]
    assert ctx["ladders"]["push"]["currentExercise"] == "knee-push-up"
    assert ctx["cardio"]["totalMinutes"] == 30  # the 30-day-old walk is outside the week
    assert ctx["period"] == "Mon 03 Nov to Sun 09 Nov"
    with_today = context.build(TODAY, include_today=True)
    assert with_today["period"].startswith("Tue 04 Nov to Mon 10 Nov")


def test_review_is_stored_and_synced(coach_api, monkeypatch):
    seed(coach_api)
    coach_api.answers.append({
        "summary": "Solid week.", "wins": ["7/7 days logged"], "concerns": [], "tip": "Walk after dinner.",
        "proposals": [
            {"kind": "exercise_harder", "target": "push", "value": None, "reason": "All easy."},
            {"kind": "exercise_harder", "target": "bench", "value": None, "reason": "bad ladder"},
            {"kind": "calorie_target", "target": None, "value": 400, "reason": "too low, dropped"},
            {"kind": "cardio_easier", "target": "x", "value": 3, "reason": "Tired."},
        ]})
    status, body = call(coach_api, "POST", "/coach/review", {"today": TODAY})
    assert status == 200
    review = body["review"]
    assert review["id"] == str(TODAY) and review["weekStart"] == TODAY and review["dirty"] is False
    parsed = json.loads(review["body"])
    assert [p["kind"] for p in parsed["proposals"]] == ["exercise_harder", "cardio_easier"]
    assert parsed["proposals"][1]["target"] is None and parsed["proposals"][1]["value"] is None
    assert all(p["status"] == "pending" for p in parsed["proposals"])
    status, pulled = pull_all(coach_api, monkeypatch)
    assert any(c["type"] == "CoachReview" and c["id"] == str(TODAY) for c in pulled["changes"])


def test_scheduled_review(coach_api, monkeypatch):
    from momentum_api import app, context
    monkeypatch.setattr(context, "today_epoch_day", lambda now=None: TODAY + 1)
    coach_api.answers.append({"summary": "s", "wins": [], "concerns": [], "proposals": [], "tip": "t"})
    assert app.handler({"action": "weekly-review"}, None) == {"ok": True, "week": str(TODAY)}


def test_chat_stores_both_messages_and_sends_history(coach_api, monkeypatch):
    coach_api.answers += ["Pehle hello!", "Try a walk after dinner."]
    status, body = call(coach_api, "POST", "/coach/chat", {"id": "msg-00000001", "text": "Hi", "today": TODAY})
    assert status == 200
    mine, reply = body["messages"]
    assert mine["id"] == "msg-00000001" and mine["role"] == "user" and reply["role"] == "coach"
    call(coach_api, "POST", "/coach/chat", {"id": "msg-00000002", "text": "Evening tips?", "today": TODAY})
    sent = coach_api.calls[1]["messages"]
    assert [m["role"] for m in sent] == ["system", "user", "assistant", "user"]
    assert sent[2]["content"] == "Pehle hello!"
    _, pulled = pull_all(coach_api, monkeypatch)
    assert sum(c["type"] == "CoachMessage" for c in pulled["changes"]) == 4


def test_chat_validation(coach_api):
    assert call(coach_api, "POST", "/coach/chat", {"id": "msg-00000001", "text": ""})[0] == 400
    assert call(coach_api, "POST", "/coach/chat", {"id": "a#b-000000", "text": "hi"})[0] == 400
    assert call(coach_api, "POST", "/coach/chat", {"id": "msg-00000001", "text": "hi", "today": "x"})[0] == 400


BURGER = {"foodId": "chicken-burger", "name": "Dawn crispy fillet burger", "quantity": 1, "unit": "piece",
          "kcal": 480, "protein": 22, "carbs": 45, "fat": 22, "lookup": "Dawn chicken crispy fillet nutrition Pakistan"}
FOODS_WITH_BURGER = FOODS + [{"id": "chicken-burger", "name": "Chicken burger", "unit": "piece"}]


def test_branded_item_is_looked_up_online(coach_api):
    roti = {"foodId": "roti", "name": "x", "quantity": 1, "unit": "x", "kcal": 0, "protein": 0, "carbs": 0, "fat": 0,
            "lookup": None}
    coach_api.answers += [
        {"items": [roti, BURGER], "note": "Assumed one tablespoon of mayo."},
        {"items": [{"index": 1, "kcal": 520, "protein": 25, "carbs": 48, "fat": 24, "source": "carrefour.pk"},
                   {"index": 0, "kcal": 1, "protein": 1, "carbs": 1, "fat": 1, "source": "nope"}]},  # not a lookup item
    ]
    status, body = call(coach_api, "POST", "/coach/meal", {"text": "roti and a dawn burger", "foods": FOODS_WITH_BURGER})
    assert status == 200
    roti_out, burger = body["items"]
    assert roti_out["foodId"] == "roti" and roti_out["kcal"] is None
    # branded: never mapped onto the generic list item, and the online figures replace the estimate
    assert burger["foodId"] is None and burger["name"] == "Dawn crispy fillet burger"
    assert burger["kcal"] == 520 and burger["source"] == "carrefour.pk"
    assert body["note"] == "Assumed one tablespoon of mayo. Looked up online: Dawn crispy fillet burger: carrefour.pk."
    first, second = coach_api.calls
    assert "plugins" not in first
    assert second["plugins"] == [{"id": "web", "engine": "native", "max_results": 4}]
    assert "1. Dawn crispy fillet burger" in second["messages"][1]["content"]


def test_failed_lookup_keeps_the_estimate(coach_api):
    coach_api.answers += [{"items": [BURGER], "note": ""}, "not json"]
    status, body = call(coach_api, "POST", "/coach/meal", {"text": "dawn burger", "foods": FOODS_WITH_BURGER})
    assert status == 200
    assert body["items"][0]["kcal"] == 480 and "source" not in body["items"][0]
    assert body["note"] == "Couldn't find Dawn crispy fillet burger online, so that's an estimate."
    assert body["items"][0]["branded"] is True
    assert len(coach_api.calls) == 2  # the lookup isn't retried


def test_lookups_are_capped(coach_api):
    from momentum_api import coach
    coach_api.answers += [{"items": [dict(BURGER, name=f"Burger {i}") for i in range(5)], "note": ""},
                          {"items": []}]
    call(coach_api, "POST", "/coach/meal", {"text": "five burgers", "foods": FOODS_WITH_BURGER})
    asked = coach_api.calls[1]["messages"][1]["content"]
    assert asked.count("search:") == coach.MAX_LOOKUPS


def test_ask_compares_options_with_budget(coach_api):
    coach_api.answers.append({"options": [
        {"foodId": "roti", "name": "x", "quantity": 2, "unit": "x", "kcal": 0, "protein": 0, "carbs": 0, "fat": 0, "lookup": None},
        {"foodId": None, "name": "Chicken biryani plate", "quantity": 1, "unit": "plate", "kcal": 750, "protein": 30,
         "carbs": 90, "fat": 28, "lookup": None}], "best": 0, "answer": "Go for the rotis."})
    foods = [dict(f, kcal=120, protein=4) for f in FOODS]
    status, body = call(coach_api, "POST", "/coach/ask",
                        {"question": "2 roti or biryani?", "foods": foods, "kcalLeft": 800, "proteinLeft": 60})
    assert status == 200
    assert body["best"] == 0 and body["answer"] == "Go for the rotis."
    assert body["options"][0]["foodId"] == "roti" and body["options"][1]["kcal"] == 750
    text = coach_api.calls[0]["messages"][1]["content"][0]["text"]
    assert "roti | Roti (chapati) | 1 roti | 120 kcal, 4 g protein per unit" in text
    assert text.endswith("Left for today: 800 kcal, 60 g protein")


def test_ask_rewrites_answer_after_lookup(coach_api):
    coach_api.answers += [
        {"options": [BURGER], "best": 5, "answer": "About 480 kcal."},
        {"items": [{"index": 0, "kcal": 610, "protein": 30, "carbs": 50, "fat": 30, "source": "kfcpakistan.com"}]},
        "About 610 kcal; fits if you keep dinner light.",
    ]
    status, body = call(coach_api, "POST", "/coach/ask", {"question": "dawn burger calories?", "foods": FOODS})
    assert status == 200
    assert body["best"] is None  # out of range
    assert body["options"][0]["kcal"] == 610
    assert body["answer"] == ("About 610 kcal; fits if you keep dinner light. "
                              "Looked up online: Dawn crispy fillet burger: kfcpakistan.com.")
    assert "Final numbers:\n0. Dawn crispy fillet burger: 610 kcal" in coach_api.calls[2]["messages"][1]["content"]


def test_ask_needs_a_question(coach_api):
    assert call(coach_api, "POST", "/coach/ask", {"foods": FOODS})[0] == 400
