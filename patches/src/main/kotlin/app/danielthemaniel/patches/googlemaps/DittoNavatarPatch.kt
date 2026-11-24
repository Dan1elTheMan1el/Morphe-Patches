package app.danielthemaniel.patches.googlemaps

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference


private const val EXT = "Lapp/danielthemaniel/patches/googlemaps/extension/CustomNavatar;"
private const val FLIPBOOK = "Lapp/danielthemaniel/patches/googlemaps/extension/Flipbook;"
private const val SETUP = "Lapp/danielthemaniel/patches/googlemaps/extension/DittoSetup;"
private const val FBX_LOADER = "Lcom/google/android/libraries/geo/mapcore/internal/impl/fbx/FbxNativeUtil;"
private const val SETTABLE_FUTURE = "Lcom/google/common/util/concurrent/SettableFuture;"

/**
 * Adds an animated Ditto to Maps' navatar (3D navigation vehicle) picker, built on the device.
 *
 *  - Vehicle list: the manifest parser (azgt(String, int) in 26.47, found by "Models array was empty") gets
 *    the JSON with a "dtm_ditto" entry added once Ditto is built: scale 3420 (car.glb x 1.8), one colour,
 *    files and icon under a made-up "dtm_ditto/" folder of Maps' download base URL.
 *  - Files: the vehicle downloader (voq.b(url, name, folder)) serves dtm_ditto/ URLs from Maps' private
 *    storage and returns an already completed future; the loader then reads them like any vehicle.
 *  - Icon: the descriptor's icon URL getters return a file:// URI to the icon DittoBuilder rendered.
 *  - Animation: Maps' map engine draws a .glb's raw mesh without skins or animations, so animation is a
 *    flipbook of posed copies (Flipbook), started only when the loader (voq.J, the one caller of
 *    FbxNativeUtil.nativeLoadFbxModel) loads Ditto: the glTF model's constructor call, placement c(), clip
 *    list b(), clip switch j() and destroy d() are hooked.
 *  - Setup: Ditto is built once by DittoBuilder from the Pokémon X/Y Ditto archive, which the user downloads
 *    in an in-app browser offered from MapsActivity's onResume() (DittoSetup).
 */
@Suppress("unused")
val dittoNavatarPatch = bytecodePatch(
    name = "Ditto navigation vehicle",
    description = "Adds an animated Ditto to the 3D vehicles you can pick for navigation. On first launch, Maps " +
        "opens the Pokémon X/Y Ditto model page (The Models Resource) in an in-app browser; tap download once and " +
        "Maps builds Ditto from it. Then pick him while navigating (tap the arrow).",
    default = true,
) {
    compatibleWith(COMPATIBILITY_GOOGLE_MAPS)
    dependsOn(googleMapsExtensionPatch)

    execute {
        val loaderClass = findLoaderClass()
        val loaderClassDef = mutableClassDefBy(loaderClass.type)
        val loader = loaderClassDef.methods.single { m -> m.calls(FBX_LOADER, "nativeLoadFbxModel") }
        val descriptorType = loader.parameterTypes.firstOrNull()?.toString()
            ?: throw PatchException("navatar loader ${loader.name} has no descriptor parameter")

        // The loader's descriptor: remember whether the vehicle being loaded is Ditto (for the flipbook).
        loader.addInstructions(0, "invoke-static/range { p1 .. p1 }, $EXT->loading(Ljava/lang/Object;)V")

        // Vehicle list: the manifest parser, new <manifest>(String json, int) reading "models".
        val manifestParsers = mutableListOf<String>()
        classDefForEach { c ->
            if (c.methods.any { m ->
                    m.name == "<init>" && m.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/String;", "I") &&
                        m.instructions.any { (it.ref() as? StringReference)?.string == "Models array was empty" }
                }) manifestParsers += c.type
        }
        val manifestType = manifestParsers.singleOrNull() ?: throw PatchException("no single manifest parser ($manifestParsers)")
        mutableClassDefBy(manifestType).methods.single { m ->
            m.name == "<init>" && m.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/String;", "I")
        }.addInstructions(
            0,
            """
                invoke-static { p1 }, $EXT->manifest(Ljava/lang/String;)Ljava/lang/String;
                move-result-object p1
            """,
        )

        // Downloads: (url, file name, folder) -> future. Ditto's files are served locally, then an already
        // completed future is returned the way the method itself builds one (SettableFuture -> wrapper).
        val download = loaderClassDef.methods.singleOrNull { m ->
            m.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/String;", "Ljava/lang/String;", "Ljava/io/File;") &&
                m.instructions.any { (it.ref() as? MethodReference)?.definingClass == SETTABLE_FUTURE }
        } ?: throw PatchException("${loaderClass.type} has no download method")
        val callback = download.instructions.mapNotNull { insn ->
            (insn.ref() as? com.android.tools.smali.dexlib2.iface.reference.TypeReference)?.type
                ?.takeIf { insn.opcode == Opcode.NEW_INSTANCE && it != SETTABLE_FUTURE }
        }.first()
        val set = classDefBy(callback).methods.flatMap { it.instructions }.mapNotNull { it.ref() as? MethodReference }
            .first { it.definingClass == SETTABLE_FUTURE && it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Object;") }
        val wrap = download.instructions.filter { it.opcode == Opcode.INVOKE_STATIC }.mapNotNull { it.ref() as? MethodReference }
            .last { it.returnType == download.returnType }
        download.addInstructionsWithLabels(
            0,
            """
                invoke-static/range { p1 .. p3 }, $EXT->serve(Ljava/lang/String;Ljava/lang/String;Ljava/io/File;)Z
                move-result v0
                if-eqz v0, :maps
                new-instance v0, $SETTABLE_FUTURE
                invoke-direct { v0 }, $SETTABLE_FUTURE-><init>()V
                const/4 v1, 0x0
                invoke-virtual { v0, v1 }, $set
                invoke-static { v0 }, $wrap
                move-result-object v0
                return-object v0
            """,
            ExternalLabel("maps", download.getInstruction(0)),
        )

        // Picker icon URLs: the descriptor's (base, DisplayMetrics[, colour]) -> String getters.
        val iconGetters = mutableClassDefBy(descriptorType).methods.filter { m ->
            m.returnType == "Ljava/lang/String;" && m.parameterTypes.size >= 2 &&
                m.parameterTypes[0].toString() == "Ljava/lang/String;" && m.parameterTypes[1].toString() == "Landroid/util/DisplayMetrics;"
        }
        if (iconGetters.isEmpty()) throw PatchException("$descriptorType has no icon URL getters")
        iconGetters.forEach { m ->
            m.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }.map { it.index }.sortedDescending().forEach { i ->
                val r = (m.instructions[i] as OneRegisterInstruction).registerA
                // Replace the return itself so branches that jump to it (goto :return) pass the hook too.
                m.replaceInstruction(i, "invoke-static/range { v$r .. v$r }, $EXT->icon(Ljava/lang/String;)Ljava/lang/String;")
                m.addInstructions(
                    i + 1,
                    """
                        move-result-object v$r
                        return-object v$r
                    """,
                )
            }
        }

        // CustomChevronGltfModel: the glTF vehicle.
        val gltfModel = mutableListOf<String>()
        classDefForEach { c ->
            if (c.methods.any { m -> m.instructions.any { (it.ref() as? StringReference)?.string?.contains("CustomChevronGltfModel") == true } }) gltfModel += c.type
        }
        val gltfType = gltfModel.singleOrNull() ?: throw PatchException("no single CustomChevronGltfModel (found $gltfModel)")
        val gltf = mutableClassDefBy(gltfType)
        fun gltfMethod(name: String, params: List<String>?, ret: String) = gltf.methods.singleOrNull { m ->
            m.name == name && m.returnType == ret &&
                (params == null || m.parameterTypes.map(CharSequence::toString) == params)
        } ?: throw PatchException("$gltfType has no $name(...)$ret")

        // c(position, f, bearing, scale): flip to the frame due now.
        val place = gltf.methods.singleOrNull { m ->
            m.name == "c" && m.returnType == "V" && m.parameterTypes.map(CharSequence::toString).drop(1) == listOf("F", "F", "F")
        } ?: throw PatchException("$gltfType has no placement method")
        place.addInstructions(
            0,
            """
                invoke-static/range { p0 .. p0 }, $FLIPBOOK->frame(Ljava/lang/Object;)V
            """,
        )
        // b(): clip names (empty for glTF) -> the bundled clips, so the renderer asks for them.
        gltfMethod("b", emptyList(), "Ljava/util/Collection;").addInstructions(
            0,
            """
                invoke-static { p0 }, $FLIPBOOK->clips(Ljava/lang/Object;)Ljava/util/Collection;
                move-result-object p0
                return-object p0
            """,
        )
        // j(clip): a no-op for glTF -> switch the flipbook clip.
        gltfMethod("j", listOf("Ljava/lang/String;"), "V")
            .addInstructions(0, "invoke-static { p0, p1 }, $FLIPBOOK->clip(Ljava/lang/Object;Ljava/lang/String;)V")
        // d(): destroy -> destroy the frames too.
        gltfMethod("d", emptyList(), "V").addInstructions(0, "invoke-static { p0 }, $FLIPBOOK->destroy(Ljava/lang/Object;)V")

        // The loader builds the model with new CustomChevronGltfModel(engine, bytes, texture, blend, registry,
        // z, z, z); right after, the flipbook builds its frames with the same arguments.
        val builds = loader.instructions.withIndex().filter { (_, insn) ->
            insn.opcode == Opcode.INVOKE_DIRECT_RANGE &&
                (insn.ref() as? MethodReference)?.let { it.definingClass == gltfType && it.name == "<init>" && it.parameterTypes.size == 8 } == true
        }
        if (builds.isEmpty()) throw PatchException("${loader.name} never builds $gltfType")
        builds.sortedByDescending { it.index }.forEach { (i, insn) ->
            val r = insn as RegisterRangeInstruction
            val last = r.startRegister + r.registerCount - 1
            loader.addInstructions(
                i + 1,
                "invoke-static/range { v${r.startRegister} .. v$last }, $FLIPBOOK->created(" +
                    "Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Landroid/graphics/Bitmap;FLjava/lang/Object;ZZZ)V",
            )
        }

        // First-run setup: the main activity's onResume() offers the in-app download (DittoSetup).
        var activity: String? = "Lcom/google/android/maps/MapsActivity;"
        var onResume: app.morphe.patcher.util.proxy.mutableTypes.MutableMethod? = null
        while (activity != null && activity.startsWith("L") && !activity.startsWith("Landroid/")) {
            val c = classDefByOrNull(activity) ?: break
            val m = c.methods.firstOrNull { it.name == "onResume" && it.parameterTypes.isEmpty() && it.implementation != null }
            if (m != null) {
                onResume = mutableClassDefBy(activity).methods.first { it.name == "onResume" && it.parameterTypes.isEmpty() }
                break
            }
            activity = c.superclass
        }
        (onResume ?: throw PatchException("MapsActivity has no onResume()")).addInstructions(
            0,
            "invoke-static/range { p0 .. p0 }, $SETUP->onResume(Landroid/app/Activity;)V",
        )
    }
}

private fun BytecodePatchContext.findLoaderClass(): ClassDef {
    val found = mutableListOf<ClassDef>()
    classDefForEach { c -> if (c.methods.any { it.calls(FBX_LOADER, "nativeLoadFbxModel") }) found += c }
    return found.singleOrNull() ?: throw PatchException("expected one navatar loader, found ${found.map { it.type }}")
}

private fun Method.calls(owner: String, name: String) =
    instructions.any { (it.ref() as? MethodReference)?.let { r -> r.definingClass == owner && r.name == name } == true }

private val Method.instructions: List<Instruction>
    get() = implementation?.instructions?.toList().orEmpty()

private fun Instruction.ref() = (this as? ReferenceInstruction)?.reference
