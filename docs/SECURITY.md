# セキュリティ設計

## 秘密情報
- DeepSeek APIキーはAndroidへ永続保存しない。
- 設定画面は `FLAG_SECURE` でスクリーンショット/画面録画を抑止する。
- APIキーはペアリング秘密からHKDF-SHA256で導出した鍵でAES-256-GCM暗号化してWindowsへ送る。
- Windows側では受信後にDPAPIで暗号化保存する。
- Androidに保存するペアリング秘密はAndroid KeystoreのAES-GCM鍵で暗号化する。
- Androidバックアップは無効。

## 通信
- API要求はHMAC-SHA256で認証し、成功応答にもHMAC署名を付けてAndroid側で改ざん検知する。
- 署名対象は HTTPメソッド + APIパス + timestamp + nonce + SHA-256(body)。
- 90秒を超えた要求、nonce再利用、署名不一致は拒否する。
- HTTP接続はAndroid側でプライベートLAN / loopbackに限定する。
- APIキー本体はHMACだけに頼らずAES-GCMで暗号化する。

## Android権限
現在版で必要な権限は通常権限 `INTERNET` のみ。ユーザーへ表示される実行時権限ダイアログは0件。

通知機能はまだ無いため、POST_NOTIFICATIONSを先に要求する処理は削除した。位置情報、写真、連絡先、マイク、ストレージ、カメラも要求しない。

## 実装していないもの
- 楽天証券パスワード/暗証番号のAndroid保存
- DeepSeekへの証券口座資格情報送信
- 実資金の注文処理
- 未取得データを架空値で埋める処理
