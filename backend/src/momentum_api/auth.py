"""Single-user bearer-token auth.

Only the SHA-256 hash of the token is stored (SSM parameter, name in TOKEN_HASH_PARAM). The plain token
lives on the phone and in ~/.momentum/api-token on the Mac that generated it (scripts/new-token.sh).
"""

import hashlib
import hmac
import os
import time

import boto3

_CACHE_SECONDS = 300
_cached: tuple[str, float] | None = None


def _token_hash() -> str:
    global _cached
    now = time.time()
    if _cached and now - _cached[1] < _CACHE_SECONDS:
        return _cached[0]
    ssm = boto3.client("ssm")
    value = ssm.get_parameter(Name=os.environ["TOKEN_HASH_PARAM"])["Parameter"]["Value"].strip().lower()
    _cached = (value, now)
    return value


def normalize(token: str) -> str:
    """Tokens are typed by hand on the phone: ignore case, spaces and dashes."""
    return "".join(ch for ch in token.upper() if ch.isalnum())


def hash_token(token: str) -> str:
    return hashlib.sha256(normalize(token).encode()).hexdigest()


def is_authorized(headers: dict[str, str]) -> bool:
    header = headers.get("authorization", "")
    if not header.lower().startswith("bearer "):
        return False
    token = header[7:].strip()
    if not token:
        return False
    try:
        expected = _token_hash()
    except Exception:  # SSM unreachable or parameter missing: fail closed
        return False
    return hmac.compare_digest(hash_token(token), expected)


def reset_cache() -> None:
    global _cached
    _cached = None
