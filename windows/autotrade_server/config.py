from __future__ import annotations
import os
from pathlib import Path

APP_DIR = Path(os.environ.get("LOCALAPPDATA", Path.home())) / "AutoTrade"
APP_DIR.mkdir(parents=True, exist_ok=True)
PAIRING_FILE = APP_DIR / "pairing_secret.dpapi"
DEEPSEEK_FILE = APP_DIR / "deepseek_key.dpapi"
MARKET_FILE = APP_DIR / "market_snapshot.json"
CALENDAR_FILE = APP_DIR / "calendar.json"
AI_USAGE_FILE = APP_DIR / "ai_usage.json"
SETTINGS_FILE = APP_DIR / "settings.json"

HOST = os.environ.get("AUTOTRADE_HOST", "0.0.0.0")
PORT = int(os.environ.get("AUTOTRADE_PORT", "8765"))

# Fast and inexpensive by default. Override from the Windows environment if desired.
DEEPSEEK_MODEL = os.environ.get("AUTOTRADE_DEEPSEEK_MODEL", "deepseek-flash")
DEEPSEEK_REASONING_EFFORT = os.environ.get("AUTOTRADE_DEEPSEEK_REASONING", "none")
DEEPSEEK_MAX_TOKENS = max(128, min(1200, int(os.environ.get("AUTOTRADE_DEEPSEEK_MAX_TOKENS", "300"))))
AI_CACHE_SECONDS = max(15, min(900, int(os.environ.get("AUTOTRADE_AI_CACHE_SECONDS", "120"))))
