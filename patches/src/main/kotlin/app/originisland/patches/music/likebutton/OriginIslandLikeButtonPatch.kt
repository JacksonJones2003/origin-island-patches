package app.originisland.patches.music.likebutton

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.originisland.patches.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.w3c.dom.Element

private const val EXTENSION_CLASS = "Lapp/originisland/extension/OriginIslandLikeButtonPatch;"

private const val MUSIC_BROWSER_SERVICE =
    "com.google.android.apps.youtube.music.mediabrowser.MusicBrowserService"

/**
 * The music card only offers the heart button to apps it controls through a media browser
 * service that declares this action. Every other app gets a controller without it.
 */
private const val MUSIC_CARD_SERVICE_ACTION = "com.vivo.musicwidgetmix.support.service"

/**
 * Root the app already gives to media browser clients that may connect but not browse.
 */
private const val EMPTY_BROWSER_ROOT_ID = "__EMPTY_ROOT_ID__"

private val musicCardServiceResourcePatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { document ->
            val services = document.getElementsByTagName("service")
            val browserService = (0 until services.length)
                .map { services.item(it) as Element }
                .firstOrNull { it.getAttribute("android:name") == MUSIC_BROWSER_SERVICE }
                ?: throw PatchException("Could not find $MUSIC_BROWSER_SERVICE")

            browserService.appendChild(
                document.createElement("intent-filter").apply {
                    appendChild(
                        document.createElement("action").apply {
                            setAttribute("android:name", MUSIC_CARD_SERVICE_ACTION)
                        }
                    )
                }
            )
        }
    }
}

@Suppress("unused")
val originIslandLikeButtonPatch = bytecodePatch(
    name = "Origin Island like button",
    description = "Makes the heart button of the OriginOS music card on the lock screen " +
            "and Origin Island like the current song.",
    default = true
) {
    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    dependsOn(musicCardServiceResourcePatch)

    extendWith("extensions/extension.mpe")

    execute {
        // The app only lets allowlisted clients connect to its media browser service.
        MusicBrowserServiceOnGetRootFingerprint.method.apply {
            val parameterRegisters = 4
            if (implementation!!.registerCount - parameterRegisters < 3) {
                throw PatchException("Not enough registers in onGetRoot")
            }

            val browserRootClass = returnType

            // The parameter registers of this method are out of reach of a regular invoke.
            addInstructionsWithLabels(
                0,
                """
                    invoke-static/range { p1 .. p1 }, $EXTENSION_CLASS->isMusicCard(Ljava/lang/String;)Z
                    move-result v0
                    if-eqz v0, :original
                    new-instance v0, $browserRootClass
                    const-string v1, "$EMPTY_BROWSER_ROOT_ID"
                    const/4 v2, 0x0
                    invoke-direct { v0, v1, v2 }, $browserRootClass-><init>(Ljava/lang/String;Landroid/os/Bundle;)V
                    return-object v0
                """,
                ExternalLabel("original", getInstruction(0))
            )
        }

        // Other patches publish metadata to the session from their extensions,
        // so every call is hooked and not only the one of the app.
        MediaSessionSetMetadataFingerprint.matchAll().forEach { match ->
            match.method.apply {
                val setMetadataIndices = implementation!!.instructions.withIndex().filter { (_, instruction) ->
                    val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                    reference?.definingClass == "Landroid/media/session/MediaSession;" &&
                            reference.name == "setMetadata"
                }.map { it.index }

                // Reversed, so the indices of the remaining calls stay valid.
                setMetadataIndices.reversed().forEach { index ->
                    val register = getInstruction<FiveRegisterInstruction>(index).registerD

                    addInstructions(
                        index,
                        """
                            invoke-static { v$register }, $EXTENSION_CLASS->onSetMetadata(Landroid/media/MediaMetadata;)Landroid/media/MediaMetadata;
                            move-result-object v$register
                        """
                    )
                }
            }
        }

        MediaSessionCallbackOnSetRatingFingerprint.matchAll().forEach { match ->
            match.method.addInstructions(
                0,
                """
                    invoke-static/range { p1 .. p1 }, $EXTENSION_CLASS->onSetRating(Landroid/media/Rating;)Landroid/media/Rating;
                    move-result-object p1
                """
            )
        }
    }
}
