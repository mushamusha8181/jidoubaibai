from autotrade_server import config
from autotrade_server.settings_store import get_settings, update_settings


def test_ai_daily_token_limit_persists(tmp_path, monkeypatch):
    path = tmp_path / "settings.json"
    monkeypatch.setattr(config, "SETTINGS_FILE", path)
    assert get_settings()["ai_daily_token_limit"] == 20_000
    assert update_settings(12_345)["ai_daily_token_limit"] == 12_345
    assert get_settings()["ai_daily_token_limit"] == 12_345
    assert update_settings(0)["ai_daily_token_limit"] == 0
