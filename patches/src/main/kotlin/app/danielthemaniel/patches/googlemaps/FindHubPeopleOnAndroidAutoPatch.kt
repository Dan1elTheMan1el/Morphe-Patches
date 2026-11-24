package app.danielthemaniel.patches.googlemaps

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.intOption
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val EXT = "Lapp/danielthemaniel/patches/googlemaps/extension/FindHubOnCar;"
private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"
private const val LIST = "Ljava/util/List;"
private const val IMMUTABLE_LIST = "Lcom/google/common/collect/ImmutableList;"

// ---- Location sharing (Find Hub people): class names per Maps build, member names are the same. ----

/**
 * The location-sharing classes of one Maps build. Members used (same names in both builds):
 *  - store.f(account Optional, storeValue): publishes a new value (people state, or shown people)
 *  - peopleState.d(): every share with you; shownPeople.c: the people Maps shows on its map
 *    (hidden and blocked left out)
 *  - share.a: person; person.a: id, .b display name, .c short name, .d photo URL, .e email (Optionals)
 *  - optional.i() isPresent, .d() get
 *  - locator.a(share, nowMillis): Optional location as the phone map draws it (extrapolated on trips)
 *  - location.d latitude, .c longitude
 *  - updater ("SharedLocationsUpdater"): q polling ref count, s account, b(account, force) fetches
 *  - featureId(JJ) and latLng(DD): what personal places are built from
 */
private class MapsBuild(
    val name: String,
    val store: String,
    val storeValue: String,
    val optional: String,
    val account: String,
    val peopleState: String,
    val shownPeople: String,
    val share: String,
    val person: String,
    val personId: String,
    val locator: String,
    val location: String,
    val updater: String,
    val featureId: String,
    val latLng: String,
)

private val MAPS_BUILDS = listOf(
    MapsBuild(
        "26.44.06.1006895251", store = "Lajro;", storeValue = "Lajtx;", optional = "Lbwgj;", account = "Layje;",
        peopleState = "Lajrh;", shownPeople = "Lajrc;", share = "Lajml;", person = "Lajmy;", personId = "Lajmj;",
        locator = "Lajpi;", location = "Lcdrm;", updater = "Lajtj;", featureId = "Lbjow;", latLng = "Lbjpd;",
    ),
    MapsBuild(
        "26.47.00.1022347503", store = "Lakdg;", storeValue = "Lakfr;", optional = "Lbwqd;", account = "Layuh;",
        peopleState = "Lakcz;", shownPeople = "Lakcv;", share = "Lajyf;", person = "Lajyr;", personId = "Lajyd;",
        locator = "Lakba;", location = "Lcecd;", updater = "Lakfd;", featureId = "Lbjvy;", latLng = "Lbjwf;",
    ),
)

// Set from the matching MapsBuild at the start of execute.
private var STORE = ""
private var STORE_VALUE = ""
private var OPTIONAL = ""
private var ACCOUNT = ""
private var PEOPLE_STATE = ""
private var SHOWN_PEOPLE = ""
private var SHARE = ""
private var PERSON = ""
private var PERSON_ID = ""
private var LOCATOR = ""
private var LOCATION = ""
private var UPDATER = ""
private var FEATURE_ID = ""
private var LATLNG = ""

private const val CAR_LABELS_TRACE = "MapManager.onCreate() - personal place labels"
private const val GENERATOR_TRACE = "PersonalPlaceLabelGenerator.onUpdateLabels"

internal val googleMapsExtensionPatch = bytecodePatch {
    compatibleWith(COMPATIBILITY_GOOGLE_MAPS)
    extendWith("extensions/googlemaps.dex")
    execute { }
}

/**
 * Draws the people sharing their location with you (Find Hub / location sharing) on the Android Auto
 * map, with their profile photo, the way the car map already draws Home, Work and saved places.
 *
 * The phone map draws people with Android views floated over the map, which never reach the car's
 * projected GL map. The car map does run Maps' own personal-place label generator (Home and Work pins),
 * and that generator can draw a place of a CUSTOM list whose description carries a photo URL as a
 * round photo pin. So:
 *
 *  - The location-sharing store's writes are observed: the people state (everyone sharing, with their
 *    last location) and the list of people Maps shows on its map (hidden or blocked people left out).
 *  - The car MapManager's personal-place controller is tagged as the car's; only its generator gets
 *    the people, so the phone map (which draws them itself) is unchanged.
 *  - That generator's place setter adds one personal place per person, each in its own CUSTOM list
 *    whose photo is the person's profile picture.
 *  - While the car generator is on, Maps' SharedLocationsUpdater is held polling, as the phone's
 *    location-sharing layer does while it is shown, so the pins move with the phone screen off.
 */
@Suppress("unused")
val findHubPeopleOnCarPatch = bytecodePatch(
    name = "Find Hub people on Android Auto",
    description = "Shows the people sharing their location with you on the Android Auto map, with their " +
        "profile photo, like Home and Work. Use with a patch that puts Maps' own map on the car screen.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_GOOGLE_MAPS)
    dependsOn(googleMapsExtensionPatch)

    val photoPinSize by intOption(
        key = "photoPinSize",
        default = 160,
        values = mapOf("Maps' own (80 px)" to 80, "Medium (120 px)" to 120, "Large (160 px)" to 160, "Extra large (240 px)" to 240),
        title = "Photo pin size",
        description = "Pixel size Maps asks for when it loads a photo pin. Maps' own 80 px looks small and blurry " +
            "on a car screen. Also applies to the photo pins of your own saved lists.",
        required = true,
    )

    execute {
        selectMapsBuild()
        checkPinnedLocationSharing()
        val labels = resolveLabelGenerator()
        hookLocationSharing()
        hookCarController(labels)
        hookGenerator(labels)
        writeBridges(labels, resolvePlaceItems())
        resizePhotoPins(photoPinSize ?: 160)
    }
}

/**
 * The personal-place label builder asks Google's image server for a photo pin's picture at a fixed
 * 80x80 px (`const/16 vX, 0x50`, then `resize(url, vX, vX, null)`), and hands the result to a remote-icon
 * label (type 22). That constant becomes [size]. Its register is overwritten right after the call, so
 * nothing else sees the change.
 */
private fun BytecodePatchContext.resizePhotoPins(size: Int) {
    if (size !in 32..512) throw PatchException("photo pin size must be 32-512 px, not $size")
    if (size == 80) return
    class Site(val type: String, val method: Method, val constIndex: Int, val register: Int)
    val sites = mutableListOf<Site>()
    classDefForEach { c ->
        for (m in c.methods) {
            val code = m.implementation?.instructions?.toList() ?: continue
            for (i in code.indices) {
                if (code[i].opcode != Opcode.INVOKE_VIRTUAL && code[i].opcode != Opcode.INVOKE_INTERFACE) continue
                val call = code[i].ref() as? MethodReference ?: continue
                if (call.returnType != STRING ||
                    call.parameterTypes.map(CharSequence::toString) != listOf(STRING, "I", "I", "Landroid/widget/ImageView\$ScaleType;")
                ) continue
                // receiver C, url D, width E, height F, scale type G: width and height share one register.
                val args = code[i] as FiveRegisterInstruction
                if (args.registerE != args.registerF) continue
                val constAt = (i - 1 downTo maxOf(0, i - 6)).firstOrNull {
                    code[it].opcode.setsRegister() && (code[it] as? OneRegisterInstruction)?.registerA == args.registerE
                } ?: continue
                if ((code[constAt] as? WideLiteralInstruction)?.wideLiteral != 80L) continue
                // The picture goes into a remote-icon label: type 22 shortly after.
                val icon = (i + 1 until minOf(code.size, i + 30)).any { (code[it] as? WideLiteralInstruction)?.wideLiteral == 22L }
                if (icon) sites += Site(c.type, m, constAt, args.registerE)
            }
        }
    }
    val site = sites.singleOrNull() ?: throw PatchException("expected one photo pin picture size, found ${sites.size}")
    mutableClassDefBy(site.type).methods.single {
        it.name == site.method.name && it.returnType == site.method.returnType &&
            params(it) == params(site.method)
    }.replaceInstruction(site.constIndex, "const/16 v${site.register}, $size")
}

// ------------------------------------------------------------------------------------------------
// Helpers
// ------------------------------------------------------------------------------------------------

private fun Instruction.ref() = (this as? ReferenceInstruction)?.reference
private fun params(m: Method) = m.parameterTypes.map(CharSequence::toString)
private fun isInstance(flags: Int) = !AccessFlags.STATIC.isSet(flags)
private fun publicFlags(flags: Int) =
    flags and (AccessFlags.PRIVATE.value or AccessFlags.PROTECTED.value).inv() or AccessFlags.PUBLIC.value

/** Makes [type] public, and its listed (non-private) methods and fields, for the extension's bridges. */
private fun BytecodePatchContext.open(type: String, methods: Set<String> = emptySet(), fields: Set<String> = emptySet()) {
    val c = mutableClassDefBy(type)
    c.accessFlags = publicFlags(c.accessFlags)
    c.methods.filter { it.name in methods }.forEach {
        if (AccessFlags.PRIVATE.isSet(it.accessFlags)) throw PatchException("$type->${it.name} is private")
        it.accessFlags = publicFlags(it.accessFlags)
    }
    c.fields.filter { it.name in fields }.forEach { it.accessFlags = publicFlags(it.accessFlags) }
}

private fun BytecodePatchContext.requireMethod(type: String, name: String, parameters: List<String>, returnType: String) {
    val c = classDefByOrNull(type) ?: throw PatchException("$type not found")
    if (c.methods.none { it.name == name && params(it) == parameters && it.returnType == returnType }) {
        throw PatchException("$type->$name(${parameters.joinToString("")})$returnType not found")
    }
}

private fun BytecodePatchContext.requireField(type: String, name: String, fieldType: String) {
    val c = classDefByOrNull(type) ?: throw PatchException("$type not found")
    if (c.fields.none { it.name == name && it.type == fieldType }) {
        throw PatchException("$type->$name:$fieldType not found")
    }
}

/** Inserts [smali] before every return-void of [method] (latest first, so indices stay valid). */
private fun MutableMethod.beforeReturns(smali: String) {
    val returns = implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }
    if (returns.isEmpty()) throw PatchException("$definingClass->$name has no return-void")
    returns.sortedDescending().forEach { addInstructions(it, smali) }
}

// ------------------------------------------------------------------------------------------------
// Location sharing
// ------------------------------------------------------------------------------------------------

/**
 * Picks the build whose updater, share and locator classes have the expected members. Obfuscated names
 * are reused across builds, so a class merely existing proves nothing; these three together do.
 */
private fun BytecodePatchContext.selectMapsBuild() {
    fun matches(b: MapsBuild): Boolean {
        val updater = classDefByOrNull(b.updater) ?: return false
        val share = classDefByOrNull(b.share) ?: return false
        val locator = classDefByOrNull(b.locator) ?: return false
        return updater.fields.any { it.name == "q" && it.type == "I" } &&
            updater.fields.any { it.name == "s" && it.type == b.account } &&
            share.fields.any { it.name == "a" && it.type == b.person } &&
            locator.methods.any { it.name == "a" && params(it) == listOf(b.share, "J") && it.returnType == b.optional }
    }
    val build = MAPS_BUILDS.firstOrNull(::matches)
        ?: throw PatchException("unsupported Maps build: supported are ${MAPS_BUILDS.joinToString { it.name }}")
    STORE = build.store
    STORE_VALUE = build.storeValue
    OPTIONAL = build.optional
    ACCOUNT = build.account
    PEOPLE_STATE = build.peopleState
    SHOWN_PEOPLE = build.shownPeople
    SHARE = build.share
    PERSON = build.person
    PERSON_ID = build.personId
    LOCATOR = build.locator
    LOCATION = build.location
    UPDATER = build.updater
    FEATURE_ID = build.featureId
    LATLNG = build.latLng
}

private fun BytecodePatchContext.checkPinnedLocationSharing() {
    requireMethod(STORE, "f", listOf(OPTIONAL, STORE_VALUE), "V")
    requireMethod(OPTIONAL, "i", emptyList(), "Z")
    requireMethod(OPTIONAL, "d", emptyList(), OBJECT)
    requireMethod(PEOPLE_STATE, "d", emptyList(), IMMUTABLE_LIST)
    requireField(SHOWN_PEOPLE, "c", IMMUTABLE_LIST)
    requireField(SHARE, "a", PERSON)
    requireField(PERSON, "a", PERSON_ID)
    for (f in listOf("b", "c", "d", "e")) requireField(PERSON, f, OPTIONAL)
    requireMethod(LOCATOR, "a", listOf(SHARE, "J"), OPTIONAL)
    requireField(LOCATION, "c", "D")
    requireField(LOCATION, "d", "D")
    requireField(UPDATER, "q", "I")
    requireField(UPDATER, "s", ACCOUNT)
    requireMethod(UPDATER, "b", listOf(ACCOUNT, "Z"), "Lcom/google/common/util/concurrent/ListenableFuture;")
    requireMethod(FEATURE_ID, "<init>", listOf("J", "J"), "V")
    requireMethod(LATLNG, "<init>", listOf("D", "D"), "V")
    // The person fields' meaning, cross-checked against the profile card, which asserts on them.
    val card = classDefForStrings("Expected profile to have a display name.", "Expected profile to have a display email.")
    val cardInit = card.methods.single { it.name == "<init>" }
    val read = cardInit.implementation!!.instructions.mapNotNull { (it.ref() as? FieldReference)?.takeIf { f -> f.definingClass == PERSON }?.name }
    if (read.firstOrNull() != "b" || "d" !in read || "e" !in read) {
        throw PatchException("$PERSON's name/photo fields moved (profile card reads $read)")
    }
}

private fun BytecodePatchContext.classDefForStrings(vararg strings: String): com.android.tools.smali.dexlib2.iface.ClassDef {
    val found = mutableListOf<com.android.tools.smali.dexlib2.iface.ClassDef>()
    classDefForEach { c ->
        val all = c.methods.flatMap { m -> m.implementation?.instructions?.mapNotNull { (it.ref() as? StringReference)?.string } ?: emptyList() }.toSet()
        if (strings.all { it in all }) found += c
    }
    return found.singleOrNull() ?: throw PatchException("expected one class with ${strings.toList()}, found ${found.map { it.type }}")
}

private fun BytecodePatchContext.hookLocationSharing() {
    open(STORE, methods = setOf("f"))
    open(PEOPLE_STATE, methods = setOf("d"))
    open(SHOWN_PEOPLE, fields = setOf("c"))
    open(SHARE, fields = setOf("a"))
    open(PERSON, fields = setOf("a", "b", "c", "d"))
    open(OPTIONAL, methods = setOf("i", "d"))
    open(LOCATOR, methods = setOf("a"))
    open(LOCATION, fields = setOf("c", "d"))
    open(UPDATER, methods = setOf("b"), fields = setOf("q", "s"))
    open(FEATURE_ID)
    open(LATLNG)

    // Every value the location-sharing stores publish (people state, shown people, ...).
    mutableClassDefBy(STORE).methods.single { it.name == "f" && params(it) == listOf(OPTIONAL, STORE_VALUE) }
        .addInstructions(0, "invoke-static/range { p2 .. p2 }, $EXT->stored($OBJECT)V")

    // The SharedLocationsUpdater, once built: held polling while the car map is up.
    mutableClassDefBy(UPDATER).methods.filter { it.name == "<init>" }.ifEmpty { throw PatchException("$UPDATER has no constructor") }
        .forEach { it.beforeReturns("invoke-static/range { p0 .. p0 }, $EXT->updater($OBJECT)V") }
}

// ------------------------------------------------------------------------------------------------
// Personal place label generator (what draws Home/Work), its owner and controller
// ------------------------------------------------------------------------------------------------

internal class Labels(
    val generator: String,
    val setter: String,
    val switch: String,
    val changed: String,
    val owner: String,
    val ownerHolder: String,
    val ownerGenerator: String,
    val accountPlaces: String,
    val controller: String,
    val controllerHolder: FieldReference,
    /** The controller's show/hide toggle, (Z)V: Maps hides personal places with it while a route is drawn. */
    val controllerToggle: String,
)

internal fun BytecodePatchContext.resolveLabelGenerator(): Labels {
    val generators = mutableListOf<String>()
    classDefForEach { c ->
        if (c.methods.any { m -> m.implementation?.instructions?.any { (it.ref() as? StringReference)?.string == GENERATOR_TRACE } == true }) {
            generators += c.type
        }
    }
    val generatorType = generators.singleOrNull() ?: throw PatchException("expected one personal place label generator, found $generators")
    val generator = mutableClassDefBy(generatorType)
    generator.accessFlags = publicFlags(generator.accessFlags)

    val setter = generator.methods.singleOrNull { it.returnType == "V" && params(it) == listOf(IMMUTABLE_LIST) }
        ?: throw PatchException("$generatorType has no single place setter")
    val switch = generator.methods.singleOrNull { it.returnType == "Z" && params(it) == listOf("Z") }
        ?: throw PatchException("$generatorType has no single on/off switch")

    // Owner: the generator's one constructor parameter; it keeps the generator and the account's places.
    val ownerType = generator.methods.filter { it.name == "<init>" }.singleOrNull()?.let { params(it) }?.singleOrNull()
        ?.takeIf { it.startsWith("L") } ?: throw PatchException("$generatorType's constructor no longer takes its owner alone")
    val ownerHolder = generator.fields.singleOrNull { isInstance(it.accessFlags) && it.type == ownerType }
        ?: throw PatchException("$generatorType keeps no single $ownerType")
    ownerHolder.accessFlags = publicFlags(ownerHolder.accessFlags)
    val owner = mutableClassDefBy(ownerType)
    owner.accessFlags = publicFlags(owner.accessFlags)
    val ownerGenerator = owner.fields.singleOrNull { isInstance(it.accessFlags) && it.type == generatorType }
        ?: throw PatchException("$ownerType keeps no single $generatorType")
    val accountPlaces = owner.fields.singleOrNull { isInstance(it.accessFlags) && it.type == IMMUTABLE_LIST }
        ?: throw PatchException("$ownerType keeps no single place list")
    ownerGenerator.accessFlags = publicFlags(ownerGenerator.accessFlags)
    accountPlaces.accessFlags = publicFlags(accountPlaces.accessFlags)

    // The owner's on and off: ()V methods that call the generator's switch with a literal 1 or 0.
    fun switchesTo(m: Method): Set<Long> {
        val code = m.implementation?.instructions?.toList() ?: return emptySet()
        val out = mutableSetOf<Long>()
        for (i in code.indices) {
            val call = code[i].ref() as? MethodReference ?: continue
            if (code[i].opcode != Opcode.INVOKE_VIRTUAL || call.definingClass != generatorType || call.name != switch.name) continue
            val register = (code[i] as FiveRegisterInstruction).registerD
            val source = (i - 1 downTo 0).map { code[it] }.firstOrNull {
                it.opcode.setsRegister() && (it as? OneRegisterInstruction)?.registerA == register
            }
            (source as? WideLiteralInstruction)?.let { out += it.wideLiteral }
        }
        return out
    }
    fun ownerSwitch(on: Long) = owner.methods.singleOrNull { it.returnType == "V" && it.parameterTypes.isEmpty() && switchesTo(it) == setOf(on) }
        ?: throw PatchException("$ownerType has no single method turning $generatorType ${if (on == 1L) "on" else "off"}")
    val enable = ownerSwitch(1).name
    val disable = ownerSwitch(0).name

    // Controller (phone and car each have one): its (Z)V shows/hides the owner; it holds the owner.
    class Found(val type: String, val holder: FieldReference, val toggle: String)
    val controllers = mutableListOf<Found>()
    classDefForEach { c ->
        for (m in c.methods) {
            if (!isInstance(m.accessFlags) || m.returnType != "V" || params(m) != listOf("Z")) continue
            val code = m.implementation?.instructions?.toList() ?: continue
            val calls = code.mapNotNull { (it.ref() as? MethodReference)?.takeIf { r -> r.definingClass == ownerType } }.map { it.name }.toSet()
            if (calls != setOf(enable, disable)) continue
            if (code.none { it.opcode == Opcode.CHECK_CAST && (it.ref() as TypeReference).type == ownerType }) continue
            val holder = code.mapNotNull { insn ->
                (insn.ref() as? FieldReference)?.takeIf { insn.opcode == Opcode.IGET_OBJECT && it.definingClass == c.type }
            }.distinct().singleOrNull() ?: continue
            controllers += Found(c.type, holder, m.name)
        }
    }
    val controller = controllers.singleOrNull()
        ?: throw PatchException("expected one $ownerType visibility toggle, found ${controllers.map { it.type }}")
    mutableClassDefBy(controller.type).let { it.accessFlags = publicFlags(it.accessFlags) }

    // The "places changed" flag: Maps sets it just before calling the setter, or drawn tiles keep old labels.
    val changed = mutableSetOf<String>()
    classDefForEach { c ->
        for (m in c.methods) {
            val code = m.implementation?.instructions?.toList() ?: continue
            for (i in code.indices) {
                val call = code[i].ref() as? MethodReference ?: continue
                if (call.definingClass != generatorType || call.name != setter.name || code[i].opcode != Opcode.INVOKE_VIRTUAL) continue
                (i - 1 downTo maxOf(0, i - 6)).mapNotNull { code[it].ref() as? MethodReference }.firstOrNull {
                    it.definingClass == generatorType && it.returnType == "V" && it.parameterTypes.map(CharSequence::toString) == listOf("Z")
                }?.let { changed += it.name }
            }
        }
    }
    val changedFlag = changed.singleOrNull() ?: throw PatchException("$generatorType's changed flag is not clear: $changed")

    return Labels(
        generatorType, setter.name, switch.name, changedFlag, ownerType, ownerHolder.name, ownerGenerator.name,
        accountPlaces.name, controller.type, controller.holder, controller.toggle,
    )
}

/** Tags the car MapManager's personal-place controller, right before it creates (turns on) its generator. */
private fun BytecodePatchContext.hookCarController(labels: Labels) {
    var hooked = 0
    val managers = mutableListOf<String>()
    classDefForEach { c ->
        if (c.methods.any { m -> m.implementation?.instructions?.any { (it.ref() as? StringReference)?.string == CAR_LABELS_TRACE } == true }) {
            managers += c.type
        }
    }
    val managerType = managers.singleOrNull() ?: throw PatchException("expected one car MapManager, found $managers")
    val manager = mutableClassDefBy(managerType)
    for (method in manager.methods) {
        val code = method.implementation?.instructions?.toList() ?: continue
        val at = code.indexOfFirst { (it.ref() as? StringReference)?.string == CAR_LABELS_TRACE }
        if (at < 0) continue
        val create = (at + 1 until code.size).firstOrNull { i ->
            val call = code[i].ref() as? MethodReference
            code[i].opcode == Opcode.INVOKE_VIRTUAL && call?.definingClass == labels.controller &&
                call.parameterTypes.isEmpty() && call.returnType == "V"
        } ?: throw PatchException("$managerType doesn't create ${labels.controller} after \"$CAR_LABELS_TRACE\"")
        val register = (code[create] as FiveRegisterInstruction).registerC
        method.addInstructions(create, "invoke-static/range { v$register .. v$register }, $EXT->carController($OBJECT)V")
        hooked++
    }
    if (hooked != 1) throw PatchException("expected one car personal-place create in $managerType, hooked $hooked")

    // dtmGenerator(): the generator of the owner this controller holds, or null.
    val holder = labels.controllerHolder
    mutableClassDefBy(labels.controller).methods.add(
        ImmutableMethod(labels.controller, "dtmGenerator", emptyList(), OBJECT, AccessFlags.PUBLIC.value, null, null, MutableMethodImplementation(3))
            .toMutable().apply {
                addInstructions(
                    0,
                    """
                        iget-object v0, p0, ${holder.definingClass}->${holder.name}:${holder.type}
                        instance-of v1, v0, ${labels.owner}
                        if-eqz v1, :none
                        check-cast v0, ${labels.owner}
                        iget-object v0, v0, ${labels.owner}->${labels.ownerGenerator}:${labels.generator}
                        return-object v0
                        :none
                        const/4 v0, 0x0
                        return-object v0
                    """,
                )
            },
    )
}

/** The generator's place setter adds the people; its switch tells us when the car map is up; dtmRefresh redraws. */
private fun BytecodePatchContext.hookGenerator(labels: Labels) {
    val generator = mutableClassDefBy(labels.generator)
    generator.methods.single { it.name == labels.setter && params(it) == listOf(IMMUTABLE_LIST) && it.returnType == "V" }
        .addInstructions(
            0,
            """
                invoke-static { p0, p1 }, $EXT->withPeople($OBJECT$OBJECT)$OBJECT
                move-result-object p1
                check-cast p1, $IMMUTABLE_LIST
            """,
        )
    generator.methods.single { it.name == labels.switch && params(it) == listOf("Z") && it.returnType == "Z" }
        .addInstructions(0, "invoke-static { p0, p1 }, $EXT->switched(${OBJECT}Z)V")
    // Redraw from the owner's own account list (never the generator's last list, which already holds people).
    generator.methods.add(
        ImmutableMethod(labels.generator, "dtmRefresh", emptyList(), "V", AccessFlags.PUBLIC.value, null, null, MutableMethodImplementation(3))
            .toMutable().apply {
                addInstructions(
                    0,
                    """
                        const/4 v1, 0x1
                        invoke-virtual { p0, v1 }, ${labels.generator}->${labels.changed}(Z)V
                        iget-object v0, p0, ${labels.generator}->${labels.ownerHolder}:${labels.owner}
                        iget-object v0, v0, ${labels.owner}->${labels.accountPlaces}:$IMMUTABLE_LIST
                        invoke-virtual { p0, v0 }, ${labels.generator}->${labels.setter}($IMMUTABLE_LIST)V
                        return-void
                    """,
                )
            },
    )
    generator.methods.filter { it.name == labels.changed || it.name == labels.setter }.forEach {
        if (!AccessFlags.PRIVATE.isSet(it.accessFlags)) it.accessFlags = publicFlags(it.accessFlags)
    }
}

// ------------------------------------------------------------------------------------------------
// Personal place items and saved-list descriptions
// ------------------------------------------------------------------------------------------------

private class PlaceItems(
    val item: String,
    val factory: String,
    val builder: String,
    val build: String,
    val listSetter: String,
    val starred: String,
    val info: String,
    val infoKind: String,
    val infoDescriptor: String,
)

private fun BytecodePatchContext.resolvePlaceItems(): PlaceItems {
    fun isEnum(type: String) = classDefByOrNull(type)?.superclass == "Ljava/lang/Enum;"
    fun enumNames(type: String) = classDefByOrNull(type)?.methods?.firstOrNull { it.name == "<clinit>" }?.implementation
        ?.instructions?.mapNotNull { (it.ref() as? StringReference)?.string }?.toSet().orEmpty()

    // A personal place: a static factory (feature id, position, title, subtitle) returns its builder.
    class Factory(val owner: String, val name: String, val builder: String)
    val factories = mutableListOf<Factory>()
    classDefForEach { c ->
        for (m in c.methods) {
            if (!AccessFlags.STATIC.isSet(m.accessFlags) || !AccessFlags.PUBLIC.isSet(m.accessFlags)) continue
            if (params(m) != listOf(FEATURE_ID, LATLNG, STRING, STRING)) continue
            if (classDefByOrNull(m.returnType)?.methods?.any { it.parameterTypes.isEmpty() && it.returnType == c.type } == true) {
                factories += Factory(c.type, m.name, m.returnType)
            }
        }
    }
    val factory = factories.singleOrNull() ?: throw PatchException("expected one personal place factory, found ${factories.map { it.owner }}")
    val builderClass = classDefByOrNull(factory.builder)!!
    val build = builderClass.methods.single { it.parameterTypes.isEmpty() && it.returnType == factory.owner }
    val listSetter = builderClass.methods.singleOrNull { it.returnType == "V" && params(it) == listOf("Ljava/util/Set;") }
        ?: throw PatchException("${factory.builder} has no single list setter")
    val factoryCode = classDefByOrNull(factory.owner)!!.methods.single {
        it.name == factory.name && params(it) == listOf(FEATURE_ID, LATLNG, STRING, STRING)
    }.implementation!!.instructions
    val starred = factoryCode.mapNotNull { it.ref() as? MethodReference }
        .filter { it.definingClass == factory.builder && it.parameterTypes.map(CharSequence::toString) == listOf("Z") }
        .map { it.name }.distinct().singleOrNull() ?: throw PatchException("${factory.builder}'s starred setter not found")

    // A saved list as Maps describes it to the generator:
    // (id, kind, int, name, 4 flags, time, ?, String, String, photo URL, emoji, photo shown, ImmutableList).
    class Info(val type: String, val kind: String, val descriptor: String)
    val infos = mutableListOf<Info>()
    classDefForEach { c ->
        for (m in c.methods) {
            if (m.name != "<init>" || !AccessFlags.PUBLIC.isSet(m.accessFlags)) continue
            val p = params(m)
            if (p.size != 16 || p[0] != STRING || p[2] != "I" || p[3] != STRING || p.subList(4, 8).any { it != "Z" } || p[8] != "J") continue
            if (p.subList(10, 14).any { it != STRING } || p[14] != "Z" || p[15] != IMMUTABLE_LIST || !isEnum(p[1])) continue
            infos += Info(c.type, p[1], p.joinToString(""))
        }
    }
    val info = infos.singleOrNull() ?: throw PatchException("expected one saved list description, found ${infos.map { it.type }}")
    if (!enumNames(info.kind).containsAll(listOf("FAVORITES", "WANT_TO_GO", "CUSTOM"))) {
        throw PatchException("${info.kind} is not the saved list kind")
    }

    open(factory.owner, methods = setOf(factory.name))
    open(factory.builder, methods = setOf(build.name, listSetter.name, starred))
    open(info.type)
    open(info.kind)

    return PlaceItems(
        factory.owner, factory.name, factory.builder, build.name, listSetter.name, starred,
        info.type, info.kind, info.descriptor,
    )
}

// ------------------------------------------------------------------------------------------------
// Extension stubs, rewritten against the resolved classes
// ------------------------------------------------------------------------------------------------

private fun BytecodePatchContext.writeBridges(labels: Labels, items: PlaceItems) {
    val extension = mutableClassDefBy(EXT)
    fun replace(name: String, parameters: List<String>, returnType: String, registers: Int, smali: String) {
        val stub = extension.methods.singleOrNull { it.name == name && params(it) == parameters && it.returnType == returnType }
            ?: throw PatchException("extension stub $name not found")
        extension.methods.remove(stub)
        extension.methods.add(
            ImmutableMethod(
                EXT, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returnType,
                AccessFlags.STATIC.value, null, null, MutableMethodImplementation(registers),
            ).toMutable().apply { addInstructions(0, smali) },
        )
    }

    replace(
        "redraw", listOf(OBJECT), "V", 1,
        """
            check-cast p0, ${labels.generator}
            invoke-virtual { p0 }, ${labels.generator}->dtmRefresh()V
            return-void
        """,
    )
    replace(
        "immutable", listOf(LIST), OBJECT, 1,
        """
            invoke-static { p0 }, $IMMUTABLE_LIST->copyOf(Ljava/util/Collection;)$IMMUTABLE_LIST
            move-result-object p0
            return-object p0
        """,
    )
    replace(
        "generatorOf", listOf(OBJECT), OBJECT, 2,
        """
            instance-of v0, p0, ${labels.controller}
            if-eqz v0, :none
            check-cast p0, ${labels.controller}
            invoke-virtual { p0 }, ${labels.controller}->dtmGenerator()$OBJECT
            move-result-object v0
            return-object v0
            :none
            const/4 v0, 0x0
            return-object v0
        """,
    )
    replace(
        "isPeopleState", listOf(OBJECT), "Z", 2,
        """
            instance-of v0, p0, $PEOPLE_STATE
            return v0
        """,
    )
    replace(
        "isShownPeople", listOf(OBJECT), "Z", 2,
        """
            instance-of v0, p0, $SHOWN_PEOPLE
            return v0
        """,
    )
    replace(
        "shares", listOf(OBJECT), LIST, 1,
        """
            check-cast p0, $PEOPLE_STATE
            invoke-virtual { p0 }, $PEOPLE_STATE->d()$IMMUTABLE_LIST
            move-result-object p0
            return-object p0
        """,
    )
    replace(
        "shownPeople", listOf(OBJECT), LIST, 1,
        """
            check-cast p0, $SHOWN_PEOPLE
            iget-object p0, p0, $SHOWN_PEOPLE->c:$IMMUTABLE_LIST
            return-object p0
        """,
    )
    replace(
        "personOf", listOf(OBJECT), OBJECT, 1,
        """
            check-cast p0, $SHARE
            iget-object p0, p0, $SHARE->a:$PERSON
            return-object p0
        """,
    )
    replace(
        "personId", listOf(OBJECT), OBJECT, 1,
        """
            check-cast p0, $PERSON
            iget-object p0, p0, $PERSON->a:$PERSON_ID
            return-object p0
        """,
    )
    // which: 0 display name, 1 short name, 2 photo URL.
    replace(
        "personText", listOf(OBJECT, "I"), STRING, 4,
        """
            check-cast p0, $PERSON
            if-nez p1, :not_name
            iget-object v0, p0, $PERSON->b:$OPTIONAL
            goto :got
            :not_name
            const/4 v1, 0x1
            if-ne p1, v1, :photo
            iget-object v0, p0, $PERSON->c:$OPTIONAL
            goto :got
            :photo
            iget-object v0, p0, $PERSON->d:$OPTIONAL
            :got
            invoke-virtual { v0 }, $OPTIONAL->i()Z
            move-result v1
            if-eqz v1, :none
            invoke-virtual { v0 }, $OPTIONAL->d()$OBJECT
            move-result-object v0
            instance-of v1, v0, $STRING
            if-eqz v1, :none
            check-cast v0, $STRING
            return-object v0
            :none
            const/4 v0, 0x0
            return-object v0
        """,
    )
    // { latitude, longitude } of the share now, as the phone map would place it, or null.
    replace(
        "location", listOf(OBJECT, "J"), "[D", 8,
        """
            check-cast p0, $SHARE
            invoke-static { p0, p1, p2 }, $LOCATOR->a(${SHARE}J)$OPTIONAL
            move-result-object v0
            invoke-virtual { v0 }, $OPTIONAL->i()Z
            move-result v1
            if-eqz v1, :none
            invoke-virtual { v0 }, $OPTIONAL->d()$OBJECT
            move-result-object v0
            instance-of v1, v0, $LOCATION
            if-eqz v1, :none
            check-cast v0, $LOCATION
            const/4 v1, 0x2
            new-array v1, v1, [D
            iget-wide v2, v0, $LOCATION->d:D
            const/4 v4, 0x0
            aput-wide v2, v1, v4
            iget-wide v2, v0, $LOCATION->c:D
            const/4 v4, 0x1
            aput-wide v2, v1, v4
            return-object v1
            :none
            const/4 v0, 0x0
            return-object v0
        """,
    )
    // Hold / release the SharedLocationsUpdater's polling, the way the phone's location-sharing layer
    // does on start/stop: the first holder also asks for a fresh state at once.
    replace(
        "pollStart", listOf(OBJECT), "V", 4,
        """
            check-cast p0, $UPDATER
            iget v0, p0, $UPDATER->q:I
            add-int/lit8 v0, v0, 0x1
            iput v0, p0, $UPDATER->q:I
            const/4 v1, 0x1
            if-ne v0, v1, :done
            iget-object v1, p0, $UPDATER->s:$ACCOUNT
            const/4 v2, 0x0
            invoke-virtual { p0, v1, v2 }, $UPDATER->b(${ACCOUNT}Z)Lcom/google/common/util/concurrent/ListenableFuture;
            :done
            return-void
        """,
    )
    replace(
        "pollStop", listOf(OBJECT), "V", 2,
        """
            check-cast p0, $UPDATER
            iget v0, p0, $UPDATER->q:I
            if-lez v0, :done
            add-int/lit8 v0, v0, -0x1
            iput v0, p0, $UPDATER->q:I
            :done
            return-void
        """,
    )
    // (id, name, emoji, photo URL): a CUSTOM list; with a photo URL it is drawn as a round photo pin.
    // Constructor arguments: id, kind, 4, name, shown=1,0,0,0, time 0, null, null, null, photo, emoji, photo shown, [].
    replace(
        "listInfo", listOf(STRING, STRING, STRING, STRING), OBJECT, 22,
        """
            const-class v1, ${items.infoKind}
            const-string v2, "CUSTOM"
            invoke-static { v1, v2 }, Ljava/lang/Enum;->valueOf(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Enum;
            move-result-object v2
            check-cast v2, ${items.infoKind}
            new-instance v0, ${items.info}
            move-object/from16 v1, p0
            const/4 v3, 0x4
            move-object/from16 v4, p1
            const/4 v5, 0x1
            const/4 v6, 0x0
            const/4 v7, 0x0
            const/4 v8, 0x0
            const-wide/16 v9, 0x0
            const/4 v11, 0x0
            const/4 v12, 0x0
            const/4 v13, 0x0
            move-object/from16 v14, p3
            move-object/from16 v15, p2
            const/16 v16, 0x0
            if-eqz v14, :no_photo
            const/16 v16, 0x1
            :no_photo
            invoke-static { }, $IMMUTABLE_LIST->of()$IMMUTABLE_LIST
            move-result-object v17
            invoke-direct/range { v0 .. v17 }, ${items.info}-><init>(${items.infoDescriptor})V
            return-object v0
        """,
    )
    // (lat, lng, title, list): a bare-spot (feature id 0:0) personal place in [list].
    replace(
        "item", listOf("D", "D", STRING, OBJECT), OBJECT, 10,
        """
            new-instance v0, $FEATURE_ID
            const-wide/16 v2, 0x0
            invoke-direct { v0, v2, v3, v2, v3 }, $FEATURE_ID-><init>(JJ)V
            new-instance v1, $LATLNG
            invoke-direct { v1, p0, p1, p2, p3 }, $LATLNG-><init>(DD)V
            invoke-static { v0, v1, p4, p4 }, ${items.item}->${items.factory}($FEATURE_ID$LATLNG$STRING$STRING)${items.builder}
            move-result-object v2
            if-eqz p5, :list_done
            invoke-static { p5 }, Ljava/util/Collections;->singleton($OBJECT)Ljava/util/Set;
            move-result-object v3
            invoke-virtual { v2, v3 }, ${items.builder}->${items.listSetter}(Ljava/util/Set;)V
            :list_done
            const/4 v3, 0x0
            invoke-virtual { v2, v3 }, ${items.builder}->${items.starred}(Z)V
            invoke-virtual { v2 }, ${items.builder}->${items.build}()${items.item}
            move-result-object v0
            return-object v0
        """,
    )
}
