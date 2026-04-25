# Custom FindOpenCL.cmake.
#
# CMake's stock FindOpenCL searches the host filesystem for libOpenCL.so,
# which doesn't exist on Android (the real driver lives at
# /vendor/lib64/libOpenCL.so on-device, not on the build machine). We
# satisfy find_package(OpenCL) by handing it the in-tree stub built by
# third_party/opencl-stub/ (added via add_subdirectory in our top
# CMakeLists.txt).
#
# At runtime, AndroidManifest.xml's <uses-native-library> entry tells
# Android's dynamic linker to bind our process against the device's
# real libOpenCL.so, so the stub's no-op functions are never invoked.

if (NOT TARGET OpenCL)
    message(FATAL_ERROR
        "Custom FindOpenCL.cmake expected an in-tree 'OpenCL' target. "
        "Make sure third_party/opencl-stub is added via add_subdirectory "
        "before any caller does find_package(OpenCL).")
endif()

set(OpenCL_INCLUDE_DIR  "${CMAKE_CURRENT_LIST_DIR}/../third_party/OpenCL-Headers" CACHE PATH "" FORCE)
set(OpenCL_INCLUDE_DIRS "${OpenCL_INCLUDE_DIR}" CACHE STRING "" FORCE)
set(OpenCL_LIBRARY      OpenCL CACHE STRING "" FORCE)
set(OpenCL_LIBRARIES    OpenCL CACHE STRING "" FORCE)
set(OpenCL_VERSION_STRING "3.0" CACHE STRING "" FORCE)
set(OpenCL_VERSION_MAJOR 3      CACHE STRING "" FORCE)
set(OpenCL_VERSION_MINOR 0      CACHE STRING "" FORCE)
set(OpenCL_FOUND TRUE)

# OpenCL::OpenCL is the modern imported-target name some callers use.
if (NOT TARGET OpenCL::OpenCL)
    add_library(OpenCL::OpenCL ALIAS OpenCL)
endif()
