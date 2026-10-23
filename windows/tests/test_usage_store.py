from autotrade_server import config
from autotrade_server.usage_store import record, today


def test_usage_store_accumulates(tmp_path, monkeypatch):
    path = tmp_path / "usage.json"
    monkeypatch.setattr(config, "AI_USAGE_FILE", path)
    one = {
        "prompt_tokens": 10,
        "completion_tokens": 5,
        "total_tokens": 15,
        "cache_hit_tokens": 4,
        "cache_miss_tokens": 6,
        "reasoning_tokens": 0,
        "estimated_max_usd": 0.001,
    }
    first = record(one)
    second = record(one)
    assert first["requests"] == 1
    assert second["requests"] == 2
    assert today()["total_tokens"] == 30
