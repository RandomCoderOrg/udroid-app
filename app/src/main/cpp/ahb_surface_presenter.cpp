/* SPDX-License-Identifier: MIT */

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <android/api-level.h>
#include <android/hardware_buffer.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/surface_control.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <poll.h>
#include <sys/eventfd.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#include <array>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdlib>
#include <deque>
#include <cstring>
#include <cstdint>
#include <cstdio>
#include <map>
#include <memory>
#include <mutex>
#include <new>
#include <string>
#include <thread>
#include <tuple>
#include <utility>
#include <vector>

#include "ahb_transport_protocol.h"

namespace {

constexpr char kLogTag[] = "uDroid-AHB";
constexpr char kWinsysTraceTag[] = "uDroid-Winsys";
constexpr size_t kMaxExternalResources = 6;
constexpr size_t kMaxDirectTransactions = 6;
constexpr size_t kMaxDirectFrameTransactions = 6;
constexpr size_t kMaxDormantDrainBatches = 4;
constexpr int kDirectRecoveryPollMillis = 1000;
static_assert(sizeof(UdroidAhbTransportPacket) == 40);

enum class ProducerMode {
    kInternalProbe,
    kExternalSupervisor,
};

enum class ExternalResourcePhase {
    kAvailable,
    kReleasePending,
};

struct ExternalResource {
    uint64_t generation = 0;
    AHardwareBuffer *buffer = nullptr;
    AHardwareBuffer_Desc description = {};
    EGLImageKHR image = EGL_NO_IMAGE_KHR;
    GLuint texture = 0;
    ExternalResourcePhase phase = ExternalResourcePhase::kAvailable;
    uint64_t last_frame = 0;
    bool retire_pending = false;
};

struct TimingMetric {
    uint64_t sum_us = 0;
    uint64_t max_us = 0;
    uint64_t samples = 0;

    void add(uint64_t value_us) {
        sum_us += value_us;
        if (value_us > max_us) max_us = value_us;
        ++samples;
    }

    uint64_t average() const {
        return samples == 0 ? 0 : sum_us / samples;
    }
};

struct ExternalFrameTimingWindow {
    uint64_t frames = 0;
    TimingMetric service_gap;
    TimingMetric make_current;
    TimingMetric acquire_wait;
    TimingMetric draw;
    TimingMetric fence_export;
    TimingMetric socket_send;
    TimingMetric swap;
    TimingMetric total;
    TimingMetric inter_swap;
    std::array<uint64_t, 6> interval_buckets{};
    std::chrono::steady_clock::time_point window_started;
    std::chrono::steady_clock::time_point last_swap_completed;
    uint64_t thread_cpu_started_us = 0;
};

struct ExternalFrameTimingSample {
    uint64_t make_current_us = 0;
    uint64_t acquire_wait_us = 0;
    uint64_t draw_us = 0;
    uint64_t fence_export_us = 0;
    uint64_t socket_send_us = 0;
    uint64_t swap_us = 0;
    uint64_t total_us = 0;
    uint64_t thread_cpu_started_us = 0;
    std::chrono::steady_clock::time_point service_started;
    std::chrono::steady_clock::time_point swap_completed;
};

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, kLogTag, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, kLogTag, __VA_ARGS__)
#define TRACE_WINSYS(...) \
    __android_log_print(ANDROID_LOG_INFO, kWinsysTraceTag, "UDROID_WINSYS " __VA_ARGS__)

struct SurfaceControlApi {
    using OnComplete = void (*)(void *, ASurfaceTransactionStats *);
    using CreateFromWindow = ASurfaceControl *(*)(ANativeWindow *, const char *);
    using Release = void (*)(ASurfaceControl *);
    using TransactionCreate = ASurfaceTransaction *(*)();
    using TransactionDelete = void (*)(ASurfaceTransaction *);
    using TransactionApply = void (*)(ASurfaceTransaction *);
    using TransactionSetBuffer = void (*)(ASurfaceTransaction *, ASurfaceControl *,
                                          AHardwareBuffer *, int);
    using TransactionSetVisibility = void (*)(
            ASurfaceTransaction *, ASurfaceControl *,
            enum ASurfaceTransactionVisibility);
    using TransactionSetGeometry = void (*)(ASurfaceTransaction *,
                                            ASurfaceControl *, const ARect &,
                                            const ARect &, int32_t);
    using TransactionSetOnComplete = void (*)(ASurfaceTransaction *, void *,
                                              OnComplete);
    using TransactionReparent = void (*)(ASurfaceTransaction *, ASurfaceControl *,
                                         ASurfaceControl *);
    using TransactionSetEnableBackPressure = void (*)(
            ASurfaceTransaction *, ASurfaceControl *, bool);
    using TransactionSetFrameRate = void (*)(ASurfaceTransaction *, ASurfaceControl *,
                                             float, int8_t);
    using StatsGetPreviousReleaseFence = int (*)(ASurfaceTransactionStats *,
                                                 ASurfaceControl *);
    using StatsGetLatchTime = int64_t (*)(ASurfaceTransactionStats *);
    using StatsGetPresentFence = int (*)(ASurfaceTransactionStats *);

    bool load() {
        api_level = android_get_device_api_level();
        if (api_level < 29) return false;
        library = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
        if (library == nullptr) return false;
        create_from_window = loadSymbol<CreateFromWindow>(
                "ASurfaceControl_createFromWindow");
        release = loadSymbol<Release>("ASurfaceControl_release");
        transaction_create = loadSymbol<TransactionCreate>(
                "ASurfaceTransaction_create");
        transaction_delete = loadSymbol<TransactionDelete>(
                "ASurfaceTransaction_delete");
        transaction_apply = loadSymbol<TransactionApply>(
                "ASurfaceTransaction_apply");
        transaction_set_buffer = loadSymbol<TransactionSetBuffer>(
                "ASurfaceTransaction_setBuffer");
        transaction_set_visibility = loadSymbol<TransactionSetVisibility>(
                "ASurfaceTransaction_setVisibility");
        transaction_set_geometry = loadSymbol<TransactionSetGeometry>(
                "ASurfaceTransaction_setGeometry");
        transaction_set_on_complete = loadSymbol<TransactionSetOnComplete>(
                "ASurfaceTransaction_setOnComplete");
        transaction_reparent = loadSymbol<TransactionReparent>(
                "ASurfaceTransaction_reparent");
        if (api_level >= 31) {
            transaction_set_enable_back_pressure =
                    loadSymbol<TransactionSetEnableBackPressure>(
                            "ASurfaceTransaction_setEnableBackPressure");
            transaction_set_frame_rate = loadSymbol<TransactionSetFrameRate>(
                    "ASurfaceTransaction_setFrameRate");
        }
        stats_get_previous_release_fence =
                loadSymbol<StatsGetPreviousReleaseFence>(
                        "ASurfaceTransactionStats_getPreviousReleaseFenceFd");
        stats_get_latch_time = loadSymbol<StatsGetLatchTime>(
                "ASurfaceTransactionStats_getLatchTime");
        stats_get_present_fence = loadSymbol<StatsGetPresentFence>(
                "ASurfaceTransactionStats_getPresentFenceFd");
        if (create_from_window != nullptr && release != nullptr &&
            transaction_create != nullptr && transaction_delete != nullptr &&
            transaction_apply != nullptr && transaction_set_buffer != nullptr &&
            transaction_set_visibility != nullptr &&
            transaction_set_geometry != nullptr &&
            transaction_set_on_complete != nullptr &&
            transaction_reparent != nullptr &&
            stats_get_previous_release_fence != nullptr &&
            stats_get_latch_time != nullptr &&
            stats_get_present_fence != nullptr) {
            return true;
        }
        reset();
        return false;
    }

    void reset() {
        create_from_window = nullptr;
        release = nullptr;
        transaction_create = nullptr;
        transaction_delete = nullptr;
        transaction_apply = nullptr;
        transaction_set_buffer = nullptr;
        transaction_set_visibility = nullptr;
        transaction_set_geometry = nullptr;
        transaction_set_on_complete = nullptr;
        transaction_reparent = nullptr;
        transaction_set_enable_back_pressure = nullptr;
        transaction_set_frame_rate = nullptr;
        stats_get_previous_release_fence = nullptr;
        stats_get_latch_time = nullptr;
        stats_get_present_fence = nullptr;
        api_level = 0;
        if (library != nullptr) {
            dlclose(library);
            library = nullptr;
        }
    }

    ~SurfaceControlApi() { reset(); }

    SurfaceControlApi() = default;
    SurfaceControlApi(const SurfaceControlApi &) = delete;
    SurfaceControlApi &operator=(const SurfaceControlApi &) = delete;

    void *library = nullptr;
    CreateFromWindow create_from_window = nullptr;
    Release release = nullptr;
    TransactionCreate transaction_create = nullptr;
    TransactionDelete transaction_delete = nullptr;
    TransactionApply transaction_apply = nullptr;
    TransactionSetBuffer transaction_set_buffer = nullptr;
    TransactionSetVisibility transaction_set_visibility = nullptr;
    TransactionSetGeometry transaction_set_geometry = nullptr;
    TransactionSetOnComplete transaction_set_on_complete = nullptr;
    TransactionReparent transaction_reparent = nullptr;
    TransactionSetEnableBackPressure transaction_set_enable_back_pressure = nullptr;
    TransactionSetFrameRate transaction_set_frame_rate = nullptr;
    StatsGetPreviousReleaseFence stats_get_previous_release_fence = nullptr;
    StatsGetLatchTime stats_get_latch_time = nullptr;
    StatsGetPresentFence stats_get_present_fence = nullptr;
    int api_level = 0;

private:
    template <typename Function>
    Function loadSymbol(const char *name) const {
        return reinterpret_cast<Function>(dlsym(library, name));
    }
};

struct DirectFrameKey {
    uint64_t connection_epoch = 0;
    uint64_t resource_id = 0;
    uint64_t generation = 0;
    uint64_t frame_id = 0;

    bool operator<(const DirectFrameKey &other) const {
        return std::tie(connection_epoch, resource_id, generation, frame_id) <
               std::tie(other.connection_epoch, other.resource_id,
                        other.generation, other.frame_id);
    }

    bool operator==(const DirectFrameKey &other) const {
        return connection_epoch == other.connection_epoch &&
               resource_id == other.resource_id &&
               generation == other.generation && frame_id == other.frame_id;
    }
};

struct DirectControlHandle {
    ASurfaceControl *control = nullptr;
    SurfaceControlApi::Release release = nullptr;

    ~DirectControlHandle() {
        if (control != nullptr && release != nullptr) release(control);
    }
};

struct DirectCompletionRecord {
    uint64_t transaction_id = 0;
    DirectFrameKey current;
    DirectFrameKey previous;
    bool has_current = false;
    bool has_previous = false;
    int previous_release_fence = -1;
    int current_present_fence = -1;
    int64_t current_latch_time_ns = 0;
    std::shared_ptr<DirectControlHandle> control;
};

struct DirectCallbackBridge {
    DirectCallbackBridge()
        : wake_fd(eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK)) {}

    ~DirectCallbackBridge() {
        for (DirectCompletionRecord &record : records) {
            if (record.previous_release_fence >= 0) {
                close(record.previous_release_fence);
            }
            if (record.current_present_fence >= 0) {
                close(record.current_present_fence);
            }
        }
        if (wake_fd >= 0) close(wake_fd);
    }

    std::mutex mutex;
    std::deque<DirectCompletionRecord> records;
    std::atomic<bool> wake_failed{false};
    int wake_fd = -1;
};

struct DirectCallbackContext {
    std::shared_ptr<DirectCallbackBridge> bridge;
    std::shared_ptr<DirectControlHandle> control;
    SurfaceControlApi::StatsGetPreviousReleaseFence get_previous_release_fence =
            nullptr;
    SurfaceControlApi::StatsGetLatchTime get_latch_time = nullptr;
    SurfaceControlApi::StatsGetPresentFence get_present_fence = nullptr;
    uint64_t transaction_id = 0;
    DirectFrameKey current;
    DirectFrameKey previous;
    bool has_current = false;
    bool has_previous = false;
};

enum class DirectFramePhase {
    kAcquirePending,
    kSurfaceSubmitted,
    kSurfaceReleasePending,
};

struct DirectFrameState {
    int acquire_event_fd = -1;
    int signal_event_fd = -1;
    int surface_release_fence_fd = -1;
    int presentation_signal_event_fd = -1;
    int surface_present_fence_fd = -1;
    bool presentation_terminal = false;
    bool release_terminal = false;
    DirectFramePhase phase = DirectFramePhase::kAcquirePending;
    bool drop_when_acquired = false;
    std::shared_ptr<DirectControlHandle> control;
};

void onDirectTransactionComplete(void *opaque,
                                 ASurfaceTransactionStats *stats) {
    std::unique_ptr<DirectCallbackContext> context(
            static_cast<DirectCallbackContext *>(opaque));
    DirectCompletionRecord record;
    record.transaction_id = context->transaction_id;
    record.current = context->current;
    record.previous = context->previous;
    record.has_current = context->has_current;
    record.has_previous = context->has_previous;
    record.control = std::move(context->control);
    if (record.has_current) {
        record.current_latch_time_ns = context->get_latch_time(stats);
        record.current_present_fence = context->get_present_fence(stats);
    }
    if (record.has_previous) {
        record.previous_release_fence =
                context->get_previous_release_fence(
                        stats, record.control->control);
    }
    const std::shared_ptr<DirectCallbackBridge> bridge = context->bridge;
    {
        std::lock_guard<std::mutex> lock(bridge->mutex);
        bridge->records.push_back(std::move(record));
    }
    const uint64_t signal = 1;
    ssize_t written;
    do {
        written = write(bridge->wake_fd, &signal, sizeof(signal));
    } while (written < 0 && errno == EINTR);
    if (written != static_cast<ssize_t>(sizeof(signal)) && errno != EAGAIN) {
        bridge->wake_failed.store(true, std::memory_order_release);
    }
}

// Owns timed-out SurfaceControl release state after a Presenter worker exits.
// This singleton is deliberately process-lifetime: callbacks may arrive after
// the Activity and its Presenter are gone. libandroid.so is a DT_NEEDED
// dependency of this JNI library, so DirectControlHandle's dynamically resolved
// release function remains mapped after SurfaceControlApi drops its extra
// dlopen reference.
class DirectDrainSupervisor {
public:
    static DirectDrainSupervisor &instance() {
        static DirectDrainSupervisor *supervisor =
                new DirectDrainSupervisor();
        return *supervisor;
    }

    bool adopt(std::shared_ptr<DirectCallbackBridge> bridge,
               std::map<DirectFrameKey, DirectFrameState> *frames,
               std::map<uint64_t, std::shared_ptr<DirectControlHandle>>
                       *transactions) {
        if (bridge == nullptr || frames == nullptr || transactions == nullptr ||
            (frames->empty() && transactions->empty()) ||
            frames->size() > kMaxExternalResources ||
            transactions->size() > kMaxDirectTransactions) {
            return false;
        }

        std::lock_guard<std::mutex> lock(mutex_);
        size_t slot = batches_.size();
        for (size_t index = 0; index < batches_.size(); ++index) {
            if (batches_[index] == nullptr) {
                slot = index;
                break;
            }
        }
        if (slot == batches_.size()) return false;
        std::unique_ptr<Batch> batch(new (std::nothrow) Batch());
        if (batch == nullptr) return false;
        batch->bridge = std::move(bridge);
        batch->frames = std::move(*frames);
        batch->transactions = std::move(*transactions);
        batches_[slot] = std::move(batch);
        return wake();
    }

private:
    struct Batch {
        std::shared_ptr<DirectCallbackBridge> bridge;
        std::map<DirectFrameKey, DirectFrameState> frames;
        std::map<uint64_t, std::shared_ptr<DirectControlHandle>> transactions;
        bool quarantined = false;
    };

    enum class PollKind { kBridge, kAcquire, kPresent, kSurfaceRelease };

    struct PollTarget {
        PollKind kind;
        size_t batch_index;
        DirectFrameKey key;
    };

    DirectDrainSupervisor()
        : wake_fd_(eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK)) {
        if (wake_fd_ < 0) std::abort();
        std::thread(&DirectDrainSupervisor::run, this).detach();
    }

    bool wake() const {
        const uint64_t signal = 1;
        ssize_t written;
        do {
            written = write(wake_fd_, &signal, sizeof(signal));
        } while (written < 0 && errno == EINTR);
        return written == static_cast<ssize_t>(sizeof(signal)) || errno == EAGAIN;
    }

    static void drainEventFd(int fd) {
        uint64_t value;
        ssize_t received;
        do {
            received = read(fd, &value, sizeof(value));
        } while (received < 0 && errno == EINTR);
    }

    static bool signalRelease(Batch *batch, const DirectFrameKey &key) {
        auto frame = batch->frames.find(key);
        if (frame == batch->frames.end()) return true;
        if (frame->second.acquire_event_fd >= 0) return false;
        if (frame->second.release_terminal) return false;
        const uint64_t signal = 1;
        ssize_t written;
        do {
            written = write(frame->second.signal_event_fd,
                            &signal, sizeof(signal));
        } while (written < 0 && errno == EINTR);
        if (written != static_cast<ssize_t>(sizeof(signal)) && errno != EAGAIN) {
            return false;
        }
        if (frame->second.surface_release_fence_fd >= 0) {
            close(frame->second.surface_release_fence_fd);
        }
        close(frame->second.signal_event_fd);
        frame->second.signal_event_fd = -1;
        frame->second.release_terminal = true;
        if (frame->second.presentation_terminal) batch->frames.erase(frame);
        return true;
    }

    static bool signalPresentation(Batch *batch, const DirectFrameKey &key) {
        auto frame = batch->frames.find(key);
        if (frame == batch->frames.end() ||
            frame->second.presentation_signal_event_fd < 0) return false;
        const uint64_t signal = 1;
        ssize_t written;
        do {
            written = write(frame->second.presentation_signal_event_fd,
                            &signal, sizeof(signal));
        } while (written < 0 && errno == EINTR);
        if (written != static_cast<ssize_t>(sizeof(signal)) && errno != EAGAIN)
            return false;
        close(frame->second.presentation_signal_event_fd);
        frame->second.presentation_signal_event_fd = -1;
        if (frame->second.surface_present_fence_fd >= 0) {
            close(frame->second.surface_present_fence_fd);
            frame->second.surface_present_fence_fd = -1;
        }
        frame->second.presentation_terminal = true;
        if (frame->second.release_terminal) batch->frames.erase(frame);
        return true;
    }

    static bool consumeCompletions(Batch *batch) {
        drainEventFd(batch->bridge->wake_fd);
        if (batch->bridge->wake_failed.exchange(
                    false, std::memory_order_acq_rel)) {
            return false;
        }
        std::deque<DirectCompletionRecord> records;
        {
            std::lock_guard<std::mutex> lock(batch->bridge->mutex);
            records.swap(batch->bridge->records);
        }
        bool valid = true;
        for (DirectCompletionRecord &record : records) {
            if (!valid) {
                if (record.previous_release_fence >= 0) {
                    close(record.previous_release_fence);
                }
                if (record.current_present_fence >= 0) {
                    close(record.current_present_fence);
                }
                continue;
            }
            const auto transaction =
                    batch->transactions.find(record.transaction_id);
            if (transaction == batch->transactions.end()) {
                if (record.previous_release_fence >= 0) {
                    close(record.previous_release_fence);
                }
                valid = false;
                continue;
            }
            batch->transactions.erase(transaction);
            if (record.has_current) {
                auto current = batch->frames.find(record.current);
                if (current == batch->frames.end() ||
                    current->second.presentation_signal_event_fd < 0) {
                    valid = false;
                } else if (record.current_present_fence >= 0) {
                    current->second.surface_present_fence_fd =
                            record.current_present_fence;
                    record.current_present_fence = -1;
                } else {
                    valid = signalPresentation(batch, record.current);
                }
                if (record.current_present_fence >= 0) {
                    close(record.current_present_fence);
                    record.current_present_fence = -1;
                }
            }
            if (!record.has_previous) {
                if (record.previous_release_fence >= 0) {
                    close(record.previous_release_fence);
                }
                continue;
            }
            auto frame = batch->frames.find(record.previous);
            if (frame == batch->frames.end() ||
                frame->second.phase != DirectFramePhase::kSurfaceSubmitted) {
                if (record.previous_release_fence >= 0) {
                    close(record.previous_release_fence);
                }
                valid = false;
                continue;
            }
            if (record.previous_release_fence < 0) {
                valid = signalRelease(batch, record.previous);
            } else {
                frame->second.surface_release_fence_fd =
                        record.previous_release_fence;
                frame->second.phase = DirectFramePhase::kSurfaceReleasePending;
            }
        }
        return valid;
    }

    static bool recordsEmpty(const Batch &batch) {
        std::lock_guard<std::mutex> lock(batch.bridge->mutex);
        return batch.bridge->records.empty();
    }

    void run() {
        while (true) {
            std::vector<pollfd> descriptors = {{wake_fd_, POLLIN, 0}};
            std::vector<PollTarget> targets;
            {
                std::lock_guard<std::mutex> lock(mutex_);
                for (size_t index = 0; index < batches_.size(); ++index) {
                    const std::unique_ptr<Batch> &batch = batches_[index];
                    if (batch == nullptr || batch->quarantined) continue;
                    descriptors.push_back(
                            {batch->bridge->wake_fd, POLLIN, 0});
                    targets.push_back(
                            {PollKind::kBridge, index, DirectFrameKey{}});
                    for (const auto &frame : batch->frames) {
                        if (frame.second.phase ==
                                    DirectFramePhase::kAcquirePending &&
                            frame.second.acquire_event_fd >= 0) {
                            descriptors.push_back(
                                    {frame.second.acquire_event_fd, POLLIN, 0});
                            targets.push_back(
                                    {PollKind::kAcquire, index, frame.first});
                        } else if (frame.second.phase ==
                                           DirectFramePhase::kSurfaceReleasePending &&
                                   frame.second.surface_release_fence_fd >= 0) {
                            descriptors.push_back(
                                    {frame.second.surface_release_fence_fd,
                                     POLLIN, 0});
                            targets.push_back(
                                    {PollKind::kSurfaceRelease, index,
                                     frame.first});
                        }
                        if (frame.second.surface_present_fence_fd >= 0) {
                            descriptors.push_back(
                                    {frame.second.surface_present_fence_fd,
                                     POLLIN, 0});
                            targets.push_back(
                                    {PollKind::kPresent, index, frame.first});
                        }
                    }
                }
            }

            int result;
            do {
                result = poll(descriptors.data(), descriptors.size(),
                              kDirectRecoveryPollMillis);
            } while (result < 0 && errno == EINTR);
            if (result < 0) std::abort();
            if ((descriptors[0].revents & POLLIN) != 0) {
                drainEventFd(wake_fd_);
            }

            std::lock_guard<std::mutex> lock(mutex_);
            for (size_t descriptor_index = 1;
                 descriptor_index < descriptors.size(); ++descriptor_index) {
                if (descriptors[descriptor_index].revents == 0) continue;
                const PollTarget &target = targets[descriptor_index - 1];
                Batch *batch = batches_[target.batch_index].get();
                if (batch == nullptr || batch->quarantined) continue;
                if ((descriptors[descriptor_index].revents &
                     (POLLERR | POLLHUP | POLLNVAL)) != 0) {
                    batch->quarantined = true;
                    LOGE("dormant direct drain quarantined after fence error");
                    continue;
                }
                if ((descriptors[descriptor_index].revents & POLLIN) == 0) {
                    continue;
                }
                bool valid = true;
                if (target.kind == PollKind::kBridge) {
                    valid = consumeCompletions(batch);
                } else {
                    auto frame = batch->frames.find(target.key);
                    if (frame == batch->frames.end()) continue;
                    if (target.kind == PollKind::kAcquire) {
                        if (frame->second.phase !=
                                    DirectFramePhase::kAcquirePending ||
                            !frame->second.drop_when_acquired ||
                            frame->second.control != nullptr) {
                            valid = false;
                        } else {
                            close(frame->second.acquire_event_fd);
                            frame->second.acquire_event_fd = -1;
                            valid = signalRelease(batch, target.key);
                        }
                    } else if (target.kind == PollKind::kPresent) {
                        close(frame->second.surface_present_fence_fd);
                        frame->second.surface_present_fence_fd = -1;
                        valid = signalPresentation(batch, target.key);
                    } else if (frame->second.phase !=
                                       DirectFramePhase::kSurfaceReleasePending) {
                        valid = false;
                    } else {
                        valid = signalRelease(batch, target.key);
                    }
                }
                if (!valid) {
                    batch->quarantined = true;
                    LOGE("dormant direct drain quarantined invalid transition");
                }
            }

            for (std::unique_ptr<Batch> &batch : batches_) {
                if (batch != nullptr && !batch->quarantined &&
                    (batch->bridge->wake_failed.load(
                             std::memory_order_acquire) ||
                     !recordsEmpty(*batch)) &&
                    !consumeCompletions(batch.get())) {
                    batch->quarantined = true;
                    LOGE("dormant direct drain quarantined callback recovery failure");
                }
                if (batch != nullptr && !batch->quarantined &&
                    batch->frames.empty() && batch->transactions.empty() &&
                    recordsEmpty(*batch)) {
                    batch.reset();
                }
            }
        }
    }

    mutable std::mutex mutex_;
    std::array<std::unique_ptr<Batch>, kMaxDormantDrainBatches> batches_;
    int wake_fd_ = -1;
};

bool sendPacket(int socket_fd, const UdroidAhbTransportPacket &packet) {
    ssize_t sent;
    do {
        sent = send(socket_fd, &packet, sizeof(packet), MSG_NOSIGNAL);
    } while (sent < 0 && errno == EINTR);
    return sent == static_cast<ssize_t>(sizeof(packet));
}

bool receivePacket(int socket_fd, uint32_t expected_kind,
                   uint64_t resource_id, uint64_t generation,
                   uint64_t frame_id,
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
           packet->generation == generation && packet->frame_id == frame_id;
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

bool sendPacketWithTwoFds(int socket_fd,
                          const UdroidAhbTransportPacket &packet,
                          int first_fd, int second_fd) {
    if (first_fd < 0 || second_fd < 0) return false;
    iovec io = {const_cast<UdroidAhbTransportPacket *>(&packet), sizeof(packet)};
    const int descriptors[2] = {first_fd, second_fd};
    char control[CMSG_SPACE(sizeof(descriptors))] = {};
    msghdr message = {};
    message.msg_iov = &io;
    message.msg_iovlen = 1;
    message.msg_control = control;
    message.msg_controllen = sizeof(control);
    cmsghdr *header = CMSG_FIRSTHDR(&message);
    header->cmsg_level = SOL_SOCKET;
    header->cmsg_type = SCM_RIGHTS;
    header->cmsg_len = CMSG_LEN(sizeof(descriptors));
    memcpy(CMSG_DATA(header), descriptors, sizeof(descriptors));
    ssize_t sent;
    do {
        sent = sendmsg(socket_fd, &message, MSG_NOSIGNAL);
    } while (sent < 0 && errno == EINTR);
    return sent == static_cast<ssize_t>(sizeof(packet));
}

bool receivePacketWithFd(int socket_fd, uint32_t expected_kind,
                         uint64_t resource_id, uint64_t generation,
                         uint64_t frame_id, int *fd) {
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
        packet.resource_id != resource_id || packet.generation != generation ||
        packet.frame_id != frame_id) {
        close(*fd);
        *fd = -1;
        return false;
    }
    return true;
}

bool receiveExternalPacket(int socket_fd, UdroidAhbTransportPacket *packet,
                           std::array<int, 3> *fds, size_t *fd_count) {
    fds->fill(-1);
    *fd_count = 0;
    *packet = {};
    iovec io = {packet, sizeof(*packet)};
    char control[CMSG_SPACE(sizeof(int) * 4)] = {};
    msghdr message = {};
    message.msg_iov = &io;
    message.msg_iovlen = 1;
    message.msg_control = control;
    message.msg_controllen = sizeof(control);
    ssize_t received;
    do {
        received = recvmsg(socket_fd, &message,
                           MSG_CMSG_CLOEXEC | MSG_DONTWAIT);
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
                if (received_fd_count < fds->size()) {
                    (*fds)[received_fd_count] = received_fds[index];
                } else {
                    close(received_fds[index]);
                }
                ++received_fd_count;
            }
        }
    }

    const bool valid =
            received == static_cast<ssize_t>(sizeof(*packet)) &&
            (message.msg_flags & (MSG_TRUNC | MSG_CTRUNC)) == 0 &&
            received_fd_count <= fds->size() &&
            packet->magic == UDROID_AHB_TRANSPORT_MAGIC &&
            packet->version >= UDROID_AHB_TRANSPORT_MIN_VERSION &&
            packet->version <= UDROID_AHB_TRANSPORT_VERSION &&
            packet->reserved == 0;
    if (!valid) {
        for (int &fd : *fds) {
            if (fd >= 0) close(fd);
            fd = -1;
        }
        received_fd_count = 0;
    }
    *fd_count = received_fd_count;
    return valid;
}

void closeExternalPacketFds(std::array<int, 3> *fds) {
    for (int &fd : *fds) {
        if (fd >= 0) close(fd);
        fd = -1;
    }
}

bool signalAndCloseEventFd(int *fd) {
    if (*fd < 0) return false;
    const uint64_t signal = 1;
    ssize_t written;
    do {
        written = write(*fd, &signal, sizeof(signal));
    } while (written < 0 && errno == EINTR);
    const bool signaled =
            written == static_cast<ssize_t>(sizeof(signal)) || errno == EAGAIN;
    close(*fd);
    *fd = -1;
    return signaled;
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
    float cursor = fract(uTime * 0.055);
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
    Presenter(std::string transport_path, ProducerMode producer_mode,
              bool contract_trace, bool direct_surface_control,
              uint32_t resource_cycle_frames)
        : transport_path_(std::move(transport_path)),
          producer_mode_(producer_mode),
          contract_trace_(contract_trace),
          direct_surface_control_requested_(
                  direct_surface_control &&
                  producer_mode == ProducerMode::kExternalSupervisor),
          resource_cycle_frames_(resource_cycle_frames) {
        if (producer_mode_ == ProducerMode::kExternalSupervisor) {
            wake_fd_ = eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
        }
        worker_ = std::thread(&Presenter::run, this);
    }

    ~Presenter() {
        {
            std::lock_guard<std::mutex> lock(mutex_);
            stopping_.store(true);
            window_changed_ = true;
        }
        condition_.notify_one();
        wakeExternalLoop();
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
        wakeExternalLoop();
    }

    void requestResize() {
        {
            std::lock_guard<std::mutex> lock(mutex_);
            resize_requested_ = true;
        }
        condition_.notify_one();
        wakeExternalLoop();
    }

    void doFrame(int64_t frame_time_nanos) {
        {
            std::lock_guard<std::mutex> lock(mutex_);
            pending_frame_time_nanos_ = frame_time_nanos;
            frame_pending_ = true;
        }
        condition_.notify_one();
    }

    std::string stats() const {
        std::lock_guard<std::mutex> lock(mutex_);
        char text[768];
        std::snprintf(
                text,
                sizeof(text),
                "uDroid gfxstream Surface probe\n"
                "path: AHB socket -> GLES blit or SurfaceControl -> Surface\n"
                "producer: %s\n"
                "pacing: %s\n"
                "direct AHB: %s\n"
                "surface generation: %u  size: %dx%d  resource: %dx%d\n"
                "GPU: %s\n"
                "peer: uid %lld  %s\n"
                "resource: %llu/%llu  AHB identity: %s\n"
                "frames: %llu  completed submissions/s: %.1f  swap failures: %llu  "
                "dropped detached: %llu\n"
                "sync: socket acquire/release fences  failures: %llu/%llu\n"
                "status: %s",
                producer_mode_ == ProducerMode::kInternalProbe
                        ? "internal deterministic probe"
                        : "external supervised renderer",
                producer_mode_ == ProducerMode::kInternalProbe
                        ? "Android Choreographer"
                        : "producer events + Surface BufferQueue vsync",
                direct_surface_status_.c_str(),
                surface_generation_,
                surface_width_,
                surface_height_,
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
                static_cast<unsigned long long>(dropped_detached_frames_.load()),
                static_cast<unsigned long long>(fence_failures_.load()),
                static_cast<unsigned long long>(transport_failures_.load()),
                status_.c_str());
        return text;
    }

private:
    using SteadyTimePoint = std::chrono::steady_clock::time_point;

    static uint64_t elapsedMicros(SteadyTimePoint start, SteadyTimePoint end) {
        return static_cast<uint64_t>(
                std::chrono::duration_cast<std::chrono::microseconds>(end - start)
                        .count());
    }

    static uint64_t threadCpuMicros() {
        timespec cpu_time = {};
        if (clock_gettime(CLOCK_THREAD_CPUTIME_ID, &cpu_time) != 0) return 0;
        return static_cast<uint64_t>(cpu_time.tv_sec) * 1000000ULL +
               static_cast<uint64_t>(cpu_time.tv_nsec) / 1000ULL;
    }

    void setDirectSurfaceStatus(const char *status) {
        std::lock_guard<std::mutex> lock(mutex_);
        direct_surface_status_ = status;
    }

    void initializeDirectSurfaceControl() {
        if (!direct_surface_control_requested_) return;
        const int api_level = android_get_device_api_level();
        if (api_level < 29) {
            setDirectSurfaceStatus("unavailable below Android 10; GLES fallback");
            LOGI("direct AHB probe unavailable at API %d; using GLES fallback",
                 api_level);
            return;
        }
        if (!surface_control_api_.load()) {
            setDirectSurfaceStatus("public SurfaceControl API unavailable; GLES fallback");
            LOGI("direct AHB probe could not load public SurfaceControl API; "
                 "using GLES fallback");
            return;
        }
        direct_callback_bridge_ = std::make_shared<DirectCallbackBridge>();
        if (direct_callback_bridge_->wake_fd < 0) {
            direct_callback_bridge_.reset();
            surface_control_api_.reset();
            setDirectSurfaceStatus("callback wake creation failed; GLES fallback");
            LOGI("direct AHB probe callback wake creation failed; using GLES fallback");
            return;
        }
        direct_surface_control_capable_ = true;
        setDirectSurfaceStatus("API ready; waiting for Surface");
        LOGI("direct AHB probe API ready with asynchronous previous-buffer release");
    }

    bool createDirectSurfaceControl(ANativeWindow *window) {
        if (!destroyDirectSurfaceControl()) return false;
        if (!direct_surface_control_capable_ ||
            direct_surface_control_poisoned_ || window == nullptr) {
            return false;
        }
        ASurfaceControl *control = surface_control_api_.create_from_window(
                window, "uDroid direct AHardwareBuffer probe");
        if (control == nullptr) {
            setDirectSurfaceStatus("child creation failed; GLES fallback");
            LOGI("direct AHB probe child creation failed; using GLES fallback");
            return false;
        }
        direct_surface_control_ = std::make_shared<DirectControlHandle>();
        direct_surface_control_->control = control;
        direct_surface_control_->release = surface_control_api_.release;
        direct_detach_transaction_applied_ = true;
        setDirectSurfaceStatus("child ready; direct AHB enabled");
        LOGI("direct AHB probe child ready");
        return true;
    }

    bool hasPendingDirectControl(
            const std::shared_ptr<DirectControlHandle> &control) const {
        for (const auto &transaction : direct_pending_transactions_) {
            if (transaction.second == control) return true;
        }
        for (const auto &frame : direct_pending_frames_) {
            if (frame.second.control == control &&
                frame.second.phase != DirectFramePhase::kAcquirePending) {
                return true;
            }
        }
        return false;
    }

    void dropPendingDirectAcquiresForControl(
            const std::shared_ptr<DirectControlHandle> &control) {
        for (auto &frame : direct_pending_frames_) {
            if (frame.second.phase == DirectFramePhase::kAcquirePending &&
                frame.second.control == control) {
                frame.second.drop_when_acquired = true;
                frame.second.control.reset();
            }
        }
    }

    void finalizeDirectFrameIfTerminal(const DirectFrameKey &key) {
        auto frame = direct_pending_frames_.find(key);
        if (frame == direct_pending_frames_.end() ||
            !frame->second.presentation_terminal ||
            !frame->second.release_terminal) return;
        if (frame->second.surface_present_fence_fd >= 0)
            close(frame->second.surface_present_fence_fd);
        if (frame->second.surface_release_fence_fd >= 0)
            close(frame->second.surface_release_fence_fd);
        direct_pending_frames_.erase(frame);
        auto resource = external_resources_.find(key.resource_id);
        if (resource != external_resources_.end() &&
            resource->second.generation == key.generation &&
            resource->second.phase == ExternalResourcePhase::kReleasePending &&
            resource->second.last_frame == key.frame_id &&
            resource->second.retire_pending) {
            traceResourceRetirement(key.resource_id, key.generation);
            destroyExternalResource(resource);
        }
    }

    bool signalDirectRelease(const DirectFrameKey &key) {
        auto frame = direct_pending_frames_.find(key);
        if (frame == direct_pending_frames_.end()) return false;
        if (frame->second.acquire_event_fd >= 0) {
            LOGE("refusing to release direct frame before its acquire event");
            return false;
        }
        if (frame->second.release_terminal) return false;
        const uint64_t signal = 1;
        ssize_t written;
        do {
            written = write(frame->second.signal_event_fd,
                            &signal, sizeof(signal));
        } while (written < 0 && errno == EINTR);
        if (written != static_cast<ssize_t>(sizeof(signal)) && errno != EAGAIN) {
            direct_surface_control_poisoned_ = true;
            setDirectSurfaceStatus("release signal failed; direct route poisoned");
            LOGE("direct AHB release event signal failed: %s", strerror(errno));
            return false;
        }
        if (frame->second.surface_release_fence_fd >= 0) {
            close(frame->second.surface_release_fence_fd);
            frame->second.surface_release_fence_fd = -1;
        }
        close(frame->second.signal_event_fd);
        frame->second.signal_event_fd = -1;
        frame->second.release_terminal = true;
        traceResourceFrameEvent("direct_release_signaled", key.resource_id,
                                key.generation, key.frame_id);
        finalizeDirectFrameIfTerminal(key);
        return true;
    }

    bool signalDirectPresentation(const DirectFrameKey &key,
                                  int64_t latch_time_ns) {
        auto frame = direct_pending_frames_.find(key);
        if (frame == direct_pending_frames_.end() ||
            frame->second.presentation_signal_event_fd < 0 ||
            frame->second.presentation_terminal) {
            LOGE("missing presentation signal for direct frame");
            return false;
        }
        const uint64_t signal = 1;
        ssize_t written;
        do {
            written = write(frame->second.presentation_signal_event_fd,
                            &signal, sizeof(signal));
        } while (written < 0 && errno == EINTR);
        if (written != static_cast<ssize_t>(sizeof(signal)) && errno != EAGAIN) {
            LOGE("direct AHB presentation event signal failed: %s",
                 strerror(errno));
            return false;
        }
        close(frame->second.presentation_signal_event_fd);
        frame->second.presentation_signal_event_fd = -1;
        if (frame->second.surface_present_fence_fd >= 0) {
            close(frame->second.surface_present_fence_fd);
            frame->second.surface_present_fence_fd = -1;
        }
        frame->second.presentation_terminal = true;
        TRACE_WINSYS("event=direct_present_signaled resource=%llu generation=%llu "
                     "frame=%llu latch_ns=%lld",
                     static_cast<unsigned long long>(key.resource_id),
                     static_cast<unsigned long long>(key.generation),
                     static_cast<unsigned long long>(key.frame_id),
                     static_cast<long long>(latch_time_ns));
        finalizeDirectFrameIfTerminal(key);
        return true;
    }

    bool cancelDirectFrame(const DirectFrameKey &key) {
        auto frame = direct_pending_frames_.find(key);
        if (frame == direct_pending_frames_.end()) return false;
        if (frame->second.surface_present_fence_fd >= 0)
            close(frame->second.surface_present_fence_fd);
        frame->second.surface_present_fence_fd = -1;
        if (frame->second.presentation_signal_event_fd >= 0)
            close(frame->second.presentation_signal_event_fd);
        frame->second.presentation_signal_event_fd = -1;
        frame->second.presentation_terminal = true;
        TRACE_WINSYS("event=direct_present_cancelled resource=%llu "
                     "generation=%llu frame=%llu",
                     static_cast<unsigned long long>(key.resource_id),
                     static_cast<unsigned long long>(key.generation),
                     static_cast<unsigned long long>(key.frame_id));
        if (!frame->second.release_terminal && !signalDirectRelease(key))
            return false;
        finalizeDirectFrameIfTerminal(key);
        return false;
    }

    void recordDirectTransactionCompletion(const DirectFrameKey &key) {
        const uint64_t frame = ++frames_;
        if (frame % 60 == 0) {
            const auto now = std::chrono::steady_clock::now();
            const auto sample_us =
                    std::chrono::duration_cast<std::chrono::microseconds>(
                            now - fps_sample_started_).count();
            if (sample_us > 0) {
                fps_milli_.store(
                        static_cast<uint64_t>(60000000000LL / sample_us));
            }
            fps_sample_started_ = now;
        }
        traceResourceFrameEvent("direct_complete", key.resource_id,
                                key.generation, key.frame_id);
    }

    bool drainDirectCompletionQueue() {
        if (direct_callback_bridge_ == nullptr) return true;
        uint64_t wake_count;
        while (read(direct_callback_bridge_->wake_fd, &wake_count,
                    sizeof(wake_count)) < 0 && errno == EINTR) {}

        std::deque<DirectCompletionRecord> records;
        {
            std::lock_guard<std::mutex> lock(direct_callback_bridge_->mutex);
            records.swap(direct_callback_bridge_->records);
        }
        bool valid = !direct_callback_bridge_->wake_failed.exchange(
                false, std::memory_order_acq_rel);
        for (DirectCompletionRecord &record : records) {
            const auto transaction =
                    direct_pending_transactions_.find(record.transaction_id);
            if (transaction == direct_pending_transactions_.end()) {
                if (record.previous_release_fence >= 0) {
                    close(record.previous_release_fence);
                }
                if (record.current_present_fence >= 0) {
                    close(record.current_present_fence);
                }
                valid = false;
                continue;
            }
            direct_pending_transactions_.erase(transaction);
            if (record.has_current) {
                recordDirectTransactionCompletion(record.current);
                TRACE_WINSYS("event=direct_present_feedback resource=%llu "
                             "generation=%llu frame=%llu latch_ns=%lld "
                             "present_fence=%s",
                             static_cast<unsigned long long>(
                                     record.current.resource_id),
                             static_cast<unsigned long long>(
                                     record.current.generation),
                             static_cast<unsigned long long>(
                                     record.current.frame_id),
                             static_cast<long long>(
                                     record.current_latch_time_ns),
                             record.current_present_fence >= 0
                                     ? "available" : "unavailable");
                auto current = direct_pending_frames_.find(record.current);
                if (current == direct_pending_frames_.end()) {
                    if (record.current_present_fence >= 0)
                        close(record.current_present_fence);
                    valid = false;
                } else if (record.current_present_fence >= 0) {
                    current->second.surface_present_fence_fd =
                            record.current_present_fence;
                    record.current_present_fence = -1;
                } else {
                    valid = signalDirectPresentation(
                                    record.current,
                                    record.current_latch_time_ns) && valid;
                }
            }
            if (!record.has_previous) {
                if (record.previous_release_fence >= 0) {
                    close(record.previous_release_fence);
                }
                continue;
            }
            auto frame = direct_pending_frames_.find(record.previous);
            if (frame == direct_pending_frames_.end() ||
                frame->second.phase != DirectFramePhase::kSurfaceSubmitted) {
                if (record.previous_release_fence >= 0) {
                    close(record.previous_release_fence);
                }
                valid = false;
                continue;
            }
            if (record.previous_release_fence < 0) {
                valid = signalDirectRelease(record.previous) && valid;
                continue;
            }
            frame->second.surface_release_fence_fd =
                    record.previous_release_fence;
            frame->second.phase = DirectFramePhase::kSurfaceReleasePending;
        }
        if (!valid) {
            direct_surface_control_poisoned_ = true;
            setDirectSurfaceStatus("completion mismatch; direct route poisoned");
            ++fence_failures_;
        }
        return valid;
    }

    bool serviceDirectWork(int timeout_ms) {
        if (direct_callback_bridge_ == nullptr) return true;
        std::vector<pollfd> descriptors;
        std::vector<DirectFrameKey> acquire_keys;
        std::vector<DirectFrameKey> present_keys;
        std::vector<DirectFrameKey> release_keys;
        descriptors.push_back({direct_callback_bridge_->wake_fd, POLLIN, 0});
        for (const auto &frame : direct_pending_frames_) {
            if (frame.second.phase == DirectFramePhase::kAcquirePending &&
                frame.second.acquire_event_fd >= 0) {
                descriptors.push_back(
                        {frame.second.acquire_event_fd, POLLIN, 0});
                acquire_keys.push_back(frame.first);
            }
        }
        const size_t present_start = descriptors.size();
        for (const auto &frame : direct_pending_frames_) {
            if (frame.second.surface_present_fence_fd >= 0) {
                descriptors.push_back(
                        {frame.second.surface_present_fence_fd, POLLIN, 0});
                present_keys.push_back(frame.first);
            }
        }
        const size_t release_start = descriptors.size();
        for (const auto &frame : direct_pending_frames_) {
            if (frame.second.phase ==
                        DirectFramePhase::kSurfaceReleasePending &&
                frame.second.surface_release_fence_fd >= 0) {
                descriptors.push_back(
                        {frame.second.surface_release_fence_fd, POLLIN, 0});
                release_keys.push_back(frame.first);
            }
        }
        int result;
        do {
            result = poll(descriptors.data(), descriptors.size(), timeout_ms);
        } while (result < 0 && errno == EINTR);
        if (result <= 0) return result == 0;
        bool valid = !direct_callback_bridge_->wake_failed.load(
                std::memory_order_acquire);
        if ((descriptors[0].revents & POLLIN) != 0) {
            valid = drainDirectCompletionQueue() && valid;
        }
        for (size_t index = 1; index < present_start; ++index) {
            if ((descriptors[index].revents & POLLIN) != 0) {
                valid = submitReadyDirectFrame(acquire_keys[index - 1]) && valid;
            } else if ((descriptors[index].revents &
                        (POLLERR | POLLHUP | POLLNVAL)) != 0) {
                valid = false;
            }
        }
        for (size_t index = present_start; index < release_start; ++index) {
            const size_t key_index = index - present_start;
            auto frame = direct_pending_frames_.find(present_keys[key_index]);
            if (frame == direct_pending_frames_.end()) continue;
            if ((descriptors[index].revents & POLLIN) != 0) {
                close(frame->second.surface_present_fence_fd);
                frame->second.surface_present_fence_fd = -1;
                valid = signalDirectPresentation(
                                present_keys[key_index], 0) && valid;
            } else if ((descriptors[index].revents &
                        (POLLERR | POLLHUP | POLLNVAL)) != 0) {
                close(frame->second.surface_present_fence_fd);
                frame->second.surface_present_fence_fd = -1;
                cancelDirectFrame(present_keys[key_index]);
                valid = false;
            }
        }
        for (size_t index = release_start; index < descriptors.size(); ++index) {
            const size_t key_index = index - release_start;
            if ((descriptors[index].revents & POLLIN) != 0) {
                valid = signalDirectRelease(release_keys[key_index]) && valid;
            } else if ((descriptors[index].revents &
                        (POLLERR | POLLHUP | POLLNVAL)) != 0) {
                auto frame = direct_pending_frames_.find(
                        release_keys[key_index]);
                if (frame != direct_pending_frames_.end() &&
                    frame->second.surface_release_fence_fd >= 0) {
                    close(frame->second.surface_release_fence_fd);
                    frame->second.surface_release_fence_fd = -1;
                }
                valid = false;
            }
        }
        if (!valid) {
            direct_surface_control_poisoned_ = true;
            setDirectSurfaceStatus("release fence failed; direct route poisoned");
            ++fence_failures_;
        }
        return valid;
    }

    bool drainDirectControl(
            const std::shared_ptr<DirectControlHandle> &control) {
        const auto deadline = std::chrono::steady_clock::now() +
                              std::chrono::milliseconds(1000);
        while (hasPendingDirectControl(control)) {
            const auto now = std::chrono::steady_clock::now();
            if (now >= deadline) {
                direct_surface_control_poisoned_ = true;
                setDirectSurfaceStatus("detach drain timed out; direct route poisoned");
                LOGE("direct AHB detach timed out; retained releases stay unsignaled");
                return false;
            }
            const int remaining_ms = static_cast<int>(
                    std::chrono::duration_cast<std::chrono::milliseconds>(
                            deadline - now).count());
            if (!serviceDirectWork(remaining_ms > 0 ? remaining_ms : 1)) {
                return false;
            }
        }
        return true;
    }

    bool destroyDirectSurfaceControl() {
        if (direct_surface_control_ == nullptr) return true;
        const std::shared_ptr<DirectControlHandle> control =
                direct_surface_control_;
        dropPendingDirectAcquiresForControl(control);
        if (direct_pending_transactions_.size() >= kMaxDirectTransactions) {
            direct_surface_control_poisoned_ = true;
            setDirectSurfaceStatus(
                    "detach transaction capacity exhausted; direct route poisoned");
            return false;
        }
        ASurfaceTransaction *transaction =
                surface_control_api_.transaction_create();
        std::unique_ptr<DirectCallbackContext> context(
                new (std::nothrow) DirectCallbackContext());
        if (transaction == nullptr || context == nullptr) {
            if (transaction != nullptr) {
                surface_control_api_.transaction_delete(transaction);
            }
            direct_surface_control_poisoned_ = true;
            setDirectSurfaceStatus("detach setup failed; direct route poisoned");
            return false;
        }
        context->bridge = direct_callback_bridge_;
        context->control = control;
        context->get_previous_release_fence =
                surface_control_api_.stats_get_previous_release_fence;
        context->get_latch_time = surface_control_api_.stats_get_latch_time;
        context->get_present_fence = surface_control_api_.stats_get_present_fence;
        context->transaction_id = ++direct_next_transaction_id_;
        if (direct_current_valid_) {
            context->previous = direct_current_frame_;
            context->has_previous = true;
        }
        direct_pending_transactions_.emplace(
                context->transaction_id, control);
        // Public API 29 marks setBuffer's AHardwareBuffer non-null. Removing
        // the child from the tree is the supported detach operation and its
        // OnComplete carries the previous buffer's actual release fence.
        surface_control_api_.transaction_set_visibility(
                transaction, control->control,
                ASURFACE_TRANSACTION_VISIBILITY_HIDE);
        surface_control_api_.transaction_reparent(
                transaction, control->control, nullptr);
        surface_control_api_.transaction_set_on_complete(
                transaction, context.release(), onDirectTransactionComplete);
        surface_control_api_.transaction_apply(transaction);
        surface_control_api_.transaction_delete(transaction);
        direct_detach_transaction_applied_ = true;
        direct_surface_control_.reset();
        direct_current_valid_ = false;
        const bool drained = drainDirectControl(control);
        if (drained && direct_surface_control_capable_) {
            setDirectSurfaceStatus("API ready; waiting for Surface");
        }
        return drained;
    }

    void resetExternalFrameTimings() {
        external_frame_timings_ = {};
    }

    void recordExternalFrameTiming(const ExternalFrameTimingSample &sample) {
        if (!contract_trace_) return;
        ExternalFrameTimingWindow &timings = external_frame_timings_;
        if (timings.frames == 0) {
            timings.window_started = sample.service_started;
            timings.thread_cpu_started_us = sample.thread_cpu_started_us;
        }
        if (timings.last_swap_completed != SteadyTimePoint{}) {
            const uint64_t service_gap_us = elapsedMicros(
                    timings.last_swap_completed, sample.service_started);
            const uint64_t inter_swap_us = elapsedMicros(
                    timings.last_swap_completed, sample.swap_completed);
            timings.service_gap.add(service_gap_us);
            timings.inter_swap.add(inter_swap_us);
            const size_t bucket = inter_swap_us <= 8300 ? 0
                    : inter_swap_us <= 16700 ? 1
                    : inter_swap_us <= 25000 ? 2
                    : inter_swap_us <= 33300 ? 3
                    : inter_swap_us <= 50000 ? 4
                    : 5;
            ++timings.interval_buckets[bucket];
        }
        timings.make_current.add(sample.make_current_us);
        timings.acquire_wait.add(sample.acquire_wait_us);
        timings.draw.add(sample.draw_us);
        timings.fence_export.add(sample.fence_export_us);
        timings.socket_send.add(sample.socket_send_us);
        timings.swap.add(sample.swap_us);
        timings.total.add(sample.total_us);
        timings.last_swap_completed = sample.swap_completed;
        ++timings.frames;

        if (timings.frames < 120) return;
        const uint64_t window_wall_us =
                elapsedMicros(timings.window_started, sample.swap_completed);
        const uint64_t cpu_completed_us = threadCpuMicros();
        const uint64_t window_cpu_us =
                cpu_completed_us >= timings.thread_cpu_started_us
                ? cpu_completed_us - timings.thread_cpu_started_us
                : 0;
        const double submit_fps = timings.inter_swap.sum_us > 0
                ? 1000000.0 * static_cast<double>(timings.inter_swap.samples) /
                          static_cast<double>(timings.inter_swap.sum_us)
                : 0.0;
        const double thread_cpu_percent = window_wall_us > 0
                ? 100.0 * static_cast<double>(window_cpu_us) /
                          static_cast<double>(window_wall_us)
                : 0.0;
        TRACE_WINSYS(
                "timing frames=120 avg/max_us bq_submit_fps=%.1f "
                "thread_cpu_pct=%.1f cpu_window_us=%llu wall_window_us=%llu "
                "gap_us=%llu/%llu make_us=%llu/%llu wait_us=%llu/%llu "
                "draw_us=%llu/%llu export_us=%llu/%llu send_us=%llu/%llu "
                "swap_us=%llu/%llu total_us=%llu/%llu inter_us=%llu/%llu "
                "buckets=<=8.3:%llu,<=16.7:%llu,<=25:%llu,<=33.3:%llu,"
                "<=50:%llu,>50:%llu",
                submit_fps, thread_cpu_percent,
                static_cast<unsigned long long>(window_cpu_us),
                static_cast<unsigned long long>(window_wall_us),
                static_cast<unsigned long long>(timings.service_gap.average()),
                static_cast<unsigned long long>(timings.service_gap.max_us),
                static_cast<unsigned long long>(timings.make_current.average()),
                static_cast<unsigned long long>(timings.make_current.max_us),
                static_cast<unsigned long long>(timings.acquire_wait.average()),
                static_cast<unsigned long long>(timings.acquire_wait.max_us),
                static_cast<unsigned long long>(timings.draw.average()),
                static_cast<unsigned long long>(timings.draw.max_us),
                static_cast<unsigned long long>(timings.fence_export.average()),
                static_cast<unsigned long long>(timings.fence_export.max_us),
                static_cast<unsigned long long>(timings.socket_send.average()),
                static_cast<unsigned long long>(timings.socket_send.max_us),
                static_cast<unsigned long long>(timings.swap.average()),
                static_cast<unsigned long long>(timings.swap.max_us),
                static_cast<unsigned long long>(timings.total.average()),
                static_cast<unsigned long long>(timings.total.max_us),
                static_cast<unsigned long long>(timings.inter_swap.average()),
                static_cast<unsigned long long>(timings.inter_swap.max_us),
                static_cast<unsigned long long>(timings.interval_buckets[0]),
                static_cast<unsigned long long>(timings.interval_buckets[1]),
                static_cast<unsigned long long>(timings.interval_buckets[2]),
                static_cast<unsigned long long>(timings.interval_buckets[3]),
                static_cast<unsigned long long>(timings.interval_buckets[4]),
                static_cast<unsigned long long>(timings.interval_buckets[5]));

        const SteadyTimePoint last_swap = timings.last_swap_completed;
        timings = {};
        timings.last_swap_completed = last_swap;
    }

    void wakeExternalLoop() const {
        if (wake_fd_ < 0) return;
        const uint64_t signal = 1;
        ssize_t written;
        do {
            written = write(wake_fd_, &signal, sizeof(signal));
        } while (written < 0 && errno == EINTR);
    }

    void drainExternalWake() const {
        uint64_t signal;
        while (read(wake_fd_, &signal, sizeof(signal)) < 0 && errno == EINTR) {}
    }

    bool initializeTransport() {
        sockaddr_un address = {};
        if (transport_path_.empty() ||
            transport_path_.size() >= sizeof(address.sun_path)) {
            setStatus("private graphics socket path is invalid");
            return false;
        }

        listener_socket_ = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0);
        if (listener_socket_ < 0) {
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

        if (producer_mode_ == ProducerMode::kInternalProbe) {
            transport_sockets_[0] =
                    socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0);
            if (transport_sockets_[0] < 0 ||
                connect(transport_sockets_[0],
                        reinterpret_cast<const sockaddr *>(&address),
                        sizeof(address)) != 0) {
                setStatus("graphics producer could not connect to private listener");
                return false;
            }
        } else {
            if (wake_fd_ < 0) {
                setStatus("external presenter wake event creation failed");
                return false;
            }
            setStatus("waiting for supervised graphics producer");
            return true;
        }

        while (!stopping_.load()) {
            pollfd descriptor = {listener_socket_, POLLIN, 0};
            int result;
            do {
                result = poll(&descriptor, 1, 100);
            } while (result < 0 && errno == EINTR);
            if (result < 0) {
                setStatus("graphics presenter listener poll failed");
                return false;
            }
            if (result == 0) continue;
            if ((descriptor.revents & POLLIN) == 0) {
                setStatus("graphics presenter listener closed unexpectedly");
                return false;
            }
            transport_sockets_[1] =
                    accept4(listener_socket_, nullptr, nullptr, SOCK_CLOEXEC);
            if (transport_sockets_[1] >= 0) break;
            if (errno != EINTR && errno != EAGAIN) {
                setStatus("graphics presenter could not accept producer connection");
                return false;
            }
        }
        if (stopping_.load()) return false;

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
        presenter_context_ =
                eglCreateContext(display_, config_, EGL_NO_CONTEXT, context_attributes);
        const EGLint pbuffer_attributes[] = {
            EGL_WIDTH, 1,
            EGL_HEIGHT, 1,
            EGL_NONE,
        };
        presenter_pbuffer_ = eglCreatePbufferSurface(display_, config_, pbuffer_attributes);
        if (presenter_context_ == EGL_NO_CONTEXT ||
            presenter_pbuffer_ == EGL_NO_SURFACE ||
            !makePresenterCurrent(presenter_pbuffer_)) {
            setStatus("presenter EGL context initialization failed");
            return false;
        }

        if (producer_mode_ == ProducerMode::kInternalProbe) {
            producer_context_ =
                    eglCreateContext(display_, config_, EGL_NO_CONTEXT,
                                     context_attributes);
            producer_pbuffer_ =
                    eglCreatePbufferSurface(display_, config_, pbuffer_attributes);
            if (producer_context_ == EGL_NO_CONTEXT ||
                producer_pbuffer_ == EGL_NO_SURFACE || !makeProducerCurrent()) {
                setStatus("probe producer EGL context initialization failed");
                return false;
            }
        }

        if (!makePresenterCurrent(presenter_pbuffer_)) {
            setStatus("presenter EGL context activation failed");
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

        if (producer_mode_ == ProducerMode::kInternalProbe) {
            if (!makeProducerCurrent()) {
                setStatus("probe producer EGL context activation failed");
                return false;
            }
            pattern_program_ = createProgram(kVertexShader, kPatternShader);
            if (!makePresenterCurrent(presenter_pbuffer_)) {
                setStatus("presenter EGL context activation failed");
                return false;
            }
        }
        present_program_ = createProgram(kVertexShader, kPresentShader);
        if ((producer_mode_ == ProducerMode::kInternalProbe && pattern_program_ == 0) ||
            present_program_ == 0) {
            setStatus("presenter shader compilation failed");
            return false;
        }
        initializeDirectSurfaceControl();
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
        // Direct AHardwareBuffer/SurfaceControl presentation never swaps the
        // EGL window surface. Avoid giving the vendor EGL implementation an
        // implicit vsync throttle in that mode; retain interval 1 for the
        // GLES BufferQueue fallback.
        eglSwapInterval(display_, producer_mode_ == ProducerMode::kExternalSupervisor
                                      ? 0
                                      : 1);
        if (producer_mode_ == ProducerMode::kExternalSupervisor) {
            createDirectSurfaceControl(window);
            updateWindowGeometry(window);
            setStatus(external_resources_.empty()
                              ? "waiting for external AHardwareBuffer registration"
                              : "Surface attached; external resources retained");
            return true;
        }
        return recreateFrameBuffer(window);
    }

    void updateWindowGeometry(ANativeWindow *window) {
        const int width = ANativeWindow_getWidth(window);
        const int height = ANativeWindow_getHeight(window);
        if (width <= 0 || height <= 0) return;
        std::lock_guard<std::mutex> lock(mutex_);
        surface_width_ = width;
        surface_height_ = height;
        resize_requested_ = false;
    }

    bool importExternalFrameBuffer(const UdroidAhbTransportPacket &registration) {
        pollfd descriptor = {transport_sockets_[1], POLLIN, 0};
        int result;
        do {
            result = poll(&descriptor, 1, 1000);
        } while (result < 0 && errno == EINTR);
        if (result <= 0 || (descriptor.revents & POLLIN) == 0) {
            ++transport_failures_;
            setStatus("external AHardwareBuffer handle did not follow registration");
            return false;
        }

        AHardwareBuffer *received_buffer = nullptr;
        if (AHardwareBuffer_recvHandleFromUnixSocket(transport_sockets_[1],
                                                     &received_buffer) != 0 ||
            received_buffer == nullptr) {
            ++transport_failures_;
            setStatus("external AHardwareBuffer handle receive failed");
            return false;
        }

        if (external_resources_.find(registration.resource_id) !=
                    external_resources_.end()) {
            AHardwareBuffer_release(received_buffer);
            ++transport_failures_;
            setStatus("external resource registration is duplicate");
            return false;
        }
        if (external_resources_.size() >= kMaxExternalResources) {
            AHardwareBuffer_release(received_buffer);
            ++transport_failures_;
            setStatus("external AHardwareBuffer resource pool is full");
            return false;
        }
        const auto prior_generation =
                external_latest_generations_.find(registration.resource_id);
        if (prior_generation != external_latest_generations_.end() &&
            registration.generation <= prior_generation->second) {
            AHardwareBuffer_release(received_buffer);
            ++transport_failures_;
            setStatus("external resource registration reused a stale generation");
            return false;
        }

        AHardwareBuffer_Desc description = {};
        AHardwareBuffer_describe(received_buffer, &description);
        if (description.width == 0 || description.height == 0 ||
            description.layers != 1 ||
            (description.usage & AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE) == 0) {
            AHardwareBuffer_release(received_buffer);
            ++transport_failures_;
            setStatus("external AHardwareBuffer is not GPU-sampleable content");
            return false;
        }

        if (!makePresenterCurrent(presenter_pbuffer_)) {
            setStatus("presenter EGL context activation failed for external buffer");
            AHardwareBuffer_release(received_buffer);
            return false;
        }
        EGLClientBuffer client_buffer =
                egl_get_native_client_buffer_(received_buffer);
        const EGLint image_attributes[] = {
            EGL_IMAGE_PRESERVED_KHR, EGL_TRUE,
            EGL_NONE,
        };
        EGLImageKHR image = egl_create_image_(display_, EGL_NO_CONTEXT,
                                             EGL_NATIVE_BUFFER_ANDROID, client_buffer,
                                             image_attributes);
        if (image == EGL_NO_IMAGE_KHR) {
            setStatus("external AHardwareBuffer EGLImage import failed");
            AHardwareBuffer_release(received_buffer);
            return false;
        }

        GLuint texture = 0;
        glGenTextures(1, &texture);
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        gl_egl_image_target_texture_(GL_TEXTURE_2D, image);
        if (glGetError() != GL_NO_ERROR) {
            setStatus("external AHardwareBuffer texture binding failed");
            glDeleteTextures(1, &texture);
            egl_destroy_image_(display_, image);
            AHardwareBuffer_release(received_buffer);
            return false;
        }

        ExternalResource resource;
        resource.generation = registration.generation;
        resource.buffer = received_buffer;
        resource.description = description;
        resource.image = image;
        resource.texture = texture;
        external_resources_.emplace(registration.resource_id, resource);
        external_latest_generations_[registration.resource_id] =
                registration.generation;
        active_resource_id_.store(registration.resource_id);
        buffer_generation_.store(registration.generation);
        uint64_t buffer_id = 0;
        const bool has_buffer_id =
                getHardwareBufferId(received_buffer, &buffer_id);
        uint32_t surface_generation;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            surface_generation = surface_generation_;
            frame_width_ = static_cast<int>(description.width);
            frame_height_ = static_cast<int>(description.height);
            if (has_buffer_id) {
                char identity[64];
                std::snprintf(identity, sizeof(identity), "external id %llu",
                              static_cast<unsigned long long>(buffer_id));
                buffer_identity_ = identity;
            } else {
                buffer_identity_ = "external (Android < 12)";
            }
            status_ = "external resource registered; waiting for frame";
        }
        traceResourceRegistration(registration.resource_id,
                                  registration.generation,
                                  surface_generation, description);
        return true;
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
            0,
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
                           resource_id, generation, 0, &received) ||
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
        traceRegistration(producer_description);
        return true;
    }

    void traceRegistration(const AHardwareBuffer_Desc &description) {
        if (!contract_trace_) return;
        uint32_t surface_generation;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            surface_generation = surface_generation_;
        }
        traceResourceRegistration(active_resource_id_.load(),
                                  buffer_generation_.load(),
                                  surface_generation, description);
        contract_resource_registered_ = true;
    }

    void traceResourceRegistration(uint64_t resource_id, uint64_t generation,
                                   uint32_t surface_generation,
                                   const AHardwareBuffer_Desc &description) const {
        if (!contract_trace_) return;
        TRACE_WINSYS(
                "{\"schema\":1,\"event\":\"register\",\"resource\":%llu,"
                "\"generation\":%llu,\"surface_generation\":%u,\"width\":%u,"
                "\"height\":%u,\"layers\":%u,\"format\":%u,\"usage\":%llu,"
                "\"stride\":%u}",
                static_cast<unsigned long long>(resource_id),
                static_cast<unsigned long long>(generation),
                surface_generation, description.width, description.height,
                description.layers, description.format,
                static_cast<unsigned long long>(description.usage),
                description.stride);
    }

    void traceFrameEvent(const char *event, uint64_t frame) const {
        traceResourceFrameEvent(event, active_resource_id_.load(),
                                buffer_generation_.load(), frame);
    }

    void traceResourceFrameEvent(const char *event, uint64_t resource_id,
                                 uint64_t generation, uint64_t frame) const {
        if (!contract_trace_) return;
        TRACE_WINSYS(
                "{\"schema\":1,\"event\":\"%s\",\"resource\":%llu,"
                "\"generation\":%llu,\"frame\":%llu}",
                event,
                static_cast<unsigned long long>(resource_id),
                static_cast<unsigned long long>(generation),
                static_cast<unsigned long long>(frame));
    }

    void traceRetirement() {
        if (!contract_trace_ || !contract_resource_registered_) return;
        TRACE_WINSYS(
                "{\"schema\":1,\"event\":\"retire\",\"resource\":%llu,"
                "\"generation\":%llu}",
                static_cast<unsigned long long>(active_resource_id_.load()),
                static_cast<unsigned long long>(buffer_generation_.load()));
        contract_resource_registered_ = false;
    }

    void traceResourceRetirement(uint64_t resource_id, uint64_t generation) const {
        if (!contract_trace_) return;
        TRACE_WINSYS(
                "{\"schema\":1,\"event\":\"retire\",\"resource\":%llu,"
                "\"generation\":%llu}",
                static_cast<unsigned long long>(resource_id),
                static_cast<unsigned long long>(generation));
    }

    bool transferFence(uint32_t kind, int sender_socket,
                       int receiver_socket, int fence_fd, uint64_t frame_id,
                       int *received_fd) {
        const uint64_t resource_id = active_resource_id_.load();
        const uint64_t generation = buffer_generation_.load();
        const UdroidAhbTransportPacket packet = {
            UDROID_AHB_TRANSPORT_MAGIC,
            UDROID_AHB_TRANSPORT_VERSION,
            kind,
            0,
            resource_id,
            generation,
            frame_id,
        };
        const bool sent = sendPacketWithFd(sender_socket, packet, fence_fd);
        close(fence_fd);
        if (!sent || !receivePacketWithFd(receiver_socket, kind,
                                          resource_id, generation, frame_id,
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
            surface_width_ = width;
            surface_height_ = height;
            resize_requested_ = false;
            status_ = "public AHB socket transport is active";
        }
        return true;
    }

    bool swapAndRecordFrame() {
        if (eglSwapBuffers(display_, window_surface_)) {
            const uint64_t frame = ++frames_;
            if (frame % 60 == 0) {
                const auto now = std::chrono::steady_clock::now();
                const auto sample_us =
                        std::chrono::duration_cast<std::chrono::microseconds>(
                                now - fps_sample_started_)
                                .count();
                if (sample_us > 0) {
                    fps_milli_.store(
                            static_cast<uint64_t>(60000000000LL / sample_us));
                }
                fps_sample_started_ = now;
            }
            return true;
        }
        ++swap_failures_;
        setStatus("eglSwapBuffers failed; awaiting Surface replacement");
        return false;
    }

    void destroyExternalResource(
            std::map<uint64_t, ExternalResource>::iterator resource) {
        if (resource == external_resources_.end()) return;
        makePresenterCurrent(presenter_pbuffer_);
        if (resource->second.texture != 0) {
            glDeleteTextures(1, &resource->second.texture);
        }
        if (resource->second.image != EGL_NO_IMAGE_KHR &&
            egl_destroy_image_ != nullptr) {
            egl_destroy_image_(display_, resource->second.image);
        }
        if (resource->second.buffer != nullptr) {
            AHardwareBuffer_release(resource->second.buffer);
        }
        external_resources_.erase(resource);
    }

    void destroyAllExternalResources() {
        while (!external_resources_.empty()) {
            destroyExternalResource(external_resources_.begin());
        }
    }

    void finishDeferredDirectDisconnectCleanup() {
        if (!direct_disconnect_cleanup_pending_ ||
            !direct_pending_frames_.empty() ||
            !direct_pending_transactions_.empty()) {
            return;
        }
        destroyAllExternalResources();
        external_latest_generations_.clear();
        direct_disconnect_cleanup_pending_ = false;
        setDirectSurfaceStatus("disconnect drain completed; GLES fallback ready");
    }

    void closeExternalProducer(const char *status) {
        resetExternalFrameTimings();
        const bool direct_drained = destroyDirectSurfaceControl();
        if (direct_drained && direct_pending_frames_.empty() &&
            direct_pending_transactions_.empty()) {
            destroyAllExternalResources();
            external_latest_generations_.clear();
            direct_disconnect_cleanup_pending_ = false;
        } else {
            direct_disconnect_cleanup_pending_ = true;
            direct_surface_control_poisoned_ = true;
            setDirectSurfaceStatus(
                    "disconnect drain incomplete; direct resources retained");
        }
        active_resource_id_.store(0);
        buffer_generation_.store(0);
        if (transport_sockets_[1] >= 0) {
            close(transport_sockets_[1]);
            transport_sockets_[1] = -1;
        }
        peer_uid_.store(-1);
        peer_authenticated_.store(false);
        {
            std::lock_guard<std::mutex> lock(mutex_);
            frame_width_ = 0;
            frame_height_ = 0;
            buffer_identity_ = "none";
        }
        if (status != nullptr) setStatus(status);
    }

    bool acceptExternalProducer() {
        const int socket_fd =
                accept4(listener_socket_, nullptr, nullptr, SOCK_CLOEXEC);
        if (socket_fd < 0) {
            if (errno != EINTR && errno != EAGAIN && errno != EWOULDBLOCK) {
                ++transport_failures_;
                setStatus("graphics presenter could not accept producer connection");
            }
            return false;
        }

        ucred credentials = {};
        socklen_t credentials_size = sizeof(credentials);
        if (getsockopt(socket_fd, SOL_SOCKET, SO_PEERCRED,
                       &credentials, &credentials_size) != 0 ||
            credentials_size != sizeof(credentials) || credentials.uid != getuid()) {
            close(socket_fd);
            ++transport_failures_;
            setStatus("graphics producer peer authentication failed");
            return false;
        }

        if (direct_disconnect_cleanup_pending_ ||
            !direct_pending_frames_.empty() ||
            !direct_pending_transactions_.empty()) {
            close(socket_fd);
            ++transport_failures_;
            setStatus("graphics producer reconnect blocked by direct drain");
            return false;
        }

        ++direct_connection_epoch_;
        direct_surface_control_poisoned_ = false;
        transport_sockets_[1] = socket_fd;
        peer_uid_.store(credentials.uid);
        peer_authenticated_.store(true);
        if (direct_surface_control_capable_ && external_active_window_ != nullptr &&
            direct_surface_control_ == nullptr) {
            createDirectSurfaceControl(external_active_window_);
        }
        setStatus("supervised graphics producer connected");
        return true;
    }

    bool sendExternalRelease(const UdroidAhbTransportPacket &acquire,
                             ExternalResource *resource, int release_fence) {
        const UdroidAhbTransportPacket release_packet = {
            UDROID_AHB_TRANSPORT_MAGIC,
            acquire.version,
            UDROID_AHB_RELEASE_FENCE,
            0,
            acquire.resource_id,
            acquire.generation,
            acquire.frame_id,
        };
        const bool sent = sendPacketWithFd(
                transport_sockets_[1], release_packet, release_fence);
        close(release_fence);
        if (!sent) {
            ++transport_failures_;
            setStatus("presenter failed to return external release fence");
            return false;
        }
        resource->phase = ExternalResourcePhase::kReleasePending;
        resource->last_frame = acquire.frame_id;
        traceResourceFrameEvent("release_sent", acquire.resource_id,
                                acquire.generation, acquire.frame_id);
        return true;
    }

    bool queueDirectExternalFrame(const UdroidAhbTransportPacket &packet,
                                  ExternalResource *resource,
                                  int acquire_fence,
                                  bool drop_when_acquired = false,
                                  int provided_present_signal_fd = -1,
                                  int provided_release_signal_fd = -1) {
        const bool producer_provided_signals =
                provided_present_signal_fd >= 0 &&
                provided_release_signal_fd >= 0;
        if ((provided_present_signal_fd >= 0) !=
            (provided_release_signal_fd >= 0)) {
            close(acquire_fence);
            if (provided_present_signal_fd >= 0)
                close(provided_present_signal_fd);
            if (provided_release_signal_fd >= 0)
                close(provided_release_signal_fd);
            ++transport_failures_;
            setStatus("direct presenter received incomplete frame signals");
            return false;
        }
        if ((!drop_when_acquired &&
             (direct_surface_control_ == nullptr ||
              direct_surface_control_poisoned_)) ||
            direct_pending_frames_.size() >= kMaxDirectTransactions) {
            close(acquire_fence);
            if (provided_present_signal_fd >= 0)
                close(provided_present_signal_fd);
            if (provided_release_signal_fd >= 0)
                close(provided_release_signal_fd);
            ++transport_failures_;
            setStatus("direct presenter pending-frame bound reached");
            return false;
        }

        const int release_signal_fd = producer_provided_signals
                ? provided_release_signal_fd
                : eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
        const int producer_release_fd = producer_provided_signals
                ? -1 : (release_signal_fd < 0
                        ? -1 : fcntl(release_signal_fd, F_DUPFD_CLOEXEC, 0));
        const int present_signal_fd = producer_provided_signals
                ? provided_present_signal_fd
                : eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
        const int producer_present_fd = producer_provided_signals
                ? -1 : (present_signal_fd < 0
                        ? -1 : fcntl(present_signal_fd, F_DUPFD_CLOEXEC, 0));
        if (release_signal_fd < 0 || present_signal_fd < 0 ||
            (!producer_provided_signals &&
             (producer_release_fd < 0 || producer_present_fd < 0))) {
            if (release_signal_fd >= 0) close(release_signal_fd);
            if (producer_release_fd >= 0) close(producer_release_fd);
            if (present_signal_fd >= 0) close(present_signal_fd);
            if (producer_present_fd >= 0) close(producer_present_fd);
            close(acquire_fence);
            ++fence_failures_;
            setStatus("direct presenter frame-signal setup failed");
            return false;
        }

        const DirectFrameKey current = {
            direct_connection_epoch_,
            packet.resource_id,
            packet.generation,
            packet.frame_id,
        };
        if (direct_pending_frames_.find(current) !=
            direct_pending_frames_.end()) {
            close(acquire_fence);
            close(release_signal_fd);
            close(producer_release_fd);
            close(present_signal_fd);
            close(producer_present_fd);
            ++transport_failures_;
            setStatus("direct presenter received a duplicate frame identity");
            return false;
        }

        if (!producer_provided_signals) {
            const UdroidAhbTransportPacket signals_packet = {
                UDROID_AHB_TRANSPORT_MAGIC,
                packet.version,
                UDROID_AHB_FRAME_SIGNALS,
                0,
                packet.resource_id,
                packet.generation,
                packet.frame_id,
            };
            const bool signals_sent = sendPacketWithTwoFds(
                    transport_sockets_[1], signals_packet,
                    producer_present_fd, producer_release_fd);
            close(producer_present_fd);
            close(producer_release_fd);
            if (!signals_sent) {
                close(acquire_fence);
                close(release_signal_fd);
                close(present_signal_fd);
                ++transport_failures_;
                setStatus("presenter failed to return direct frame signals");
                return false;
            }
        }
        resource->phase = ExternalResourcePhase::kReleasePending;
        resource->last_frame = packet.frame_id;
        traceResourceFrameEvent("frame_signals_sent", packet.resource_id,
                                packet.generation, packet.frame_id);

        DirectFrameState state;
        state.acquire_event_fd = acquire_fence;
        state.signal_event_fd = release_signal_fd;
        state.presentation_signal_event_fd = present_signal_fd;
        state.drop_when_acquired = drop_when_acquired;
        if (!drop_when_acquired) state.control = direct_surface_control_;
        direct_pending_frames_.emplace(current, std::move(state));
        traceResourceFrameEvent("direct_acquire_pending", packet.resource_id,
                                packet.generation, packet.frame_id);
        setStatus(drop_when_acquired
                          ? "Surface detached; waiting to release external frame"
                          : "direct AHardwareBuffer waiting for producer acquire event");
        return true;
    }

    bool submitReadyDirectFrame(const DirectFrameKey &current) {
        auto frame = direct_pending_frames_.find(current);
        if (frame == direct_pending_frames_.end() ||
            frame->second.phase != DirectFramePhase::kAcquirePending ||
            frame->second.acquire_event_fd < 0) {
            return false;
        }

        const int acquire_fence = frame->second.acquire_event_fd;
        frame->second.acquire_event_fd = -1;
        if (!waitNativeFenceOnCpu(acquire_fence)) {
            ++fence_failures_;
            setStatus("direct presenter failed to consume producer acquire event");
            return false;
        }

        if (frame->second.drop_when_acquired ||
            frame->second.control == nullptr ||
            frame->second.control != direct_surface_control_ ||
            direct_surface_control_poisoned_ ||
            external_active_window_ == nullptr ||
            window_surface_ == EGL_NO_SURFACE) {
            ++dropped_detached_frames_;
            setStatus("Surface changed; direct frame released without submission");
            return cancelDirectFrame(current);
        }

        auto resource = external_resources_.find(current.resource_id);
        if (resource == external_resources_.end() ||
            resource->second.generation != current.generation ||
            resource->second.buffer == nullptr ||
            direct_pending_transactions_.size() >=
                    kMaxDirectFrameTransactions) {
            // The acquire has completed and SurfaceFlinger never received this
            // buffer, so resolving the already-sent eventfd is safe.
            cancelDirectFrame(current);
            ++transport_failures_;
            setStatus("direct presenter could not resolve queued resource");
            return false;
        }

        ASurfaceTransaction *transaction =
                surface_control_api_.transaction_create();
        std::unique_ptr<DirectCallbackContext> context(
                new (std::nothrow) DirectCallbackContext());
        if (transaction == nullptr || context == nullptr) {
            if (transaction != nullptr) {
                surface_control_api_.transaction_delete(transaction);
            }
            cancelDirectFrame(current);
            ++transport_failures_;
            setStatus("direct presenter transaction setup failed");
            return false;
        }

        context->bridge = direct_callback_bridge_;
        context->control = frame->second.control;
        context->get_previous_release_fence =
                surface_control_api_.stats_get_previous_release_fence;
        context->get_latch_time = surface_control_api_.stats_get_latch_time;
        context->get_present_fence = surface_control_api_.stats_get_present_fence;
        context->transaction_id = ++direct_next_transaction_id_;
        context->current = current;
        context->has_current = true;
        if (direct_current_valid_) {
            context->previous = direct_current_frame_;
            context->has_previous = true;
        }
        direct_pending_transactions_.emplace(
                context->transaction_id, frame->second.control);
        frame->second.phase = DirectFramePhase::kSurfaceSubmitted;

        int surface_width;
        int surface_height;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            surface_width = surface_width_;
            surface_height = surface_height_;
        }
        const ARect source = {
            0,
            0,
            static_cast<int32_t>(resource->second.description.width),
            static_cast<int32_t>(resource->second.description.height),
        };
        const ARect destination = {0, 0, surface_width, surface_height};
        surface_control_api_.transaction_set_buffer(
                transaction, frame->second.control->control,
                resource->second.buffer, -1);
        if (surface_control_api_.transaction_set_frame_rate != nullptr) {
            // Advertise the producer's exact display cadence so SurfaceFlinger
            // does not infer a lower-rate vote from sparse transaction
            // completion callbacks. EXACT compatibility is value 1 in the
            // public NDK ABI.
            surface_control_api_.transaction_set_frame_rate(
                    transaction, frame->second.control->control, 60.0f, 1);
        }
        surface_control_api_.transaction_set_geometry(
                transaction, frame->second.control->control,
                source, destination, 0);
        surface_control_api_.transaction_set_visibility(
                transaction, frame->second.control->control,
                ASURFACE_TRANSACTION_VISIBILITY_SHOW);
        if (surface_control_api_.transaction_set_enable_back_pressure != nullptr) {
            surface_control_api_.transaction_set_enable_back_pressure(
                    transaction, frame->second.control->control, true);
        }
        surface_control_api_.transaction_set_on_complete(
                transaction, context.release(), onDirectTransactionComplete);
        traceResourceFrameEvent("direct_submit", current.resource_id,
                                current.generation, current.frame_id);
        surface_control_api_.transaction_apply(transaction);
        surface_control_api_.transaction_delete(transaction);
        direct_current_frame_ = current;
        direct_current_valid_ = true;
        direct_detach_transaction_applied_ = false;
        setDirectSurfaceStatus("direct AHB active; asynchronous release");
        setStatus("external AHardwareBuffer submitted through SurfaceControl");
        return true;
    }

    bool drawExternalFrame() {
        for (size_t control_count = 0; control_count < 64; ++control_count) {
            pollfd descriptor = {transport_sockets_[1], POLLIN, 0};
            int poll_result;
            do {
                poll_result = poll(&descriptor, 1, 0);
            } while (poll_result < 0 && errno == EINTR);
            if (poll_result == 0) return true;
            if (poll_result < 0 ||
                (descriptor.revents & (POLLERR | POLLHUP | POLLNVAL)) != 0 ||
                (descriptor.revents & POLLIN) == 0) {
                ++transport_failures_;
                setStatus("external graphics producer disconnected");
                return false;
            }

            UdroidAhbTransportPacket packet = {};
            std::array<int, 3> received_fds;
            size_t received_fd_count = 0;
            if (!receiveExternalPacket(transport_sockets_[1], &packet,
                                       &received_fds, &received_fd_count)) {
                ++transport_failures_;
                setStatus("external graphics producer sent a malformed packet");
                return false;
            }

            if (packet.kind == UDROID_AHB_REGISTER_BUFFER) {
                if (received_fd_count != 0 || packet.resource_id == 0 ||
                    packet.generation == 0 || packet.frame_id != 0) {
                    closeExternalPacketFds(&received_fds);
                    ++transport_failures_;
                    setStatus("external AHardwareBuffer registration is invalid");
                    return false;
                }
                if (!importExternalFrameBuffer(packet)) return false;
                continue;
            }

            auto resource = external_resources_.find(packet.resource_id);
            if (resource == external_resources_.end() ||
                packet.generation != resource->second.generation) {
                closeExternalPacketFds(&received_fds);
                ++transport_failures_;
                setStatus("external packet references an unknown resource generation");
                return false;
            }

            if (packet.kind == UDROID_AHB_REUSE_READY) {
                const DirectFrameKey direct_key = {
                    direct_connection_epoch_,
                    packet.resource_id,
                    packet.generation,
                    packet.frame_id,
                };
                const auto pending_direct = direct_pending_frames_.find(direct_key);
                if (received_fd_count != 0 || packet.frame_id == 0 ||
                    resource->second.phase != ExternalResourcePhase::kReleasePending ||
                    packet.frame_id != resource->second.last_frame ||
                    (pending_direct != direct_pending_frames_.end() &&
                     !pending_direct->second.release_terminal)) {
                    closeExternalPacketFds(&received_fds);
                    ++transport_failures_;
                    setStatus("external reuse acknowledgement is stale or premature");
                    return false;
                }
                resource->second.phase = ExternalResourcePhase::kAvailable;
                traceResourceFrameEvent("reuse_ready", packet.resource_id,
                                        packet.generation, packet.frame_id);
                continue;
            }

            if (packet.kind == UDROID_AHB_RETIRE_BUFFER) {
                if (received_fd_count != 0 || packet.frame_id != 0 ||
                    resource->second.retire_pending) {
                    closeExternalPacketFds(&received_fds);
                    ++transport_failures_;
                    setStatus("external resource retirement is duplicate or invalid");
                    return false;
                }
                if (resource->second.phase ==
                            ExternalResourcePhase::kReleasePending) {
                    resource->second.retire_pending = true;
                    const DirectFrameKey direct_key = {
                        direct_connection_epoch_,
                        packet.resource_id,
                        packet.generation,
                        resource->second.last_frame,
                    };
                    auto direct_frame = direct_pending_frames_.find(direct_key);
                    if (direct_frame == direct_pending_frames_.end()) {
                        // The release already completed (or GLES owns no source
                        // buffer after its copy). The producer has abandoned the
                        // resource, so no REUSE_READY acknowledgement is needed.
                        traceResourceRetirement(packet.resource_id,
                                                packet.generation);
                        destroyExternalResource(resource);
                        continue;
                    }
                    if (direct_frame->second.phase ==
                            DirectFramePhase::kAcquirePending) {
                        // SurfaceFlinger never received this buffer. Wait for
                        // its acquire event, then the normal release path may
                        // signal and retire it safely.
                        direct_frame->second.drop_when_acquired = true;
                        direct_frame->second.control.reset();
                    }
                    const bool is_current =
                            direct_current_valid_ &&
                            direct_current_frame_ == direct_key;
                    if (is_current) {
                        if (!destroyDirectSurfaceControl()) return false;
                        // Retirement removed the old child only after all of
                        // its transactions and release fences drained. Keep a
                        // live producer accelerated by attaching a fresh,
                        // empty child to the unchanged Android Surface.
                        if (transport_sockets_[1] >= 0 &&
                            peer_authenticated_.load() &&
                            direct_surface_control_capable_ &&
                            !direct_surface_control_poisoned_ &&
                            external_active_window_ != nullptr) {
                            createDirectSurfaceControl(external_active_window_);
                        }
                    }
                    setStatus(is_current
                                      ? "external current resource detached for retirement"
                                      : "external resource retirement awaiting release");
                    continue;
                }
                traceResourceRetirement(packet.resource_id, packet.generation);
                destroyExternalResource(resource);
                continue;
            }

            const bool asynchronous_acquire =
                    packet.kind == UDROID_AHB_ACQUIRE_WITH_SIGNALS;
            const size_t expected_fd_count = asynchronous_acquire ? 3 : 1;
            if ((packet.kind != UDROID_AHB_ACQUIRE_FENCE &&
                 !asynchronous_acquire) ||
                received_fd_count != expected_fd_count ||
                packet.frame_id == 0 ||
                resource->second.phase != ExternalResourcePhase::kAvailable ||
                packet.frame_id <= resource->second.last_frame ||
                resource->second.buffer == nullptr || resource->second.texture == 0) {
                closeExternalPacketFds(&received_fds);
                ++transport_failures_;
                setStatus("external acquire fence is stale or resource is unavailable");
                return false;
            }
            int received_fd = received_fds[0];
            int presentation_signal_fd = asynchronous_acquire
                    ? received_fds[1] : -1;
            int release_signal_fd = asynchronous_acquire
                    ? received_fds[2] : -1;
            received_fds.fill(-1);

            active_resource_id_.store(packet.resource_id);
            buffer_generation_.store(packet.generation);
            // These producer-side transitions are inferred from receipt of a
            // matching acquire packet. Final qualification merges Kumquat's
            // independent producer trace with the presenter trace.
            traceResourceFrameEvent("produce_begin", packet.resource_id,
                                    packet.generation, packet.frame_id);
            traceResourceFrameEvent("queue", packet.resource_id,
                                    packet.generation, packet.frame_id);

            if (window_surface_ == EGL_NO_SURFACE) {
                resetExternalFrameTimings();
                return queueDirectExternalFrame(
                        packet, &resource->second, received_fd, true,
                        presentation_signal_fd, release_signal_fd);
            }

            if (direct_surface_control_capable_ &&
                !direct_surface_control_poisoned_ &&
                direct_surface_control_ != nullptr) {
                constexpr uint64_t kDirectUsage =
                        AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
                        AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY;
                if ((resource->second.description.usage & kDirectUsage) ==
                    kDirectUsage) {
                    return queueDirectExternalFrame(
                            packet, &resource->second, received_fd, false,
                            presentation_signal_fd, release_signal_fd);
                }
                if (direct_current_valid_ &&
                    !destroyDirectSurfaceControl()) {
                    close(received_fd);
                    if (presentation_signal_fd >= 0)
                        close(presentation_signal_fd);
                    if (release_signal_fd >= 0)
                        close(release_signal_fd);
                    return false;
                }
                setDirectSurfaceStatus(
                        "buffer lacks sampled/overlay usage; GLES fallback");
            }

            ExternalFrameTimingSample timing;
            SteadyTimePoint stage_started;
            if (contract_trace_) {
                timing.service_started = std::chrono::steady_clock::now();
                timing.thread_cpu_started_us = threadCpuMicros();
                stage_started = timing.service_started;
            }
            if (!makePresenterCurrent(window_surface_)) {
                close(received_fd);
                if (presentation_signal_fd >= 0)
                    signalAndCloseEventFd(&presentation_signal_fd);
                if (release_signal_fd >= 0)
                    signalAndCloseEventFd(&release_signal_fd);
                setStatus("presenter EGL context switch failed");
                return false;
            }
            if (contract_trace_) {
                const SteadyTimePoint stage_completed =
                        std::chrono::steady_clock::now();
                timing.make_current_us = elapsedMicros(stage_started, stage_completed);
                stage_started = stage_completed;
            }
            // Kumquat's non-shareable fence is an eventfd, while Android native
            // fences are sync_file descriptors. Both become readable when the
            // producer is complete, so poll is the common explicit-sync boundary.
            // Importing an eventfd as EGL_SYNC_NATIVE_FENCE_ANDROID is invalid.
            if (!waitNativeFenceOnCpu(received_fd)) {
                if (presentation_signal_fd >= 0)
                    signalAndCloseEventFd(&presentation_signal_fd);
                if (release_signal_fd >= 0)
                    signalAndCloseEventFd(&release_signal_fd);
                ++fence_failures_;
                setStatus("presenter failed to wait for external acquire fence");
                return false;
            }
            if (contract_trace_) {
                const SteadyTimePoint stage_completed =
                        std::chrono::steady_clock::now();
                timing.acquire_wait_us = elapsedMicros(stage_started, stage_completed);
                stage_started = stage_completed;
            }
            traceResourceFrameEvent("present_begin", packet.resource_id,
                                    packet.generation, packet.frame_id);

            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glViewport(0, 0, surface_width_, surface_height_);
            glUseProgram(present_program_);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, resource->second.texture);
            glUniform1i(glGetUniformLocation(present_program_, "uFrame"), 0);
            drawQuad();
            if (contract_trace_) {
                const SteadyTimePoint stage_completed =
                        std::chrono::steady_clock::now();
                timing.draw_us = elapsedMicros(stage_started, stage_completed);
                stage_started = stage_completed;
            }

            const int release_fence = exportNativeFence();
            if (release_fence < 0) {
                if (presentation_signal_fd >= 0)
                    signalAndCloseEventFd(&presentation_signal_fd);
                if (release_signal_fd >= 0)
                    signalAndCloseEventFd(&release_signal_fd);
                ++fence_failures_;
                setStatus("presenter failed to export external release fence");
                return false;
            }
            if (contract_trace_) {
                const SteadyTimePoint stage_completed =
                        std::chrono::steady_clock::now();
                timing.fence_export_us = elapsedMicros(stage_started, stage_completed);
                stage_started = stage_completed;
            }
            if (!asynchronous_acquire) {
                if (!sendExternalRelease(packet, &resource->second,
                                         release_fence)) {
                    return false;
                }
                if (contract_trace_) {
                    const SteadyTimePoint stage_completed =
                            std::chrono::steady_clock::now();
                    timing.socket_send_us =
                            elapsedMicros(stage_started, stage_completed);
                    stage_started = stage_completed;
                }
            } else {
                resource->second.phase = ExternalResourcePhase::kReleasePending;
                resource->second.last_frame = packet.frame_id;
            }
            const bool presented = swapAndRecordFrame();
            if (asynchronous_acquire) {
                const bool presentation_signaled =
                        signalAndCloseEventFd(&presentation_signal_fd);
                const bool release_completed =
                        waitNativeFenceOnCpu(release_fence);
                const bool release_signaled =
                        signalAndCloseEventFd(&release_signal_fd);
                if (!presentation_signaled || !release_completed ||
                    !release_signaled) {
                    ++fence_failures_;
                    setStatus("asynchronous GLES frame completion failed");
                    return false;
                }
                traceResourceFrameEvent("release_sent", packet.resource_id,
                                        packet.generation, packet.frame_id);
            }
            if (contract_trace_ && presented) {
                timing.swap_completed = std::chrono::steady_clock::now();
                timing.swap_us = elapsedMicros(stage_started, timing.swap_completed);
                timing.total_us = elapsedMicros(
                        timing.service_started, timing.swap_completed);
                recordExternalFrameTiming(timing);
            }
            if (presented && frames_.load() == 1) {
                setStatus("external AHardwareBuffer frame presented with explicit fences");
            }
            return presented;
        }
        return true;
    }

    bool drawProbeFrame(float time_seconds) {
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
            traceFrameEvent("reuse_ready", contract_active_frame_);
            contract_active_frame_ = 0;
        }

        contract_active_frame_ = ++contract_next_frame_;
        traceFrameEvent("produce_begin", contract_active_frame_);

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
        traceFrameEvent("queue", contract_active_frame_);

        int presenter_acquire_fence = -1;
        if (!transferFence(UDROID_AHB_ACQUIRE_FENCE,
                           transport_sockets_[0], transport_sockets_[1],
                           acquire_fence, contract_active_frame_,
                           &presenter_acquire_fence)) {
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
        traceFrameEvent("present_begin", contract_active_frame_);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, surface_width_, surface_height_);
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
                           presenter_release_fence, contract_active_frame_,
                           &release_fence_fd_)) {
            setStatus("AHB transport failed to return release fence");
            return false;
        }
        traceFrameEvent("release_sent", contract_active_frame_);

        return swapAndRecordFrame();
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
            } else {
                traceFrameEvent("reuse_ready", contract_active_frame_);
                contract_active_frame_ = 0;
            }
        }
        traceRetirement();
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
        destroyDirectSurfaceControl();
        if (display_ == EGL_NO_DISPLAY) return;
        if (producer_mode_ == ProducerMode::kInternalProbe) {
            destroyFrameBuffer();
        }
        makePresenterCurrent(presenter_pbuffer_);
        if (window_surface_ != EGL_NO_SURFACE) {
            eglDestroySurface(display_, window_surface_);
            window_surface_ = EGL_NO_SURFACE;
        }
    }

    void handoffPendingDirectDrain() {
        if (direct_pending_frames_.empty() &&
            direct_pending_transactions_.empty()) {
            return;
        }
        if (direct_current_valid_ || !direct_detach_transaction_applied_) {
            LOGE("direct drain handoff refused without an applied detach transaction");
            std::abort();
        }
        // runExternalEventLoop has already detached the child. Any acquire
        // which never reached SurfaceFlinger may now only be dropped after its
        // producer event becomes readable.
        for (auto &frame : direct_pending_frames_) {
            if (frame.second.phase == DirectFramePhase::kAcquirePending) {
                frame.second.drop_when_acquired = true;
                frame.second.control.reset();
            }
        }
        if (!DirectDrainSupervisor::instance().adopt(
                    std::move(direct_callback_bridge_),
                    &direct_pending_frames_,
                    &direct_pending_transactions_)) {
            LOGE("unable to safely adopt dormant direct drain state; aborting");
            std::abort();
        }
        direct_surface_control_.reset();
        direct_current_valid_ = false;
        direct_disconnect_cleanup_pending_ = false;
    }

    void destroyEgl() {
        destroyWindowSurface();
        if (direct_pending_frames_.empty() &&
            direct_pending_transactions_.empty()) {
            destroyAllExternalResources();
        } else {
            LOGE("direct AHB teardown incomplete; retaining SurfaceFlinger-owned "
                 "resources until process exit");
        }
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
        if (wake_fd_ >= 0) {
            close(wake_fd_);
            wake_fd_ = -1;
        }
        surface_control_api_.reset();
        if (!transport_path_.empty()) unlink(transport_path_.c_str());
    }

    void applyExternalSurfaceChanges(ANativeWindow **active_window) {
        ANativeWindow *replacement = nullptr;
        bool replace_window = false;
        bool resize = false;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (window_changed_) {
                replacement = pending_window_;
                pending_window_ = nullptr;
                window_changed_ = false;
                replace_window = true;
            }
            resize = resize_requested_;
            resize_requested_ = false;
        }

        if (replace_window) {
            resetExternalFrameTimings();
            destroyWindowSurface();
            if (*active_window != nullptr) ANativeWindow_release(*active_window);
            *active_window = replacement;
            external_active_window_ = replacement;
            if (*active_window != nullptr && !createWindowSurface(*active_window)) {
                destroyWindowSurface();
            } else if (*active_window == nullptr) {
                {
                    std::lock_guard<std::mutex> lock(mutex_);
                    surface_width_ = 0;
                    surface_height_ = 0;
                }
                setStatus("Surface detached; external frames will be released");
            }
        } else if (resize && *active_window != nullptr &&
                   window_surface_ != EGL_NO_SURFACE) {
            updateWindowGeometry(*active_window);
        }
    }

    void runExternalEventLoop() {
        ANativeWindow *active_window = nullptr;
        while (!stopping_.load()) {
            std::vector<pollfd> descriptors = {
                {wake_fd_, POLLIN, 0},
                {transport_sockets_[1] < 0 ? listener_socket_ : -1, POLLIN, 0},
                {transport_sockets_[1], POLLIN, 0},
                {direct_callback_bridge_ == nullptr
                         ? -1
                         : direct_callback_bridge_->wake_fd,
                 POLLIN, 0},
            };
            std::vector<DirectFrameKey> acquire_keys;
            std::vector<DirectFrameKey> release_keys;
            for (const auto &frame : direct_pending_frames_) {
                if (frame.second.phase == DirectFramePhase::kAcquirePending &&
                    frame.second.acquire_event_fd >= 0) {
                    descriptors.push_back(
                            {frame.second.acquire_event_fd, POLLIN, 0});
                    acquire_keys.push_back(frame.first);
                }
            }
            const size_t release_start = descriptors.size();
            for (const auto &frame : direct_pending_frames_) {
                if (frame.second.phase ==
                            DirectFramePhase::kSurfaceReleasePending &&
                    frame.second.surface_release_fence_fd >= 0) {
                    descriptors.push_back(
                            {frame.second.surface_release_fence_fd, POLLIN, 0});
                    release_keys.push_back(frame.first);
                }
            }
            int result;
            do {
                result = poll(descriptors.data(), descriptors.size(),
                              kDirectRecoveryPollMillis);
            } while (result < 0 && errno == EINTR);
            if (result < 0) {
                ++transport_failures_;
                setStatus("external presenter event loop failed");
                break;
            }
            if (result == 0) {
                const bool direct_valid =
                        direct_callback_bridge_ == nullptr ||
                        drainDirectCompletionQueue();
                if (!direct_valid) {
                    direct_surface_control_poisoned_ = true;
                    setDirectSurfaceStatus(
                            "callback wake recovery failed; direct route poisoned");
                    ++fence_failures_;
                    closeExternalProducer(nullptr);
                }
                finishDeferredDirectDisconnectCleanup();
                continue;
            }

            if ((descriptors[0].revents & POLLIN) != 0) {
                drainExternalWake();
                if (stopping_.load()) break;
                applyExternalSurfaceChanges(&active_window);
                continue;
            }

            bool direct_valid =
                    direct_callback_bridge_ == nullptr ||
                    !direct_callback_bridge_->wake_failed.load(
                            std::memory_order_acquire);
            if ((descriptors[3].revents & POLLIN) != 0) {
                direct_valid = drainDirectCompletionQueue();
            } else if ((descriptors[3].revents &
                        (POLLERR | POLLHUP | POLLNVAL)) != 0) {
                direct_valid = false;
            }
            for (size_t index = 4; index < release_start; ++index) {
                if ((descriptors[index].revents & POLLIN) != 0) {
                    direct_valid = submitReadyDirectFrame(
                                           acquire_keys[index - 4]) &&
                                   direct_valid;
                } else if ((descriptors[index].revents &
                            (POLLERR | POLLHUP | POLLNVAL)) != 0) {
                    direct_valid = false;
                }
            }
            for (size_t index = release_start;
                 index < descriptors.size(); ++index) {
                const size_t key_index = index - release_start;
                auto frame = direct_pending_frames_.find(
                        release_keys[key_index]);
                if (frame == direct_pending_frames_.end()) continue;
                if ((descriptors[index].revents & POLLIN) != 0) {
                    direct_valid =
                            signalDirectRelease(release_keys[key_index]) &&
                            direct_valid;
                } else if ((descriptors[index].revents &
                            (POLLERR | POLLHUP | POLLNVAL)) != 0) {
                    if (frame->second.surface_release_fence_fd >= 0) {
                        close(frame->second.surface_release_fence_fd);
                        frame->second.surface_release_fence_fd = -1;
                    }
                    direct_valid = false;
                }
            }
            if (!direct_valid) {
                direct_surface_control_poisoned_ = true;
                setDirectSurfaceStatus(
                        "callback or release fence failed; direct route poisoned");
                ++fence_failures_;
                closeExternalProducer(nullptr);
                continue;
            }
            finishDeferredDirectDisconnectCleanup();

            if ((descriptors[1].revents &
                 (POLLERR | POLLHUP | POLLNVAL)) != 0) {
                ++transport_failures_;
                setStatus("graphics presenter listener closed unexpectedly");
                break;
            }
            if ((descriptors[1].revents & POLLIN) != 0) {
                acceptExternalProducer();
            }

            if (transport_sockets_[1] >= 0 &&
                (descriptors[2].revents &
                 (POLLERR | POLLHUP | POLLNVAL)) != 0) {
                closeExternalProducer("external graphics producer disconnected");
                continue;
            }
            if (transport_sockets_[1] >= 0 &&
                (descriptors[2].revents & POLLIN) != 0 &&
                !drawExternalFrame()) {
                // Closing the peer is mandatory once a packet has been consumed:
                // a synchronous guest receive must observe failure, not deadlock.
                closeExternalProducer(nullptr);
            }
        }

        closeExternalProducer(nullptr);
        destroyWindowSurface();
        external_active_window_ = nullptr;
        if (active_window != nullptr) ANativeWindow_release(active_window);
    }

    void run() {
        if (!initializeEgl()) {
            destroyEgl();
            return;
        }
        if (producer_mode_ == ProducerMode::kExternalSupervisor) {
            fps_sample_started_ = std::chrono::steady_clock::now();
            runExternalEventLoop();
            handoffPendingDirectDrain();
            destroyEgl();
            return;
        }
        ANativeWindow *active_window = nullptr;
        fps_sample_started_ = std::chrono::steady_clock::now();
        int64_t first_frame_time_nanos = 0;

        while (true) {
            ANativeWindow *replacement = nullptr;
            bool replace_window = false;
            bool resize = false;
            bool draw_requested = false;
            int64_t frame_time_nanos = 0;
            {
                std::unique_lock<std::mutex> lock(mutex_);
                condition_.wait(lock, [this] {
                    return stopping_.load() || window_changed_ || resize_requested_ ||
                           frame_pending_;
                });
                if (stopping_.load()) break;
                if (window_changed_) {
                    replacement = pending_window_;
                    pending_window_ = nullptr;
                    window_changed_ = false;
                    replace_window = true;
                }
                resize = resize_requested_;
                resize_requested_ = false;
                draw_requested = frame_pending_;
                frame_time_nanos = pending_frame_time_nanos_;
                frame_pending_ = false;
            }

            if (replace_window) {
                destroyWindowSurface();
                if (active_window != nullptr) ANativeWindow_release(active_window);
                active_window = replacement;
                if (active_window != nullptr && !createWindowSurface(active_window)) {
                    destroyWindowSurface();
                }
            } else if (resize && active_window != nullptr &&
                       window_surface_ != EGL_NO_SURFACE) {
                if (producer_mode_ == ProducerMode::kInternalProbe) {
                    recreateFrameBuffer(active_window);
                } else {
                    updateWindowGeometry(active_window);
                }
            }

            if (active_window == nullptr || window_surface_ == EGL_NO_SURFACE ||
                !draw_requested) {
                continue;
            }

            if (frame_buffer_ == nullptr) continue;

            if (first_frame_time_nanos == 0) first_frame_time_nanos = frame_time_nanos;
            const float seconds = static_cast<float>(
                    frame_time_nanos - first_frame_time_nanos) / 1000000000.0f;
            if (!drawProbeFrame(seconds)) {
                continue;
            }
            if (resource_cycle_frames_ > 0 &&
                contract_next_frame_ % resource_cycle_frames_ == 0) {
                recreateFrameBuffer(active_window);
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
    const ProducerMode producer_mode_;
    const bool contract_trace_;
    const bool direct_surface_control_requested_;
    const uint32_t resource_cycle_frames_;
    mutable std::mutex mutex_;
    std::condition_variable condition_;
    std::thread worker_;
    std::atomic<bool> stopping_{false};
    bool window_changed_ = false;
    bool resize_requested_ = false;
    bool frame_pending_ = false;
    int64_t pending_frame_time_nanos_ = 0;
    ANativeWindow *pending_window_ = nullptr;
    uint32_t surface_generation_ = 0;
    int surface_width_ = 0;
    int surface_height_ = 0;
    int frame_width_ = 0;
    int frame_height_ = 0;
    std::string status_ = "starting EGL presenter";
    std::string renderer_ = "initializing";
    std::string buffer_identity_ = "pending";
    std::string direct_surface_status_ = "disabled; GLES presenter";
    std::atomic<uint64_t> frames_{0};
    std::atomic<uint64_t> fps_milli_{0};
    std::atomic<uint64_t> swap_failures_{0};
    std::atomic<uint64_t> dropped_detached_frames_{0};
    std::atomic<uint64_t> fence_failures_{0};
    std::atomic<uint64_t> transport_failures_{0};
    std::chrono::steady_clock::time_point fps_sample_started_;
    int transport_sockets_[2] = {-1, -1};
    int listener_socket_ = -1;
    int wake_fd_ = -1;
    std::atomic<int64_t> peer_uid_{-1};
    std::atomic<bool> peer_authenticated_{false};
    uint64_t next_resource_id_ = 0;
    std::atomic<uint64_t> active_resource_id_{0};
    std::atomic<uint64_t> buffer_generation_{0};
    uint64_t contract_next_frame_ = 0;
    uint64_t contract_active_frame_ = 0;
    bool contract_resource_registered_ = false;
    std::map<uint64_t, ExternalResource> external_resources_;
    std::map<uint64_t, uint64_t> external_latest_generations_;
    ExternalFrameTimingWindow external_frame_timings_;
    SurfaceControlApi surface_control_api_;
    std::shared_ptr<DirectCallbackBridge> direct_callback_bridge_;
    std::shared_ptr<DirectControlHandle> direct_surface_control_;
    std::map<DirectFrameKey, DirectFrameState> direct_pending_frames_;
    std::map<uint64_t, std::shared_ptr<DirectControlHandle>>
            direct_pending_transactions_;
    DirectFrameKey direct_current_frame_;
    uint64_t direct_connection_epoch_ = 0;
    uint64_t direct_next_transaction_id_ = 0;
    bool direct_current_valid_ = false;
    bool direct_detach_transaction_applied_ = true;
    bool direct_surface_control_capable_ = false;
    bool direct_surface_control_poisoned_ = false;
    bool direct_disconnect_cleanup_pending_ = false;
    ANativeWindow *external_active_window_ = nullptr;

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
        JNIEnv *env, jobject, jstring socket_path, jboolean external_producer,
        jboolean contract_trace, jboolean direct_surface_control,
        jint resource_cycle_frames) {
    if (socket_path == nullptr) return 0;
    const char *path = env->GetStringUTFChars(socket_path, nullptr);
    if (path == nullptr) return 0;
    std::string transport_path(path);
    env->ReleaseStringUTFChars(socket_path, path);
    return static_cast<jlong>(
            reinterpret_cast<intptr_t>(new Presenter(
                    std::move(transport_path),
                    external_producer == JNI_TRUE
                            ? ProducerMode::kExternalSupervisor
                            : ProducerMode::kInternalProbe,
                    contract_trace == JNI_TRUE,
                    direct_surface_control == JNI_TRUE,
                    resource_cycle_frames > 0
                            ? static_cast<uint32_t>(resource_cycle_frames)
                            : 0)));
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

extern "C" JNIEXPORT void JNICALL
Java_org_randomcoder_udroid_gfxstream_AhbSurfacePresenterView_nativeDoFrame(
        JNIEnv *, jobject, jlong handle, jlong frame_time_nanos) {
    Presenter *presenter = fromHandle(handle);
    if (presenter != nullptr) presenter->doFrame(frame_time_nanos);
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
