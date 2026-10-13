package app.originisland.patches.shared.mediasession

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal fun MutableMethod.indicesOfCalls(definingClass: String, name: String) =
    implementation!!.instructions.withIndex().filter { (_, instruction) ->
        val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        reference?.definingClass == definingClass && reference.name == name
    }.map { it.index }

/**
 * Has the extension handle what the music card sends, and the app handle everything else.
 * Only the parameter registers are used, since the method may have no locals.
 *
 * @param extensionMethod A method that returns null for what it handled.
 */
internal fun MutableMethod.addMusicCardHook(extensionClass: String, parameters: String, extensionMethod: String) =
    addInstructionsWithLabels(
        0,
        """
            invoke-static/range { $parameters }, $extensionClass->$extensionMethod
            move-result-object p1
            if-nez p1, :original
            return-void
        """,
        ExternalLabel("original", getInstruction(0))
    )
