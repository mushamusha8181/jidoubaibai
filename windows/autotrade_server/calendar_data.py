from __future__ import annotations
import json
from datetime import date
from . import config

def today_events() -> dict:
    if not config.CALENDAR_FILE.exists():
        return {"source": "未接続", "date": date.today().isoformat(), "events": []}
    try:
        raw = json.loads(config.CALENDAR_FILE.read_text(encoding="utf-8"))
        target = date.today().isoformat()
        events = [e for e in raw.get("events", []) if str(e.get("date")) == target]
        return {"source": str(raw.get("source", "ローカル経済カレンダー")), "date": target, "events": events}
    except Exception:
        return {"source": "読込エラー", "date": date.today().isoformat(), "events": []}
