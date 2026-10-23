from __future__ import annotations
"""Read-only bridge from an already-open Excel workbook to market_snapshot.json.

Expected worksheet: MARKET
Columns A:J, row 2 onward:
code, name, price, prev_close, volume, avg_volume, bid, ask, high, low
Optional K: ISO-8601 timestamp or an Excel date/time value.

The workbook itself may contain MarketSpeed II RSS formulas. This module NEVER writes
order formulas and NEVER clicks/executes broker orders.
"""
import json
import time
from datetime import datetime, timezone
from .config import MARKET_FILE

WORKBOOK = "AutoTradeMarket.xlsx"
SHEET = "MARKET"
INTERVAL_SECONDS = 2.0


def _timestamp(value) -> str | None:
    if isinstance(value, datetime):
        dt = value
        if dt.tzinfo is None:
            dt = dt.astimezone()
        return dt.astimezone(timezone.utc).isoformat()
    if value:
        return str(value)
    # Do not pretend a quote is fresh just because the Excel bridge read it now.
    # Column K must contain an actual quote/update timestamp.
    return None


def read_once() -> dict:
    import win32com.client  # type: ignore

    excel = win32com.client.GetActiveObject("Excel.Application")
    wb = excel.Workbooks(WORKBOOK)
    ws = wb.Worksheets(SHEET)
    used = ws.UsedRange.Value
    if not isinstance(used, tuple) or len(used) < 2:
        return {"source": "MarketSpeed II RSS / Excel", "quotes": []}

    rows = []
    for row in used[1:]:
        if not isinstance(row, tuple):
            continue
        vals = list(row) + [None] * 11
        code, name, price, prev, vol, avg, bid, ask, high, low, ts = vals[:11]
        if not code or not price or not prev:
            continue
        rows.append({
            "code": str(code).strip(),
            "name": str(name or code).strip(),
            "price": price,
            "prev_close": prev,
            "volume": vol or 0,
            "avg_volume": avg or 0,
            "bid": bid,
            "ask": ask,
            "high": high,
            "low": low,
            "timestamp": _timestamp(ts),
        })
    return {"source": "MarketSpeed II RSS / Excel (read-only)", "quotes": rows}


def _acquire_single_instance():
    import msvcrt  # Windows only

    lock_path = MARKET_FILE.with_suffix(".sync.lock")
    handle = open(lock_path, "a+b")
    handle.seek(0)
    if handle.read(1) != b"1":
        handle.seek(0)
        handle.write(b"1")
        handle.flush()
    handle.seek(0)
    try:
        msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
    except OSError:
        handle.close()
        return None
    return handle


def main() -> None:
    instance_lock = _acquire_single_instance()
    if instance_lock is None:
        print("Excel同期はすでに起動しています。二重起動を停止します。")
        return
    print("Excel読取専用同期を開始します。Ctrl+Cで終了。")
    while True:
        try:
            data = read_once()
            tmp = MARKET_FILE.with_suffix(".tmp")
            tmp.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
            tmp.replace(MARKET_FILE)
        except KeyboardInterrupt:
            break
        except Exception as exc:
            # Do not dump workbook content or secrets.
            print(f"Excel同期待機中: {type(exc).__name__}")
        time.sleep(INTERVAL_SECONDS)
    instance_lock.close()


if __name__ == "__main__":
    main()
