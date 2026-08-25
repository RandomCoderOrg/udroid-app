/* SPDX-License-Identifier: MIT */

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <android/hardware_buffer.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <errno.h>
#include <jni.h>
#include <poll.h>
#include <unistd.h>

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstdio>
#include <mutex>
#include <string>
#include <thread>

namespace {

constexpr char kLogTag[] = "uDroid-AHB";
constexpr auto kFramePeriod = std::chrono::microseconds(16667);

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, kLogTag, __VA_ARGS__)

const char kVertexShader[] = R"(
attribute vec2 aPosition;
varying vec2 vUv;
void main() {
    vUv = aPosition * 0.5 + 0.5;
    gl_Position = vec4(aPosition, 0.0, 1.0);
}
)";

const char kPatternShader[] = R"(
precision mediump float;
varying vec2 vUv;
uniform float uTime;
void main() {
    float checker = mod(floor(vUv.x * 16.0) + floor(vUv.y * 10.0), 2.0);
    vec3 dark = vec3(0.025, 0.055, 0.075);
    vec3 teal = vec3(0.05, 0.72, 0.55);
    vec3 color = mix(dark, teal, checker * 0.45);
    float cursor = fract(uTime * 0.18);
    float bar = 1.0 - smoothstep(0.012, 0.025, abs(vUv.x - cursor));
    float pulse = 0.72 + 0.28 * sin(uTime * 2.2);
    color = mix(color, vec3(1.0, 0.35, 0.08) * pulse, bar);
    gl_FragColor = vec4(color, 1.0);
}
)";

const char kPresentShader[] = R"(
precision mediump float;
varying vec2 vUv;
uniform sampler2D uFrame;
void main() {
    gl_FragColor = texture2D(uFrame, vUv);
}
)";

const GLfloat kFullscreenQuad[] = {
    -1.0f, -1.0f,
     1.0f, -1.0f,
    -1.0f,  1.0f,
     1.0f,  1.0f,
};

class Presenter {
public:
    Presenter() : worker_(&Presenter::run, this) {}

    ~Presenter() {
        {
            std::lock_guard<std::mutex> lock(mutex_);
            stopping_ = true;
            window_changed_ = true;
        }
        condition_.notify_one();
        if (worker_.joinable()) {
            worker_.join();
        }
        std::lock_guard<std::mutex> lock(mutex_);
        if (pending_window_ != nullptr) {
            ANativeWindow_release(pending_window_);
            pending_window_ = nullptr;
        }
    }

    Presenter(const Presenter &) = delete;
    Presenter &operator=(const Presenter &) = delete;

    void setWindow(ANativeWindow *window) {
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (pending_window_ != nullptr) {
                ANativeWindow_release(pending_window_);
            }
            pending_window_ = window;
            window_changed_ = true;
            resize_requested_ = true;
            ++surface_generation_;
        }
        condition_.notify_one();
    }

    void requestResize() {
        {
            std::lock_guard<std::mutex> lock(mutex_);
            resize_requested_ = true;
        }
        condition_.notify_one();
    }

    std::string stats() const {
        std::lock_guard<std::mutex> lock(mutex_);
        char text[512];
        std::snprintf(
                text,
                sizeof(text),
                "uDroid gfxstream Surface probe\n"
                "path: AHB -> EGLImage -> GPU blit -> Surface\n"
                "surface generation: %u  size: %dx%d\n"
                "GPU: %s\n"
                "frames: %llu  recent fps: %.1f  swap failures: %llu\n"
                "sync: native acquire/release fences  failures: %llu\n"
                "status: %s",
                surface_generation_,
                frame_width_,
                frame_height_,
                renderer_.c_str(),
                static_cast<unsigned long long>(frames_.load()),
                static_cast<double>(fps_milli_.load()) / 1000.0,
                static_cast<unsigned long long>(swap_failures_.load()),
                static_cast<unsigned long long>(fence_failures_.load()),
                status_.c_str());
        return text;
    }

private:
    bool initializeEgl() {
        display_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if (display_ == EGL_NO_DISPLAY || !eglInitialize(display_, nullptr, nullptr)) {
            setStatus("EGL display initialization failed");
            return false;
        }

        const EGLint config_attributes[] = {
            EGL_SURFACE_TYPE, EGL_WINDOW_BIT | EGL_PBUFFER_BIT,
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
            EGL_RED_SIZE, 8,
            EGL_GREEN_SIZE, 8,
            EGL_BLUE_SIZE, 8,
            EGL_ALPHA_SIZE, 8,
            EGL_NONE,
        };
        EGLint config_count = 0;
        if (!eglChooseConfig(display_, config_attributes, &config_, 1, &config_count) ||
            config_count != 1) {
            setStatus("no compatible EGL window configuration");
            return false;
        }

        const EGLint context_attributes[] = {
            EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL_NONE,
        };
        producer_context_ =
                eglCreateContext(display_, config_, EGL_NO_CONTEXT, context_attributes);
        presenter_context_ =
                eglCreateContext(display_, config_, EGL_NO_CONTEXT, context_attributes);
        const EGLint pbuffer_attributes[] = {
            EGL_WIDTH, 1,
            EGL_HEIGHT, 1,
            EGL_NONE,
        };
        producer_pbuffer_ = eglCreatePbufferSurface(display_, config_, pbuffer_attributes);
        presenter_pbuffer_ = eglCreatePbufferSurface(display_, config_, pbuffer_attributes);
        if (producer_context_ == EGL_NO_CONTEXT ||
            presenter_context_ == EGL_NO_CONTEXT ||
            producer_pbuffer_ == EGL_NO_SURFACE ||
            presenter_pbuffer_ == EGL_NO_SURFACE || !makeProducerCurrent()) {
            setStatus("independent EGL context initialization failed");
            return false;
        }

        const GLubyte *renderer = glGetString(GL_RENDERER);
        {
            std::lock_guard<std::mutex> lock(mutex_);
            renderer_ = renderer == nullptr
                    ? "unknown"
                    : reinterpret_cast<const char *>(renderer);
        }

        egl_create_image_ = reinterpret_cast<PFNEGLCREATEIMAGEKHRPROC>(
                eglGetProcAddress("eglCreateImageKHR"));
        egl_destroy_image_ = reinterpret_cast<PFNEGLDESTROYIMAGEKHRPROC>(
                eglGetProcAddress("eglDestroyImageKHR"));
        egl_get_native_client_buffer_ =
                reinterpret_cast<PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC>(
                        eglGetProcAddress("eglGetNativeClientBufferANDROID"));
        gl_egl_image_target_texture_ =
                reinterpret_cast<PFNGLEGLIMAGETARGETTEXTURE2DOESPROC>(
                        eglGetProcAddress("glEGLImageTargetTexture2DOES"));
        egl_create_sync_ = reinterpret_cast<PFNEGLCREATESYNCKHRPROC>(
                eglGetProcAddress("eglCreateSyncKHR"));
        egl_destroy_sync_ = reinterpret_cast<PFNEGLDESTROYSYNCKHRPROC>(
                eglGetProcAddress("eglDestroySyncKHR"));
        egl_dup_native_fence_fd_ =
                reinterpret_cast<PFNEGLDUPNATIVEFENCEFDANDROIDPROC>(
                        eglGetProcAddress("eglDupNativeFenceFDANDROID"));
        egl_wait_sync_ = reinterpret_cast<PFNEGLWAITSYNCKHRPROC>(
                eglGetProcAddress("eglWaitSyncKHR"));
        if (egl_create_image_ == nullptr || egl_destroy_image_ == nullptr ||
            egl_get_native_client_buffer_ == nullptr ||
            gl_egl_image_target_texture_ == nullptr || egl_create_sync_ == nullptr ||
            egl_destroy_sync_ == nullptr || egl_dup_native_fence_fd_ == nullptr ||
            egl_wait_sync_ == nullptr) {
            setStatus("required AHB or native-fence EGL extensions are missing");
            return false;
        }

        pattern_program_ = createProgram(kVertexShader, kPatternShader);
        if (!makePresenterCurrent(presenter_pbuffer_)) {
            setStatus("presenter EGL context activation failed");
            return false;
        }
        present_program_ = createProgram(kVertexShader, kPresentShader);
        if (pattern_program_ == 0 || present_program_ == 0) {
            setStatus("probe shader compilation failed");
            return false;
        }
        setStatus("waiting for Android Surface");
        return true;
    }

    GLuint compileShader(GLenum type, const char *source) {
        GLuint shader = glCreateShader(type);
        glShaderSource(shader, 1, &source, nullptr);
        glCompileShader(shader);
        GLint compiled = GL_FALSE;
        glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
        if (compiled != GL_TRUE) {
            char log[512] = {};
            glGetShaderInfoLog(shader, sizeof(log), nullptr, log);
            LOGE("shader compilation failed: %s", log);
            glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    GLuint createProgram(const char *vertex_source, const char *fragment_source) {
        GLuint vertex = compileShader(GL_VERTEX_SHADER, vertex_source);
        GLuint fragment = compileShader(GL_FRAGMENT_SHADER, fragment_source);
        if (vertex == 0 || fragment == 0) {
            if (vertex != 0) glDeleteShader(vertex);
            if (fragment != 0) glDeleteShader(fragment);
            return 0;
        }
        GLuint program = glCreateProgram();
        glAttachShader(program, vertex);
        glAttachShader(program, fragment);
        glBindAttribLocation(program, 0, "aPosition");
        glLinkProgram(program);
        glDeleteShader(vertex);
        glDeleteShader(fragment);
        GLint linked = GL_FALSE;
        glGetProgramiv(program, GL_LINK_STATUS, &linked);
        if (linked != GL_TRUE) {
            char log[512] = {};
            glGetProgramInfoLog(program, sizeof(log), nullptr, log);
            LOGE("program link failed: %s", log);
            glDeleteProgram(program);
            return 0;
        }
        return program;
    }

    bool makeProducerCurrent() const {
        return eglMakeCurrent(display_, producer_pbuffer_, producer_pbuffer_,
                              producer_context_) == EGL_TRUE;
    }

    bool makePresenterCurrent(EGLSurface surface) const {
        return eglMakeCurrent(display_, surface, surface, presenter_context_) == EGL_TRUE;
    }

    int exportNativeFence() {
        const EGLint attributes[] = {
            EGL_SYNC_NATIVE_FENCE_FD_ANDROID,
            EGL_NO_NATIVE_FENCE_FD_ANDROID,
            EGL_NONE,
        };
        EGLSyncKHR sync = egl_create_sync_(display_, EGL_SYNC_NATIVE_FENCE_ANDROID,
                                           attributes);
        if (sync == EGL_NO_SYNC_KHR) return -1;
        glFlush();
        const int fence_fd = egl_dup_native_fence_fd_(display_, sync);
        egl_destroy_sync_(display_, sync);
        return fence_fd;
    }

    bool waitNativeFence(int fence_fd) {
        if (fence_fd < 0) return false;
        const EGLint attributes[] = {
            EGL_SYNC_NATIVE_FENCE_FD_ANDROID,
            fence_fd,
            EGL_NONE,
        };
        EGLSyncKHR sync = egl_create_sync_(display_, EGL_SYNC_NATIVE_FENCE_ANDROID,
                                           attributes);
        if (sync == EGL_NO_SYNC_KHR) {
            close(fence_fd);
            return false;
        }
        const bool waited = egl_wait_sync_(display_, sync, 0) == EGL_TRUE;
        egl_destroy_sync_(display_, sync);
        return waited;
    }

    bool waitNativeFenceOnCpu(int fence_fd) {
        if (fence_fd < 0) return false;
        pollfd descriptor = {fence_fd, POLLIN, 0};
        int result;
        do {
            result = poll(&descriptor, 1, 1000);
        } while (result < 0 && errno == EINTR);
        close(fence_fd);
        return result > 0 && (descriptor.revents & (POLLIN | POLLHUP)) != 0;
    }

    bool createWindowSurface(ANativeWindow *window) {
        window_surface_ = eglCreateWindowSurface(display_, config_, window, nullptr);
        if (window_surface_ == EGL_NO_SURFACE ||
            !makePresenterCurrent(window_surface_)) {
            setStatus("Android window EGL surface creation failed");
            return false;
        }
        eglSwapInterval(display_, 1);
        return recreateFrameBuffer(window);
    }

    bool recreateFrameBuffer(ANativeWindow *window) {
        destroyFrameBuffer();
        int width = ANativeWindow_getWidth(window);
        int height = ANativeWindow_getHeight(window);
        if (width <= 0 || height <= 0) {
            setStatus("Android Surface has invalid geometry");
            return false;
        }
        if (!makeProducerCurrent()) {
            setStatus("producer EGL context activation failed");
            return false;
        }

        AHardwareBuffer_Desc description = {};
        description.width = static_cast<uint32_t>(width);
        description.height = static_cast<uint32_t>(height);
        description.layers = 1;
        description.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
        description.usage = AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT |
                            AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;
        if (AHardwareBuffer_allocate(&description, &frame_buffer_) != 0 ||
            frame_buffer_ == nullptr) {
            setStatus("AHardwareBuffer allocation failed");
            return false;
        }

        EGLClientBuffer client_buffer = egl_get_native_client_buffer_(frame_buffer_);
        const EGLint image_attributes[] = {
            EGL_IMAGE_PRESERVED_KHR, EGL_TRUE,
            EGL_NONE,
        };
        frame_image_ = egl_create_image_(display_, EGL_NO_CONTEXT,
                                        EGL_NATIVE_BUFFER_ANDROID, client_buffer,
                                        image_attributes);
        if (frame_image_ == EGL_NO_IMAGE_KHR) {
            setStatus("AHardwareBuffer EGLImage import failed");
            destroyFrameBuffer();
            return false;
        }

        glGenTextures(1, &producer_texture_);
        glBindTexture(GL_TEXTURE_2D, producer_texture_);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        gl_egl_image_target_texture_(GL_TEXTURE_2D, frame_image_);

        glGenFramebuffers(1, &producer_fbo_);
        glBindFramebuffer(GL_FRAMEBUFFER, producer_fbo_);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                               producer_texture_, 0);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            setStatus("AHardwareBuffer framebuffer is incomplete");
            destroyFrameBuffer();
            return false;
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);

        if (!makePresenterCurrent(window_surface_)) {
            setStatus("presenter EGL context activation failed");
            destroyFrameBuffer();
            return false;
        }
        glGenTextures(1, &present_texture_);
        glBindTexture(GL_TEXTURE_2D, present_texture_);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        gl_egl_image_target_texture_(GL_TEXTURE_2D, frame_image_);

        {
            std::lock_guard<std::mutex> lock(mutex_);
            frame_width_ = width;
            frame_height_ = height;
            resize_requested_ = false;
            status_ = "cross-context AHB handoff is active";
        }
        return true;
    }

    bool drawFrame(float time_seconds) {
        if (!makeProducerCurrent()) {
            setStatus("producer EGL context switch failed");
            return false;
        }
        if (release_fence_fd_ >= 0) {
            const int release_fence = release_fence_fd_;
            release_fence_fd_ = -1;
            if (!waitNativeFence(release_fence)) {
                ++fence_failures_;
                setStatus("producer failed to wait for release fence");
                return false;
            }
        }

        glBindFramebuffer(GL_FRAMEBUFFER, producer_fbo_);
        glViewport(0, 0, frame_width_, frame_height_);
        glUseProgram(pattern_program_);
        glUniform1f(glGetUniformLocation(pattern_program_, "uTime"), time_seconds);
        drawQuad();

        const int acquire_fence = exportNativeFence();
        if (acquire_fence < 0) {
            ++fence_failures_;
            setStatus("producer failed to export acquire fence");
            return false;
        }

        if (!makePresenterCurrent(window_surface_)) {
            close(acquire_fence);
            setStatus("presenter EGL context switch failed");
            return false;
        }
        if (!waitNativeFence(acquire_fence)) {
            ++fence_failures_;
            setStatus("presenter failed to wait for acquire fence");
            return false;
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, frame_width_, frame_height_);
        glUseProgram(present_program_);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, present_texture_);
        glUniform1i(glGetUniformLocation(present_program_, "uFrame"), 0);
        drawQuad();

        release_fence_fd_ = exportNativeFence();
        if (release_fence_fd_ < 0) {
            ++fence_failures_;
            setStatus("presenter failed to export release fence");
            return false;
        }

        if (eglSwapBuffers(display_, window_surface_)) {
            const uint64_t frame = ++frames_;
            if (frame % 60 == 0) {
                const auto now = std::chrono::steady_clock::now();
                const auto sample_us =
                        std::chrono::duration_cast<std::chrono::microseconds>(
                                now - fps_sample_started_)
                                .count();
                if (sample_us > 0) {
                    fps_milli_.store(static_cast<uint64_t>(60000000000LL / sample_us));
                }
                fps_sample_started_ = now;
            }
        } else {
            ++swap_failures_;
            setStatus("eglSwapBuffers failed; awaiting Surface replacement");
            return false;
        }
        return true;
    }

    static void drawQuad() {
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, kFullscreenQuad);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glDisableVertexAttribArray(0);
    }

    void destroyFrameBuffer() {
        if (release_fence_fd_ >= 0) {
            const int release_fence = release_fence_fd_;
            release_fence_fd_ = -1;
            if (!waitNativeFenceOnCpu(release_fence)) {
                ++fence_failures_;
                LOGE("release fence did not signal before AHardwareBuffer teardown");
            }
        }
        if (producer_context_ != EGL_NO_CONTEXT && makeProducerCurrent()) {
            if (producer_fbo_ != 0) {
                glDeleteFramebuffers(1, &producer_fbo_);
                producer_fbo_ = 0;
            }
            if (producer_texture_ != 0) {
                glDeleteTextures(1, &producer_texture_);
                producer_texture_ = 0;
            }
        }
        if (presenter_context_ != EGL_NO_CONTEXT &&
            makePresenterCurrent(presenter_pbuffer_)) {
            if (present_texture_ != 0) {
                glDeleteTextures(1, &present_texture_);
                present_texture_ = 0;
            }
        }
        if (frame_image_ != EGL_NO_IMAGE_KHR && egl_destroy_image_ != nullptr) {
            egl_destroy_image_(display_, frame_image_);
            frame_image_ = EGL_NO_IMAGE_KHR;
        }
        if (frame_buffer_ != nullptr) {
            AHardwareBuffer_release(frame_buffer_);
            frame_buffer_ = nullptr;
        }
    }

    void destroyWindowSurface() {
        if (display_ == EGL_NO_DISPLAY) return;
        destroyFrameBuffer();
        makePresenterCurrent(presenter_pbuffer_);
        if (window_surface_ != EGL_NO_SURFACE) {
            eglDestroySurface(display_, window_surface_);
            window_surface_ = EGL_NO_SURFACE;
        }
    }

    void destroyEgl() {
        destroyWindowSurface();
        if (makeProducerCurrent() && pattern_program_ != 0) {
            glDeleteProgram(pattern_program_);
        }
        if (makePresenterCurrent(presenter_pbuffer_) && present_program_ != 0) {
            glDeleteProgram(present_program_);
        }
        if (display_ != EGL_NO_DISPLAY) {
            eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            if (producer_pbuffer_ != EGL_NO_SURFACE) {
                eglDestroySurface(display_, producer_pbuffer_);
            }
            if (presenter_pbuffer_ != EGL_NO_SURFACE) {
                eglDestroySurface(display_, presenter_pbuffer_);
            }
            if (producer_context_ != EGL_NO_CONTEXT) {
                eglDestroyContext(display_, producer_context_);
            }
            if (presenter_context_ != EGL_NO_CONTEXT) {
                eglDestroyContext(display_, presenter_context_);
            }
            eglTerminate(display_);
        }
    }

    void run() {
        if (!initializeEgl()) {
            destroyEgl();
            return;
        }
        ANativeWindow *active_window = nullptr;
        const auto started = std::chrono::steady_clock::now();
        fps_sample_started_ = started;
        auto next_frame = started;

        while (true) {
            ANativeWindow *replacement = nullptr;
            bool replace_window = false;
            bool resize = false;
            {
                std::unique_lock<std::mutex> lock(mutex_);
                if (active_window == nullptr && !window_changed_ && !stopping_) {
                    condition_.wait(lock, [this] { return stopping_ || window_changed_; });
                }
                if (stopping_) break;
                if (window_changed_) {
                    replacement = pending_window_;
                    pending_window_ = nullptr;
                    window_changed_ = false;
                    replace_window = true;
                }
                resize = resize_requested_;
            }

            if (replace_window) {
                destroyWindowSurface();
                if (active_window != nullptr) ANativeWindow_release(active_window);
                active_window = replacement;
                if (active_window != nullptr && !createWindowSurface(active_window)) {
                    destroyWindowSurface();
                }
                next_frame = std::chrono::steady_clock::now();
            } else if (resize && active_window != nullptr &&
                       window_surface_ != EGL_NO_SURFACE) {
                recreateFrameBuffer(active_window);
            }

            if (active_window == nullptr || window_surface_ == EGL_NO_SURFACE ||
                frame_buffer_ == nullptr) {
                // Surface creation can fail transiently while Android is replacing
                // the window. Keep the worker bounded until a lifecycle event retries it.
                std::this_thread::sleep_for(kFramePeriod);
                continue;
            }

            const auto now = std::chrono::steady_clock::now();
            const float seconds =
                    std::chrono::duration<float>(now - started).count();
            if (!drawFrame(seconds)) {
                // A persistent EGL or fence error must not turn into a CPU-burning
                // retry loop while Android is replacing or resizing the Surface.
                std::this_thread::sleep_for(kFramePeriod);
                next_frame = std::chrono::steady_clock::now();
                continue;
            }
            next_frame += kFramePeriod;
            const auto after_swap = std::chrono::steady_clock::now();
            if (next_frame > after_swap) {
                std::this_thread::sleep_until(next_frame);
            } else {
                next_frame = after_swap;
            }
        }

        destroyEgl();
        if (active_window != nullptr) ANativeWindow_release(active_window);
    }

    void setStatus(const char *status) {
        std::lock_guard<std::mutex> lock(mutex_);
        status_ = status;
    }

    mutable std::mutex mutex_;
    std::condition_variable condition_;
    std::thread worker_;
    bool stopping_ = false;
    bool window_changed_ = false;
    bool resize_requested_ = false;
    ANativeWindow *pending_window_ = nullptr;
    uint32_t surface_generation_ = 0;
    int frame_width_ = 0;
    int frame_height_ = 0;
    std::string status_ = "starting EGL presenter";
    std::string renderer_ = "initializing";
    std::atomic<uint64_t> frames_{0};
    std::atomic<uint64_t> fps_milli_{0};
    std::atomic<uint64_t> swap_failures_{0};
    std::atomic<uint64_t> fence_failures_{0};
    std::chrono::steady_clock::time_point fps_sample_started_;

    EGLDisplay display_ = EGL_NO_DISPLAY;
    EGLConfig config_ = nullptr;
    EGLContext producer_context_ = EGL_NO_CONTEXT;
    EGLContext presenter_context_ = EGL_NO_CONTEXT;
    EGLSurface producer_pbuffer_ = EGL_NO_SURFACE;
    EGLSurface presenter_pbuffer_ = EGL_NO_SURFACE;
    EGLSurface window_surface_ = EGL_NO_SURFACE;
    AHardwareBuffer *frame_buffer_ = nullptr;
    EGLImageKHR frame_image_ = EGL_NO_IMAGE_KHR;
    GLuint producer_texture_ = 0;
    GLuint producer_fbo_ = 0;
    GLuint present_texture_ = 0;
    GLuint pattern_program_ = 0;
    GLuint present_program_ = 0;
    int release_fence_fd_ = -1;

    PFNEGLCREATEIMAGEKHRPROC egl_create_image_ = nullptr;
    PFNEGLDESTROYIMAGEKHRPROC egl_destroy_image_ = nullptr;
    PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC egl_get_native_client_buffer_ = nullptr;
    PFNGLEGLIMAGETARGETTEXTURE2DOESPROC gl_egl_image_target_texture_ = nullptr;
    PFNEGLCREATESYNCKHRPROC egl_create_sync_ = nullptr;
    PFNEGLDESTROYSYNCKHRPROC egl_destroy_sync_ = nullptr;
    PFNEGLDUPNATIVEFENCEFDANDROIDPROC egl_dup_native_fence_fd_ = nullptr;
    PFNEGLWAITSYNCKHRPROC egl_wait_sync_ = nullptr;
};

Presenter *fromHandle(jlong handle) {
    return reinterpret_cast<Presenter *>(static_cast<intptr_t>(handle));
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_org_randomcoder_udroid_gfxstream_AhbSurfacePresenterView_nativeCreate(
        JNIEnv *, jobject) {
    return static_cast<jlong>(reinterpret_cast<intptr_t>(new Presenter()));
}

extern "C" JNIEXPORT void JNICALL
Java_org_randomcoder_udroid_gfxstream_AhbSurfacePresenterView_nativeSetSurface(
        JNIEnv *env, jobject, jlong handle, jobject surface) {
    Presenter *presenter = fromHandle(handle);
    if (presenter == nullptr) return;
    ANativeWindow *window =
            surface == nullptr ? nullptr : ANativeWindow_fromSurface(env, surface);
    presenter->setWindow(window);
}

extern "C" JNIEXPORT void JNICALL
Java_org_randomcoder_udroid_gfxstream_AhbSurfacePresenterView_nativeSurfaceResized(
        JNIEnv *, jobject, jlong handle) {
    Presenter *presenter = fromHandle(handle);
    if (presenter != nullptr) presenter->requestResize();
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_randomcoder_udroid_gfxstream_AhbSurfacePresenterView_nativeGetStats(
        JNIEnv *env, jobject, jlong handle) {
    Presenter *presenter = fromHandle(handle);
    const std::string stats = presenter == nullptr ? "presenter unavailable" : presenter->stats();
    return env->NewStringUTF(stats.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_org_randomcoder_udroid_gfxstream_AhbSurfacePresenterView_nativeDestroy(
        JNIEnv *, jobject, jlong handle) {
    delete fromHandle(handle);
}
