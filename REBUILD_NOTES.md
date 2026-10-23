# コード健康診断メモ

## 今回の全体見直しで修正したもの
- 更新1回につき3本出ていた通信を、`/v1/dashboard` 1本へ統合。
- Android側の重複したHTTP実行コードを共通化。
- AI分析中の連打による重複API呼び出しを防止。
- DeepSeekの既定を高コストなPro高推論からFlash非推論へ変更。
- AIへ渡す市場JSONを圧縮し、最大12銘柄へ制限。
- AI出力の理由・リスク・確信度を実際に画面へ表示し、捨てていた出力を解消。
- 入力 / 出力 / 合計 / キャッシュ / 推論トークンを表示。
- 本日のAI使用量をWindows側で累積保存。
- 新鮮な市場データが無い時はAIを呼ばない。
- timestamp無しを「新鮮」と扱っていた判定を修正。
- 同じ銘柄が急騰と出来高で二重表示される問題を統合表示へ修正。
- Excel日時がJSON化できない可能性を修正。
- HMAC署名へHTTPメソッドとパスを追加。
- Windows→Androidの成功応答にもHMAC署名を追加し、LAN上の応答改ざんを検知。
- 更新ボタンの多重実行を防止。
- AI分析をWindows側でも同時実行1件に制限。
- Excel同期の二重起動をロックで防止。
- 相場鮮度判定を1回の更新中に何度も繰り返していた処理を1回へ集約。
- ローカル数値判定の相場感とDeepSeekのAI判断が同じstateを上書きしていたため分離。
- 切断・再ペアリング後に以前のAI結果が残る表示をクリア。
- Android Keystore鍵が壊れた場合に再ペアリング不能になるケースを自己回復するよう修正。
- ダークモード時もステータスバー/ナビゲーションバーが白固定になるテーマ不整合を修正。
- Windows DPAPI呼び出しのctypes引数型を明示し、Windows API境界を堅牢化。
- 接続テスト失敗時に誤った接続情報を保存しないよう修正。
- 未実装なのに求めていた通知権限を削除。
- 未使用のACCESS_NETWORK_STATE権限を削除。
- 未使用のAndroid Navigation Compose依存を削除。
- 未使用のAndroid health呼び出しとサーバーhealth endpointを削除。
- 実トレード履歴が無いのに孤立していた損益分析モジュールを削除し、未実装機能を実装済みに見せないよう整理。

## 検証
- Windows Python全モジュール: compileall成功。
- Windows単体テスト: 6 passed。
- Android/Windows暗号固定ベクトル: Kotlinを単体コンパイルして要求署名・応答署名の一致を確認。
- Android用のCryptoEnvelope JVMテスト2件も同梱。
- DeepSeek API仕様（usage、モデル名、thinking設定）を公式ドキュメントと再照合。

## 環境依存で未検証
- Android SDKがこの実行環境に無いためAPKの実ビルドは未実施。
- MarketSpeed II RSS + Excel COMの実機接続は利用者Windows環境で必要。
- DeepSeek実API呼び出しは実キー無しのため未実行。
