#include <GL/glx.h>
#include <inttypes.h>
#include <stdio.h>
#include <unistd.h>

int main(void) {
    Display *display = XOpenDisplay(NULL);
    PFNGLXGETSYNCVALUESOMLPROC get_sync_values;
    XVisualInfo *visual;
    XSetWindowAttributes attributes;
    Window window;
    GLXContext context;
    int visual_attributes[] = {GLX_RGBA, GLX_DOUBLEBUFFER, None};

    if (!display) return 1;
    visual = glXChooseVisual(display, DefaultScreen(display), visual_attributes);
    if (!visual) return 2;
    attributes.colormap = XCreateColormap(display,
                                          RootWindow(display, visual->screen),
                                          visual->visual, AllocNone);
    window = XCreateWindow(display, RootWindow(display, visual->screen),
                           0, 0, 64, 64, 0, visual->depth, InputOutput,
                           visual->visual, CWColormap, &attributes);
    context = glXCreateContext(display, visual, NULL, True);
    if (!window || !context || !glXMakeCurrent(display, window, context)) return 3;
    get_sync_values = (PFNGLXGETSYNCVALUESOMLPROC)
        glXGetProcAddressARB((const GLubyte *) "glXGetSyncValuesOML");
    if (!get_sync_values) return 4;

    for (int i = 0; i < 12; i++) {
        int64_t ust = 0, msc = 0, sbc = 0;
        Bool ok = get_sync_values(display, window, &ust, &msc, &sbc);
        printf("ok=%d ust=%" PRId64 " msc=%" PRId64 " sbc=%" PRId64 "\n",
               ok, ust, msc, sbc);
        fflush(stdout);
        usleep(250000);
    }

    glXMakeCurrent(display, None, NULL);
    glXDestroyContext(display, context);
    XDestroyWindow(display, window);
    XFree(visual);
    XCloseDisplay(display);
    return 0;
}
