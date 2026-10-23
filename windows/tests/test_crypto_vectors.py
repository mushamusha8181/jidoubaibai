import base64
import hashlib
import hmac
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.kdf.hkdf import HKDF


def derive(secret: bytes, info: bytes) -> bytes:
    return HKDF(algorithm=hashes.SHA256(), length=32, salt=b"\x00" * 32, info=info).derive(secret)


def encode(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).decode().rstrip("=")


def test_request_and_response_vectors_match_android():
    secret = bytes(range(32))
    key = derive(secret, b"autotrade-auth-v1")
    body_hash = hashlib.sha256(b"{}").hexdigest()

    request = f"POST./v1/analysis/compare.1700000000.nonce.{body_hash}".encode()
    request_sig = encode(hmac.new(key, request, hashlib.sha256).digest())
    assert request_sig == "ZjIbN4awF3swgkOLVD0etZO-MTguBLDOgYaK--YtaaE"

    response = f"RESP./v1/analysis/compare.nonce.{body_hash}".encode()
    response_sig = encode(hmac.new(key, response, hashlib.sha256).digest())
    assert response_sig == "fD6xhAI4q4Lizfy7hiuAhPBfFtXsWSu8bGuaXzKfptA"

    other = f"POST./v1/secrets/deepseek.1700000000.nonce.{body_hash}".encode()
    assert hmac.new(key, other, hashlib.sha256).digest() != hmac.new(key, request, hashlib.sha256).digest()
