#!/usr/bin/env python3
"""Validate GLX_EXT_texture_from_pixmap with deterministic red/green frames."""

import ctypes
import ctypes.util
import sys


Display = ctypes.c_void_p
Drawable = ctypes.c_ulong
Pixmap = ctypes.c_ulong
GLXFBConfig = ctypes.c_void_p
GLXContext = ctypes.c_void_p
GLXDrawable = ctypes.c_ulong


class XVisualInfo(ctypes.Structure):
    _fields_ = [
        ("visual", ctypes.c_void_p),
        ("visualid", ctypes.c_ulong),
        ("screen", ctypes.c_int),
        ("depth", ctypes.c_int),
        ("visual_class", ctypes.c_int),
        ("red_mask", ctypes.c_ulong),
        ("green_mask", ctypes.c_ulong),
        ("blue_mask", ctypes.c_ulong),
        ("colormap_size", ctypes.c_int),
        ("bits_per_rgb", ctypes.c_int),
    ]


GLX_DRAWABLE_TYPE = 0x8010
GLX_RENDER_TYPE = 0x8011
GLX_X_RENDERABLE = 0x8012
GLX_RGBA_TYPE = 0x8014
GLX_RGBA_BIT = 0x00000001
GLX_PIXMAP_BIT = 0x00000002
GLX_PBUFFER_BIT = 0x00000004
GLX_RED_SIZE = 8
GLX_GREEN_SIZE = 9
GLX_BLUE_SIZE = 10
GLX_ALPHA_SIZE = 11
GLX_PBUFFER_HEIGHT = 0x8040
GLX_PBUFFER_WIDTH = 0x8041
GLX_BIND_TO_TEXTURE_RGB_EXT = 0x20D0
GLX_TEXTURE_FORMAT_EXT = 0x20D5
GLX_TEXTURE_TARGET_EXT = 0x20D6
GLX_TEXTURE_FORMAT_RGB_EXT = 0x20D8
GLX_TEXTURE_2D_EXT = 0x20DC

GL_TEXTURE_2D = 0x0DE1
GL_TEXTURE_MIN_FILTER = 0x2801
GL_TEXTURE_MAG_FILTER = 0x2800
GL_NEAREST = 0x2600
GL_RGB = 0x1907
GL_UNSIGNED_BYTE = 0x1401


def load(name: str) -> ctypes.CDLL:
    path = ctypes.util.find_library(name)
    if not path:
        raise RuntimeError(f"could not find lib{name}")
    return ctypes.CDLL(path)


def phase(name: str) -> None:
    print(f"GLX_TFP_PHASE {name}", flush=True)


def main() -> int:
    phase("load-libraries")
    x11 = load("X11")
    gl = load("GL")

    x11.XOpenDisplay.argtypes = [ctypes.c_char_p]
    x11.XOpenDisplay.restype = Display
    x11.XDefaultScreen.argtypes = [Display]
    x11.XDefaultScreen.restype = ctypes.c_int
    x11.XRootWindow.argtypes = [Display, ctypes.c_int]
    x11.XRootWindow.restype = Drawable
    x11.XCreatePixmap.argtypes = [Display, Drawable, ctypes.c_uint, ctypes.c_uint, ctypes.c_uint]
    x11.XCreatePixmap.restype = Pixmap
    x11.XCreateGC.argtypes = [Display, Drawable, ctypes.c_ulong, ctypes.c_void_p]
    x11.XCreateGC.restype = ctypes.c_void_p
    x11.XSetForeground.argtypes = [Display, ctypes.c_void_p, ctypes.c_ulong]
    x11.XFillRectangle.argtypes = [
        Display,
        Drawable,
        ctypes.c_void_p,
        ctypes.c_int,
        ctypes.c_int,
        ctypes.c_uint,
        ctypes.c_uint,
    ]
    x11.XSync.argtypes = [Display, ctypes.c_int]
    x11.XFree.argtypes = [ctypes.c_void_p]
    x11.XFreeGC.argtypes = [Display, ctypes.c_void_p]
    x11.XFreePixmap.argtypes = [Display, Pixmap]

    gl.glXChooseFBConfig.argtypes = [
        Display,
        ctypes.c_int,
        ctypes.POINTER(ctypes.c_int),
        ctypes.POINTER(ctypes.c_int),
    ]
    gl.glXChooseFBConfig.restype = ctypes.POINTER(GLXFBConfig)
    gl.glXGetVisualFromFBConfig.argtypes = [Display, GLXFBConfig]
    gl.glXGetVisualFromFBConfig.restype = ctypes.POINTER(XVisualInfo)
    gl.glXCreateNewContext.argtypes = [Display, GLXFBConfig, ctypes.c_int, GLXContext, ctypes.c_int]
    gl.glXCreateNewContext.restype = GLXContext
    gl.glXCreatePbuffer.argtypes = [Display, GLXFBConfig, ctypes.POINTER(ctypes.c_int)]
    gl.glXCreatePbuffer.restype = GLXDrawable
    gl.glXCreatePixmap.argtypes = [Display, GLXFBConfig, Pixmap, ctypes.POINTER(ctypes.c_int)]
    gl.glXCreatePixmap.restype = GLXDrawable
    gl.glXMakeContextCurrent.argtypes = [Display, GLXDrawable, GLXDrawable, GLXContext]
    gl.glXMakeContextCurrent.restype = ctypes.c_int
    gl.glXGetProcAddressARB.argtypes = [ctypes.c_char_p]
    gl.glXGetProcAddressARB.restype = ctypes.c_void_p
    gl.glXDestroyPixmap.argtypes = [Display, GLXDrawable]
    gl.glXDestroyPbuffer.argtypes = [Display, GLXDrawable]
    gl.glXDestroyContext.argtypes = [Display, GLXContext]

    gl.glGenTextures.argtypes = [ctypes.c_int, ctypes.POINTER(ctypes.c_uint)]
    gl.glBindTexture.argtypes = [ctypes.c_uint, ctypes.c_uint]
    gl.glTexParameteri.argtypes = [ctypes.c_uint, ctypes.c_uint, ctypes.c_int]
    gl.glGetTexImage.argtypes = [
        ctypes.c_uint,
        ctypes.c_int,
        ctypes.c_uint,
        ctypes.c_uint,
        ctypes.c_void_p,
    ]
    gl.glFinish.argtypes = []
    gl.glDeleteTextures.argtypes = [ctypes.c_int, ctypes.POINTER(ctypes.c_uint)]

    phase("open-display")
    display = x11.XOpenDisplay(None)
    if not display:
        print("[fail] could not open DISPLAY", file=sys.stderr)
        return 1
    screen = x11.XDefaultScreen(display)
    attributes = (ctypes.c_int * 21)(
        GLX_X_RENDERABLE,
        1,
        GLX_DRAWABLE_TYPE,
        GLX_PIXMAP_BIT | GLX_PBUFFER_BIT,
        GLX_RENDER_TYPE,
        GLX_RGBA_BIT,
        GLX_RED_SIZE,
        8,
        GLX_GREEN_SIZE,
        8,
        GLX_BLUE_SIZE,
        8,
        GLX_ALPHA_SIZE,
        0,
        GLX_BIND_TO_TEXTURE_RGB_EXT,
        1,
        0,
        0,
        0,
        0,
        0,
    )
    count = ctypes.c_int()
    phase("choose-fbconfig")
    configs = gl.glXChooseFBConfig(display, screen, attributes, ctypes.byref(count))
    if not configs or count.value == 0:
        print("[fail] no texture-from-pixmap FBConfig", file=sys.stderr)
        return 1
    config = None
    visual = None
    for index in range(count.value):
        candidate = configs[index]
        candidate_visual = gl.glXGetVisualFromFBConfig(display, candidate)
        if candidate_visual:
            config = candidate
            visual = candidate_visual
            break
    if not visual:
        print(f"[fail] none of {count.value} FBConfigs has an X visual", file=sys.stderr)
        return 1

    phase("create-x-and-glx-objects")
    width = height = 64
    root = x11.XRootWindow(display, screen)
    pixmap = x11.XCreatePixmap(display, root, width, height, visual.contents.depth)
    gc = x11.XCreateGC(display, pixmap, 0, None)
    context = gl.glXCreateNewContext(display, config, GLX_RGBA_TYPE, None, 1)
    pbuffer_attributes = (ctypes.c_int * 5)(
        GLX_PBUFFER_WIDTH,
        width,
        GLX_PBUFFER_HEIGHT,
        height,
        0,
    )
    pbuffer = gl.glXCreatePbuffer(display, config, pbuffer_attributes)
    pixmap_attributes = (ctypes.c_int * 5)(
        GLX_TEXTURE_FORMAT_EXT,
        GLX_TEXTURE_FORMAT_RGB_EXT,
        GLX_TEXTURE_TARGET_EXT,
        GLX_TEXTURE_2D_EXT,
        0,
    )
    glx_pixmap = gl.glXCreatePixmap(display, config, pixmap, pixmap_attributes)
    if not all((pixmap, gc, context, pbuffer, glx_pixmap)):
        print("[fail] GLX object creation", file=sys.stderr)
        return 1
    phase("make-context-current")
    if not gl.glXMakeContextCurrent(display, pbuffer, pbuffer, context):
        print("[fail] glXMakeContextCurrent", file=sys.stderr)
        return 1

    bind_address = gl.glXGetProcAddressARB(b"glXBindTexImageEXT")
    release_address = gl.glXGetProcAddressARB(b"glXReleaseTexImageEXT")
    if not bind_address or not release_address:
        print("[fail] GLX_EXT_texture_from_pixmap entrypoints missing", file=sys.stderr)
        return 1
    bind = ctypes.CFUNCTYPE(None, Display, GLXDrawable, ctypes.c_int, ctypes.c_void_p)(bind_address)
    release = ctypes.CFUNCTYPE(None, Display, GLXDrawable, ctypes.c_int)(release_address)

    texture = ctypes.c_uint()
    gl.glGenTextures(1, ctypes.byref(texture))
    gl.glBindTexture(GL_TEXTURE_2D, texture)
    gl.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
    gl.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)

    samples = []
    for name, pixel in (("red", visual.contents.red_mask), ("green", visual.contents.green_mask)):
        phase(f"x-fill-{name}")
        x11.XSetForeground(display, gc, pixel)
        x11.XFillRectangle(display, pixmap, gc, 0, 0, width, height)
        x11.XSync(display, 0)
        phase(f"bind-{name}")
        bind(display, glx_pixmap, 0x20DE, None)  # GLX_FRONT_LEFT_EXT
        phase(f"finish-{name}")
        gl.glFinish()
        image = (ctypes.c_ubyte * (width * height * 3))()
        phase(f"read-{name}")
        gl.glGetTexImage(GL_TEXTURE_2D, 0, GL_RGB, GL_UNSIGNED_BYTE, image)
        center = ((height // 2) * width + width // 2) * 3
        rgb = tuple(image[center + channel] for channel in range(3))
        samples.append(rgb)
        print(f"GLX_TFP frame={name} rgb={rgb[0]},{rgb[1]},{rgb[2]}", flush=True)
        phase(f"release-{name}")
        release(display, glx_pixmap, 0x20DE)

    red, green = samples
    passed = red[0] > 200 and red[1] < 40 and green[1] > 200 and green[0] < 40
    print(f"GLX_TFP_RESULT pass={'yes' if passed else 'no'} red={red} green={green}", flush=True)

    gl.glDeleteTextures(1, ctypes.byref(texture))
    gl.glXMakeContextCurrent(display, 0, 0, None)
    gl.glXDestroyPixmap(display, glx_pixmap)
    gl.glXDestroyPbuffer(display, pbuffer)
    gl.glXDestroyContext(display, context)
    x11.XFreeGC(display, gc)
    x11.XFreePixmap(display, pixmap)
    x11.XFree(visual)
    x11.XFree(configs)
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
