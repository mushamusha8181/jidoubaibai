import json
from datetime import datetime, timezone, timedelta
from pathlib import Path
from autotrade_server import config
from autotrade_server.market import overview


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def test_classification_is_unique_and_multi_category(tmp_path: Path, monkeypatch):
    p = tmp_path / "market.json"
    p.write_text(json.dumps({"source": "test", "quotes": [
        {"code": "1111", "name": "A", "price": 103, "prev_close": 100, "volume": 300, "avg_volume": 100, "timestamp": now_iso()},
        {"code": "2222", "name": "B", "price": 96, "prev_close": 100, "volume": 100, "avg_volume": 100, "timestamp": now_iso()},
        {"code": "3333", "name": "C", "price": 101, "prev_close": 100, "volume": 500, "avg_volume": 100, "timestamp": now_iso()},
        {"code": "4444", "name": "D", "price": 101, "prev_close": 100, "volume": 100, "avg_volume": 100, "timestamp": now_iso()},
        {"code": "5555", "name": "E", "price": 101, "prev_close": 100, "volume": 100, "avg_volume": 100, "timestamp": now_iso()},
    ]}), encoding="utf-8")
    monkeypatch.setattr(config, "MARKET_FILE", p)
    data = overview()
    rows = {x["code"]: x for x in data["movers"]}
    assert data["source"] == "test"
    assert "急騰" in rows["1111"]["categories"]
    assert "出来高" in rows["1111"]["categories"]
    assert "急落" in rows["2222"]["categories"]
    assert "出来高" in rows["3333"]["categories"]
    assert len(rows) == len(data["movers"])
    assert data["stance"] in {"強め", "中立", "弱め"}


def test_missing_or_old_timestamp_is_stale(tmp_path: Path, monkeypatch):
    old = (datetime.now(timezone.utc) - timedelta(minutes=10)).isoformat()
    p = tmp_path / "market.json"
    p.write_text(json.dumps({"source": "test", "quotes": [
        {"code": "1111", "price": 103, "prev_close": 100, "volume": 300, "avg_volume": 100},
        {"code": "2222", "price": 96, "prev_close": 100, "volume": 100, "avg_volume": 100, "timestamp": old},
    ]}), encoding="utf-8")
    monkeypatch.setattr(config, "MARKET_FILE", p)
    data = overview()
    assert data["movers"] == []
    assert data["fresh_quote_count"] == 0
    assert data["stale_quote_count"] == 2


def test_movers_include_suitability_score(tmp_path: Path, monkeypatch):
    p = tmp_path / "market.json"
    p.write_text(json.dumps({"source": "test", "quotes": [
        {"code": "1111", "name": "A", "price": 104, "prev_close": 100, "volume": 400, "avg_volume": 100,
         "bid": 103.9, "ask": 104.0, "high": 105, "low": 101, "timestamp": now_iso()},
    ]}), encoding="utf-8")
    monkeypatch.setattr(config, "MARKET_FILE", p)
    data = overview()
    row = data["movers"][0]
    assert 0 <= row["suitability_score"] <= 100
    assert row["suitability_notes"]
