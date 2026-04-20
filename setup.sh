#!/bin/bash
# setup.sh - 下载 whisper.cpp 源码和模型
# 在项目根目录运行: bash setup.sh

set -e

echo "=== 老挝语翻译器 - 环境搭建 ==="

# 1. 下载 whisper.cpp 源码
CPP_DIR="app/src/main/cpp/whisper"
if [ ! -f "$CPP_DIR/include/whisper.h" ]; then
    echo "[1/3] 下载 whisper.cpp 源码..."
    git clone --depth 1 https://github.com/ggerganov/whisper.cpp.git /tmp/whisper-tmp
    mkdir -p "$CPP_DIR/include" "$CPP_DIR/src" \
             "$CPP_DIR/ggml/include" "$CPP_DIR/ggml/src/ggml-cpu"

    # whisper core
    cp /tmp/whisper-tmp/src/whisper.cpp       "$CPP_DIR/src/"
    cp /tmp/whisper-tmp/include/whisper.h     "$CPP_DIR/include/"

    # ggml core
    cp /tmp/whisper-tmp/ggml/src/ggml.c          "$CPP_DIR/ggml/src/"
    cp /tmp/whisper-tmp/ggml/src/ggml-alloc.c    "$CPP_DIR/ggml/src/"
    cp /tmp/whisper-tmp/ggml/src/ggml-quants.c   "$CPP_DIR/ggml/src/"
    cp /tmp/whisper-tmp/ggml/src/ggml-backend.cpp "$CPP_DIR/ggml/src/"
    cp /tmp/whisper-tmp/ggml/src/ggml-threading.cpp "$CPP_DIR/ggml/src/" 2>/dev/null || true

    # ggml CPU backend
    cp /tmp/whisper-tmp/ggml/src/ggml-cpu/ggml-cpu.c "$CPP_DIR/ggml/src/ggml-cpu/" 2>/dev/null || true

    # ggml headers
    cp /tmp/whisper-tmp/ggml/include/ggml.h      "$CPP_DIR/ggml/include/"
    cp /tmp/whisper-tmp/ggml/include/ggml-backend.h "$CPP_DIR/ggml/include/" 2>/dev/null || true
    cp /tmp/whisper-tmp/ggml/src/ggml-common.h   "$CPP_DIR/ggml/src/" 2>/dev/null || true
    cp /tmp/whisper-tmp/ggml/src/ggml-impl.h     "$CPP_DIR/ggml/src/" 2>/dev/null || true
    cp /tmp/whisper-tmp/ggml/src/ggml-quants.h   "$CPP_DIR/ggml/src/" 2>/dev/null || true

    rm -rf /tmp/whisper-tmp
    echo "    ✅ whisper.cpp 源码已复制 (新目录结构)"
else
    echo "[1/3] whisper.cpp 源码已存在，跳过"
fi

# 2. 下载模型
MODEL_DIR="app/src/main/assets/models"
mkdir -p "$MODEL_DIR"

# small 模型（推荐，460MB，老挝语效果够用）
if [ ! -f "$MODEL_DIR/ggml-small.bin" ]; then
    echo "[2/3] 下载 whisper small 模型（460MB，需要几分钟）..."
    curl -L -o "$MODEL_DIR/ggml-small.bin" \
        https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin
    echo "    ✅ 模型下载完成"
else
    echo "[2/3] 模型已存在，跳过"
fi

# 3. 完成
echo "[3/3] ✅ 环境搭建完成！"
echo ""
echo "下一步："
echo "1. 用 Android Studio 打开项目"
echo "2. 等待 Gradle 同步完成"
echo "3. 连接真机运行（模拟器不支持录音）"
echo ""
echo "注意："
echo "- small 模型 460MB，首次打包 APK 会较大"
echo "- 建议改为首次启动时下载模型（已预留接口）"
