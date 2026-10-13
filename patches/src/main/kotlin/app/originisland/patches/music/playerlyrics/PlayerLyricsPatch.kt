package app.originisland.patches.music.playerlyrics

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.originisland.patches.music.musiccard.MusicActivityOnResumeFingerprint
import app.originisland.patches.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.originisland.patches.shared.mediasession.MediaSessionConstructorFingerprint
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_PACKAGE = "Lapp/originisland/extension/"
private const val EXTENSION_CLASS = "${EXTENSION_PACKAGE}PlayerLyricsPatch;"

private const val MORPHE_EXTENSION_PACKAGE = "Lapp/morphe/extension/"

private const val MEDIA_SESSION_CLASS = "Landroid/media/session/MediaSession;"

@Suppress("unused")
val playerLyricsPatch = bytecodePatch(
    name = "Lyrics in player",
    description = "Shows the line of the lyrics that is sung below the title of the queue in the " +
            "player. Requires the Third-party lyrics patch.",
    default = true
) {
    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    extendWith("extensions/extension.mpe")

    execute {
        // The view of the line is added to the player from the activity, by the names of the
        // views of the app, so that nothing of the player itself has to be found.
        MusicActivityOnResumeFingerprint.method.addInstructions(
            0,
            "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->onActivityResume(Landroid/app/Activity;)V"
        )

        // The position the song is at is read from the session of the app.
        MediaSessionConstructorFingerprint.matchAll().forEach { match ->
            match.method.apply {
                if (definingClass.startsWith(EXTENSION_PACKAGE) ||
                    definingClass.startsWith(MORPHE_EXTENSION_PACKAGE)
                ) return@forEach

                implementation!!.instructions.withIndex().filter { (_, instruction) ->
                    val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                    reference?.definingClass == MEDIA_SESSION_CLASS && reference.name == "<init>"
                }.map { it.index }.reversed().forEach { index ->
                    val sessionRegister = (getInstruction(index) as? FiveRegisterInstruction)?.registerC
                        ?: return@forEach

                    addInstructions(
                        index + 1,
                        "invoke-static/range { v$sessionRegister .. v$sessionRegister }, " +
                                "$EXTENSION_CLASS->onSessionCreated($MEDIA_SESSION_CLASS)V"
                    )
                }
            }
        }
    }
}
