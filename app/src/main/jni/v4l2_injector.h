#pragma once

#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    int fd;
    int width;
    int height;
    uint32_t pixel_format;
    int running;
} V4L2Device;

int v4l2_open_device(const char* device_path);
int v4l2_query_capability(int fd);
int v4l2_set_format(int fd, int width, int height, uint32_t format);
int v4l2_init_mmap(int fd);
int v4l2_write_frame(int fd, const uint8_t* data, size_t size, int width, int height);
void v4l2_close_device(int fd);
int v4l2_check_device(const char* device_path);

/* Image conversion */
void rgb_to_yuv420(const uint8_t* rgb, uint8_t* yuv, int width, int height);
void rgba_to_yuv420(const uint8_t* rgba, uint8_t* yuv, int width, int height);
void rgba_to_yuyv(const uint8_t* rgba, uint8_t* yuyv, int width, int height);

#ifdef __cplusplus
}
#endif
