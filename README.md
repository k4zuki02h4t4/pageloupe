# PageLoupe

Chrome DevTools を内蔵した Android 用 Web ブラウザです。通常のブラウズ機能（タブ、シークレットタブ、履歴、ブックマーク）に加えて、開いているページに対して本物の Chrome DevTools（Elements / Console / Network など）をアプリ内で使えます。ページ全体のオンデバイス翻訳（ML Kit、翻訳先は日本語）も備えています。

- 対応 OS: Android 13 以降（minSdk 33）
- パッケージ名: jp.kaleidpixel.pageloupe
- 開発言語: Kotlin（Gradle は使わず、後述の手動パイプラインでビルドします）
- ライセンス: Apache License 2.0（[LICENSE](LICENSE) を参照）

## ビルド方法

依存バイナリ（AAR / jar）はリポジトリに含めていません。パッケージマネージャ相当の配布元（Google Maven / Maven Central）から取得し、記録済みの SHA-256 と照合してから使う方式です。出所の一覧は [app/libs/README.md](app/libs/README.md) にあります。

### 必要なツール

| ツール | バージョン | 取得先・備考 |
|---|---|---|
| JDK | 17 | 任意の配布（Temurin など） |
| Kotlin コンパイラ | 2.0.21 | [Kotlin の GitHub リリース](https://github.com/JetBrains/kotlin/releases/tag/v2.0.21) の `kotlin-compiler-2.0.21.zip`（SHA-256: `0352c0a45bd22f80f6b26e485cd04da8047baa5de54865281fb9f89a4a7bcf2a`、展開前に照合してください）。stdlib のハッシュはビルド時にも照合します |
| Android SDK | platform 34 / build-tools 34.0.0 | command line tools の `sdkmanager` で `"platforms;android-34"` と `"build-tools;34.0.0"` を導入 |
| R8 | 8.7.18（jar 単体） | 下記 URL から取得。SHA-256 をビルド時に照合します |
| その他 | curl / unzip / python3 / sha256sum | 依存取得とビルド補助に使用 |

R8 jar の取得先（SHA-256: `58366f77067207c39a17d469de7b05701d2877212a9c55201bcb0af43e59e903`）:

```
https://dl.google.com/dl/android/maven2/com/android/tools/r8/8.7.18/r8-8.7.18.jar
```

動作確認済みのホストは Linux（x86_64）です。他の OS では未検証です（特に macOS では `sha256sum` など GNU 系コマンドの有無に注意してください）。

ツールの指定は環境変数で行います。`PAGELOUPE_TOOLS` を指定すると、その配下のレイアウトが一括で使われます（この指定がある場合、`JAVA_HOME` や `ANDROID_HOME` より優先されます）。

```
<PAGELOUPE_TOOLS>/
  jdk/                  JDK 17
  sdk/                  Android SDK（platforms/android-34 と build-tools/34.0.0 を含む）
  kotlinc/              Kotlin コンパイラ 2.0.21
  r8/r8-8.7.18.jar      R8 jar
```

`PAGELOUPE_TOOLS` を指定しない場合は、既存の `JAVA_HOME` / `ANDROID_HOME`（または `ANDROID_SDK_ROOT`）が使われます。Kotlin と R8 だけは `KOTLINC_HOME` / `R8_JAR` で個別に上書きできます。ビルド開始時に、解決されたツールパスと JDK のバージョンが検査されます。

### 手順

```sh
./app/fetch_libs.sh   # 依存を取得し、SHA256SUMS と照合する
./app/build.sh        # app/build/pageloupe.apk ができる
```

`build.sh` は aapt2 → kotlinc → javac（生成 R クラス）→ D8 → zipalign → 署名 → マニフェスト検証、という手動パイプラインです。ソース配置は標準の Gradle レイアウト（`src/main/...`）に従っています。

### 署名についての注意

生成される APK は、ビルド時に自動生成されるデバッグ鍵（`app/build/debug.keystore`）で署名されます。配布用のリリース署名ではありません。鍵を差し替える場合は `app/build.sh` の署名まわりを変更してください。

### テスト

JVM 単体テストがあります。前提として、先に `app/fetch_libs.sh` を実行してください（テストが使うライブラリの派生 jar と、テスト専用 jar の取得も `fetch_libs.sh` が行います）。

```sh
./tests/run_tests.sh
```

## 実装上の注意

- 翻訳エンジンのネイティブライブラリは arm64-v8a のみを同梱します。他の ABI では翻訳機能だけが読み込めません（ブラウザ自体は動作します）。
- DevTools のフロントエンド本体は、Google が配布する公式の Chrome DevTools フロントエンドで、利用時に `https://chrome-devtools-frontend.appspot.com` から HTTPS で読み込まれます。フロントエンドは性質上、開いているページを完全に操作できます（それが DevTools の機能です）。アプリと WebView の間の接続は端末内のみです（ローカルホストのみで待ち受けるプロキシ＋セッションごとのトークン）。
- 翻訳の言語モデルは、初回利用時に言語ごとにユーザーの同意を得てダウンロードします。
- 依存ライブラリの例外（androidx.webkit / androidx.core と ML Kit 一式）については [app/libs/README.md](app/libs/README.md) に、採用理由と残余リスクを含めて記録しています。
