# Using uDroid

uDroid installs and runs Linux systems from one Android app. This guide covers
the everyday controls and when to use them.

## Main areas

| Area | Use it for |
| --- | --- |
| **Home** | See the active system and open common actions. |
| **Linux** | Install, open, reset, or delete Linux systems. |
| **Terminal** | Run Linux commands and install packages. |
| **Apps** | Open graphical or terminal applications installed in Linux. |
| **About** | Check for updates, get support, and copy diagnostic logs. |

Some areas appear only after a Linux system is installed.
**Device compatibility** on Home shows which optional features are available
on the phone and is useful before changing graphics settings.

## Install a Linux system

1. Open **Linux** and choose a distribution.
2. Review its download size and file access.
3. Select **Download image**.
4. Follow the installation stages until **Linux is ready**.
5. Select **Open terminal**.

You can leave the page while installation continues. The notification returns
to the same installation page. A paused download can be resumed without
starting again.

If installation stops, read the message below the active stage and open
**View install log** for the full error. Fix the reported issue, then select
**Try again**.

## Use an installed system

Open **Linux**, then select an installed system. Its page contains the controls
and settings for that system.

### Terminal

Use **Terminal** to install packages, edit files, or run command-line tools.
The terminal stays alive when you move between uDroid pages. Use **Stop** on the
system page when you want to end Linux or change settings that require it to be
stopped.

### Desktop

uDroid can start X11 desktop sessions installed inside Linux.

1. Install XFCE, Plasma, MATE, or another X11 desktop with the distribution's
   package manager.
2. Return to the system page and refresh it.
3. Choose the detected desktop and select **Start**.
4. Select **Display** after the desktop is running.

Use **Desktop compositing** for effects and transparency. Turn it off when a
desktop feels slow or shows visual glitches. **Touch-sized interface** makes
desktop controls and the pointer easier to use on a phone.

Leave **Graphics driver** on **Automatic** unless an application needs another
available profile. Compatibility is checked per device, and experimental
profiles may be unstable.

### Linux apps

The **Apps** page lists launchable applications that provide a standard Linux
desktop entry. Install an application inside Linux, refresh the list, then
select it to launch.

Use **Add to home screen** when you want an Android launcher shortcut. The
shortcut always points to that application in that specific Linux system.

## Control the desktop

The top toolbar opens the Android keyboard, mouse palette, display buttons
(**Keys**), and desktop settings. Its arrow collapses the toolbar; tap the
compact handle to bring it back.

### Pointer and mouse

Choose a touch mode in **Desktop settings → Pointer**:

- **Direct:** the pointer follows your finger.
- **Trackpad:** move the pointer relative to your finger for finer control.
- **Native:** send touch points to Linux apps that support touch input.

The mouse palette has **L**, **M**, **R**, and scroll up/down. Tap a mouse
button to keep it pressed; tap it again to release it. Hold a button and move
another finger on the desktop for a temporary press-and-drag. Drag the
palette by its **Mouse** header, or tap its **Lock** control to keep the
palette in place. That Lock controls the palette's position, not a mouse
button.

### Keyboard strip and display buttons

The two-row keyboard strip stays at the bottom even when the Android keyboard
is closed. If **Keep desktop above keyboard** is on, the strip moves above
the Android keyboard when it opens. Turn **Show keyboard strip** off in
**Desktop settings → Keyboard** to reclaim the space.

The strip has 12 editable slots. In **Desktop settings → Keyboard**, tap a
slot to assign a letter, number, modifier, arrow, function or navigation key,
supported punctuation key, mouse button, or scroll direction. **Reset keyboard
strip** restores the default bindings.

**Keys** on the toolbar shows separate translucent buttons over the Linux
display. Add, change, or remove them in **Desktop settings → Keyboard**. To
move one, choose **Arrange on display**, drag it to the desired place, then
tap **Done** on the toolbar. Positions are saved. Normal use does not move
buttons, so holding a key cannot accidentally drag it.

Both the strip and display buttons use the same key gestures:

| Gesture | Result |
| --- | --- |
| Tap a key | Press and release it once. |
| Hold a key | Keep it pressed until your finger lifts; hold several for chords or WASD diagonals. |
| Double-tap a modifier | Lock Ctrl, Alt, Shift, or Super on. Tap it once to unlock. |

For example, hold **Ctrl** with one finger and tap **C** with another. A
single tap on Ctrl alone sends a complete press-and-release. Hiding controls,
changing their bindings, leaving the display, or losing the renderer releases
held and locked inputs.

Desktop settings also control resolution, scaling, sharpness, pointer speed,
keyboard handling, and whether the screen stays awake. **Keep desktop above
keyboard** resizes Linux into the space above the Android keyboard; turn it
off if you prefer the keyboard to cover the desktop without resizing it.

## Audio and microphone

**Device speaker** sends Linux audio to Android and is enabled by default.
**Device microphone** is optional and stored per Linux system. Android asks for
permission and shows its privacy indicator while the microphone is active.

Applications need PulseAudio-compatible client libraries. If one application
has no sound while others work, install its distribution's PulseAudio client
or compatibility package.

## Files and environment variables

Open **File access** to choose which Android and system paths Linux can see.
Internal shared storage can be exposed at `/mnt/shared`. Custom mounts are for
other Android paths the app can read.

Stop Linux before changing mounts. Session mounts used by the display, audio,
or graphics features are added automatically only when those features need
them.

Open **Environment variables** to view or change the values used by new
terminal, app, and desktop launches. Restart Linux when an existing terminal
needs the new values. **Reset** restores uDroid-managed values without removing
your custom variables.

## Reset or delete a system

Stop its terminal and desktop first.

- **Reset** erases the Linux filesystem and installs a fresh copy.
- **Delete** removes the Linux system without reinstalling it.

Android folders shared with Linux are outside its private filesystem and are
not erased by reset or delete.

## Updates and diagnostics

Open **About** to check for updates. uDroid verifies a downloaded APK before
opening Android's installer; Android always asks for final confirmation.

The diagnostic log records app actions and supervised Linux commands. Search
inside it, select a log block to copy that block, or use **Copy report** when
asking for help.

## When something goes wrong

| Problem | What to try |
| --- | --- |
| Installation stopped | Read the active-stage message, open the install log, then retry. |
| Android closes Linux | Follow the warning on the system page and review Android's child-process setting. |
| Desktop is slow or damaged | Use **Automatic** or **Software** graphics, disable compositing, or lower the display resolution. |
| An installed app is missing | Refresh **Apps** and check that the package provides a desktop entry. |
| One app has no sound | Install its PulseAudio client or compatibility package. |
| You need help | Copy the diagnostic report from **About** and attach it to a GitHub issue. |
