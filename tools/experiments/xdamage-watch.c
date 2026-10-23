#include <X11/Xlib.h>
#include <X11/extensions/Xdamage.h>

#include <errno.h>
#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>
#include <time.h>

int main(int argc, char **argv) {
    Display *display;
    Window window;
    Damage damage;
    XEvent event;
    int damage_event;
    int damage_error;
    unsigned long count = 0;
    time_t deadline;

    if (argc != 3) {
        fprintf(stderr, "usage: %s WINDOW_ID SECONDS\n", argv[0]);
        return 2;
    }

    errno = 0;
    window = (Window)strtoul(argv[1], NULL, 0);
    deadline = time(NULL) + strtol(argv[2], NULL, 10);
    if (errno || !window || deadline <= time(NULL))
        return 2;

    display = XOpenDisplay(NULL);
    if (!display || !XDamageQueryExtension(display, &damage_event, &damage_error))
        return 3;

    damage = XDamageCreate(display, window, XDamageReportRawRectangles);
    XSync(display, False);
    while (time(NULL) < deadline) {
        while (XPending(display)) {
            XNextEvent(display, &event);
            if (event.type == damage_event + XDamageNotify) {
                XDamageNotifyEvent *notify = (XDamageNotifyEvent *)&event;
                printf("damage=%lu drawable=0x%lx area=%dx%d+%d+%d\n",
                       ++count, notify->drawable, notify->area.width,
                       notify->area.height, notify->area.x, notify->area.y);
            }
        }
        struct timespec pause = {.tv_nsec = 10000000};
        nanosleep(&pause, NULL);
    }

    printf("total=%lu\n", count);
    XDamageDestroy(display, damage);
    XCloseDisplay(display);
    return 0;
}
