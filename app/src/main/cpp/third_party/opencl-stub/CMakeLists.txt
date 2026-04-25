# Build a SHARED libOpenCL.so containing no-op stubs for every OpenCL
# function declared in the Khronos headers. This .so is used ONLY at
# link time so the GGML OpenCL backend can resolve clCreateContext et al.
#
# At runtime, the Android device's /vendor/lib64/libOpenCL.so (declared
# via <uses-native-library> in AndroidManifest.xml) replaces these
# stubs. The Adreno (or Mali) OpenCL driver becomes the actual
# implementation invoked by ggml-opencl. None of this stub code ever
# runs.
#
# Verified: the generated stub exposes 219 symbols, which is a
# superset of the 161 symbols the Adreno 740 driver exports.

cmake_minimum_required(VERSION 3.22.1)

add_library(OpenCL SHARED opencl_stub.c)

target_include_directories(OpenCL PUBLIC
    ${CMAKE_CURRENT_LIST_DIR}/../OpenCL-Headers
)

target_compile_definitions(OpenCL PUBLIC
    CL_TARGET_OPENCL_VERSION=300
)

# Suppress warnings about deprecated API decls — the Khronos headers
# warn about CL 1.0 functions even when those are intentionally listed.
target_compile_options(OpenCL PRIVATE
    -Wno-deprecated-declarations
    -Wno-unused-parameter
    -Wno-unused-variable
)

set_target_properties(OpenCL PROPERTIES
    OUTPUT_NAME "OpenCL"
    SOVERSION  ""
    VERSION    ""
)
