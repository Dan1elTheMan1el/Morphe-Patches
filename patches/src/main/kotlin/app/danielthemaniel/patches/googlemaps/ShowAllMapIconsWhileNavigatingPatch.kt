package app.danielthemaniel.patches.googlemaps

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val NAV_ICONS = "Lapp/danielthemaniel/patches/googlemaps/extension/NavIcons;"

/** In the converter of Maps' client map-style enum ("VersatileMapStyle"), the same in 26.44 and 26.47. */
private const val STYLE_ENUM_MARKER = "Can not convert VersatileMapStyle: "

/**
 * Keeps the map's icons while driving, on the phone and in Android Auto.
 *
 * Maps thins the map out in two separate places once a route is up:
 *
 *  - Place icons (restaurants, shops, gas stations ...): every client map style asks the server for a
 *    paint style, and the NAVIGATION* client styles ask for navigation paint styles that draw few places.
 *    When the style enum is initialised, each NAVIGATION* style is pointed at the regular map's paint
 *    style: ROADMAP, ROADMAP_DARK for the low-light and dark ones, ROADMAP_SATELLITE for satellite.
 *    Only the tiles change: the navigation camera, route, turn cards and day/night switching stay.
 *    NAVIGATION_MIN_MODE* (Maps' deliberately minimal map) is left alone.
 *
 *  - Your pins (Home, Work, saved places, and the Find Hub people from this bundle): the personal-place
 *    controller is hidden whenever a route is drawn. Its show/hide toggle now always shows.
 *
 * Patches run in name order: this one sorts after Dutch App Patches' "Offline saved places", which hooks
 * the same toggle, so its "show" is decided before their hook reads it.
 */
@Suppress("unused")
val showMapIconsWhileNavigatingPatch = bytecodePatch(
    name = "Show all map icons while navigating",
    description = "Keeps place icons (restaurants, shops, gas stations) and your pins (Home, Work, saved places, " +
        "Find Hub people) on the map during navigation, on the phone and in Android Auto.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_GOOGLE_MAPS)
    dependsOn(googleMapsExtensionPatch)

    execute {
        // Place icons: remap the navigation styles' paint style once the enum is built.
        val styles = mutableListOf<String>()
        classDefForEach { c ->
            if (c.superclass != "Ljava/lang/Enum;") return@classDefForEach
            if (c.methods.any { m -> m.implementation?.instructions?.any { (it.ref() as? StringReference)?.string == STYLE_ENUM_MARKER } == true }) {
                styles += c.type
            }
        }
        val styleType = styles.singleOrNull() ?: throw PatchException("expected one map style enum, found $styles")
        val style = mutableClassDefBy(styleType)
        // Its paint style: the one instance field of another enum type. Not final, so the remap can set it.
        val paint = style.fields.filter { f ->
            !AccessFlags.STATIC.isSet(f.accessFlags) && f.type != styleType &&
                classDefByOrNull(f.type)?.superclass == "Ljava/lang/Enum;"
        }.singleOrNull() ?: throw PatchException("$styleType has no single paint style field")
        paint.accessFlags = paint.accessFlags and AccessFlags.FINAL.value.inv()
        val clinit = style.methods.singleOrNull { it.name == "<clinit>" } ?: throw PatchException("$styleType has no static initialiser")
        val returns = clinit.implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }
        if (returns.isEmpty()) throw PatchException("$styleType's static initialiser has no return")
        // v0 is dead at the initialiser's return.
        returns.sortedDescending().forEach {
            clinit.addInstructions(
                it,
                """
                    const-class v0, $styleType
                    invoke-static { v0 }, $NAV_ICONS->navigationStyles(Ljava/lang/Class;)V
                """,
            )
        }

        // Your pins: the controller's show/hide toggle always shows.
        val labels = resolveLabelGenerator()
        val toggle = mutableClassDefBy(labels.controller).methods.singleOrNull {
            it.name == labels.controllerToggle && it.returnType == "V" &&
                it.parameterTypes.map(CharSequence::toString) == listOf("Z")
        } ?: throw PatchException("${labels.controller} has no show/hide toggle")
        toggle.addInstructions(0, "const/16 p1, 0x1")
    }
}

private fun com.android.tools.smali.dexlib2.iface.instruction.Instruction.ref() =
    (this as? ReferenceInstruction)?.reference
