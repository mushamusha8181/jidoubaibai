from datetime import datetime, timezone
from autotrade_server.excel_sync import _timestamp


def test_timestamp_serializes_datetime():
    value = datetime(2026, 10, 23, 3, 0, tzinfo=timezone.utc)
    assert _timestamp(value).startswith("2026-10-23T03:00:00")


def test_missing_timestamp_stays_missing():
    assert _timestamp(None) is None
