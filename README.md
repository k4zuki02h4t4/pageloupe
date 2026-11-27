# PageLoupe

Chrome DevTools を内蔵した Android 用 Web ブラウザです。通常のブラウズ機能（タブ、シークレットタブ、履歴、ブックマーク）に加えて、開いているページに対して本物の Chrome DevTools（Elements / Console / Network など）をアプリ内で使えます。ページ全体のオンデバイス翻訳（ML Kit、翻訳先は日本語）も備えています。

- 対応 OS: Android 13 以降（minSdk 33）
- パッケージ名: jp.kaleidpixel.pageloupe
- 開発言語: Kotlin（ビルドは Gradle 8.10.2 + Android Gradle Plugin 8.5.2）
- ライセンス: Apache License 2.0（[LICENSE](LICENSE) を参照）

## ビルド方法

依存バイナリ（AAR / jar）はリポジトリに含めていません。Gradle が配布元（Google Maven / Maven Central）から取得し、`gradle/verification-metadata.xml` に記録した SHA-256 と照合してから使います（照合に失敗するとビルドが止まります）。依存の内訳と出所は [docs/dependencies.md](docs/dependencies.md) にあります。

### 必要なツール

| ツール | バージョン | 取得先・備考 |
|---|---|---|
| JDK | 17 | 任意の配布（Temurin など）。`JAVA_HOME` を設定してください |
| Gradle | 8.10.2 | 後述のとおり wrapper を再生成するか、単体で導入してください |
| Android SDK | platform 34 / build-tools 34.0.0 | command line tools の `sdkmanager` で `"platforms;android-34"` と `"build-tools;34.0.0"` を導入。場所は `local.properties` の `sdk.dir` か環境変数 `ANDROID_HOME` で指定 |
| python3 | 3 | ビルド後のマニフェスト検証（`app/verify_manifest.py`）に使用 |

Gradle wrapper の jar はこのリポジトリに含めていません（配布形態の都合）。wrapper スクリプトと設定は含まれているので、Gradle 8.10.2 を一度だけ使って再生成できます。

```sh
gradle wrapper --gradle-version 8.10.2 --gradle-distribution-sha256-sum 31c55713e40233a8303827ceb42ca48a47267a0ad4bab9177123121e71524c26
# gradle/wrapper/gradle-wrapper.jar を再生成（配布物の SHA-256 は gradle.org の公開値）
```

動作確認済みのホストは Linux（x86_64）です。他の OS では未検証です。

### 手順

```sh
./gradlew :app:assembleDebug   # app/build/outputs/apk/debug/app-debug.apk ができる
```

パッケージングの直後に、出荷マニフェストが許可リストと一致することを自動で検証します（コンポーネントとその `exported` は `app/manifest-components.txt`、権限は `app/manifest-permissions.txt`、要素や属性の構造目録は `app/manifest-structure.txt`。あわせて `debuggable` 等のアプリ属性の値も検査します。不一致ならビルドが失敗し、APK は `.rejected` に改名されます）。依存モジュールの集合は、パッケージングの前に `app/dependency-closure.txt` と一致することを検証します（不一致なら APK は作られません）。

### 署名についての注意

APK はデバッグ鍵で署名されます。鍵ファイルはリポジトリに含まれていません（`app/debug.keystore` に、パスワード `android` / エイリアス `androiddebugkey` の鍵を用意してください。無い場合は Android Studio などが生成する通常の debug.keystore をその場所に置いても構いません。その場合、別の鍵で署名された既存のインストールには上書きできません。新規インストール専用になります）。配布用のリリース署名ではありません。鍵を差し替える場合は `app/build.gradle.kts` の `signingConfigs` を変更してください。

### テスト

JVM 単体テストがあります（`tests/` 配下、JUnit ではなく main() 方式のチェックランナーです）。

```sh
./gradlew :app:runUnitTests
```

失敗したスイートがあっても全件を実行したい場合は `--continue` を付けてください（付けない場合、Gradle は最初の失敗で止まります）。`:app:check`（および `build`）にも組み込まれています。

## 実装上の注意

- 翻訳エンジンのネイティブライブラリは arm64-v8a のみを同梱します。他の ABI では翻訳機能だけが読み込めません（ブラウザ自体は動作します）。
- DevTools のフロントエンド本体は、Google が配布する公式の Chrome DevTools フロントエンドで、利用時に `https://chrome-devtools-frontend.appspot.com` から HTTPS で読み込まれます。フロントエンドは性質上、開いているページを完全に操作できます（それが DevTools の機能です）。アプリと WebView の間の接続は端末内のみです（ローカルホストのみで待ち受けるプロキシ＋セッションごとのトークン）。
- 翻訳の言語モデルは、初回利用時に言語ごとにユーザーの同意を得てダウンロードします。
- 依存ライブラリの例外（androidx.webkit / androidx.core と ML Kit 一式）については [docs/dependencies.md](docs/dependencies.md) に、採用理由と残余リスクを含めて記録しています。
