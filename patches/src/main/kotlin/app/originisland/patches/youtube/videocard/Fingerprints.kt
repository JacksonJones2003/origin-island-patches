package app.originisland.patches.youtube.videocard

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.string

/**
 * The method of the media session callback of the app that rates the video that plays.
 * It logs its name, and reads the id of the video first.
 */
internal object MediaSessionCallbackSetRatingFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("onSetRating()")
)

/**
 * `toString()` of the model of the entity that tells if a video is liked.
 */
internal object LikeStatusEntityModelToStringFingerprint : Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    strings = listOf("LikeStatusEntityModel{")
)

/**
 * A method of the list of caption tracks of a video, which makes the tracks
 * that are translated from another track.
 */
internal object CaptionTrackListTranslatedTracksFingerprint : Fingerprint(
    returnType = "Ljava/util/List;",
    parameters = listOf(),
    strings = listOf("&tlang=")
)

/**
 * Constructor of the model of the watch next response, which has what plays after a video.
 * The numbers are the field numbers of the renderers it reads from the response.
 */
internal object WatchNextResponseModelConstructorFingerprint : Fingerprint(
    name = "<init>",
    returnType = "V",
    filters = listOf(
        // singleColumnWatchNextResults
        literal(51779735),
        // playlistPanelRenderer
        literal(50631000),
        // autoplayRenderer
        literal(46659098),
        // playerOverlayRenderer
        literal(78882851)
    ),
    custom = { method, _ ->
        method.parameterTypes.size == 1
    }
)

/**
 * The method that plays a video from its address once the app has looked the address up.
 * It plays the video without opening the app while the app plays in the background
 * or on another device, and opens the app to play the video otherwise.
 */
internal object PlayFromAddressFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        string("Unsupported link: %s"),
        methodCall(
            definingClass = "Landroid/content/Context;",
            name = "startActivity",
            parameters = listOf("Landroid/content/Intent;")
        )
    )
)
