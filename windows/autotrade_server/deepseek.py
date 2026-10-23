from __future__ import annotations
import copy
import json
import time
import httpx
from . import config
from .secrets import get_deepseek_key
from .settings_store import get_settings
from .usage_store import record, today

URL = "https://api.deepseek.com/chat/completions"
_ALLOWED_MODELS = {"deepseek-flash", "deepseek-v4-pro"}
_ALLOWED_EFFORTS = {"none", "low", "high", "max"}

# Peak published prices per 1M tokens. The estimate is intentionally conservative.
_PEAK_PRICES = {
    "deepseek-flash": {"hit": 0.006, "miss": 0.30, "output": 1.20},
    "deepseek-v4-pro": {"hit": 0.044, "miss": 1.32, "output": 3.96},
}
_PRICE_BASIS = "DeepSeek公表ピーク単価（2026-10-23確認）"

SYSTEM = (
    "日本株の短期市場分析補助。注文はしない。入力数値だけを根拠にJSONのみ返す。"
    "キーは stance,summary,reasons,risks。stanceは強気/中立/弱気。"
    "summaryは80文字以内、reasonsとrisksは各2件以内。データ不足なら中立。"
    "数値の再計算や長い一般論は不要。"
)

# In-memory cache is deliberate: it avoids re-billing the same snapshot while not
# persisting AI text indefinitely on disk. Server restart naturally clears it.
_CACHE: dict[str, tuple[float, dict]] = {}


class TokenBudgetExceeded(RuntimeError):
    pass


def _usage(data: dict, model: str) -> dict:
    raw = data.get("usage") or {}
    prompt = int(raw.get("prompt_tokens", 0) or 0)
    completion = int(raw.get("completion_tokens", 0) or 0)
    hit = int(raw.get("prompt_cache_hit_tokens", 0) or 0)
    miss = int(raw.get("prompt_cache_miss_tokens", 0) or 0)
    if hit + miss == 0 and prompt > 0:
        miss = prompt
    reasoning = int((raw.get("completion_tokens_details") or {}).get("reasoning_tokens", 0) or 0)
    prices = _PEAK_PRICES.get(model, _PEAK_PRICES["deepseek-v4-pro"])
    estimated = (hit * prices["hit"] + miss * prices["miss"] + completion * prices["output"]) / 1_000_000
    return {
        "model": model,
        "prompt_tokens": prompt,
        "completion_tokens": completion,
        "total_tokens": int(raw.get("total_tokens", prompt + completion) or (prompt + completion)),
        "cache_hit_tokens": hit,
        "cache_miss_tokens": miss,
        "reasoning_tokens": reasoning,
        "estimated_max_usd": round(estimated, 8),
        "pricing_basis": _PRICE_BASIS,
    }


def _zero_usage(model: str) -> dict:
    return {
        "model": model,
        "prompt_tokens": 0,
        "completion_tokens": 0,
        "total_tokens": 0,
        "cache_hit_tokens": 0,
        "cache_miss_tokens": 0,
        "reasoning_tokens": 0,
        "estimated_max_usd": 0.0,
        "pricing_basis": _PRICE_BASIS,
    }


def _model_settings() -> tuple[str, str]:
    model = config.DEEPSEEK_MODEL if config.DEEPSEEK_MODEL in _ALLOWED_MODELS else "deepseek-flash"
    effort = config.DEEPSEEK_REASONING_EFFORT if config.DEEPSEEK_REASONING_EFFORT in _ALLOWED_EFFORTS else "none"
    return model, effort


def _budget_check(user_content: str) -> tuple[int, int]:
    settings = get_settings()
    limit = int(settings.get("ai_daily_token_limit", 0) or 0)
    used = int(today().get("total_tokens", 0) or 0)
    if limit == 0:
        return used, limit

    # Conservative preflight. Japanese/JSON can tokenize densely, so count one token per
    # character plus output allowance and a small system-message reserve.
    estimated_request = len(user_content) + config.DEEPSEEK_MAX_TOKENS + 300
    if used + estimated_request > limit:
        raise TokenBudgetExceeded(f"daily token budget would be exceeded ({used}/{limit})")
    return used, limit


def _cache_key(snapshot_id: str, model: str, effort: str) -> str:
    return f"{snapshot_id}:{model}:{effort}"


def _cached(snapshot_id: str, model: str, effort: str) -> dict | None:
    key = _cache_key(snapshot_id, model, effort)
    item = _CACHE.get(key)
    if not item:
        return None
    saved_at, result = item
    if time.time() - saved_at > config.AI_CACHE_SECONDS:
        _CACHE.pop(key, None)
        return None
    reused = copy.deepcopy(result)
    previous_tokens = int((reused.get("usage") or {}).get("total_tokens", 0) or 0)
    reused["usage"] = _zero_usage(model)
    reused["today_usage"] = today()
    reused["cache_reused"] = True
    reused["saved_tokens_estimate"] = previous_tokens
    return reused


async def analyze_snapshot(market: dict, snapshot_id: str) -> dict:
    api_key = get_deepseek_key()
    if not api_key:
        raise RuntimeError("DeepSeek API key is not configured")

    if int(market.get("fresh", 0) or 0) == 0:
        return {
            "stance": "中立",
            "summary": "新鮮な市場データがないためAI分析を実行しませんでした。",
            "reasons": [],
            "risks": ["市場データ不足"],
            "usage": None,
            "today_usage": today(),
            "cache_reused": False,
            "saved_tokens_estimate": 0,
        }

    model, effort = _model_settings()
    cached = _cached(snapshot_id, model, effort)
    if cached is not None:
        return cached

    user_content = json.dumps(market, ensure_ascii=False, separators=(",", ":"))
    _budget_check(user_content)

    payload = {
        "model": model,
        "thinking": {"type": "disabled" if effort == "none" else "enabled"},
        "reasoning_effort": effort,
        "response_format": {"type": "json_object"},
        "messages": [
            {"role": "system", "content": SYSTEM},
            {"role": "user", "content": user_content},
        ],
        "max_tokens": config.DEEPSEEK_MAX_TOKENS,
    }
    headers = {"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"}
    async with httpx.AsyncClient(timeout=httpx.Timeout(30.0, connect=8.0)) as client:
        response = await client.post(URL, headers=headers, json=payload)
        response.raise_for_status()
        data = response.json()

    choices = data.get("choices") or []
    if not choices:
        raise RuntimeError("DeepSeek returned no choices")
    message = choices[0].get("message") or {}
    content = message.get("content")
    if not isinstance(content, str) or not content.strip():
        raise RuntimeError("DeepSeek returned empty content")

    parsed = json.loads(content)
    stance = parsed.get("stance", "中立")
    if stance not in {"強気", "中立", "弱気"}:
        stance = "中立"

    usage = _usage(data, model)
    today_usage = record(usage)
    result = {
        "stance": stance,
        "summary": str(parsed.get("summary", ""))[:300],
        "reasons": [str(x)[:160] for x in (parsed.get("reasons") or [])[:2]],
        "risks": [str(x)[:160] for x in (parsed.get("risks") or [])[:2]],
        "usage": usage,
        "today_usage": today_usage,
        "cache_reused": False,
        "saved_tokens_estimate": 0,
    }
    _CACHE[_cache_key(snapshot_id, model, effort)] = (time.time(), copy.deepcopy(result))
    return result
