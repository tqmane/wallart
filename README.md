# Wallet Art

Google Walletのカード券面をカードごとにカスタマイズする、Material 3 ExpressiveのLSPosedモジュールです。画像は端末内に保存し、決済データやNFC動作には触れません。

## 対応範囲

- Wallet: `com.google.android.apps.walletnfcrel` — `26.37.981219770`
- Suica詳細: `com.google.android.gms` の `com.google.android.gms.pay.main.PayActivity` のみ（GMS scopeを追加した場合）
- LSPosed Modern API: 102+
- 設定アプリ: 日本語・英語。Android 13以降のアプリごとの言語設定にも対応。

WalletとGMSは静的scopeとしてAPKに同梱します。GMSはプロセス単位でscopeに入りますが、実行時フックは対象のPayActivityに限定し、Google Play servicesの他の画面・決済処理には適用しません。

## ビルド

Android SDK 37を設定し、PowerShellから実行します。

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug --no-daemon --max-workers=1
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## インストール

1. APKを通常のアプリとしてインストールします。
2. LSPosed/VectorでWallArtを有効にします。WalletとGMSのscopeはAPKに固定されています。
3. 初回インストール後にWallArtを一度開き、WalletとGMS Pay UIへのローカル画像読み取り権限を付与します。以後は端末起動時とWallArt更新後に自動で再付与します。
4. Walletを開いて券面を検出し、WallArtで対象カードの画像を選択します。Photo Picker後にカード比率の切り抜きを調整できます。
5. 表示済み画面を更新するには、対象アプリをforce-stopしてから再度開きます。端末再起動は不要です。

PNG/JPEG/WebPに対応しています。画像はアプリ専用ストレージへコピーし、Resetで元の券面に戻します。元画像プレビューはWallet自身の描画結果、または認証情報・クエリのない公開Google画像URLからローカルに作成します。URLそのものは保存しません。

## 保守

- [docs/research.md](docs/research.md): 対象APKの解析根拠とGMS詳細画面の境界
- [docs/maintenance.md](docs/maintenance.md): モジュール構成、scope、検証、更新手順

## 既知の検証状況

Wallet `26.37.981219770` のホームカードとSuica/QUICPayお気に入り券面はNothing A059 / Android 17で確認済みです。GMS Pay詳細の端末実描画確認は、更新APKを入れてscope反映後に実施します（端末上のGMS version: `26.34.65`）。

## セキュリティ境界

WallArt changes only the local visual representation of card artwork. It does not modify payment credentials, NFC/HCE, TapAndPay, authentication, attestation, Google Play Integrity, root detection, or payment decisions.

## License

Copyright (C) 2026 tqmane. Licensed under [GPL-3.0-only](LICENSE).
