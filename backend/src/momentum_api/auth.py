"""Single-user bearer-token auth.

Only the SHA-256 hash of the token is stored (SSM parameter, name in TOKEN_HASH_PARAM). The plain token
lives on the phone and in ~/.momentum/api-token on the Mac that generated it (scripts/new-token.sh).
"""

import hashlib
import hmac
import logging
import os
import time

import boto3

log = logging.getLogger()
# The characters new-token.sh uses (no 0/O, 1/I/L), so a typo with a look-alike can be spotted in the logs.
ALPHABET = frozenset("ABCDEFGHJKMNPQRSTUVWXYZ23456789")
TOKEN_LENGTH = 25

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
        log.warning("unauthorized: no bearer token")
        return False
    token = header[7:].strip()
    if not token:
        log.warning("unauthorized: empty token")
        return False
    try:
        expected = _token_hash()
    except Exception:  # SSM unreachable or parameter missing: fail closed
        log.exception("unauthorized: can't read the token hash")
        return False
    if hmac.compare_digest(hash_token(token), expected):
        return True
    log.warning("unauthorized: %s", describe_mismatch(token))
    return False


def describe_mismatch(token: str) -> str:
    """Why a token might be wrong, without revealing it: its length and any characters a real token can't contain."""
    n = normalize(token)
    bad = sorted({c for c in n if c not in ALPHABET})
    parts = [f"wrong token ({len(n)} characters, expected {TOKEN_LENGTH})"]
    if bad:
        parts.append("contains " + ", ".join(bad) + " which never appear in a real token (look-alike typo?)")
    return "; ".join(parts)


def reset_cache() -> None:
    global _cached
    _cached = None
