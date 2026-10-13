package app.originisland.patches.youtube.videocard

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import app.originisland.patches.shared.Constants.COMPATIBILITY_YOUTUBE
import app.originisland.patches.shared.mediasession.MediaBrowserServiceOnGetRootFingerprint
import app.originisland.patches.shared.mediasession.MediaBrowserServiceOnLoadChildrenFingerprint
import app.originisland.patches.shared.mediasession.MediaBrowserServiceOnLoadChildrenWithOptionsFingerprint
import app.originisland.patches.shared.mediasession.MediaSessionCallbackOnPlayFingerprint
import app.originisland.patches.shared.mediasession.MediaSessionCallbackOnPlayFromMediaIdFingerprint
import app.originisland.patches.shared.mediasession.MediaSessionCallbackOnSetRatingFingerprint
import app.originisland.patches.shared.mediasession.MediaSessionConstructorFingerprint
import app.originisland.patches.shared.mediasession.MediaSessionSetMetadataFingerprint
import app.originisland.patches.shared.mediasession.MediaSessionSetPlaybackStateFingerprint
import app.originisland.patches.shared.mediasession.addMusicCardHook
import app.originisland.patches.shared.mediasession.indicesOfCalls
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import org.w3c.dom.Element

private const val EXTENSION_PACKAGE = "Lapp/originisland/extension/"
private const val EXTENSION_CLASS = "${EXTENSION_PACKAGE}OriginIslandVideoCardPatch;"
private const val VIDEO_ID_PROVIDER_CLASS = "${EXTENSION_PACKAGE}OriginIslandVideoCardPatch\$VideoIdProvider;"
private const val VIDEO_ID_PROVIDER_METHOD = "originIslandGetVideoId"

private const val MORPHE_EXTENSION_PACKAGE = "Lapp/morphe/extension/"

private const val MEDIA_SESSION_CLASS = "Landroid/media/session/MediaSession;"
private const val MEDIA_BROWSER_SERVICE_CLASS = "Landroid/service/media/MediaBrowserService;"

private const val MEDIA_BROWSER_SERVICE =
    "com.google.android.apps.youtube.app.extensions.mediabrowser.impl.MainAppMediaBrowserService"

/**
 * The music card only offers the heart button, lyrics and lists to apps it controls through
 * a media browser service that declares this action. Every other app gets a controller without them.
 */
private const val MUSIC_CARD_SERVICE_ACTION = "com.vivo.musicwidgetmix.support.service"

private val videoCardServiceResourcePatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { document ->
            val services = document.getElementsByTagName("service")
            val browserService = (0 until services.length)
                .map { services.item(it) as Element }
                .firstOrNull { it.getAttribute("android:name") == MEDIA_BROWSER_SERVICE }
                ?: throw PatchException("Could not find $MEDIA_BROWSER_SERVICE")

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
val originIslandVideoCardPatch = bytecodePatch(
    name = "Origin Island music card",
    description = "Lets the OriginOS music card on the lock screen and Origin Island like videos, " +
            "show the captions of a video as its lyrics, and show what plays next as the playlist.",
    default = true
) {
    compatibleWith(COMPATIBILITY_YOUTUBE)

    dependsOn(videoCardServiceResourcePatch)

    extendWith("extensions/extension.mpe")

    execute {
        fun isExtension(definingClass: String) = definingClass.startsWith(EXTENSION_PACKAGE)

        fun isOtherPatch(definingClass: String) = definingClass.startsWith(MORPHE_EXTENSION_PACKAGE)

        /**
         * Adds instructions to where a constructor returns. Code that jumps to a return
         * would jump past instructions that are added in front of it, so the return is replaced.
         */
        fun MutableMethod.addInstructionsBeforeReturns(first: String, others: String) {
            val returnIndices = implementation!!.instructions.withIndex()
                .filter { it.value.opcode == Opcode.RETURN_VOID }
                .map { it.index }
            if (returnIndices.isEmpty()) throw PatchException("$definingClass->$name does not return")

            // Reversed, so the indices of the remaining returns stay valid.
            returnIndices.reversed().forEach { index ->
                replaceInstruction(index, first)
                addInstructions(
                    index + 1,
                    """
                        $others
                        return-void
                    """
                )
            }
        }

        /**
         * Calls the extension with the object as it has been constructed.
         */
        fun MutableClass.addConstructedHook(extensionMethod: String) {
            val constructors = methods.filter { it.name == "<init>" }
            if (constructors.isEmpty()) throw PatchException("$type has no constructor")

            constructors.forEach { constructor ->
                constructor.addInstructionsBeforeReturns(
                    "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->$extensionMethod",
                    ""
                )
            }
        }

        // The card is given a root of the platform, so that it loads the playlist through
        // the platform and not through a channel of the media library of the app.
        MediaBrowserServiceOnGetRootFingerprint.matchAll().forEach { match ->
            match.method.apply {
                val parameterRegisters = 4
                if (implementation!!.registerCount - parameterRegisters < 1) {
                    throw PatchException("Not enough registers in onGetRoot")
                }

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
                if (isExtension(definingClass)) return@forEach

                val extensionMethod = if (isOtherPatch(definingClass)) "onSetExtensionMetadata" else "onSetMetadata"

                // Reversed, so the indices of the remaining calls stay valid.
                indicesOfCalls(MEDIA_SESSION_CLASS, "setMetadata").reversed().forEach { index ->
                    val instruction = getInstruction<FiveRegisterInstruction>(index)
                    val sessionRegister = instruction.registerC
                    val metadataRegister = instruction.registerD

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

        // The app makes its session before it has anything to play.
        MediaSessionConstructorFingerprint.matchAll().forEach { match ->
            match.method.apply {
                if (isExtension(definingClass) || isOtherPatch(definingClass)) return@forEach

                indicesOfCalls(MEDIA_SESSION_CLASS, "<init>").reversed().forEach { index ->
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

        // The metadata of the app does not tell which video plays, and the app publishes
        // its playback state as it starts another video. It has none for a video that is closed.
        MediaSessionSetPlaybackStateFingerprint.matchAll().forEach { match ->
            match.method.apply {
                if (isExtension(definingClass) || isOtherPatch(definingClass)) return@forEach

                indicesOfCalls(MEDIA_SESSION_CLASS, "setPlaybackState").reversed().forEach { index ->
                    val instruction = getInstruction<FiveRegisterInstruction>(index)

                    addInstructions(
                        index,
                        """
                            invoke-static { v${instruction.registerC}, v${instruction.registerD} }, $EXTENSION_CLASS->onSetPlaybackState(${MEDIA_SESSION_CLASS}Landroid/media/session/PlaybackState;)Landroid/media/session/PlaybackState;
                            move-result-object v${instruction.registerD}
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

        // The app has nothing to play once a video is closed.
        MediaSessionCallbackOnPlayFingerprint.matchAll().forEach { match ->
            match.method.apply {
                val parameterRegisters = 1
                if (implementation!!.registerCount - parameterRegisters < 1) {
                    throw PatchException("Not enough registers in onPlay")
                }

                addInstructionsWithLabels(
                    0,
                    """
                        invoke-static { }, $EXTENSION_CLASS->onPlay()Z
                        move-result v0
                        if-eqz v0, :original
                        return-void
                    """,
                    ExternalLabel("original", getInstruction(0))
                )
            }
        }

        // A closed video is played again from its address, for which the app opens.
        // Android does not let it while it is not in view.
        PlayFromAddressFingerprint.method.apply {
            val instructions = implementation!!.instructions.toList()
            val startActivityIndex = indicesOfCalls("Landroid/content/Context;", "startActivity").first()

            // The check that decides if the app opens is the last one in front of that.
            val checkIndex = (startActivityIndex downTo 2).firstOrNull { index ->
                val reference = (instructions[index - 2] as? ReferenceInstruction)?.reference as? MethodReference
                instructions[index].opcode == Opcode.IF_NEZ &&
                        instructions[index - 1].opcode == Opcode.MOVE_RESULT &&
                        reference != null && reference.returnType == "Z" && reference.parameterTypes.isEmpty()
            } ?: throw PatchException("Unexpected way of playing from an address in $definingClass")

            val checkRegister = (instructions[checkIndex - 1] as OneRegisterInstruction).registerA
            addInstructions(
                checkIndex,
                """
                    invoke-static/range { v$checkRegister .. v$checkRegister }, $EXTENSION_CLASS->playWithoutActivity(Z)Z
                    move-result v$checkRegister
                """
            )
        }

        MediaSessionCallbackOnPlayFromMediaIdFingerprint.matchAll().forEach { match ->
            match.method.addMusicCardHook(
                EXTENSION_CLASS,
                "p1 .. p2",
                "onPlayFromMediaId(Ljava/lang/String;Landroid/os/Bundle;)Ljava/lang/String;"
            )
        }

        val onLoadChildrenParameters = MEDIA_BROWSER_SERVICE_CLASS +
                "Ljava/lang/String;" +
                "Landroid/service/media/MediaBrowserService\$Result;"

        MediaBrowserServiceOnLoadChildrenFingerprint.matchAll().forEach { match ->
            match.method.addMusicCardHook(
                EXTENSION_CLASS,
                "p0 .. p2",
                "onLoadChildren($onLoadChildrenParameters)Ljava/lang/String;"
            )
        }

        MediaBrowserServiceOnLoadChildrenWithOptionsFingerprint.matchAll().forEach { match ->
            match.method.addMusicCardHook(
                EXTENSION_CLASS,
                "p0 .. p3",
                "onLoadChildren(${onLoadChildrenParameters}Landroid/os/Bundle;)Ljava/lang/String;"
            )
        }

        // The session of the app does not tell which video plays. Its callback reads the id of
        // the video to rate it, from a field of its own and through getters without parameters.
        // These are made a method of the callback, which the extension calls through an interface.
        MediaSessionCallbackSetRatingFingerprint.let { fingerprint ->
            val setRatingMethod = fingerprint.method
            val callbackClass = fingerprint.classDef
            val instructions = setRatingMethod.implementation!!.instructions.toList()

            fun unexpected(): Nothing =
                throw PatchException("Unexpected way of reading the video id in ${setRatingMethod.definingClass}")

            fun MethodReference.isGetter() = parameterTypes.isEmpty()

            val thisRegister = setRatingMethod.implementation!!.registerCount -
                    setRatingMethod.parameterTypes.size - 1

            val videoIdIndex = instructions.indexOfFirst { instruction ->
                val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                reference != null && reference.isGetter() && reference.returnType == "Ljava/lang/String;" &&
                        (instruction.opcode == Opcode.INVOKE_VIRTUAL || instruction.opcode == Opcode.INVOKE_INTERFACE)
            }
            if (videoIdIndex < 0 || instructions[videoIdIndex + 1].opcode != Opcode.MOVE_RESULT_OBJECT) unexpected()
            var index = videoIdIndex

            val getters = ArrayDeque<String>()
            val field: FieldReference
            while (true) {
                val invoke = instructions[index]
                val reference = (invoke as ReferenceInstruction).reference as MethodReference
                val opcode = when (invoke.opcode) {
                    Opcode.INVOKE_VIRTUAL -> "invoke-virtual"
                    Opcode.INVOKE_INTERFACE -> "invoke-interface"
                    else -> unexpected()
                }
                if (!reference.isGetter() || (invoke as FiveRegisterInstruction).registerCount != 1 || index < 1) {
                    unexpected()
                }

                getters.addFirst(
                    """
                        $opcode { v0 }, ${reference.definingClass}->${reference.name}()${reference.returnType}
                        move-result-object v0
                    """
                )

                val objectRegister = invoke.registerC
                val previous = instructions[index - 1]
                if (previous.opcode == Opcode.IGET_OBJECT) {
                    previous as TwoRegisterInstruction
                    if (previous.registerA != objectRegister || previous.registerB != thisRegister) unexpected()

                    field = (previous as ReferenceInstruction).reference as FieldReference
                    break
                }
                if (previous.opcode != Opcode.MOVE_RESULT_OBJECT ||
                    (previous as OneRegisterInstruction).registerA != objectRegister
                ) {
                    unexpected()
                }

                index -= 2
                if (index < 0) unexpected()
            }

            callbackClass.interfaces.add(VIDEO_ID_PROVIDER_CLASS)
            callbackClass.methods.add(
                ImmutableMethod(
                    callbackClass.type,
                    VIDEO_ID_PROVIDER_METHOD,
                    emptyList(),
                    "Ljava/lang/String;",
                    AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                    null,
                    null,
                    MutableMethodImplementation(2)
                ).toMutable().apply {
                    addInstructions(
                        0,
                        """
                            iget-object v0, p0, ${field.definingClass}->${field.name}:${field.type}
                            ${getters.joinToString("\n")}
                            return-object v0
                        """
                    )
                }
            )

            callbackClass.addConstructedHook("setVideoIdProvider($VIDEO_ID_PROVIDER_CLASS)V")

            // The app has no video to rate once the video is closed.
            val videoIdRegister = (instructions[videoIdIndex + 1] as OneRegisterInstruction).registerA
            setRatingMethod.addInstructions(
                videoIdIndex + 2,
                """
                    invoke-static/range { v$videoIdRegister .. v$videoIdRegister }, $EXTENSION_CLASS->getVideoIdToRate(Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v$videoIdRegister
                """
            )
        }

        // The app keeps if a video is liked in an entity, which it makes a model of
        // as it reads the entity and as it changes it.
        LikeStatusEntityModelToStringFingerprint.classDef
            .addConstructedHook("onLikeStatusEntity(Ljava/lang/Object;)V")

        // The app makes the list of the caption tracks of a video from the id of the video
        // and a renderer of the player response.
        CaptionTrackListTranslatedTracksFingerprint.classDef.let { trackListClass ->
            val constructor = trackListClass.methods.singleOrNull { method ->
                val parameters = method.parameterTypes.map { it.toString() }
                method.name == "<init>" && parameters.size >= 3 &&
                        parameters[0] == "Ljava/lang/String;" && parameters[1] == "Z" &&
                        parameters[2].startsWith("L") && parameters[2] != "Ljava/lang/String;"
            } ?: throw PatchException("Unexpected constructor of the caption track list ${trackListClass.type}")

            constructor.addInstructions(
                0,
                "invoke-static/range { p1 .. p3 }, " +
                        "$EXTENSION_CLASS->onCaptionTracks(Ljava/lang/String;ZLjava/lang/Object;)V"
            )
        }

        // What plays after a video is part of the watch next response. Other patches change
        // the response as its model is constructed, so it is read once that is done.
        WatchNextResponseModelConstructorFingerprint.let { fingerprint ->
            val constructor = fingerprint.method
            val modelClass = fingerprint.classDef
            val responseType = constructor.parameterTypes.first().toString()
            val responseField = modelClass.fields.singleOrNull { it.type == responseType }
                ?: throw PatchException("Could not find the response of ${modelClass.type}")

            val parameterRegisters = 2
            if (constructor.implementation!!.registerCount - parameterRegisters < 1) {
                throw PatchException("Not enough registers in the constructor of ${modelClass.type}")
            }

            // The constructor uses its parameter register for other values.
            constructor.addInstructionsBeforeReturns(
                "move-object/from16 v0, p0",
                """
                    iget-object v0, v0, ${modelClass.type}->${responseField.name}:${responseField.type}
                    invoke-static { v0 }, $EXTENSION_CLASS->onWatchNextResponse(Ljava/lang/Object;)V
                """
            )
        }
    }
}
