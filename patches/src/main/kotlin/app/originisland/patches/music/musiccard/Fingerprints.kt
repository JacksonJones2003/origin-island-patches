package app.originisland.patches.music.musiccard

import app.morphe.patcher.Fingerprint

/**
 * `Activity.onResume()` of the main activity, which keeps its name.
 */
internal object MusicActivityOnResumeFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/apps/youtube/music/activities/MusicActivity;",
    name = "onResume",
    returnType = "V",
    parameters = listOf()
)
