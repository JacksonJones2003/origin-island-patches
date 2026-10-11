package app.originisland.patches.music.likebutton

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall

/**
 * Framework calls and overrides keep their names, so these match regardless of
 * how the app obfuscates the media session code around them.
 */
internal object MediaSessionSetMetadataFingerprint : Fingerprint(
    filters = listOf(
        methodCall(
            definingClass = "Landroid/media/session/MediaSession;",
            name = "setMetadata",
            parameters = listOf("Landroid/media/MediaMetadata;")
        )
    )
)

internal object MediaSessionCallbackOnSetRatingFingerprint : Fingerprint(
    name = "onSetRating",
    returnType = "V",
    parameters = listOf("Landroid/media/Rating;"),
    custom = { _, classDef ->
        classDef.superclass == "Landroid/media/session/MediaSession\$Callback;"
    }
)

/**
 * `MediaBrowserServiceCompat.onGetRoot(String clientPackageName, int clientUid, Bundle rootHints)`.
 */
internal object MusicBrowserServiceOnGetRootFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/apps/youtube/music/mediabrowser/MusicBrowserService;",
    returnType = "L",
    parameters = listOf("Ljava/lang/String;", "I", "Landroid/os/Bundle;")
)
