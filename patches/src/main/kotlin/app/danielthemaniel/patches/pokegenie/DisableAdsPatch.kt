package app.danielthemaniel.patches.pokegenie

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element

private const val DATA_MANAGER = "Lcom/canjin/pokegenie/pokegenie/DataManager;"

/** Containers the shared MAX banner views get attached to. */
private val AD_CONTAINER_IDS = setOf(
    "adview_layout",         // in-app banner slot (~40 screens)
    "adbackground",          // grey 50dp strip behind it
    "overlay_adview_layout", // banner slot in the floating IV overlay
)

/**
 * Collapses the now-empty 50dp banner slots so no blank strip is left behind.
 */
private val disableAdsResourcePatch = resourcePatch {
    compatibleWith(COMPATIBILITY_POKEGENIE)

    execute {
        get("res").listFiles { file -> file.isDirectory && file.name.startsWith("layout") }
            ?.flatMap { it.listFiles { file -> file.extension == "xml" }?.toList().orEmpty() }
            ?.forEach { layout ->
                val path = "res/${layout.parentFile.name}/${layout.name}"

                // Skip parsing layouts that can't contain an ad container.
                if (AD_CONTAINER_IDS.none { layout.readText().contains("id/$it\"") }) return@forEach

                document(path).use { document ->
                    val nodes = document.getElementsByTagName("*")

                    for (i in 0 until nodes.length) {
                        val element = nodes.item(i) as? Element ?: continue
                        val id = element.getAttribute("android:id")
                            .removePrefix("@+id/")
                            .removePrefix("@id/")

                        if (id in AD_CONTAINER_IDS) {
                            element.setAttribute("android:visibility", "gone")
                            element.setAttribute("android:layout_height", "0dp")
                        }
                    }
                }
            }

        // Some screens reserve space for the banner with this dimen (63dp) instead.
        document("res/values/dimens.xml").use { document ->
            val dimens = document.getElementsByTagName("dimen")

            for (i in 0 until dimens.length) {
                val element = dimens.item(i) as? Element ?: continue
                if (element.getAttribute("name") == "ad_view_height_total") {
                    element.textContent = "0dp"
                    break
                }
            }
        }
    }
}

/**
 * Stops Poke Genie from ever creating its two AppLovin MAX banners:
 *
 * - DataManager.setupAds(Activity)       -> in-app banner (MAX unit db7c95cce3a8671c)
 *                                           + AppLovin / Amazon APS SDK init
 * - DataManager.createOverlayAdViews()   -> floating overlay banner (MAX unit 0dd432992d2bdde2)
 *
 * With both returning immediately, DataManager.maxAdView / maxAdViewOverlay stay null.
 * Every placement already null-checks those, so nothing is attached and no ads are requested.
 *
 * This does not modify DataManager.disableAds. That flag is the "Remove Ads" purchase
 * and also gates paid features (scan trial limit, backup, CSV export, rename templates),
 * so those stay locked and their upsell prompts still show.
 */
@Suppress("unused")
val disableAdsPatch = bytecodePatch(
    name = "Disable Ads",
    description = "Removes the in-app and overlay banner ads without unlocking paid features.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_POKEGENIE)
    dependsOn(stripPairipPatch, disableAdsResourcePatch)

    execute {
        mutableClassDefBy(DATA_MANAGER).methods
            .filter {
                (it.name == "setupAds" &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == "Landroid/app/Activity;") ||
                    (it.name == "createOverlayAdViews" && it.parameterTypes.isEmpty())
            }
            .also { require(it.size == 2) { "Expected setupAds and createOverlayAdViews, found ${it.size}" } }
            .forEach { it.addInstructions(0, "return-void") }
    }
}
