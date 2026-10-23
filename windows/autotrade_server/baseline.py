from __future__ import annotations
import hashlib
import json
import math


def deterministic_analysis(market: dict) -> dict:
    """Token-free market assessment from already-computed market metrics.

    This is intentionally small and deterministic: the same market snapshot gives the
    same answer. It is used as the non-AI control when comparing with DeepSeek.
    """
    fresh = int(market.get("fresh_quote_count", 0) or 0)
    metrics = market.get("metrics") or {}
    if fresh < 5:
        return {
            "stance": "中立",
            "score": 50,
            "summary": "新鮮な銘柄が5件未満のため、数値判定はデータ不足です。",
            "reasons": ["AIは使用していません"],
            "token_cost": 0,
        }

    adv = int(metrics.get("advancers", 0) or 0)
    dec = int(metrics.get("decliners", 0) or 0)
    total = max(1, adv + dec + int(metrics.get("unchanged", 0) or 0))
    breadth = (adv - dec) / total
    avg = float(metrics.get("average_change_pct", 0.0) or 0.0)
    median = float(metrics.get("median_change_pct", 0.0) or 0.0)
    volatility = max(0.0, float(metrics.get("change_stddev_pct", 0.0) or 0.0))

    # Breadth is the largest contributor; mean/median only nudge the result.
    raw = 50.0 + 27.0 * breadth + 11.0 * math.tanh(avg / 1.2) + 7.0 * math.tanh(median / 1.0)
    # Very high cross-sectional volatility lowers conviction rather than flipping direction.
    conviction_penalty = min(7.0, max(0.0, volatility - 3.0) * 1.5)
    if raw > 50:
        raw -= conviction_penalty
    elif raw < 50:
        raw += conviction_penalty
    score = int(round(max(0.0, min(100.0, raw))))

    stance = "強気" if score >= 61 else "弱気" if score <= 39 else "中立"
    reasons = [
        f"値上がり {adv} / 値下がり {dec}",
        f"平均騰落率 {avg:+.2f}%・中央値 {median:+.2f}%",
    ]
    if volatility >= 3.0:
        reasons.append(f"銘柄間のばらつきが大きめ（σ {volatility:.2f}%）")
    else:
        reasons.append(f"銘柄間のばらつき σ {volatility:.2f}%")

    return {
        "stance": stance,
        "score": score,
        "summary": f"数値判定は{stance}。値上がり/値下がりの広がりと騰落率だけで判定しています。",
        "reasons": reasons,
        "token_cost": 0,
    }


def snapshot_fingerprint(snapshot: dict) -> str:
    canonical = json.dumps(snapshot, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(canonical).hexdigest()[:16]


def compare_stances(baseline: dict, ai: dict) -> dict:
    left = str(baseline.get("stance", "中立"))
    right = str(ai.get("stance", "中立"))
    return {
        "agreement": left == right,
        "label": "一致" if left == right else "不一致",
        "baseline_stance": left,
        "ai_stance": right,
    }
