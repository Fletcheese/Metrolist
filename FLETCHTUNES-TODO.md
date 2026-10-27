# FletchTunes to-do (pick up on the Linux box)

## 0. Retest the four queue-bug fixes (branch `claude/fletchtunes-queue-bugs-04546c`)

Fixed and pushed from Windows on 2026-10-26, after each bug was reproduced on the 6T: `faf55e2` (a new queue overwrote the active one), `834d1f4` (speed-dial shuffle did nothing on pinned playlists), `5919685` ("Song N of M" on each queue), `0200e61` (resume mid-song). The branch is based on the queues branch (`cc65d811`), not `main`; merging is Fletcher's call.

Not yet retested on the phone: the Windows build could not install, because the phone's app is signed by the Linux box's debug key (cert SHA-256 `807e40c9…6ad6`). On Linux:

- `git fetch && git checkout claude/fletchtunes-queue-bugs-04546c`, then `./gradlew :app:installFossDebug` (no uninstall, so the data stays).
- Bug 1: with a queue playing, shuffle a different local playlist. Expected: a new queue appears, and the old one keeps its songs and position. Before the fix, the old queue was rewritten with the new playlist's songs at song 1.
- Bug 2: tap the shuffle icon (top-left) on a pinned speed-dial playlist. Expected: it plays shuffled as a new queue. Before the fix, nothing happened.
- Bug 3: the Queues tab reads "Song N of M", and N advances as tracks change.
- Bug 4: leave a song partway through (e.g. at 1:06), switch to another queue, then tap the first one again. Expected: it resumes near 1:06. Before the fix, it restarted at 0:00.
- What a queue is (agreed 2026-10-26): shuffle always creates a new queue, saved in its shuffled order; it is never reshuffled automatically; opening a saved queue keeps that order, so shuffle shows "off" while it plays.
- To also build on Windows later, copy the Linux `~/.android/debug.keystore` to `app/persistent-debug.keystore` there (it is gitignored).

## 1. Option A: rebuild the debug app as "FletchTunes" (do this first)

The debug build type now sets the launcher label to `FletchTunes` instead of `FletchTunes Debug`. The package id (`com.metrolist.music.debug`) and `applicationIdSuffix` are unchanged, so it installs over the current app and keeps its data.

- Build and install from the same Linux box that built the current app: `./gradlew :app:installFossDebug`.
- It only installs over the existing app if it is signed with the same debug key. The build uses `app/persistent-debug.keystore` if that file exists (it is gitignored), otherwise `~/.android/debug.keystore`. Use whichever the Linux box used last time; a different key means Android refuses the update, and uninstalling first loses the data.
- Confirm the launcher label on the phone reads "FletchTunes".

## 2. Option B, later: a proper signed release

- Create the release keystore once: `keytool -genkeypair -v -keystore release.keystore -alias fletchtunes -keyalg RSA -keysize 4096 -validity 10000`.
- Back up the keystore and both passwords off-machine. Losing them means no future update can install over the app.
- The build already defines `signingConfigs.release`, which reads `app/keystore/release.keystore` and the `STORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` environment variables. Put the keystore there (`*.keystore` and `*.jks` are now gitignored) and set the variables.
- Add `signingConfig = signingConfigs.getByName("release")` to the `release` build type in `app/build.gradle.kts`.
- Build `./gradlew :app:assembleFossRelease`.
- Move the data: in the old app, Settings → Backup and restore → Backup; install the release app; Restore; re-download songs; then uninstall the debug app.

## 3. Own application id, at the same time as B

- Change `applicationId` from upstream's `com.metrolist.music` (the `baseApplicationId` value) to `dev.fletcheese.fletchtunes` (Fletcher owns fletcheese.dev). A new id is a new app either way, so do it once, together with B.
- Change only `applicationId`, not the Kotlin `namespace`.
- Update anything that names the old package: Android Auto, and TalkToMe's "open FletchTunes".

## 4. Google Play caveat: check before investing

Play policy bars apps that download or play YouTube content outside YouTube's API terms, and Metrolist-style YouTube Music clients are normally distributed through F-Droid, IzzyOnDroid or GitHub rather than Play. Research the current policy before planning FletchTunes as part of a Play suite. The Play developer account (one-time fee) and the choice between your own keystore and Play App Signing apply to every app in the suite.

## 5. Sync with upstream before B

This fork was 63 commits behind upstream `MetrolistGroup/Metrolist` on 2026-10-21. Sync it before building the release.

## 6. Remember queues: fix queue loss, then OuterTune-style multiple queues

FletchTunes sometimes forgets the queue when the app closes, and Fletcher wants OuterTune-style parallel queues. Research, hypotheses, repro steps, the phased plan and four open questions are in `FLETCHTUNES-QUEUES.md`. Answer the questions and reproduce the loss on the phone before changing any code; do item 5 (upstream sync) first.
