#!/bin/sh
set -eu

log=/root/udroid-glx-tfp.log
rm -f "$log"
exec timeout 30s python3 -u /root/glx_texture_from_pixmap_probe.py >"$log" 2>&1
