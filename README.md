# 🇱🇦 老挝语翻译器 - Android 原生版

老挝语 ↔ 中文 双向翻译 Android 应用，支持语音输入和语音播报。

## ✨ 功能

- **双向翻译**：老挝语 → 中文 / 中文 → 老挝语
- **语音输入**：点击麦克风按钮，说话即翻译（使用 Android 原生 SpeechRecognizer）
- **语音播报**：翻译结果可朗读（使用 Android 原生 TextToSpeech）
- **一键复制**：翻译结果快速复制到剪贴板
- **翻译历史**：自动保存最近翻译记录
- **Material Design**：清爽的现代 UI 设计

## 🛠 技术栈

- **语言**：Kotlin
- **架构**：ViewBinding + Coroutines
- **翻译 API**：MyMemory Translation API（免费，无需 API Key）
- **语音识别**：Android SpeechRecognizer
- **语音合成**：Android TextToSpeech
- **网络请求**：OkHttp 4
- **UI**：Material Components

## 📱 系统要求

- Android 7.0 (API 24) 及以上
- 需要网络连接（翻译功能）
- 需要麦克风权限（语音输入功能）

## 🚀 构建方式

### 使用 Android Studio

1. 用 Android Studio 打开项目
2. 等待 Gradle 同步完成
3. 点击 Run ▶️ 即可

### 命令行构建

```bash
./gradlew assembleDebug
# APK 输出位置: app/build/outputs/apk/debug/app-debug.apk
```

## 📄 License

MIT
