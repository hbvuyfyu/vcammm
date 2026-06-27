/**
 * vcam_jni.cpp
 * JNI layer for VCam - writes real image/video frames to:
 *   1. v4l2loopback device (system-wide, all apps)
 *   2. /data/local/tmp/vcam/frame.yuyv (shared with LD_PRELOAD inject lib)
 */
#include <jni.h>
#include <string>
#include <thread>
#include <atomic>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <sys/stat.h>
#include <linux/videodev2.h>
#include <android/bitmap.h>
#include <android/log.h>

#include "v4l2_injector.h"

#define TAG "VCamJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define VCAM_DIR       "/data/local/tmp/vcam"
#define VCAM_FRAME     VCAM_DIR "/frame.yuyv"
#define VCAM_META      VCAM_DIR "/frame_info"

static std::atomic<bool>  g_running(false);
static std::atomic<bool>  g_has_new_frame(false);
static int                g_video_fd   = -1;
static int                g_frame_w    = 640;
static int                g_frame_h    = 480;

/* Protected frame buffer — Kotlin writes, native reads */
static uint8_t*           g_frame_buf  = nullptr;
static size_t             g_frame_size = 0;
static pthread_mutex_t    g_frame_lock = PTHREAD_MUTEX_INITIALIZER;

/* ── Write frame to the shared file (read by inject lib) ────────────── */
static void write_shared_frame(const uint8_t* data, size_t size, int w, int h) {
    /* Write metadata */
    FILE* mf = fopen(VCAM_META, "w");
    if (mf) { fprintf(mf, "%d %d", w, h); fclose(mf); }

    /* Write frame atomically: write to .tmp then rename */
    const char* tmp = VCAM_DIR "/frame.yuyv.tmp";
    int fd = open(tmp, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd >= 0) {
        write(fd, data, size);
        close(fd);
        rename(tmp, VCAM_FRAME);
    }
}

/* ── V4L2 frame pump ─────────────────────────────────────────────────── */
static void v4l2_frame_pump(int fd, int w, int h) {
    size_t frame_size = (size_t)(w * h * 2); /* YUYV */
    uint8_t* tmp_buf = (uint8_t*)malloc(frame_size);
    if (!tmp_buf) { g_running.store(false); return; }

    LOGI("V4L2 frame pump started: %dx%d", w, h);

    while (g_running.load()) {
        if (g_has_new_frame.exchange(false)) {
            pthread_mutex_lock(&g_frame_lock);
            if (g_frame_buf && g_frame_size <= frame_size) {
                memcpy(tmp_buf, g_frame_buf, g_frame_size);
            }
            pthread_mutex_unlock(&g_frame_lock);
        }

        ssize_t ret = write(fd, tmp_buf, frame_size);
        if (ret < 0 && errno != EAGAIN) {
            LOGE("V4L2 write error: %s", strerror(errno));
            break;
        }
        usleep(33333); /* ~30fps */
    }

    free(tmp_buf);
    LOGI("V4L2 frame pump stopped");
}

extern "C" {

/* ─────────────────────────────────────────────────────────────────────
 * nativeStartFrameLoop — open v4l2 device, start pump thread.
 * Called once when injection begins.
 * ───────────────────────────────────────────────────────────────────── */
JNIEXPORT jboolean JNICALL
Java_com_vcam_utils_CameraInjector_nativeStartFrameLoop(
        JNIEnv* env, jobject thiz,
        jint width, jint height, jstring device_path) {

    if (g_running.load()) return JNI_TRUE; /* already running */

    const char* dev = env->GetStringUTFChars(device_path, nullptr);
    LOGI("Starting frame loop: %dx%d on %s", width, height, dev);

    int fd = v4l2_open_device(dev);
    if (fd < 0) {
        LOGE("Cannot open v4l2 device: %s", dev);
        env->ReleaseStringUTFChars(device_path, dev);
        return JNI_FALSE;
    }

    if (v4l2_set_format(fd, width, height, V4L2_PIX_FMT_YUYV) < 0) {
        v4l2_close_device(fd);
        env->ReleaseStringUTFChars(device_path, dev);
        return JNI_FALSE;
    }

    g_video_fd   = fd;
    g_frame_w    = width;
    g_frame_h    = height;
    g_frame_size = (size_t)(width * height * 2);

    /* Allocate frame buffer */
    pthread_mutex_lock(&g_frame_lock);
    if (g_frame_buf) { free(g_frame_buf); g_frame_buf = nullptr; }
    g_frame_buf = (uint8_t*)calloc(1, g_frame_size);
    /* Default: gray */
    if (g_frame_buf) {
        for (size_t i = 0; i < g_frame_size; i += 4) {
            g_frame_buf[i+0] = 128;
            g_frame_buf[i+1] = 128;
            g_frame_buf[i+2] = 128;
            g_frame_buf[i+3] = 128;
        }
    }
    pthread_mutex_unlock(&g_frame_lock);

    g_running.store(true);
    g_has_new_frame.store(true);

    /* Start pump thread */
    std::thread([fd, width, height]() {
        v4l2_frame_pump(fd, width, height);
        v4l2_close_device(fd);
        g_video_fd = -1;
    }).detach();

    env->ReleaseStringUTFChars(device_path, dev);
    LOGI("Frame loop started");
    return JNI_TRUE;
}

/* ─────────────────────────────────────────────────────────────────────
 * nativeUpdateYUYVFrame — Kotlin sends a new YUYV frame (jbyteArray).
 * Copies into g_frame_buf and writes to shared file.
 * ───────────────────────────────────────────────────────────────────── */
JNIEXPORT void JNICALL
Java_com_vcam_utils_CameraInjector_nativeUpdateYUYVFrame(
        JNIEnv* env, jobject thiz,
        jbyteArray yuyv_data, jint width, jint height) {

    if (!yuyv_data) return;
    jsize len = env->GetArrayLength(yuyv_data);
    if (len <= 0) return;

    jbyte* raw = env->GetByteArrayElements(yuyv_data, nullptr);
    if (!raw) return;

    size_t size = (size_t)len;

    pthread_mutex_lock(&g_frame_lock);
    if (!g_frame_buf || g_frame_size != size) {
        if (g_frame_buf) free(g_frame_buf);
        g_frame_buf  = (uint8_t*)malloc(size);
        g_frame_size = size;
    }
    if (g_frame_buf) memcpy(g_frame_buf, raw, size);
    pthread_mutex_unlock(&g_frame_lock);

    g_has_new_frame.store(true);

    /* Also write to shared file for LD_PRELOAD inject lib */
    write_shared_frame((const uint8_t*)raw, size, width, height);

    env->ReleaseByteArrayElements(yuyv_data, raw, JNI_ABORT);
}

/* ─────────────────────────────────────────────────────────────────────
 * nativeStopInjection — stop all injection
 * ───────────────────────────────────────────────────────────────────── */
JNIEXPORT void JNICALL
Java_com_vcam_utils_CameraInjector_nativeStopInjection(
        JNIEnv* env, jobject thiz) {
    LOGI("Stop injection requested");
    g_running.store(false);

    pthread_mutex_lock(&g_frame_lock);
    if (g_frame_buf) { free(g_frame_buf); g_frame_buf = nullptr; }
    pthread_mutex_unlock(&g_frame_lock);

    /* Remove shared files */
    unlink(VCAM_FRAME);
    unlink(VCAM_META);
}

/* ─────────────────────────────────────────────────────────────────────
 * nativeCheckDevice — check if a v4l2 device is usable
 * ───────────────────────────────────────────────────────────────────── */
JNIEXPORT jboolean JNICALL
Java_com_vcam_utils_CameraInjector_nativeCheckDevice(
        JNIEnv* env, jobject thiz, jstring device_path) {
    const char* path = env->GetStringUTFChars(device_path, nullptr);
    jboolean result = (jboolean)v4l2_check_device(path);
    env->ReleaseStringUTFChars(device_path, path);
    return result;
}

/* ─────────────────────────────────────────────────────────────────────
 * Legacy stubs kept for compatibility
 * ───────────────────────────────────────────────────────────────────── */
JNIEXPORT jint JNICALL
Java_com_vcam_utils_CameraInjector_nativeInjectImage(
        JNIEnv* env, jobject thiz,
        jstring image_path, jstring video_device) {
    /* Deprecated: use nativeStartFrameLoop + nativeUpdateYUYVFrame */
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_vcam_utils_CameraInjector_nativeInjectVideo(
        JNIEnv* env, jobject thiz,
        jstring video_path, jstring video_device) {
    /* Deprecated: use nativeStartFrameLoop + nativeUpdateYUYVFrame */
    return 0;
}

} // extern "C"
