package app.danielthemaniel.patches.defit

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element

private const val EXTENSION =
    "Lapp/danielthemaniel/patches/defit/extension/DefitAutoStop;"

/**
 * Adds the auto-stop row (enable toggle + km target field) to the main screen.
 *
 * The two versions lay the main screen out differently, so instead of
 * hard-coding constraints the new SwitchCompat copies adText's own top/start
 * anchors -- adText marks where the ad-time block sat in both layouts, so the
 * row lands in that freed space whichever version is targeted. The km field is
 * then chained to the end of the switch.
 *
 * Ids (defit_autostop_switch / defit_autostop_km) are resolved at runtime by
 * the extension via getIdentifier, so no R reference is needed.
 */
private val autoStopUiPatch = resourcePatch {
    compatibleWith(COMPATIBILITY_DEFIT)

    execute {
        document("res/layout/activity_main.xml").use { document ->
            val nodes = document.getElementsByTagName("*")

            var adText: Element? = null
            for (i in 0 until nodes.length) {
                val element = nodes.item(i) as? Element ?: continue
                when (element.getAttribute("android:id")) {
                    "@+id/adText", "@id/adText" -> adText = element
                }
            }

            val anchor = adText ?: error(
                "Unable to find DeFit adText to anchor the auto-stop row"
            )
            val parent = anchor.parentNode

            val switch = document.createElement(
                "androidx.appcompat.widget.SwitchCompat"
            ).apply {
                setAttribute("android:id", "@+id/defit_autostop_switch")
                setAttribute("android:layout_width", "wrap_content")
                setAttribute("android:layout_height", "wrap_content")
                setAttribute("android:text", "Auto-stop at distance (km)")

                // Inherit whichever top/start anchor adText used in this layout.
                listOf(
                    "app:layout_constraintTop_toTopOf",
                    "app:layout_constraintTop_toBottomOf",
                    "app:layout_constraintStart_toStartOf",
                    "app:layout_constraintLeft_toLeftOf",
                    "android:layout_marginTop",
                    "android:layout_marginStart",
                ).forEach { attr ->
                    val value = anchor.getAttribute(attr)
                    if (value.isNotEmpty()) setAttribute(attr, value)
                }
            }
            parent.appendChild(switch)

            val kmField = document.createElement("EditText").apply {
                setAttribute("android:id", "@+id/defit_autostop_km")
                setAttribute("android:layout_width", "wrap_content")
                setAttribute("android:layout_height", "wrap_content")
                setAttribute("android:inputType", "numberDecimal")
                setAttribute("android:hint", "km")
                setAttribute("android:ems", "4")
                setAttribute("android:maxLength", "6")
                setAttribute(
                    "app:layout_constraintStart_toEndOf",
                    "@id/defit_autostop_switch",
                )
                setAttribute(
                    "app:layout_constraintTop_toTopOf",
                    "@id/defit_autostop_switch",
                )
                setAttribute(
                    "app:layout_constraintBottom_toBottomOf",
                    "@id/defit_autostop_switch",
                )
                setAttribute("android:layout_marginStart", "8dp")
            }
            parent.appendChild(kmField)
        }
    }
}

/**
 * Turns sync off automatically once the current session reaches a chosen
 * distance.
 *
 * The heavy lifting lives in the DefitAutoStop extension; this patch only wires
 * three one-line hooks and depends on Session Distance Notification, which is
 * what tracks the session distance the extension reads.
 *
 * - capture : when the sync switch is turned on, snapshot the toggle + km field
 * - restore : on resume, repopulate the toggle + km field from the saved values
 * - enforce : on each notification refresh, stop sync if the target is reached
 *
 * All three target methods share their signature across both versions; only the
 * sync-switch listener class and the notification refresh method name differ.
 */
@Suppress("unused")
val autoStopDistancePatch = bytecodePatch(
    name = "Auto Stop After Distance",
    description = "Adds a toggle and a km field to the main screen that turn sync off automatically once the session reaches the set distance.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_DEFIT)
    dependsOn(autoStopUiPatch, sessionDistanceNotificationPatch)

    extendWith("extensions/defit.dex")

    execute {
        val mainActivity = mutableClassDefBy(
            "Lcom/googlefit/tester/MainActivity;"
        )

        val is093 = mainActivity.methods.any {
            it.name == "updateUIAdTime"
        }

        val syncSwitchListenerType = if (is093) {
            "Lcom/googlefit/tester/MainActivity\$12;"
        } else {
            "Lcom/googlefit/tester/MainActivity\$l;"
        }

        // 0.9.3 NotificationService.update(Context); 0.8.2a renamed it to b().
        val notificationUpdateName = if (is093) "update" else "b"

        // Capture the toggle + km field when the sync switch is turned on.
        mutableClassDefBy(syncSwitchListenerType).methods
            .first { it.name == "onCheckedChanged" }
            .addInstructions(
                0,
                """
                invoke-virtual {p1}, Landroid/view/View;->getContext()Landroid/content/Context;
                move-result-object v0
                invoke-static {v0, p2}, $EXTENSION->captureSettings(Landroid/content/Context;Z)V
                """.trimIndent()
            )

        // Repopulate the views from saved values when the screen resumes.
        mainActivity.methods
            .first {
                it.name == "onResume" &&
                    it.parameterTypes.isEmpty() &&
                    it.returnType == "V"
            }
            .addInstructions(
                0,
                """
                invoke-static {p0}, $EXTENSION->restore(Landroid/content/Context;)V
                """.trimIndent()
            )

        // Check the target on every notification refresh while syncing.
        mutableClassDefBy("Lcom/googlefit/tester/NotificationService;").methods
            .first {
                it.name == notificationUpdateName &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0].toString() ==
                        "Landroid/content/Context;" &&
                    it.returnType == "V"
            }
            .addInstructions(
                0,
                """
                invoke-static {p1}, $EXTENSION->enforce(Landroid/content/Context;)V
                """.trimIndent()
            )
    }
}
