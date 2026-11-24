package app.danielthemaniel.patches.googlemaps.extension;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Find Hub people (location sharing) on the Android Auto map.
 *
 * The car map draws Home, Work and saved places through Maps' personal-place label generator. This
 * class gives the car's generator one extra place per person sharing their location with you, each in
 * its own CUSTOM list whose photo is the person's profile picture, so Maps draws a round photo pin
 * with their name -- the same label system as Home and Work.
 *
 * Data comes from Maps' own location-sharing stores (observed as they publish), and while the car map
 * is up Maps' SharedLocationsUpdater is held polling, the way the phone's people layer holds it while
 * it is shown -- so the pins keep moving with the phone screen off.
 *
 * Every method that touches Maps' (obfuscated) classes is a stub rewritten by the patch.
 */
@SuppressWarnings({"unused", "rawtypes", "unchecked"})
public final class FindHubOnCar {
    private FindHubOnCar() {}

    private static final String TAG = "FindHubOnCar";
    /** Redraws rebuild every visible tile's labels: at most one every two seconds. */
    private static final long MIN_REDRAW_MS = 2_000;
    /** Shared trips are extrapolated along the route, so positions move between server updates. */
    private static final long TICK_MS = 30_000;
    private static final String NO_PHOTO_EMOJI = "👤"; // bust in silhouette

    private static final Handler main = new Handler(Looper.getMainLooper());

    /** The car map's generators (weak, identity), and those of them switched on now. */
    private static final Set<Object> carGenerators = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<Object> shown = Collections.newSetFromMap(new WeakHashMap<>());
    /** Our items last handed to each generator, so the next list replaces them instead of piling up. */
    private static final Map<Object, Set<Object>> ours = new WeakHashMap<>();
    /** Items by person/position/name/photo: unchanged people keep the same item between redraws. */
    private static Map<String, Object> itemCache = new HashMap<>();

    private static volatile Object peopleState;
    private static volatile Object shownPeople;
    private static volatile WeakReference<Object> updater;
    private static boolean polling;

    private static boolean pending;
    private static long lastRedraw;
    private static final Runnable redrawAll = FindHubOnCar::redrawAll;
    private static final Runnable tick = new Runnable() {
        @Override public void run() {
            if (anyShown()) {
                refresh();
                main.postDelayed(this, TICK_MS);
            }
        }
    };

    // ---------------------------------------------------------------------------------------------
    // Hooks
    // ---------------------------------------------------------------------------------------------

    /** From the location-sharing store's setter: every value it publishes. */
    public static void stored(Object value) {
        if (value == null) return;
        try {
            if (isPeopleState(value)) peopleState = value;
            else if (isShownPeople(value)) shownPeople = value;
            else return;
            refresh();
        } catch (Throwable t) {
            Log.w(TAG, "store update", t);
        }
    }

    /** From the end of the SharedLocationsUpdater's constructor. */
    public static void updater(Object value) {
        updater = new WeakReference<>(value);
        main.post(FindHubOnCar::updatePolling);
    }

    /** From the car MapManager, just before it creates its personal-place controller. */
    public static void carController(Object controller) {
        try {
            Object generator = generatorOf(controller);
            if (generator == null) return;
            synchronized (carGenerators) {
                carGenerators.add(generator);
            }
        } catch (Throwable t) {
            Log.w(TAG, "car controller", t);
        }
    }

    /** From the start of a generator's on/off switch. */
    public static void switched(Object generator, boolean on) {
        if (!isCar(generator)) return;
        boolean changed;
        synchronized (shown) {
            changed = on ? shown.add(generator) : shown.remove(generator);
        }
        if (!changed) return;
        main.post(FindHubOnCar::updatePolling);
        if (on) {
            refresh();
            main.removeCallbacks(tick);
            main.postDelayed(tick, TICK_MS);
        }
    }

    /** From the start of a generator's place setter: the car's gets the people added. */
    public static Object withPeople(Object generator, Object places) {
        if (!isCar(generator)) return places;
        try {
            List<Object> people = peopleItems();
            List<Object> out = new ArrayList<>();
            synchronized (ours) {
                Set<Object> previous = ours.get(generator);
                if (places instanceof Iterable) {
                    for (Object o : (Iterable<?>) places) {
                        if (previous == null || !previous.contains(o)) out.add(o);
                    }
                }
                Set<Object> current = Collections.newSetFromMap(new IdentityHashMap<>());
                current.addAll(people);
                ours.put(generator, current);
            }
            out.addAll(people);
            return immutable(out);
        } catch (Throwable t) {
            Log.w(TAG, "people on the car map", t);
            return places;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // People
    // ---------------------------------------------------------------------------------------------

    private static List<Object> peopleItems() {
        List<Object> out = new ArrayList<>();
        Object state = peopleState;
        if (state == null) return out;

        // The people Maps shows on its map; without that list yet, everyone with a location.
        Map<Object, Object> profiles = null;
        Object visible = shownPeople;
        if (visible != null) {
            profiles = new HashMap<>();
            List<?> list = shownPeople(visible);
            if (list != null) for (Object p : list) profiles.put(personId(p), p);
        }

        long now = System.currentTimeMillis();
        Map<String, Object> cache = new HashMap<>();
        List<?> shares = shares(state);
        if (shares == null) return out;
        for (Object share : shares) {
            Object person = personOf(share);
            if (person == null) continue;
            Object id = personId(person);
            Object profile = person;
            if (profiles != null) {
                profile = profiles.get(id);
                if (profile == null) continue; // hidden from the map, or not shown by Maps
            }
            double[] at = location(share, now);
            if (at == null || at.length != 2 || Double.isNaN(at[0]) || Double.isNaN(at[1])) continue;
            if (at[0] == 0 && at[1] == 0) continue;

            String name = firstText(personText(profile, 0), personText(person, 0), personText(profile, 1), personText(person, 1));
            String photo = firstText(personText(profile, 2), personText(person, 2));
            if (photo != null && photo.startsWith("//")) photo = "https:" + photo;

            String listId = "dtm:findhub:" + Integer.toHexString(id == null ? 0 : id.hashCode());
            String key = listId + '|' + at[0] + '|' + at[1] + '|' + name + '|' + photo;
            Object item = itemCache.get(key);
            if (item == null) {
                Object list = listInfo(listId, name == null ? "" : name, photo == null ? NO_PHOTO_EMOJI : null, photo);
                item = item(at[0], at[1], name == null ? "" : name, list);
            }
            if (item == null) continue;
            cache.put(key, item);
            out.add(item);
        }
        itemCache = cache;
        return out;
    }

    private static String firstText(String... options) {
        for (String s : options) if (s != null && !s.trim().isEmpty()) return s;
        return null;
    }

    // ---------------------------------------------------------------------------------------------
    // Drawing and polling
    // ---------------------------------------------------------------------------------------------

    private static boolean isCar(Object generator) {
        if (generator == null) return false;
        synchronized (carGenerators) {
            return carGenerators.contains(generator);
        }
    }

    private static boolean anyShown() {
        synchronized (shown) {
            return !shown.isEmpty();
        }
    }

    /** Coalesced redraw of every shown car generator. */
    static void refresh() {
        long delay;
        synchronized (shown) {
            if (pending || shown.isEmpty()) return;
            pending = true;
            delay = Math.max(0, lastRedraw + MIN_REDRAW_MS - SystemClock.elapsedRealtime());
        }
        main.postDelayed(redrawAll, delay);
    }

    private static void redrawAll() {
        List<Object> live;
        synchronized (shown) {
            pending = false;
            lastRedraw = SystemClock.elapsedRealtime();
            live = new ArrayList<>(shown);
        }
        for (Object generator : live) {
            try {
                redraw(generator);
            } catch (Throwable t) {
                Log.w(TAG, "redraw", t);
            }
        }
    }

    /** Main thread: hold Maps' location polling while a car map is up, release it after. */
    private static void updatePolling() {
        try {
            WeakReference<Object> ref = updater;
            Object u = ref == null ? null : ref.get();
            if (u == null) return;
            boolean want = anyShown();
            if (want && !polling) {
                pollStart(u);
                polling = true;
            } else if (!want && polling) {
                pollStop(u);
                polling = false;
            }
        } catch (Throwable t) {
            Log.w(TAG, "location polling", t);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Stubs: rewritten by the patch against Maps' classes. Bodies here are never used.
    // ---------------------------------------------------------------------------------------------

    /** The generator draws its place list again (through withPeople). */
    static void redraw(Object generator) {}

    /** Maps' immutable list of [places]. */
    static Object immutable(List places) { return places; }

    /** The generator of a personal-place controller, or null. */
    static Object generatorOf(Object controller) { return null; }

    /** Whether a store value is the people state (every share, with its location). */
    static boolean isPeopleState(Object value) { return false; }

    /** Whether a store value is the list of people Maps shows on its map. */
    static boolean isShownPeople(Object value) { return false; }

    /** The shares in a people state. */
    static List shares(Object state) { return null; }

    /** The people in a shown-people value. */
    static List shownPeople(Object value) { return null; }

    /** A share's person. */
    static Object personOf(Object share) { return null; }

    /** A person's id (equal for the same person across values). */
    static Object personId(Object person) { return null; }

    /** [which]: 0 display name, 1 short name, 2 photo URL; null when absent. */
    static String personText(Object person, int which) { return null; }

    /** { latitude, longitude } of a share at [nowMillis], as the phone map places it, or null. */
    static double[] location(Object share, long nowMillis) { return null; }

    /** Holds the SharedLocationsUpdater polling (the first holder also fetches at once). */
    static void pollStart(Object updater) {}

    /** Releases one hold on the SharedLocationsUpdater's polling. */
    static void pollStop(Object updater) {}

    /** A CUSTOM saved-list description; with a [photo] URL its places are drawn as round photo pins. */
    static Object listInfo(String id, String name, String emoji, String photo) { return null; }

    /** A bare-spot personal place at [lat],[lng] named [title], in [list]. */
    static Object item(double lat, double lng, String title, Object list) { return null; }
}
