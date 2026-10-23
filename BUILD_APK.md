# APKビルド

このプロジェクトには `.github/workflows/android.yml` を同梱しています。
GitHubへpushすると `Build AutoTrade APK` が実行され、成功時に `auto-trade-debug-apk` というArtifactから `app-debug.apk` を取得できます。

ローカルでビルドする場合は Android SDK 35 / JDK 17 / Gradle 8.7 を使用し、`android` ディレクトリで次を実行します。

```bash
gradle :app:assembleDebug --no-daemon
```

出力先:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```
