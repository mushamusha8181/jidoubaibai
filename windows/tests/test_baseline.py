from autotrade_server.baseline import compare_stances, deterministic_analysis, snapshot_fingerprint


def test_baseline_is_token_free_and_deterministic():
    market = {
        "fresh_quote_count": 10,
        "metrics": {
            "advancers": 8,
            "decliners": 2,
            "unchanged": 0,
            "average_change_pct": 1.2,
            "median_change_pct": 0.8,
            "change_stddev_pct": 1.5,
        },
    }
    first = deterministic_analysis(market)
    second = deterministic_analysis(market)
    assert first == second
    assert first["token_cost"] == 0
    assert first["stance"] == "強気"


def test_snapshot_fingerprint_and_comparison():
    snapshot = {"fresh": 6, "metrics": {"advancers": 4}, "movers": []}
    assert snapshot_fingerprint(snapshot) == snapshot_fingerprint(dict(snapshot))
    compared = compare_stances({"stance": "中立"}, {"stance": "中立"})
    assert compared["agreement"] is True
    assert compared["label"] == "一致"
