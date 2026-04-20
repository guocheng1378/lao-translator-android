# 老挝语 ↔ 中文 智能实时翻译器

纯端上运行的 Android App，无需服务器，支持离线使用。

## 功能

- 🧠 **智能语言识别** — 自动判断老挝语/中文，不用手动切换
- 🔄 **双向翻译** — 老挝语→中文 / 中文→老挝语
- 🔊 **自动播报** — 每段翻译结果自动朗读
- 📴 **完全离线** — 所有模型本地运行

## 架构

```
麦克风录音 (2.5s 切片)
    ↓
Whisper.cpp (JNI) → 自动检测语言 + 转写文本
    ↓
ML Kit Translation (离线) → 翻译到另一种语言
    ↓
Android TTS → 语音播报译文
```

## 快速开始

### 1. 下载依赖

```bash
cd lao-translator-android
bash setup.sh
```

下载 whisper.cpp 源码 + small 模型 (460MB)。

### 2. 打开项目

用 Android Studio 打开，等 Gradle 同步。

### 3. 运行

连接真机，点 Run。

## 项目结构

```
app/src/main/
├── cpp/
│   ├── CMakeLists.txt           # 编译配置
│   ├── whisper_jni.cpp          # JNI: 自动语言检测+转写
│   └── whisper/                 # whisper.cpp 源码
├── java/com/lao/translator/
│   ├── stt/
│   │   ├── WhisperManager.kt    # Whisper 封装 (返回text+语言)
│   │   └── AudioRecorder.kt     # 流式录音 (2.5s切片)
│   ├── translate/
│   │   └── TranslationManager.kt # ML Kit 双向翻译
│   ├── tts/
│   │   └── TtsManager.kt        # 双语TTS
│   └── ui/
│       └── MainActivity.kt      # 智能识别逻辑 + UI
└── res/layout/
    └── activity_main.xml
```

## TTS 说明

| 语言 | 方案 | 操作 |
|------|------|------|
| 中文 | 系统自带 | 无需操作 |
| 老挝语 | Google TTS | 安装 Google TTS → 下载老挝语语言包 |

App 会自动检测，不可用时顶部显示提示。

## 延迟

端到端约 **3-5 秒**。

| 优化 | 预期 |
|------|------|
| 切片改 1.5s | ~2-3s |
| 换 base 模型 | ~1-2s（质量降） |
| VAD 跳过静音 | 减少无效识别 |

## 注意事项

- 模型 460MB，正式发布建议首次启动下载
- 需要真机（模拟器无录音）
- 长时间使用建议插电
