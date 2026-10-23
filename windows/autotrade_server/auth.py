from __future__ import annotations
import base64
import hashlib
import hmac
import time
import json
from collections import OrderedDict
from fastapi import HTTPException, Request, Response
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from .secrets import get_or_create_pairing_secret

_NONCES: OrderedDict[str, int] = OrderedDict()
WINDOW_SECONDS = 90


def derive(info: bytes) -> bytes:
    return HKDF(
        algorithm=hashes.SHA256(),
        length=32,
        salt=b"\x00" * 32,
        info=info,
    ).derive(get_or_create_pairing_secret())


def _b64u_decode(value: str) -> bytes:
    return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))


def decrypt_api_key(nonce_b64: str, ciphertext_b64: str) -> str:
    aes = AESGCM(derive(b"autotrade-secret-v1"))
    plain = aes.decrypt(
        _b64u_decode(nonce_b64),
        _b64u_decode(ciphertext_b64),
        b"deepseek-api-key-v1",
    )
    return plain.decode("utf-8")


def _purge(now: int) -> None:
    expired = [k for k, ts in _NONCES.items() if now - ts > WINDOW_SECONDS * 2]
    for key in expired:
        _NONCES.pop(key, None)


async def require_auth(request: Request) -> None:
    ts_raw = request.headers.get("X-AT-Timestamp", "")
    nonce = request.headers.get("X-AT-Nonce", "")
    supplied = request.headers.get("X-AT-Signature", "")
    try:
        ts = int(ts_raw)
    except ValueError as exc:
        raise HTTPException(401, "invalid timestamp") from exc

    now = int(time.time())
    if abs(now - ts) > WINDOW_SECONDS:
        raise HTTPException(401, "expired request")

    _purge(now)
    if not nonce or nonce in _NONCES:
        raise HTTPException(401, "replayed request")

    body = await request.body()
    body_hash = hashlib.sha256(body).hexdigest()
    canonical = f"{request.method}.{request.url.path}.{ts_raw}.{nonce}.{body_hash}".encode()
    expected = hmac.new(derive(b"autotrade-auth-v1"), canonical, hashlib.sha256).digest()
    try:
        supplied_bytes = _b64u_decode(supplied)
    except Exception as exc:
        raise HTTPException(401, "invalid signature") from exc

    if not hmac.compare_digest(expected, supplied_bytes):
        raise HTTPException(401, "invalid signature")

    _NONCES[nonce] = now
    if len(_NONCES) > 4096:
        _NONCES.popitem(last=False)


def signed_json(request: Request, payload: dict, status_code: int = 200) -> Response:
    body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    body_hash = hashlib.sha256(body).hexdigest()
    request_nonce = request.headers.get("X-AT-Nonce", "")
    canonical = f"RESP.{request.url.path}.{request_nonce}.{body_hash}".encode()
    signature = hmac.new(derive(b"autotrade-auth-v1"), canonical, hashlib.sha256).digest()
    encoded = base64.urlsafe_b64encode(signature).decode().rstrip("=")
    return Response(
        content=body,
        status_code=status_code,
        media_type="application/json",
        headers={
            "X-AT-Response-Signature": encoded,
            "Cache-Control": "no-store",
        },
    )
