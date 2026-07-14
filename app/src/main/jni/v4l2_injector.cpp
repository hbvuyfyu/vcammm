#include "v4l2_injector.h"

#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <string.h>
#include <stdlib.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <linux/videodev2.h>
#include <android/log.h>

#define TAG "VCamNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static volatile int g_stop_flag = 0;

static inline int clamp(int v, int lo, int hi) {
    if (v < lo) return lo;
    if (v > hi) return hi;
    return v;
}

void v4l2_request_stop() {
    g_stop_flag = 1;
}

int v4l2_open_device(const char* device_path) {
    int fd = open(device_path, O_RDWR | O_NONBLOCK, 0);
    if (fd < 0) {
        LOGE("Cannot open device %s: %s", device_path, strerror(errno));
        return -1;
    }
    LOGI("Opened device: %s (fd=%d)", device_path, fd);
    return fd;
}

int v4l2_query_capability(int fd) {
    struct v4l2_capability cap;
    if (ioctl(fd, VIDIOC_QUERYCAP, &cap) < 0) {
        LOGE("VIDIOC_QUERYCAP failed: %s", strerror(errno));
        return -1;
    }
    LOGI("Driver: %s, Card: %s, Bus: %s", cap.driver, cap.card, cap.bus_info);
    if (!(cap.capabilities & V4L2_CAP_VIDEO_OUTPUT)) {
        LOGE("Device does not support video output");
        return -1;
    }
    return 0;
}

int v4l2_set_format(int fd, int width, int height, uint32_t format) {
    struct v4l2_format fmt = {};
    fmt.type = V4L2_BUF_TYPE_VIDEO_OUTPUT;
    fmt.fmt.pix.width = width;
    fmt.fmt.pix.height = height;
    fmt.fmt.pix.pixelformat = format;
    fmt.fmt.pix.field = V4L2_FIELD_NONE;
    fmt.fmt.pix.bytesperline = width * 2; // YUYV: 2 bytes per pixel
    fmt.fmt.pix.sizeimage = width * height * 2;
    fmt.fmt.pix.colorspace = V4L2_COLORSPACE_SRGB;
    fmt.fmt.pix.quantization = V4L2_QUANTIZATION_FULL_RANGE;
    fmt.fmt.pix.xfer_func = V4L2_XFER_FUNC_SRGB;
    fmt.fmt.pix.ycbcr_enc = V4L2_YCBCR_ENC_601;

    if (ioctl(fd, VIDIOC_S_FMT, &fmt) < 0) {
        // Try YUV420
        fmt.fmt.pix.pixelformat = V4L2_PIX_FMT_YUV420;
        fmt.fmt.pix.bytesperline = width;
        fmt.fmt.pix.sizeimage = width * height * 3 / 2;
        if (ioctl(fd, VIDIOC_S_FMT, &fmt) < 0) {
            LOGE("VIDIOC_S_FMT failed: %s", strerror(errno));
            return -1;
        }
    }
    LOGI("Format set: %dx%d", fmt.fmt.pix.width, fmt.fmt.pix.height);
    return 0;
}

int v4l2_write_frame(int fd, const uint8_t* data, size_t size, int width, int height) {
    ssize_t written = write(fd, data, size);
    if (written < 0) {
        if (errno != EAGAIN) {
            LOGE("Write failed: %s", strerror(errno));
        }
        return -1;
    }
    return (int)written;
}

void v4l2_close_device(int fd) {
    if (fd >= 0) {
        close(fd);
        LOGI("Device closed");
    }
}

int v4l2_check_device(const char* device_path) {
    int fd = open(device_path, O_RDWR | O_NONBLOCK);
    if (fd < 0) return 0;

    struct v4l2_capability cap;
    int has_output = 0;
    if (ioctl(fd, VIDIOC_QUERYCAP, &cap) >= 0) {
        has_output = (cap.capabilities & V4L2_CAP_VIDEO_OUTPUT) ? 1 : 0;
    }
    close(fd);
    return has_output;
}

/* Full-range (0-255) BT.601 RGB→YUV conversion.
 * Matches V4L2_COLORSPACE_SRGB so consumers interpret values as full-range
 * and colours stay accurate (no red tint from limited-range clamping). */
void rgba_to_yuyv(const uint8_t* rgba, uint8_t* yuyv, int width, int height) {
    int size = width * height;
    for (int i = 0; i < size / 2; i++) {
        int r0 = rgba[i * 8 + 0];
        int g0 = rgba[i * 8 + 1];
        int b0 = rgba[i * 8 + 2];
        int r1 = rgba[i * 8 + 4];
        int g1 = rgba[i * 8 + 5];
        int b1 = rgba[i * 8 + 6];

        int y0 = (77 * r0 + 150 * g0 + 29 * b0) >> 8;
        int y1 = (77 * r1 + 150 * g1 + 29 * b1) >> 8;
        int u = ((-43 * r0 - 85 * g0 + 128 * b0) >> 8) + 128;
        int v = ((128 * r0 - 107 * g0 - 21 * b0) >> 8) + 128;

        yuyv[i * 4 + 0] = (uint8_t)clamp(y0, 0, 255);
        yuyv[i * 4 + 1] = (uint8_t)clamp(u,  0, 255);
        yuyv[i * 4 + 2] = (uint8_t)clamp(y1, 0, 255);
        yuyv[i * 4 + 3] = (uint8_t)clamp(v,  0, 255);
    }
}

void rgba_to_yuv420(const uint8_t* rgba, uint8_t* yuv, int width, int height) {
    uint8_t* y_plane = yuv;
    uint8_t* u_plane = yuv + width * height;
    uint8_t* v_plane = u_plane + (width * height / 4);

    for (int j = 0; j < height; j++) {
        for (int i = 0; i < width; i++) {
            int idx = (j * width + i) * 4;
            int r = rgba[idx + 0];
            int g = rgba[idx + 1];
            int b = rgba[idx + 2];

            int y = (77 * r + 150 * g + 29 * b) >> 8;
            y_plane[j * width + i] = (uint8_t)clamp(y, 0, 255);

            if (j % 2 == 0 && i % 2 == 0) {
                int u = ((-43 * r - 85 * g + 128 * b) >> 8) + 128;
                int v = ((128 * r - 107 * g - 21 * b) >> 8) + 128;
                int uv_idx = (j / 2) * (width / 2) + (i / 2);
                u_plane[uv_idx] = (uint8_t)clamp(u, 0, 255);
                v_plane[uv_idx] = (uint8_t)clamp(v, 0, 255);
            }
        }
    }
}
