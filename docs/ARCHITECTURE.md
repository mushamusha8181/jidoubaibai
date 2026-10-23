# アーキテクチャ

```
Android「自動売買」
  ├─ Home: 相場感 / AI分析 / AI使用量 / 重要指標
  ├─ Movers: 監視対象の急騰 / 急落 / 出来高急増
  ├─ Guide: 初期設定と使い方
  └─ Settings: Windowsペアリング / DeepSeekキー登録
          │
          │ 要求/応答HMAC + APIキー転送はAES-GCM
          ▼
Windows Bridge
  ├─ Dashboard API（相場・指標・AI使用量を1通信で返す）
  ├─ DPAPI Secret Store
  ├─ DeepSeek Client
  ├─ AI Token Usage Store
  ├─ Market Analyzer
  ├─ Economic Calendar Reader
  └─ Excel RSS Read-only Sync
          │
          ▼
Microsoft Excel + MarketSpeed II RSS
  └─ 市場データの読み取りのみ（現版）
```

## 重複削減
Androidの更新処理は、接続確認→相場→経済指標という3本の逐次通信を廃止し、認証済み `/v1/dashboard` 1本に統合した。API認証に成功してダッシュボードを取得できれば、それ自体が接続確認になるため別のpingは不要。

## 注目銘柄
- 急騰: 前日比 +2%以上
- 急落: 前日比 -2%以下
- 出来高急増: 通常出来高比 2倍以上

複数条件に該当する同一銘柄は1行へ統合し、カテゴリだけ複数表示する。

## AI前処理
AIへ生データを大量送信せず、Windows側で騰落数、平均、中央値、標準偏差、平均スプレッドを計算する。AIへ送る注目銘柄は最大12件。
