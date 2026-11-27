package app.danielthemaniel.patches.defit

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element

/**
 * Hides the ad-time UI that the bypass makes meaningless.
 *
 * Both target versions share res/layout/activity_main.xml and the same ids:
 * - adText     : the "N minutes remaining / 1200" readout (what refreshes)
 * - adButton   : "watch ad for time"
 * - noAdButton : the no-ad grant button
 *
 * All three are direct children of a ConstraintLayout, so android:visibility
 * = "gone" collapses each to a zero-size point and the sync switch (0.9.3) /
 * scroll content (0.8.2a) below them reflows up to fill the gap. Their heights
 * are left untouched on purpose: for a ConstraintLayout child "0dp" means
 * match-constraints, not zero, so it would not collapse -- gone does.
 *
 * Applied as a hidden dependency of the bypass so the readout is never hidden
 * while active time is still a real, depleting limit.
 */
private val hideAdTimeUiPatch = resourcePatch {
    compatibleWith(COMPATIBILITY_DEFIT)

    execute {
        document("res/layout/activity_main.xml").use { document ->
            val nodes = document.getElementsByTagName("*")

            for (i in 0 until nodes.length) {
                val element = nodes.item(i) as? Element ?: continue

                when (element.getAttribute("android:id")) {
                    "@+id/adText", "@id/adText",
                    "@+id/adButton", "@id/adButton",
                    "@+id/noAdButton", "@id/noAdButton" ->
                        element.setAttribute("android:visibility", "gone")
                }
            }
        }
    }
}

/**
 * Removes DeFit's active-time limit entirely instead of topping it up.
 *
 * Active time is a single stored integer (seconds) in SharedPreferences,
 * read everywhere through one getter. That getter is the only gate:
 *
 * - SyncUtil.sync() subtracts elapsed time from it each cycle and, when the
 *   result reaches zero, calls setSyncStatus(false) -- i.e. the countdown is
 *   what flips the on/off switch off.
 * - The UI ("minutes remaining"), the notification, and the covert screen all
 *   render straight from the same getter, which is why the readout constantly
 *   refreshes while syncing.
 *
 * Forcing the getter to always return the version's maximum makes both of
 * those collapse: sync's remaining-time is always positive so it never
 * disables the switch, and every readout is pinned at full so nothing ticks.
 * The stored value is still written (and ignored), so no other code path
 * needs touching and the "watch ad for time" button never appears (its
 * low-time branch can no longer trigger). hideAdTimeUiPatch then removes the
 * now-static ad-time readout and buttons, leaving only the sync switch.
 *
 * DeFit 0.8.2a:
 * - getActiveTime: k4.j.b(Context)  ->  max 43,200 seconds (12 hours)
 *
 * DeFit 0.9.3:
 * - getActiveTime: Variables.getActiveTime(Context)  ->  max 72,000 seconds (20 hours)
 *
 * Version discriminator: 0.9.3 restored readable method names, so the
 * presence of MainActivity.updateUIAdTime distinguishes it from 0.8.2a
 * without relying on APK version metadata inside the execute block.
 */
val unlimitedTimeBypassPatch = bytecodePatch(
    name = "Unlimited Time Bypass",
    description = "Removes DeFit's active-time limit entirely so sync never expires and the time-remaining readout stops counting down, then hides the ad-time UI. Only the on/off sync switch matters.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_DEFIT)
    dependsOn(hideAdTimeUiPatch)

    execute {
        val is093 = mutableClassDefBy(
            "Lcom/googlefit/tester/MainActivity;"
        ).methods.any { it.name == "updateUIAdTime" }

        if (is093) {
            mutableClassDefBy(
                "Lcom/googlefit/tester/Variables;"
            ).methods
                .first { it.name == "getActiveTime" }
                .addInstructions(
                    0,
                    """
                    const v0, 0x11940
                    return v0
                    """.trimIndent()
                )
        } else {
            mutableClassDefBy(
                "Lk4/j;"
            ).methods
                .first { it.name == "b" }
                .addInstructions(
                    0,
                    """
                    const v0, 0xa8c0
                    return v0
                    """.trimIndent()
                )
        }
    }
}
