# Dual-screen build (LBA2-DS, Retroid Pocket Duo Lite)

A separate APK, `io.github.sergeshack.lba2ds`, that installs beside the upstream `org.lbalab.lba2cc` one. The game runs on the top screen; the bottom screen carries a panel and takes over the game's modal screens. Only the Retroid Pocket Duo Lite is targeted: the top 1920x1080 panel is the default display, the bottom 1280x960 panel is the presentation display. On a device with one screen the APK plays like the upstream one.

## Building

```bash
bash scripts/dev/build-sdl3-android.sh      # once
export ANDROID_NDK=... SDL3_ANDROID_DIR=$PWD/out/android/sdl3-install ANDROID_HOME=... JAVA_HOME=...
bash scripts/dev/build-android-ds.sh        # dist/lba2ds-<version>.apk
```

The script builds the engine with `scripts/dev/build-android.sh`, stages `liblba2cc.so` (as `libmain.so`), `libSDL3.so` and `libc++_shared.so` into `android-ds/app/src/main/jniLibs`, and runs the Gradle project in `android-ds/`. `SKIP_NATIVE=1` reuses the last engine build. It keeps its own shared-storage folder, `/sdcard/LBA2-DS/` (`-DLBA2_ANDROID_STORAGE_DIR=LBA2-DS`): the game data goes there, and saves and settings are in `/sdcard/LBA2-DS/user/LBA2/`. Data already in `/sdcard/lba2cc/` for the upstream APK is found too ([ANDROID.md](ANDROID.md)).

## How it fits together

| Piece | Where | What it does |
|---|---|---|
| Launcher | `android-ds/.../ds/LauncherActivity.kt` | Starts the game on the top screen, whichever screen the icon was tapped on |
| Game | `android-ds/.../ds/GameActivity.kt` | `SDLActivity`; brings the panel up, keeps the game on its display, ignores the focus loss a tap on the other display causes |
| Panel host | `android-ds/.../ds/CompanionActivity.kt` | Activity on the bottom display with a focus anchor, so taps there do not take the controller from the game |
| Panel UI | `android-ds/.../ds/CompanionScreen.kt` | Compose: status, behaviours, weapons, items, and the mirrored modal screens with touch |
| Mailbox | [LIB386/SYSTEM/DS_LINK.CPP](../LIB386/SYSTEM/DS_LINK.CPP) | Commands in, state and frames out, across the UI and game threads; the JNI exports |
| Game side | [SOURCES/DS_BRIDGE.CPP](../SOURCES/DS_BRIDGE.CPP) | Turns commands into key presses and pointer events, publishes the hero's state, moves modal frames to the bottom screen |

The panel only presses keys the game already has (F5-F8, 1-7, N, J, C, F, Shift, H, F2-F4), so no game rule changes. A command is a key held for four input polls through `ApplyCompanionKeys`, a weak hook in [LIB386/SYSTEM/KEYBOARD.CPP](../LIB386/SYSTEM/KEYBOARD.CPP) beside the touch overlay's and the harness's.

While a game menu, the saved-games list, the inventory, the behaviour menu or the slate runs (`DsModalScope` at the top of each loop), `DsBridge_PrePresent` sends its frames to the panel and the top screen keeps the last gameplay frame. The holomap stays on the top screen, where the globe has room, and the bottom screen becomes a touch pad for it. Touch:

- menus and saved games: the finger is the mouse ([GAMEMENU_MOUSE.CPP](../SOURCES/GAMEMENU_MOUSE.CPP) handles the click),
- holomap (on the top screen): drag on the bottom screen turns the globe, a pinch zooms out and back (never closer than the game's own view, where the globe would cover its caption), a tap is the action key,
- inventory, behaviour menu, slate: a swipe is an arrow key, a tap the action key.

Nothing in the engine changes until the panel is on screen (`DsLink_IsActive`). Once it is, the touch overlay is switched off.

## Look of the panel

The panel is drawn in the game's own hand: the menus' cyan frames over darkened ground and their blue for the selected entry, and text in the game's font. The engine renders the font, the HUD sprites (life, magic, kashes, zlitos, key, clover boxes) and the inventory's item models into a corner of Log, reads them back through the normal palette and hands them to the panel as pictures (`RenderAssets` in [SOURCES/DS_BRIDGE.CPP](../SOURCES/DS_BRIDGE.CPP)); nothing of the game is in the APK. Font and sprites come once the banks are loaded. The item models need the inventory's textures and 3D view, so they are drawn from the inventory's own loop only; the panel keeps them in its app-private storage and shows those until the inventory is next opened.

A bar at the bottom names what the pad's buttons do right now: in play it follows the behaviour (the action button is Jump in Sporty, Fight in Aggressive, Hide in Discreet; the hints read the live bindings) and the weapon on R1; in a menu, the holomap, the inventory, the behaviour menu or the slate it names that screen's buttons, and during a dialogue (which stays on the top screen) it says any button turns the page and Start skips it.

## Pad layout

`build-android-ds.sh` configures the engine with `-DLBA2_GAMEPAD_DS_LAYOUT=ON`: the left stick and the D-pad both walk (up/down) and turn (left/right), and the D-pad's quick items (penguin, jetpack, protection, lightning) are left unbound, since the panel has them. The behaviour's action (jump, fight, hide) moves to the bottom face button and talk/search to the left one. The triggers are free. These are compiled defaults: a `lba2.cfg` that already names its gamepad bindings keeps them until they are reset in the controls menu or edited (`Gamepad0`..`Gamepad3` and their `_2` keys, `Gamepad7`, `Gamepad8`, `Gamepad32`..`Gamepad35`). The button hints read the live bindings, so they stay right either way..

## Testing from adb

The game accepts one bridge command per broadcast from the adb shell (guarded by the `DUMP` permission, which apps cannot hold):

```bash
adb shell am broadcast -a io.github.sergeshack.lba2ds.CMD --es line "'key 14'"           # F5
adb shell am broadcast -a io.github.sergeshack.lba2ds.CMD --es line "'con vargame 0 1'"  # console
adb shell input -d 2 tap 640 280                                                          # tap the bottom screen
```
