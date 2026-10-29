#define _GNU_SOURCE

#include <execinfo.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

#define TRACE_SIGNAL (SIGRTMIN + 7)

static void
write_backtrace(int signal_number)
{
   void *frames[96];
   int count = backtrace(frames, 96);
   static const char start[] = "UDROID_SIGNAL_BACKTRACE start\n";
   static const char end[] = "UDROID_SIGNAL_BACKTRACE end\n";

   (void)signal_number;
   ssize_t unused = write(STDERR_FILENO, start, sizeof(start) - 1);
   backtrace_symbols_fd(frames, count, STDERR_FILENO);
   unused = write(STDERR_FILENO, end, sizeof(end) - 1);
   (void)unused;
}

__attribute__((constructor)) static void
install_backtrace_signal(void)
{
   char executable[256];
   ssize_t length = readlink("/proc/self/exe", executable,
                             sizeof(executable) - 1);
   if (length < 0)
      return;

   executable[length] = '\0';
   const char *name = strrchr(executable, '/');
   name = name ? name + 1 : executable;
   if (strcmp(name, "gnome-shell") != 0)
      return;

   /* Prime the unwinder before a diagnostic signal interrupts the process. */
   void *frame;
   backtrace(&frame, 1);

   struct sigaction action = {
      .sa_handler = write_backtrace,
      .sa_flags = SA_RESTART,
   };
   sigemptyset(&action.sa_mask);
   sigaction(TRACE_SIGNAL, &action, NULL);
}
