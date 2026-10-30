cmake_minimum_required(VERSION 3.22)
project(EndlessSkyAndroid LANGUAGES C CXX)

if(NOT ANDROID)
    message(FATAL_ERROR "Use the Android NDK toolchain to build this application.")
endif()
if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "The initial port is configured for arm64-v8a only.")
endif()

get_filename_component(PORT_ROOT "${CMAKE_CURRENT_LIST_DIR}/../.." ABSOLUTE)
if(NOT EXISTS "${PORT_ROOT}/build/upstream/CMakeLists.txt")
    message(FATAL_ERROR "Run python3 tools/prepare_android.py before configuring Android.")
endif()

# Dependencies were installed by the outer vcpkg toolchain using our Android manifest.
set(ES_USE_VCPKG ON CACHE BOOL "Use cross-compiled vcpkg dependencies" FORCE)
set(ES_USE_SYSTEM_LIBRARIES OFF CACHE BOOL "Use Android dependencies" FORCE)
set(ES_USE_SDL3 OFF CACHE BOOL "Use the SDL2 Java/native bridge" FORCE)
set(ES_GLES ON CACHE BOOL "Use OpenGL ES" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "No desktop test runners in APK" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
# Dependencies are release-only in the custom triplet, including for a debug app.
set(CMAKE_MAP_IMPORTED_CONFIG_DEBUG Release)
set(CMAKE_MAP_IMPORTED_CONFIG_RELWITHDEBINFO Release)
add_subdirectory("${PORT_ROOT}/build/upstream" "${CMAKE_CURRENT_BINARY_DIR}/endless-sky")
