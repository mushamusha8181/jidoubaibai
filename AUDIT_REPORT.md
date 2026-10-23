# コード健康診断 — AI比較版

## 結論
今回の改修では「AIを増やす」のではなく、AIを使わない基準系を先に作り、必要な時だけAIを比較対象として呼ぶ構成へ変更した。

## 追加・修正
- 決定論的なAIなし市場判定（0 tokens）
- 同一市場スナップショットでDeepSeekと比較
- スナップショット fingerprint
- 短時間AI結果キャッシュ
- キャッシュ再利用時は今回0 tokens
- 1日AIトークン上限 + API送信前の保守的な予算確認
- AI入力の注目銘柄を最大8件へ縮小
- 最大出力を300 tokensへ縮小
- AIの「confidence %」表示を削除
- システム健康状態
- 注目銘柄のスキャルピング適性スコア
- Android前面時だけ5秒更新
- Excel timestamp欠落時の現在時刻補完を廃止し、fail-closed化
- 旧AI分析エンドポイントを比較エンドポイントへ一本化
- 未使用Android状態フィールドを削除
- 未使用 `ai_snapshot()` ラッパーを削除

## トークン経路
DeepSeekを呼ぶ経路は `/v1/analysis/compare` のみ。

次では呼ばない。
- `/v1/dashboard`
- 前面5秒更新
- 注目銘柄抽出
- 適性スコア
- システム健康状態
- 重要指標表示
- AIなし判定

## セキュリティ
- DeepSeek APIキーをAndroidへ永続保存しない
- Android→Windows秘密情報はAES-GCM
- Windows保存はDPAPI
- Android pairing secretはKeystore
- 要求/成功応答ともHMAC
- nonce replay防止
- 設定画面FLAG_SECURE
- APIキーのログ出力なし

## 権限
Android Manifest: `INTERNET` のみ。
実行時権限は現在0件。

## 重複・死んだコード
- 6行以上のクロスファイル同一コードブロック: 0件
- 旧 `/v1/ai/analyze` 実装/参照: 0件
- 旧AI確信度フィールド: 0件
- 未使用 `ai_snapshot()` ラッパー: 削除

## テスト
- Python compileall: 成功
- pytest: **13 passed**
- CryptoEnvelope単体Kotlinコンパイル: 成功
- Android/Compose全体: Android SDK / Gradle Wrapperがこの環境にないためAPK完全ビルドは未実施

## 意図的に未実装
- 実注文
- 銘柄ごとの自動AI分析
- 秒単位AI分析
- SNSセンチメント
- 巨大チャート
- 大量テクニカル指標
- QRペアリング
- Androidバックグラウンド常駐
- 将来価格を追跡するペーパートレード勝率比較

ペーパートレードは有用だが、将来価格の観測、欠損、取引時間、スリッページ、約定仮定まで正しく設計してから別工程で追加する方が安全。
