from __future__ import annotations
import os
from pathlib import Path
from . import config
from .dpapi import protect, unprotect


def _save(path: Path, value: bytes) -> None:
    path.write_bytes(protect(value))


def _load(path: Path) -> bytes | None:
    if not path.exists():
        return None
    return unprotect(path.read_bytes())


def get_or_create_pairing_secret() -> bytes:
    current = _load(config.PAIRING_FILE)
    if current and len(current) == 32:
        return current
    secret = os.urandom(32)
    _save(config.PAIRING_FILE, secret)
    return secret


def set_deepseek_key(value: str) -> None:
    value = value.strip()
    if not value or len(value) < 10 or len(value) > 512:
        raise ValueError("Invalid API key length")
    _save(config.DEEPSEEK_FILE, value.encode("utf-8"))


def get_deepseek_key() -> str | None:
    raw = _load(config.DEEPSEEK_FILE)
    return raw.decode("utf-8") if raw else None


def has_deepseek_key() -> bool:
    try:
        return config.DEEPSEEK_FILE.exists() and config.DEEPSEEK_FILE.stat().st_size > 0
    except OSError:
        return False
