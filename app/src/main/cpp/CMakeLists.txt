cmake_minimum_required(VERSION 3.22.1)
project(dubwhisper C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(BUILD_SHARED_LIBS      OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE            OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)
if (ANDROID)
    # Modern phones (2018+): half-precision + dot-product instructions make Whisper much faster.
    set(GGML_CPU_ARM_ARCH "armv8.2-a+fp16+dotprod" CACHE STRING "" FORCE)
endif()

add_subdirectory(whisper)

add_library(dubwhisper SHARED jni.c)
target_link_libraries(dubwhisper PRIVATE whisper)
if (ANDROID)
    target_link_libraries(dubwhisper PRIVATE log)
    target_compile_options(dubwhisper PRIVATE -O3)
endif()

# sherpa-onnx (prebuilt C API, downloaded by the CI build into src/main/jniLibs): separation + voice fingerprints
if (ANDROID)
    add_library(sherpa-onnx-c-api SHARED IMPORTED)
    set_target_properties(sherpa-onnx-c-api PROPERTIES
        IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libsherpa-onnx-c-api.so)
    add_library(dubsherpa SHARED sherpa_jni.c)
    target_include_directories(dubsherpa PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
    target_link_libraries(dubsherpa PRIVATE sherpa-onnx-c-api m log)
    target_compile_options(dubsherpa PRIVATE -O2)
endif()
