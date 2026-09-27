# uDroid Android App

[![Android CI](https://github.com/RandomCoderOrg/udroid-app/actions/workflows/android.yml/badge.svg)](https://github.com/RandomCoderOrg/udroid-app/actions/workflows/android.yml)

> [!WARNING]
> **uDroid is in early development.** Expect incomplete features and breaking
> changes. Use it for testing and keep important data backed up.

This is the standalone Android application for
[uDroid](https://github.com/RandomCoderOrg/fs-manager-udroid): a friendly,
supervised way to install and use Linux distributions on Android.

## Download and install

1. Download the newest `.apk` from
   [GitHub Releases](https://github.com/RandomCoderOrg/udroid-app/releases).
2. Open it and approve the installation.

Android may ask you to allow installs from your browser or file manager. Once
installed, uDroid can check for updates from **About**.

## Feature support

- Install Linux images from uDroid and PRoot-Distro.
- Keep and manage multiple Linux systems.
- Use a built-in, persistent terminal.
- Launch X11 desktops and installed Linux apps.
- Use touch, mouse, keyboard, and multi-touch input.
- Play audio and optionally use the microphone.
- Configure mounts and environment variables per system.
- Check for updates and copy diagnostic logs.

## Limitations

- One Linux system and one X11 display can run at a time.
- Wayland sessions are not supported yet.
- PRoot is not a virtual machine and cannot grant real root privileges. Docker,
  LXC, Flatpak, Snap, and tools that need privileged mounts, namespaces, or
  device access may not work.
- Graphics acceleration is experimental and device-dependent. Venus, VirGL,
  and ANGLE may work on supported Mali devices. Turnip is not supported yet
  because I do not have a Snapdragon device to port and test it.
- Desktop integration varies between environments.

## Documentation

User guide: [Using uDroid](docs/USER_GUIDE.md).

Developer reference: [builds and releases](docs/DEVELOPMENT.md),
[product scope](docs/PRODUCT_SCOPE.md), [Linux systems](docs/DISTRIBUTION_CATALOGUE.md),
[Linux apps](docs/LINUX_APPLICATION_LAUNCHER.md),
[desktop](docs/X11_RUNTIME_ARCHITECTURE.md), [audio](docs/AUDIO_RUNTIME.md),
[updates](docs/APP_UPDATES.md), and [performance](docs/PERFORMANCE.md).

Licensing: [MIT license](LICENSE) and
[third-party notices](THIRD_PARTY_NOTICES.md).
