#include <errno.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
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

int main(int argc, char **argv) {
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
