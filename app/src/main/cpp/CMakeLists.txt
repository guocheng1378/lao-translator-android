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
# ggml-backend-reg.cpp removed — causes SIGSEGV on some Android devices
# during ggml_backend_dev_backend_reg() at startup.
add_library(ggml-backend STATIC
    ${WHISPER_DIR}/ggml/src/ggml-backend.cpp
    ${WHISPER_DIR}/ggml/src/ggml-cpu/ggml-cpu.c
    ${WHISPER_DIR}/ggml/src/ggml-threading.cpp
)

target_include_directories(ggml-backend PUBLIC
    ${WHISPER_DIR}/ggml/include
    ${WHISPER_DIR}/ggml/src
)

# Force CPU-only mode — skip dynamic backend registration entirely
target_compile_definitions(ggml-backend PRIVATE GGML_USE_CPU_ONLY)
target_compile_definitions(whisper PRIVATE GGML_USE_CPU_ONLY)

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
