#define _POSIX_C_SOURCE 200809L

#include <X11/Xatom.h>
#include <X11/Xlib.h>
#include <X11/Xutil.h>
#include <X11/extensions/XInput2.h>
#include <X11/keysym.h>

#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define APP_NAME "uDroid Touchscope"
#define MAX_CONTACTS 20
#define MAX_TOUCH_DEVICES 16
#define MAX_DEVICE_NAME 96
#define HEADER_HEIGHT 142
#define CONTACT_RADIUS 42

typedef struct {
    bool active;
    bool recovered;
    int id;
    int device_id;
    double x;
    double y;
    uint64_t updates;
    uint64_t began_ns;
    uint64_t last_ns;
} Contact;

typedef struct {
    int id;
    int mode;
    int max_touches;
    char name[MAX_DEVICE_NAME];
} TouchDevice;

typedef struct {
    uint64_t begins;
    uint64_t updates;
    uint64_t ends;
    uint64_t sequence_errors;
    uint64_t dropped_contacts;
    unsigned active_contacts;
    unsigned maximum_contacts;
    uint64_t rate_window_started_ns;
    uint64_t rate_window_updates;
    uint64_t updates_per_second;
    uint64_t rendered_frames;
    uint64_t touch_batches;
    uint64_t pending_touch_events;
    uint64_t maximum_events_per_draw;
    char last_event[80];
    char last_device[MAX_DEVICE_NAME];
} Diagnostics;

typedef struct {
    Display *display;
    int screen;
    int xi_opcode;
    Window root;
    Window window;
    Atom wm_delete;
    GC gc;
    Pixmap back_buffer;
    unsigned width;
    unsigned height;
    unsigned long background;
    unsigned long foreground;
    unsigned long muted;
    unsigned long warning;
    unsigned long contact_colors[8];
    bool verbose;
    bool fullscreen;
    Contact contacts[MAX_CONTACTS];
    TouchDevice devices[MAX_TOUCH_DEVICES];
    int device_count;
    Diagnostics diagnostics;
} App;

static uint64_t monotonic_ns(void) {
    struct timespec timestamp;
    if (clock_gettime(CLOCK_MONOTONIC, &timestamp) != 0) {
        return 0;
    }
    return (uint64_t) timestamp.tv_sec * 1000000000ULL +
            (uint64_t) timestamp.tv_nsec;
}

static unsigned long named_color(
        Display *display,
        int screen,
        const char *name,
        unsigned long fallback
) {
    XColor color;
    XColor exact;
    if (XAllocNamedColor(
            display,
            DefaultColormap(display, screen),
            name,
            &color,
            &exact
    )) {
        return color.pixel;
    }
    return fallback;
}

static const char *touch_mode_name(int mode) {
    switch (mode) {
        case XIDirectTouch:
            return "direct";
        case XIDependentTouch:
            return "dependent";
        default:
            return "unknown";
    }
}

static void discover_touch_devices(App *app) {
    int count = 0;
    XIDeviceInfo *devices =
            XIQueryDevice(app->display, XIAllDevices, &count);
    if (devices == NULL) {
        return;
    }

    printf("XI2 touch devices:\n");
    for (int index = 0; index < count; index++) {
        XIDeviceInfo *device = &devices[index];
        for (int class_index = 0;
                class_index < device->num_classes;
                class_index++) {
            XIAnyClassInfo *info = device->classes[class_index];
            if (info->type != XITouchClass) {
                continue;
            }
            XITouchClassInfo *touch = (XITouchClassInfo *) info;
            printf(
                    "  id=%d source=%d mode=%s contacts=%d name=%s\n",
                    device->deviceid,
                    touch->sourceid,
                    touch_mode_name(touch->mode),
                    touch->num_touches,
                    device->name == NULL ? "(unnamed)" : device->name
            );
            if (app->device_count < MAX_TOUCH_DEVICES) {
                TouchDevice *saved = &app->devices[app->device_count++];
                saved->id = device->deviceid;
                saved->mode = touch->mode;
                saved->max_touches = touch->num_touches;
                snprintf(
                        saved->name,
                        sizeof(saved->name),
                        "%s",
                        device->name == NULL ? "(unnamed)" : device->name
                );
            }
            break;
        }
    }
    if (app->device_count == 0) {
        printf("  none reported by the X server\n");
    }
    fflush(stdout);
    XIFreeDeviceInfo(devices);
}

static const char *device_name(const App *app, int id) {
    for (int index = 0; index < app->device_count; index++) {
        if (app->devices[index].id == id) {
            return app->devices[index].name;
        }
    }
    return "unknown XI2 device";
}

static Contact *find_contact(App *app, int device_id, int id) {
    for (int index = 0; index < MAX_CONTACTS; index++) {
        if (app->contacts[index].active &&
                app->contacts[index].device_id == device_id &&
                app->contacts[index].id == id) {
            return &app->contacts[index];
        }
    }
    return NULL;
}

static Contact *allocate_contact(App *app, int device_id, int id) {
    for (int index = 0; index < MAX_CONTACTS; index++) {
        if (app->contacts[index].active) {
            continue;
        }
        Contact *contact = &app->contacts[index];
        memset(contact, 0, sizeof(*contact));
        contact->active = true;
        contact->device_id = device_id;
        contact->id = id;
        app->diagnostics.active_contacts++;
        if (app->diagnostics.active_contacts >
                app->diagnostics.maximum_contacts) {
            app->diagnostics.maximum_contacts =
                    app->diagnostics.active_contacts;
        }
        return contact;
    }
    app->diagnostics.dropped_contacts++;
    app->diagnostics.sequence_errors++;
    return NULL;
}

static void release_contact(App *app, Contact *contact) {
    if (contact == NULL || !contact->active) {
        return;
    }
    contact->active = false;
    if (app->diagnostics.active_contacts > 0) {
        app->diagnostics.active_contacts--;
    }
}

static void update_rate(Diagnostics *diagnostics, uint64_t now_ns) {
    if (diagnostics->rate_window_started_ns == 0) {
        diagnostics->rate_window_started_ns = now_ns;
    }
    diagnostics->rate_window_updates++;
    uint64_t elapsed =
            now_ns - diagnostics->rate_window_started_ns;
    if (elapsed < 1000000000ULL) {
        return;
    }
    diagnostics->updates_per_second =
            diagnostics->rate_window_updates * 1000000000ULL / elapsed;
    diagnostics->rate_window_started_ns = now_ns;
    diagnostics->rate_window_updates = 0;
}

static void print_verbose_event(
        const App *app,
        const char *type,
        const XIDeviceEvent *event
) {
    if (!app->verbose) {
        return;
    }
    printf(
            "%-6s id=%d source=%d x=%.1f y=%.1f emulated=%s\n",
            type,
            event->detail,
            event->sourceid,
            event->event_x,
            event->event_y,
            (event->flags & XITouchEmulatingPointer) ? "yes" : "no"
    );
    fflush(stdout);
}

static void handle_touch_event(App *app, XIDeviceEvent *event) {
    uint64_t now_ns = monotonic_ns();
    app->diagnostics.pending_touch_events++;
    Contact *contact =
            find_contact(app, event->sourceid, event->detail);
    const char *event_name;

    switch (event->evtype) {
        case XI_TouchBegin:
            event_name = "begin";
            app->diagnostics.begins++;
            if (contact != NULL) {
                app->diagnostics.sequence_errors++;
                release_contact(app, contact);
            }
            contact =
                    allocate_contact(
                            app,
                            event->sourceid,
                            event->detail
                    );
            if (contact != NULL) {
                contact->began_ns = now_ns;
            }
            break;
        case XI_TouchUpdate:
            event_name = "update";
            app->diagnostics.updates++;
            update_rate(&app->diagnostics, now_ns);
            if (contact == NULL) {
                app->diagnostics.sequence_errors++;
                contact =
                        allocate_contact(
                                app,
                                event->sourceid,
                                event->detail
                        );
                if (contact != NULL) {
                    contact->recovered = true;
                    contact->began_ns = now_ns;
                }
            }
            if (contact != NULL) {
                contact->updates++;
            }
            break;
        case XI_TouchEnd:
            event_name = "end";
            app->diagnostics.ends++;
            if (contact == NULL) {
                app->diagnostics.sequence_errors++;
            }
            break;
        default:
            return;
    }

    if (contact != NULL) {
        contact->x = event->event_x;
        contact->y = event->event_y;
        contact->last_ns = now_ns;
    }
    snprintf(
            app->diagnostics.last_event,
            sizeof(app->diagnostics.last_event),
            "%s #%d at %.0f, %.0f%s",
            event_name,
            event->detail,
            event->event_x,
            event->event_y,
            (event->flags & XITouchEmulatingPointer)
                    ? " (pointer-emulating)"
                    : ""
    );
    snprintf(
            app->diagnostics.last_device,
            sizeof(app->diagnostics.last_device),
            "%s",
            device_name(app, event->sourceid)
    );
    print_verbose_event(app, event_name, event);

    if (event->evtype == XI_TouchEnd) {
        release_contact(app, contact);
    }
}

static void draw_text(
        App *app,
        Drawable target,
        int x,
        int y,
        unsigned long color,
        const char *text
) {
    XSetForeground(app->display, app->gc, color);
    XDrawString(
            app->display,
            target,
            app->gc,
            x,
            y,
            text,
            (int) strlen(text)
    );
}

static void draw_contact(
        App *app,
        Drawable target,
        const Contact *contact,
        int slot
) {
    int x = (int) contact->x;
    int y = (int) contact->y;
    int radius = CONTACT_RADIUS;
    unsigned long color =
            contact->recovered
                    ? app->warning
                    : app->contact_colors[slot % 8];
    XSetForeground(app->display, app->gc, color);
    XFillArc(
            app->display,
            target,
            app->gc,
            x - radius,
            y - radius,
            (unsigned) radius * 2,
            (unsigned) radius * 2,
            0,
            360 * 64
    );

    char label[64];
    snprintf(
            label,
            sizeof(label),
            "#%d  %llu",
            contact->id,
            (unsigned long long) contact->updates
    );
    int label_x = x - (int) strlen(label) * 3;
    draw_text(app, target, label_x, y + 4, app->background, label);
}

static void draw(App *app) {
    if (app->back_buffer == None) {
        return;
    }
    app->diagnostics.rendered_frames++;
    if (app->diagnostics.pending_touch_events > 0) {
        app->diagnostics.touch_batches++;
        if (app->diagnostics.pending_touch_events >
                app->diagnostics.maximum_events_per_draw) {
            app->diagnostics.maximum_events_per_draw =
                    app->diagnostics.pending_touch_events;
        }
        app->diagnostics.pending_touch_events = 0;
    }
    XSetForeground(app->display, app->gc, app->background);
    XFillRectangle(
            app->display,
            app->back_buffer,
            app->gc,
            0,
            0,
            app->width,
            app->height
    );

    draw_text(
            app,
            app->back_buffer,
            22,
            28,
            app->foreground,
            APP_NAME " — native XInput2 contacts"
    );

    char line[256];
    snprintf(
            line,
            sizeof(line),
            "Active %u / %d    Peak %u    Begin %llu    Update %llu    End %llu    Rate %llu/s",
            app->diagnostics.active_contacts,
            MAX_CONTACTS,
            app->diagnostics.maximum_contacts,
            (unsigned long long) app->diagnostics.begins,
            (unsigned long long) app->diagnostics.updates,
            (unsigned long long) app->diagnostics.ends,
            (unsigned long long) app->diagnostics.updates_per_second
    );
    draw_text(app, app->back_buffer, 22, 52, app->foreground, line);

    snprintf(
            line,
            sizeof(line),
            "Errors %llu    Dropped %llu    Draws %llu    Max batch %llu    Device: %s",
            (unsigned long long) app->diagnostics.sequence_errors,
            (unsigned long long) app->diagnostics.dropped_contacts,
            (unsigned long long) app->diagnostics.rendered_frames,
            (unsigned long long) app->diagnostics.maximum_events_per_draw,
            app->diagnostics.last_device[0] == '\0'
                    ? "waiting"
                    : app->diagnostics.last_device
    );
    draw_text(
            app,
            app->back_buffer,
            22,
            76,
            app->diagnostics.sequence_errors == 0
                    ? app->muted
                    : app->warning,
            line
    );

    snprintf(
            line,
            sizeof(line),
            "Last: %s",
            app->diagnostics.last_event[0] == '\0'
                    ? "waiting for XI_TouchBegin"
                    : app->diagnostics.last_event
    );
    draw_text(app, app->back_buffer, 22, 100, app->muted, line);
    draw_text(
            app,
            app->back_buffer,
            22,
            124,
            app->muted,
            "Esc/Q exits. Trackpad and Direct modes produce mouse events; select Native touch for this probe."
    );

    XSetForeground(app->display, app->gc, app->muted);
    XDrawLine(
            app->display,
            app->back_buffer,
            app->gc,
            0,
            HEADER_HEIGHT,
            (int) app->width,
            HEADER_HEIGHT
    );

    for (int index = 0; index < MAX_CONTACTS; index++) {
        if (app->contacts[index].active) {
            draw_contact(
                    app,
                    app->back_buffer,
                    &app->contacts[index],
                    index
            );
        }
    }

    XCopyArea(
            app->display,
            app->back_buffer,
            app->window,
            app->gc,
            0,
            0,
            app->width,
            app->height,
            0,
            0
    );
    XFlush(app->display);
}

static void resize_back_buffer(App *app, unsigned width, unsigned height) {
    if (width == 0 || height == 0) {
        return;
    }
    if (app->back_buffer != None) {
        XFreePixmap(app->display, app->back_buffer);
    }
    app->width = width;
    app->height = height;
    app->back_buffer =
            XCreatePixmap(
                    app->display,
                    app->window,
                    width,
                    height,
                    (unsigned) DefaultDepth(app->display, app->screen)
            );
}

static void request_fullscreen(App *app) {
    Atom state =
            XInternAtom(app->display, "_NET_WM_STATE", False);
    Atom fullscreen =
            XInternAtom(app->display, "_NET_WM_STATE_FULLSCREEN", False);
    XChangeProperty(
            app->display,
            app->window,
            state,
            XA_ATOM,
            32,
            PropModeReplace,
            (unsigned char *) &fullscreen,
            1
    );
}

static bool initialize_x11(App *app, bool fullscreen) {
    app->display = XOpenDisplay(NULL);
    if (app->display == NULL) {
        fprintf(
                stderr,
                "touchscope: could not open DISPLAY (current value: %s)\n",
                getenv("DISPLAY") == NULL ? "(unset)" : getenv("DISPLAY")
        );
        return false;
    }
    app->screen = DefaultScreen(app->display);
    app->root = RootWindow(app->display, app->screen);
    app->fullscreen = fullscreen;

    int event = 0;
    int error = 0;
    if (!XQueryExtension(
            app->display,
            "XInputExtension",
            &app->xi_opcode,
            &event,
            &error
    )) {
        fprintf(stderr, "touchscope: XInput extension is unavailable\n");
        return false;
    }
    int major = 2;
    int minor = 2;
    int version_status = XIQueryVersion(app->display, &major, &minor);
    if (version_status == BadRequest || major < 2 ||
            (major == 2 && minor < 2)) {
        fprintf(
                stderr,
                "touchscope: XI 2.2 required; server negotiated %d.%d\n",
                major,
                minor
        );
        return false;
    }
    printf("Connected to DISPLAY=%s with XI %d.%d\n",
            DisplayString(app->display), major, minor);

    app->background =
            named_color(
                    app->display,
                    app->screen,
                    "#11141a",
                    BlackPixel(app->display, app->screen)
            );
    app->foreground =
            named_color(
                    app->display,
                    app->screen,
                    "#f4f6fa",
                    WhitePixel(app->display, app->screen)
            );
    app->muted =
            named_color(
                    app->display,
                    app->screen,
                    "#98a2b3",
                    app->foreground
            );
    app->warning =
            named_color(
                    app->display,
                    app->screen,
                    "#ff8a65",
                    app->foreground
            );
    const char *colors[8] = {
            "#66d9ef",
            "#a6e22e",
            "#fd971f",
            "#ae81ff",
            "#f92672",
            "#e6db74",
            "#7fdbca",
            "#ff6188",
    };
    for (int index = 0; index < 8; index++) {
        app->contact_colors[index] =
                named_color(
                        app->display,
                        app->screen,
                        colors[index],
                        app->foreground
                );
    }

    if (fullscreen) {
        XWindowAttributes root_attributes;
        if (XGetWindowAttributes(
                app->display,
                app->root,
                &root_attributes
        )) {
            app->width = (unsigned) root_attributes.width;
            app->height = (unsigned) root_attributes.height;
        }
    }
    if (app->width == 0 || app->height == 0) {
        app->width = 960;
        app->height = 640;
    }
    app->window =
            XCreateSimpleWindow(
                    app->display,
                    app->root,
                    0,
                    0,
                    app->width,
                    app->height,
                    0,
                    app->muted,
                    app->background
            );
    XStoreName(app->display, app->window, APP_NAME);
    XSelectInput(
            app->display,
            app->window,
            ExposureMask |
                    StructureNotifyMask |
            KeyPressMask
    );
    if (fullscreen) {
        XSelectInput(
                app->display,
                app->root,
                StructureNotifyMask
        );
    }

    discover_touch_devices(app);
    int mask_count = app->device_count == 0 ? 1 : app->device_count;
    unsigned char xi_masks[MAX_TOUCH_DEVICES][XIMaskLen(XI_LASTEVENT)];
    XIEventMask masks[MAX_TOUCH_DEVICES];
    memset(xi_masks, 0, sizeof(xi_masks));
    memset(masks, 0, sizeof(masks));
    for (int index = 0; index < mask_count; index++) {
        XISetMask(xi_masks[index], XI_TouchBegin);
        XISetMask(xi_masks[index], XI_TouchUpdate);
        XISetMask(xi_masks[index], XI_TouchEnd);
        masks[index].deviceid =
                app->device_count == 0
                        ? XIAllDevices
                        : app->devices[index].id;
        masks[index].mask_len = (int) sizeof(xi_masks[index]);
        masks[index].mask = xi_masks[index];
    }
    if (XISelectEvents(
            app->display,
            app->window,
            masks,
            mask_count
    ) != Success) {
        fprintf(stderr, "touchscope: could not select XI2 touch events\n");
        return false;
    }

    app->wm_delete =
            XInternAtom(app->display, "WM_DELETE_WINDOW", False);
    XSetWMProtocols(app->display, app->window, &app->wm_delete, 1);
    app->gc = XCreateGC(app->display, app->window, 0, NULL);
    resize_back_buffer(app, app->width, app->height);
    if (fullscreen) {
        request_fullscreen(app);
    }
    XMapRaised(app->display, app->window);
    XFlush(app->display);
    return true;
}

static void print_summary(const App *app) {
    printf(
            "summary begin=%llu update=%llu end=%llu peak=%u errors=%llu dropped=%llu draws=%llu touch_batches=%llu max_batch=%llu\n",
            (unsigned long long) app->diagnostics.begins,
            (unsigned long long) app->diagnostics.updates,
            (unsigned long long) app->diagnostics.ends,
            app->diagnostics.maximum_contacts,
            (unsigned long long) app->diagnostics.sequence_errors,
            (unsigned long long) app->diagnostics.dropped_contacts,
            (unsigned long long) app->diagnostics.rendered_frames,
            (unsigned long long) app->diagnostics.touch_batches,
            (unsigned long long)
                    app->diagnostics.maximum_events_per_draw
    );
}

static void destroy(App *app) {
    if (app->display == NULL) {
        return;
    }
    if (app->back_buffer != None) {
        XFreePixmap(app->display, app->back_buffer);
    }
    if (app->gc != None) {
        XFreeGC(app->display, app->gc);
    }
    if (app->window != None) {
        XDestroyWindow(app->display, app->window);
    }
    XCloseDisplay(app->display);
    app->display = NULL;
}

static bool run_self_test(void) {
    App app = {0};
    XIDeviceEvent event = {0};
    event.sourceid = 11;

    event.evtype = XI_TouchBegin;
    event.detail = 3;
    event.event_x = 100;
    event.event_y = 120;
    handle_touch_event(&app, &event);

    event.detail = 17;
    event.event_x = 300;
    event.event_y = 320;
    handle_touch_event(&app, &event);

    event.evtype = XI_TouchUpdate;
    event.detail = 3;
    event.event_x = 110;
    event.event_y = 130;
    handle_touch_event(&app, &event);

    event.detail = 17;
    event.event_x = 310;
    event.event_y = 330;
    handle_touch_event(&app, &event);

    event.evtype = XI_TouchEnd;
    event.detail = 3;
    handle_touch_event(&app, &event);
    event.detail = 17;
    handle_touch_event(&app, &event);

    /* A missing Begin must be detected and recovered without corrupting slots. */
    event.evtype = XI_TouchUpdate;
    event.detail = 29;
    handle_touch_event(&app, &event);
    event.evtype = XI_TouchEnd;
    handle_touch_event(&app, &event);

    bool passed =
            app.diagnostics.begins == 2 &&
                    app.diagnostics.updates == 3 &&
                    app.diagnostics.ends == 3 &&
                    app.diagnostics.sequence_errors == 1 &&
                    app.diagnostics.dropped_contacts == 0 &&
                    app.diagnostics.active_contacts == 0 &&
                    app.diagnostics.maximum_contacts == 2;
    printf(
            "self-test %s: begin=%llu update=%llu end=%llu peak=%u errors=%llu active=%u\n",
            passed ? "passed" : "failed",
            (unsigned long long) app.diagnostics.begins,
            (unsigned long long) app.diagnostics.updates,
            (unsigned long long) app.diagnostics.ends,
            app.diagnostics.maximum_contacts,
            (unsigned long long) app.diagnostics.sequence_errors,
            app.diagnostics.active_contacts
    );
    return passed;
}

static void usage(FILE *stream, const char *program) {
    fprintf(
            stream,
            "Usage: %s [--fullscreen] [--verbose] [--self-test]\n"
            "Visualize native XI2 multi-touch contacts and sequence health.\n",
            program
    );
}

static void handle_x_event(
        App *app,
        XEvent *event,
        bool *running,
        bool *redraw
) {
    switch (event->type) {
        case Expose:
            if (event->xexpose.count == 0) {
                *redraw = true;
            }
            break;
        case ConfigureNotify:
            if (app->fullscreen &&
                    event->xconfigure.window == app->root) {
                unsigned root_width =
                        (unsigned) event->xconfigure.width;
                unsigned root_height =
                        (unsigned) event->xconfigure.height;
                if (root_width > 0 && root_height > 0) {
                    XMoveResizeWindow(
                            app->display,
                            app->window,
                            0,
                            0,
                            root_width,
                            root_height
                    );
                    XRaiseWindow(app->display, app->window);
                    XFlush(app->display);
                }
                break;
            }
            if ((unsigned) event->xconfigure.width != app->width ||
                    (unsigned) event->xconfigure.height != app->height) {
                resize_back_buffer(
                        app,
                        (unsigned) event->xconfigure.width,
                        (unsigned) event->xconfigure.height
                );
                *redraw = true;
            }
            break;
        case KeyPress: {
            char text[8] = {0};
            KeySym symbol = NoSymbol;
            XLookupString(
                    &event->xkey,
                    text,
                    sizeof(text),
                    &symbol,
                    NULL
            );
            if (symbol == XK_Escape || text[0] == 'q' ||
                    text[0] == 'Q') {
                *running = false;
            }
            break;
        }
        case ClientMessage:
            if ((Atom) event->xclient.data.l[0] == app->wm_delete) {
                *running = false;
            }
            break;
        case GenericEvent:
            if (event->xcookie.extension != app->xi_opcode ||
                    !XGetEventData(app->display, &event->xcookie)) {
                break;
            }
            if (event->xcookie.evtype == XI_TouchBegin ||
                    event->xcookie.evtype == XI_TouchUpdate ||
                    event->xcookie.evtype == XI_TouchEnd) {
                handle_touch_event(
                        app,
                        (XIDeviceEvent *) event->xcookie.data
                );
                *redraw = true;
            }
            XFreeEventData(app->display, &event->xcookie);
            break;
        default:
            break;
    }
}

int main(int argc, char **argv) {
    bool fullscreen = false;
    bool self_test = false;
    App app = {0};
    for (int index = 1; index < argc; index++) {
        if (strcmp(argv[index], "--fullscreen") == 0) {
            fullscreen = true;
        } else if (strcmp(argv[index], "--verbose") == 0) {
            app.verbose = true;
        } else if (strcmp(argv[index], "--self-test") == 0) {
            self_test = true;
        } else if (strcmp(argv[index], "--help") == 0 ||
                strcmp(argv[index], "-h") == 0) {
            usage(stdout, argv[0]);
            return EXIT_SUCCESS;
        } else {
            usage(stderr, argv[0]);
            return EXIT_FAILURE;
        }
    }

    if (self_test) {
        return run_self_test() ? EXIT_SUCCESS : EXIT_FAILURE;
    }

    if (!initialize_x11(&app, fullscreen)) {
        destroy(&app);
        return EXIT_FAILURE;
    }
    draw(&app);

    bool running = true;
    while (running) {
        XEvent event;
        bool redraw = false;
        XNextEvent(app.display, &event);
        handle_x_event(&app, &event, &running, &redraw);
        while (running && XPending(app.display) > 0) {
            XNextEvent(app.display, &event);
            handle_x_event(&app, &event, &running, &redraw);
        }
        if (running && redraw) {
            draw(&app);
        }
    }

    print_summary(&app);
    destroy(&app);
    return app.diagnostics.sequence_errors == 0
            ? EXIT_SUCCESS
            : 2;
}
