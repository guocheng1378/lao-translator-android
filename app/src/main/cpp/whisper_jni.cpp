// whisper_jni.cpp — 优化版：更快出结果

#include <jni.h>
#include <string>
#include <mutex>
#include "whisper.h"

static struct whisper_context *g_ctx = nullptr;
static std::mutex g_mutex;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_lao_translator_stt_WhisperManager_nativeInit(
        JNIEnv *env, jobject thiz, jstring model_path) {
    const char *path = env->GetStringUTFChars(model_path, nullptr);
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_ctx) whisper_free(g_ctx);
    g_ctx = whisper_init_from_file(path);
    env->ReleaseStringUTFChars(model_path, path);
    return g_ctx != nullptr;
}

JNIEXPORT jstring JNICALL
Java_com_lao_translator_stt_WhisperManager_nativeTranscribe(
        JNIEnv *env, jobject thiz,
        jfloatArray audio_data, jint n_samples,
        jstring language) {

    if (!g_ctx) return env->NewStringUTF("");

    float *samples = env->GetFloatArrayElements(audio_data, nullptr);
    const char *lang = env->GetStringUTFChars(language, nullptr);
    bool auto_detect = (lang[0] == '\0');

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.language = auto_detect ? "auto" : lang;
    params.n_threads = 4;
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;

    // === 性能优化 ===
    params.no_context = true;           // 每段独立，适合流式
    params.single_segment = true;       // 只要一段，快速出结果
    params.detect_language = auto_detect;

    // 降低 beam_size 加速（greedy 模式 beam_size=1）
    // 提前终止：如果概率够高就不继续
    params.greedy.best_of = 1;          // greedy 只采样一次

    // 不做 token 级别的时间戳
    params.token_timestamps = false;

    std::lock_guard<std::mutex> lock(g_mutex);
    int ret = whisper_full(g_ctx, params, samples, n_samples);

    std::string result;
    if (ret == 0) {
        const auto *lang_id = whisper_lang_str(whisper_lang_id(g_ctx));
        std::string detected_lang = lang_id ? lang_id : "unknown";
        result = "LANG:" + detected_lang + "\n";

        int n_segments = whisper_full_n_segments(g_ctx);
        for (int i = 0; i < n_segments; ++i) {
            const char *text = whisper_full_get_segment_text(g_ctx, i);
            if (text) result += text;
        }
    }

    env->ReleaseFloatArrayElements(audio_data, samples, 0);
    env->ReleaseStringUTFChars(language, lang);

    return env->NewStringUTF(result.c_str());
}

JNIEXPORT void JNICALL
Java_com_lao_translator_stt_WhisperManager_nativeRelease(JNIEnv *env, jobject thiz) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_ctx) {
        whisper_free(g_ctx);
        g_ctx = nullptr;
    }
}

}
