cmake_minimum_required(VERSION 3.22.1)
project(whisper_jni)

set(CMAKE_CXX_STANDARD 17)

# whisper.cpp sources
set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper)

add_library(whisper STATIC
    ${WHISPER_DIR}/ggml.c
    ${WHISPER_DIR}/ggml-alloc.c
    ${WHISPER_DIR}/ggml-backend.c
    ${WHISPER_DIR}/ggml-quants.c
    ${WHISPER_DIR}/whisper.cpp
)

target_include_directories(whisper PUBLIC ${WHISPER_DIR})

# JNI bridge
add_library(whisper_jni SHARED whisper_jni.cpp)

target_link_libraries(whisper_jni
    whisper
    log
    android
)

# Optimize for mobile
target_compile_definitions(whisper PRIVATE
    GGML_USE_CPU
    GGML_USE_ACCELERATE=0
)
