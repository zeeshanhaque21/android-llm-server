// Minimal JNI bridge between Kotlin and llama.cpp.
//
// Design:
//   - One opaque context per load (returned as a jlong). Owns the model,
//     llama_context, batch, and sampler.
//   - `generate()` runs a single prompt->completion pass, invoking a Kotlin
//     callback per decoded piece. No chat history is tracked here; the HTTP
//     layer is responsible for assembling a prompt from OpenAI messages.
//   - `cancel()` sets an atomic flag checked inside the decode loop so a
//     streaming HTTP client disconnect aborts generation promptly.
//
// This file deliberately stays tiny. Everything that is not strictly required
// to get tokens flowing belongs in Kotlin.

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <cstring>
#include <string>
#include <vector>

#include "llama.h"
#include "common.h"
#include "sampling.h"

#define LOG_TAG "llmbridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct LlmContext {
    llama_model     * model    = nullptr;
    llama_context   * ctx      = nullptr;
    common_sampler  * sampler  = nullptr;
    llama_batch       batch    = {};
    std::atomic<bool> cancel   { false };
};

bool g_backend_initialized = false;

void ensure_backend() {
    if (!g_backend_initialized) {
        llama_backend_init();
        g_backend_initialized = true;
        LOGI("llama backend initialized");
    }
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_zeeshan_androidllmserver_llm_LlmBridge_nativeInit(
        JNIEnv * env, jobject /*thiz*/, jstring jModelPath, jint nCtx, jint nThreads, jboolean useGpu) {

    ensure_backend();

    const char * model_path = env->GetStringUTFChars(jModelPath, nullptr);
    LOGI("loading model: %s (gpu=%d)", model_path, (int) useGpu);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = useGpu ? 99 : 0;
    llama_model * model = llama_model_load_from_file(model_path, mparams);
    env->ReleaseStringUTFChars(jModelPath, model_path);

    if (!model) {
        LOGE("llama_model_load_from_file failed");
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = nCtx > 0 ? (uint32_t) nCtx : 2048;
    cparams.n_batch         = 512;
    cparams.n_ubatch        = 512;
    cparams.n_threads       = nThreads > 0 ? nThreads : 4;
    cparams.n_threads_batch = cparams.n_threads;

    llama_context * ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        LOGE("llama_init_from_model failed");
        llama_model_free(model);
        return 0;
    }

    common_params_sampling sparams;
    sparams.temp = 0.7f;
    common_sampler * sampler = common_sampler_init(model, sparams);
    if (!sampler) {
        LOGE("common_sampler_init failed");
        llama_free(ctx);
        llama_model_free(model);
        return 0;
    }

    auto * c = new LlmContext();
    c->model   = model;
    c->ctx     = ctx;
    c->sampler = sampler;
    c->batch   = llama_batch_init(512, 0, 1);

    LOGI("model loaded: n_ctx=%u threads=%d", cparams.n_ctx, cparams.n_threads);
    return reinterpret_cast<jlong>(c);
}

extern "C" JNIEXPORT void JNICALL
Java_com_zeeshan_androidllmserver_llm_LlmBridge_nativeCancel(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto * c = reinterpret_cast<LlmContext *>(handle);
    if (c) c->cancel.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_zeeshan_androidllmserver_llm_LlmBridge_nativeFree(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto * c = reinterpret_cast<LlmContext *>(handle);
    if (!c) return;
    if (c->sampler) common_sampler_free(c->sampler);
    llama_batch_free(c->batch);
    if (c->ctx)   llama_free(c->ctx);
    if (c->model) llama_model_free(c->model);
    delete c;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_zeeshan_androidllmserver_llm_LlmBridge_nativeGenerate(
        JNIEnv * env,
        jobject /*thiz*/,
        jlong   handle,
        jstring jPrompt,
        jint    nPredict,
        jobject jCallback) {

    auto * c = reinterpret_cast<LlmContext *>(handle);
    if (!c || !c->ctx || !c->model) {
        LOGE("nativeGenerate: invalid context");
        return -1;
    }
    c->cancel.store(false);

    // Reset sampler + KV cache for a fresh single-shot completion.
    common_sampler_reset(c->sampler);
    llama_memory_clear(llama_get_memory(c->ctx), true);

    // Resolve the callback method once.
    jclass    cbClass  = env->GetObjectClass(jCallback);
    jmethodID cbOnTok  = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V");
    if (!cbOnTok) {
        LOGE("nativeGenerate: callback.onToken(String) not found");
        return -2;
    }

    // Tokenize the prompt. add_special=true, parse_special=true so chat template
    // tags (<|im_start|>, etc.) are recognized when the caller has already
    // formatted them.
    const char * prompt = env->GetStringUTFChars(jPrompt, nullptr);
    std::vector<llama_token> tokens = common_tokenize(c->ctx, prompt, true, true);
    env->ReleaseStringUTFChars(jPrompt, prompt);

    if (tokens.empty()) {
        LOGW("nativeGenerate: empty tokenization");
        return 0;
    }

    const uint32_t n_ctx = llama_n_ctx(c->ctx);
    if ((uint32_t) tokens.size() >= n_ctx) {
        LOGE("nativeGenerate: prompt %zu tokens >= n_ctx %u", tokens.size(), n_ctx);
        return -3;
    }

    // Feed prompt in batches of BATCH_SIZE.
    const int BATCH_SIZE = 512;
    int cur_pos = 0;
    for (int i = 0; i < (int) tokens.size(); i += BATCH_SIZE) {
        const int cur = std::min(BATCH_SIZE, (int) tokens.size() - i);
        common_batch_clear(c->batch);
        for (int j = 0; j < cur; ++j) {
            const bool want_logit = (i + j == (int) tokens.size() - 1);
            common_batch_add(c->batch, tokens[i + j], cur_pos++, {0}, want_logit);
        }
        if (llama_decode(c->ctx, c->batch) != 0) {
            LOGE("nativeGenerate: llama_decode (prompt) failed");
            return -4;
        }
    }

    const llama_vocab * vocab = llama_model_get_vocab(c->model);

    // Decode loop.
    std::string utf8_cache;
    int produced = 0;
    const int limit = nPredict > 0 ? nPredict : 256;

    while (produced < limit) {
        if (c->cancel.load()) {
            LOGI("nativeGenerate: cancelled after %d tokens", produced);
            break;
        }
        if ((uint32_t) cur_pos >= n_ctx) {
            LOGW("nativeGenerate: hit context window");
            break;
        }

        llama_token id = common_sampler_sample(c->sampler, c->ctx, -1);
        common_sampler_accept(c->sampler, id, true);

        if (llama_vocab_is_eog(vocab, id)) {
            break;
        }

        std::string piece = common_token_to_piece(c->ctx, id);
        utf8_cache += piece;

        // Flush only on complete UTF-8 so we never hand partial bytes to Java.
        // Simple heuristic: last byte must not be a UTF-8 continuation start.
        bool safe = true;
        if (!utf8_cache.empty()) {
            unsigned char last = (unsigned char) utf8_cache.back();
            // If the last byte starts a multibyte sequence but we don't have
            // the continuation yet, keep caching.
            if ((last & 0x80) && ((last & 0xC0) == 0xC0)) safe = false;
        }

        if (safe) {
            jstring jtok = env->NewStringUTF(utf8_cache.c_str());
            env->CallVoidMethod(jCallback, cbOnTok, jtok);
            env->DeleteLocalRef(jtok);
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
                break;
            }
            utf8_cache.clear();
        }

        // Feed the sampled token back in.
        common_batch_clear(c->batch);
        common_batch_add(c->batch, id, cur_pos++, {0}, true);
        if (llama_decode(c->ctx, c->batch) != 0) {
            LOGE("nativeGenerate: llama_decode (token) failed");
            return -5;
        }
        produced++;
    }

    return produced;
}
