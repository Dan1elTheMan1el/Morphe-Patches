package app.danielthemaniel.patches.googlemaps

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val EXT = "Lapp/danielthemaniel/patches/googlemaps/extension/NavatarModes;"

/**
 * Maps picks the navigation location marker ("puck mode", enum with NAVIGATION_CUSTOM_3D_CHEVRON*) from the
 * travel mode (enum DRIVE, BICYCLE, WALK, ..., TWO_WHEELER) in one class (apmr in 26.47):
 *  - af(travel) -> puck mode: DRIVE -> NAVIGATION_CUSTOM_3D_CHEVRON, BICYCLE / TWO_WHEELER -> their own 3D
 *    modes behind server flags, the rest -> NAVIGATION. Found by shape: (enum) -> puck mode, switching on
 *    ordinal() and reading NAVIGATION_CUSTOM_3D_CHEVRON_BICYCLE.
 *  - am(route, travel) -> boolean: may the 3D vehicle be used (DRIVE: a vehicle is picked and the route
 *    allows it; BICYCLE / TWO_WHEELER: server flag; others: never). Found as the only (L, travel)Z method
 *    of the same class that switches on the travel mode's ordinal().
 * Both get a prologue that swaps walking, cycling, two-wheeler and taxi for DRIVE, so those modes use the
 * vehicle picked for driving.
 */
@Suppress("unused")
val navatarAllTravelModesPatch = bytecodePatch(
    name = "Navigation vehicle in all travel modes",
    description = "Shows the 3D vehicle you picked for driving (or your custom one) while navigating on foot, " +
        "by bike, two-wheeler or taxi too, instead of the plain arrow.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_GOOGLE_MAPS)
    dependsOn(googleMapsExtensionPatch)

    execute {
        val puckMode = enumWith("NAVIGATION_CUSTOM_3D_CHEVRON_BICYCLE")
        val bicycle = enumField(puckMode, "NAVIGATION_CUSTOM_3D_CHEVRON_BICYCLE")

        val selectors = mutableListOf<Pair<String, String>>() // owner, travel mode type
        classDefForEach { c ->
            c.methods.filter { m ->
                !AccessFlags.STATIC.isSet(m.accessFlags) && m.returnType == puckMode.type && m.parameterTypes.size == 1 &&
                    m.reads(puckMode.type, bicycle) && m.callsOrdinal()
            }.forEach { selectors += c.type to it.parameterTypes[0].toString() }
        }
        val (owner, travel) = selectors.singleOrNull()
            ?: throw PatchException("expected one travel mode -> marker selector, found $selectors")

        val ownerDef = mutableClassDefBy(owner)
        val select = ownerDef.methods.single { m ->
            m.returnType == puckMode.type && m.parameterTypes.map(CharSequence::toString) == listOf(travel) && m.reads(puckMode.type, bicycle)
        }
        val allowed = ownerDef.methods.filter { m ->
            !AccessFlags.STATIC.isSet(m.accessFlags) && m.returnType == "Z" && m.parameterTypes.size == 2 &&
                m.parameterTypes[1].toString() == travel && m.callsOrdinal()
        }.singleOrNull() ?: throw PatchException("$owner has no single (route, travel mode) gate")

        // af(p1 = travel), am(p1 = route, p2 = travel)
        listOf(select to "p1", allowed to "p2").forEach { (m, r) ->
            m.addInstructions(
                0,
                """
                    invoke-static { $r }, $EXT->travel(Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object $r
                    check-cast $r, $travel
                """,
            )
        }
    }
}

private fun BytecodePatchContext.enumWith(vararg names: String): ClassDef {
    val found = mutableListOf<ClassDef>()
    classDefForEach { c ->
        if (c.superclass != "Ljava/lang/Enum;") return@classDefForEach
        val strings = c.methods.firstOrNull { it.name == "<clinit>" }?.implementation?.instructions
            ?.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }.orEmpty()
        if (names.all { it in strings }) found += c
    }
    return found.singleOrNull() ?: throw PatchException("expected one enum with ${names.toList()}, found ${found.map { it.type }}")
}

/** The static field <clinit> stores the constant named [name] in (const-string name ... sput-object). */
private fun enumField(enum: ClassDef, name: String): String {
    val insns = enum.methods.first { it.name == "<clinit>" }.implementation!!.instructions.toList()
    val at = insns.indexOfFirst { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == name }
    if (at < 0) throw PatchException("${enum.type} has no $name")
    return insns.drop(at).firstNotNullOfOrNull { insn ->
        ((insn as? ReferenceInstruction)?.reference as? FieldReference)
            ?.takeIf { insn.opcode == Opcode.SPUT_OBJECT && it.type == enum.type }?.name
    } ?: throw PatchException("${enum.type}.$name is never stored")
}

private fun Method.reads(owner: String, field: String) = implementation?.instructions?.any {
    ((it as? ReferenceInstruction)?.reference as? FieldReference)?.let { f -> f.definingClass == owner && f.name == field } == true
} == true

private fun Method.callsOrdinal() = implementation?.instructions?.any {
    it.opcode == Opcode.INVOKE_VIRTUAL && ((it as ReferenceInstruction).reference as? MethodReference)?.name == "ordinal"
} == true
