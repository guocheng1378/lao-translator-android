cmake_minimum_required(VERSION 3.22.1)
project(whisper_jni)

set(CMAKE_CXX_STANDARD 17)

set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper)

# whisper (静态库)
add_library(whisper STATIC
    ${WHISPER_DIR}/src/whisper.cpp
)

# ✅ 禁用 assert，否则 Release 构建会 SIGABRT 崩溃
target_compile_definitions(whisper PRIVATE NDEBUG)

target_include_directories(whisper PUBLIC
    ${WHISPER_DIR}/include
    ${WHISPER_DIR}/ggml/include
    ${WHISPER_DIR}/ggml/src
)

# JNI bridge - 把 ggml 所有源文件直接编译进共享库，避免静态库链接器丢弃问题
add_library(whisper_jni SHARED
    whisper_jni.cpp
    # ggml core
    ${WHISPER_DIR}/ggml/src/ggml.c
    ${WHISPER_DIR}/ggml/src/ggml-alloc.c
    ${WHISPER_DIR}/ggml/src/ggml-quants.c
    # ggml backend
    ${WHISPER_DIR}/ggml/src/ggml-backend.cpp
    ${WHISPER_DIR}/ggml/src/ggml-backend-reg.cpp
    ${WHISPER_DIR}/ggml/src/ggml-threading.cpp
    # ggml-cpu backend
    ${WHISPER_DIR}/ggml/src/ggml-cpu/ggml-cpu.c
    ${WHISPER_DIR}/ggml/src/ggml-cpu/ggml-cpu.cpp
    ${WHISPER_DIR}/ggml/src/ggml-cpu/quants.c
    ${WHISPER_DIR}/ggml/src/ggml-cpu/traits.cpp
    ${WHISPER_DIR}/ggml/src/ggml-cpu/vec.cpp
    ${WHISPER_DIR}/ggml/src/ggml-cpu/ops.cpp
    ${WHISPER_DIR}/ggml/src/ggml-cpu/unary-ops.cpp
    ${WHISPER_DIR}/ggml/src/ggml-cpu/binary-ops.cpp
    # arm64 NEON 优化的量化函数
    ${WHISPER_DIR}/ggml/src/ggml-cpu/arch/arm/quants.c
)

# ✅ 同样禁用 assert
target_compile_definitions(whisper_jni PRIVATE GGML_USE_CPU NDEBUG)

target_include_directories(whisper_jni PRIVATE
    ${WHISPER_DIR}/include
    ${WHISPER_DIR}/ggml/include
    ${WHISPER_DIR}/ggml/src
    ${WHISPER_DIR}/ggml/src/ggml-cpu
)

target_link_libraries(whisper_jni
    whisper
    log
    android
)
