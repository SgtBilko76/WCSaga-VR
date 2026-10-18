# Wing Commander Saga VR (Meta Quest)

Wing Commander Saga running natively on the Meta Quest 3, built on the
[FreeSpace Open](https://github.com/scp-fs2open/fs2open.github.com) engine with
OpenXR on OpenGL ES. Branch: `quest-vr`.

Wing Commander Saga is a free fan game. The APK contains only the engine; the
game data (about 7 GB) is downloaded separately.

## Install

1. Install `WCSaga-VR-<version>.apk` with SideQuest or `adb install -r -g`.
2. Start **Wing Commander Saga VR** from *Unknown Sources*. The first start opens
   the **One-Tap Installer** window:
   - Press **Allow file access**, switch on "Allow access" and press Done.
   - Press **Download and install**. The game data (Wing Commander Saga 0.9.65,
     3.6 GB) is downloaded from the FSNebula mod repository, checked and unpacked
     to `/sdcard/WCSaga/Wing_Commander_Saga` (6.7 GB, about 9 GB free space needed
     while installing). This takes about 10-20 minutes, depending on your Wi-Fi.
     Keep the headset on. If it is interrupted, start the app again: it continues
     where it stopped.
   - When it is finished, the game starts. Later starts go straight into the game.

You can also put the data on the headset yourself, so that the folder
`/sdcard/WCSaga/Wing_Commander_Saga/data` exists; the installer is then skipped:
- On a PC with Python 3 and 7-Zip, with the Quest connected by USB:
  `python3 tools/quest/get_wcs_data.py WCSaga --push`
- Or, on a Windows PC: download the full game and patch v1.1.0.7822 from
  [wcsaga.com](https://www.wcsaga.com/downloads/), install the game, then the
  patch, and copy the whole installed game folder to
  `/sdcard/WCSaga/Wing_Commander_Saga` with SideQuest.

## Controls

Menus, briefings and cutscenes are shown on a large screen in front of you.

| Input | Action |
|---|---|
| Laser (either controller) + trigger | Click |
| A | Enter |
| B / left Menu button | Escape (back / pause) |

Text fields (pilot callsign, chat): a keyboard appears below the menu screen while the
field is active; point at a key and pull the trigger. Hold Back to keep deleting.

Flight (the Touch controllers act as one gamepad; every binding can be changed
in Options > Controls, "Reset to default" restores this layout):

| Input | Action |
|---|---|
| Left stick | Pitch and turn |
| Right stick left/right | Roll |
| Right stick up/down | Throttle (stays where you leave it) |
| Right stick click | Match target speed |
| Left stick click | Autopilot |
| Right trigger | Guns |
| Left trigger | Missiles |
| Right grip (hold) | Afterburner |
| A | Target nearest enemy |
| B | Target ship in crosshair |
| X | Next missile type |
| Y | Next gun type |
| Left Menu button | Pause menu |
| Left grip + A | Next target |
| Left grip + B | Target your target's attacker |
| Left grip + X | Next subsystem on target |
| Left grip + Y | Comms menu |
| Left grip + right stick up / down | Full speed / full stop |
| Left grip + left stick click | Look behind |
| Left grip + right stick click | Chase view on / off |

Comms menu open: left stick up/down picks an order, A or a trigger confirms,
Y closes the menu.

## Building

Linux host with the Android SDK/NDK: `tools/quest/build_apk.sh --install`.
`tools/quest/run.sh` launches it on a connected headset and
`tools/quest/fps_test.sh` flies the first mission and prints the frame rate.

## License

The FreeSpace Open engine is released under the Volition source code license
(non-commercial use only); see `Copying.md`. Wing Commander is a trademark of
Electronic Arts; Wing Commander Saga is a non-commercial fan project.
