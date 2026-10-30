package app.danielthemaniel.patches.youtube

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS =
    "Lapp/danielthemaniel/patches/youtube/extension/CurrentFrameMediaArtwork;"

/** The call that publishes media metadata to the system. Hooked to see (and later replace) it. */
private object MediaSessionSetMetadataFingerprint : Fingerprint(
    filters = listOf(
        methodCall(
            definingClass = "Landroid/media/session/MediaSession;",
            name = "setMetadata",
            parameters = listOf("Landroid/media/MediaMetadata;"),
        ),
    ),
)

/**
 * Morphe's add-on entry point. Morphe's patches declare this method and never modify it;
 * add-on bundles insert a call to their own static registration method at its start.
 * The method lives in Morphe's YouTube extension, so it only exists when this bundle
 * is applied together with Morphe's YouTube patches.
 */
private object AddOnRegisterFingerprint : Fingerprint(
    definingClass = "Lapp/morphe/extension/youtube/addon/AddOnManager;",
    name = "registerAddOns",
    returnType = "V",
    parameters = listOf(),
)

// The remaining fingerprints use YouTube's obfuscated names, so they only match the version
// this bundle targets (see COMPATIBILITY_YOUTUBE).

/**
 * Laltw; is the player's ExoPlayer wrapper and r() re-evaluates which Surface the player renders
 * into. When the video view has no surface (app hidden, screen off), r() uses null, which drops
 * the video output.
 */
private object VideoSurfaceSelectionFingerprint : Fingerprint(
    definingClass = "Laltw;",
    name = "r",
    returnType = "Ljava/lang/Boolean;",
    parameters = listOf("Lalwn;"),
)

/**
 * Lalth; is the player engine. I(foreground, reason) is its "app foreground state changed"
 * handler: it arms the pause timers and notifies the native fetch controller.
 */
private object EngineForegroundFingerprint : Fingerprint(
    definingClass = "Lalth;",
    name = "I",
    returnType = "V",
    parameters = listOf("Z", "Lazfh;"),
)

/**
 * Lalwt; is the input to YouTube's track selector. Its constructor takes "a video view exists",
 * and without one the selector builds no video selection, which disables the video renderer.
 */
private object TrackSelectionInputFingerprint : Fingerprint(
    definingClass = "Lalwt;",
    name = "<init>",
    returnType = "V",
    parameters = listOf("Lalwn;", "Lalif;", "Lamdi;", "Z"),
)

@Suppress("unused")
val currentFrameMediaArtworkPatch = bytecodePatch(
    name = "Current Frame Media Artwork",
    description = "Adds an in-player control that updates the media artwork from the current " +
        "video frame, including while the screen is off, and can blank the media title.",
    default = true,
) {
    extendWith("extensions/current_frame_media_artwork.dex")
    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        // Artwork: hand the session and metadata to the extension before every setMetadata call.
        val fingerprint = MediaSessionSetMetadataFingerprint
        val instruction = fingerprint.instructionMatches.first()
        val registers = instruction.getInstruction<FiveRegisterInstruction>()

        fingerprint.method.addInstructions(
            instruction.index,
            "invoke-static { v${registers.registerC}, v${registers.registerD} }, " +
                "$EXTENSION_CLASS->onMetadata(" +
                "Landroid/media/session/MediaSession;Landroid/media/MediaMetadata;)V",
        )

        // Background frames: wherever r() would pick "no surface" (null) for the player, pick
        // the extension's off-screen surface instead. backgroundSurface is null unless the
        // feature is enabled, so behavior is unchanged when it is off.
        //
        // r() has two such places (v9 = surface, v3 = null):
        //   1. view exists but has no surface (screen off): "move-object v9, v3"
        //   2. view is detached entirely (activity stopped): "move-object v9, v3" followed by
        //      "move-object v10, v9", where v10 is the GL renderer and must stay null.
        VideoSurfaceSelectionFingerprint.method.apply {
            val instructions = implementation!!.instructions

            val noSurfaceMoves = instructions.withIndex().filter { (_, instruction) ->
                instruction.opcode == Opcode.MOVE_OBJECT &&
                    (instruction as TwoRegisterInstruction).let { it.registerA == 9 && it.registerB == 3 }
            }.map { it.index }
            if (noSurfaceMoves.size != 2) {
                throw PatchException("Expected 2 no-surface branches in the player but found ${noSurfaceMoves.size}")
            }
            val (viewNoSurfaceIndex, viewDetachedIndex) = noSurfaceMoves

            // v10 must keep being null in the detached branch.
            val detachedRendererIndex = viewDetachedIndex + 1
            val rendererMove = instructions[detachedRendererIndex]
            if (rendererMove.opcode != Opcode.MOVE_OBJECT ||
                (rendererMove as TwoRegisterInstruction).let { it.registerA != 10 || it.registerB != 9 }
            ) {
                throw PatchException("Unexpected instruction after the detached-view branch")
            }

            // With no view there is nothing to ask for its type, so skip the alternate-surface
            // block (which dereferences the view) when v8 is null. Insert before its
            // "Laltv;->f()Z" call and jump to the code that follows that block, which starts at
            // the first "iget-boolean v12" in the method.
            val altSurfaceCheckIndex = instructions.indexOfFirst { instruction ->
                instruction.opcode == Opcode.INVOKE_INTERFACE &&
                    (instruction as ReferenceInstruction).reference.let {
                        it is MethodReference && it.definingClass == "Laltv;" && it.name == "f"
                    }
            }
            val afterAltSurfaceIndex = instructions.indexOfFirst { instruction ->
                instruction.opcode == Opcode.IGET_BOOLEAN &&
                    (instruction as TwoRegisterInstruction).registerA == 12
            }
            if (altSurfaceCheckIndex < 0 || afterAltSurfaceIndex <= altSurfaceCheckIndex) {
                throw PatchException("Could not locate the alternate-surface block in the player")
            }

            val skipTarget = ExternalLabel("skipAltSurface", getInstruction(afterAltSurfaceIndex))

            // Same-size replacements first (indexes do not move), insertion last.
            val fallbackSurface = "sget-object v9, $EXTENSION_CLASS->backgroundSurface:Landroid/view/Surface;"
            replaceInstruction(viewNoSurfaceIndex, fallbackSurface)
            replaceInstruction(viewDetachedIndex, fallbackSurface)
            replaceInstruction(detachedRendererIndex, "move-object v10, v3")

            addInstructionsWithLabels(
                altSurfaceCheckIndex,
                "if-eqz v8, :skipAltSurface",
                skipTarget,
            )
        }

        // Keep the engine in "foreground" mode while background frames are enabled, so it does
        // not start its background handling when the app is hidden (p1 = foreground flag).
        EngineForegroundFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p1 }, $EXTENSION_CLASS->engineForeground(Z)Z
                move-result p1
            """,
        )

        // Keep video selected while hidden: report that a video view exists (p4 = hasView).
        TrackSelectionInputFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p4 }, $EXTENSION_CLASS->hasVideoView(Z)Z
                move-result p4
            """,
        )

        // Overlay button: subscribe to Morphe's player overlay button hook at runtime.
        AddOnRegisterFingerprint.method.addInstruction(
            0,
            "invoke-static { }, $EXTENSION_CLASS->register()V",
        )
    }
}