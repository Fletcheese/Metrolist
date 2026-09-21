# CLAUDE.md

Guidance for Claude Code when working in this repo. This is **Fletch's personal
fork** of [Metrolist](https://github.com/MetrolistGroup/Metrolist) (a YouTube
Music client for Android), rebranded as **"FletchTunes"** with a few personal
UX tweaks. Package id / applicationId is unchanged (`com.metrolist.music`, debug
builds install as `com.metrolist.music.debug`), so upstream sync/merges stay easy.

## Build environment (this machine)

`JAVA_HOME` and `adb` are **not** on PATH by default in this shell — set them
explicitly or commands fail with "JAVA_HOME is not set":

```bash
JAVA_HOME=/home/fletch/.jdks/jbr-21.0.11 ANDROID_HOME=/home/fletch/Android/Sdk \
  ./gradlew :app:installFossDebug
```

adb lives at `/home/fletch/Android/Sdk/platform-tools/adb` (not on PATH either).

### Product flavors

The app has 3 flavors (`flavorDimensions += "variant"`): `foss` (default,
no Google Cast), `gms` (Cast + updater), `izzy` (F-Droid/IzzyOnDroid, no
Cast/updater). Because of this, plain task names like `:app:compileDebugKotlin`
or `:app:installDebug` are **ambiguous** — always use the flavored form, e.g.:

- `./gradlew :app:compileFossDebugKotlin` — fast compile check
- `./gradlew :app:installFossDebug` — build + install straight to a connected
  device (this is the normal "deploy and try it" loop; no need to go through
  Android Studio)

### Deploying to a physical device

Fletch has a OnePlus 6T (`ONEPLUS_A6013`) connected over USB most of the time.
Check with `adb devices -l`, then `installFossDebug` as above. The installed
package is `com.metrolist.music.debug`. To launch it:

```bash
adb shell monkey -p com.metrolist.music.debug -c android.intent.category.LAUNCHER 1
```

If the phone screen is off/locked, `adb shell input keyevent KEYCODE_WAKEUP`
wakes it, but **do not** attempt to dismiss a secured (PIN/pattern) lock
screen — ask Fletch to unlock it (check `adb shell dumpsys window | grep
isKeyguardShowing`).

`rsvg-convert`, `imagemagick` (`magick`/`convert`) and `python3` are available
for any future icon/asset rasterization work.

## Fork-specific changes made so far

### 1. Rebrand to "FletchTunes"

- `app/src/main/res/values/app_name.xml` and the two `resValue("string",
  "app_name", …)` calls in `app/build.gradle.kts` (release + debug variant)
  set the display name. Everything else in the app reads `@string/app_name` /
  `R.string.app_name`, so renaming again only requires editing those three
  spots.

### 2. Home screen: Speed Dial always first, Mixes hidden

In `app/src/main/kotlin/com/metrolist/music/ui/screens/HomeScreen.kt`:

- The `HomeSection.AccountPlaylists` entry (labeled "Mixes" in the UI, via
  `R.string.mixes`) is **never added** to the `homeSections` list, so it
  never renders. To re-enable it, restore the
  `if (!chipActive && accountPlaylists?.isNotEmpty() == true) list.add(HomeSection.AccountPlaylists)`
  line that used to sit alongside the other `list.add(...)` calls (~line
  1045-1051).
- After the existing randomize/default-order sort produces `ordered`, one
  more `ordered.sortedByDescending { it == HomeSection.SpeedDial }` pins
  Speed Dial to the front unconditionally — this overrides
  `randomizeHomeOrder` (`PreferenceKeys.kt`, defaults `true`), which would
  otherwise sometimes shuffle Speed Dial below Quick Picks/Daily Discover.

### 3. Speed Dial shuffle-play icon

`SpeedDialGridItem` (`ui/component/SpeedDialGridItem.kt`) takes an optional
`onShuffleClick: (() -> Unit)?`. When set, a shuffle icon renders in the
**top-left** corner (mirroring the pin icon already in the top-right) for
`SongItem` / `AlbumItem` / `PlaylistItem` / `EpisodeItem` (not `ArtistItem`,
nothing to shuffle-play there). It has its own `clickable` so it consumes the
tap before the parent tile's `combinedClickable` (normal tap = play/navigate)
fires.

The handler is wired up in `HomeScreen.kt` where `SpeedDialGridItem` is
called (~line 1588, inside the speed-dial grid page loop):

- `SongItem` / `EpisodeItem` → `ListQueue` with the single item, then
  `player.shuffleModeEnabled = true`.
- `AlbumItem` → `YouTubeAlbumRadio(item.playlistId)` +
  `shuffleModeEnabled = true` (album "shuffle" has no real server-side
  shuffle endpoint in this codebase — `YouTubeAlbumMenu`'s own Shuffle
  button has the same no-op-ish behavior upstream; ExoPlayer's shuffle
  order is the only real shuffle here).
- `PlaylistItem` → prefers `item.shuffleEndpoint` (YouTube's real
  server-shuffled queue) when present, else falls back to `playEndpoint` +
  `shuffleModeEnabled = true`.

Disabled entirely (`onShuffleClick = null`) when `isListenTogetherGuest`.

### 4. App icon — forest-green music-note "F" on slate

Custom adaptive icon: a stylized "F" whose vertical stroke doubles as a music
note's stem (ending in a notehead), and whose two crossbars become note
flags. Forest green gradient on a dark slate gradient background.

**Design source of truth** (100×100 unit coordinate space; the actual shipped
assets are derived from this — see below):

```
stem:      rounded rect x=34 y=16 w=13 h=58 rx=6
top flag:  M47,16 C65,17 72,25 68,37 C66,45 56,44 51,38 C49,35 47,30 47,25 Z
           rotated -12° about (47,22)  [counterclockwise]
mid flag:  M47,40 C59,41 65,46 62,54 C60,59 53,58 50,53 C48,50 47,46 47,43 Z
notehead:  ellipse cx=32 cy=81 rx=13 ry=9, rotated -16° about (32,81)

glyph gradient (top-left → bottom-right): #63b880 → #2f8a57 (55%) → #1a5c39
background gradient (top-left → bottom-right): #3a434b → #181c1f
```

The **whole glyph above is shifted by `translate(4.4, -3.18)`** before
drawing — its raw bounding-box center is `(45.6, 53.18)`, not `(50, 50)`, so
this correction is required to keep it centered. If you ever redo the
geometry, recompute the bbox center and re-derive this offset rather than
copying `4.4, -3.18` blindly.

**Where it lives:**

- `drawable/ic_launcher_foreground.xml` / `drawable/ic_launcher_foreground_v31.xml`
  — the glyph as a `<vector>` with `<aapt:attr>` linear gradients, wrapped in
  a `translate(26.64, 22.09) scale(0.6)` group (this maps the 100-unit design
  + the 4.4/-3.18 recentering into a 108dp adaptive-icon canvas, scaled down
  to fit the ~66dp "safe zone" that survives circular/squircle launcher
  masks — most third-party launchers **do** mask to a circle, so don't skip
  this).
- `drawable/ic_launcher_monochrome.xml` — same shapes, flat black (system
  re-tints for themed/Material You icons).
- `drawable/ic_launcher_background.xml` + `drawable-v31/ic_launcher_background_v31.xml`
  — the slate gradient `<shape>`. (The `-v31` copy used to be a dynamic
  Material-You `system_accent` gradient upstream; it's now pinned to the same
  fixed slate so the icon looks the same on Android 12+ as everywhere else.)
- `values/ic_launcher_background.xml` (`ic_launcher_background` color) and
  `values/colors.xml` (`ic_launcher_static_background`) — flat fallback
  colors, set to `#282f35`.
- Legacy/static assets (used by the "classic icon" `activity-alias` in
  `AndroidManifest.xml`, and as pre-API26 fallbacks): `drawable/
  ic_launcher_static_foreground.png` (1388×1388) and per-density
  `mipmap-{m,h,xh,xxh,xxxh}dpi/{ic_launcher.webp, ic_launcher_round.webp,
  ic_launcher_static.png}`. These are **rasterized PNGs/WEBPs**, not
  generated at build time — if the design changes, regenerate them (see
  below) rather than hand-editing.

**Regenerating the raster assets** — the SVG masters aren't checked into the
repo (they were scratch files). To rebuild them from the geometry above:

1. Write two SVGs at the coordinates/gradients listed above:
   - a 100×100 "full icon" (slate background + glyph + drop-shadow filter +
     a subtle radial-gradient sheen clipped to the glyph shape) for the
     static/legacy combined icons.
   - a 108×108 "foreground only" (transparent bg, glyph wrapped in
     `translate(26.64,22.09) scale(0.6)`) for `ic_launcher_static_foreground.png`.
2. `rsvg-convert -w <size> -h <size> icon-full.svg -o out.png` at 48/72/96/144/192
   (mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi) for `ic_launcher_static.png`.
3. For round variants, circle-mask with ImageMagick:
   `magick out.png \( -size WxH xc:none -fill white -draw "circle cx,cy cx,0" \) -alpha off -compose CopyOpacity -composite round.png`
4. `magick out.png out.webp` / `magick round.png round.webp` for the
   `ic_launcher*.webp` mipmaps.
5. `rsvg-convert -w 1388 -h 1388 icon-foreground.svg -o ic_launcher_static_foreground.png`.

## Things to know before changing the home screen further

`HomeScreen.kt` is large (~2600 lines). The section list is built once in a
`remember(...)` block (~line 1026) keyed on all the relevant data sources,
then ordered either randomly (`randomizeHomeOrder` preference) or by a fixed
`defaultOrder` map — read that block fully before adding/removing/reordering
a section, since both branches need to stay consistent with whatever's in
`homeSections`'s `when` statement below (~line 1700+) that actually renders
each section.
