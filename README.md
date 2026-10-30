# TruePlayer

**English** · [Português (Brasil)](README.pt-BR.md)

A music player and tag editor for Android. It plays the files in your music folder, downloads songs from links, fixes the tags by itself, and can keep everything in a GitHub repository. No account, no ads, no tracking.

## Features
- **Player:** library, albums, artists, genres, playlists, queue, shuffle, repeat, lock screen and headphone controls.
- **Tag editor:** title, artist, album, cover, year, track, genre and more.
- **Download by link:** share a YouTube, SoundCloud or Spotify link (song, album or playlist). Spotify audio is protected, so each song is found on YouTube Music, YouTube or SoundCloud (up to 100 songs per Spotify playlist). Cancel one song or the whole playlist from the notification.
- **Automatic tags:** after a download it looks the song up on Apple Music, Deezer and Genius and fills in the tags and cover. If it is not sure, it leaves them alone.
- **Statistics:** most played songs, albums and artists by minutes or by plays, for the week, month, 6 months, year or all time.
- **Portable folder:** everything lives in the folder you pick (`songs/`, `playlists/` and `.trueplayer/` for statistics and settings). Changing phones loses nothing.
- **Git:** commit and push the folder to GitHub, in the background, resuming if it fails.
- **Light and dark theme, 21 languages** (following the phone, or your choice). Translations have no native-speaker review, so corrections are welcome.
- **Automatic updates** from [Releases](https://github.com/mauricioaires30/TruePlayer/releases).

## Install
1. On your phone, download the latest `.apk` from [Releases](https://github.com/mauricioaires30/TruePlayer/releases).
2. Open it and allow "install unknown apps" for the app you opened it with.
3. On first launch, pick your music folder and grant the permissions asked.

Needs Android 8.0 or newer on a 64-bit (arm64) phone. After the first install the app updates itself.

## Permissions and privacy
Audio files (play the songs), notifications (player, downloads, updates), internet (downloads, tag lookup, updates, GitHub), install unknown apps (self-update), and all files access **only** for the Git feature. Battery optimization off is optional.

Your library, playlists and statistics stay on your phone. The app goes online only for features that need it: downloads, tag and cover lookup (only song and artist name are sent), update checks and GitHub (the token is encrypted on the phone).

## Build
Needs JDK 21 and Android SDK 36.

```
cd TruePlayer-android
gradle :app:assembleDebug
gradle :app:testDebugUnitTest
```

The debug APK is signed with your machine's key, so it will not update over the official one. Official APKs are built, signed and published by GitHub Actions (`.github/workflows/`) from `RELEASE_NOTES.md`.

## License
Copyright © 2026 mauricioaires30. Distributed under the **GNU GPL v3** (see [LICENSE](LICENSE)): you may use, modify and share it as long as the result stays under GPL-3.0 with the source available. No warranty. GPL is required because the app bundles [youtubedl-android](https://github.com/JunkFood02/youtubedl-android) and FFmpeg (GPL-3.0).

Other components keep their own licenses: [yt-dlp](https://github.com/yt-dlp/yt-dlp) (Unlicense), [jaudiotagger](https://github.com/ijabz/jaudiotagger) (LGPL), [JGit](https://github.com/eclipse-jgit/jgit) (BSD-3-Clause), and Kotlin, Jetpack Compose, AndroidX, Ktor, kotlinx.* (Apache-2.0).

**Content notice:** TruePlayer is a tool. You are responsible for downloading only content you have the right to download and for following the terms of the services the links come from. The project is not affiliated with or endorsed by YouTube, Google, SoundCloud, Spotify, Apple, Deezer, Genius or GitHub. This text is not legal advice.
