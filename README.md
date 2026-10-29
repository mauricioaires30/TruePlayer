# TruePlayer

**English** · [Português (Brasil)](README.pt-BR.md)

A music player and tag editor for Android. It plays the files in your music folder, shows what you listen to, downloads songs from YouTube and SoundCloud links (playlists included), fixes the tags by itself, and can keep everything in a GitHub repository.

No account, no ads, no tracking. Your library stays on your phone.

## Features
- **Library and player:** songs, albums, artists, genres and playlists, with queue, shuffle, repeat, and control from headphones, the lock screen and the system media buttons. The song that is playing gets animated waves behind its row and a wavy progress bar.
- **Tag editor:** title, artist, album, cover art, year, track number, genre and more.
- **Download by link:** share a YouTube or SoundCloud link to TruePlayer. A playlist link becomes an app playlist with the songs in their original order, downloaded one at a time.
- **Automatic tag fixing:** after a download it looks the song up on Apple Music, Deezer and Genius and fills in title, artist, album and cover. If it is not sure, it leaves the tags alone.
- **Statistics:** listening time and play counts per song, album and artist (week, month, 6 months, year and all time).
- **Organized, portable folder:** everything lives in the folder you pick (`songs/` for music, `playlists/` for `.m3u` playlists, `.trueplayer/` for statistics and settings). Switching phones loses nothing.
- **Git:** keeps the folder in a GitHub repository. Commit and Send are separate buttons; sending runs in the background (progress in the notification), goes in parts and resumes where it stopped if it fails. Statistics, settings and album covers go along. "Download from GitHub" restores everything on a new phone.
- **Automatic updates:** checks [Releases](https://github.com/mauricioaires30/TruePlayer/releases) for new versions, shows what changed and installs after you confirm.

## Install
1. Open the [Releases](https://github.com/mauricioaires30/TruePlayer/releases) tab on your phone and download the latest `.apk`.
2. Open the file. Android will ask you to allow "install unknown apps" for the app you opened it with (browser or file manager).
3. On first launch, pick your music folder and grant the permissions the app asks for.

Requires Android 8.0 or newer on a 64-bit (arm64) phone. After the first install the app updates itself.

## Permissions
| Permission | What it is for |
|---|---|
| Audio files | List and play the songs in the folder |
| Notifications | Player controls, download progress and update notices |
| Internet | Download songs, look up tags and covers, check for updates and push to GitHub |
| Install unknown apps | Install the updates the app downloads itself |
| All files access | **Only** for the Git feature, which works directly on the folder's files |
| Run in background (no battery optimization) | Optional: downloads, GitHub sends and update notices keep working with the app closed |

## Privacy
There is no account, sign-up, ad or tracking. Your library, playlists and statistics stay on your phone, in the folder you chose. The app only goes online when you use a feature that needs it:
- downloads (YouTube/SoundCloud);
- tag and cover lookup (Apple Music, Deezer and Genius), which receives only the song and artist name;
- update checks (GitHub);
- sending to GitHub, when you use Git (the token is encrypted and stored only on the phone).

## Repository layout
```
TruePlayer-android/   Android project (Kotlin + Jetpack Compose)
  app/                app source code
  gradle/             dependency version catalog
LICENSE               license (GNU GPL v3)
RELEASE_NOTES.md      what changed in the version (becomes the release text)
.github/workflows/    automatic release publishing
```
Each version's APK is not stored in the repository: GitHub Actions builds, signs and publishes it to Releases on every push with a new version.

## Build
Requires JDK 21 and the Android SDK 36.

```
cd TruePlayer-android
gradle :app:assembleDebug
gradle :app:testDebugUnitTest
```

The APK ends up in `TruePlayer-android/app/build/outputs/apk/debug/`. It is signed with your machine's debug key, so it will **not** update over the official APK from Releases (the signature differs).

## Rights and license
Copyright © 2026 mauricioaires30. All copyright in the TruePlayer code belongs to the author.

TruePlayer is distributed under the **GNU General Public License, version 3** (see [LICENSE](LICENSE)). In practice:
- you may use, study, copy and modify the app, and share your changes, **as long as** you keep the copyright notice and distribute the result under GPL-3.0 as well, with the source code available;
- there is no warranty of any kind (see sections 15 and 16 of the license).

**Why GPL-3.0 and not "all rights reserved":** TruePlayer uses the `youtubedl-android` library and the FFmpeg it bundles, both GPL-3.0. Code that incorporates them and is distributed must follow the same license. This does not take away the authorship of the original code, but it prevents restricting whoever receives the app.

### Third-party components
Each component stays under its own license:

| Component | License |
|---|---|
| [youtubedl-android](https://github.com/JunkFood02/youtubedl-android) and FFmpeg (bundled by it) | GPL-3.0 |
| [yt-dlp](https://github.com/yt-dlp/yt-dlp) | Unlicense |
| [jaudiotagger](https://github.com/ijabz/jaudiotagger) | LGPL |
| [JGit](https://github.com/eclipse-jgit/jgit) | BSD-3-Clause (Eclipse Distribution License) |
| Kotlin, Jetpack Compose, AndroidX (WorkManager and others), Ktor, kotlinx.* | Apache-2.0 |

The complete source code is in this repository, which also satisfies the LGPL requirement to let you replace the jaudiotagger library.

### Content notice
TruePlayer is a tool. **You are responsible** for downloading only content you have the right to download, and for respecting the terms of the services the link comes from and the artists' copyrights. The project is not affiliated with, associated with or endorsed by YouTube, Google, SoundCloud, Apple, Deezer, Genius or GitHub. Those names belong to their respective owners.

This text describes the project's intent and is not legal advice. If in doubt about licenses, consult a professional.
