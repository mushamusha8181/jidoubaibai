from __future__ import annotations
import socket
import uvicorn
from .config import HOST, PORT, APP_DIR
from .secrets import get_or_create_pairing_secret


def lan_ip() -> str:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80)); return s.getsockname()[0]
    except Exception:
        return "127.0.0.1"
    finally:
        s.close()

if __name__ == "__main__":
    secret = get_or_create_pairing_secret().hex()
    print("=" * 60)
    print("自動売買 Windowsブリッジ")
    print(f"Androidに入力するURL: http://{lan_ip()}:{PORT}")
    print(f"64桁ペアリングコード: {secret}")
    print(f"秘密情報の保存先: {APP_DIR}")
    print("※ ペアリングコードやAPIキーを第三者へ送らないでください。")
    print("=" * 60)
    uvicorn.run("autotrade_server.app:app", host=HOST, port=PORT, log_level="warning")
