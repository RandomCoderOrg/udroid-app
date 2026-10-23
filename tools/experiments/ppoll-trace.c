#define _GNU_SOURCE

#include <dlfcn.h>
#include <execinfo.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

static int (*real_poll)(struct pollfd *, nfds_t, int);
static int (*real_ppoll)(struct pollfd *, nfds_t, const struct timespec *,
                         const sigset_t *);
static pthread_t main_thread;
static __thread int tracing;
static __thread unsigned int traced_waits;

__attribute__((constructor)) static void
remember_main_thread(void)
{
   main_thread = pthread_self();
}

static int
is_gnome_shell(void)
{
   char executable[256];
   ssize_t length = readlink("/proc/self/exe", executable, sizeof(executable) - 1);

   if (length < 0)
      return 0;
   executable[length] = '\0';

   const char *name = strrchr(executable, '/');
   return strcmp(name ? name + 1 : executable, "gnome-shell") == 0;
}

static void
trace_wait(const char *name, const struct pollfd *fds, nfds_t count, int indefinite)
{
   if (tracing || !indefinite || count != 1 || traced_waits >= 512 ||
       !pthread_equal(pthread_self(), main_thread) || !is_gnome_shell())
      return;

   tracing = 1;
   traced_waits++;

   void *frames[48];
   int frame_count;

   dprintf(STDERR_FILENO,
           "UDROID_POLL_TRACE %s seq=%u pid=%d fd=%d events=0x%x\n",
           name, traced_waits, getpid(), fds[0].fd, fds[0].events);
   frame_count = backtrace(frames, 48);
   backtrace_symbols_fd(frames, frame_count, STDERR_FILENO);
   dprintf(STDERR_FILENO, "UDROID_POLL_TRACE end\n");

   tracing = 0;
}

int
poll(struct pollfd *fds, nfds_t count, int timeout)
{
   if (!real_poll)
      real_poll = dlsym(RTLD_NEXT, "poll");
   trace_wait("poll", fds, count, timeout < 0);
   return real_poll(fds, count, timeout);
}

int
ppoll(struct pollfd *fds, nfds_t count, const struct timespec *timeout,
      const sigset_t *sigmask)
{
   if (!real_ppoll)
      real_ppoll = dlsym(RTLD_NEXT, "ppoll");
   trace_wait("ppoll", fds, count, timeout == NULL);
   return real_ppoll(fds, count, timeout, sigmask);
}
