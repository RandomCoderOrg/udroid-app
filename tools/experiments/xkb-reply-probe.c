#define _POSIX_C_SOURCE 200809L

#include <poll.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <time.h>
#include <xcb/xcb.h>
#include <xcb/xcbext.h>
#include <xcb/xkb.h>
#include <xkbcommon/xkbcommon-x11.h>

struct request {
    const char *name;
    unsigned int sequence;
    int complete;
};

static int64_t
milliseconds(void)
{
    struct timespec now;

    clock_gettime(CLOCK_MONOTONIC, &now);
    return now.tv_sec * 1000 + now.tv_nsec / 1000000;
}

int
main(void)
{
    xcb_connection_t *connection = xcb_connect(NULL, NULL);
    if (xcb_connection_has_error(connection)) {
        fputs("connect failed\n", stderr);
        return 1;
    }

    if (!xkb_x11_setup_xkb_extension(connection,
                                     XKB_X11_MIN_MAJOR_XKB_VERSION,
                                     XKB_X11_MIN_MINOR_XKB_VERSION,
                                     XKB_X11_SETUP_XKB_EXTENSION_NO_FLAGS,
                                     NULL, NULL, NULL, NULL)) {
        fputs("XKB setup failed\n", stderr);
        return 1;
    }

    int32_t device = xkb_x11_get_core_keyboard_device_id(connection);
    if (device < 0) {
        fputs("core keyboard lookup failed\n", stderr);
        return 1;
    }
    printf("device=%d\n", device);

    const xcb_xkb_map_part_t map_parts =
        XCB_XKB_MAP_PART_KEY_TYPES |
        XCB_XKB_MAP_PART_KEY_SYMS |
        XCB_XKB_MAP_PART_MODIFIER_MAP |
        XCB_XKB_MAP_PART_EXPLICIT_COMPONENTS |
        XCB_XKB_MAP_PART_KEY_ACTIONS |
        XCB_XKB_MAP_PART_VIRTUAL_MODS |
        XCB_XKB_MAP_PART_VIRTUAL_MOD_MAP;
    const xcb_xkb_name_detail_t names =
        XCB_XKB_NAME_DETAIL_KEYCODES |
        XCB_XKB_NAME_DETAIL_SYMBOLS |
        XCB_XKB_NAME_DETAIL_TYPES |
        XCB_XKB_NAME_DETAIL_COMPAT |
        XCB_XKB_NAME_DETAIL_KEY_TYPE_NAMES |
        XCB_XKB_NAME_DETAIL_KT_LEVEL_NAMES |
        XCB_XKB_NAME_DETAIL_INDICATOR_NAMES |
        XCB_XKB_NAME_DETAIL_KEY_NAMES |
        XCB_XKB_NAME_DETAIL_KEY_ALIASES |
        XCB_XKB_NAME_DETAIL_VIRTUAL_MOD_NAMES |
        XCB_XKB_NAME_DETAIL_GROUP_NAMES;

    xcb_xkb_get_map_cookie_t map =
        xcb_xkb_get_map(connection, device, map_parts,
                        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    xcb_xkb_get_indicator_map_cookie_t indicators =
        xcb_xkb_get_indicator_map(connection, device, UINT32_MAX);
    xcb_xkb_get_compat_map_cookie_t compat =
        xcb_xkb_get_compat_map(connection, device, 0, 1, 0, 0);
    xcb_xkb_get_names_cookie_t key_names =
        xcb_xkb_get_names(connection, device, names);
    xcb_xkb_get_controls_cookie_t controls =
        xcb_xkb_get_controls(connection, device);

    struct request requests[] = {
        { "GetMap", map.sequence, 0 },
        { "GetIndicatorMap", indicators.sequence, 0 },
        { "GetCompatMap", compat.sequence, 0 },
        { "GetNames", key_names.sequence, 0 },
        { "GetControls", controls.sequence, 0 },
    };
    const size_t request_count = sizeof(requests) / sizeof(requests[0]);
    size_t completed = 0;
    int64_t deadline = milliseconds() + 3000;

    xcb_flush(connection);
    for (size_t i = 0; i < request_count; i++)
        printf("sent %s sequence=%u\n", requests[i].name, requests[i].sequence);
    fflush(stdout);

    while (completed < request_count && milliseconds() < deadline) {
        for (size_t i = 0; i < request_count; i++) {
            void *reply = NULL;
            xcb_generic_error_t *error = NULL;

            if (requests[i].complete ||
                !xcb_poll_for_reply(connection, requests[i].sequence,
                                    &reply, &error))
                continue;

            requests[i].complete = 1;
            completed++;
            printf("reply %s sequence=%u status=%s error=%u\n",
                   requests[i].name, requests[i].sequence,
                   reply ? "ok" : "error", error ? error->error_code : 0);
            free(reply);
            free(error);
        }

        struct pollfd fd = { xcb_get_file_descriptor(connection), POLLIN, 0 };
        poll(&fd, 1, 20);
    }

    for (size_t i = 0; i < request_count; i++) {
        if (!requests[i].complete)
            printf("timeout %s sequence=%u\n",
                   requests[i].name, requests[i].sequence);
    }

    xcb_disconnect(connection);
    return completed == request_count ? 0 : 2;
}
