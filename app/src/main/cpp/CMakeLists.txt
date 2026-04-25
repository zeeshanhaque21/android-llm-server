cmake_minimum_required(VERSION 3.22.1)

project("llmbridge" LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# --- llama.cpp build options (keep this minimal: no examples, no server, no tests) ---
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
# We still need the `common` helper lib (sampling, chat template, arg helpers)
# which llm_bridge.cpp links against. It's off by default when examples are off.
set(LLAMA_BUILD_COMMON   ON  CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)

# GPU backend selection on Android.
#
# Vulkan: disabled. Crashes on Adreno (vkCmdBindPipeline+20, null
# pipeline) per llama.cpp issues #5186 / #16881 — known-broken
# combination. ggml-vulkan claims op support, hands null to the
# driver, SIGSEGV. Affects modern Snapdragons (S23 Ultra confirmed).
#
# OpenCL: enabled. This is the path Google's AI Edge / MediaPipe
# uses on Adreno because Qualcomm's OpenCL driver is mature and
# co-developed with them. We:
#   - Vendor Khronos OpenCL-Headers under third_party/
#   - Generate a link-time stub libOpenCL.so from those headers
#     (third_party/opencl-stub) so cmake's find_package(OpenCL) is
#     satisfied without us shipping a real driver
#   - Add <uses-native-library android:name="libOpenCL.so"
#     android:required="false"/> to the manifest, which tells the
#     Android dynamic linker (API 31+) to expose the device's
#     /vendor/lib64/libOpenCL.so to our process at load time
#   - Turn on GGML_OPENCL_USE_ADRENO_KERNELS for the Adreno-tuned
#     matmul kernels, and EMBED_KERNELS so the .cl source is baked
#     into libggml-opencl.so (no runtime asset shipping needed).
set(GGML_VULKAN                     OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN_CHECK_RESULTS       OFF CACHE BOOL "" FORCE)

set(GGML_OPENCL                     ON  CACHE BOOL "" FORCE)
set(GGML_OPENCL_USE_ADRENO_KERNELS  ON  CACHE BOOL "" FORCE)
set(GGML_OPENCL_EMBED_KERNELS       ON  CACHE BOOL "" FORCE)
set(GGML_OPENCL_TARGET_VERSION      300 CACHE STRING "" FORCE)

# Build the stub libOpenCL.so first so the in-tree `OpenCL` target
# exists before llama.cpp's ggml-opencl/CMakeLists.txt calls
# find_package(OpenCL). Our custom cmake/FindOpenCL.cmake then aliases
# the stub target into OpenCL_LIBRARIES, dodging the host filesystem
# search that the stock FindOpenCL would do.
add_subdirectory(${CMAKE_CURRENT_LIST_DIR}/third_party/opencl-stub build-opencl-stub)
list(PREPEND CMAKE_MODULE_PATH "${CMAKE_CURRENT_LIST_DIR}/cmake")

# Help find_package(Vulkan) locate the C++ headers + glslc.
# NDK 27 ships C headers in the sysroot but lacks vulkan.hpp. We vendor
# the Khronos Vulkan-Headers under third_party/ for the C++ wrappers.
# llama.cpp (>= f65bc34) additionally #include's <spirv/unified1/spirv.hpp>;
# that header isn't in Vulkan-Headers but the NDK ships it inside
# shaderc/third_party/spirv-tools, so we pass both dirs as a semicolon
# list to find_package(Vulkan) (CACHE STRING lets it be a list).
set(Vulkan_INCLUDE_DIR
    "${CMAKE_CURRENT_LIST_DIR}/third_party/Vulkan-Headers/include;${ANDROID_NDK}/sources/third_party/shaderc/third_party/spirv-tools/external/spirv-headers/include"
    CACHE STRING "" FORCE)
set(Vulkan_GLSLC_EXECUTABLE "${ANDROID_NDK}/shader-tools/${ANDROID_HOST_TAG}/glslc" CACHE FILEPATH "" FORCE)

# ARM-specific tuning. KleidiAI gives a meaningful boost on arm64-v8a (the only
# ABI we ship for v1). x86_64 is built only for emulator convenience.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
endif()

set(LLAMA_DIR ${CMAKE_CURRENT_LIST_DIR}/llama.cpp)
add_subdirectory(${LLAMA_DIR} build-llama)

# Pull in libmtmd without enabling LLAMA_BUILD_TOOLS (which would also
# build llama-cli/server/bench/quantize/etc — wasted work for a JNI
# bridge). Because mtmd/CMakeLists.txt references LLAMA_INSTALL_VERSION
# and that's set in llama.cpp's subdirectory scope (not visible here),
# we redeclare it in our scope so set_target_properties(mtmd VERSION ...)
# gets a valid value.
set(LLAMA_INSTALL_VERSION 0.0.0)
add_subdirectory(${LLAMA_DIR}/tools/mtmd build-mtmd)

add_library(llmbridge SHARED llm_bridge.cpp)

target_include_directories(llmbridge PRIVATE
    ${LLAMA_DIR}
    ${LLAMA_DIR}/include
    ${LLAMA_DIR}/common
    ${LLAMA_DIR}/ggml/include
    ${LLAMA_DIR}/tools/mtmd
)

# The common helper lib was renamed `common` → `llama-common` in
# llama.cpp PR #21936. Pick whichever target the pinned submodule
# actually defines so we work across both naming conventions.
if (TARGET llama-common)
    set(_LLAMA_COMMON_TARGET llama-common)
else()
    set(_LLAMA_COMMON_TARGET common)
endif()

target_link_libraries(llmbridge
    llama
    ${_LLAMA_COMMON_TARGET}
    mtmd
    android
    log
    # `vulkan` link omitted — GGML_VULKAN=OFF, see above. Re-add when
    # the Adreno crash is solved upstream.
)

# --- stable-diffusion.cpp (image generation) ---
set(SD_DIR ${CMAKE_CURRENT_LIST_DIR}/stable-diffusion.cpp)
if(EXISTS ${SD_DIR}/CMakeLists.txt)
    message(STATUS "Building stable-diffusion.cpp")

    # SD build options — Vulkan disabled for SD because init_backend()
    # crashes on Adreno 640 when both llama.cpp and SD try to init Vulkan
    # in the same process. SD runs CPU-only for now.
    # We save/restore GGML_VULKAN so llama.cpp keeps its Vulkan support.
    set(_SAVED_GGML_VULKAN ${GGML_VULKAN})
    set(SD_BUILD_EXAMPLES  OFF CACHE BOOL "" FORCE)
    set(SD_BUILD_SERVER    OFF CACHE BOOL "" FORCE)
    set(SD_VULKAN          OFF CACHE BOOL "" FORCE)
    set(SD_FLASH_ATTN      ON  CACHE BOOL "" FORCE)
    set(BUILD_SHARED_LIBS  OFF CACHE BOOL "" FORCE)
    set(GGML_VULKAN        OFF CACHE BOOL "" FORCE)

    add_subdirectory(${SD_DIR} build-sd)

    # Restore Vulkan for any subsequent targets
    set(GGML_VULKAN ${_SAVED_GGML_VULKAN} CACHE BOOL "" FORCE)

    add_library(sdbridge SHARED sd_bridge.cpp)

    target_include_directories(sdbridge PRIVATE
        ${SD_DIR}
        ${SD_DIR}/include
        ${SD_DIR}/thirdparty
        ${SD_DIR}/ggml/include
    )

    target_link_libraries(sdbridge
        stable-diffusion
        android
        log
    )
else()
    message(STATUS "stable-diffusion.cpp not found — skipping image generation")
endif()
