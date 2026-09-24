# 保守ガイド

## ビルドとテスト

Windows/PowerShellとAndroid SDK 37を使います。

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug --no-daemon --max-workers=1
```

端末へ更新する場合は `adb install -r app\build\outputs\apk\debug\app-debug.apk` を使います。`-r` はアプリ専用データを保持します。モジュールやscopeを反映するときは対象アプリだけを `adb shell am force-stop <package>` して再度起動してください。端末再起動は不要です。

Wallet/GMSからProviderを解決できるよう、WallArtはURI prefixのread権限を付与します。Androidはこの付与を端末再起動時に失効させるため、`ProviderAccessReceiver`が`BOOT_COMPLETED`と`MY_PACKAGE_REPLACED`で再付与します。初回インストール後だけはWallArtを一度起動してください。`Failed to find provider info for com.tqmane.wallart.provider` が出た場合は、`adb shell dumpsys activity permissions`で両パッケージへのURI grantを確認してください。

## 構成

- `xposed/WalletDiscovery.java`: DEXを列挙し、券面Drawable・Composeカードスタック・お気に入りタイルを型とメソッド形状で探索します。難読クラス名をhook条件として固定しません。
- `xposed/WalletModule.java`: Walletと、明示的なカード詳細アクション、および正確な `TapActivity` / `TAP_EVENT` の組み合わせだけを対象にします。他のGMS画面では元の表示を通します。タップ確認ではIntentを解析せず、表示中の券種または直前のWallet選択だけで照合します。
- `xposed/CardArtRuntime.java`: カード文脈と描画を結び、設定画像を読み込みます。元画像はWalletのDrawableまたは許可済みの公開画像から端末内プレビューを作ります。
- `CardIdentity.java`: stable IDをSHA-256化し、異なるプロセス間の一致用fingerprintもハッシュだけで保持します。完全なPAN、URL、認証情報は設定・ログに保存しません。
- `storage/CardStore.java` / `CardArtProvider.java`: 設定と画像をアプリ専用領域に保存し、Wallet/GMSからはread-onlyで読み取れるようにします。呼び出し元の許可パッケージはscope変更時に確認してください。
- `MainActivity.kt`: Material 3 Expressive UI、Photo Picker、カード比率の切り抜き。

## LSPosed scope

- `app/src/main/resources/META-INF/xposed/scope.list` は `staticScope=true` なので選択式ではありません。
- 固定scope: `com.google.android.apps.walletnfcrel`, `com.google.android.gms`

GMS scopeはパッケージ単位ですが、実行時処理はカード詳細とタップ確認Activityの券面Viewに限定します。TapAndPayの決済API、NFC/HCE、認証や取引データには触れません。実決済を起動するテストは禁止です。scopeを変えた場合はAPKを更新し、Wallet/GMSプロセスだけ再起動します。端末再起動は不要です。

## データと画像

- カスタム券面: `files/card_art/<sha256>.<png|jpg|webp>`
- 元画像プレビュー: `files/original_art/<sha256>.jpg`
- 元URLは保存しません。Composeからの追加取得はHTTPS、Google画像ホスト、query/fragment/user-infoなしのURLだけに限定し、レスポンスサイズと画像寸法を検査します。
- Resetはそのカードのカスタム画像だけを削除し、元画像プレビューは残します。
- `strings.xml` は英語を既定、`values-ja/strings.xml` は日本語です。言語を増やすときは翻訳リソースと `locales_config.xml` を同時に更新してください。

## 更新時チェックリスト

1. 対象Wallet APKのversionとDEXを確認し、カード券面の描画経路を再特定する。
2. Walletのホーム、カード一覧/詳細、Suica/QUICPayタイルを端末で開き、自動検出と描画を確認する。
3. 画像選択、切り抜き、Fit mode、Reset、元画像プレビュー、日本語/英語表示を確認する。
4. GMS対応を使う場合はPayActivityだけを確認し、他のGMS画面やWallet外の支払い処理へフックが漏れていないことを確認する。
5. `testDebugUnitTest assembleDebug` を実行し、`git diff --check` と最終差分を確認する。

調査ログを共有する際はlast fourやURLを伏せてください。Hook discoveryが失敗した場合は元のWallet/GMS表示へフォールバックし、決済処理へのhookやセキュリティ回避は追加しません。
