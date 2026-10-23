from autotrade_server.deepseek import _usage


def test_usage_parsing_and_peak_cost_estimate():
    data = {
        "usage": {
            "prompt_tokens": 1000,
            "completion_tokens": 200,
            "total_tokens": 1200,
            "prompt_cache_hit_tokens": 600,
            "prompt_cache_miss_tokens": 400,
            "completion_tokens_details": {"reasoning_tokens": 50},
        }
    }
    usage = _usage(data, "deepseek-flash")
    assert usage["total_tokens"] == 1200
    assert usage["cache_hit_tokens"] == 600
    assert usage["cache_miss_tokens"] == 400
    assert usage["reasoning_tokens"] == 50
    assert usage["estimated_max_usd"] > 0
