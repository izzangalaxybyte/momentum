"""DynamoDB storage for synced records.

Single table:
  pk      USER#<user>
  sk      <TYPE>#<id>
  gsi1pk  USER#<user>
  gsi1sk  <serverUpdatedAt, 15 digits>#<TYPE>#<id>   -> "what changed since cursor"
  type, id, updatedAt (client ms, conflict resolution), serverUpdatedAt (server ms, pull cursor),
  deleted, data (JSON string), expiresAt (TTL, tombstones only)
"""

import json
import os
import time
from dataclasses import dataclass
from typing import Any

import boto3
from boto3.dynamodb.conditions import Key
from botocore.exceptions import ClientError

USER = "me"  # single-user app; becomes the Cognito subject if users are ever added

SYNC_TYPES = frozenset({
    "Profile", "Equipment", "WeightEntry", "Food", "FoodLog", "ExerciseState", "WorkoutSession",
    "SetLog", "CardioSession", "DailyActivity", "PlannedDay", "Setting", "CoachReview", "CoachMessage",
})

TOMBSTONE_TTL_DAYS = 180
# Pull never returns rows written in the last few seconds, so a write that commits slightly out of
# order (two concurrent Lambda invocations) can't slip behind a cursor the client already holds.
SETTLE_MS = 3000


@dataclass(frozen=True)
class Change:
    type: str
    id: str
    updated_at: int
    deleted: bool
    data: dict[str, Any]

    def to_json(self) -> dict[str, Any]:
        return {"type": self.type, "id": self.id, "updatedAt": self.updated_at,
                "deleted": self.deleted, "data": self.data}


def _table():
    return boto3.resource("dynamodb").Table(os.environ["TABLE_NAME"])


def _now_ms() -> int:
    return int(time.time() * 1000)


def put_change(change: Change, now_ms: int | None = None) -> bool:
    """Last-write-wins upsert. Returns False when the stored row is newer (or equal) than the change."""
    server_ms = now_ms if now_ms is not None else _now_ms()
    item: dict[str, Any] = {
        "pk": f"USER#{USER}",
        "sk": f"{change.type}#{change.id}",
        "gsi1pk": f"USER#{USER}",
        "gsi1sk": f"{server_ms:015d}#{change.type}#{change.id}",
        "type": change.type,
        "id": change.id,
        "updatedAt": change.updated_at,
        "serverUpdatedAt": server_ms,
        "deleted": change.deleted,
        "data": json.dumps(change.data, separators=(",", ":"), ensure_ascii=False),
    }
    if change.deleted:
        item["expiresAt"] = server_ms // 1000 + TOMBSTONE_TTL_DAYS * 86400
    try:
        _table().put_item(
            Item=item,
            ConditionExpression="attribute_not_exists(pk) OR updatedAt < :u",
            ExpressionAttributeValues={":u": change.updated_at},
        )
        return True
    except ClientError as e:
        if e.response["Error"]["Code"] == "ConditionalCheckFailedException":
            return False
        raise


def changes_since(cursor: str, limit: int, now_ms: int | None = None) -> tuple[list[Change], str, bool]:
    """Rows changed after `cursor` (a gsi1sk value, "" for everything), oldest first."""
    upper = f"{(now_ms if now_ms is not None else _now_ms()) - SETTLE_MS:015d}"
    if cursor and cursor >= upper:
        return [], cursor, False
    cond = Key("gsi1pk").eq(f"USER#{USER}")
    cond &= Key("gsi1sk").between(cursor + "\x00", upper) if cursor else Key("gsi1sk").lt(upper)
    resp = _table().query(IndexName="gsi1", KeyConditionExpression=cond, Limit=limit, ScanIndexForward=True)
    items = resp.get("Items", [])
    changes = [
        Change(type=i["type"], id=i["id"], updated_at=int(i["updatedAt"]), deleted=bool(i["deleted"]),
               data=json.loads(i["data"]))
        for i in items
    ]
    new_cursor = items[-1]["gsi1sk"] if items else cursor
    return changes, new_cursor, "LastEvaluatedKey" in resp


def records(type_: str) -> list[dict[str, Any]]:
    """Every live (not deleted) record of one type, as the client's data objects."""
    cond = Key("pk").eq(f"USER#{USER}") & Key("sk").begins_with(f"{type_}#")
    out: list[dict[str, Any]] = []
    kwargs: dict[str, Any] = {"KeyConditionExpression": cond}
    while True:
        resp = _table().query(**kwargs)
        out += [json.loads(i["data"]) for i in resp.get("Items", []) if not i.get("deleted")]
        if "LastEvaluatedKey" not in resp:
            return out
        kwargs["ExclusiveStartKey"] = resp["LastEvaluatedKey"]


def put_record(type_: str, data: dict[str, Any], now_ms: int | None = None) -> dict[str, Any]:
    """Write a server-made record (coach reviews and messages) so it syncs down to the phone."""
    t = now_ms if now_ms is not None else _now_ms()
    row = {**data, "updatedAt": t, "deleted": False, "dirty": False}
    put_change(Change(type=type_, id=row["id"], updated_at=t, deleted=False, data=row), now_ms=t)
    return row
