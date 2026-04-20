#include <jni.h>
#include <string>
#include <mutex>
#include "whisper.h"
#include "ggml-backend.h"

static struct whisper_context *g_ctx = nullptr;
static std::mutex g_mutex;
static bool g_backend_ready = false;

// Ensure CPU backend is registered before whisper_init_from_file
// whisper_init_from_file calls ggml_backend_dev_backend_reg which requires
// the CPU backend to be in the registry. ggml_backend_init_by_type triggers
// implicit registration as a side effect.
static void ensure_backend() {
    if (g_backend_ready) return;
    ggml_backend_t backend = ggml_backend_init_by_type(GGML_BACKEND_DEVICE_TYPE_CPU, nullptr);
    if (backend) {
        ggml_backend_free(backend);
    }
    g_backend_ready = true;
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_lao_translator_stt_WhisperManager_nativeInit(
        JNIEnv *env, jobject thiz, jstring model_path) {
    const char *path = env->GetStringUTFChars(model_path, nullptr);
    std::lock_guard<std::mutex> lock(g_mutex);
    ensure_backend();
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

    std::lock_guard<std::mutex> lock(g_mutex);

    // Auto-detect language if needed
    std::string lang_prefix;
    if (auto_detect) {
        float lang_probs[100];
        int detected_id = whisper_lang_auto_detect(g_ctx, 0, 4, lang_probs);
        if (detected_id >= 0) {
            const char *detected_str = whisper_lang_str(detected_id);
            lang_prefix = "LANG:" + std::string(detected_str ? detected_str : "unknown") + "\n";
        } else {
            lang_prefix = "LANG:unknown\n";
        }
    }

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.language = auto_detect ? "auto" : lang;
    params.n_threads = 4;
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.no_context = true;
    params.single_segment = true;
    params.detect_language = auto_detect;
    params.greedy.best_of = 1;
    params.token_timestamps = false;

    int ret = whisper_full(g_ctx, params, samples, n_samples);

    std::string result;
    if (ret == 0) {
        result = lang_prefix;
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
