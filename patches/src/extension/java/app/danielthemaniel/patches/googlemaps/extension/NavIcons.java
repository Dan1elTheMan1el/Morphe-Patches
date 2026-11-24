package app.danielthemaniel.patches.googlemaps.extension;

import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

/**
 * Keeps place icons on the map during navigation.
 *
 * Maps' client map-style enum ("VersatileMapStyle") pairs every client style with the server paint style
 * it asks the tiles for. The NAVIGATION* paint styles draw few places. Called at the end of the enum's
 * static initialiser, this points each NAVIGATION* style at the regular map's paint style instead:
 * ROADMAP, ROADMAP_DARK for low-light and dark ones, ROADMAP_SATELLITE for satellite ones. The client
 * style itself is unchanged, so navigation still picks its day/night/satellite variant as before.
 * NAVIGATION_MIN_MODE* (Maps' deliberately minimal map) is left alone.
 */
@SuppressWarnings("unused")
public final class NavIcons {
    private NavIcons() {}

    private static final String TAG = "NavIcons";

    public static void navigationStyles(Class<?> styles) {
        try {
            Object[] values = styles.getEnumConstants();
            if (values == null) return;

            // The paint style: the one instance field whose type is another enum.
            Field paint = null;
            for (Field f : styles.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (!f.getType().isEnum() || f.getType() == styles) continue;
                if (paint != null) return; // ambiguous: leave Maps as it is
                paint = f;
            }
            if (paint == null) return;
            paint.setAccessible(true);

            Map<String, Object> byName = new HashMap<>();
            for (Object v : values) byName.put(((Enum<?>) v).name(), paint.get(v));
            Object road = byName.get("ROADMAP");
            Object dark = byName.get("ROADMAP_DARK");
            Object satellite = byName.get("ROADMAP_SATELLITE");
            if (road == null || dark == null) return;

            int changed = 0;
            for (Object v : values) {
                String name = ((Enum<?>) v).name();
                if (!name.startsWith("NAVIGATION") || name.contains("MIN_MODE")) continue;
                Object target;
                if (name.contains("SATELLITE")) target = satellite;
                else if (name.contains("LOW_LIGHT") || name.contains("DARK") || name.equals("NAVIGATION_GLASSES")) target = dark;
                else target = road;
                if (target == null) continue;
                paint.set(v, target);
                changed++;
            }
            Log.i(TAG, "navigation styles drawing the full map: " + changed);
        } catch (Throwable t) {
            Log.w(TAG, "navigation styles", t);
        }
    }
}
