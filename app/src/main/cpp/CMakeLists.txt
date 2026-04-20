cmake_minimum_required(VERSION 3.22.1)
project(whisper_jni)

set(CMAKE_CXX_STANDARD 17)

set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper)

# ggml sources
add_library(ggml STATIC
    ${WHISPER_DIR}/ggml/src/ggml.c
    ${WHISPER_DIR}/ggml/src/ggml-alloc.c
    ${WHISPER_DIR}/ggml/src/ggml-quants.c
)

target_include_directories(ggml PUBLIC
    ${WHISPER_DIR}/ggml/include
    ${WHISPER_DIR}/ggml/src
)

# ggml backend (CPU only)
# 注意：whisper.cpp v1.7.6 的 ggml-cpu 后端需要编译多个源文件
# ggml-cpu.cpp 包含 ggml_backend_cpu_reg (后端注册入口)
# 其他 .cpp 文件是 ggml-cpu.cpp 的依赖
add_library(ggml-backend STATIC
    ${WHISPER_DIR}/ggml/src/ggml-backend.cpp
    ${WHISPER_DIR}/ggml/src/ggml-backend-reg.cpp
    ${WHISPER_DIR}/ggml/src/ggml-threading.cpp
    ${WHISPER_DIR}/ggml/src/ggml-cpu/ggml-cpu.c
    ${WHISPER_DIR}/ggml/src/ggml-cpu/ggml-cpu.cpp
    ${WHISPER_DIR}/ggml/src/ggml-cpu/quants.c
    ${WHISPER_DIR}/ggml/src/ggml-cpu/traits.cpp
    ${WHISPER_DIR}/ggml/src/ggml-cpu/vec.cpp
    ${WHISPER_DIR}/ggml/src/ggml-cpu/ops.cpp
    ${WHISPER_DIR}/ggml/src/ggml-cpu/unary-ops.cpp
    ${WHISPER_DIR}/ggml/src/ggml-cpu/binary-ops.cpp
)

# 关键：定义 GGML_USE_CPU 使 ggml-backend-reg.cpp 编译入 CPU 后端注册代码
target_compile_definitions(ggml-backend PRIVATE GGML_USE_CPU)

target_include_directories(ggml-backend PUBLIC
    ${WHISPER_DIR}/ggml/include
    ${WHISPER_DIR}/ggml/src
    ${WHISPER_DIR}/ggml/src/ggml-cpu
)

target_link_libraries(ggml-backend ggml)

# whisper
add_library(whisper STATIC
    ${WHISPER_DIR}/src/whisper.cpp
)

target_include_directories(whisper PUBLIC
    ${WHISPER_DIR}/include
    ${WHISPER_DIR}/ggml/include
    ${WHISPER_DIR}/ggml/src
)
target_link_libraries(whisper ggml ggml-backend)

# JNI bridge
add_library(whisper_jni SHARED whisper_jni.cpp)

target_include_directories(whisper_jni PRIVATE
    ${WHISPER_DIR}/include
    ${WHISPER_DIR}/ggml/include
)

target_link_libraries(whisper_jni
    whisper
    ggml
    ggml-backend
    log
    android
)
