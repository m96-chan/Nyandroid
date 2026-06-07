# Nyandroid — Claude Code ルール

## プロジェクト概要
kitty 互換の GPU アクセラレーテッドターミナルエミュレータ for Android。
Android 16 の Debian VM に SSH 接続して動作する。

## ビルド & テスト

```bash
# 環境変数（macOS + Android Studio）
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"

# ビルド
./gradlew :app:assembleDebug

# JVM テスト（エミュレータ層のユニットテスト）
./gradlew :app:test

# 実機インストール & 起動
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop dev.nyandroid.terminal
adb shell am start -n dev.nyandroid.terminal/.MainActivity

# スクリーンショット
adb exec-out screencap -p > /tmp/nyandroid_screenshot.png
```

## ワークフロー（必須）

機能実装時は以下の手順を**すべて**実行すること：

1. **JVM テスト**: `./gradlew :app:test` が通ること
2. **ビルド**: `./gradlew :app:assembleDebug` が成功すること
3. **実機テスト**: Pixel 10a にインストールし、動作をスクリーンショットで確認
4. **コミット**: `Closes #N` をコミットメッセージに含める
5. **プッシュ**: `git push origin main`
6. **ISSUE 報告**: `gh issue comment N` で実装内容と検証結果を報告（自動クローズされていない場合は `gh issue close N` も実行）

## コミットメッセージ規約

```
feat: 短い説明 (#Issue番号)

詳細な説明（何を、なぜ）。

- 変更点1
- 変更点2

Closes #N

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
```

## アーキテクチャ

```
SshBackend (SSH→Debian VM)
    ↓ onOutput
TerminalEmulator (VtParser + TerminalGrid, lock で保護)
    ↓ onChange
RenderThread (HandlerThread, コアレス)
    ↓ snapshot → renderFrame
GlesGpuBackend (OpenGL ES 3.x, 1 draw call)
```

- **Backend**: `TerminalBackend` インターフェース。現在は `SshBackend`
- **Emulator**: 純 Kotlin、Android 依存なし、JVM テスト可能
- **Renderer**: `GpuBackend` インターフェース。将来 Vulkan 差し替え可能
- **スレッド**: PTY reader / ssh-reader / ssh-writer / render (HandlerThread) / UI

## ターゲット

- デバイス: Pixel 10a (arm64)
- Android: 16 (API 36)
- NDK: 27.2.12479018
- 16KB ページアライメント必須（CMakeLists.txt の `max-page-size=16384`）

## 既知の制約

- SSH 認証は Ed25519 公開鍵（BouncyCastle プロバイダ必須）
- `ip neigh` は untrusted_app で使えない → `NetworkInterface` + サブネットスキャンで VM IP 発見
- SSH write は UI スレッドから呼ばれるため専用 writer thread が必要（`NetworkOnMainThreadException` 回避）
- BouncyCastle: Android のストリップ版を完全版で置換（`Security.removeProvider("BC")`）

## ISSUE 管理

GitHub Issues でタスク管理。実装順は Issue 番号順。
各 Issue は実装完了後にコメントで報告しクローズすること。
