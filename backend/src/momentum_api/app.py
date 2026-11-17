"""Momentum API: one Lambda behind an API Gateway HTTP API (payload format 2.0).

Routes
  GET  /health        liveness, no auth
  POST /sync/push     {changes: [{type, id, updatedAt, deleted, data}]} -> {applied: [ids], stale: [ids]}
  GET  /sync/pull     ?since=<cursor>&limit=<n> -> {changes: [...], cursor, hasMore}
"""

import base64
import json
import logging
from typing import Any

from . import auth, store

log = logging.getLogger()
log.setLevel(logging.INFO)

MAX_PUSH = 200
MAX_PULL = 500
MAX_ID_LEN = 64
MAX_RECORD_BYTES = 32_000


def _resp(status: int, body: dict[str, Any]) -> dict[str, Any]:
    return {"statusCode": status, "headers": {"content-type": "application/json"},
            "body": json.dumps(body, ensure_ascii=False)}


def _body(event: dict[str, Any]) -> Any:
    raw = event.get("body") or ""
    if event.get("isBase64Encoded"):
        raw = base64.b64decode(raw).decode()
    return json.loads(raw) if raw else {}


def _parse_change(raw: Any) -> store.Change:
    if not isinstance(raw, dict):
        raise ValueError("change must be an object")
    t, i, u = raw.get("type"), raw.get("id"), raw.get("updatedAt")
    data = raw.get("data") or {}
    if t not in store.SYNC_TYPES:
        raise ValueError(f"unknown type {t!r}")
    if not isinstance(i, str) or not i or len(i) > MAX_ID_LEN or "#" in i:
        raise ValueError("bad id")
    if not isinstance(u, int) or isinstance(u, bool) or u <= 0:
        raise ValueError("bad updatedAt")
    if not isinstance(data, dict):
        raise ValueError("data must be an object")
    if len(json.dumps(data)) > MAX_RECORD_BYTES:
        raise ValueError("record too large")
    return store.Change(type=t, id=i, updated_at=u, deleted=bool(raw.get("deleted", False)), data=data)


def push(event: dict[str, Any]) -> dict[str, Any]:
    try:
        payload = _body(event)
        raw_changes = payload.get("changes")
        if not isinstance(raw_changes, list):
            raise ValueError("changes must be a list")
        if len(raw_changes) > MAX_PUSH:
            raise ValueError(f"at most {MAX_PUSH} changes per push")
        changes = [_parse_change(c) for c in raw_changes]
    except (ValueError, json.JSONDecodeError) as e:
        return _resp(400, {"error": str(e)})
    applied, stale = [], []
    for c in changes:
        (applied if store.put_change(c) else stale).append(c.id)
    return _resp(200, {"applied": applied, "stale": stale})


def pull(event: dict[str, Any]) -> dict[str, Any]:
    qs = event.get("queryStringParameters") or {}
    cursor = qs.get("since", "")
    if cursor and (len(cursor) > 200 or not cursor[:15].isdigit()):
        return _resp(400, {"error": "bad cursor"})
    try:
        limit = max(1, min(MAX_PULL, int(qs.get("limit", MAX_PULL))))
    except ValueError:
        return _resp(400, {"error": "bad limit"})
    changes, new_cursor, has_more = store.changes_since(cursor, limit)
    return _resp(200, {"changes": [c.to_json() for c in changes], "cursor": new_cursor, "hasMore": has_more})


ROUTES = {
    ("POST", "/sync/push"): push,
    ("GET", "/sync/pull"): pull,
}


def handler(event: dict[str, Any], _context: Any) -> dict[str, Any]:
    http = event.get("requestContext", {}).get("http", {})
    method, path = http.get("method", ""), event.get("rawPath", "")
    if (method, path) == ("GET", "/health"):
        return _resp(200, {"ok": True})
    route = ROUTES.get((method, path))
    if route is None:
        return _resp(404, {"error": "not found"})
    headers = {k.lower(): v for k, v in (event.get("headers") or {}).items()}
    if not auth.is_authorized(headers):
        return _resp(401, {"error": "unauthorized"})
    try:
        return route(event)
    except Exception:
        log.exception("unhandled error on %s %s", method, path)  # no request bodies in logs
        return _resp(500, {"error": "internal error"})
