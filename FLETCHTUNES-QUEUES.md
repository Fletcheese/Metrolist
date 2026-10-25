# FletchTunes queues: persistence bug and OuterTune-style queues

Research only, done on 2026-10-24 on the Windows box. No code has been changed. This file is the handover for a fresh Claude session on the Linux box, which has no memory of that conversation. Read `CLAUDE.md` (build environment, device) and `AGENTS.md` (repo rules) first.

## What Fletcher asked

> "FletchTunes forgets my queue when it closed. Can we remember queues? The app I used to use was called 'OuterTune' and it had this neat parallel queue management system. Can we do something similar?"

So there are two asks: fix the lost queue (a bug), and add multiple remembered queues (a feature).

## How queue persistence works today

All in `app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt`. Line numbers were checked against commit `838473b45`; they drift after any upstream sync, so search for the names.

- The queue is serialised to `files/persistent_queue.data` with Java `ObjectOutputStream` in `saveQueueToDisk()` (l.4078). The automix list goes to `persistent_automix.data` alongside it. The write runs on the service scope, not a background dispatcher, and is a plain truncate-and-rewrite.
- `saveQueueToDisk()` is called on:
  - song change, `onMediaItemTransition` (l.2596)
  - playback-state change, `onPlaybackStateChanged` (l.2643)
  - shuffle toggle (l.2822) and repeat toggle (l.2835)
  - a 60 s timer loop (l.1261–1266)
  - `onDestroy` (l.4270–4271)
- Playback position is saved every 15 s to `persistent_player_state.data` by a second timer loop right after the first (l.1268 onwards, `savePlayerStateToDisk()`).
- Restore happens in the service `onCreate` (l.1180): it reads the file and calls `playQueue(queue = restoredQueue, playWhenReady = false, restoringQueue = true)` (l.1195–1199).
- On any read or restore failure, `clearPersistedQueueFiles()` (defined l.1467) silently deletes all three files (calls at l.1204, 1208, 1225, 1229, 1255). The only trace is a Timber warning.
- Setting: `PersistentQueueKey`, shown as "Persistent queue" in Settings › Player, default true.
- Upstream `MetrolistGroup/Metrolist` has not changed this code since the fork base (`eebff5b54`, 18 Sep). There is no matching upstream issue; the closest is #4387 (radio cache).

## Hypotheses, most likely first

1. The file becomes unreadable and is silently deleted. Writes are not atomic (truncate then rewrite, so a kill mid-write leaves a corrupt file). `PersistQueue`, `MediaMetadata` and `PersistPlayerState` (in `models/`) declare no `serialVersionUID`, so any build that changes their shape cannot read the old file. That is likely on every reinstall after an upstream sync.
2. A new playback replaces the single restored queue: a Speed Dial tap, tapping a song, or Bluetooth auto-start (upstream #4206).
3. Queue edits (add, remove, move, play next) fire `EVENT_TIMELINE_CHANGED`, whose handler (l.2766) only updates metadata and never saves. A kill within about 60 s of an edit loses those edits (partial loss, not the whole queue).
4. The setting is off, for example after restoring a backup. "Stop music on task clear" only stops playback; `onDestroy` still saves.
5. Minor: when `playQueue` is called before the player is ready it re-calls itself without passing `restoringQueue` (l.1765), which resets shuffle on restore. The position restore uses a blind `delay(1000)` (l.1243).

## Reproduce BEFORE fixing

Do not change code until one of these reproduces. The debug build is debuggable, so `run-as` works. Package: `com.metrolist.music.debug`. `adb` is at `/home/fletch/Android/Sdk/platform-tools/adb` (not on PATH).

- Settings › Player: note whether "Persistent queue" and "Stop music on task clear" are on.
- Logs: `adb logcat -v time MusicService:* AndroidRuntime:E *:S`, and in a second terminal `adb logcat -b events | grep -E "am_kill|am_proc_died"`. Watch for "Queue saved successfully", "Skipping queue save", "Failed to read persisted queue, clearing data".
- Before and after each close: `adb shell run-as com.metrolist.music.debug ls -la files/`.
- Matrix: play a playlist, add 3 songs, then:
  - (a) back button
  - (b) swipe from recents within 60 s of the edit, and again after 70 s
  - (c) `adb shell am force-stop com.metrolist.music.debug`
  - (d) pause, background, then `adb shell am kill com.metrolist.music.debug`
  - (e) reboot
  - (f) install a new build over the old one
- OnePlus battery management: `adb shell dumpsys deviceidle whitelist` and `adb shell cmd appops get com.metrolist.music.debug RUN_ANY_IN_BACKGROUND`.

## Plan

### Phase 0: sync with upstream

Do item 5 in `FLETCHTUNES-TODO.md` (sync with upstream) first, so the fix is written against current code.

### Phase 1: persistence fix

Only after hypothesis 1 or 3 reproduces. About 80–120 lines, half a day to a day, in `MusicService.kt`, `models/PersistQueue.kt` and `models/MediaMetadata.kt` (plus `models/PersistPlayerState.kt`).

- Atomic writes: `AtomicFile`, or write to a tmp file and rename.
- Pin `serialVersionUID` to the currently computed values (use `serialver` or read it from a failing deserialisation) so existing files stay readable.
- Save on `EVENT_TIMELINE_CHANGED`, debounced about 2 s, and in `onTaskRemoved` (l.4311).
- On read failure, rename the file to `.bad` instead of deleting it.
- Pass `restoringQueue` through in the re-call at l.1765.

Conflict risk is medium (upstream touched `MusicService.kt` five times in the last month) but the hunks are small. Consider offering it upstream as a PR.

### Phase 2a (recommended): recent queues

Keep the single live queue, and store up to N snapshots whenever `playQueue` replaces a queue.

- New `playback/QueueHistory`, a hook in `playQueue`, and a list in `ui/player/Queue.kt` that restores a snapshot via `playQueue(ListQueue)` with its own position and shuffle state.
- Store in a file or a small separate DB. No change to the main schema (`AGENTS.md` rule 5: do not edit the schema unless absolutely necessary; `MusicDatabase` is at version 38).
- About 400–600 lines, 2–3 days, low to medium conflict risk.

### Phase 2b: full OuterTune QueueBoard port

Merge and dedupe rules, live editing of inactive queues, OuterTune's shuffle model.

- About 2,000–3,000 lines, 1–2 weeks, a Room migration, and a rewrite of most queue code in `MusicService` and `Queue.kt`.
- High conflict risk on every upstream sync: it makes the playback core a permanent fork.
- The code must be adapted, not dropped in: package names, `MediaMetadata` fields and song foreign keys differ.
- Hazard: QueueBoard becomes the source of truth, so every path that edits the player directly has to go through it: add, move and remove; ExoPlayer shuffle; auto-load-more and radio continuation; Android Auto's `MediaLibrarySessionCallback`.
- OuterTune does not persist radio continuation.

## OuterTune reference

- Repo: https://github.com/OuterTune/OuterTune (`DD3Boh/OuterTune` redirects there). GPL-3.0 like Metrolist, so code can be adapted with attribution. Unmaintained; its README recommends Metrolist.
- The default branch `lite` is local-only. Port from `dev` (Feb 2026) or `release/0.10.x`.
- What the user sees:
  - one queue per source, titled after it (renamable)
  - replaying a source reuses its queue and adds only new songs
  - "Add to queue" makes a "<title> +" queue
  - up to 19 queues by default (1–30, in Experimental settings); the oldest is dropped
  - the queue sheet lists queues most recent first; tap previews, play switches, each row can be deleted
  - each queue keeps its shuffle state, current song and position, and all of them persist
- Code, under `app/src/main/java/com/dd3boh/outertune/`:
  - `playback/QueueBoard.kt` (about 940 lines; saves debounced 5 s behind a mutex)
  - `models/MultiQueueObject.kt`
  - Room tables `queue` and `queue_song_map` (`db/daos/QueueDao.kt`, `saveQueue` in `DatabaseDao.kt`)
  - UI: `ui/player/Queue.kt`, `ui/dialog/QueueDialogs.kt`

## Build and install (Linux box)

With the OnePlus 6T on USB:

```bash
JAVA_HOME=/home/fletch/.jdks/jbr-21.0.11 ANDROID_HOME=/home/fletch/Android/Sdk ./gradlew :app:installFossDebug
```

It installs as `com.metrolist.music.debug`. It must be signed with the same debug key as the installed app (`app/persistent-debug.keystore` if present, otherwise `~/.android/debug.keystore`), or the install fails, and uninstalling first wipes the app's data, queue included. See item 1 in `FLETCHTUNES-TODO.md`.

## Open questions for Fletcher

Answer these before Phase 1 or 2.

1. What does "closed" mean: back button, swiped from recents, phone restart, reopening hours later? Does it happen right after installing a new build?
2. Is the whole queue gone (empty player), or replaced by something newly started?
3. Is Settings › Player › Persistent queue on? Is Stop music on task clear on?
4. Option 2a (recent queues) or 2b (full OuterTune port)?

## Live repro (2026-10-24, Linux box, OnePlus 6T)

Phone was USB-connected, FletchTunes debug already installed at current HEAD
(`d77e3046`, versionCode 153, `lastUpdateTime` matches the commit time, so the
build under test is current). A real session was already in progress: queue
size 57, paused, position 107397 ms, active item id 7 ("Lizzo: NPR Music Tiny
Desk Concert").

**Blocker:** the phone's screen was locked with a pattern/fingerprint
(trust-agent "on-body" lock had lapsed — `dumpsys trust` showed
`deviceLocked=1`). Per this repo's own `CLAUDE.md` ("do not attempt to
dismiss a secured (PIN/pattern) lock screen — ask Fletch to unlock it"), I
did not try to bypass it. That rules out driving the UI (tapping into the
queue to add/remove/reorder a song), so the specific case Fletcher asked for
— edit the queue, then kill within the ~60 s save window (case c/d) — could
**not** be completed end-to-end live in this session.

**What I did run (case c, no screen needed):**

1. Captured state via `dumpsys media_session`: size=57, state=2 (paused),
   position=107397, item id=7; and `md5sum` of all three persisted files.
2. `adb shell am force-stop com.metrolist.music.debug` — a harder kill than
   `am kill` (fully stops the process, not just low-memory reclaim).
3. Confirmed the files were untouched by the kill itself (same md5sums).
4. Relaunched via `am start` (activity queues behind the lock screen, not
   visible, but the service/process still comes up and restores).
5. `dumpsys media_session` again: size=57, state=2, position=107397, item
   id=7, same metadata — **exact restore**, no data loss.
6. Checked `logcat` across the whole cycle: no corruption/clear-data
   warnings; "Queue saved successfully" fired normally once playback resumed
   after restore.

**Conclusion of what was tested:** with no edit in the last 60 s before the
kill, force-stop → restore round-trips perfectly in the current build. This
is live evidence against hypothesis 1 (corruption / unreadable file) as a
general cause — the file read back fine, no `serialVersionUID` mismatch bit
us here because no new build was installed between save and restore. It does
not clear hypothesis 1 for the "new build installed, old file present" case
(f) — that needs a real reinstall-over-existing-data test, which is screen-
independent and still worth doing.

**Code re-check against current `d77e3046` (not just the stale `838473b45`
line numbers from the first pass):**

- `MusicService.onEvents()` (now ~l.2778-2801): `EVENT_TIMELINE_CHANGED` /
  `EVENT_POSITION_DISCONTINUITY` still only does
  `currentMediaMetadata.value = player.currentMetadata` — it still never
  calls `saveQueueToDisk()`. Hypothesis 3's mechanism is confirmed present,
  unchanged by the upstream merge: a queue edit (add/remove/move/play next)
  that doesn't also trigger `onMediaItemTransition` or
  `onPlaybackStateChanged` is invisible to the save path until the 60 s
  timer or `onDestroy`.
- `playQueue()` (now ~l.1788-1799): the "player not initialized yet" branch
  still re-calls itself as `playQueue(queue, playWhenReady)`, dropping
  `restoringQueue`. Confirmed still present. This branch is plausible on
  *every* cold-start restore (player may not be ready yet when
  `onCreate`'s restore call fires), so it's more than "minor" — worth
  rechecking whether it reproduces as a shuffle-reset-on-restore symptom,
  separate from the "queue forgotten" complaint.

**Net assessment:** hypothesis 3 (edit not saved within the window) is now
the best-supported explanation — baseline persistence/restore works, and the
code path that would drop an un-timered edit is confirmed still live — but
it is *not yet confirmed by the literal live repro Fletcher asked for*
(make an edit, kill within 60 s, see the edit specifically lost). That step
needs either the phone unlocked so a scripted `input tap` sequence can drive
an add/remove/reorder, or Fletcher running the matrix himself (case c/d with
an actual queue edit, in Settings › Player check Persistent queue / Stop on
task clear beforehand) and reporting what he sees.

## Status and next step

No code changed. Phase 1 (persistence fix) is **not started** — per
Fletcher's explicit instruction, the fix should not be written until the
live repro confirms root cause, and the edit-then-kill case specifically
could not be run due to the locked device. Next: either (a) Fletcher unlocks
the phone or hands over a PIN/pattern he's comfortable having scripted, so
the edit+kill repro can be completed, or (b) Fletcher runs that one case
himself and reports the result, or (c) if he's comfortable proceeding on the
strength of the code-confirmed mechanism + baseline live test above, say so
explicitly and Phase 1 can start from there.
