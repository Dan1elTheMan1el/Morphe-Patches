package app.danielthemaniel.patches.googlemaps

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

/**
 * Google Maps is R8-obfuscated and its class names change every build. The Find Hub patch resolves
 * the map-label classes structurally; the location-sharing classes come from a per-build table
 * (MapsBuild), matched against the APK at patch time, so another build fails with a PatchException.
 *
 * Meant to be patched in the same run as the Dutch App Patches (nl-morphe-patches) Maps bundle, whose
 * "Native navigation for any Android Auto host" puts Maps' own car map on the head unit.
 */
internal val COMPATIBILITY_GOOGLE_MAPS = Compatibility(
    name = "Google Maps",
    packageName = "com.google.android.apps.maps",
    apkFileType = ApkFileType.APK,
    appIconColor = 0x34A853,
    targets = listOf(
        AppTarget(version = "26.47.00.1022347503", versionCode = 1068892333),
        AppTarget(version = "26.44.06.1006895251", versionCode = 1068856906),
    ),
)
