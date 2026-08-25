/* SPDX-License-Identifier: MIT */

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <android/hardware_buffer.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <errno.h>
#include <jni.h>
#include <poll.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstring>
#include <cstdint>
#include <cstdio>
#include <mutex>
#include <string>
#include <thread>
#include <utility>

#include "ahb_transport_protocol.h"

namespace {

constexpr char kLogTag[] = "uDroid-AHB";
constexpr auto kFramePeriod = std::chrono::microseconds(16667);
static_assert(sizeof(UdroidAhbTransportPacket) == 32);

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, kLogTag, __VA_ARGS__)

bool sendPacket(int socket_fd, const UdroidAhbTransportPacket &packet) {
    ssize_t sent;
    do {
        sent = send(socket_fd, &packet, sizeof(packet), MSG_NOSIGNAL);
    } while (sent < 0 && errno == EINTR);
    return sent == static_cast<ssize_t>(sizeof(packet));
}

bool receivePacket(int socket_fd, uint32_t expected_kind,
                   uint64_t resource_id, uint64_t generation,
                   UdroidAhbTransportPacket *packet) {
    ssize_t received;
    do {
        received = recv(socket_fd, packet, sizeof(*packet), MSG_WAITALL);
    } while (received < 0 && errno == EINTR);
    if (received != static_cast<ssize_t>(sizeof(*packet))) {
        return false;
    }
    return packet->magic == UDROID_AHB_TRANSPORT_MAGIC &&
           packet->version == UDROID_AHB_TRANSPORT_VERSION &&
           packet->kind == expected_kind &&
           packet->reserved == 0 && packet->resource_id == resource_id &&
           packet->generation == generation;
}

bool sendPacketWithFd(int socket_fd, const UdroidAhbTransportPacket &packet, int fd) {
    if (fd < 0) return false;
    iovec io = {const_cast<UdroidAhbTransportPacket *>(&packet), sizeof(packet)};
    char control[CMSG_SPACE(sizeof(int))] = {};
    msghdr message = {};
    message.msg_iov = &io;
    message.msg_iovlen = 1;
    message.msg_control = control;
    message.msg_controllen = sizeof(control);
    cmsghdr *header = CMSG_FIRSTHDR(&message);
    header->cmsg_level = SOL_SOCKET;
    header->cmsg_type = SCM_RIGHTS;
    header->cmsg_len = CMSG_LEN(sizeof(int));
    memcpy(CMSG_DATA(header), &fd, sizeof(fd));
    ssize_t sent;
    do {
        sent = sendmsg(socket_fd, &message, MSG_NOSIGNAL);
    } while (sent < 0 && errno == EINTR);
    return sent == static_cast<ssize_t>(sizeof(packet));
}

bool receivePacketWithFd(int socket_fd, uint32_t expected_kind,
                         uint64_t resource_id, uint64_t generation, int *fd) {
    *fd = -1;
    UdroidAhbTransportPacket packet = {};
    iovec io = {&packet, sizeof(packet)};
    char control[CMSG_SPACE(sizeof(int) * 4)] = {};
    msghdr message = {};
    message.msg_iov = &io;
    message.msg_iovlen = 1;
    message.msg_control = control;
    message.msg_controllen = sizeof(control);
    ssize_t received;
    do {
        received = recvmsg(socket_fd, &message, MSG_CMSG_CLOEXEC);
    } while (received < 0 && errno == EINTR);

    size_t received_fd_count = 0;
    if (received >= 0) {
        for (cmsghdr *header = CMSG_FIRSTHDR(&message); header != nullptr;
             header = CMSG_NXTHDR(&message, header)) {
            if (header->cmsg_level != SOL_SOCKET ||
                header->cmsg_type != SCM_RIGHTS ||
                header->cmsg_len < CMSG_LEN(0)) {
                continue;
            }
            const size_t payload_size = header->cmsg_len - CMSG_LEN(0);
            if (payload_size % sizeof(int) != 0) continue;
            const size_t count = payload_size / sizeof(int);
            const int *received_fds =
                    reinterpret_cast<const int *>(CMSG_DATA(header));
            for (size_t index = 0; index < count; ++index) {
                if (received_fd_count == 0) {
                    *fd = received_fds[index];
                } else {
                    close(received_fds[index]);
                }
                ++received_fd_count;
            }
        }
    }

    if (received != static_cast<ssize_t>(sizeof(packet)) ||
        (message.msg_flags & (MSG_TRUNC | MSG_CTRUNC)) != 0 ||
        received_fd_count != 1 || *fd < 0 ||
        packet.magic != UDROID_AHB_TRANSPORT_MAGIC ||
        packet.version != UDROID_AHB_TRANSPORT_VERSION ||
        packet.kind != expected_kind || packet.reserved != 0 ||
        packet.resource_id != resource_id || packet.generation != generation) {
        close(*fd);
        *fd = -1;
        return false;
    }
    return true;
}

using AHardwareBufferGetId = int (*)(const AHardwareBuffer *, uint64_t *);

bool getHardwareBufferId(const AHardwareBuffer *buffer, uint64_t *id) {
    static const auto get_id = reinterpret_cast<AHardwareBufferGetId>(
            dlsym(RTLD_DEFAULT, "AHardwareBuffer_getId"));
    return get_id != nullptr && get_id(buffer, id) == 0;
}

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
    explicit Presenter(std::string transport_path)
        : transport_path_(std::move(transport_path)), worker_(&Presenter::run, this) {}

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
                "path: AHB socket -> EGLImage -> GPU blit -> Surface\n"
                "surface generation: %u  size: %dx%d\n"
                "GPU: %s\n"
                "peer: uid %lld  %s\n"
                "resource: %llu/%llu  AHB identity: %s\n"
                "frames: %llu  recent fps: %.1f  swap failures: %llu\n"
                "sync: socket acquire/release fences  failures: %llu/%llu\n"
                "status: %s",
                surface_generation_,
                frame_width_,
                frame_height_,
                renderer_.c_str(),
                static_cast<long long>(peer_uid_.load()),
                peer_authenticated_.load() ? "authenticated" : "not authenticated",
                static_cast<unsigned long long>(active_resource_id_.load()),
                static_cast<unsigned long long>(buffer_generation_.load()),
                buffer_identity_.c_str(),
                static_cast<unsigned long long>(frames_.load()),
                static_cast<double>(fps_milli_.load()) / 1000.0,
                static_cast<unsigned long long>(swap_failures_.load()),
                static_cast<unsigned long long>(fence_failures_.load()),
                static_cast<unsigned long long>(transport_failures_.load()),
                status_.c_str());
        return text;
    }

private:
    bool initializeTransport() {
        sockaddr_un address = {};
        if (transport_path_.empty() ||
            transport_path_.size() >= sizeof(address.sun_path)) {
            setStatus("private graphics socket path is invalid");
            return false;
        }

        listener_socket_ = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0);
        transport_sockets_[0] =
                socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0);
        if (listener_socket_ < 0 || transport_sockets_[0] < 0) {
            setStatus("private graphics transport socket creation failed");
            return false;
        }

        address.sun_family = AF_UNIX;
        memcpy(address.sun_path, transport_path_.c_str(), transport_path_.size() + 1);
        unlink(transport_path_.c_str());
        if (bind(listener_socket_, reinterpret_cast<const sockaddr *>(&address),
                 sizeof(address)) != 0 ||
            chmod(transport_path_.c_str(), S_IRUSR | S_IWUSR) != 0 ||
            listen(listener_socket_, 1) != 0) {
            setStatus("private graphics listener setup failed");
            return false;
        }

        if (connect(transport_sockets_[0],
                    reinterpret_cast<const sockaddr *>(&address),
                    sizeof(address)) != 0) {
            setStatus("graphics producer could not connect to private listener");
            return false;
        }
        transport_sockets_[1] = accept4(listener_socket_, nullptr, nullptr, SOCK_CLOEXEC);
        if (transport_sockets_[1] < 0) {
            setStatus("graphics presenter could not accept producer connection");
            return false;
        }

        ucred credentials = {};
        socklen_t credentials_size = sizeof(credentials);
        if (getsockopt(transport_sockets_[1], SOL_SOCKET, SO_PEERCRED,
                       &credentials, &credentials_size) != 0 ||
            credentials_size != sizeof(credentials) || credentials.uid != getuid()) {
            setStatus("graphics producer peer authentication failed");
            return false;
        }
        peer_uid_.store(credentials.uid);
        peer_authenticated_.store(true);
        return true;
    }

    bool initializeEgl() {
        if (!initializeTransport()) return false;
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

    bool registerFrameBufferTransport() {
        const uint64_t resource_id = ++next_resource_id_;
        const uint64_t generation = buffer_generation_.fetch_add(1) + 1;
        active_resource_id_.store(resource_id);
        const UdroidAhbTransportPacket registration = {
            UDROID_AHB_TRANSPORT_MAGIC,
            UDROID_AHB_TRANSPORT_VERSION,
            UDROID_AHB_REGISTER_BUFFER,
            0,
            resource_id,
            generation,
        };
        if (!sendPacket(transport_sockets_[0], registration) ||
            AHardwareBuffer_sendHandleToUnixSocket(frame_buffer_,
                                                   transport_sockets_[0]) != 0) {
            ++transport_failures_;
            setStatus("producer failed to send AHardwareBuffer registration");
            return false;
        }

        UdroidAhbTransportPacket received = {};
        if (!receivePacket(transport_sockets_[1],
                           UDROID_AHB_REGISTER_BUFFER,
                           resource_id, generation, &received) ||
            AHardwareBuffer_recvHandleFromUnixSocket(transport_sockets_[1],
                                                     &present_frame_buffer_) != 0 ||
            present_frame_buffer_ == nullptr) {
            ++transport_failures_;
            setStatus("presenter failed to receive AHardwareBuffer registration");
            return false;
        }

        AHardwareBuffer_Desc producer_description = {};
        AHardwareBuffer_Desc presenter_description = {};
        AHardwareBuffer_describe(frame_buffer_, &producer_description);
        AHardwareBuffer_describe(present_frame_buffer_, &presenter_description);
        if (producer_description.width != presenter_description.width ||
            producer_description.height != presenter_description.height ||
            producer_description.layers != presenter_description.layers ||
            producer_description.format != presenter_description.format ||
            producer_description.usage != presenter_description.usage ||
            producer_description.stride != presenter_description.stride) {
            ++transport_failures_;
            setStatus("AHardwareBuffer registration metadata changed in transport");
            return false;
        }

        uint64_t producer_id = 0;
        uint64_t presenter_id = 0;
        const bool producer_has_id = getHardwareBufferId(frame_buffer_, &producer_id);
        const bool presenter_has_id =
                getHardwareBufferId(present_frame_buffer_, &presenter_id);
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (!producer_has_id || !presenter_has_id) {
                buffer_identity_ = "unavailable (Android < 12)";
            } else if (producer_id == presenter_id) {
                buffer_identity_ = "matched";
            } else {
                buffer_identity_ = "MISMATCH";
            }
        }
        if (producer_has_id && presenter_has_id && producer_id != presenter_id) {
            ++transport_failures_;
            setStatus("AHardwareBuffer identity changed in transport");
            return false;
        }
        return true;
    }

    bool transferFence(uint32_t kind, int sender_socket,
                       int receiver_socket, int fence_fd, int *received_fd) {
        const uint64_t resource_id = active_resource_id_.load();
        const uint64_t generation = buffer_generation_.load();
        const UdroidAhbTransportPacket packet = {
            UDROID_AHB_TRANSPORT_MAGIC,
            UDROID_AHB_TRANSPORT_VERSION,
            kind,
            0,
            resource_id,
            generation,
        };
        const bool sent = sendPacketWithFd(sender_socket, packet, fence_fd);
        close(fence_fd);
        if (!sent || !receivePacketWithFd(receiver_socket, kind,
                                          resource_id, generation,
                                          received_fd)) {
            ++transport_failures_;
            return false;
        }
        return true;
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
        producer_image_ = egl_create_image_(display_, EGL_NO_CONTEXT,
                                           EGL_NATIVE_BUFFER_ANDROID, client_buffer,
                                           image_attributes);
        if (producer_image_ == EGL_NO_IMAGE_KHR) {
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
        gl_egl_image_target_texture_(GL_TEXTURE_2D, producer_image_);

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

        if (!registerFrameBufferTransport()) {
            destroyFrameBuffer();
            return false;
        }

        if (!makePresenterCurrent(window_surface_)) {
            setStatus("presenter EGL context activation failed");
            destroyFrameBuffer();
            return false;
        }
        client_buffer = egl_get_native_client_buffer_(present_frame_buffer_);
        present_image_ = egl_create_image_(display_, EGL_NO_CONTEXT,
                                          EGL_NATIVE_BUFFER_ANDROID, client_buffer,
                                          image_attributes);
        if (present_image_ == EGL_NO_IMAGE_KHR) {
            setStatus("transported AHardwareBuffer EGLImage import failed");
            destroyFrameBuffer();
            return false;
        }
        glGenTextures(1, &present_texture_);
        glBindTexture(GL_TEXTURE_2D, present_texture_);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        gl_egl_image_target_texture_(GL_TEXTURE_2D, present_image_);

        {
            std::lock_guard<std::mutex> lock(mutex_);
            frame_width_ = width;
            frame_height_ = height;
            resize_requested_ = false;
            status_ = "public AHB socket transport is active";
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

        int presenter_acquire_fence = -1;
        if (!transferFence(UDROID_AHB_ACQUIRE_FENCE,
                           transport_sockets_[0], transport_sockets_[1],
                           acquire_fence, &presenter_acquire_fence)) {
            setStatus("AHB transport failed to carry acquire fence");
            return false;
        }

        if (!makePresenterCurrent(window_surface_)) {
            close(presenter_acquire_fence);
            setStatus("presenter EGL context switch failed");
            return false;
        }
        if (!waitNativeFence(presenter_acquire_fence)) {
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

        const int presenter_release_fence = exportNativeFence();
        if (presenter_release_fence < 0) {
            ++fence_failures_;
            setStatus("presenter failed to export release fence");
            return false;
        }
        if (!transferFence(UDROID_AHB_RELEASE_FENCE,
                           transport_sockets_[1], transport_sockets_[0],
                           presenter_release_fence, &release_fence_fd_)) {
            setStatus("AHB transport failed to return release fence");
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
        if (producer_image_ != EGL_NO_IMAGE_KHR && egl_destroy_image_ != nullptr) {
            egl_destroy_image_(display_, producer_image_);
            producer_image_ = EGL_NO_IMAGE_KHR;
        }
        if (present_image_ != EGL_NO_IMAGE_KHR && egl_destroy_image_ != nullptr) {
            egl_destroy_image_(display_, present_image_);
            present_image_ = EGL_NO_IMAGE_KHR;
        }
        if (frame_buffer_ != nullptr) {
            AHardwareBuffer_release(frame_buffer_);
            frame_buffer_ = nullptr;
        }
        if (present_frame_buffer_ != nullptr) {
            AHardwareBuffer_release(present_frame_buffer_);
            present_frame_buffer_ = nullptr;
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
        for (int &socket_fd : transport_sockets_) {
            if (socket_fd >= 0) {
                close(socket_fd);
                socket_fd = -1;
            }
        }
        if (listener_socket_ >= 0) {
            close(listener_socket_);
            listener_socket_ = -1;
        }
        if (!transport_path_.empty()) unlink(transport_path_.c_str());
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

    const std::string transport_path_;
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
    std::string buffer_identity_ = "pending";
    std::atomic<uint64_t> frames_{0};
    std::atomic<uint64_t> fps_milli_{0};
    std::atomic<uint64_t> swap_failures_{0};
    std::atomic<uint64_t> fence_failures_{0};
    std::atomic<uint64_t> transport_failures_{0};
    std::chrono::steady_clock::time_point fps_sample_started_;
    int transport_sockets_[2] = {-1, -1};
    int listener_socket_ = -1;
    std::atomic<int64_t> peer_uid_{-1};
    std::atomic<bool> peer_authenticated_{false};
    uint64_t next_resource_id_ = 0;
    std::atomic<uint64_t> active_resource_id_{0};
    std::atomic<uint64_t> buffer_generation_{0};

    EGLDisplay display_ = EGL_NO_DISPLAY;
    EGLConfig config_ = nullptr;
    EGLContext producer_context_ = EGL_NO_CONTEXT;
    EGLContext presenter_context_ = EGL_NO_CONTEXT;
    EGLSurface producer_pbuffer_ = EGL_NO_SURFACE;
    EGLSurface presenter_pbuffer_ = EGL_NO_SURFACE;
    EGLSurface window_surface_ = EGL_NO_SURFACE;
    AHardwareBuffer *frame_buffer_ = nullptr;
    AHardwareBuffer *present_frame_buffer_ = nullptr;
    EGLImageKHR producer_image_ = EGL_NO_IMAGE_KHR;
    EGLImageKHR present_image_ = EGL_NO_IMAGE_KHR;
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
        JNIEnv *env, jobject, jstring socket_path) {
    if (socket_path == nullptr) return 0;
    const char *path = env->GetStringUTFChars(socket_path, nullptr);
    if (path == nullptr) return 0;
    std::string transport_path(path);
    env->ReleaseStringUTFChars(socket_path, path);
    return static_cast<jlong>(
            reinterpret_cast<intptr_t>(new Presenter(std::move(transport_path))));
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
