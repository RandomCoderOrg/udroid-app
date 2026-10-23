#include <X11/Xlib.h>
#include <X11/extensions/XTest.h>
#include <X11/keysym.h>

#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int
parse_coordinate(const char *value, int *coordinate)
{
   char *end;
   errno = 0;
   long parsed = strtol(value, &end, 10);
   if (errno || *value == '\0' || *end != '\0' || parsed < 0 || parsed > 32767)
      return 0;

   *coordinate = parsed;
   return 1;
}

int
main(int argc, char **argv)
{
   Display *display = XOpenDisplay(NULL);
   if (!display) {
      fprintf(stderr, "could not open DISPLAY\n");
      return 1;
   }

   int screen = DefaultScreen(display);
   if (argc == 2 && strcmp(argv[1], "--size") == 0) {
      printf("%dx%d\n", DisplayWidth(display, screen), DisplayHeight(display, screen));
   } else if (argc == 2 && strcmp(argv[1], "--show-apps") == 0) {
      KeyCode super = XKeysymToKeycode(display, XK_Super_L);
      KeyCode a = XKeysymToKeycode(display, XK_A);
      if (!super || !a ||
          !XTestFakeKeyEvent(display, super, True, CurrentTime) ||
          !XTestFakeKeyEvent(display, a, True, CurrentTime) ||
          !XTestFakeKeyEvent(display, a, False, CurrentTime) ||
          !XTestFakeKeyEvent(display, super, False, CurrentTime)) {
         fprintf(stderr, "could not inject Super+A\n");
         XCloseDisplay(display);
         return 1;
      }
   } else if (argc == 4 && strcmp(argv[1], "--move") == 0) {
      int x, y;
      if (!parse_coordinate(argv[2], &x) || !parse_coordinate(argv[3], &y) ||
          !XTestFakeMotionEvent(display, screen, x, y, CurrentTime)) {
         fprintf(stderr, "could not inject pointer move\n");
         XCloseDisplay(display);
         return 1;
      }
   } else {
      fprintf(stderr, "usage: %s --size | --show-apps | --move X Y\n", argv[0]);
      XCloseDisplay(display);
      return 2;
   }

   XSync(display, False);
   XCloseDisplay(display);
   return 0;
}
