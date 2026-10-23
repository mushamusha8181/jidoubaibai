from __future__ import annotations
import json
import threading
from datetime import date
from . import config

_LOCK = threading.Lock()
_FIELDS = (
    "prompt_tokens",
    "completion_tokens",
    "total_tokens",
    "cache_hit_tokens",
    "cache_miss_tokens",
    "reasoning_tokens",
)


def _blank() -> dict:
    return {**{field: 0 for field in _FIELDS}, "requests": 0, "estimated_max_usd": 0.0}


def _load() -> dict:
    if not config.AI_USAGE_FILE.exists():
        return {"date": date.today().isoformat(), "today": _blank(), "all_time": _blank()}
    try:
        raw = json.loads(config.AI_USAGE_FILE.read_text(encoding="utf-8"))
        if not isinstance(raw, dict):
            raise ValueError("invalid usage file")
        raw.setdefault("today", _blank())
        raw.setdefault("all_time", _blank())
        if raw.get("date") != date.today().isoformat():
            raw["date"] = date.today().isoformat()
            raw["today"] = _blank()
        return raw
    except Exception:
        return {"date": date.today().isoformat(), "today": _blank(), "all_time": _blank()}


def _safe_public(bucket: dict) -> dict:
    return {
        **{field: int(bucket.get(field, 0) or 0) for field in _FIELDS},
        "requests": int(bucket.get("requests", 0) or 0),
        "estimated_max_usd": round(float(bucket.get("estimated_max_usd", 0.0) or 0.0), 8),
    }


def record(usage: dict) -> dict:
    with _LOCK:
        data = _load()
        for name in ("today", "all_time"):
            bucket = data[name]
            for field in _FIELDS:
                bucket[field] = int(bucket.get(field, 0) or 0) + int(usage.get(field, 0) or 0)
            bucket["requests"] = int(bucket.get("requests", 0) or 0) + 1
            bucket["estimated_max_usd"] = float(bucket.get("estimated_max_usd", 0.0) or 0.0) + float(usage.get("estimated_max_usd", 0.0) or 0.0)
        tmp = config.AI_USAGE_FILE.with_suffix(".tmp")
        tmp.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
        tmp.replace(config.AI_USAGE_FILE)
        return _safe_public(data["today"])


def today() -> dict:
    with _LOCK:
        return _safe_public(_load()["today"])
