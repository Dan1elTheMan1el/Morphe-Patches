package app.danielthemaniel.patches.googlemaps

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/** Dutch App Patches' UI switches, from their extension (merged when their Maps patches are selected). */
private const val DUTCH_SHAPES = "Lorg/ungoogled/ui/Shapes;"

/**
 * Repairs two runtime bugs in Dutch App Patches 1.95.0-1.98.0 "Hide ads and clutter" (Maps 26.44 and 26.47).
 *
 * 1. Hide Explore feed forces the home sheet OPEN. At the sheet-state merge point it sets the state to
 *    `agqi.a` (26.47) / `aggg.a` (26.44), assuming that is HIDDEN; both are initialised to the enum's
 *    EXPANDED constant. This points that read at the enum constant named "HIDDEN".
 *
 * 2. Hide AI never turns off the "Ask Maps" master switch. Its guard (`true` becomes `!HIDE_AI`) was put
 *    in front of the switch's `return`, but the true path `const/4 1; goto` jumps to the `return` itself,
 *    past the guard. This makes those `true` constants `!HIDE_AI` at their source.
 *
 * Patches run in name order, so this ("Hide ads and clutter fixes") runs right after the Dutch "Hide ads
 * and clutter". Each fix matches the Dutch-inserted code by shape, so it does nothing when that patch is
 * not selected or once the bugs are fixed upstream.
 */
@Suppress("unused")
val dutchHideAdsAndClutterFixPatch = bytecodePatch(
    name = "Hide ads and clutter fixes",
    description = "Fixes Dutch App Patches' \"Hide ads and clutter\": the Explore feed (\"Local vibe\") opening " +
        "instead of hiding, and Ask Maps staying on. Patch in the same run.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_GOOGLE_MAPS)

    execute {
        // Only methods that read one of Dutch's switches can hold their guards.
        val candidates = mutableListOf<Pair<ClassDef, Method>>()
        classDefForEach { c ->
            if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            for (m in c.methods) {
                if (m.implementation?.instructions?.any { it.shapesFlag() != null } == true) candidates += c to m
            }
        }
        for ((c, m) in candidates) {
            val flags = m.implementation!!.instructions.mapNotNull { it.shapesFlag() }.toSet()
            if ("HIDE_EXPLORE" in flags) fixExploreState(c, m)
            if ("HIDE_AI" in flags) fixBypassedAiGuard(c, m)
        }
    }
}

private fun Instruction.shapesFlag(): String? {
    if (opcode != Opcode.SGET_BOOLEAN) return null
    val field = (this as ReferenceInstruction).reference as FieldReference
    return field.name.takeIf { field.definingClass == DUTCH_SHAPES }
}

private fun BytecodePatchContext.mutableMethod(c: ClassDef, m: Method) =
    mutableClassDefBy(c.type).methods.single {
        it.name == m.name && it.returnType == m.returnType &&
            it.parameterTypes.map(CharSequence::toString) == m.parameterTypes.map(CharSequence::toString)
    }

/** The enum constant field named [constant] in [enumType], from its static initialiser, or null. */
private fun BytecodePatchContext.enumConstant(enumType: String, constant: String): String? {
    val enum = classDefByOrNull(enumType)?.takeIf { it.superclass == "Ljava/lang/Enum;" } ?: return null
    val code = enum.methods.firstOrNull { it.name == "<clinit>" }?.implementation?.instructions?.toList() ?: return null
    val named = code.indexOfFirst { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == constant }
    if (named < 0) return null
    val stored = code.drop(named).firstOrNull { it.opcode == Opcode.SPUT_OBJECT } ?: return null
    val field = (stored as ReferenceInstruction).reference as FieldReference
    return field.name.takeIf { field.definingClass == enumType && field.type == enumType }
}

/** `sget-boolean vS, HIDE_EXPLORE; if-eqz vS, :keep; sget-object vX, F` -> F becomes the enum's HIDDEN. */
private fun BytecodePatchContext.fixExploreState(c: ClassDef, m: Method) {
    val code = m.implementation!!.instructions.toList()
    for (i in 0 until code.size - 2) {
        if (code[i].shapesFlag() != "HIDE_EXPLORE") continue
        if (code[i + 1].opcode != Opcode.IF_EQZ || code[i + 2].opcode != Opcode.SGET_OBJECT) continue
        val field = (code[i + 2] as ReferenceInstruction).reference as FieldReference
        val hidden = enumConstant(field.type, "HIDDEN") ?: continue
        if (field.definingClass == field.type && field.name == hidden) continue // already HIDDEN
        val register = (code[i + 2] as OneRegisterInstruction).registerA
        mutableMethod(c, m).replaceInstruction(i + 2, "sget-object v$register, ${field.type}->$hidden:${field.type}")
    }
}

/**
 * Dutch's return guard: `if-eqz vR, :out; sget-boolean vR, HIDE_AI; xor-int/lit8 vR, vR, 1; :out return vR`.
 * Any other branch to that `return` skips it; where such a branch carries a literal `true`
 * (`const/4 vR, 1` right before it), that `true` becomes `!HIDE_AI`.
 */
private fun BytecodePatchContext.fixBypassedAiGuard(c: ClassDef, m: Method) {
    val mutable = mutableMethod(c, m)
    val code = mutable.implementation!!.instructions
    data class Fix(val index: Int, val register: Int)
    val fixes = mutableListOf<Fix>()
    for (i in 1 until code.size - 2) {
        if (code[i].shapesFlag() != "HIDE_AI") continue
        val guard = code[i - 1]
        val register = (code[i] as OneRegisterInstruction).registerA
        if (guard.opcode != Opcode.IF_EQZ || (guard as OneRegisterInstruction).registerA != register) continue
        val xor = code[i + 1]
        if (xor.opcode != Opcode.XOR_INT_LIT8 || (xor as TwoRegisterInstruction).registerA != register) continue
        val ret = code[i + 2]
        if (ret.opcode != Opcode.RETURN || (ret as OneRegisterInstruction).registerA != register) continue
        if ((guard as BuilderOffsetInstruction).target.location.index != i + 2) continue
        for (b in code.indices) {
            if (b == i - 1) continue
            val branch = code[b] as? BuilderOffsetInstruction ?: continue
            if (branch.target.location.index != i + 2 || b == 0) continue
            val before = code[b - 1]
            if (before.opcode == Opcode.CONST_4 && (before as OneRegisterInstruction).registerA == register &&
                (before as NarrowLiteralInstruction).narrowLiteral == 1
            ) fixes += Fix(b - 1, register)
        }
    }
    // Latest first, so each insert leaves the earlier indices alone.
    for (fix in fixes.distinct().sortedByDescending { it.index }) {
        mutable.replaceInstruction(fix.index, "sget-boolean v${fix.register}, $DUTCH_SHAPES->HIDE_AI:Z")
        mutable.addInstruction(fix.index + 1, "xor-int/lit8 v${fix.register}, v${fix.register}, 0x1")
    }
}
