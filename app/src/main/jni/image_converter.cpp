#include "v4l2_injector.h"

#include <stdlib.h>
#include <string.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <jni.h>

#define TAG "VCamConverter"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Simple BMP/JPEG stub — real decode handled by Android framework in Kotlin
// This file provides the raw conversion primitives

uint8_t* alloc_yuv420_buffer(int width, int height) {
    return (uint8_t*)malloc(width * height * 3 / 2);
}

uint8_t* alloc_yuyv_buffer(int width, int height) {
    return (uint8_t*)malloc(width * height * 2);
}

void free_buffer(uint8_t* buf) {
    if (buf) free(buf);
}

// Clamp helper
static inline int clamp(int v, int lo, int hi) {
    if (v < lo) return lo;
    if (v > hi) return hi;
    return v;
}

// Scale RGBA buffer to target dimensions (nearest-neighbour)
void scale_rgba(const uint8_t* src, int sw, int sh,
                uint8_t* dst, int dw, int dh) {
    for (int j = 0; j < dh; j++) {
        for (int i = 0; i < dw; i++) {
            int si = i * sw / dw;
            int sj = j * sh / dh;
            int si_clamped = clamp(si, 0, sw - 1);
            int sj_clamped = clamp(sj, 0, sh - 1);
            int src_idx = (sj_clamped * sw + si_clamped) * 4;
            int dst_idx = (j * dw + i) * 4;
            dst[dst_idx + 0] = src[src_idx + 0];
            dst[dst_idx + 1] = src[src_idx + 1];
            dst[dst_idx + 2] = src[src_idx + 2];
            dst[dst_idx + 3] = src[src_idx + 3];
        }
    }
}
