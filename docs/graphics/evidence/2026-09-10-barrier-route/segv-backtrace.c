#define _GNU_SOURCE

#include <signal.h>
#include <stddef.h>
#include <stdint.h>
#include <unistd.h>

#if defined(__linux__) && defined(__aarch64__)
#include <ucontext.h>
#endif

static void write_hex(const char *name, size_t name_length, uintptr_t value)
{
    static const char hex[] = "0123456789abcdef";
    char line[32];
    size_t i;

    for (i = 0; i < name_length; i++)
        line[i] = name[i];
    line[name_length++] = '=';
    line[name_length++] = '0';
    line[name_length++] = 'x';
    for (i = 0; i < sizeof(uintptr_t) * 2; i++)
        line[name_length + i] =
            hex[(value >> ((sizeof(uintptr_t) * 2 - i - 1) * 4)) & 0xf];
    name_length += sizeof(uintptr_t) * 2;
    line[name_length++] = '\n';
    (void)write(STDERR_FILENO, line, name_length);
}

#define WRITE_HEX(name, value) write_hex(name, sizeof(name) - 1, (uintptr_t)(value))

static void crash_handler(int signal_number, siginfo_t *info, void *context)
{
    WRITE_HEX("signal", signal_number);
    WRITE_HEX("fault", info ? info->si_addr : 0);
#if defined(__linux__) && defined(__aarch64__)
    ucontext_t *ucontext = context;

    WRITE_HEX("pc", ucontext->uc_mcontext.pc);
    WRITE_HEX("sp", ucontext->uc_mcontext.sp);
    WRITE_HEX("x0", ucontext->uc_mcontext.regs[0]);
    WRITE_HEX("x1", ucontext->uc_mcontext.regs[1]);
    WRITE_HEX("x2", ucontext->uc_mcontext.regs[2]);
    WRITE_HEX("x3", ucontext->uc_mcontext.regs[3]);
    WRITE_HEX("x4", ucontext->uc_mcontext.regs[4]);
    WRITE_HEX("x5", ucontext->uc_mcontext.regs[5]);
    WRITE_HEX("x23", ucontext->uc_mcontext.regs[23]);
    WRITE_HEX("x25", ucontext->uc_mcontext.regs[25]);
#else
    (void)context;
#endif
    _exit(128 + signal_number);
}

__attribute__((constructor)) static void install_crash_handlers(void)
{
    struct sigaction action = {
        .sa_sigaction = crash_handler,
        .sa_flags = SA_SIGINFO | SA_RESETHAND,
    };

    sigemptyset(&action.sa_mask);
    (void)sigaction(SIGSEGV, &action, NULL);
    (void)sigaction(SIGABRT, &action, NULL);
}
