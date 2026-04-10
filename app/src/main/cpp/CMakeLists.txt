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

# Vulkan GPU backend — Adreno 640 (Note 10+) supports Vulkan 1.1.
# The NDK ships Vulkan C headers in the sysroot but the C++ wrapper
# (vulkan.hpp) lives under sources/third_party/vulkan. We point
# Vulkan_INCLUDE_DIR there so find_package(Vulkan) picks up both.
set(GGML_VULKAN          ON  CACHE BOOL "" FORCE)
set(GGML_VULKAN_CHECK_RESULTS OFF CACHE BOOL "" FORCE)

# Help find_package(Vulkan) locate the C++ headers + glslc.
# NDK 27 ships C headers in the sysroot but lacks vulkan.hpp. We vendor
# the Khronos Vulkan-Headers under third_party/ for the C++ wrappers.
set(Vulkan_INCLUDE_DIR "${CMAKE_CURRENT_LIST_DIR}/third_party/Vulkan-Headers/include" CACHE PATH "" FORCE)
set(Vulkan_GLSLC_EXECUTABLE "${ANDROID_NDK}/shader-tools/${ANDROID_HOST_TAG}/glslc" CACHE FILEPATH "" FORCE)

# ARM-specific tuning. KleidiAI gives a meaningful boost on arm64-v8a (the only
# ABI we ship for v1). x86_64 is built only for emulator convenience.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
endif()

set(LLAMA_DIR ${CMAKE_CURRENT_LIST_DIR}/llama.cpp)
add_subdirectory(${LLAMA_DIR} build-llama)

add_library(llmbridge SHARED llm_bridge.cpp)

target_include_directories(llmbridge PRIVATE
    ${LLAMA_DIR}
    ${LLAMA_DIR}/include
    ${LLAMA_DIR}/common
    ${LLAMA_DIR}/ggml/include
)

target_link_libraries(llmbridge
    llama
    common
    android
    log
    vulkan
)
