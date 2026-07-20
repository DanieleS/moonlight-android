# Ratatoskr

A fork of [Artemis](https://github.com/ClassicOldSong/moonlight-android) (itself a fork of Moonlight),
an open source client for [Sunshine](https://github.com/LizardByte/Sunshine)/[Apollo](https://github.com/ClassicOldSong/Apollo)
and the [Vibepollo](https://github.com/DanieleS/Vibepollo) fork.

Ratatoskr streams your collection of games from your Windows PC to your Android device, at home or over
the internet, and reworks how you *browse* that collection: a cover-flow library enriched with the
Playnite metadata a Vibepollo host serves.

# Library

Ratatoskr's main addition over upstream Artemis is the library. When paired with a
[Vibepollo](https://github.com/DanieleS/Vibepollo) host — which exposes Playnite metadata over its
`/appmetadata` endpoint — the library becomes far richer than a plain grid of app names:

1. A cover-flow presentation with hero backgrounds, alongside an "All games" grid.
2. Playnite metadata per game: cover art, description, genres, developers/publishers, release date and community score.
3. Sort orders you choose from the app bar: host order, name, recently played, release date and community score.
   "Recently played" uses Playnite's own last-played (PC sessions included), falling back to this device's launch history.
4. A companion panel and an in-game menu.
5. Library sync to a folder that external scanning frontends can read.

Everything degrades gracefully on a stock Sunshine/Apollo host: with no metadata to rank by, the
metadata-driven orders simply fall back to alphabetical, so the library still works — it just shows less.

# Inherited features

Ratatoskr carries the full Artemis feature set on top of upstream Moonlight, including:

1. Custom virtual buttons with import and export support.
2. [Custom resolutions](https://github.com/moonlight-stream/moonlight-android/pull/1349).
3. Custom bitrates.
4. [Multiple mouse mode switching](https://github.com/moonlight-stream/moonlight-android/pull/1304) (normal mouse, [multi-touch](https://github.com/moonlight-stream/moonlight-android/pull/1364), touchpad, disabled, local cursor mode).
5. Optimized virtual gamepad skins and free joystick.
6. External monitor mode.
7. Joycon D-pad support.
8. Simplified performance information display.
9. [Game back menu](https://github.com/moonlight-stream/moonlight-android/pull/1171).
10. Custom shortcut commands.
11. Easy soft keyboard switching.
12. Portrait mode.
13. Display on top mode, useful for foldable phones.
14. [Virtual touchpad space and sensitivity adjustment](https://github.com/moonlight-stream/moonlight-android/issues/1348#issuecomment-2236344729) for playing right-click view games, such as Warcraft.
15. Force use device's own vibration motor (in case your gamepad's vibration is not effective).
16. Gamepad debugging page to view gamepad vibration and gyroscope information, as well as Android kernel version information.
17. Trackpad tap/scrolling support
18. Natural track pad mode with touch screen
19. Non-QWERTY keyboard layout support
20. Quick Meta key with physical BACK button
21. Frame rate lock fix for some devices
22. Video scale mode: Fit/Fill/Stretch
23. View pan/zoom support
24. Rotate screen in-game
25. Add option to quit app directly
26. Samsung DeX scrolling support
27. Proper click/scroll/right-click for trackpad on generic Android tablet when using local cursor
28. Virtual Display integration with [Apollo](https://github.com/ClassicOldSong/Apollo)
29. Server Command integration with [Apollo](https://github.com/ClassicOldSong/Apollo)
30. Clipboard sync (requires Apollo)
31. SBS 3D for external Displays (Using AI MiDaS v2 Lite)

## Downloads

* [Download the APK directly](https://github.com/DanieleS/moonlight-android/releases) — grab the `nonRoot` APK from the latest release.
* [Use Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22dev.kylobyte.ratatoskr%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2FDanieleS%2Fmoonlight-android%22%2C%22author%22%3A%22DanieleS%22%2C%22name%22%3A%22Ratatoskr%22%2C%22additionalSettings%22%3A%22%7B%5C%22apkFilterRegEx%5C%22%3A%5C%22nonRoot%5C%22%2C%5C%22matchGroutToUse%5C%22%3A%5C%22%241%5C%22%2C%5C%22versionExtractionRegEx%5C%22%3A%5C%22v(.%2B)%5C%22%7D%22%7D) (recommended) — auto-updates from the GitHub releases.

## Building

* Install Android Studio and the Android NDK.
* Run `git submodule update --init --recursive` from within the repository.
* Create a `local.properties` file in the repository root. Add an `ndk.dir=` property pointing at your NDK directory.
* Build the APK using Android Studio or Gradle. The installable flavor is `nonRoot_game` (e.g. `./gradlew :app:assembleNonRoot_gameRelease`).

## Credits

Ratatoskr builds on the work of:

* [Artemis](https://github.com/ClassicOldSong/moonlight-android) by [ClassicOldSong](https://github.com/ClassicOldSong).
* Moonlight, the work of students at [Case Western](http://case.edu), started as a project at [MHacks](http://mhacks.org):
  [Cameron Gutman](https://github.com/cgutman), [Diego Waxemberg](https://github.com/dwaxemberg),
  [Aaron Neyer](https://github.com/Aaronneyer) and [Andrew Hennessy](https://github.com/yetanothername).
