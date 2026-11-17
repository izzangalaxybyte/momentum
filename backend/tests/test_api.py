import json

import boto3
import pytest
from moto import mock_aws

TOKEN = "ABCDE-FGHIJ-KLMNO-PQRST-UVWXY"


@pytest.fixture
def api(monkeypatch):
    monkeypatch.setenv("AWS_DEFAULT_REGION", "eu-central-1")
    monkeypatch.setenv("AWS_ACCESS_KEY_ID", "test")
    monkeypatch.setenv("AWS_SECRET_ACCESS_KEY", "test")
    monkeypatch.setenv("TABLE_NAME", "momentum-data")
    monkeypatch.setenv("TOKEN_HASH_PARAM", "/momentum/api-token-hash")
    with mock_aws():
        ddb = boto3.client("dynamodb")
        ddb.create_table(
            TableName="momentum-data",
            BillingMode="PAY_PER_REQUEST",
            AttributeDefinitions=[{"AttributeName": n, "AttributeType": "S"}
                                  for n in ("pk", "sk", "gsi1pk", "gsi1sk")],
            KeySchema=[{"AttributeName": "pk", "KeyType": "HASH"}, {"AttributeName": "sk", "KeyType": "RANGE"}],
            GlobalSecondaryIndexes=[{
                "IndexName": "gsi1",
                "KeySchema": [{"AttributeName": "gsi1pk", "KeyType": "HASH"},
                              {"AttributeName": "gsi1sk", "KeyType": "RANGE"}],
                "Projection": {"ProjectionType": "ALL"},
            }],
        )
        from momentum_api import app, auth
        boto3.client("ssm").put_parameter(Name="/momentum/api-token-hash", Value=auth.hash_token(TOKEN),
                                          Type="String")
        auth.reset_cache()
        yield app


def call(app, method, path, body=None, qs=None, token=TOKEN):
    event = {
        "rawPath": path,
        "requestContext": {"http": {"method": method}},
        "headers": {"Authorization": f"Bearer {token}"} if token else {},
        "queryStringParameters": qs,
        "body": json.dumps(body) if body is not None else None,
    }
    r = app.handler(event, None)
    return r["statusCode"], json.loads(r["body"])


def change(id_, updated, data=None, type_="FoodLog", deleted=False):
    return {"type": type_, "id": id_, "updatedAt": updated, "deleted": deleted, "data": data or {}}


def pull_all(app, monkeypatch, since=""):
    from momentum_api import store
    monkeypatch.setattr(store, "SETTLE_MS", -10_000)  # tests don't wait for rows to settle
    return call(app, "GET", "/sync/pull", qs={"since": since} if since else None)


def test_health_needs_no_auth(api):
    assert call(api, "GET", "/health", token=None) == (200, {"ok": True})


def test_rejects_missing_and_wrong_token(api):
    assert call(api, "GET", "/sync/pull", token=None)[0] == 401
    assert call(api, "GET", "/sync/pull", token="WRONG-TOKEN")[0] == 401


def test_token_is_case_and_dash_insensitive(api):
    assert call(api, "GET", "/sync/pull", token="abcdefghijklmnopqrstuvwxy")[0] == 200


def test_push_then_pull_round_trip(api, monkeypatch):
    status, body = call(api, "POST", "/sync/push", {"changes": [
        change("a", 100, {"kcal": 120.5, "name": "roti"}),
        change("w1", 100, {"kg": 101.2}, type_="WeightEntry"),
    ]})
    assert status == 200 and sorted(body["applied"]) == ["a", "w1"] and body["stale"] == []
    status, body = pull_all(api, monkeypatch)
    assert status == 200
    by_id = {c["id"]: c for c in body["changes"]}
    assert by_id["a"]["data"] == {"kcal": 120.5, "name": "roti"}
    assert by_id["w1"]["type"] == "WeightEntry"
    assert body["cursor"] and body["hasMore"] is False


def test_last_write_wins(api, monkeypatch):
    call(api, "POST", "/sync/push", {"changes": [change("a", 200, {"v": 2})]})
    _, body = call(api, "POST", "/sync/push", {"changes": [change("a", 100, {"v": 1})]})
    assert body == {"applied": [], "stale": ["a"]}
    _, body = call(api, "POST", "/sync/push", {"changes": [change("a", 300, {"v": 3})]})
    assert body["applied"] == ["a"]
    _, body = pull_all(api, monkeypatch)
    assert [c["data"] for c in body["changes"]] == [{"v": 3}]


def test_cursor_returns_only_newer_changes(api, monkeypatch):
    from momentum_api import store
    store.put_change(store.Change("FoodLog", "a", 1, False, {}), now_ms=1_000)
    _, body = pull_all(api, monkeypatch)
    cursor = body["cursor"]
    store.put_change(store.Change("FoodLog", "b", 1, False, {}), now_ms=2_000)
    _, body = pull_all(api, monkeypatch, since=cursor)
    assert [c["id"] for c in body["changes"]] == ["b"]


def test_recent_rows_wait_to_settle(api):
    call(api, "POST", "/sync/push", {"changes": [change("a", 1)]})
    _, body = call(api, "GET", "/sync/pull")
    assert body["changes"] == []


def test_tombstones_sync(api, monkeypatch):
    call(api, "POST", "/sync/push", {"changes": [change("a", 1, {"x": 1})]})
    call(api, "POST", "/sync/push", {"changes": [change("a", 2, deleted=True)]})
    _, body = pull_all(api, monkeypatch)
    assert body["changes"][0]["deleted"] is True


@pytest.mark.parametrize("bad", [
    {"changes": "nope"},
    {"changes": [change("a", 1, type_="Nope")]},
    {"changes": [change("a#b", 1)]},
    {"changes": [change("a", 0)]},
    {"changes": [change("a", 1, data={"big": "x" * 40_000})]},
    {"changes": [change(str(i), 1) for i in range(201)]},
])
def test_push_validation(api, bad):
    assert call(api, "POST", "/sync/push", bad)[0] == 400


def test_unknown_route(api):
    assert call(api, "GET", "/nope")[0] == 404
