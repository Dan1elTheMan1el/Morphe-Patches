package app.danielthemaniel.patches.defit

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction22x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference

/**
 * Replaces the foreground notification's "Tap to open DeFit" text with the
 * total distance generated during the current sync session.
 *
 * Example:
 *
 *     Session distance: 3.42 km
 *
 * The value is accumulated from the exact distance value DeFit submits to
 * Google Fit rather than estimated from elapsed time.
 */
@Suppress("unused")
val sessionDistanceNotificationPatch = bytecodePatch(
    name = "Session Distance Notification",
    description = "Shows the total distance added during the current sync session in the persistent notification.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_DEFIT)

    execute {
        val notificationService = mutableClassDefBy(
            "Lcom/googlefit/tester/NotificationService;"
        )

        // 0.9.3 uses readable method names. 0.8.2a uses the older obfuscated
        // names, so this is also a convenient version discriminator.
        val is093 = notificationService.methods.any {
            it.name == "get" &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0].toString() ==
                    "Landroid/content/Context;" &&
                it.returnType == "Landroid/app/Notification;"
        }

        val notificationMethod = notificationService.methods.firstOrNull {
            it.parameterTypes.size == 1 &&
                it.parameterTypes[0].toString() ==
                    "Landroid/content/Context;" &&
                it.returnType == "Landroid/app/Notification;"
        } ?: error(
            "Unable to find DeFit's notification builder method"
        )

        val notificationImplementation =
            notificationMethod.implementation
                ?: error(
                    "Notification builder has no implementation"
                )

        val notificationContextRegister =
            notificationImplementation.registerCount - 1

        /*
         * public static void patch_addSessionDistance(Context, float)
         *
         * Adds one exact DeFit distance DataPoint to our running total.
         *
         * 0.8.2a passes the sync Context directly, so no synthetic static
         * Context field is required and NotificationService is not mutated at
         * startup.
         */
        val latestSyncForAccumulator = if (is093) {
            """
            invoke-static {p0}, Lcom/googlefit/tester/Variables;->getLatestSyncMillis(Landroid/content/Context;)J
            """.trimIndent()
        } else {
            """
            invoke-static {p0}, Lk4/j;->d(Landroid/content/Context;)J
            """.trimIndent()
        }

        val addDistanceMethod = ImmutableMethod(
            notificationService.type,
            "patch_addSessionDistance",
            listOf(
                ImmutableMethodParameter(
                    "Landroid/content/Context;",
                    null,
                    null,
                ),
                ImmutableMethodParameter(
                    "F",
                    null,
                    null,
                ),
            ),
            "V",
            AccessFlags.PUBLIC.value or
                AccessFlags.STATIC.value,
            null,
            null,
            MutableMethodImplementation(12),
        ).toMutable().apply {
            addInstructions(
                0,
                """
                const-string v0, "defit_patch_notification"
                const/4 v1, 0x0
                invoke-virtual {p0, v0, v1}, Landroid/content/Context;->getSharedPreferences(Ljava/lang/String;I)Landroid/content/SharedPreferences;
                move-result-object v0

                $latestSyncForAccumulator
                move-result-wide v2

                const-wide/16 v4, -0x1
                cmp-long v6, v2, v4
                if-nez v6, :patch_distance_load

                invoke-interface {v0}, Landroid/content/SharedPreferences;->edit()Landroid/content/SharedPreferences${'$'}Editor;
                move-result-object v2

                const-string v3, "session_distance_m"
                const/4 v4, 0x0
                invoke-interface {v2, v3, v4}, Landroid/content/SharedPreferences${'$'}Editor;->putFloat(Ljava/lang/String;F)Landroid/content/SharedPreferences${'$'}Editor;
                move-result-object v2
                invoke-interface {v2}, Landroid/content/SharedPreferences${'$'}Editor;->apply()V

                :patch_distance_load
                const-string v1, "session_distance_m"
                const/4 v2, 0x0
                invoke-interface {v0, v1, v2}, Landroid/content/SharedPreferences;->getFloat(Ljava/lang/String;F)F
                move-result v2

                add-float/2addr v2, p1

                invoke-interface {v0}, Landroid/content/SharedPreferences;->edit()Landroid/content/SharedPreferences${'$'}Editor;
                move-result-object v0

                invoke-interface {v0, v1, v2}, Landroid/content/SharedPreferences${'$'}Editor;->putFloat(Ljava/lang/String;F)Landroid/content/SharedPreferences${'$'}Editor;
                move-result-object v0
                invoke-interface {v0}, Landroid/content/SharedPreferences${'$'}Editor;->apply()V

                return-void
                """.trimIndent()
            )
        }

        notificationService.methods.add(addDistanceMethod)

        /*
         * public static String patch_getSessionDistanceText(Context)
         *
         * This method is intentionally side-effect-free because it runs while
         * the foreground notification is being built during service startup.
         */
        val distanceTextMethod = ImmutableMethod(
            notificationService.type,
            "patch_getSessionDistanceText",
            listOf(
                ImmutableMethodParameter(
                    "Landroid/content/Context;",
                    null,
                    null,
                )
            ),
            "Ljava/lang/String;",
            AccessFlags.PUBLIC.value or
                AccessFlags.STATIC.value,
            null,
            null,
            MutableMethodImplementation(10),
        ).toMutable().apply {
            addInstructions(
                0,
                """
                const-string v0, "defit_patch_notification"
                const/4 v1, 0x0
                invoke-virtual {p0, v0, v1}, Landroid/content/Context;->getSharedPreferences(Ljava/lang/String;I)Landroid/content/SharedPreferences;
                move-result-object v0

                const-string v1, "session_distance_m"
                const/4 v2, 0x0
                invoke-interface {v0, v1, v2}, Landroid/content/SharedPreferences;->getFloat(Ljava/lang/String;F)F
                move-result v0

                const v1, 0x447a0000
                div-float/2addr v0, v1

                const-string v1, "Session distance: %.2f km"

                const/4 v2, 0x1
                new-array v2, v2, [Ljava/lang/Object;

                invoke-static {v0}, Ljava/lang/Float;->valueOf(F)Ljava/lang/Float;
                move-result-object v0

                const/4 v3, 0x0
                aput-object v0, v2, v3

                invoke-static {v1, v2}, Ljava/lang/String;->format(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;
                move-result-object v0

                return-object v0
                """.trimIndent()
            )
        }

        notificationService.methods.add(distanceTextMethod)

        val addDistanceReference = ImmutableMethodReference(
            notificationService.type,
            "patch_addSessionDistance",
            listOf(
                "Landroid/content/Context;",
                "F",
            ),
            "V",
        )

        val distanceTextReference = ImmutableMethodReference(
            notificationService.type,
            "patch_getSessionDistanceText",
            listOf("Landroid/content/Context;"),
            "Ljava/lang/String;",
        )

        /*
         * Accumulate the exact distance written by each version.
         */
        if (is093) {
            val googleFitTools = mutableClassDefBy(
                "Lcom/googlefit/tester/GoogleFitTools;"
            )

            val insertFit = googleFitTools.methods.firstOrNull {
                it.name == "insertFit" &&
                    it.returnType == "V"
            } ?: error(
                "Unable to find DeFit 0.9.3 GoogleFitTools.insertFit()"
            )

            val implementation = insertFit.implementation
                ?: error("insertFit() has no implementation")

            val instructions = implementation.instructions.toList()

            val getDistanceIndex =
                instructions.withIndex().firstOrNull {
                    (_, instruction) ->
                    val reference =
                        (instruction as? ReferenceInstruction)
                            ?.reference as? MethodReference

                    reference?.name == "getNormDistance" &&
                        reference.returnType == "F"
                }?.index
                    ?: error(
                        "Unable to find DeFit 0.9.3 distance calculation"
                    )

            val moveDistanceIndex =
                (getDistanceIndex + 1 until instructions.size)
                    .firstOrNull { index ->
                        instructions[index].opcode ==
                            Opcode.MOVE_RESULT
                    }
                    ?: error(
                        "Unable to find DeFit 0.9.3 distance result"
                    )

            val distanceRegister =
                (instructions[moveDistanceIndex] as?
                    OneRegisterInstruction)
                    ?.registerA
                    ?: error(
                        "Unable to resolve DeFit 0.9.3 distance register"
                    )

            val isStatic =
                insertFit.accessFlags and
                    AccessFlags.STATIC.value != 0

            fun width(type: CharSequence): Int =
                if (type == "J" || type == "D") 2 else 1

            val parameterWidth =
                insertFit.parameterTypes.sumOf { width(it) } +
                    if (isStatic) 0 else 1

            var parameterRegister =
                implementation.registerCount - parameterWidth

            if (!isStatic) {
                parameterRegister++
            }

            var contextRegister: Int? = null

            insertFit.parameterTypes.forEach { type ->
                if (
                    contextRegister == null &&
                    type.toString() ==
                        "Landroid/content/Context;"
                ) {
                    contextRegister = parameterRegister
                }
                parameterRegister += width(type)
            }

            val context =
                contextRegister
                    ?: error(
                        "Unable to find DeFit 0.9.3 insertFit Context"
                    )

            if (context > 0xF || distanceRegister > 0xF) {
                error(
                    "Unsupported DeFit 0.9.3 insertFit register layout"
                )
            }

            implementation.addInstruction(
                moveDistanceIndex + 1,
                BuilderInstruction35c(
                    Opcode.INVOKE_STATIC,
                    2,
                    context,
                    distanceRegister,
                    0,
                    0,
                    0,
                    addDistanceReference,
                ),
            )
        } else {
            /*
             * 0.8.2a calculates the distance inside k4.i.b(Context).
             *
             * Decompiled source:
             *
             *   d3.a aVarA2 = cVar.a(DataType.D);
             *   ...
             *   d3.f fVarP = aVarL3.f1882a.p(cVar5);
             *   ...
             *   fVarP.f3655o = fRound;
             *
             * Do NOT fingerprint f3655o by obfuscated name. Morphe's target
             * representation can expose a different field name even though
             * the surrounding distance block is stable.
             *
             * Instead:
             * 1. locate the DataType.D field reference,
             * 2. scan forward inside that distance-data-point block,
             * 3. use the first float IPUT after it.
             *
             * That float write is the exact distance value submitted to Fit.
             */
            val syncUtil = mutableClassDefBy(
                "Lk4/i;"
            )

            val sync = syncUtil.methods.firstOrNull {
                it.name == "b" &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0].toString() ==
                        "Landroid/content/Context;" &&
                    it.returnType == "Z"
            } ?: error(
                "Unable to find DeFit 0.8.2a sync method"
            )

            val implementation = sync.implementation
                ?: error(
                    "DeFit 0.8.2a sync method has no implementation"
                )

            val instructions =
                implementation.instructions.toList()

            val distanceTypeIndex =
                instructions.withIndex()
                    .firstOrNull { (_, instruction) ->
                        val reference =
                            (instruction as? ReferenceInstruction)
                                ?.reference as? FieldReference

                        reference?.definingClass ==
                            "Lcom/google/android/gms/fitness/data/DataType;" &&
                            reference.name == "D"
                    }
                    ?.index
                    ?: error(
                        "Unable to find DeFit 0.8.2a DataType.D block"
                    )

            /*
             * The float-value write is shortly after DataType.D and before
             * the activity-segment DataType block. A bounded forward scan
             * keeps this fingerprint specific to the distance DataPoint.
             */
            val searchEnd =
                minOf(
                    instructions.lastIndex,
                    distanceTypeIndex + 100,
                )

            val distanceWrite =
                (distanceTypeIndex + 1..searchEnd)
                    .firstNotNullOfOrNull { index ->
                        val instruction = instructions[index]

                        if (instruction.opcode != Opcode.IPUT) {
                            return@firstNotNullOfOrNull null
                        }

                        val reference =
                            (instruction as? ReferenceInstruction)
                                ?.reference as? FieldReference
                                ?: return@firstNotNullOfOrNull null

                        if (reference.type != "F") {
                            return@firstNotNullOfOrNull null
                        }

                        index to instruction
                    }
                    ?: error(
                        "Unable to find DeFit 0.8.2a distance float write " +
                            "after DataType.D"
                    )

            val distanceInstruction =
                distanceWrite.second as?
                    TwoRegisterInstruction
                    ?: error(
                        "Unable to resolve DeFit 0.8.2a distance write"
                    )

            val distanceRegister =
                distanceInstruction.registerA

            /*
             * IPUT is a 22c instruction, so both registers are guaranteed to
             * be <= v15. After fVarP.distance = fRound, the object register is
             * dead in the real 0.8.2a source; reuse it as a Context temp.
             */
            val contextTempRegister =
                distanceInstruction.registerB

            val syncContextRegister =
                implementation.registerCount - 1

            implementation.addInstruction(
                distanceWrite.first + 1,
                BuilderInstruction22x(
                    Opcode.MOVE_OBJECT_FROM16,
                    contextTempRegister,
                    syncContextRegister,
                ),
            )

            implementation.addInstruction(
                distanceWrite.first + 2,
                BuilderInstruction35c(
                    Opcode.INVOKE_STATIC,
                    2,
                    contextTempRegister,
                    distanceRegister,
                    0,
                    0,
                    0,
                    addDistanceReference,
                ),
            )
        }

        /*
         * Replace the stock notification body without touching R$string.
         *
         * 0.8.2a's generated R class is not retained in Morphe's stripped
         * bytecode, so looking up R.string.notification_text by class or
         * field can never be reliable here.
         *
         * Both supported versions build the foreground notification with an
         * AndroidX-style builder. The title and body are set by consecutive
         * invoke-virtual calls whose method signature is:
         *
         *   (Ljava/lang/CharSequence;)L<same-builder-class>;
         *
         * The FIRST such call sets the title.
         * The SECOND such call sets the body ("Tap to open DeFit").
         *
         * We inject immediately before that second call, overwrite only its
         * CharSequence argument register with our dynamic text, and leave the
         * original builder call untouched.
         */
        val currentNotificationInstructions =
            notificationImplementation.instructions.toList()

        val bodyCall =
            if (!is093) {
                currentNotificationInstructions.withIndex()
                    .firstOrNull { (_, instruction) ->
                        val reference =
                            (instruction as? ReferenceInstruction)
                                ?.reference as? MethodReference

                        reference?.definingClass == "Ly/k;" &&
                            reference.name == "c" &&
                            reference.parameterTypes
                                .map { it.toString() } ==
                                listOf(
                                    "Ljava/lang/CharSequence;"
                                ) &&
                            reference.returnType == "Ly/k;"
                    }
                    ?: error(
                        "Unable to find DeFit 0.8.2a " +
                            "notification body setter y.k.c()"
                    )
            } else {
                val calls =
                    currentNotificationInstructions.withIndex()
                        .mapNotNull { indexed ->
                            val instruction = indexed.value

                            if (
                                instruction.opcode !=
                                    Opcode.INVOKE_VIRTUAL &&
                                instruction.opcode !=
                                    Opcode.INVOKE_VIRTUAL_RANGE
                            ) {
                                return@mapNotNull null
                            }

                            val reference =
                                (instruction as?
                                    ReferenceInstruction)
                                    ?.reference as?
                                    MethodReference
                                    ?: return@mapNotNull null

                            if (
                                reference.parameterTypes
                                    .map { it.toString() } !=
                                    listOf(
                                        "Ljava/lang/CharSequence;"
                                    ) ||
                                reference.returnType !=
                                    reference.definingClass
                            ) {
                                return@mapNotNull null
                            }

                            indexed
                        }

                if (calls.size < 2) {
                    error(
                        "Unable to find DeFit 0.9.3 " +
                            "notification title/body setters"
                    )
                }

                calls[1]
            }

        val bodyTextRegister =
            when (
                val instruction = bodyCall.value
            ) {
                is com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction ->
                    // invoke-virtual {builder, text}, method(...)
                    instruction.registerD

                is com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction ->
                    // First register is the receiver; second is CharSequence.
                    instruction.startRegister + 1

                else ->
                    error(
                        "Unsupported DeFit notification body " +
                            "invoke instruction"
                    )
            }

        val insertIndex = bodyCall.index

        if (notificationContextRegister <= 0xF) {
            notificationImplementation.addInstruction(
                insertIndex,
                BuilderInstruction35c(
                    Opcode.INVOKE_STATIC,
                    1,
                    notificationContextRegister,
                    0,
                    0,
                    0,
                    0,
                    distanceTextReference,
                ),
            )
        } else {
            notificationImplementation.addInstruction(
                insertIndex,
                BuilderInstruction3rc(
                    Opcode.INVOKE_STATIC_RANGE,
                    notificationContextRegister,
                    1,
                    distanceTextReference,
                ),
            )
        }

        notificationImplementation.addInstruction(
            insertIndex + 1,
            BuilderInstruction11x(
                Opcode.MOVE_RESULT_OBJECT,
                bodyTextRegister,
            ),
        )
    }
}
