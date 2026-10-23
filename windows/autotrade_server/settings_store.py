from __future__ import annotations
import json
import threading
from . import config

_LOCK = threading.Lock()
_DEFAULT_TOKEN_LIMIT = 20_000
_MAX_TOKEN_LIMIT = 2_000_000


def _normalize(raw: dict | None) -> dict:
    raw = raw or {}
    try:
        limit = int(raw.get("ai_daily_token_limit", _DEFAULT_TOKEN_LIMIT) or 0)
    except (TypeError, ValueError):
        limit = _DEFAULT_TOKEN_LIMIT
    # 0 means unlimited; otherwise keep the value in a sensible range.
    if limit != 0:
        limit = max(1_000, min(_MAX_TOKEN_LIMIT, limit))
    return {"ai_daily_token_limit": limit}


def get_settings() -> dict:
    with _LOCK:
        if not config.SETTINGS_FILE.exists():
            return _normalize(None)
        try:
            raw = json.loads(config.SETTINGS_FILE.read_text(encoding="utf-8"))
            return _normalize(raw if isinstance(raw, dict) else None)
        except Exception:
            return _normalize(None)


def update_settings(ai_daily_token_limit: int) -> dict:
    value = _normalize({"ai_daily_token_limit": ai_daily_token_limit})
    with _LOCK:
        tmp = config.SETTINGS_FILE.with_suffix(".tmp")
        tmp.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")
        tmp.replace(config.SETTINGS_FILE)
    return value
