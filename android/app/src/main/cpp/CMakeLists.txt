cmake_minimum_required(VERSION 3.22)
project(whisper_jni C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Keep native libraries aligned for 16 KB page-size devices (Android 15+).
add_link_options(-Wl,-z,max-page-size=16384)

set(WHISPER_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp" CACHE PATH "whisper.cpp source directory")
set(SPIRV_HEADERS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/SPIRV-Headers" CACHE PATH "SPIR-V headers directory")
if(NOT EXISTS "${WHISPER_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "whisper.cpp not found at ${WHISPER_DIR}. Run android/scripts/fetch-native-deps.sh first.")
endif()
if(NOT EXISTS "${SPIRV_HEADERS_DIR}/include/spirv/unified1/spirv.hpp")
    message(FATAL_ERROR "SPIR-V headers not found at ${SPIRV_HEADERS_DIR}. Run android/scripts/fetch-native-deps.sh first.")
endif()

# whisper.cpp rewrites CMake's output directories. Keep AGP's values so the JNI
# library still lands where Gradle expects it.
set(_jni_saved_library_output "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}")
set(_jni_saved_runtime_output "${CMAKE_RUNTIME_OUTPUT_DIRECTORY}")

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(WHISPER_FATAL_WARNINGS OFF CACHE BOOL "" FORCE)
set(WHISPER_OPENMP OFF CACHE BOOL "" FORCE)
set(WHISPER_COREML OFF CACHE BOOL "" FORCE)
set(WHISPER_OPENVINO OFF CACHE BOOL "" FORCE)
set(WHISPER_VITISAI OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN ON CACHE BOOL "" FORCE)
set(GGML_VULKAN_SHADERS_GEN_TOOLCHAIN "" CACHE FILEPATH "Host toolchain for vulkan-shaders-gen")

# During cross-compilation the NDK sysroot is searched for libraries/headers.
# The host provides the SPIRV-Headers CONFIG package used by ggml-vulkan,
# so allow CONFIG packages from both the sysroot and the host.
if(CMAKE_CROSSCOMPILING)
    set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE BOTH)
endif()

add_subdirectory("${WHISPER_DIR}" "${CMAKE_BINARY_DIR}/whisper.cpp")

# ggml-vulkan only calls find_package(SPIRV-Headers) without linking its
# imported target, so the NDK compile never sees the header search path.
if(TARGET ggml-vulkan)
    target_include_directories(ggml-vulkan PRIVATE "${SPIRV_HEADERS_DIR}/include")
else()
    message(WARNING "ggml-vulkan target not found; Vulkan backend may be disabled")
endif()

if(_jni_saved_library_output)
    set(CMAKE_LIBRARY_OUTPUT_DIRECTORY "${_jni_saved_library_output}")
endif()
if(_jni_saved_runtime_output)
    set(CMAKE_RUNTIME_OUTPUT_DIRECTORY "${_jni_saved_runtime_output}")
endif()

add_library(whisper_jni SHARED whisper_jni.cpp)

target_link_libraries(whisper_jni PRIVATE whisper log)
target_compile_options(whisper_jni PRIVATE -Wall -Wextra -Wno-unused-parameter -fvisibility=hidden)
target_link_options(whisper_jni PRIVATE -Wl,--exclude-libs,ALL)
