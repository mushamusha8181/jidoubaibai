import time
from autotrade_server import deepseek


def test_same_snapshot_reuses_ai_without_new_tokens(monkeypatch):
    deepseek._CACHE.clear()
    monkeypatch.setattr(deepseek.config, "AI_CACHE_SECONDS", 120)
    key = deepseek._cache_key("abc", "deepseek-flash", "none")
    deepseek._CACHE[key] = (time.time(), {
        "stance": "中立",
        "summary": "x",
        "reasons": [],
        "risks": [],
        "usage": {"total_tokens": 321},
        "today_usage": {"total_tokens": 1000},
        "cache_reused": False,
        "saved_tokens_estimate": 0,
    })
    monkeypatch.setattr(deepseek, "today", lambda: {"total_tokens": 1000})
    result = deepseek._cached("abc", "deepseek-flash", "none")
    assert result is not None
    assert result["cache_reused"] is True
    assert result["usage"]["total_tokens"] == 0
    assert result["saved_tokens_estimate"] == 321


def test_budget_preflight_blocks_before_api_call(monkeypatch):
    monkeypatch.setattr(deepseek, "get_settings", lambda: {"ai_daily_token_limit": 1000})
    monkeypatch.setattr(deepseek, "today", lambda: {"total_tokens": 900})
    monkeypatch.setattr(deepseek.config, "DEEPSEEK_MAX_TOKENS", 300)
    try:
        deepseek._budget_check("x" * 100)
        assert False, "budget check should block"
    except deepseek.TokenBudgetExceeded:
        pass
