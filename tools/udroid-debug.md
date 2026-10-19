# Dev-build ADB controls

`udroid-debug.py` talks to the installed `org.randomcoder.udroid.dev` app. It uses
an ADB-shell-only receiver and is not included in release builds. It invokes the
same supervised session paths as the UI; it does not run arbitrary guest commands.

```sh
python3 tools/udroid-debug.py roots
python3 tools/udroid-debug.py status
python3 tools/udroid-debug.py start-desktop --rootfs noble-raw --environment gnome
python3 tools/udroid-debug.py open-desktop
python3 tools/udroid-debug.py logs --limit 40
python3 tools/udroid-debug.py stop-desktop
```

Other controls: `open-system`, `start-terminal`, and `stop-terminal`. Add
`--serial DEVICE` when multiple devices are connected. Set `ADB` to the full
`adb` executable path if it is not on `PATH`. Commands are queued; `status`
reports whether the supervised operation actually started.

## Panfrost damage reproduction

Start a clean Panfrost GNOME session, launch Alacritty through uDroid's normal
Linux-app path, and collapse the Android display controls:

```sh
python3 tools/udroid-debug.py repro-damage
```

Then drag the Alacritty window and type `glxgears` one character at a time.
Use `--keep-controls` when the expanded controls are part of the comparison.
