# FletchTunes: what this fork changes

FletchTunes is Fletch's personal fork of [Metrolist](https://github.com/MetrolistGroup/Metrolist), the YouTube Music client for Android. It is built for one person's daily listening and is not published as an app. Everything below is on `main`; upstream Metrolist is merged in from time to time. Last updated 7 November 2026.

## Where it stands

A debug build is in daily use on Fletch's phone. Queues, the scaled-down app, offline mode and the first battery fixes are in use and confirmed. The newest build (7 November) adds Android Auto improvements, a fix for podcast episodes that would not play, and listening-progress sync to YouTube; these are installed but not yet tested in the car or over a full listen. Battery use during screen-off music playback is still being measured: the first fixes cut CPU per playback hour by about a quarter in real use, and the rest of the gap is under investigation.

## What's different from Metrolist

**Parallel queues.** Like OuterTune, the app keeps several named queues and remembers them across restarts. A Queues tab lists them with "Song N of M", and you can open, rename, switch, delete, or merge one queue into another. Starting something new makes a new queue instead of overwriting the one you were on, and a queue resumes mid-song.

**The queue holds only what you started.** Tapping a song, album or playlist puts just that in the queue. Radio suggestions sit in a separate Autoplay section below it and only play once the queue runs out. A shuffle order is picked once and kept across switches and restarts.

**A smaller app.** One build flag (`DISCOVERY_FEATURES`, off by default) removes the Home and Listen Together tabs, so the app is Search, Queues and Library, opening on Queues. It also stops the background work those screens caused. A single "Discover Mix" entry stays at the top of Library > Playlists, which shows your own playlists in list view. Build with `-PdiscoveryFeatures=true` to get everything back.

**Offline mode.** With no network the app shows and plays only downloaded music, skips songs that aren't downloaded, searches the on-phone library, and switches back by itself when the connection returns.

**Android Auto.** Voice requests from the phone ("play my driving playlist", "shuffle artist X") now reach the app, connecting to the car resumes the last queue, and the car's browse screen has Queues and Discover Mix. The car's buttons lead with shuffle, like and radio.

**Podcasts.** Episodes from RSS-imported podcasts, which always failed to play, now play from the publisher's own audio file. Listening progress is reported back to YouTube, so an episode shows how far you got.

**Battery.** Screen-off playback does less work: the player wakes less often, volume normalisation is cheaper, the lock screen isn't sent a position update every 3 seconds, and screens stop polling while they're hidden. A crash on every reboot before the phone was first unlocked is fixed.

**Look.** Renamed FletchTunes with its own icon, and Speed Dial first on the home screen with a shuffle button on each tile.

## Notes for anyone reading the code

The application id is unchanged (`com.metrolist.music`; debug builds install as `com.metrolist.music.debug`) so upstream merges stay easy. Build and device notes are in [CLAUDE.md](CLAUDE.md); the queue design and its research are in [FLETCHTUNES-QUEUES.md](FLETCHTUNES-QUEUES.md). Commit messages on `main` carry the detail and the measurements behind each change.
