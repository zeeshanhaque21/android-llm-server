#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <cstdlib>
#include <string>
#include <vector>

#define TAG "sd_bridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#include "stable-diffusion.cpp/include/stable-diffusion.h"

// stb_image_write for PNG encoding
#define STB_IMAGE_WRITE_IMPLEMENTATION
#include "stable-diffusion.cpp/thirdparty/stb_image_write.h"

// Base64 encoding helper
static const char b64_table[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

static std::string base64_encode(const uint8_t* data, size_t len) {
    std::string result;
    result.reserve(4 * ((len + 2) / 3));
    for (size_t i = 0; i < len; i += 3) {
        uint32_t n = (uint32_t)data[i] << 16;
        if (i + 1 < len) n |= (uint32_t)data[i + 1] << 8;
        if (i + 2 < len) n |= (uint32_t)data[i + 2];
        result += b64_table[(n >> 18) & 0x3F];
        result += b64_table[(n >> 12) & 0x3F];
        result += (i + 1 < len) ? b64_table[(n >> 6) & 0x3F] : '=';
        result += (i + 2 < len) ? b64_table[n & 0x3F] : '=';
    }
    return result;
}

// Callback for stb_image_write to write to a vector
struct PngBuffer {
    std::vector<uint8_t> data;
};

static void png_write_callback(void* context, void* data, int size) {
    auto* buf = static_cast<PngBuffer*>(context);
    auto* bytes = static_cast<uint8_t*>(data);
    buf->data.insert(buf->data.end(), bytes, bytes + size);
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_zeeshan_androidllmserver_sd_SdBridge_nativeInit(
    JNIEnv* env, jobject, jstring modelPath, jboolean useGpu) {

    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    LOGI("Loading SD model: %s (gpu=%d)", path, useGpu);

    sd_ctx_params_t params;
    sd_ctx_params_init(&params);
    params.model_path = path;
    params.vae_decode_only = true;
    params.n_threads = -1; // auto
    params.wtype = SD_TYPE_COUNT; // auto
    params.rng_type = STD_DEFAULT_RNG;
    params.flash_attn = true;

    sd_ctx_t* ctx = new_sd_ctx(&params);
    env->ReleaseStringUTFChars(modelPath, path);

    if (!ctx) {
        LOGE("Failed to create SD context");
        return 0;
    }

    LOGI("SD model loaded successfully");
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jstring JNICALL
Java_com_zeeshan_androidllmserver_sd_SdBridge_nativeGenerate(
    JNIEnv* env, jobject, jlong handle, jstring prompt,
    jstring negativePrompt, jint width, jint height,
    jint steps, jfloat cfgScale, jlong seed) {

    if (handle == 0) return env->NewStringUTF("");

    auto* ctx = reinterpret_cast<sd_ctx_t*>(handle);
    const char* promptStr = env->GetStringUTFChars(prompt, nullptr);
    const char* negStr = env->GetStringUTFChars(negativePrompt, nullptr);

    LOGI("Generating image: %dx%d, steps=%d, cfg=%.1f, seed=%lld",
         width, height, steps, cfgScale, (long long)seed);

    sd_img_gen_params_t img_params;
    sd_img_gen_params_init(&img_params);
    img_params.prompt = promptStr;
    img_params.negative_prompt = negStr;
    img_params.width = width;
    img_params.height = height;
    img_params.sample_params.sample_steps = steps;
    img_params.sample_params.guidance.txt_cfg = cfgScale;
    img_params.seed = seed;
    img_params.batch_count = 1;
    img_params.strength = 1.0f;

    sd_image_t* result = generate_image(ctx, &img_params);

    env->ReleaseStringUTFChars(prompt, promptStr);
    env->ReleaseStringUTFChars(negativePrompt, negStr);

    if (!result || !result->data) {
        LOGE("Image generation failed");
        return env->NewStringUTF("");
    }

    LOGI("Image generated: %dx%d, %d channels", result->width, result->height, result->channel);

    // Encode to PNG
    PngBuffer pngBuf;
    int ok = stbi_write_png_to_func(
        png_write_callback, &pngBuf,
        result->width, result->height, result->channel,
        result->data, result->width * result->channel
    );

    free(result->data);
    free(result);

    if (!ok || pngBuf.data.empty()) {
        LOGE("PNG encoding failed");
        return env->NewStringUTF("");
    }

    // Base64 encode the PNG
    std::string b64 = base64_encode(pngBuf.data.data(), pngBuf.data.size());
    LOGI("PNG encoded: %zu bytes -> %zu base64 chars", pngBuf.data.size(), b64.size());

    return env->NewStringUTF(b64.c_str());
}

JNIEXPORT void JNICALL
Java_com_zeeshan_androidllmserver_sd_SdBridge_nativeFree(
    JNIEnv* env, jobject, jlong handle) {
    if (handle == 0) return;
    auto* ctx = reinterpret_cast<sd_ctx_t*>(handle);
    free_sd_ctx(ctx);
    LOGI("SD context freed");
}

} // extern "C"
