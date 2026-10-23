from __future__ import annotations
import json
import math
import statistics
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from . import config

_CATEGORY_ORDER = {"急騰": 0, "急落": 1, "出来高": 2}


@dataclass(frozen=True)
class Quote:
    code: str
    name: str
    price: float
    prev_close: float
    volume: float
    avg_volume: float
    bid: float | None = None
    ask: float | None = None
    high: float | None = None
    low: float | None = None
    timestamp: str | None = None

    @property
    def change_pct(self) -> float:
        return ((self.price / self.prev_close) - 1) * 100 if self.prev_close > 0 else 0.0

    @property
    def volume_ratio(self) -> float:
        return self.volume / self.avg_volume if self.avg_volume > 0 else 0.0

    @property
    def spread_bps(self) -> float | None:
        if self.bid is not None and self.ask is not None and self.bid > 0 and self.ask >= self.bid:
            mid = (self.bid + self.ask) / 2
            return (self.ask - self.bid) / mid * 10_000 if mid > 0 else None
        return None

    def stale(self, max_age_seconds: int = 120) -> bool:
        # A quote without a timestamp cannot be proven fresh, so fail closed.
        if not self.timestamp:
            return True
        try:
            t = datetime.fromisoformat(str(self.timestamp).replace("Z", "+00:00"))
            age = (datetime.now(timezone.utc) - t.astimezone(timezone.utc)).total_seconds()
            return age < -30 or age > max_age_seconds
        except Exception:
            return True


def _finite(value, default=0.0):
    try:
        parsed = float(value)
        return parsed if math.isfinite(parsed) else default
    except (TypeError, ValueError):
        return default


def load_quotes(path: Path | None = None) -> tuple[str, list[Quote]]:
    p = path or config.MARKET_FILE
    if not p.exists():
        return "未接続", []
    try:
        raw = json.loads(p.read_text(encoding="utf-8"))
        source = str(raw.get("source", "ローカル市場データ"))
        quotes_by_code: dict[str, Quote] = {}
        for row in raw.get("quotes", []):
            code = str(row.get("code", "")).strip()
            name = str(row.get("name", "")).strip()
            price = _finite(row.get("price"))
            prev = _finite(row.get("prev_close"))
            if not code or price <= 0 or prev <= 0:
                continue
            quotes_by_code[code] = Quote(
                code=code,
                name=name or code,
                price=price,
                prev_close=prev,
                volume=_finite(row.get("volume")),
                avg_volume=_finite(row.get("avg_volume")),
                bid=_finite(row.get("bid"), None) if row.get("bid") is not None else None,
                ask=_finite(row.get("ask"), None) if row.get("ask") is not None else None,
                high=_finite(row.get("high"), None) if row.get("high") is not None else None,
                low=_finite(row.get("low"), None) if row.get("low") is not None else None,
                timestamp=str(row.get("timestamp")) if row.get("timestamp") is not None else None,
            )
        return source, list(quotes_by_code.values())
    except Exception:
        return "データ読込エラー", []


def fresh_quotes(quotes: list[Quote]) -> list[Quote]:
    return [quote for quote in quotes if not quote.stale()]


def _scalping_suitability(quote: Quote) -> tuple[int, list[str]]:
    """Heuristic suitability score, not a probability of profit."""
    change = abs(quote.change_pct)
    volume_ratio = quote.volume_ratio
    spread = quote.spread_bps

    move_points = min(30.0, change / 5.0 * 30.0)
    if change > 10.0:
        move_points = max(12.0, move_points - min(15.0, (change - 10.0) * 1.5))

    volume_points = min(30.0, max(0.0, (volume_ratio - 1.0) * 18.0))
    if spread is None:
        spread_points = 8.0
    elif spread <= 5:
        spread_points = 25.0
    elif spread <= 10:
        spread_points = 20.0
    elif spread <= 20:
        spread_points = 12.0
    elif spread <= 40:
        spread_points = 5.0
    else:
        spread_points = 0.0

    if quote.high and quote.low and quote.price > 0 and quote.high >= quote.low > 0:
        range_pct = (quote.high - quote.low) / quote.price * 100
        range_points = min(15.0, range_pct / 3.0 * 15.0)
    else:
        range_pct = None
        range_points = min(10.0, change / 4.0 * 10.0)

    score = int(round(max(0.0, min(100.0, move_points + volume_points + spread_points + range_points))))
    notes: list[str] = []
    notes.append("出来高◎" if volume_ratio >= 3 else "出来高○" if volume_ratio >= 2 else "出来高△")
    notes.append("値動き◎" if 2 <= change <= 8 else "値動き○" if change > 0.8 else "値動き△")
    if spread is None:
        notes.append("スプレッド不明")
    else:
        notes.append("スプレッド◎" if spread <= 5 else "スプレッド○" if spread <= 15 else "スプレッド△")
    return score, notes


def _classify_fresh(fresh: list[Quote], limit_each: int = 8) -> list[dict]:
    buckets = (
        ("急騰", sorted((q for q in fresh if q.change_pct >= 2.0), key=lambda q: q.change_pct, reverse=True)[:limit_each]),
        ("急落", sorted((q for q in fresh if q.change_pct <= -2.0), key=lambda q: q.change_pct)[:limit_each]),
        ("出来高", sorted((q for q in fresh if q.volume_ratio >= 2.0), key=lambda q: q.volume_ratio, reverse=True)[:limit_each]),
    )

    selected: dict[str, dict] = {}
    for category, rows in buckets:
        for quote in rows:
            item = selected.get(quote.code)
            if item is None:
                item = {"quote": quote, "categories": set()}
                selected[quote.code] = item
            item["categories"].add(category)

    out: list[dict] = []
    for item in selected.values():
        quote: Quote = item["quote"]
        categories = sorted(item["categories"], key=lambda x: _CATEGORY_ORDER[x])
        suitability_score, suitability_notes = _scalping_suitability(quote)
        out.append({
            "code": quote.code,
            "name": quote.name,
            "price": round(quote.price, 4),
            "change_pct": round(quote.change_pct, 3),
            "volume_ratio": round(quote.volume_ratio, 3),
            "spread_bps": round(quote.spread_bps, 2) if quote.spread_bps is not None else None,
            "suitability_score": suitability_score,
            "suitability_notes": suitability_notes,
            "categories": categories,
        })
    out.sort(key=lambda row: (row["suitability_score"], abs(row["change_pct"])), reverse=True)
    return out


def _market_metrics_fresh(fresh: list[Quote]) -> dict:
    if not fresh:
        return {
            "advancers": 0,
            "decliners": 0,
            "unchanged": 0,
            "average_change_pct": 0.0,
            "median_change_pct": 0.0,
            "change_stddev_pct": 0.0,
            "average_spread_bps": None,
        }

    changes = [q.change_pct for q in fresh]
    spreads = [q.spread_bps for q in fresh if q.spread_bps is not None]
    return {
        "advancers": sum(x > 0 for x in changes),
        "decliners": sum(x < 0 for x in changes),
        "unchanged": sum(x == 0 for x in changes),
        "average_change_pct": round(statistics.fmean(changes), 4),
        "median_change_pct": round(statistics.median(changes), 4),
        "change_stddev_pct": round(statistics.pstdev(changes), 4) if len(changes) > 1 else 0.0,
        "average_spread_bps": round(statistics.fmean(spreads), 3) if spreads else None,
    }


def _market_stance_fresh(fresh: list[Quote]) -> str:
    if len(fresh) < 5:
        return "データ不足"
    adv = sum(q.change_pct > 0 for q in fresh)
    dec = sum(q.change_pct < 0 for q in fresh)
    breadth = (adv - dec) / len(fresh)
    avg = statistics.fmean(q.change_pct for q in fresh)
    if breadth > 0.25 and avg > 0.4:
        return "強め"
    if breadth < -0.25 and avg < -0.4:
        return "弱め"
    return "中立"

def overview() -> dict:
    source, quotes = load_quotes()
    fresh = fresh_quotes(quotes)
    return {
        "source": source,
        "stance": _market_stance_fresh(fresh),
        "movers": _classify_fresh(fresh),
        "quote_count": len(quotes),
        "fresh_quote_count": len(fresh),
        "stale_quote_count": len(quotes) - len(fresh),
        "metrics": _market_metrics_fresh(fresh),
    }


def ai_snapshot_from_overview(data: dict, max_movers: int = 8) -> dict:
    return {
        "stance": data["stance"],
        "fresh": data["fresh_quote_count"],
        "metrics": data["metrics"],
        "movers": [
            {
                "code": row["code"],
                "chg": row["change_pct"],
                "vol": row["volume_ratio"],
                "spread": row["spread_bps"],
                "score": row["suitability_score"],
                "tags": row["categories"],
            }
            for row in data["movers"][:max_movers]
        ],
    }

