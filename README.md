# GPS Setter

An Xposed module for static location spoofing and route playback, using the modern [libxposed](https://github.com/libxposed/api) API.

This repository, **alkali210/android-gps-setter**, is a fork of [varma322/android-gps-setter](https://github.com/varma322/android-gps-setter), based on its `v0.2.0` commit (`5e28c57`). That project builds on [jqssun/android-gps-setter](https://github.com/jqssun/android-gps-setter) and [Android1500/GpsSetter](https://github.com/Android1500/GpsSetter).

## Changes in this fork

The changes below are relative to varma322's `v0.2.0`, not features newly introduced by that upstream fork:

- **Light-mode appearance.** Theme-aware surfaces, text, dialog backgrounds, system-bar icons and accent-selection outlines replace dark-only styling. Full's Google map uses its default light style in light mode; FOSS selects OpenFreeMap's light or dark style.
- **China coordinate handling and app-hook boundaries.** Full converts WGS-84 coordinates to GCJ-02 for Google map display, including the live blue dot, selected pins, routes and traveler markers. Map taps are converted back before saving or spoofing. App hooks modify Android-delivered fixes instead of globally overriding `Location` getters or `set`, so SDK-converted coordinates are not overwritten. See [coordinate limitations](#coordinates-and-map-limitations).
- **Map-type changes without restarting the app.** In Full, returning from Settings applies the selected Google map type. All four map types use the same coordinate-conversion policy.
- **Recenter without changing the selected location.** The centering button moves the camera to the location reported by the location provider without moving the selected pin or changing the spoofing target. It preserves zoom levels closer than 12 and zooms in to 12 only when farther out.
- **GPX playback locks home-screen location editing.** The Static / Route panel is hidden, Start / Stop is greyed out and disabled, and map taps, search and favorite selection cannot replace the selected location during GPX playback. Finishing playback restores Static mode and leaves spoofing active at the endpoint until the home Stop button is pressed. Live preference reads and ordered writes keep the home controls in sync with playback completion.
- **No camera reset after GPX playback.** Completion updates the endpoint pin without changing the map's zoom, center, bearing or tilt.
- **GPX display survives theme changes.** Switching light/dark mode in Settings, or through the system while following its theme, restores the GPX route and the current map camera after the page is recreated, without fitting the route again or restarting playback.

Coordinate-conversion, playback-transition and theme-recreation regression tests accompany these changes. Tests do not establish compatibility with every map layer, ROM or Xposed implementation.

## Features inherited from upstream

- Static spoofing, saved favorites, JSON favorite import/export and an optional overlay joystick.
- GPX playback with interpolated position, altitude, speed and bearing; A-to-B route selection with road-routing providers.
- A general Quick Settings spoofing toggle and three favorite-location tiles.
- Light/dark theme selection and accent colors.
- Double-precision stored coordinates and settings shared with the hook through libxposed remote preferences.
- System-server location hooks, including approximately 1 Hz generated fixes on Android 12+, plus optional per-app hooks.

## Downloads and installation compatibility

Use this fork's [Releases](https://github.com/alkali210/android-gps-setter/releases) for published APKs, or [build from source](#building). APKs from varma322's releases do not include this fork's later changes.

| Variant | Map | Location client |
|---|---|---|
| Full (`app-full-*.apk`) | Google Maps | Google Play services |
| FOSS (`app-foss-*.apk`) | MapLibre + keyless OpenFreeMap | microG client library |

The build currently produces **arm64-v8a** APKs. Both variants and the varma322 fork share the application ID **`io.github.varma322.gpssetter`**, so they cannot be installed side by side. Installing one over another requires matching signing certificates; otherwise Android requires uninstalling the existing app first. Export favorites before changing installations. The older jqssun app uses a different ID, `io.github.jqssun.gpssetter`.

**The in-app update checker still queries varma322's releases, not this fork.** Leave **Check for updates** disabled if you want to stay on this fork, and obtain its updates from the link above. This README change does not alter the application's update endpoint or signing configuration.

## Requirements

- A rooted Android device with a modern libxposed-capable framework, such as a compatible LSPosed / Vector build. The module declares minimum API **101** and target API **102**, and is built against libxposed **102.0.0**. Legacy LSPosed 1.x is not supported; LSPatch is not supported by this remote-preference setup.
- **Android 8.1+** (`minSdk 27`). Continuous system-server fix generation requires **Android 12+**; the same behavior is not available on every older Android version.
- Grant location access for map location/recentering and route playback. Allow notifications to see playback status and its Stop action. The optional joystick requires overlay permission.
- Network access for online map tiles, address lookup and road routing. Importing a local GPX file does not require a road-routing request.

## Setup and use

1. Install the APK and enable **GPS Setter** in your Xposed manager.
2. Include **System Framework** in the scope for system-wide spoofing. Optionally scope individual apps for per-app hooks.
3. Reboot after changing system-framework hooks. Restart individually scoped apps after updating their hooks.
4. Open GPS Setter, choose **Static**, tap the map to select a location and press **Start**. Press **Stop** to disable spoofing.

The centering button only moves the camera. The location provider may itself report a spoofed location while system-wide spoofing is active; centering is not a way to recover the real GPS position.

### GPX route playback

Choose **Play route (GPX)** in the drawer, select a file and enter a playback speed in km/h. The importer reads `trkpt`, `rtept` and `wpt` elements with optional elevation; at least two points are required. Playback uses the chosen speed with an initial acceleration ramp, **not the timestamps recorded in the GPX file**. The importer is a lightweight parser, not a full GPX/XML implementation; separate tracks/segments are flattened into one sequence.

During playback, the home Static / Route panel is hidden and the home Start / Stop control is disabled and greyed out. You can move the map, but cannot place a new pin by tapping, searching or choosing a favorite.

When playback finishes, the home screen returns to **Static**, updates the endpoint marker and enables **Stop**. The camera stays where you left it. Spoofing remains active at the endpoint with motion cleared until you press the home **Stop** button.

**Stop route** in the drawer, or **Stop** in the route playback notification, stops movement and holds the current point; it does not disable spoofing. These home-screen protections are specific to GPX playback and do not imply that every other control, such as a Quick Settings tile or the joystick, is locked.

GPX geometry and the current camera are retained across theme/configuration recreation. The new map redraws the route without changing the saved zoom, center, bearing or tilt; FOSS restores the overlay after its new style has loaded. This display state is retained in memory, not persisted across process death. The animated traveler/progress panel belongs to A-to-B playback, not GPX import.

### A-to-B routes

Select **Route**, tap the start and destination, choose a speed and start playback. The route panel shows distance, speed, progress and estimated arrival time.

Road routing tries OpenRouteService when an `ORS_KEY` is configured, then the public OSRM driving service. If both requests fail, it falls back to a **straight line between A and B**, not a verified road route. Endpoint coordinates are sent to the routing provider. A-to-B playback uses its own route panel; the GPX-specific home-screen lock and completion UI described above do not apply to it.

### Favorites and shortcuts

Use the favorite button to save a selected point, and the drawer to select, import or export favorites as JSON. Android's file picker handles import/export.

Add the GPS Setter tiles through Android's Quick Settings editor. The general tile toggles static spoofing; the three favorite tiles activate the corresponding entries in the saved-favorites list. Do not use these shortcuts or the joystick to change location while a route is running: they do not cancel playback, so a subsequent route update can replace their changes.

## How location spoofing works

- **System Framework:** hooks Android's location service and, on Android 12+, provider delivery through `LocationProviderManager.onReportLocation`. A generator supplies approximately 1 Hz fixes to GPS, network and fused providers so listeners can receive updates even without a fresh physical GPS fix. Raw GNSS measurement, navigation and batching listeners are also restricted while spoofing.
- **Scoped apps:** hooks `LocationManager.getLastKnownLocation` and framework listener/current-location transports. It leaves `Location` getters and copying alone. PendingIntent delivery and Play-services or SDK-owned paths outside those transports are not covered by these app-side hooks; use the System Framework path where needed.
- **Emergency handling:** the Android 14+ system-provider path checks the platform's emergency helper and skips rewriting/generating fixes when it reports an emergency. This is not a guarantee for every Android version, ROM or location-delivery path; do not rely on a spoofing module for emergency-location safety.

## Coordinates and map limitations

- Stored coordinates, GPX points and spoofed Android fixes use **WGS-84**. Convert coordinates copied from GCJ-02 or BD-09 sources before entering/importing them as WGS-84.
- Full's conversion applies to Google normal, terrain, satellite and hybrid maps. It uses an **approximate rectangular China boundary**, not a precise mainland border or a per-tile/provider check. Alignment is not guaranteed near borders or in Hong Kong, Macau, Taiwan and neighboring regions; different tile layers may also differ in their actual coordinate reference.
- FOSS/OpenFreeMap uses WGS-84 directly. Its map-type setting does not provide Google-style satellite or terrain imagery; the current implementation selects a light or dark OpenFreeMap style.
- Re-select China-area pins/favorites saved by older Full builds that stored uncorrected map coordinates. They are not migrated automatically because they cannot be distinguished reliably from intentional WGS-84 inputs.
- Restart scoped apps, and reboot for System Framework, after updating coordinate-related hooks so old hooks are unloaded.

## Other limitations

- Use only one location-spoofing module at a time. System-framework spoofing affects other apps and services, not only the app currently on screen.
- Google Location Accuracy can remain enabled with the Android 12+ system-server generator, but delivery still depends on the device's Android implementation and location providers.
- Apps with anti-tamper protection, including some using `libpairipcore.so`, may reject or crash under per-app hooking. Prefer the System Framework scope rather than injecting into those apps.
- Local JVM/Robolectric tests do not exercise a real Xposed system server or native map rendering. Verify your device, map layer and intended apps before relying on the reported position.

## Building

Use **JDK 17** (as in CI) or **JDK 21**, the Android SDK with **platform 37**, and the included Gradle wrapper. The project uses AGP **8.13.2**, Gradle **8.14.3**, Kotlin **2.1.10**, and targets Android API **34**. Debug builds do not need a release keystore.

```sh
./gradlew assembleFossDebug       # FOSS APK
./gradlew assembleFullDebug       # Google Maps APK
./gradlew assembleDebug test      # both debug APKs + unit tests
```

On Windows, use `gradlew.bat` instead of `./gradlew` and set `JAVA_HOME` to a compatible JDK. Outputs are under `app/build/outputs/apk/<flavor>/debug/`.

AGP 8.13.2 currently warns that compile SDK 37 is newer than its tested SDK range. This is an existing toolchain warning, not a claim that all API 37 behavior is validated.

Optional `local.properties` configuration:

- `sdk.dir`: local Android SDK path, when not supplied through the environment.
- `ORS_KEY`: OpenRouteService API key for A-to-B road routing. Without it, routing starts with OSRM.
- `keyAlias`, `keyPassword`, `storeFile`, `storePassword`: required for signed release builds. `storeFile` is relative to the repository root. Do not commit keys or signing secrets.

[Build CI](.github/workflows/build.yml) runs `assembleDebug test` on pushes to `main` and pull requests. The [release workflow](.github/workflows/apk.yml) builds both signed variants for `v*.*.*` tags, `release/**` pushes or manual dispatch; it needs `STORE` (base64 keystore) and `LOCAL` (base64 `local.properties`) secrets.

`./release.sh v1.2.3` edits the version, commits tracked changes, creates a tag and pushes it. It uses Bash and GNU-compatible `sed -i`; run it only from a clean working tree when you intend to publish.

## Credits

- [varma322/android-gps-setter](https://github.com/varma322/android-gps-setter), the direct upstream for this fork
- [jqssun/android-gps-setter](https://github.com/jqssun/android-gps-setter), the earlier GPS Setter project
- [Android1500/GpsSetter](https://github.com/Android1500/GpsSetter), the original GpsSetter for Android 8.1–13
- [libxposed](https://github.com/libxposed) and [LSPosed](https://github.com/LSPosed/LSPosed) / [Vector](https://github.com/JingMatrix/Vector)
- [MapLibre](https://github.com/maplibre/maplibre-native), [OpenFreeMap](https://openfreemap.org) and [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors (ODbL map data)
- [microG](https://github.com/microg/GmsCore), the location client used by the FOSS build

## License

[GPL-3.0](LICENSE)
