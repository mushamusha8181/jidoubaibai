from __future__ import annotations
import time
from . import config


def _file_age_seconds(path) -> float | None:
    try:
        return max(0.0, time.time() - path.stat().st_mtime)
    except OSError:
        return None


def system_health(market: dict, deepseek_configured: bool, calendar: dict) -> dict:
    age = _file_age_seconds(config.MARKET_FILE)
    fresh = int(market.get("fresh_quote_count", 0) or 0)
    total = int(market.get("quote_count", 0) or 0)

    if age is None:
        excel_status = "未接続"
    elif age <= 10:
        excel_status = "正常"
    elif age <= 60:
        excel_status = "遅延"
    else:
        excel_status = "停止の可能性"

    if fresh > 0:
        market_status = "正常"
    elif total > 0:
        market_status = "データが古い"
    else:
        market_status = "未接続"

    calendar_source = str(calendar.get("source", "未接続"))
    if calendar_source == "読込エラー":
        calendar_status = "読込エラー"
    elif calendar_source == "未接続":
        calendar_status = "未接続"
    else:
        calendar_status = "取得済"

    return {
        "bridge": "正常",
        "excel_sync": excel_status,
        "market_data": market_status,
        "deepseek": "設定済" if deepseek_configured else "未設定",
        "calendar": calendar_status,
        "market_file_age_seconds": round(age, 1) if age is not None else None,
    }
