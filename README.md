# TileCam

Shoots a scene as a grid of overlapping ~50 mm tiles and stitches them into one high-resolution image.

## Build

Open this folder in Android Studio (Ladybug or newer) and let it sync; it will create the Gradle wrapper.
Run on a real phone (an emulator has no gyro or real camera). Min Android 10.

## How to shoot

1. Aim the crosshair at one corner of the scene and tap **Lock & set first corner**.
   Focus, exposure and white balance are frozen from now on, so aim at the main subject.
2. Aim at the opposite corner and tap **Set opposite corner**. The status line shows the tile count while you aim.
3. Move the crosshair onto the white dot. When the phone is on target, level and still, a ring fills and the tile is taken.
   - Captured tiles are drawn where they sit in the scene: yellow = checking, green = overlap OK, red = retake.
   - Turn the phone around the lens; don't slide it sideways (parallax causes ghosting on near objects).
4. Tap **Stitch**. The result goes to `Pictures/TileCam`.

Raw tiles and `session.json` (pose of each tile) are kept in the app's files dir under `sessions/`.

## Code map

| File | Role |
|---|---|
| `camera/LensSelector.kt` | Picks a listed back lens near 50 mm; otherwise zooms by ratio so the phone can use a hidden telephoto; crops on Android 10 |
| `camera/TileCamera.kt` | Camera2: preview, full-res JPEG, focus/exposure/WB lock, EIS off |
| `sensors/OrientationTracker.kt` | Game rotation vector → yaw / pitch / roll / angular speed |
| `sensors/Aim.kt` | Pinhole camera orientation: world rays ↔ image coordinates |
| `capture/GridPlan.kt` | Tile grid between two corners on a flat scene plane, 30 % overlap checked through each tile's real frame, serpentine order |
| `capture/ShootSession.kt` | Tile states, retakes, `session.json` |
| `ui/GuidanceOverlay.kt` | Target, captured tiles, level line, hold ring, minimap |
| `stitch/Stitcher.kt` | SIFT matching → spanning tree of homographies (gyro fallback) → strip-wise feathered blend |

## Known limits / next steps

- Flat projection: fine up to roughly 90° total width. For wider scenes switch to a spherical warp.
- The grid assumes an upright scene facing you (or, when aiming steeply, one facing the phone). A poster seen
  at a sideways angle is a trapezoid, so two corners can't pin it down; stand square to it.
- Zooming by ratio leaves the lens choice to the phone: some phones stay on a crop of the main lens in low light.
- Feather blending; multi-band blending and seam finding would hide moving objects and vignetting better.
- No crop of the black border around the stitched result yet.
- Only lens sizes from the standard stream map are used; RAW/DNG capture is not implemented.

## CI and releases

- `.github/workflows/ci.yml` runs lint and builds a debug APK on every push to `main` and on pull requests.
  The APK is attached to the run as the `tilecam-debug-apk` artifact.
- `.github/workflows/release.yml` runs on a `v*` tag. It checks that the tag matches `versionName` in
  `app/build.gradle.kts`, builds a signed release APK and publishes it as a GitHub release.

To release: bump `versionName` (and `versionCode`), commit, then `git tag vX.Y.Z && git push origin vX.Y.Z`.

Signing uses the repository secrets `TILECAM_KEYSTORE_BASE64`, `TILECAM_KEYSTORE_PASSWORD`,
`TILECAM_KEY_ALIAS` and `TILECAM_KEY_PASSWORD`. Keep a backup of the keystore: Android only installs
updates signed with the same key.
