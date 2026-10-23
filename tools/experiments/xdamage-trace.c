#define _GNU_SOURCE
#include <X11/Xlib.h>
#include <X11/extensions/Xdamage.h>

#include <dlfcn.h>
#include <stdio.h>
#include <time.h>

static FILE *trace_file(void) {
    static FILE *file;
    if (!file)
        file = fopen("/tmp/udroid-xdamage-trace.log", "a");
    return file;
}

static void trace(const char *operation, unsigned long drawable,
                  unsigned long damage, int level) {
    FILE *file = trace_file();
    struct timespec now;
    if (!file)
        return;
    clock_gettime(CLOCK_MONOTONIC, &now);
    fprintf(file, "%lld.%09ld %s drawable=0x%lx damage=0x%lx level=%d\n",
            (long long)now.tv_sec, now.tv_nsec, operation, drawable, damage,
            level);
    fflush(file);
}

Damage XDamageCreate(Display *display, Drawable drawable, int level) {
    static Damage (*next)(Display *, Drawable, int);
    Damage damage;
    if (!next)
        next = dlsym(RTLD_NEXT, "XDamageCreate");
    damage = next(display, drawable, level);
    trace("create", drawable, damage, level);
    return damage;
}

void XDamageSubtract(Display *display, Damage damage, XserverRegion repair,
                     XserverRegion parts) {
    static void (*next)(Display *, Damage, XserverRegion, XserverRegion);
    if (!next)
        next = dlsym(RTLD_NEXT, "XDamageSubtract");
    trace("subtract", repair, damage, 0);
    next(display, damage, repair, parts);
}

void XDamageDestroy(Display *display, Damage damage) {
    static void (*next)(Display *, Damage);
    if (!next)
        next = dlsym(RTLD_NEXT, "XDamageDestroy");
    trace("destroy", 0, damage, 0);
    next(display, damage);
}
