from __future__ import annotations
import asyncio
from fastapi import Depends, FastAPI, HTTPException, Request
from pydantic import BaseModel
from .auth import require_auth, decrypt_api_key, signed_json
from .baseline import compare_stances, deterministic_analysis, snapshot_fingerprint
from .calendar_data import today_events
from .deepseek import TokenBudgetExceeded, analyze_snapshot
from .health import system_health
from .market import ai_snapshot_from_overview, overview
from .secrets import has_deepseek_key, set_deepseek_key
from .settings_store import get_settings, update_settings
from .usage_store import today as today_usage

app = FastAPI(title="AutoTrade Windows Bridge", docs_url=None, redoc_url=None)
_ai_lock = asyncio.Lock()


class SecretEnvelope(BaseModel):
    nonce: str
    ciphertext: str


class AiSettingsUpdate(BaseModel):
    ai_daily_token_limit: int


@app.get("/v1/pair/test", dependencies=[Depends(require_auth)])
async def pair_test(request: Request):
    return signed_json(request, {"ok": True})


@app.post("/v1/secrets/deepseek", dependencies=[Depends(require_auth)])
async def save_deepseek(envelope: SecretEnvelope, request: Request):
    try:
        value = decrypt_api_key(envelope.nonce, envelope.ciphertext)
        set_deepseek_key(value)
        return signed_json(request, {"ok": True})
    except Exception as exc:
        raise HTTPException(400, "secret could not be stored") from exc


@app.post("/v1/settings/ai", dependencies=[Depends(require_auth)])
async def save_ai_settings(value: AiSettingsUpdate, request: Request):
    try:
        settings = update_settings(value.ai_daily_token_limit)
        return signed_json(request, settings)
    except Exception as exc:
        raise HTTPException(400, "AI settings could not be stored") from exc


@app.get("/v1/dashboard", dependencies=[Depends(require_auth)])
async def dashboard(request: Request):
    market = overview()
    calendar = today_events()
    deepseek_configured = has_deepseek_key()
    baseline = deterministic_analysis(market)
    compact = ai_snapshot_from_overview(market)
    return signed_json(request, {
        "deepseek_configured": deepseek_configured,
        "market": market,
        "baseline": baseline,
        "snapshot_id": snapshot_fingerprint(compact),
        "calendar": calendar,
        "health": system_health(market, deepseek_configured, calendar),
        "ai_usage_today": today_usage(),
        "ai_settings": get_settings(),
    })


@app.post("/v1/analysis/compare", dependencies=[Depends(require_auth)])
async def compare_analysis(request: Request):
    if _ai_lock.locked():
        raise HTTPException(409, "AI analysis already running")

    market = overview()
    compact = ai_snapshot_from_overview(market)
    snapshot_id = snapshot_fingerprint(compact)
    baseline = deterministic_analysis(market)
    if int(compact.get("fresh", 0) or 0) == 0:
        return signed_json(request, {
            "snapshot_id": snapshot_id,
            "baseline": baseline,
            "ai": None,
            "comparison": None,
            "message": "新鮮な市場データがないためAIを呼び出しませんでした。",
        })

    try:
        async with _ai_lock:
            ai = await analyze_snapshot(compact, snapshot_id)
        return signed_json(request, {
            "snapshot_id": snapshot_id,
            "baseline": baseline,
            "ai": ai,
            "comparison": compare_stances(baseline, ai),
        })
    except TokenBudgetExceeded as exc:
        raise HTTPException(429, "daily AI token budget reached") from exc
    except Exception as exc:
        # Never return API keys or upstream response bodies.
        raise HTTPException(502, "AI analysis unavailable") from exc
