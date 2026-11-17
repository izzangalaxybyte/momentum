"""OpenRouter chat completions (Claude Haiku by default), standard library only.

- The key lives in SSM (SecureString, OPENROUTER_KEY_PARAM) and never leaves AWS.
- The model id comes from COACH_MODEL, so it can be switched with a deploy, no app release.
- Requests go only to the model maker's own servers and only to endpoints that don't keep prompts.
- `json_call` asks for JSON matching a schema, validates it with the caller's check, and retries once.
"""

import json
import logging
import os
import time
import urllib.error
import urllib.request
from collections.abc import Callable
from typing import Any

import boto3

log = logging.getLogger()

URL = "https://openrouter.ai/api/v1/chat/completions"
DEFAULT_MODEL = "anthropic/claude-haiku-5.5"
KEY_TTL_S = 300

_key_cache: tuple[str, float] | None = None


class CoachError(RuntimeError):
    """The model couldn't be reached or didn't give a usable answer."""


def model() -> str:
    return os.environ.get("COACH_MODEL") or DEFAULT_MODEL


def _key() -> str:
    global _key_cache
    now = time.time()
    if _key_cache and now - _key_cache[1] < KEY_TTL_S:
        return _key_cache[0]
    name = os.environ.get("OPENROUTER_KEY_PARAM", "/momentum/openrouter-api-key")
    value = boto3.client("ssm").get_parameter(Name=name, WithDecryption=True)["Parameter"]["Value"]
    _key_cache = (value, now)
    return value


def reset_cache() -> None:
    global _key_cache
    _key_cache = None


def _post(body: dict[str, Any], timeout: float) -> dict[str, Any]:
    req = urllib.request.Request(
        URL,
        data=json.dumps(body).encode(),
        headers={"Authorization": f"Bearer {_key()}", "Content-Type": "application/json", "X-Title": "Momentum"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read())
    except urllib.error.HTTPError as e:
        raise CoachError(f"OpenRouter {e.code}: {e.read()[:300]!r}") from e
    except (urllib.error.URLError, TimeoutError) as e:
        raise CoachError(f"OpenRouter unreachable: {e}") from e


def chat(system: str, messages: list[dict[str, Any]], *, max_tokens: int = 1000, effort: str = "low",
         schema: dict[str, Any] | None = None, timeout: float = 25, web: bool = False) -> tuple[str, dict[str, Any]]:
    """One completion. `messages` are OpenAI-style ({role, content}); returns (text, usage).
    web=True lets the model search the web (the model maker's own search; ~1 cent per search)."""
    m = model()
    body: dict[str, Any] = {
        "model": m,
        "max_tokens": max_tokens,
        "messages": [{"role": "system", "content": system}, *messages],
        "provider": {"only": [m.split("/")[0]], "allow_fallbacks": False, "data_collection": "deny"},
        "reasoning": {"effort": effort, "exclude": True},
        "usage": {"include": True},
    }
    if web:
        body["plugins"] = [{"id": "web", "engine": "native", "max_results": 4}]
    if schema:
        body["response_format"] = {"type": "json_schema", "json_schema": {"name": "answer", "strict": True, "schema": schema}}
    data = _post(body, timeout)
    if data.get("error") or not data.get("choices"):
        raise CoachError(f"OpenRouter error: {json.dumps(data.get('error') or data)[:300]}")
    choice = data["choices"][0]
    text = (choice.get("message") or {}).get("content") or ""
    if choice.get("finish_reason") == "length":
        raise CoachError("answer was cut off")
    usage = data.get("usage") or {}
    log.info("coach call model=%s in=%s out=%s searches=%s cost=%s", data.get("model"), usage.get("prompt_tokens"),
             usage.get("completion_tokens"), (usage.get("server_tool_use_details") or {}).get("web_search_requests", 0),
             usage.get("cost"))
    return text, usage


def _parse_json(text: str) -> Any:
    t = text.strip()
    if t.startswith("```"):
        t = t.strip("`")
        t = t[t.find("\n") + 1:] if "\n" in t else t
    start, end = t.find("{"), t.rfind("}")
    if start < 0 or end < start:
        raise ValueError("no JSON object in the answer")
    return json.loads(t[start:end + 1])


def json_call(system: str, messages: list[dict[str, Any]], schema: dict[str, Any],
              check: Callable[[Any], Any], attempts: int = 2, **kwargs: Any) -> Any:
    """Ask for JSON matching `schema`; `check` validates/cleans it (raise ValueError to reject). One retry."""
    last: Exception | None = None
    for _ in range(attempts):
        text, _usage = chat(system, messages, schema=schema, **kwargs)
        try:
            return check(_parse_json(text))
        except (ValueError, TypeError, KeyError) as e:
            last = e
            log.warning("coach answer rejected: %s", e)
    raise CoachError(f"no valid answer: {last}")
