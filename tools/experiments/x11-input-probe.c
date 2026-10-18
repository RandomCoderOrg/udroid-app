#include <X11/Xlib.h>
#include <X11/keysym.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

extern Bool XTestFakeKeyEvent(Display *, unsigned int, Bool, unsigned long);
extern Bool XTestFakeMotionEvent(Display *, int, int, int, unsigned long);
extern Bool XTestFakeButtonEvent(Display *, unsigned int, Bool, unsigned long);

int
main(int argc, char **argv)
{
    Display *display = XOpenDisplay(NULL);

    if (!display) {
        fputs("could not open DISPLAY\n", stderr);
        return 1;
    }

    if (argc == 4 && strcmp(argv[1], "move") == 0) {
        if (!XTestFakeMotionEvent(display, DefaultScreen(display), atoi(argv[2]), atoi(argv[3]), 0))
            return 2;
    } else if (argc == 2 && strcmp(argv[1], "click") == 0) {
        if (!XTestFakeButtonEvent(display, 1, True, 0) ||
            !XTestFakeButtonEvent(display, 1, False, 0))
            return 2;
    } else if (argc == 2 && strcmp(argv[1], "show-apps") == 0) {
        KeyCode super = XKeysymToKeycode(display, XK_Super_L);
        KeyCode a = XKeysymToKeycode(display, XK_a);

        if (!super || !a ||
            !XTestFakeKeyEvent(display, super, True, 0) ||
            !XTestFakeKeyEvent(display, a, True, 0) ||
            !XTestFakeKeyEvent(display, a, False, 0) ||
            !XTestFakeKeyEvent(display, super, False, 0))
            return 2;
    } else {
        fprintf(stderr, "usage: %s move X Y | click | show-apps\n", argv[0]);
        return 2;
    }

    XSync(display, False);
    XCloseDisplay(display);
    return 0;
}
