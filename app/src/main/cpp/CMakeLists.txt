cmake_minimum_required(VERSION 3.22.1)
project(whisper_jni)

set(CMAKE_CXX_STANDARD 17)

set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper)

# ggml + ggml-backend 合并为一个静态库，避免静态库交叉引用导致 undefined symbol
add_library(ggml-all STATIC
    # ggml core
    ${WHISPER_DIR}/ggml/src/ggml.c
    ${WHISPER_DIR}/ggml/src/ggml-alloc.c
    ${WHISPER_DIR}/ggml/src/ggml-quants.c
    # ggml backend
    ${WHISPER_DIR}/ggml/src/ggml-backend.cpp
    ${WHISPER_DIR}/ggml/src/ggml-backend-reg.cpp
    ${WHISPER_DIR}/ggml/src/ggml-threading.cpp
    # ggml-cpu backend (whisper.cpp v1.7.6 需要编译全部子模块)
    ${WHISPER_DIR}/ggml/src/ggml-cpu/ggml-cpu.c
    ${WHISPER_DIR}/ggml/src/ggml-cpu/ggml-cpu.cpp
    ${WHISPER_DIR}/ggml/src/ggml-cpu/quants.c
    ${WHISPER_DIR}/ggml/src/ggml-cpu/traits.cpp
    ${WHISPER_DIR}/ggml/src/ggml-cpu/vec.cpp
    ${WHISPER_DIR}/ggml/src/ggml-cpu/ops.cpp
    ${WHISPER_DIR}/ggml/src/ggml-cpu/unary-ops.cpp
    ${WHISPER_DIR}/ggml/src/ggml-cpu/binary-ops.cpp
    # arm64 NEON 优化的量化函数 (ggml_vec_dot_*, quantize_row_*)
    ${WHISPER_DIR}/ggml/src/ggml-cpu/arch/arm/quants.c
)

target_compile_definitions(ggml-all PRIVATE GGML_USE_CPU)

target_include_directories(ggml-all PUBLIC
    ${WHISPER_DIR}/ggml/include
    ${WHISPER_DIR}/ggml/src
    ${WHISPER_DIR}/ggml/src/ggml-cpu
)

# whisper
add_library(whisper STATIC
    ${WHISPER_DIR}/src/whisper.cpp
)

target_include_directories(whisper PUBLIC
    ${WHISPER_DIR}/include
    ${WHISPER_DIR}/ggml/include
    ${WHISPER_DIR}/ggml/src
)
target_link_libraries(whisper ggml-all)

# JNI bridge
add_library(whisper_jni SHARED whisper_jni.cpp)

target_include_directories(whisper_jni PRIVATE
    ${WHISPER_DIR}/include
    ${WHISPER_DIR}/ggml/include
)

# 使用 --whole-archive 强制链接 ggml-all 的所有目标文件
# 否则 ggml-backend-reg.cpp 中的 GGML_REGISTER_BACKEND static initializer 会被链接器丢弃
# 导致 CPU backend 未注册，whisper_init 崩溃
target_link_libraries(whisper_jni
    whisper
    -Wl,--whole-archive ggml-all -Wl,--no-whole-archive
    log
    android
)
