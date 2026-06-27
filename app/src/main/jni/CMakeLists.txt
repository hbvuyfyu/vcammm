cmake_minimum_required(VERSION 3.22.1)
project("vcam_native")

# ── Library 1: vcam_native — JNI + V4L2 frame pump ───────────────────────────
# Called from Kotlin: converts bitmaps, writes to v4l2 device and shared file.
add_library(vcam_native SHARED
    vcam_jni.cpp
    v4l2_injector.cpp
    image_converter.cpp
)
find_library(log-lib      log)
find_library(jnigraphics-lib jnigraphics)
find_library(android-lib  android)
target_link_libraries(vcam_native
    ${log-lib}
    ${jnigraphics-lib}
    ${android-lib}
)
target_compile_options(vcam_native PRIVATE -O2 -Wall -std=c++17)

# ── Library 2: vcam_inject — LD_PRELOAD Camera HAL hook ──────────────────────
# Loaded into cameraserver or target app.
# Intercepts hw_get_module → serves YUYV frames from shared file.
add_library(vcam_inject SHARED vcam_inject.cpp)
target_link_libraries(vcam_inject ${log-lib} dl)
target_compile_options(vcam_inject PRIVATE -O2 -Wall -std=c++17)

# Export both libs so they're packaged in the APK's jniLibs
set_target_properties(vcam_native PROPERTIES
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}"
)
set_target_properties(vcam_inject PROPERTIES
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}"
)
