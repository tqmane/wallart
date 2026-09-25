# Wallet Art

[English](README.md) | **日本語**

Google Walletのカード券面をカードごとにカスタマイズする、Material 3 ExpressiveのLSPosedモジュールです。画像は端末内に保存し、決済データやNFC動作には触れません。

## スクリーンショット

### WallArt 設定アプリ (Material 3 Expressive)

カードごとに券面画像をプレビューしながら、選択・切り抜き・配置モードを直感的に設定できます。

<p align="center">
  <img src="docs/images/wallart-app-1.png" width="31%" alt="WallArt 設定画面 1" />
  <img src="docs/images/wallart-app-2.png" width="31%" alt="WallArt 設定画面 2" />
  <img src="docs/images/wallart-crop.png" width="31%" alt="カード比率で切り抜き" />
</p>

<p align="center">
  <sub>左・中央: WallArt 設定アプリ ｜ 右: カード規格（12dp角丸）の切り抜きエディタ</sub>
</p>

### Google Wallet での表示例

ホーム画面のメインカードやショートカットタイル、各カードの詳細画面まで美しいカスタム券面が反映されます。

<p align="center">
  <img src="docs/images/wallet-home.png" width="23%" alt="Google Wallet ホーム画面" />
  <img src="docs/images/wallet-suica.png" width="23%" alt="Suica 詳細画面" />
  <img src="docs/images/wallet-debit.png" width="23%" alt="デビットカード 詳細画面" />
  <img src="docs/images/wallet-card-view.png" width="23%" alt="カード全画面表示" />
</p>

<p align="center">
  <sub>左から: Google Wallet ホーム ｜ Suica 詳細 ｜ デビットカード詳細 (みんなの銀行) ｜ カード全画面表示</sub>
</p>

## 対応範囲

- Wallet: `com.google.android.apps.walletnfcrel` — `26.37.981219770`
- GMSカード詳細とタップ確認: 正確な `PayActivity` 詳細アクション、および `TapActivity` / `TAP_EVENT` の表示券面だけを置換（GMS scopeが必要）。Activityフックは `com.google.android.gms.ui` のみに限定し、GMS/HCE本体プロセスには入れません。
- LSPosed Modern API: 102+
- 設定アプリ: 日本語・英語。Android 13以降のアプリごとの言語設定にも対応。

WalletとGMSは静的scopeとしてAPKに同梱します。GMSはパッケージ単位でscopeに入りますが、Activityフックは観測済みの `.ui` プロセス内で正確な詳細・タップ確認アクションだけに限定します。TapAndPay、HCE、NFCサービスはフックしません。

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

Wallet `26.37.981219770` のホームカード、Suica/QUICPayお気に入り券面、GMSカード詳細はNothing A059 / Android 17で確認済みです。タップ確認画面は `TapActivity` の正確なクラス名と `TAP_EVENT` アクションに限定した券面描画を追加しましたが、実際の決済を発生させる検証はしていません。

## セキュリティ境界

WallArt changes only the local visual representation of card artwork. It does not modify payment credentials, NFC/HCE, TapAndPay, authentication, attestation, Google Play Integrity, root detection, or payment decisions.

## License

Copyright (C) 2026 tqmane. Licensed under [GPL-3.0-only](LICENSE).
