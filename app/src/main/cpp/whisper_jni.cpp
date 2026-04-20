#include <jni.h>
#include <string>
#include <mutex>
#include <android/log.h>
#include "whisper.h"
#include "ggml-backend.h"

#define TAG "whisper_jni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

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

    if (g_ctx) {
        LOGI("nativeInit: OK, n_mels=%d", whisper_model_n_mels(g_ctx));
    } else {
        LOGE("nativeInit: FAILED for: %s", path);
    }

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

    std::lock_guard<std::mutex> lock(g_mutex);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.language = "auto";
    params.n_threads = 4;
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.no_context = true;
    params.single_segment = true;
    params.detect_language = true;
    params.greedy.best_of = 1;
    params.token_timestamps = false;

    LOGI("whisper_full: samples=%d, threads=%d", n_samples, params.n_threads);
    int ret = whisper_full(g_ctx, params, samples, n_samples);
    LOGI("whisper_full done: ret=%d", ret);

    env->ReleaseFloatArrayElements(audio_data, samples, 0);
    env->ReleaseStringUTFChars(language, lang);

    std::string result;
    if (ret == 0) {
        int lang_id = whisper_full_lang_id(g_ctx);
        const char *detected = whisper_lang_str(lang_id);
        result = "LANG:" + std::string(detected ? detected : "unknown") + "\n";
        int n_segments = whisper_full_n_segments(g_ctx);
        for (int i = 0; i < n_segments; ++i) {
            const char *text = whisper_full_get_segment_text(g_ctx, i);
            if (text) result += text;
        }
    } else {
        LOGE("whisper_full failed: %d (samples=%d)", ret, n_samples);
    }

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
