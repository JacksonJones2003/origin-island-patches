package app.originisland.patches.music.musiccard

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.originisland.patches.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.w3c.dom.Element

private const val EXTENSION_PACKAGE = "Lapp/originisland/extension/"
private const val EXTENSION_CLASS = "${EXTENSION_PACKAGE}OriginIslandMusicCardPatch;"

private const val MUSIC_BROWSER_SERVICE =
    "com.google.android.apps.youtube.music.mediabrowser.MusicBrowserService"

/**
 * The music card only offers the heart button, the loop mode button and lyrics to apps it
 * controls through a media browser service that declares this action.
 * Every other app gets a controller without them.
 */
private const val MUSIC_CARD_SERVICE_ACTION = "com.vivo.musicwidgetmix.support.service"

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
val originIslandMusicCardPatch = bytecodePatch(
    name = "Origin Island music card",
    description = "Lets the OriginOS music card on the lock screen and Origin Island like songs, " +
            "switch between repeat and shuffle, show the playlist, and show the lyrics of the " +
            "Third-party lyrics patch.",
    default = true
) {
    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    dependsOn(musicCardServiceResourcePatch)

    extendWith("extensions/extension.mpe")

    execute {
        // The app only lets allowlisted clients connect to its media browser service.
        // The card is answered before the app is asked, with a root of the platform,
        // so that it loads the playlist through the platform as well.
        MediaBrowserServiceOnGetRootFingerprint.matchAll().forEach { match ->
            match.method.apply {
                val parameterRegisters = 4
                if (implementation!!.registerCount - parameterRegisters < 1) {
                    throw PatchException("Not enough registers in onGetRoot")
                }

                // The parameter registers of this method can be out of reach of a regular invoke.
                addInstructionsWithLabels(
                    0,
                    """
                        invoke-static/range { p1 .. p1 }, $EXTENSION_CLASS->onGetBrowserRoot(Ljava/lang/String;)Landroid/service/media/MediaBrowserService${'$'}BrowserRoot;
                        move-result-object v0
                        if-eqz v0, :original
                        return-object v0
                    """,
                    ExternalLabel("original", getInstruction(0))
                )
            }
        }

        // Other patches publish metadata to the session from their extensions,
        // so every call is hooked and not only the one of the app.
        MediaSessionSetMetadataFingerprint.matchAll().forEach { match ->
            match.method.apply {
                // The extension of this patch publishes to the session as well.
                if (definingClass.startsWith(EXTENSION_PACKAGE)) return@forEach

                val isAppMetadata = !definingClass.startsWith("Lapp/morphe/extension/")

                val setMetadataIndices = implementation!!.instructions.withIndex().filter { (_, instruction) ->
                    val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                    reference?.definingClass == "Landroid/media/session/MediaSession;" &&
                            reference.name == "setMetadata"
                }.map { it.index }

                // Reversed, so the indices of the remaining calls stay valid.
                setMetadataIndices.reversed().forEach { index ->
                    val instruction = getInstruction<FiveRegisterInstruction>(index)
                    val sessionRegister = instruction.registerC
                    val metadataRegister = instruction.registerD

                    val extensionMethod = if (isAppMetadata) "onSetMetadata" else "onSetExtensionMetadata"

                    addInstructions(
                        index,
                        """
                            invoke-static { v$sessionRegister, v$metadataRegister }, $EXTENSION_CLASS->$extensionMethod(Landroid/media/session/MediaSession;Landroid/media/MediaMetadata;)Landroid/media/MediaMetadata;
                            move-result-object v$metadataRegister
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

        // These hooks handle what the music card sends and let the app handle everything else.
        // Only the parameter registers are used, since the methods may have no locals.
        fun MutableMethod.addMusicCardHook(parameters: String, extensionMethod: String) =
            addInstructionsWithLabels(
                0,
                """
                    invoke-static/range { $parameters }, $EXTENSION_CLASS->$extensionMethod
                    move-result-object p1
                    if-nez p1, :original
                    return-void
                """,
                ExternalLabel("original", getInstruction(0))
            )

        MediaSessionCallbackOnCustomActionFingerprint.matchAll().forEach { match ->
            match.method.addMusicCardHook(
                "p1 .. p2",
                "onCustomAction(Ljava/lang/String;Landroid/os/Bundle;)Ljava/lang/String;"
            )
        }

        MediaSessionCallbackOnPlayFromMediaIdFingerprint.matchAll().forEach { match ->
            match.method.addMusicCardHook(
                "p1 .. p2",
                "onPlayFromMediaId(Ljava/lang/String;Landroid/os/Bundle;)Ljava/lang/String;"
            )
        }

        val onLoadChildrenParameters = "Landroid/service/media/MediaBrowserService;" +
                "Ljava/lang/String;" +
                "Landroid/service/media/MediaBrowserService\$Result;"

        MediaBrowserServiceOnLoadChildrenFingerprint.matchAll().forEach { match ->
            match.method.addMusicCardHook(
                "p0 .. p2",
                "onLoadChildren($onLoadChildrenParameters)Ljava/lang/String;"
            )
        }

        MediaBrowserServiceOnLoadChildrenWithOptionsFingerprint.matchAll().forEach { match ->
            match.method.addMusicCardHook(
                "p0 .. p3",
                "onLoadChildren(${onLoadChildrenParameters}Landroid/os/Bundle;)Ljava/lang/String;"
            )
        }
    }
}
