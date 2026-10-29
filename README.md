# GPS Setter

[![release](https://img.shields.io/github/v/release/varma322/android-gps-setter)](https://github.com/varma322/android-gps-setter/releases)
[![build](https://img.shields.io/github/actions/workflow/status/varma322/android-gps-setter/apk.yml)](https://github.com/varma322/android-gps-setter/actions/workflows/apk.yml)
[![license](https://img.shields.io/github/license/varma322/android-gps-setter)](https://github.com/varma322/android-gps-setter/blob/main/LICENSE)
[![issues](https://img.shields.io/github/issues/varma322/android-gps-setter)](https://github.com/varma322/android-gps-setter/issues)

An Xposed module that sets your device's location, built on the modern [libxposed](https://github.com/libxposed/api) API for LSPosed 2.x / Vector.

This is a fork of [jqssun/android-gps-setter](https://github.com/jqssun/android-gps-setter), which builds on [Android1500/GpsSetter](https://github.com/Android1500/GpsSetter).

## What's different in this fork

- **Modern Xposed API (libxposed 101).** No "deprecated feature" warning in LSPosed 2.x. Settings reach the hook through LSPosed's remote preferences instead of a world-readable file, so the module keeps working after LSPosed 2.3.0 removes that file-based sharing.
- **System-wide spoofing with continuous fix generation (Android 12+).** The module actively generates and dispatches spoofed fixes at 1 Hz inside the system server across GPS, fused, and network providers. Google Location Accuracy can stay enabled, and Play-services apps follow the spoofed location without scoping even indoors.
- **Real location during emergencies.** While an emergency call or SMS is active (Android 14+), real fixes pass through untouched and fake fix generation is suspended.
- **System hooks fixed for Android 11–13.** The location service moved packages in Android 11, so the system hook never ran there.
- **Start, stop and location changes apply immediately**, without a reboot.
- **Working maps in the FOSS build.** It uses keyless [OpenFreeMap](https://openfreemap.org) tiles, because the previously bundled Mapbox token was revoked.
- **Coordinate-safe app hooks.** Injects WGS-84 fixes at Android's location-delivery boundary without overwriting coordinates that a map SDK has converted to GCJ-02 or BD-09.
- **China map alignment in the Full build.** Google normal, terrain, satellite and hybrid maps receive GCJ-02 display coordinates for the live blue dot, selected points and routes; map clicks are converted back to WGS-84 before saving or spoofing. FOSS/OpenFreeMap remains WGS-84.
- **Fixes:** saving favorites and map taps in the FOSS build, crashes when offline or without a geocoder, and the update checker picking the other build's APK. Debug builds no longer need a keystore.

## Downloads

Grab an APK from [Releases](https://github.com/varma322/android-gps-setter/releases). There are two builds:

|                | `app-full-*.apk`                     | `app-foss-*.apk`                        |
|----------------|--------------------------------------|-----------------------------------------|
| Maps           | Google Maps (`play-services-maps`)   | MapLibre + OpenFreeMap                  |
| Fused location | Google Play services                 | microG client (`org.microg.gms`)        |

This fork installs as `io.github.varma322.gpssetter`, alongside upstream (`io.github.jqssun.gpssetter`) rather than over it, and its builds are signed with a different key. It is not on F-Droid.

## Requirements

- A rooted device with **LSPosed 2.x or Vector** (libxposed API 101+).
  - LSPosed 1.x can't load libxposed 101 modules; use upstream v0.0.6 there.
  - LSPatch (unrooted) isn't supported, because embedded frameworks don't provide remote preferences.
- **Android 8.1+.** System-wide spoofing of live location updates needs Android 12+. Tested on Android 16.

## Setup

1. Install the APK and enable **GPS Setter** in LSPosed.
2. In its scope, tick **System Framework** for system-wide spoofing. Optionally also tick individual apps (see [Tips](#tips-and-limitations)).
3. Reboot. System Framework hooks load at boot; per-app hooks only need that app restarted.
4. Open GPS Setter, pick a location on the map and press ▶.

### GPX route playback

Use **Play route (GPX)** in the drawer to choose a track and playback speed. While it runs, the bottom **Static / Route** panel is hidden and the home **Start / Stop** control is greyed out and disabled. The map remains movable, but map taps, search and favorite selection cannot place a new pin.

When playback finishes, the home screen returns to **Static** with spoofing still enabled at the last route point and **Stop** available. The endpoint marker updates without changing the map's current zoom or viewport. Press **Stop** on the home screen to disable spoofing. **Stop route** in the drawer or playback notification only ends movement and holds the current point.

## How it works

- **System Framework.** Actively generates 1 Hz spoofed fixes to location providers (`gps`, `fused`, `network`) and rewrites incoming fixes in `LocationProviderManager.onReportLocation` (Android 12+), `LocationManagerService.getLastLocation` and `injectLocation`. While spoofing, it also refuses raw GNSS measurement, navigation and batching listeners.
- **Scoped apps.** Inside the app's own process, hooks `LocationManager.getLastKnownLocation` and framework listener/current-location transports (single fixes and, on Android 12+, lists of fixes). `Location` getters and `set` are left untouched so SDK coordinate conversions and copies remain valid. For Play-services or SDK-owned delivery paths outside `LocationManager`, use the System Framework hook.

## Tips and limitations

- **Google Location Accuracy can stay on.** Because the module generates continuous 1 Hz GPS fixes inside the system framework, Google Play services' Fused Location Provider (FLP) prioritizes the high-accuracy GPS fixes over Wi-Fi/cell estimates. Play-services apps follow the spoofed location indoors and outdoors without needing individual app scoping.
- **Apps protected by Google Play's anti-tamper** (look for `libpairipcore.so` in the APK) crash when scoped. Don't scope them; rely on the system hook.
- **Only enable one location-spoofing module.** If two hook the same calls, whichever runs last wins.
- **Spoofing is system-wide while it's on.** That includes other system services; only emergencies are exempt (Android 14+).
- **Coordinate systems.** Android location fixes and numeric coordinates supplied to this module use WGS-84. Chinese map SDKs may convert these to GCJ-02 or BD-09 for display; the module must not convert the system fix a second time or overwrite the SDK's converted result. Convert coordinates copied from GCJ-02/BD-09 sources to WGS-84 before entering them manually. After updating the module, restart scoped apps (and reboot if using System Framework) to unload the old hooks.
- **Google map layers.** Full's correction applies consistently to normal, terrain, satellite and hybrid maps: changing the layer does not bypass the display-coordinate conversion. The conversion uses the conventional approximate China bounding rectangle, not precise regional borders; it cannot guarantee alignment near borders or in Hong Kong, Macau, Taiwan and adjacent regions. Use the FOSS WGS-84 map where that approximation is unsuitable.
- **Previously saved China map picks.** Full builds before the coordinate correction saved raw display coordinates from shifted maps; the initial correction also excluded satellite/hybrid modes. Re-select points/favorites created through those uncorrected paths after updating. They are not migrated automatically because they are indistinguishable from deliberately entered WGS-84 coordinates.

## Building

- Debug builds need JDK 17–21 and Android SDK platform 36, but no keystore:

  ```sh
  ./gradlew assembleFossDebug   # or assembleFullDebug
  ```

- Release builds read `keyAlias`, `keyPassword`, `storeFile` and `storePassword` from `local.properties`.
- CI ([`apk.yml`](.github/workflows/apk.yml)) builds both flavors on `v*.*.*` tags and publishes a GitHub release. It needs these repository secrets:
  - `STORE`: base64 of the keystore
  - `LOCAL`: base64 of a `local.properties` containing the signing keys
- `./release.sh v1.2.3` bumps the version, then tags and pushes. It uses `gsed` (GNU sed).

## Credits

- [jqssun/android-gps-setter](https://github.com/jqssun/android-gps-setter), the project this fork is based on
- [Android1500/GpsSetter](https://github.com/Android1500/GpsSetter), the original GpsSetter for Android 8.1–13
- [libxposed](https://github.com/libxposed) and [LSPosed](https://github.com/LSPosed/LSPosed) / [Vector](https://github.com/JingMatrix/Vector)
- [MapLibre](https://github.com/maplibre/maplibre-native) for the mapping library; [OpenFreeMap](https://openfreemap.org) for tiles; map data © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors
- [microG](https://github.com/microg/GmsCore) for the FOSS implementation of Google Mobile Services

## License

[GPL-3.0](LICENSE)
