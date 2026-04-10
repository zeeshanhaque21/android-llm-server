#include <jni.h>
#include <android/log.h>
#include <string>

#define TAG "sd_bridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#ifdef ENABLE_SD
#include "stable-diffusion.h"

struct SdContext {
    sd_ctx_t* sd_ctx;
};
#endif

extern "C" {

// Initialize SD context with a model path
// Returns opaque handle, 0 on failure
JNIEXPORT jlong JNICALL
Java_com_zeeshan_androidllmserver_sd_SdBridge_nativeInit(
    JNIEnv* env, jobject, jstring modelPath, jboolean useGpu) {
#ifdef ENABLE_SD
    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    LOGI("Loading SD model: %s", path);

    sd_ctx_t* ctx = new_sd_ctx(
        path,           // model_path
        "",             // clip_l_path
        "",             // clip_g_path
        "",             // t5xxl_path
        "",             // diffusion_model_path
        "",             // vae_path
        "",             // taesd_path
        "",             // controlnet_path
        "",             // lora_model_dir
        "",             // embed_dir
        "",             // stacked_id_embed_dir
        false,          // vae_decode_only
        true,           // vae_tiling
        false,          // free_params_immediately
        -1,             // n_threads (auto)
        SD_TYPE_COUNT,  // wtype (auto)
        RNG_STD_DEFAULT,
        SD_DEFAULT_SCHEDULER,
        false,          // keep_clip_on_cpu
        false,          // keep_control_net_cpu
        false           // keep_vae_on_cpu
    );

    env->ReleaseStringUTFChars(modelPath, path);

    if (!ctx) {
        LOGE("Failed to create SD context");
        return 0;
    }

    auto* wrapper = new SdContext{ctx};
    return reinterpret_cast<jlong>(wrapper);
#else
    LOGE("stable-diffusion.cpp not built - ENABLE_SD not defined");
    return 0;
#endif
}

// Generate image from text prompt
// Returns base64-encoded PNG, or empty string on failure
JNIEXPORT jstring JNICALL
Java_com_zeeshan_androidllmserver_sd_SdBridge_nativeGenerate(
    JNIEnv* env, jobject, jlong handle, jstring prompt,
    jstring negativePrompt, jint width, jint height,
    jint steps, jfloat cfgScale, jlong seed) {
#ifdef ENABLE_SD
    if (handle == 0) return env->NewStringUTF("");

    auto* wrapper = reinterpret_cast<SdContext*>(handle);
    const char* promptStr = env->GetStringUTFChars(prompt, nullptr);
    const char* negStr = env->GetStringUTFChars(negativePrompt, nullptr);

    LOGI("Generating image: %dx%d, steps=%d, cfg=%.1f", width, height, steps, cfgScale);

    sd_image_t* result = txt2img(
        wrapper->sd_ctx,
        promptStr,
        negStr,
        -1,             // clip_skip
        cfgScale,
        0.0f,           // guidance
        width,
        height,
        EULER,          // sample_method
        steps,
        seed,
        1,              // batch_count
        nullptr,        // control_cond
        0.0f,           // control_strength
        0.0f,           // style_ratio
        false,          // normalize_input
        "",             // input_id_images_path
        nullptr,        // skip_layers
        0,              // skip_layers_count
        0.0f,           // slg_scale
        0.0f,           // skip_layer_start
        0.0f            // skip_layer_end
    );

    env->ReleaseStringUTFChars(prompt, promptStr);
    env->ReleaseStringUTFChars(negativePrompt, negStr);

    if (!result || !result->data) {
        LOGE("Image generation failed");
        return env->NewStringUTF("");
    }

    // Convert raw RGBA to PNG then base64
    // For now, return pixel dimensions as placeholder
    // Full implementation would use stb_image_write to encode PNG
    LOGI("Image generated: %dx%d", result->width, result->height);

    // TODO: Encode result->data as PNG, then base64
    // For now return empty - will be implemented when SD submodule is added
    free(result->data);
    free(result);

    return env->NewStringUTF("");
#else
    return env->NewStringUTF("");
#endif
}

JNIEXPORT void JNICALL
Java_com_zeeshan_androidllmserver_sd_SdBridge_nativeFree(
    JNIEnv* env, jobject, jlong handle) {
#ifdef ENABLE_SD
    if (handle == 0) return;
    auto* wrapper = reinterpret_cast<SdContext*>(handle);
    free_sd_ctx(wrapper->sd_ctx);
    delete wrapper;
    LOGI("SD context freed");
#endif
}

} // extern "C"
