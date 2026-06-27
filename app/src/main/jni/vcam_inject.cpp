/**
 * vcam_inject.cpp
 * LD_PRELOAD hook for system-wide camera injection.
 *
 * Loaded into cameraserver (system-wide) or target app process.
 * Intercepts hw_get_module / hw_get_module_by_class → returns fake camera module.
 * Reads YUYV frames from /data/local/tmp/vcam/frame.yuyv written by VCamService.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <android/log.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include <unistd.h>
#include <stdint.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <errno.h>

#define TAG "VCamInject"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define VCAM_DIR        "/data/local/tmp/vcam"
#define VCAM_FRAME_YUV  VCAM_DIR "/frame.yuyv"
#define VCAM_META       VCAM_DIR "/frame_info"
#define VCAM_CONFIG     VCAM_DIR "/vcam_config"

/* ── AOSP hardware.h binary layout ──────────────────────────────────── */
typedef struct hw_module_methods_t {
    int (*open)(const struct hw_module_t* module, const char* id,
                struct hw_device_t** device);
} hw_module_methods_t;

typedef struct hw_module_t {
    uint32_t tag;
    uint16_t module_api_version;
    uint16_t hal_api_version;
    const char* id;
    const char* name;
    const char* author;
    hw_module_methods_t* methods;
    void* dso;
#ifdef __LP64__
    uint64_t reserved[32-7];
#else
    uint32_t reserved[32-7];
#endif
} hw_module_t;

typedef struct hw_device_t {
    uint32_t tag;
    uint32_t version;
    struct hw_module_t* module;
#ifdef __LP64__
    uint64_t reserved[12];
#else
    uint32_t reserved[12];
#endif
    int (*close)(struct hw_device_t* device);
} hw_device_t;

/* ── Camera HAL 1 types ──────────────────────────────────────────────── */
typedef struct camera_memory {
    void*  data;
    size_t size;
    void*  handle;
    void  (*release)(struct camera_memory*);
} camera_memory_t;

typedef void (*camera_notify_cb)(int32_t, int32_t, int32_t, void*);
typedef void (*camera_data_cb)(int32_t, camera_memory_t*, unsigned, void*);
typedef void (*camera_ts_cb)(int64_t, int32_t, camera_memory_t*, unsigned, void*);
typedef camera_memory_t* (*camera_request_memory_t)(int fd, size_t buf_size, unsigned int num_bufs, void* user);
struct preview_stream_ops;

typedef struct camera_device_ops_t {
    int  (*set_preview_window)(struct camera_device*, struct preview_stream_ops*);
    void (*set_callbacks)(struct camera_device*, camera_notify_cb, camera_data_cb,
                          camera_ts_cb, camera_request_memory_t, void*);
    void (*enable_msg_type)(struct camera_device*, int32_t);
    void (*disable_msg_type)(struct camera_device*, int32_t);
    int  (*msg_type_enabled)(struct camera_device*, int32_t);
    int  (*start_preview)(struct camera_device*);
    void (*stop_preview)(struct camera_device*);
    int  (*preview_enabled)(struct camera_device*);
    int  (*store_meta_data_in_buffers)(struct camera_device*, int);
    int  (*start_recording)(struct camera_device*);
    void (*stop_recording)(struct camera_device*);
    int  (*recording_enabled)(struct camera_device*);
    void (*release_recording_frame)(struct camera_device*, const void*);
    int  (*auto_focus)(struct camera_device*);
    int  (*cancel_auto_focus)(struct camera_device*);
    int  (*take_picture)(struct camera_device*);
    int  (*cancel_picture)(struct camera_device*);
    int  (*set_parameters)(struct camera_device*, const char*);
    char*(*get_parameters)(struct camera_device*);
    void (*put_parameters)(struct camera_device*, char*);
    int  (*send_command)(struct camera_device*, int32_t, int32_t, int32_t);
    void (*release)(struct camera_device*);
    int  (*dump)(struct camera_device*, int);
} camera_device_ops_t;

typedef struct camera_device {
    hw_device_t         common;
    camera_device_ops_t* ops;
    void*               priv;
} camera_device_t;

/* ── Real function pointers ─────────────────────────────────────────── */
typedef int (*hw_get_module_by_class_fn)(const char*, const char*, const hw_module_t**);
typedef int (*hw_get_module_fn)(const char*, const hw_module_t**);
static hw_get_module_by_class_fn real_by_class = NULL;
static hw_get_module_fn          real_get      = NULL;

/* ── Per-device state ───────────────────────────────────────────────── */
typedef struct {
    camera_data_cb         data_cb;
    camera_request_memory_t request_mem;
    void*                  user;
    volatile int           preview_running;
    volatile int           recording_running;
    pthread_t              preview_thread;
    int                    width;
    int                    height;
    int                    rotation;   /* degrees: 0/90/180/270 */
    int                    mirror;     /* 0 or 1 */
} fake_cam_priv_t;

/* ── Frame reading from shared file ─────────────────────────────────── */
static int g_frame_width  = 640;
static int g_frame_height = 480;

static void read_frame_meta(void) {
    FILE* f = fopen(VCAM_META, "r");
    if (!f) return;
    int w = 640, h = 480;
    fscanf(f, "%d %d", &w, &h);
    fclose(f);
    if (w > 0 && w <= 4096) g_frame_width  = w;
    if (h > 0 && h <= 4096) g_frame_height = h;
}

/**
 * Read the latest YUYV frame from shared file.
 * Returns number of bytes read, or 0 on failure.
 */
static size_t read_yuyv_frame(uint8_t* dst, size_t max_bytes) {
    int fd = open(VCAM_FRAME_YUV, O_RDONLY);
    if (fd < 0) return 0;
    ssize_t n = read(fd, dst, max_bytes);
    close(fd);
    return (n > 0) ? (size_t)n : 0;
}

/* ── Preview thread ─────────────────────────────────────────────────── */
static void* preview_thread_fn(void* arg) {
    fake_cam_priv_t* priv = (fake_cam_priv_t*)arg;
    read_frame_meta();
    int w = g_frame_width;
    int h = g_frame_height;
    size_t frame_size = (size_t)(w * h * 2); /* YUYV: 2 bytes/pixel */

    uint8_t* frame_buf = (uint8_t*)malloc(frame_size);
    if (!frame_buf) {
        priv->preview_running = 0;
        return NULL;
    }

    /* Default frame: gray */
    for (size_t i = 0; i < frame_size; i += 4) {
        frame_buf[i+0] = 128; /* Y */
        frame_buf[i+1] = 128; /* U */
        frame_buf[i+2] = 128; /* Y */
        frame_buf[i+3] = 128; /* V */
    }

    LOGI("Preview thread started: %dx%d", w, h);

    while (priv->preview_running) {
        /* Try to read the real frame from the shared file */
        size_t n = read_yuyv_frame(frame_buf, frame_size);
        if (n == 0) {
            /* Keep the previous frame if file not ready */
        }

        if (priv->data_cb && priv->request_mem) {
            /* Allocate camera memory and deliver frame */
            camera_memory_t* mem = priv->request_mem(-1, frame_size, 1, priv->user);
            if (mem && mem->data) {
                memcpy(mem->data, frame_buf, frame_size);
                /* CAMERA_MSG_PREVIEW_FRAME = 0x008 */
                priv->data_cb(0x008, mem, 0, priv->user);
                if (mem->release) mem->release(mem);
            }
        }

        /* ~30 fps */
        usleep(33333);
    }

    free(frame_buf);
    LOGI("Preview thread stopped");
    return NULL;
}

/* ── Forward declarations ────────────────────────────────────────────── */
static void fake_stop_preview(struct camera_device* dev);

/* ── Fake device close ───────────────────────────────────────────────── */
static int fake_device_close(struct hw_device_t* device) {
    if (!device) return 0;
    camera_device_t* cam = (camera_device_t*)device;
    fake_stop_preview(cam);
    fake_cam_priv_t* priv = (fake_cam_priv_t*)cam->priv;
    if (priv) {
        free(priv);
        cam->priv = NULL;
    }
    free(cam);
    LOGI("VCam: device closed");
    return 0;
}

/* ── Fake camera device ops ─────────────────────────────────────────── */
static int fake_set_preview_window(struct camera_device* dev,
                                    struct preview_stream_ops* window) {
    (void)dev; (void)window;
    return 0;
}

static void fake_set_callbacks(struct camera_device* dev,
                                camera_notify_cb notify_cb,
                                camera_data_cb data_cb,
                                camera_ts_cb ts_cb,
                                camera_request_memory_t get_memory,
                                void* user) {
    (void)notify_cb; (void)ts_cb;
    fake_cam_priv_t* priv = (fake_cam_priv_t*)dev->priv;
    if (priv) {
        priv->data_cb    = data_cb;
        priv->request_mem = get_memory;
        priv->user       = user;
    }
}

static void fake_enable_msg_type(struct camera_device* dev, int32_t msg_type) {
    (void)dev; (void)msg_type;
}
static void fake_disable_msg_type(struct camera_device* dev, int32_t msg_type) {
    (void)dev; (void)msg_type;
}
static int fake_msg_type_enabled(struct camera_device* dev, int32_t msg_type) {
    (void)dev; (void)msg_type; return 1;
}

static int fake_start_preview(struct camera_device* dev) {
    fake_cam_priv_t* priv = (fake_cam_priv_t*)dev->priv;
    if (!priv || priv->preview_running) return 0;
    priv->preview_running = 1;
    int rc = pthread_create(&priv->preview_thread, NULL, preview_thread_fn, priv);
    if (rc != 0) {
        LOGE("VCam: pthread_create failed: %d", rc);
        priv->preview_running = 0;
        return -1;
    }
    LOGI("VCam: preview started");
    return 0;
}

static void fake_stop_preview(struct camera_device* dev) {
    fake_cam_priv_t* priv = (fake_cam_priv_t*)dev->priv;
    if (!priv || !priv->preview_running) return;
    priv->preview_running = 0;
    pthread_join(priv->preview_thread, NULL);
    LOGI("VCam: preview stopped");
}

static int fake_preview_enabled(struct camera_device* dev) {
    fake_cam_priv_t* priv = (fake_cam_priv_t*)dev->priv;
    return (priv && priv->preview_running) ? 1 : 0;
}

static int fake_store_meta(struct camera_device* dev, int enable) {
    (void)dev; (void)enable; return 0;
}
static int fake_start_recording(struct camera_device* dev) {
    fake_cam_priv_t* priv = (fake_cam_priv_t*)dev->priv;
    if (priv) priv->recording_running = 1;
    return 0;
}
static void fake_stop_recording(struct camera_device* dev) {
    fake_cam_priv_t* priv = (fake_cam_priv_t*)dev->priv;
    if (priv) priv->recording_running = 0;
}
static int fake_recording_enabled(struct camera_device* dev) {
    fake_cam_priv_t* priv = (fake_cam_priv_t*)dev->priv;
    return (priv && priv->recording_running) ? 1 : 0;
}
static void fake_release_recording_frame(struct camera_device* dev, const void* opaque) {
    (void)dev; (void)opaque;
}
static int  fake_auto_focus(struct camera_device* dev)        { (void)dev; return 0; }
static int  fake_cancel_auto_focus(struct camera_device* dev) { (void)dev; return 0; }
static int  fake_take_picture(struct camera_device* dev) {
    /* Deliver a JPEG snapshot using the preview frame */
    fake_cam_priv_t* priv = (fake_cam_priv_t*)dev->priv;
    if (priv && priv->data_cb && priv->request_mem) {
        read_frame_meta();
        size_t frame_size = (size_t)(g_frame_width * g_frame_height * 2);
        uint8_t* buf = (uint8_t*)malloc(frame_size);
        if (buf) {
            read_yuyv_frame(buf, frame_size);
            camera_memory_t* mem = priv->request_mem(-1, frame_size, 1, priv->user);
            if (mem && mem->data) {
                memcpy(mem->data, buf, frame_size);
                /* CAMERA_MSG_RAW_IMAGE = 0x080 */
                priv->data_cb(0x080, mem, 0, priv->user);
                if (mem->release) mem->release(mem);
            }
            free(buf);
        }
    }
    return 0;
}
static int  fake_cancel_picture(struct camera_device* dev)    { (void)dev; return 0; }

static int  fake_set_parameters(struct camera_device* dev, const char* params) {
    (void)dev; (void)params; return 0;
}
static char* fake_get_parameters(struct camera_device* dev) {
    (void)dev;
    /* Return minimal camera parameters */
    static char params[] =
        "preview-size=640x480;"
        "preview-size-values=1920x1080,1280x720,640x480,320x240;"
        "picture-size=1920x1080;"
        "picture-format=jpeg;"
        "jpeg-quality=90;"
        "preview-format=yuv422i-yuyv;"
        "preview-frame-rate=30;";
    return params;
}
static void  fake_put_parameters(struct camera_device* dev, char* params) { (void)dev; (void)params; }
static int   fake_send_command(struct camera_device* dev, int32_t cmd, int32_t arg1, int32_t arg2) {
    (void)dev; (void)cmd; (void)arg1; (void)arg2; return 0;
}
static void  fake_release(struct camera_device* dev) {
    fake_stop_preview(dev);
    fake_cam_priv_t* priv = (fake_cam_priv_t*)dev->priv;
    if (priv) {
        free(priv);
        dev->priv = NULL;
    }
}
static int   fake_dump(struct camera_device* dev, int fd) { (void)dev; (void)fd; return 0; }

static camera_device_ops_t g_fake_ops = {
    .set_preview_window       = fake_set_preview_window,
    .set_callbacks            = fake_set_callbacks,
    .enable_msg_type          = fake_enable_msg_type,
    .disable_msg_type         = fake_disable_msg_type,
    .msg_type_enabled         = fake_msg_type_enabled,
    .start_preview            = fake_start_preview,
    .stop_preview             = fake_stop_preview,
    .preview_enabled          = fake_preview_enabled,
    .store_meta_data_in_buffers = fake_store_meta,
    .start_recording          = fake_start_recording,
    .stop_recording           = fake_stop_recording,
    .recording_enabled        = fake_recording_enabled,
    .release_recording_frame  = fake_release_recording_frame,
    .auto_focus               = fake_auto_focus,
    .cancel_auto_focus        = fake_cancel_auto_focus,
    .take_picture             = fake_take_picture,
    .cancel_picture           = fake_cancel_picture,
    .set_parameters           = fake_set_parameters,
    .get_parameters           = fake_get_parameters,
    .put_parameters           = fake_put_parameters,
    .send_command             = fake_send_command,
    .release                  = fake_release,
    .dump                     = fake_dump,
};

/* ── Fake module: open function ─────────────────────────────────────── */
static int fake_camera_open(const struct hw_module_t* module,
                             const char* id,
                             struct hw_device_t** device) {
    LOGI("VCam: fake_camera_open(%s)", id ? id : "null");

    camera_device_t* cam = (camera_device_t*)calloc(1, sizeof(camera_device_t));
    if (!cam) return -ENOMEM;

    fake_cam_priv_t* priv = (fake_cam_priv_t*)calloc(1, sizeof(fake_cam_priv_t));
    if (!priv) { free(cam); return -ENOMEM; }

    read_frame_meta();
    priv->width  = g_frame_width;
    priv->height = g_frame_height;

    cam->common.tag     = 0x48574456; /* HWDV */
    cam->common.version = 1;
    cam->common.module  = (struct hw_module_t*)module;
    cam->common.close   = fake_device_close;
    cam->ops            = &g_fake_ops;
    cam->priv           = priv;

    *device = &cam->common;
    return 0;
}

static hw_module_methods_t g_methods = {
    .open = fake_camera_open,
};

static hw_module_t g_fake_module = {
    /* tag                */ 0x48574D4F, /* HWMO */
    /* module_api_version */ 0x0100,
    /* hal_api_version    */ 0x0100,
    /* id                 */ "camera",
    /* name               */ "VCam Virtual Camera",
    /* author             */ "VCam",
    /* methods            */ &g_methods,
    /* dso                */ NULL,
};

/* ── Constructor: resolve real functions ONCE at load time ───────────── */
__attribute__((constructor))
static void vcam_init(void) {
    real_by_class = (hw_get_module_by_class_fn)dlsym(RTLD_NEXT, "hw_get_module_by_class");
    real_get      = (hw_get_module_fn)         dlsym(RTLD_NEXT, "hw_get_module");
    if (!real_by_class || !real_get) {
        void* lh = dlopen("libhardware.so", RTLD_NOW | RTLD_NOLOAD);
        if (!lh) lh = dlopen("libhardware.so", RTLD_NOW | RTLD_GLOBAL);
        if (lh) {
            if (!real_by_class)
                real_by_class = (hw_get_module_by_class_fn)dlsym(lh, "hw_get_module_by_class");
            if (!real_get)
                real_get = (hw_get_module_fn)dlsym(lh, "hw_get_module");
        }
    }
    LOGI("VCamInject loaded: by_class=%p get=%p", real_by_class, real_get);
    read_frame_meta();
}

/* ── Hooks ───────────────────────────────────────────────────────────── */
int hw_get_module_by_class(const char* class_name, const char* inst,
                            const hw_module_t** module) {
    if (class_name && strcmp(class_name, "camera") == 0) {
        LOGI("VCam: intercepted hw_get_module_by_class(camera)");
        *module = &g_fake_module;
        return 0;
    }
    if (real_by_class) return real_by_class(class_name, inst, module);
    return -1;
}

int hw_get_module(const char* id, const hw_module_t** module) {
    if (id && strcmp(id, "camera") == 0) {
        LOGI("VCam: intercepted hw_get_module(camera)");
        *module = &g_fake_module;
        return 0;
    }
    if (real_get) return real_get(id, module);
    return -1;
}
