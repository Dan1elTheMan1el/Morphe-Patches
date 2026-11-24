package app.danielthemaniel.patches.googlemaps.extension;

/**
 * Picks the 3D vehicle ("navatar") location marker for navigation in more travel modes.
 *
 * Maps chooses the navigation marker from the travel mode in two places: whether the 3D vehicle may be
 * used (am: DRIVE with a vehicle picked; BICYCLE / TWO_WHEELER only behind server flags; others never) and
 * which marker mode to use (af: DRIVE -> NAVIGATION_CUSTOM_3D_CHEVRON). Both are asked with DRIVE instead
 * for the modes listed here, so the vehicle picked for driving shows up there too.
 */
@SuppressWarnings("unused")
public final class NavatarModes {
    private NavatarModes() {}

    private static final String[] MODES = {"WALK", "BICYCLE", "TWO_WHEELER", "BIKESHARING", "TAXI", "TAXICAB"};

    /** Travel modes in {@link #MODES} count as DRIVE for picking the navigation marker. */
    public static Object travel(Object travelMode) {
        if (!(travelMode instanceof Enum)) return travelMode;
        String name = ((Enum<?>) travelMode).name();
        for (String m : MODES) {
            if (m.equals(name)) {
                for (Object c : ((Enum<?>) travelMode).getDeclaringClass().getEnumConstants()) {
                    if ("DRIVE".equals(((Enum<?>) c).name())) return c;
                }
            }
        }
        return travelMode;
    }
}
