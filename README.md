# Zefiro

An open source game streaming client for [Sunshine](https://github.com/LizardByte/Sunshine)/[Apollo](https://github.com/ClassicOldSong/Apollo)
and the [Scirocco](https://github.com/DanieleS/scirocco) host (our fork of Vibepollo). It started as a fork of
[Artemis](https://github.com/ClassicOldSong/moonlight-android) (itself a fork of Moonlight) and now goes
its own way: it no longer merges from Artemis, and its version numbers start again from 1.0.0.

Zefiro streams your collection of games from your Windows PC to your Android device, at home or over
the internet, and reworks how you *browse* that collection: a cover-flow library enriched with the
Playnite metadata a Scirocco host serves.

It is tailored specifically for the [Ayn Thor](https://www.ayntec.com/products/ayn-thor) handheld: the layout, controls and
library presentation are built around that device. It still runs on other Android devices, but the Thor
is what it is designed for.

# Library

Zefiro's main addition over Artemis is the library. When paired with a
[Scirocco](https://github.com/DanieleS/scirocco) host — which exposes Playnite metadata over its
`/appmetadata` endpoint — the library becomes far richer than a plain grid of app names:

1. A cover-flow presentation with hero backgrounds, alongside an "All games" grid.
2. Playnite metadata per game: cover art, description, genres, developers/publishers, release date and community score.
3. Sort orders you choose from the app bar: host order, name, recently played, release date and community score.
   "Recently played" uses Playnite's own last-played (PC sessions included), falling back to this device's launch history.
4. A companion panel and an in-game menu.
5. Library sync to a folder that external scanning frontends can read.
6. Play statistics, from the app bar: a week, month or year at a time with a chart per day or month,
   the most played games, the library's totals and the games worth picking up again, as
   [CouchPilot](https://github.com/DanieleS/couchpilot) shows them. Scirocco serves them
   (`/appstats`) from the sessions the
   [GameActivity](https://github.com/Lacro59/playnite-gameactivity-plugin) Playnite extension
   records; without it only Playnite's totals are there, and the screen says to install it. Fully
   driven by a gamepad: the bumpers step the period, X and Y the range.
7. Achievements, from what [SuccessStory](https://github.com/Lacro59/playnite-successstory-plugin)
   keeps on the PC (`/appachievements`): per game from its menu ("Stats & achievements", with the
   hours and launches, or with GameActivity the sessions and a twelve-week sparkline), in the
   statistics, and as a toast when one is unlocked during a stream or reported just after it.
8. Screenshot, "Save last 30 s" and recording from the in-game menu, through the PC's Game Bar or
   NVIDIA overlay shortcuts (stream setting "Capture tool").
9. The bitrate, changed mid-stream from the in-game menu, and optionally adapted on its own: it
   steps down when the network loses frames, the round trip swells or the decoder falls behind,
   and climbs back to the bitrate you chose once things are clean (stream setting "Adaptive
   bitrate"). Needs Scirocco's `/bitrate`; the controller is ported from
   [Moonlight V+](https://github.com/qiin2333/moonlight-vplus).
10. Reconnect after a network drop (advanced setting, off by default): the last frame stays on
    screen while the Wi-Fi is gone, and the game comes back through the host's `/resume` once it
    returns. A stock Sunshine/Vibepollo/Scirocco host lets go of a silent client after about 5 s (the ENet
    timeout of its control server, which `ping_timeout` does not change), so the "hold" time
    defaults to 5 s; holding longer only helps with a host changed to keep the session longer.

Everything degrades gracefully on a stock Sunshine/Apollo host: with no metadata to rank by, the
metadata-driven orders simply fall back to alphabetical, so the library still works — it just shows less.
Statistics, achievements and the live bitrate only appear where the host answers for them.

# PyroWave (experimental)

A [Scirocco](https://github.com/DanieleS/scirocco) host can stream [PyroWave](https://github.com/Themaister/pyrowave),
an intra-only wavelet codec encoded and decoded on the GPU. Every frame stands alone, so a lost packet
costs at most that frame (often only some detail in it), and encode and decode take a few milliseconds.
The price is bandwidth: a clean picture needs hundreds of Mbps.

Turn it on in the stream settings ("Use PyroWave when the host offers it", under the codec settings);
it is off by default. It needs a 64-bit device with a Vulkan 1.3 GPU, which the Thor's Adreno 740 is.
PyroWave streams have their own bitrate setting: at 0 it follows Scirocco's guidance of 1.6 bits per
pixel (about 400 Mbps at 1080p120), and the regular bitrate stays for the codecs PyroWave falls back to.
MediaCodec cannot decode PyroWave: the frames go from moonlight-core to a native Vulkan renderer
(`app/src/main/jni/pyrowave-renderer`), on the fragment decode path on Adreno. The codec is
vendored at the exact commit Scirocco encodes with (`app/src/main/jni/pyrowave/VENDOR.txt`), since the
bitstream has no version field; a host that advertises another revision, or no PyroWave at all, gets the
usual codecs. 8-bit 4:2:0 SDR only for now: with a PyroWave host the app does not ask for HDR. PyroWave
is not used on metered networks or in the 3D render modes.

# Inherited features

Zefiro carries the Artemis feature set, as it stood when it forked, on top of upstream Moonlight, including:

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

* [Download the APK directly](https://github.com/DanieleS/zefiro/releases) — grab the `nonRoot` APK from the latest release.
* [Use Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22dev.kylobyte.zefiro%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2FDanieleS%2Fzefiro%22%2C%22author%22%3A%22DanieleS%22%2C%22name%22%3A%22Zefiro%22%2C%22additionalSettings%22%3A%22%7B%5C%22apkFilterRegEx%5C%22%3A%5C%22nonRoot%5C%22%2C%5C%22matchGroutToUse%5C%22%3A%5C%22%241%5C%22%2C%5C%22versionExtractionRegEx%5C%22%3A%5C%22v(.%2B)%5C%22%7D%22%7D) (recommended) — auto-updates from the GitHub releases.

## Building

* Install Android Studio and the Android NDK.
* Run `git submodule update --init --recursive` from within the repository. In a clone made before moonlight-common-c moved to DanieleS/moonlight-common-c, run `git submodule sync --recursive` first so the submodule fetches from the new URL.
* Create a `local.properties` file in the repository root. Add an `ndk.dir=` property pointing at your NDK directory.
* Build the APK using Android Studio or Gradle. The installable flavor is `nonRoot_game` (e.g. `./gradlew :app:assembleNonRoot_gameRelease`).

## Credits

Zefiro builds on the work of:

* [Artemis](https://github.com/ClassicOldSong/moonlight-android) by [ClassicOldSong](https://github.com/ClassicOldSong).
* PyroWave by [Hans-Kristian Arntzen](https://github.com/Themaister/pyrowave); its streaming protocol and
  framing parser by [Nonary](https://github.com/Nonary/moonlight-common-c); the Android Vulkan renderer from
  [LavArtemis](https://github.com/Lavagnou/LavArtemis) and the Adreno tuning from
  [joemossjr16](https://github.com/joemossjr16/artemis-android-pyrowave).
* Moonlight, the work of students at [Case Western](http://case.edu), started as a project at [MHacks](http://mhacks.org):
  [Cameron Gutman](https://github.com/cgutman), [Diego Waxemberg](https://github.com/dwaxemberg),
  [Aaron Neyer](https://github.com/Aaronneyer) and [Andrew Hennessy](https://github.com/yetanothername).
