#include <errno.h>
#include <signal.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/prctl.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>

static volatile sig_atomic_t running = 1;

static void stop_probe(int signal_number) {
    (void)signal_number;
    running = 0;
}

static int64_t monotonic_ms(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) {
        return -1;
    }
    return ((int64_t)ts.tv_sec * 1000) + (ts.tv_nsec / 1000000);
}

static int read_exactly(int fd, uint8_t *buffer, size_t count) {
    size_t offset = 0;
    while (offset < count) {
        ssize_t received = read(fd, buffer + offset, count - offset);
        if (received < 0 && errno == EINTR) {
            continue;
        }
        if (received <= 0) {
            return -1;
        }
        offset += (size_t)received;
    }
    return 0;
}

static int probe_x11_socket(const char *path, int force_denied,
                            int abstract_socket, const char *socket_namespace) {
    static const uint8_t setup_request[] = {
        0x6c, 0x00, 0x0b, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    };
    uint8_t setup_header[8];
    struct sockaddr_un address;
    socklen_t address_size;
    struct timeval timeout = {.tv_sec = 1, .tv_usec = 0};
    const int64_t started_ms = monotonic_ms();

    if (force_denied) {
        printf("{\"event\":\"x11_guest_probe\",\"status\":\"connect_failed\","
               "\"socket_namespace\":\"%s\",\"errno\":%d,"
               "\"address_bytes\":0,\"elapsed_ms\":0,"
               "\"detail\":\"Permission denied (injected)\"}\n",
               socket_namespace, EACCES);
        return 20;
    }
    if (strlen(path) >= sizeof(address.sun_path)) {
        printf("{\"event\":\"x11_guest_probe\",\"status\":\"invalid_path\","
               "\"socket_namespace\":\"%s\",\"errno\":%d,"
               "\"address_bytes\":0,\"elapsed_ms\":%lld,"
               "\"detail\":\"Socket path is too long\"}\n",
               socket_namespace, ENAMETOOLONG,
               (long long)(monotonic_ms() - started_ms));
        return 21;
    }

    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) {
        printf("{\"event\":\"x11_guest_probe\",\"status\":\"socket_failed\","
               "\"socket_namespace\":\"%s\",\"errno\":%d,"
               "\"address_bytes\":0,\"elapsed_ms\":%lld,\"detail\":\"%s\"}\n",
               socket_namespace, errno,
               (long long)(monotonic_ms() - started_ms), strerror(errno));
        return 22;
    }
    (void)setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
    (void)setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));

    memset(&address, 0, sizeof(address));
    address.sun_family = AF_UNIX;
    if (abstract_socket) {
        size_t path_length = strlen(path);
        address.sun_path[0] = '\0';
        memcpy(address.sun_path + 1, path, path_length);
        address_size = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + path_length);
    } else {
        memcpy(address.sun_path, path, strlen(path) + 1);
        /* libxcb 1.14 passes the complete structure for filesystem sockets. */
        address_size = sizeof(address);
    }
    if (connect(fd, (struct sockaddr *)&address, address_size) != 0) {
        int saved_errno = errno;
        printf("{\"event\":\"x11_guest_probe\",\"status\":\"connect_failed\","
               "\"socket_namespace\":\"%s\",\"errno\":%d,"
               "\"address_bytes\":%u,\"elapsed_ms\":%lld,\"detail\":\"%s\"}\n",
               socket_namespace, saved_errno, (unsigned int)address_size,
               (long long)(monotonic_ms() - started_ms), strerror(saved_errno));
        close(fd);
        return 23;
    }
    if (write(fd, setup_request, sizeof(setup_request)) != (ssize_t)sizeof(setup_request) ||
        read_exactly(fd, setup_header, sizeof(setup_header)) != 0) {
        int saved_errno = errno;
        printf("{\"event\":\"x11_guest_probe\",\"status\":\"handshake_failed\","
               "\"socket_namespace\":\"%s\",\"errno\":%d,"
               "\"address_bytes\":%u,\"elapsed_ms\":%lld,\"detail\":\"%s\"}\n",
               socket_namespace, saved_errno, (unsigned int)address_size,
               (long long)(monotonic_ms() - started_ms),
               saved_errno == 0 ? "Connection closed" : strerror(saved_errno));
        close(fd);
        return 24;
    }
    close(fd);

    unsigned int protocol_major =
        (unsigned int)setup_header[2] | ((unsigned int)setup_header[3] << 8U);
    unsigned int protocol_minor =
        (unsigned int)setup_header[4] | ((unsigned int)setup_header[5] << 8U);
    if (setup_header[0] != 1) {
        printf("{\"event\":\"x11_guest_probe\",\"status\":\"rejected\","
               "\"socket_namespace\":\"%s\",\"setup_status\":%u,"
               "\"protocol_major\":%u,\"protocol_minor\":%u,"
               "\"address_bytes\":%u,\"elapsed_ms\":%lld}\n",
               socket_namespace, (unsigned int)setup_header[0], protocol_major, protocol_minor,
               (unsigned int)address_size, (long long)(monotonic_ms() - started_ms));
        return 25;
    }
    printf("{\"event\":\"x11_guest_probe\",\"status\":\"ready\","
           "\"socket_namespace\":\"%s\",\"protocol_major\":%u,"
           "\"protocol_minor\":%u,\"address_bytes\":%u,\"elapsed_ms\":%lld}\n",
           socket_namespace, protocol_major, protocol_minor, (unsigned int)address_size,
           (long long)(monotonic_ms() - started_ms));
    return 0;
}

int main(int argc, char **argv) {
    if (argc >= 3 && strcmp(argv[1], "--x11-abstract") == 0) {
        setvbuf(stdout, NULL, _IOLBF, 0);
        return probe_x11_socket(argv[2], 0, 1, "abstract");
    }
    if (argc >= 3 &&
        (strcmp(argv[1], "--x11") == 0 || strcmp(argv[1], "--x11-deny") == 0)) {
        setvbuf(stdout, NULL, _IOLBF, 0);
        return probe_x11_socket(argv[2], strcmp(argv[1], "--x11-deny") == 0,
                                0, "filesystem");
    }

    const char *boot_id = argc > 1 ? argv[1] : "unknown";
    const pid_t original_parent = getppid();

    signal(SIGTERM, stop_probe);
    signal(SIGINT, stop_probe);
    prctl(PR_SET_PDEATHSIG, SIGTERM);
    if (getppid() != original_parent) {
        return 2;
    }

    setvbuf(stdout, NULL, _IOLBF, 0);
    printf("{\"event\":\"probe_started\",\"boot_id\":\"%s\",\"pid\":%d,\"ppid\":%d}\n",
           boot_id, getpid(), getppid());

    unsigned long sequence = 0;
    while (running) {
        printf("{\"event\":\"heartbeat\",\"boot_id\":\"%s\",\"sequence\":%lu,\"monotonic_ms\":%lld}\n",
               boot_id, sequence++, (long long)monotonic_ms());

        struct timespec delay = {.tv_sec = 1, .tv_nsec = 0};
        while (running && nanosleep(&delay, &delay) != 0 && errno == EINTR) {
        }
    }

    printf("{\"event\":\"probe_stopped\",\"boot_id\":\"%s\",\"sequence\":%lu}\n",
           boot_id, sequence);
    return 0;
}
