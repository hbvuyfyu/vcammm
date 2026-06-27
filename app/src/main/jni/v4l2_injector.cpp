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

/* Color conversion functions */
void rgba_to_yuyv(const uint8_t* rgba, uint8_t* yuyv, int width, int height) {
    int size = width * height;
    for (int i = 0; i < size / 2; i++) {
        int r0 = rgba[i * 8 + 0];
        int g0 = rgba[i * 8 + 1];
        int b0 = rgba[i * 8 + 2];
        int r1 = rgba[i * 8 + 4];
        int g1 = rgba[i * 8 + 5];
        int b1 = rgba[i * 8 + 6];

        int y0 = ((66 * r0 + 129 * g0 + 25 * b0 + 128) >> 8) + 16;
        int y1 = ((66 * r1 + 129 * g1 + 25 * b1 + 128) >> 8) + 16;
        int u = ((-38 * r0 - 74 * g0 + 112 * b0 + 128) >> 8) + 128;
        int v = ((112 * r0 - 94 * g0 - 18 * b0 + 128) >> 8) + 128;

        yuyv[i * 4 + 0] = (uint8_t)y0;
        yuyv[i * 4 + 1] = (uint8_t)u;
        yuyv[i * 4 + 2] = (uint8_t)y1;
        yuyv[i * 4 + 3] = (uint8_t)v;
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

            int y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
            y_plane[j * width + i] = (uint8_t)y;

            if (j % 2 == 0 && i % 2 == 0) {
                int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                int uv_idx = (j / 2) * (width / 2) + (i / 2);
                u_plane[uv_idx] = (uint8_t)u;
                v_plane[uv_idx] = (uint8_t)v;
            }
        }
    }
}
