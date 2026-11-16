# PocketReel

An Android player for VideoNow personal video discs: **VideoNow XP** (including the interactive
adventures and quiz discs), **VideoNow Color / Jr / Color FX**, and the original **black and white**
discs. Bring your own rips.

PocketReel is an independent fan project. It is not affiliated with, endorsed by or connected to
Hasbro or Tiger Electronics. "VideoNow" is a trademark of its owner and is used here only to say which
discs the app can play. No disc content, logos or firmware are included.

## What it does

- Decodes the disc stream directly: 144 × 80 picture with 4-bit colour channels, 8-bit mono sound at
  35 280 Hz (XP/Color, read at twice CD speed), or 80 × 80 greys at 44 100 Hz for black and white.
- Plays interactive XP discs the way they were built: each segment's header carries its branch table,
  so choices, timed prompts, quiz scoring and score-based endings all come from the disc itself.
- Picture and sound stay in sync off the audio clock.
- An original handheld-style skin: D-pad and OK/MENU buttons for the disc's six choices, transport keys,
  track list, an optional LCD effect, 4:3 or stored-grid picture, and a full-screen mode.
- Optional highlighting of the buttons the current scene responds to.
- Game controllers and keyboards work (D-pad, A/X = OK, B/Y = MENU, Start = pause, L1/R1 = skip,
  L2 = go back a scene, Select = track list).

## Supported rips

- `.cue` + `.bin` (one file per track, or a single `.bin`)
- `.zip` of the above (Redump-style sets), unpacked to the app's cache when opened
- a single raw track (`.bin` / `.raw`) or a `.wav` rip of one track

Pick a **library folder** once and PocketReel lists every disc in it, or use **Open files** and select a
`.cue` together with its `.bin` files (or a `.zip`).

## Controls

| Disc button | Screen | Controller | Keyboard |
|---|---|---|---|
| 1 | D-pad up | D-pad up | 1 / W / ↑ |
| 2 | D-pad right | D-pad right | 2 / D / → |
| 3 | D-pad down | D-pad down | 3 / S / ↓ |
| 4 | D-pad left | D-pad left | 4 / A / ← |
| 5 | OK | A or X | 5 / Enter |
| 6 | MENU | B or Y | 6 / M |

The undo-arrow key steps back to the previous scene, which is handy after a wrong turn.

## Building

GitHub Actions builds a debug APK on every push (see the **Actions** tab, artifact `PocketReel-apk`).
Locally: `gradle :app:assembleDebug`. The `tools` module is a desktop test harness:

```
gradle :tools:installDist
tools/build/install/tools/bin/tools info  "Disc.cue"
tools/build/install/tools/bin/tools run   "Disc.cue" out/ 60 46:1   # play 60 s, press button 1 at 46 s
```

## Credits

The disc format and the player logic are ported from [vxp](https://github.com/zdiemer/vxp)
(MIT), whose format notes build on [PVDTools](https://github.com/saramibreak/PVDTools) by saramibreak.
See `NOTICE`.
