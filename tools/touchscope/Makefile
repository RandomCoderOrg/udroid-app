CC ?= cc
PKG_CONFIG ?= pkg-config
PREFIX ?= /usr/local

CFLAGS ?= -O2
CFLAGS += -std=c11 -Wall -Wextra -Wpedantic

ifeq ($(shell uname -s),Darwin)
CPPFLAGS += -I/opt/X11/include
LDFLAGS += -L/opt/X11/lib
LDLIBS += -lX11 -lXi -lm
else
CPPFLAGS += $(shell $(PKG_CONFIG) --cflags x11 xi)
LDLIBS += $(shell $(PKG_CONFIG) --libs x11 xi) -lm
endif

.PHONY: all clean install uninstall

all: touchscope

touchscope: touchscope.c
	$(CC) $(CPPFLAGS) $(CFLAGS) $< $(LDFLAGS) $(LDLIBS) -o $@

install: touchscope
	install -d "$(DESTDIR)$(PREFIX)/bin"
	install -m 0755 touchscope "$(DESTDIR)$(PREFIX)/bin/touchscope"
	install -d "$(DESTDIR)$(PREFIX)/share/applications"
	install -m 0644 touchscope.desktop \
		"$(DESTDIR)$(PREFIX)/share/applications/touchscope.desktop"

uninstall:
	rm -f "$(DESTDIR)$(PREFIX)/bin/touchscope"
	rm -f "$(DESTDIR)$(PREFIX)/share/applications/touchscope.desktop"

clean:
	rm -f touchscope
