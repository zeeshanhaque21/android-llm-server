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
#include "mtmd.h"
#include "mtmd-helper.h"

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
    // Optional multimodal projector. Set only for models loaded via
    // nativeInitMm(); nullptr means "text only" and multimodal calls
    // against this handle return an error.
    mtmd_context    * mtmd     = nullptr;
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
    if (c->mtmd)    mtmd_free(c->mtmd);
    if (c->sampler) common_sampler_free(c->sampler);
    llama_batch_free(c->batch);
    if (c->ctx)     llama_free(c->ctx);
    if (c->model)   llama_model_free(c->model);
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
    LOGI("nativeGenerate: start (n_predict=%d)", (int) nPredict);
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

// ------------------------------------------------------------------------
// Multimodal init + generate. Uses libmtmd from llama.cpp to encode images
// and audio via the mmproj sidecar, then hands off to the same llama
// sampling loop as the text path.
// ------------------------------------------------------------------------

extern "C" JNIEXPORT jlong JNICALL
Java_com_zeeshan_androidllmserver_llm_LlmBridge_nativeInitMm(
        JNIEnv * env, jobject /*thiz*/,
        jstring jModelPath, jstring jMmprojPath,
        jint nCtx, jint nThreads, jboolean useGpu) {

    ensure_backend();

    const char * model_path  = env->GetStringUTFChars(jModelPath,  nullptr);
    const char * mmproj_path = env->GetStringUTFChars(jMmprojPath, nullptr);
    LOGI("loading MM model: %s (mmproj=%s, gpu=%d)", model_path, mmproj_path, (int) useGpu);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = useGpu ? 99 : 0;
    llama_model * model = llama_model_load_from_file(model_path, mparams);

    if (!model) {
        LOGE("nativeInitMm: llama_model_load_from_file failed");
        env->ReleaseStringUTFChars(jModelPath,  model_path);
        env->ReleaseStringUTFChars(jMmprojPath, mmproj_path);
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = nCtx > 0 ? (uint32_t) nCtx : 4096;
    cparams.n_batch         = 512;
    cparams.n_ubatch        = 512;
    cparams.n_threads       = nThreads > 0 ? nThreads : 4;
    cparams.n_threads_batch = cparams.n_threads;

    llama_context * ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        LOGE("nativeInitMm: llama_init_from_model failed");
        llama_model_free(model);
        env->ReleaseStringUTFChars(jModelPath,  model_path);
        env->ReleaseStringUTFChars(jMmprojPath, mmproj_path);
        return 0;
    }

    mtmd_context_params mp = mtmd_context_params_default();
    mp.use_gpu       = useGpu;
    mp.n_threads     = cparams.n_threads;
    mp.print_timings = false;
    mp.warmup        = false;  // skip warmup to keep startup fast on Android

    mtmd_context * mctx = mtmd_init_from_file(mmproj_path, model, mp);

    env->ReleaseStringUTFChars(jModelPath,  model_path);
    env->ReleaseStringUTFChars(jMmprojPath, mmproj_path);

    if (!mctx) {
        LOGE("nativeInitMm: mtmd_init_from_file failed");
        llama_free(ctx);
        llama_model_free(model);
        return 0;
    }

    LOGI("mtmd loaded: vision=%d audio=%d",
         (int) mtmd_support_vision(mctx), (int) mtmd_support_audio(mctx));

    common_params_sampling sparams;
    sparams.temp = 0.7f;
    common_sampler * sampler = common_sampler_init(model, sparams);
    if (!sampler) {
        LOGE("nativeInitMm: common_sampler_init failed");
        mtmd_free(mctx);
        llama_free(ctx);
        llama_model_free(model);
        return 0;
    }

    auto * c = new LlmContext();
    c->model   = model;
    c->ctx     = ctx;
    c->sampler = sampler;
    c->batch   = llama_batch_init(512, 0, 1);
    c->mtmd    = mctx;

    LOGI("MM model loaded: n_ctx=%u threads=%d", cparams.n_ctx, cparams.n_threads);
    return reinterpret_cast<jlong>(c);
}

// Query multimodal capability flags for a handle. Returns bit0=vision, bit1=audio.
extern "C" JNIEXPORT jint JNICALL
Java_com_zeeshan_androidllmserver_llm_LlmBridge_nativeMmCaps(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto * c = reinterpret_cast<LlmContext *>(handle);
    if (!c || !c->mtmd) return 0;
    int caps = 0;
    if (mtmd_support_vision(c->mtmd)) caps |= 0x1;
    if (mtmd_support_audio(c->mtmd))  caps |= 0x2;
    return caps;
}

// Generate a completion with interleaved media.
//
// - `jPrompt` is the already-templated prompt string. Each media item
//   must be represented by one "<__media__>" marker so mtmd_tokenize
//   can interleave chunks. The Kotlin layer is responsible for getting
//   the marker count right.
// - `jMedia` is a jobject[] where each element is a byte[] holding the
//   encoded bytes of an image (PNG/JPEG) or audio (WAV/MP3/FLAC).
//   mtmd_helper_bitmap_init_from_buf decodes each one.
// - Text tokens + media tokens are fed via mtmd_helper_eval_chunks,
//   leaving the KV cache primed at n_past. The existing sampling loop
//   from nativeGenerate runs from there.
extern "C" JNIEXPORT jint JNICALL
Java_com_zeeshan_androidllmserver_llm_LlmBridge_nativeGenerateMm(
        JNIEnv * env,
        jobject /*thiz*/,
        jlong    handle,
        jstring  jPrompt,
        jobjectArray jMedia,
        jint     nPredict,
        jobject  jCallback) {

    auto * c = reinterpret_cast<LlmContext *>(handle);
    if (!c || !c->ctx || !c->model) {
        LOGE("nativeGenerateMm: invalid context");
        return -1;
    }
    if (!c->mtmd) {
        LOGE("nativeGenerateMm: handle was not opened via nativeInitMm (no mmproj)");
        return -10;
    }
    c->cancel.store(false);

    common_sampler_reset(c->sampler);
    llama_memory_clear(llama_get_memory(c->ctx), true);

    jclass    cbClass = env->GetObjectClass(jCallback);
    jmethodID cbOnTok = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V");
    if (!cbOnTok) {
        LOGE("nativeGenerateMm: callback.onToken(String) not found");
        return -2;
    }

    // Decode the media byte arrays into mtmd_bitmaps.
    const jsize n_media = jMedia ? env->GetArrayLength(jMedia) : 0;
    std::vector<mtmd_bitmap *> bitmaps;
    bitmaps.reserve(n_media);

    auto free_bitmaps = [&]() {
        for (auto * b : bitmaps) mtmd_bitmap_free(b);
        bitmaps.clear();
    };

    for (jsize i = 0; i < n_media; ++i) {
        jobject obj = env->GetObjectArrayElement(jMedia, i);
        auto ba = reinterpret_cast<jbyteArray>(obj);
        if (!ba) {
            LOGE("nativeGenerateMm: media[%d] is null", i);
            free_bitmaps();
            return -11;
        }
        const jsize len = env->GetArrayLength(ba);
        jbyte * raw = env->GetByteArrayElements(ba, nullptr);
        mtmd_bitmap * bmp = mtmd_helper_bitmap_init_from_buf(
            c->mtmd, reinterpret_cast<const unsigned char *>(raw), (size_t) len);
        env->ReleaseByteArrayElements(ba, raw, JNI_ABORT);
        env->DeleteLocalRef(obj);
        if (!bmp) {
            LOGE("nativeGenerateMm: bitmap_init failed for media[%d] (%d bytes)", i, len);
            free_bitmaps();
            return -12;
        }
        bitmaps.push_back(bmp);
    }

    // Tokenize prompt + media into chunks.
    mtmd_input_chunks * chunks = mtmd_input_chunks_init();
    if (!chunks) {
        free_bitmaps();
        return -13;
    }

    const char * prompt = env->GetStringUTFChars(jPrompt, nullptr);
    mtmd_input_text txt{};
    txt.text          = prompt;
    txt.add_special   = true;
    txt.parse_special = true;

    std::vector<const mtmd_bitmap *> bm_ptrs(bitmaps.begin(), bitmaps.end());
    const int32_t tok_rc = mtmd_tokenize(c->mtmd, chunks, &txt,
                                         bm_ptrs.data(), bm_ptrs.size());
    env->ReleaseStringUTFChars(jPrompt, prompt);

    if (tok_rc != 0) {
        LOGE("nativeGenerateMm: mtmd_tokenize failed (%d) — check <__media__> marker count vs media count", tok_rc);
        mtmd_input_chunks_free(chunks);
        free_bitmaps();
        return -14;
    }

    // Evaluate all chunks (interleaves llama_decode on text chunks and
    // mtmd_encode + llama_decode on image/audio chunks).
    llama_pos n_past = 0;
    const int32_t eval_rc = mtmd_helper_eval_chunks(
        c->mtmd, c->ctx, chunks,
        /*n_past=*/0, /*seq_id=*/0,
        /*n_batch=*/512, /*logits_last=*/true,
        &n_past);

    mtmd_input_chunks_free(chunks);
    free_bitmaps();

    if (eval_rc != 0) {
        LOGE("nativeGenerateMm: mtmd_helper_eval_chunks failed (%d)", eval_rc);
        return -15;
    }

    // Continue the sampling loop from the end of the primed KV cache.
    const llama_vocab * vocab = llama_model_get_vocab(c->model);
    const uint32_t n_ctx      = llama_n_ctx(c->ctx);

    int cur_pos = (int) n_past;
    std::string utf8_cache;
    int produced = 0;
    const int limit = nPredict > 0 ? nPredict : 256;

    while (produced < limit) {
        if (c->cancel.load()) {
            LOGI("nativeGenerateMm: cancelled after %d tokens", produced);
            break;
        }
        if ((uint32_t) cur_pos >= n_ctx) {
            LOGW("nativeGenerateMm: hit context window");
            break;
        }

        llama_token id = common_sampler_sample(c->sampler, c->ctx, -1);
        common_sampler_accept(c->sampler, id, true);

        if (llama_vocab_is_eog(vocab, id)) break;

        std::string piece = common_token_to_piece(c->ctx, id);
        utf8_cache += piece;

        bool safe = true;
        if (!utf8_cache.empty()) {
            unsigned char last = (unsigned char) utf8_cache.back();
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

        common_batch_clear(c->batch);
        common_batch_add(c->batch, id, cur_pos++, {0}, true);
        if (llama_decode(c->ctx, c->batch) != 0) {
            LOGE("nativeGenerateMm: llama_decode (token) failed");
            return -16;
        }
        produced++;
    }

    return produced;
}
