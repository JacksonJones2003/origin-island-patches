package app.originisland.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

internal object Constants {
    val COMPATIBILITY_YOUTUBE_MUSIC = Compatibility(
        name = "YouTube Music",
        packageName = "com.google.android.apps.youtube.music",
        apkFileType = ApkFileType.APK_REQUIRED,
        appIconColor = 0xFF0000,
        targets = listOf(
            // The patch hooks Android framework media session calls and one unobfuscated
            // service class, so it is expected to work with other app versions as well.
            AppTarget(
                version = null,
                minSdk = 26,
                isExperimental = true
            ),
            AppTarget(
                version = "9.40.51",
                minSdk = 26
            )
        )
    )

    val COMPATIBILITY_YOUTUBE = Compatibility(
        name = "YouTube",
        packageName = "com.google.android.youtube",
        apkFileType = ApkFileType.APK_REQUIRED,
        appIconColor = 0xFF0000,
        targets = listOf(
            // Most of the patch hooks Android framework media session calls. The rest is found
            // from strings and numbers of the app that are not obfuscated, so the patch
            // is expected to work with other app versions as well.
            AppTarget(
                version = null,
                minSdk = 26,
                isExperimental = true
            ),
            AppTarget(
                version = "21.38.123",
                minSdk = 26
            )
        )
    )
}
